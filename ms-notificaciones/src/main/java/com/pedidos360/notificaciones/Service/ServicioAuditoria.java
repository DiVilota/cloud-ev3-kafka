package com.pedidos360.notificaciones.Service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.pedidos360.notificaciones.Component.PuenteAuditoriaKafka;

import lombok.extern.slf4j.Slf4j;

/**
 * Auditoría de TODO lo que pasa por el exchange (binding "#"). Demuestra que
 * una misma publicación llega a dos colas distintas por patrón (cliente y
 * auditoría) sin que el productor lo sepa.
 *
 * Además del log, cada evento se reenvía al tópico Kafka auditoria-eventos
 * (PuenteAuditoriaKafka) cuando app.auditoria.kafka.habilitado=true. El puente
 * es opcional (ObjectProvider) para que las pruebas corran sin broker Kafka.
 */
@Slf4j
@Service
public class ServicioAuditoria {

    /** El log no necesita el cuerpo completo; evita líneas de varios KB. */
    private static final int MAX_CUERPO_LOG = 500;

    private final ObjectProvider<PuenteAuditoriaKafka> puenteKafka;

    public ServicioAuditoria(ObjectProvider<PuenteAuditoriaKafka> puenteKafka) {
        this.puenteKafka = puenteKafka;
    }

    public void registrar(String routingKey, String messageId, String cuerpo) {
        registrar(null, routingKey, messageId, cuerpo);
    }

    public void registrar(String exchange, String routingKey, String messageId, String cuerpo) {
        String recortado = cuerpo == null ? "" : cuerpo.length() > MAX_CUERPO_LOG
                ? cuerpo.substring(0, MAX_CUERPO_LOG) + "...(recortado)"
                : cuerpo;
        log.info("[AUDITORIA] routingKey={} messageId={} cuerpo={}", routingKey, messageId, recortado);
        // Si Kafka no confirma, la excepción sube al listener y el reintento del
        // consumidor vuelve a entregar el mensaje: no se pierde la auditoría.
        puenteKafka.ifAvailable(p -> p.reenviar(exchange, routingKey, messageId, cuerpo));
    }
}
