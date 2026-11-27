package com.kafka.recomendaciones.Controller;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.kafka.recomendaciones.Model.AuditoriaItem;
import com.kafka.recomendaciones.Repository.AuditoriaDynamoRepository;

import lombok.RequiredArgsConstructor;

/**
 * Lectura de la tabla DynamoDB pedidos360-auditoria (evidencia de la cadena
 * RabbitMQ -> Kafka -> DynamoDB).
 *   GET /api/auditoria?limite=20        muestra de la tabla
 *   GET /api/auditoria/{entidad}        todo lo de un pedido (P-21) o usuario
 */
@RestController
@RequestMapping("/api/auditoria")
@RequiredArgsConstructor
@ConditionalOnExpression("${app.dynamodb.habilitado:true} and ${app.auditoria.dynamo.habilitado:false}")
public class AuditoriaController {

    private static final int LIMITE_MAXIMO = 100;

    private final AuditoriaDynamoRepository repositorio;

    @GetMapping
    public ResponseEntity<List<AuditoriaItem>> muestra(@RequestParam(defaultValue = "20") int limite) {
        return ResponseEntity.ok(repositorio.muestra(acotar(limite)));
    }

    @GetMapping("/{entidad}")
    public ResponseEntity<List<AuditoriaItem>> deEntidad(@PathVariable String entidad,
                                                         @RequestParam(defaultValue = "50") int limite) {
        return ResponseEntity.ok(repositorio.deEntidad(entidad, acotar(limite)));
    }

    private static int acotar(int limite) {
        return Math.max(1, Math.min(limite, LIMITE_MAXIMO));
    }
}
