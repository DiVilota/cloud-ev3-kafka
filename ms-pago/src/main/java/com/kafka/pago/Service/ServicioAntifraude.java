package com.kafka.pago.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kafka.pago.Config.AntifraudeProperties;
import com.kafka.pago.DTO.IntentoPagoDTO;
import com.kafka.pago.Exception.TarjetaBloqueadaException;
import com.kafka.pago.Model.AlertaFraude;
import com.kafka.pago.Model.BloqueoTarjeta;
import com.kafka.pago.Repository.AlertaFraudeRepositorio;
import com.kafka.pago.Repository.BloqueoTarjetaRepositorio;
import com.kafka.pago.Repository.IntentoTarjetaRepositorio;
import com.kafka.pago.Model.IntentoTarjeta;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Lógica del antifraude por huella de tarjeta. El @KafkaListener sólo valida y
 * delega acá (§3.3): la regla no sabe que existe Kafka y se puede invocar y
 * probar sin broker.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServicioAntifraude {

    private final IntentoTarjetaRepositorio intentoRepositorio;
    private final BloqueoTarjetaRepositorio bloqueoRepositorio;
    private final AlertaFraudeRepositorio alertaRepositorio;
    private final VentanaAntifraude ventana;
    private final AntifraudeProperties propiedades;
    private final Clock reloj;

    @Transactional(readOnly = true)
    public Optional<BloqueoTarjeta> bloqueoVigente(String huella) {
        Instant ahora = reloj.instant();
        return bloqueoRepositorio.findById(huella).filter(b -> b.estaVigente(ahora));
    }

    /**
     * Bloqueo vigente que coincide con los últimos 4 dígitos de una tarjeta.
     *
     * Con Webpay, ms-pago nunca ve el número: Transbank lo captura en su
     * formulario (PCI DSS) y en el commit sólo devuelve los últimos 4. Es la
     * única forma de reconocer en la tienda una tarjeta que el antifraude ya
     * bloqueó (por ejemplo con la ráfaga de la consola en modo simulado). Puede
     * dar falsos positivos entre tarjetas que comparten terminación, pero sólo
     * mientras dura el bloqueo (30 min): se prefiere rechazar de más a cobrar
     * con una tarjeta marcada como fraude.
     */
    @Transactional(readOnly = true)
    public Optional<BloqueoTarjeta> bloqueoVigentePorUltimos4(String ultimos4) {
        if (ultimos4 == null || ultimos4.isBlank()) {
            return Optional.empty();
        }
        String digitos = HuellaTarjeta.normalizar(ultimos4);
        if (digitos.length() < 4) {
            return Optional.empty();
        }
        return bloqueoRepositorio.findFirstByUltimos4AndBloqueadoHastaAfterOrderByBloqueadoHastaDesc(
                digitos.substring(digitos.length() - 4), reloj.instant());
    }

    /** Paso 2 del flujo HTTP: si la huella está bloqueada, se corta con 429 antes de publicar nada. */
    @Transactional(readOnly = true)
    public void verificarNoBloqueada(String huella) {
        Optional<BloqueoTarjeta> bloqueo = bloqueoVigente(huella);
        if (bloqueo.isPresent()) {
            BloqueoTarjeta b = bloqueo.get();
            log.warn("Intento rechazado con 429: huella={} ultimos4={} bloqueada hasta {}",
                    HuellaTarjeta.abreviar(huella), b.getUltimos4(), b.getBloqueadoHasta());
            throw new TarjetaBloqueadaException(aHoraLocal(b.getBloqueadoHasta()), b.getIntentos(),
                    minutosRestantes(reloj.instant(), b.getBloqueadoHasta()));
        }
    }

    /**
     * Anticipa en el flujo HTTP la decisión que tomará el consumidor.
     *
     * Sin esto el bloqueo llega un intento tarde: el consumidor decide de forma
     * asíncrona, así que cuando procesa el 4.º intento la respuesta HTTP de ese
     * mismo intento ya salió con 200 y el cobro ya partió. El enunciado pide
     * bloquear LA transacción que infringe, no la siguiente.
     *
     * Se cuenta con la misma ventana y los mismos datos que mantiene el
     * consumidor (los intentos ya procesados): no es una segunda regla, es leer
     * el estado que Kafka ya construyó. Devuelve la decisión para que el
     * llamador publique el intento igual (es un intento real y el consumidor
     * debe registrarlo, bloquear y alertar) y recién después responda 429.
     */
    @Transactional(readOnly = true)
    public VentanaAntifraude.Decision anticiparDecision(String huella) {
        Instant ahora = reloj.instant();
        List<Instant> previos = intentoRepositorio.instantesEnVentana(huella, ventana.inicioVentana(ahora), ahora);
        return ventana.evaluar(previos, ahora);
    }

    /** 429 para el intento que infringe, con el bloqueo que el consumidor está por registrar. */
    public TarjetaBloqueadaException excepcionPorUmbral(String huella, VentanaAntifraude.Decision decision) {
        Instant hasta = reloj.instant().plus(Duration.ofMinutes(propiedades.getBloqueoMinutos()));
        log.warn("Intento rechazado con 429: huella={} haría {} intentos en {} s (umbral {})",
                HuellaTarjeta.abreviar(huella), decision.intentosEnVentana(),
                ventana.getVentana().toSeconds(), ventana.getUmbral());
        return new TarjetaBloqueadaException(aHoraLocal(hasta), decision.intentosEnVentana(),
                propiedades.getBloqueoMinutos());
    }

    /**
     * Evalúa un intento con la ventana deslizante real.
     *
     * El instante que se cuenta es el del EVENTO (timestamp del DTO), no la hora
     * en que el consumidor lo procesa: si el consumidor se atrasa o un mensaje se
     * reintenta, una ráfaga no se "comprime" artificialmente ni una serie lenta
     * parece ráfaga. Por la misma razón el bloqueo dura desde el intento que
     * infringe: si se reprocesa un tópico viejo, los bloqueos resultantes ya
     * nacen vencidos en vez de castigar hoy a una tarjeta por algo de hace días.
     */
    @Transactional
    public VentanaAntifraude.Decision procesarIntento(IntentoPagoDTO intento) {
        String huella = intento.getHuellaTarjeta();
        String huellaCorta = HuellaTarjeta.abreviar(huella);

        if (intentoRepositorio.existsByIdIntento(intento.getIdIntento())) {
            // Entrega "al menos una vez": el mismo intento puede llegar dos veces
            // (p. ej. tras un rebalanceo). Contarlo de nuevo inflaría la ventana.
            log.warn("Intento {} ya registrado (huella={}): se ignora el duplicado", intento.getIdIntento(), huellaCorta);
            return new VentanaAntifraude.Decision(0, false);
        }

        Instant instante = Instant.ofEpochMilli(intento.getTimestamp());
        List<Instant> previos = intentoRepositorio.instantesEnVentana(huella, ventana.inicioVentana(instante), instante);
        VentanaAntifraude.Decision decision = ventana.evaluar(previos, instante);

        intentoRepositorio.save(new IntentoTarjeta(null, intento.getIdIntento(), huella, instante));

        if (decision.bloquear()) {
            registrarBloqueo(intento, decision, instante);
        } else {
            log.info("Intento {} huella={} cliente={}: {} de {} permitidos en {} s",
                    intento.getIdIntento(), huellaCorta, intento.getIdCliente(),
                    decision.intentosEnVentana(), ventana.getUmbral(), ventana.getVentana().toSeconds());
        }
        return decision;
    }

    private void registrarBloqueo(IntentoPagoDTO intento, VentanaAntifraude.Decision decision, Instant instante) {
        Instant ahora = reloj.instant();
        Instant hasta = instante.plus(Duration.ofMinutes(propiedades.getBloqueoMinutos()));

        BloqueoTarjeta bloqueo = bloqueoRepositorio.findById(intento.getHuellaTarjeta()).orElseGet(() -> {
            BloqueoTarjeta nuevo = new BloqueoTarjeta();
            nuevo.setHuella(intento.getHuellaTarjeta());
            return nuevo;
        });
        boolean yaVigente = bloqueo.estaVigente(ahora);

        bloqueo.setUltimos4(intento.getUltimos4());
        bloqueo.setIdCliente(intento.getIdCliente());
        bloqueo.setIntentos(decision.intentosEnVentana());
        if (bloqueo.getBloqueadoHasta() == null || hasta.isAfter(bloqueo.getBloqueadoHasta())) {
            bloqueo.setBloqueadoHasta(hasta);
        }
        bloqueo.setActualizadoEn(ahora);
        bloqueoRepositorio.save(bloqueo);

        if (yaVigente) {
            // Sólo pasa si varios intentos entraron antes de que el primer bloqueo
            // fuera visible para el HTTP: se actualiza el conteo sin duplicar la alerta.
            log.warn("Huella={} ya estaba bloqueada; intentos en ventana ahora {}",
                    HuellaTarjeta.abreviar(intento.getHuellaTarjeta()), decision.intentosEnVentana());
            return;
        }

        AlertaFraude alerta = new AlertaFraude();
        alerta.setHuella(intento.getHuellaTarjeta());
        alerta.setUltimos4(intento.getUltimos4());
        alerta.setIdCliente(intento.getIdCliente());
        alerta.setIntentos(decision.intentosEnVentana());
        alerta.setCreadaEn(ahora);
        alertaRepositorio.save(alerta);

        log.error("ALERTA FRAUDE: tarjeta huella={} ultimos4={} cliente={} pasarela={} hizo {} intentos en {} s "
                        + "(umbral {}). Bloqueada hasta {}",
                HuellaTarjeta.abreviar(intento.getHuellaTarjeta()), intento.getUltimos4(), intento.getIdCliente(),
                intento.getPasarela(), decision.intentosEnVentana(), ventana.getVentana().toSeconds(),
                ventana.getUmbral(), aHoraLocal(bloqueo.getBloqueadoHasta()));
    }

    /**
     * Conservado del flujo anterior, ahora sobre la huella. Un pago AUTORIZADO
     * por Transbank prueba que el titular pasó la autenticación del emisor: se
     * limpia la ventana de esa huella y se levanta un bloqueo que hubiera quedado
     * (criterio previo del equipo). Un rechazo o anulación NO limpia nada: es
     * justamente la señal que el antifraude necesita.
     */
    @Transactional
    public void gestionarResultadoCallbackPago(String huella, boolean esExitoso) {
        String huellaCorta = HuellaTarjeta.abreviar(huella);
        if (!esExitoso) {
            log.info("Pago no exitoso para huella={}: la ventana se conserva", huellaCorta);
            return;
        }
        int borrados = intentoRepositorio.borrarPorHuella(huella);
        bloqueoRepositorio.findById(huella).ifPresent(b -> {
            b.setBloqueadoHasta(null);
            b.setIntentos(0);
            b.setActualizadoEn(reloj.instant());
            bloqueoRepositorio.save(b);
        });
        log.info("Pago exitoso para huella={}: {} intentos de la ventana reiniciados", huellaCorta, borrados);
    }

    /**
     * La ventana mira 10 s hacia atrás; guardar más de una hora sólo engorda la
     * tabla y vuelve lenta la consulta. Corre cada 5 minutos.
     */
    @Scheduled(fixedDelayString = "${app.antifraude.limpieza-ms:300000}",
               initialDelayString = "${app.antifraude.limpieza-ms:300000}")
    @Transactional
    public int limpiarIntentosAntiguos() {
        Instant limite = reloj.instant().minus(Duration.ofMinutes(propiedades.getRetencionIntentosMinutos()));
        int borrados = intentoRepositorio.borrarAnterioresA(limite);
        if (borrados > 0) {
            log.info("Limpieza antifraude: {} intentos anteriores a {} eliminados", borrados, limite);
        }
        return borrados;
    }

    /** Público a propósito: un método de paquete invocado sobre el proxy de Spring no llega al objeto real. */
    public LocalDateTime aHoraLocal(Instant instante) {
        return instante == null ? null : LocalDateTime.ofInstant(instante, reloj.getZone()).truncatedTo(ChronoUnit.SECONDS);
    }

    /** Redondea hacia arriba: con 20 s restantes la consola debe mostrar 1 min, no 0 ("habilitado"). */
    static long minutosRestantes(Instant ahora, Instant hasta) {
        long segundos = Duration.between(ahora, hasta).toSeconds();
        return segundos <= 0 ? 0 : (segundos + 59) / 60;
    }
}
