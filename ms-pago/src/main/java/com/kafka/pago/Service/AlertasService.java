package com.kafka.pago.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kafka.pago.DTO.AlertaFraudeDTO;
import com.kafka.pago.DTO.ResumenAlertasDTO;
import com.kafka.pago.Model.BloqueoTarjeta;
import com.kafka.pago.Repository.BloqueoTarjetaRepositorio;
import com.kafka.pago.Repository.IntentoTarjetaRepositorio;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Arma el resumen del panel de alertas. Vive fuera del controlador para que el
 * controlador sólo traduzca HTTP.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertasService {

    private final BloqueoTarjetaRepositorio bloqueoRepositorio;
    private final IntentoTarjetaRepositorio intentoRepositorio;
    private final ServicioAntifraude servicioAntifraude;
    private final Clock reloj;

    @Transactional(readOnly = true)
    public ResumenAlertasDTO obtenerResumenAlertas() {
        Instant ahora = reloj.instant();

        int bloqueosActivos = (int) bloqueoRepositorio.countByBloqueadoHastaAfter(ahora);
        int vigiladas = (int) intentoRepositorio.contarHuellasDistintas();

        List<AlertaFraudeDTO> alertas = bloqueoRepositorio.findTop20ByBloqueadoHastaAfterOrderByActualizadoEnDesc(ahora)
                .stream()
                .map(b -> aDto(b, ahora))
                .toList();

        log.info("Resumen de alertas: {} bloqueos activos, {} huellas vigiladas, {} alertas listadas",
                bloqueosActivos, vigiladas, alertas.size());
        return new ResumenAlertasDTO(bloqueosActivos, vigiladas, alertas);
    }

    /** Borra TODOS los bloqueos e intentos. Solo para demo/desarrollo. */
    @Transactional
    public int resetearTodo() {
        long bloqueos = bloqueoRepositorio.count();
        long intentos = intentoRepositorio.count();
        bloqueoRepositorio.deleteAll();
        intentoRepositorio.deleteAll();
        log.warn("RESET de alertas: borrados {} bloqueos y {} intentos", bloqueos, intentos);
        return (int) (bloqueos + intentos);
    }

    private AlertaFraudeDTO aDto(BloqueoTarjeta b, Instant ahora) {
        return new AlertaFraudeDTO(
                b.getIdCliente(),
                b.getIntentos(),
                servicioAntifraude.aHoraLocal(b.getBloqueadoHasta()),
                ServicioAntifraude.minutosRestantes(ahora, b.getBloqueadoHasta()),
                servicioAntifraude.aHoraLocal(b.getActualizadoEn()),
                HuellaTarjeta.abreviar(b.getHuella()),
                b.getUltimos4());
    }
}
