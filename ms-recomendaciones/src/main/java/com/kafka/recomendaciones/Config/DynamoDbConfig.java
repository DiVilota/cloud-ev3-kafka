package com.kafka.recomendaciones.Config;

import com.kafka.recomendaciones.Repository.AuditoriaDynamoRepository;
import com.kafka.recomendaciones.Repository.EventosOperacionesDynamoRepository;
import com.kafka.recomendaciones.Repository.EventosOperacionesRepository;
import com.kafka.recomendaciones.Repository.HistorialEventosDynamoRepository;
import com.kafka.recomendaciones.Repository.HistorialEventosRepository;
import com.kafka.recomendaciones.Repository.RecomendacionesUsuarioDynamoRepository;
import com.kafka.recomendaciones.Repository.RecomendacionesUsuarioRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProviderChain;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;

import java.net.URI;

/**
 * Cliente DynamoDB (SDK v2) y repositorios respaldados por DynamoDB.
 *
 * Solo se activa con app.dynamodb.habilitado=true (por defecto). Con false el
 * contexto usa RepositoriosEnMemoria y no intenta conectarse a nada: asi las
 * pruebas corren sin AWS ni DynamoDB Local.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "app.dynamodb.habilitado", havingValue = "true", matchIfMissing = true)
public class DynamoDbConfig {

    @Bean(destroyMethod = "close")
    public DynamoDbClient clienteDynamo(@Value("${app.dynamodb.endpoint:}") String endpoint,
                                        @Value("${app.dynamodb.region:us-east-1}") String region) {
        DynamoDbClientBuilder builder = DynamoDbClient.builder().region(Region.of(region));
        if (StringUtils.hasText(endpoint)) {
            // DynamoDB Local acepta cualquier credencial, pero el SDK exige alguna.
            // Primero las del entorno (por si se apunta a un endpoint real) y si no
            // hay, unas fijas de desarrollo: asi el compose no necesita inventar
            // AWS_ACCESS_KEY_ID solo para arrancar.
            builder.endpointOverride(URI.create(endpoint))
                    .credentialsProvider(AwsCredentialsProviderChain.builder()
                            .credentialsProviders(
                                    DefaultCredentialsProvider.create(),
                                    StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
                            .build());
            log.info("DynamoDB en endpoint propio {} (region {})", endpoint, region);
        } else {
            // AWS real. En la EC2 del Learner Lab manda el ROL de la instancia
            // (LabInstanceProfile -> LabRole), que el SDK renueva solo: las
            // credenciales del lab vencen cada ~4 h y no deben ir en el .env.
            // Primero el rol; si no hay metadata (corriendo fuera de EC2) cae a la
            // cadena por defecto (variables de entorno, perfil ~/.aws).
            builder.credentialsProvider(AwsCredentialsProviderChain.builder()
                    .reuseLastProviderEnabled(true)
                    .credentialsProviders(
                            InstanceProfileCredentialsProvider.create(),
                            DefaultCredentialsProvider.create())
                    .build());
            log.info("DynamoDB en AWS, region {}", region);
        }
        return builder.build();
    }

    @Bean
    public DynamoDbEnhancedClient clienteDynamoMejorado(DynamoDbClient clienteDynamo) {
        return DynamoDbEnhancedClient.builder().dynamoDbClient(clienteDynamo).build();
    }

    /** Tabla de auditoría (RabbitMQ -> Kafka -> DynamoDB). Sólo si el flujo está encendido. */
    @Bean
    @ConditionalOnProperty(name = "app.auditoria.dynamo.habilitado", havingValue = "true")
    public AuditoriaDynamoRepository auditoriaDynamoRepository(
            DynamoDbClient clienteDynamo, DynamoDbEnhancedClient cliente,
            @Value("${app.dynamodb.tablas.auditoria:pedidos360-auditoria}") String tabla) {
        return new AuditoriaDynamoRepository(clienteDynamo, cliente, tabla);
    }

    @Bean
    public HistorialEventosRepository historialEventosRepository(
            DynamoDbEnhancedClient cliente, @Value("${app.dynamodb.tablas.historial}") String tabla) {
        return new HistorialEventosDynamoRepository(cliente, tabla);
    }

    @Bean
    public RecomendacionesUsuarioRepository recomendacionesUsuarioRepository(
            DynamoDbEnhancedClient cliente, @Value("${app.dynamodb.tablas.recomendaciones}") String tabla) {
        return new RecomendacionesUsuarioDynamoRepository(cliente, tabla);
    }

    @Bean
    public EventosOperacionesRepository eventosOperacionesRepository(
            DynamoDbEnhancedClient cliente,
            @Value("${app.dynamodb.tablas.operaciones}") String tabla,
            @Value("${app.dynamodb.operaciones-ttl-horas:24}") long ttlHoras) {
        return new EventosOperacionesDynamoRepository(cliente, tabla, ttlHoras);
    }
}
