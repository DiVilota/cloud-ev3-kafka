package com.pedidos360.notificaciones.Component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class PuenteAuditoriaKafkaTest {

    private final ObjectMapper json = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

    private final PuenteAuditoriaKafka puente = new PuenteAuditoriaKafka(kafka, json, "auditoria-eventos", 1);

    @Test
    @SuppressWarnings("unchecked")
    void reenviaAlTopicoConKeyDelPedidoYCabecerasDeRabbit() throws Exception {
        ProducerRecord<String, String> enviado = new ProducerRecord<>("auditoria-eventos", "x");
        RecordMetadata meta = new RecordMetadata(new TopicPartition("auditoria-eventos", 1), 0, 7, 0, 0, 0);
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(new SendResult<>(enviado, meta)));

        puente.reenviar("pedidos360.notificaciones", "pedido.pagado", "m-1",
                "{\"idPedido\":\"P-7\",\"usuarioId\":\"ana@correo.cl\"}");

        ArgumentCaptor<ProducerRecord<String, String>> registro = ArgumentCaptor.forClass(ProducerRecord.class);
        org.mockito.Mockito.verify(kafka).send(registro.capture());
        assertThat(registro.getValue().topic()).isEqualTo("auditoria-eventos");
        assertThat(registro.getValue().key()).isEqualTo("P-7");
        assertThat(new String(registro.getValue().headers().lastHeader("rabbit-routing-key").value()))
                .isEqualTo("pedido.pagado");
        JsonNode evento = json.readTree(registro.getValue().value());
        assertThat(evento.get("routingKey").asText()).isEqualTo("pedido.pagado");
        assertThat(evento.get("origen").asText()).isEqualTo(PuenteAuditoriaKafka.ORIGEN);
        assertThat(evento.get("cuerpo").get("idPedido").asText()).isEqualTo("P-7");
    }

    @Test
    @SuppressWarnings("unchecked")
    void siKafkaNoConfirmaFallaParaQueRabbitReintente() {
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker caído")));

        assertThatThrownBy(() -> puente.reenviar("ex", "pedido.creado", "m-2", "no es json"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void laKeyCaeAUsuarioYLuegoAlMessageId() throws Exception {
        assertThat(PuenteAuditoriaKafka.clave(json.readTree("{\"usuarioId\":\"u1\"}"), "m", "rk")).isEqualTo("u1");
        assertThat(PuenteAuditoriaKafka.clave(null, "m", "rk")).isEqualTo("m");
        assertThat(PuenteAuditoriaKafka.clave(null, null, "rk")).isEqualTo("rk");
    }
}
