package com.kafka.pago.Service;

import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import com.kafka.pago.Component.ProductorIntentosPago;
import com.kafka.pago.Component.ProductorResultadosPago;
import com.kafka.pago.DTO.EstadoResultadoPago;
import com.kafka.pago.DTO.IntentoPagoDTO;
import com.kafka.pago.DTO.OrigenPago;
import com.kafka.pago.DTO.PeticionPagoDTO;
import com.kafka.pago.DTO.ResultadoPagoDTO;
import com.kafka.pago.DTO.TipoPasarela;
import com.kafka.pago.Model.BloqueoTarjeta;
import com.kafka.pago.Service.PasarelaPago.Decision;
import com.kafka.pago.Service.PasarelaPago.Redireccion;
import com.kafka.pago.Service.PasarelaPago.SolicitudCobro;
import com.kafka.pago.Service.PasarelaWebpay.ConfirmacionWebpay;

import lombok.extern.slf4j.Slf4j;

/**
 * Orquesta el flujo de pago (§4.2): bloqueo → publicar intento → pasarela →
 * publicar resultado. No conoce HTTP: devuelve resultados tipados y lanza
 * excepciones de negocio que el ManejadorErroresGlobal traduce a códigos.
 */
@Slf4j
@Service
public class ServicioPagos {

    private final ServicioAntifraude antifraude;
    private final HuellaTarjeta huellas;
    private final ProductorIntentosPago productorIntentos;
    private final ProductorResultadosPago productorResultados;
    private final PasarelaWebpay pasarelaWebpay;
    private final Map<TipoPasarela, PasarelaPago> pasarelas = new EnumMap<>(TipoPasarela.class);
    private final Clock reloj;
    private final String returnUrl;

    public ServicioPagos(ServicioAntifraude antifraude, HuellaTarjeta huellas,
                         ProductorIntentosPago productorIntentos, ProductorResultadosPago productorResultados,
                         PasarelaWebpay pasarelaWebpay, List<PasarelaPago> todas, Clock reloj,
                         @Value("${app.pagos.return-url:http://localhost:8083/api/pagos/commit}") String returnUrl) {
        this.antifraude = antifraude;
        this.huellas = huellas;
        this.productorIntentos = productorIntentos;
        this.productorResultados = productorResultados;
        this.pasarelaWebpay = pasarelaWebpay;
        this.reloj = reloj;
        this.returnUrl = returnUrl;
        todas.forEach(p -> pasarelas.put(p.tipo(), p));
    }

    /** Lo que el controlador necesita para responder POST /api/pagos/iniciar. */
    public sealed interface RespuestaInicioPago permits RedireccionWebpay, CobroInmediato {
    }

    public record RedireccionWebpay(String url, String token) implements RespuestaInicioPago {
    }

    public record CobroInmediato(EstadoResultadoPago estado, String idOrden, String ultimos4)
            implements RespuestaInicioPago {
    }

    public RespuestaInicioPago iniciarPago(PeticionPagoDTO peticion) {
        TipoPasarela tipo = peticion.getPasarela();
        // El número vive sólo en esta variable local y en la solicitud a la pasarela:
        // no entra al DTO de Kafka, ni a la base, ni a ningún log.
        String numero = tipo == TipoPasarela.SIMULADA ? peticion.getTarjeta().getNumero() : null;
        String huella = numero != null ? huellas.deTarjeta(numero) : huellas.deCuenta(peticion.getClientId());
        String ultimos4 = numero != null ? HuellaTarjeta.ultimos4(numero) : null;

        // 2. Huella bloqueada -> 429 antes de publicar o cobrar nada.
        antifraude.verificarNoBloqueada(huella);
        // 2b. Con SIMULADA la huella es la tarjeta; se revisa también la cuenta
        // del cliente, por si la bloqueó una ráfaga hecha con Webpay (donde la
        // huella ES la cuenta). Así un bloqueo vale en las dos pasarelas.
        if (numero != null) {
            antifraude.verificarNoBloqueada(huellas.deCuenta(peticion.getClientId()));
        }

        // 3. Publicar el intento y esperar el ack: sin evidencia en Kafka no se cobra (503).
        IntentoPagoDTO intento = IntentoPagoDTO.builder()
                .idIntento(UUID.randomUUID().toString())
                .idOrden(peticion.getTransactionId())
                .idCliente(peticion.getClientId())
                .huellaTarjeta(huella)
                .ultimos4(ultimos4)
                .monto(peticion.getAmount())
                .moneda("CLP")
                .pasarela(tipo)
                .timestamp(reloj.millis())
                .build();
        // Se decide ANTES de publicar (para no contar este mismo intento dos veces)
        // pero se publica igual: el intento que infringe es justamente el que el
        // consumidor necesita para registrar el bloqueo y la alerta.
        VentanaAntifraude.Decision anticipada = antifraude.anticiparDecision(huella);
        productorIntentos.publicarConConfirmacion(intento);
        if (anticipada.bloquear()) {
            throw antifraude.excepcionPorUmbral(huella, anticipada);
        }

        // 4. Cobro.
        PasarelaPago pasarela = pasarelas.get(tipo);
        if (pasarela == null) {
            throw new IllegalStateException("No hay implementación para la pasarela " + tipo);
        }
        SolicitudCobro solicitud = new SolicitudCobro(peticion.getTransactionId(), peticion.getClientId(),
                peticion.getAmount(), returnUrlConOrigen(peticion.getOrigen()), numero);

        return switch (pasarela.iniciar(solicitud)) {
            case Redireccion r -> new RedireccionWebpay(r.url(), r.token());
            case Decision d -> {
                EstadoResultadoPago estado = d.aprobado() ? EstadoResultadoPago.AUTORIZADO : EstadoResultadoPago.RECHAZADO;
                // Con SIMULADA no se llama a gestionarResultadoCallbackPago: la
                // decisión es instantánea, y limpiar la ventana en cada aprobación
                // haría invisible justo el patrón de un bot probando tarjetas
                // (muchas aprobaciones seguidas en segundos).
                productorResultados.publicar(ResultadoPagoDTO.builder()
                        .idOrden(peticion.getTransactionId())
                        .idCliente(peticion.getClientId())
                        .estado(estado)
                        .monto(peticion.getAmount())
                        .codigoAutorizacion(d.codigoAutorizacion())
                        .ultimos4(ultimos4)
                        .pasarela(TipoPasarela.SIMULADA)
                        .timestamp(reloj.millis())
                        .build());
                yield new CobroInmediato(estado, peticion.getTransactionId(), ultimos4);
            }
        };
    }

