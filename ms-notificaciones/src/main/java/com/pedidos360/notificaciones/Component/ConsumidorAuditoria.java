package com.pedidos360.notificaciones.Component;

import com.pedidos360.notificaciones.Service.ServicioAuditoria;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Consumidor de notificaciones.auditoria (binding "#": recibe todo). Cada
 * mensaje se registra y se reenvía al tópico Kafka auditoria-eventos.
 *
 * Recibe el Message crudo (fabricaMensajesCrudos): la auditoría tiene que
 * registrar también lo que viene mal formado, así que no puede depender de que
 * el JSON se convierta a un DTO.
 */
@Component
@RequiredArgsConstructor
public class ConsumidorAuditoria {

    private final ServicioAuditoria servicioAuditoria;

    @RabbitListener(id = "consumidor-auditoria",
            queues = "#{@topologiaRabbitProperties.colaAuditoria}",
            containerFactory = "fabricaMensajesCrudos")
    public void auditar(Message mensaje) {
        servicioAuditoria.registrar(
                mensaje.getMessageProperties().getReceivedExchange(),
                mensaje.getMessageProperties().getReceivedRoutingKey(),
                mensaje.getMessageProperties().getMessageId(),
                new String(mensaje.getBody(), StandardCharsets.UTF_8));
    }
}
