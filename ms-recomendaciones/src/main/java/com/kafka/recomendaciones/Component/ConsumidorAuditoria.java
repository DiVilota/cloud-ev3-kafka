package com.kafka.recomendaciones.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafka.recomendaciones.Exception.PayloadInvalidoException;
import com.kafka.recomendaciones.Model.AuditoriaItem;
import com.kafka.recomendaciones.Repository.AuditoriaDynamoRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * grupo-auditoria-dynamo sobre auditoria-eventos (earliest): persiste en
 * DynamoDB cada evento que ms-notificaciones trajo desde la cola RabbitMQ
 * notificaciones.auditoria. Cierra la cadena RabbitMQ -> Kafka -> DynamoDB.
 *
 * putItem es idempotente con la misma PK+SK (entidad + instante#messageId):
 * si Kafka reentrega el registro tras un rebalanceo, se sobrescribe el mismo
 * item en vez de duplicarlo. Un JSON roto va directo al DLT; una falla de
 * DynamoDB se reintenta con backoff y, si persiste, también termina en el DLT.
 */
@Slf4j
@Component
@ConditionalOnExpression("${app.dynamodb.habilitado:true} and ${app.auditoria.dynamo.habilitado:false}")
public class ConsumidorAuditoria {

    private static final DateTimeFormatter ISO_MILIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX").withZone(ZoneOffset.UTC);
    private static final long NOVENTA_DIAS_S = 90L * 24 * 3600;

    private final AuditoriaDynamoRepository repositorio;
    private final ObjectMapper json;

    public ConsumidorAuditoria(AuditoriaDynamoRepository repositorio, ObjectMapper json) {
        this.repositorio = repositorio;
        this.json = json;
    }

    @KafkaListener(
            id = "consumidor-auditoria",
            topics = "${app.auditoria.dynamo.topico:auditoria-eventos}",
            groupId = "${app.auditoria.dynamo.grupo:grupo-auditoria-dynamo}",
            containerFactory = "fabricaListenerAuditoria",
            concurrency = "1",
            clientIdPrefix = "ms-recomendaciones-auditoria")
    public void consumir(ConsumerRecord<String, String> registro) {
        AuditoriaItem item = aItem(registro, json);
        repositorio.guardar(item);
        log.info("[AUDITORIA->DYNAMO] topico={} particion={} offset={} entidad={} routingKey={} messageId={}",
                registro.topic(), registro.partition(), registro.offset(), item.getEntidad(),
                item.getRoutingKey(), item.getMessageId());
    }

    static AuditoriaItem aItem(ConsumerRecord<String, String> registro, ObjectMapper json) {
        JsonNode evento;
        try {
            evento = registro.value() == null ? null : json.readTree(registro.value());
        } catch (Exception e) {
            throw new PayloadInvalidoException("Evento de auditoría no es JSON: " + e.getMessage());
        }
        if (evento == null || !evento.isObject()) {
            throw new PayloadInvalidoException("Evento de auditoría vacío o no es un objeto JSON");
        }
        String messageId = texto(evento, "messageId");
        long ms = registro.timestamp() > 0 ? registro.timestamp() : System.currentTimeMillis();
        String entidad = registro.key() != null && !registro.key().isBlank() ? registro.key()
                : (messageId != null ? messageId : "sin-clave");

        AuditoriaItem item = new AuditoriaItem();
        item.setEntidad(entidad);
        item.setOcurridoEn(ISO_MILIS.format(Instant.ofEpochMilli(ms)) + "#"
                + (messageId != null ? messageId : registro.partition() + "-" + registro.offset()));
        item.setMessageId(messageId);
        item.setRoutingKey(texto(evento, "routingKey"));
        item.setExchange(texto(evento, "exchange"));
        item.setOrigen(texto(evento, "origen"));
        JsonNode cuerpo = evento.get("cuerpo");
        item.setCuerpo(cuerpo != null ? cuerpo.toString() : texto(evento, "cuerpoCrudo"));
        item.setTopico(registro.topic());
        item.setParticion(registro.partition());
        item.setOffset(registro.offset());
        item.setExpiraEn(ms / 1000 + NOVENTA_DIAS_S);
        return item;
    }

    private static String texto(JsonNode n, String campo) {
        JsonNode v = n.get(campo);
        return v == null || v.isNull() ? null : v.asText();
    }
}