    /**
     * El origen viaja dentro del returnUrl porque es lo único que Transbank nos
     * devuelve tal cual; así el commit sabe a qué frontend volver sin guardar estado.
     */
    String returnUrlConOrigen(OrigenPago origen) {
        return UriComponentsBuilder.fromUriString(returnUrl)
                .queryParam("origen", origen.name())
                .build()
                .toUriString();
    }

    /** Commit de Webpay: confirma, ajusta el antifraude y publica el resultado. */
    public ConfirmacionWebpay confirmarWebpay(String tokenWs) {
        ConfirmacionWebpay c = pasarelaWebpay.confirmar(tokenWs);

        // La tarjeta recién la conocemos acá (Transbank devuelve los últimos 4).
        // Si el antifraude la tiene bloqueada, el cobro se anula y el pago se
        // trata como RECHAZADO: ms-carrito libera el stock y la tienda lo muestra.
        if (c.autorizado()) {
            Optional<BloqueoTarjeta> bloqueo = antifraude.bloqueoVigentePorUltimos4(c.ultimos4());
            if (bloqueo.isPresent()) {
                return rechazarPorAntifraude(tokenWs, c, bloqueo.get());
            }
        }
        boolean autorizado = c.autorizado();

        if (c.idCliente() != null && !c.idCliente().isBlank()) {
            antifraude.gestionarResultadoCallbackPago(huellas.deCuenta(c.idCliente()), autorizado);
        }

        if (autorizado) {
            log.info("Pago Webpay AUTORIZADO: orden={} monto={}", c.idOrden(), c.monto());
        } else {
            log.warn("Pago Webpay RECHAZADO por Transbank: orden={} estado={}", c.idOrden(), c.estado());
        }

        productorResultados.publicar(ResultadoPagoDTO.builder()
                .idOrden(c.idOrden())
                .idCliente(c.idCliente())
                .estado(autorizado ? EstadoResultadoPago.AUTORIZADO : EstadoResultadoPago.RECHAZADO)
                .monto(c.monto())
                .codigoAutorizacion(c.codigoAutorizacion())
                .ultimos4(c.ultimos4())
                .pasarela(TipoPasarela.WEBPAY)
                .timestamp(reloj.millis())
                .build());
        return c;
    }

    /**
     * Transbank autorizó, pero con una tarjeta bloqueada por el antifraude: se
     * pide la anulación (refund) del cobro y se publica RECHAZADO. La ventana
     * del antifraude NO se limpia: un pago con tarjeta bloqueada no prueba nada.
     */
    private ConfirmacionWebpay rechazarPorAntifraude(String tokenWs, ConfirmacionWebpay c, BloqueoTarjeta bloqueo) {
        log.warn("Pago Webpay RECHAZADO por antifraude: orden={} tarjeta ****{} bloqueada hasta {} (huella={})",
                c.idOrden(), c.ultimos4(), antifraude.aHoraLocal(bloqueo.getBloqueadoHasta()),
                HuellaTarjeta.abreviar(bloqueo.getHuella()));
        boolean anulado = pasarelaWebpay.anular(tokenWs, c.monto());
        productorResultados.publicar(ResultadoPagoDTO.builder()
                .idOrden(c.idOrden())
                .idCliente(c.idCliente())
                .estado(EstadoResultadoPago.RECHAZADO)
                .monto(c.monto())
                .ultimos4(c.ultimos4())
                .pasarela(TipoPasarela.WEBPAY)
                .timestamp(reloj.millis())
                .build());
        return new ConfirmacionWebpay(ConfirmacionWebpay.BLOQUEADA_ANTIFRAUDE, c.idOrden(), c.idCliente(), c.monto(),
                anulado ? "ANULADO" : null, c.fecha(), c.ultimos4());
    }

    /**
     * El cliente abandonó el formulario de Webpay. Cuenta como no exitoso (la
     * ventana NO se limpia) y se publica ANULADO para que ms-carrito libere el
     * stock del pedido. Sin orden de compra no hay a quién avisarle.
     */
    public void registrarAnulacionWebpay(String idOrden, String idCliente) {
        if (idCliente != null && !idCliente.isBlank()) {
            antifraude.gestionarResultadoCallbackPago(huellas.deCuenta(idCliente), false);
        }
        if (idOrden == null || idOrden.isBlank()) {
            log.warn("Anulación de Webpay sin orden de compra: no se publica resultado");
            return;
        }
        productorResultados.publicar(ResultadoPagoDTO.builder()
                .idOrden(idOrden)
                .idCliente(idCliente)
                .estado(EstadoResultadoPago.ANULADO)
                .pasarela(TipoPasarela.WEBPAY)
                .timestamp(reloj.millis())
                .build());
    }
}
