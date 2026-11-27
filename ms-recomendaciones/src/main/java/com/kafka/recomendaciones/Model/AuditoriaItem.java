package com.kafka.recomendaciones.Model;

import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * Item de la tabla DynamoDB pedidos360-auditoria.
 *
 * Origen: cola RabbitMQ notificaciones.auditoria -> tópico Kafka
 * auditoria-eventos -> grupo-auditoria-dynamo (este servicio) -> esta tabla.
 *
 * PK "entidad" = key del registro Kafka (idPedido, o usuarioId si no hay
 * pedido): la consulta natural es "todo lo que pasó con el pedido P-21", y
 * con esta PK es un Query, no un Scan.
 * SK "ocurridoEn" = instante ISO-8601 con milisegundos + "#" + messageId: ordena
 * los eventos de la entidad en el tiempo y no choca si dos llegan en el mismo ms.
 * "expiraEn" (epoch segundos) es el TTL: 90 días, igual que la retención del tópico.
 */
@Data
@NoArgsConstructor
@DynamoDbBean
public class AuditoriaItem {

    @Getter(onMethod_ = @DynamoDbPartitionKey)
    private String entidad;

    @Getter(onMethod_ = @DynamoDbSortKey)
    private String ocurridoEn;

    private String messageId;
    private String routingKey;
    private String exchange;
    private String origen;
    /** JSON del evento tal como lo publicó ms-carrito en RabbitMQ. */
    private String cuerpo;
    private String topico;
    private Integer particion;
    private Long offset;
    private Long expiraEn;
}
