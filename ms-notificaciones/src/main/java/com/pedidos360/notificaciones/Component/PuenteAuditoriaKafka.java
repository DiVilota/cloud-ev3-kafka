package com.pedidos360.notificaciones.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.extern.slf4j.Slf4j;

/**
 * Puente RabbitMQ -> Kafka de la auditoría.
 *
 * La cola notificaciones.auditoria (binding "#") recibe una copia de TODO lo
 * que se publica en el exchange de notificaciones. RabbitMQ la entrega y la
 * borra al confirmar: sirve para reaccionar, no para guardar historia. Kafka sí
 * guarda: el tópico auditoria-eventos conserva cada evento 90 días, se puede
 * releer desde el inicio (replay) y lo pueden consumir varios grupos sin
 * quitárselo al otro (hoy: ms-recomendaciones, que lo persiste en DynamoDB).
 *
 * Envío SÍNCRONO con acks=all: el listener de Rabbit sólo confirma el mensaje
 * cuando Kafka confirmó la escritura. Si Kafka no responde se lanza la
 * excepción y el reintento del consumidor vuelve a intentarlo, así un corte del
 * broker no hace desaparecer eventos de auditoría.
 *
 * Key del registro: idPedido si viene (todos los eventos de un pedido quedan en
 * la misma partición, en orden), si no usuarioId, si no el messageId.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.auditoria.kafka.habilitado", havingValue = "true")
public class PuenteAuditoriaKafka {

    static final String ORIGEN = "rabbitmq:notificaciones.auditoria";

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;
    private final Clock reloj;
    private final String topico;
    private final long timeoutSegundos;

    public PuenteAuditoriaKafka(KafkaTemplate<String, String> kafka, ObjectMapper json,
                                @Value("${app.auditoria.kafka.topico:auditoria-eventos}") String topico,
                                @Value("${app.auditoria.kafka.timeout-confirmacion-segundos:5}") long timeoutSegundos) {
        this.kafka = kafka;
        this.json = json;
        this.reloj = Clock.systemUTC();
        this.topico = topico;
        this.timeoutSegundos = timeoutSegundos;
    }

    public void reenviar(String exchange, String routingKey, String messageId, String cuerpo) {
        JsonNode cuerpoJson = leer(cuerpo);
        ObjectNode evento = json.createObjectNode();
        evento.put("origen", ORIGEN);
        evento.put("exchange", exchange);
        evento.put("routingKey", routingKey);
        evento.put("messageId", messageId);
        evento.put("recibidoEn", Instant.now(reloj).toString());
        if (cuerpoJson != null) {
            evento.set("cuerpo", cuerpoJson);
        } else {
            // Lo mal formado también se audita: se guarda tal cual, como texto.
            evento.put("cuerpoCrudo", cuerpo);
        }
        String key = clave(cuerpoJson, messageId, routingKey);

        ProducerRecord<String, String> registro = new ProducerRecord<>(topico, key, evento.toString());
        registro.headers().add("rabbit-routing-key", bytes(routingKey));
        registro.headers().add("rabbit-message-id", bytes(messageId));
        registro.headers().add("origen", bytes(ORIGEN));
        try {
            SendResult<String, String> r = kafka.send(registro).get(timeoutSegundos, TimeUnit.SECONDS);
            log.info("[AUDITORIA->KAFKA] topico={} particion={} offset={} key={} routingKey={} messageId={}",
                    topico, r.getRecordMetadata().partition(), r.getRecordMetadata().offset(), key, routingKey, messageId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrumpido esperando a Kafka", e);
        } catch (Exception e) {
            log.error("[AUDITORIA->KAFKA] Kafka no confirmó messageId={} routingKey={}: {}", messageId, routingKey, e.getMessage());
            throw new IllegalStateException("Kafka no confirmó el evento de auditoría " + messageId, e);
        }
    }

    private JsonNode leer(String cuerpo) {
        if (cuerpo == null || cuerpo.isBlank()) {
            return null;
        }
        try {
            JsonNode n = json.readTree(cuerpo);
            return n != null && n.isObject() ? n : null;
        } catch (Exception e) {
            return null;
        }
    }

    static String clave(JsonNode cuerpo, String messageId, String routingKey) {
        if (cuerpo != null) {
            for (String campo : new String[] {"idPedido", "usuarioId"}) {
                JsonNode v = cuerpo.get(campo);
                if (v != null && v.isTextual() && !v.asText().isBlank()) {
                    return v.asText();
                }
            }
        }
        if (messageId != null && !messageId.isBlank()) {
            return messageId;
        }
        return routingKey;
    }

    private static byte[] bytes(String s) {
        return (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
    }
}
