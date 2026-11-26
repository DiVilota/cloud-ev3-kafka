package com.kafka.pago.Controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kafka.pago.DTO.ResumenAlertasDTO;
import com.kafka.pago.Service.AlertasService;

import lombok.RequiredArgsConstructor;

/**
 * GET /api/alertas: bloqueos vigentes por huella de tarjeta para el panel de
 * la consola. La forma de la respuesta no cambia (la consola la lee).
 *
 * DELETE /api/alertas: resetea bloqueos e intentos. Existe para poder repetir
 * la demostración de antifraude sin reiniciar el contenedor ni tocar la BD a
 * mano; no es una operación de producción.
 */
@RestController
@RequestMapping("/api/alertas")
@RequiredArgsConstructor
public class AlertasController {

    private final AlertasService alertasService;

    @GetMapping
    public ResponseEntity<ResumenAlertasDTO> obtenerAlertas() {
        return ResponseEntity.ok(alertasService.obtenerResumenAlertas());
    }

    /** Resetea todos los bloqueos e intentos. Solo para demo/desarrollo. */
    @DeleteMapping
    public ResponseEntity<String> resetearAlertas() {
        int borrados = alertasService.resetearTodo();
        return ResponseEntity.ok("Reseteados " + borrados + " bloqueos e intentos");
    }
}
