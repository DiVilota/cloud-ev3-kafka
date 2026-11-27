package com.kafka.recomendaciones.Component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafka.recomendaciones.Exception.PayloadInvalidoException;
import com.kafka.recomendaciones.Model.AuditoriaItem;

class ConsumidorAuditoriaTest {

    private final ObjectMapper json = new ObjectMapper();

    private static ConsumerRecord<String, String> registro(String key, String valor) {
        return new ConsumerRecord<>("auditoria-eventos", 2, 41L, 1795800000000L, TimestampType.CREATE_TIME,
                0, 0, key, valor, new RecordHeaders(), Optional.empty());
    }

    @Test
    void armaElItemConPkDeLaEntidadYSkOrdenable() {
        AuditoriaItem item = ConsumidorAuditoria.aItem(registro("P-21",
                "{\"origen\":\"rabbitmq:notificaciones.auditoria\",\"routingKey\":\"pedido.pagado\","
                        + "\"messageId\":\"m-1\",\"cuerpo\":{\"idPedido\":\"P-21\"}}"), json);

        assertThat(item.getEntidad()).isEqualTo("P-21");
        assertThat(item.getOcurridoEn()).isEqualTo("2026-11-27T17:20:00.000Z#m-1");
        assertThat(item.getRoutingKey()).isEqualTo("pedido.pagado");
        assertThat(item.getCuerpo()).contains("P-21");
        assertThat(item.getParticion()).isEqualTo(2);
        assertThat(item.getOffset()).isEqualTo(41L);
        assertThat(item.getExpiraEn()).isEqualTo(1795800000L + 90L * 24 * 3600);
    }

    @Test
    void unJsonRotoEsPayloadInvalido() {
        assertThatThrownBy(() -> ConsumidorAuditoria.aItem(registro("k", "no-json{"), json))
                .isInstanceOf(PayloadInvalidoException.class);
    }
}
