package com.kafka.recomendaciones.Repository;

import java.util.List;

import org.springframework.beans.factory.InitializingBean;

import com.kafka.recomendaciones.Model.AuditoriaItem;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveSpecification;

/**
 * Persistencia de la auditoría en DynamoDB. Crea la tabla (on-demand, con TTL)
 * al arrancar si no existe, igual que InicializadorTablasDynamo con las otras.
 */
@Slf4j
public class AuditoriaDynamoRepository implements InitializingBean {

    static final TableSchema<AuditoriaItem> ESQUEMA = TableSchema.fromBean(AuditoriaItem.class);

    private final DynamoDbClient clienteBajo;
    private final DynamoDbTable<AuditoriaItem> tabla;
    private final String nombreTabla;

    public AuditoriaDynamoRepository(DynamoDbClient clienteBajo, DynamoDbEnhancedClient cliente, String nombreTabla) {
        this.clienteBajo = clienteBajo;
        this.tabla = cliente.table(nombreTabla, ESQUEMA);
        this.nombreTabla = nombreTabla;
    }

    @Override
    public void afterPropertiesSet() {
        try {
            clienteBajo.describeTable(r -> r.tableName(nombreTabla));
            log.info("Tabla DynamoDB {} ya existe", nombreTabla);
        } catch (ResourceNotFoundException e) {
            // On-demand: la auditoría llega a ráfagas (cada compra genera varios
            // eventos seguidos) y con capacidad fija habría throttling.
            clienteBajo.createTable(r -> r.tableName(nombreTabla)
                    .attributeDefinitions(
                            AttributeDefinition.builder().attributeName("entidad").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("ocurridoEn").attributeType(ScalarAttributeType.S).build())
                    .keySchema(
                            KeySchemaElement.builder().attributeName("entidad").keyType(KeyType.HASH).build(),
                            KeySchemaElement.builder().attributeName("ocurridoEn").keyType(KeyType.RANGE).build())
                    .billingMode(BillingMode.PAY_PER_REQUEST));
            clienteBajo.waiter().waitUntilTableExists(r -> r.tableName(nombreTabla));
            log.info("Tabla DynamoDB {} creada (PK entidad, SK ocurridoEn)", nombreTabla);
            try {
                clienteBajo.updateTimeToLive(r -> r.tableName(nombreTabla).timeToLiveSpecification(
                        TimeToLiveSpecification.builder().attributeName("expiraEn").enabled(true).build()));
            } catch (SdkException ttl) {
                log.warn("No se pudo activar el TTL en {}: {}", nombreTabla, ttl.getMessage());
            }
        } catch (SdkException e) {
            log.error("No se pudo verificar/crear la tabla {}: {}. El consumidor reintentará y luego irá al DLT.",
                    nombreTabla, e.getMessage());
        }
    }

    public void guardar(AuditoriaItem item) {
        tabla.putItem(item);
    }

    /** Eventos de una entidad (pedido o usuario), del más reciente al más antiguo. */
    public List<AuditoriaItem> deEntidad(String entidad, int limite) {
        return tabla.query(QueryEnhancedRequest.builder()
                        .queryConditional(QueryConditional.keyEqualTo(Key.builder().partitionValue(entidad).build()))
                        .scanIndexForward(false)
                        .limit(limite)
                        .build())
                .items().stream().limit(limite).toList();
    }

    /** Muestra para el panel: un Scan acotado (la tabla es de auditoría, no de consulta masiva). */
    public List<AuditoriaItem> muestra(int limite) {
        return tabla.scan(ScanEnhancedRequest.builder().limit(limite).build())
                .items().stream().limit(limite).toList();
    }
}
