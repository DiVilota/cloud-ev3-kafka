package com.kafka.recomendaciones.Config;

import com.kafka.recomendaciones.DTO.EventoInteraccionDTO;
import com.kafka.recomendaciones.DTO.ProductoCatalogoDTO;
import com.kafka.recomendaciones.Exception.PayloadInvalidoException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuracion explicita del cliente Kafka (criterio 1 de la pauta).
 *
 * Todo esta declarado a mano en vez de confiar en spring.kafka.* para que cada
 * propiedad quede a la vista con el motivo de su valor. Hay una ConsumerFactory y
 * una container factory por cada DTO/grupo que lo necesita: interacciones con
 * earliest (recomendaciones), interacciones con latest (dashboard), catalogo, y
 * los DLT leidos como texto.
 */
@Slf4j
@Configuration
public class KafkaConfig {

    private static final String SERVICIO = "ms-recomendaciones";
    /** Unico paquete desde el que se aceptan tipos JSON: nunca "*". */
    private static final String PAQUETE_DTO = "com.kafka.recomendaciones.DTO";

    private final String bootstrapServers;
    private final KafkaTopicosProperties props;

    public KafkaConfig(@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
                       KafkaTopicosProperties props) {
        this.bootstrapServers = bootstrapServers;
        this.props = props;
    }

    // =====================================================================
    // Productores
    // =====================================================================

    private Map<String, Object> configProductor(String clientId) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        // acks=all + idempotencia: el lider confirma solo cuando las replicas en
        // sincronia (min ISR 2) tienen el mensaje, y un reintento no duplica.
        cfg.put(ProducerConfig.ACKS_CONFIG, "all");
        cfg.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        cfg.put(ProducerConfig.RETRIES_CONFIG, 10);
        // 5 es el maximo que la idempotencia admite sin perder el orden por particion.
        cfg.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        // Techo total por mensaje (incluye reintentos): pasado eso, el callback
        // informa el error en vez de reintentar para siempre.
        cfg.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30000);
        cfg.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000);
        // Esperar 10 ms agrupa el clickstream en lotes; nadie nota 10 ms en una recomendacion.
        cfg.put(ProducerConfig.LINGER_MS_CONFIG, 10);
        cfg.put(ProducerConfig.BATCH_SIZE_CONFIG, 32768);
        // Igual que el topico (lz4): el broker no recomprime y el JSON repetitivo comprime bien.
        cfg.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        // send() bloquea hasta max.block.ms si no hay metadatos (cluster caido). Con
        // el valor por defecto (60 s) un POST quedaria colgado un minuto; con 5 s
        // falla rapido, se loguea y la operacion HTTP sigue (§3.2).
        cfg.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000);
        cfg.put(ProducerConfig.CLIENT_ID_CONFIG, clientId);
        cfg.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        cfg.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        // Sin cabeceras de tipo: el consumidor no debe depender del paquete Java
        // del productor (cada servicio tiene su propia copia del DTO).
        cfg.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        return cfg;
    }

    @Bean
    public ProducerFactory<String, EventoInteraccionDTO> fabricaProductorInteracciones() {
        return new DefaultKafkaProducerFactory<>(configProductor(SERVICIO + "-producer"));
    }

    @Bean
    public KafkaTemplate<String, EventoInteraccionDTO> plantillaInteracciones() {
        return new KafkaTemplate<>(fabricaProductorInteracciones());
    }

    /**
     * Plantilla del DeadLetterPublishingRecoverer.
     *
     * Un mensaje que no se pudo deserializar llega al recuperador como byte[]
     * (los bytes originales), y uno que fallo en la logica llega como DTO. Por eso
     * el serializador elige por tipo: byte[] tal cual, cualquier otra cosa a JSON.
     */
    @Bean
    public KafkaTemplate<Object, Object> plantillaDlt() {
        Map<Class<?>, Serializer<?>> porTipo = new LinkedHashMap<>();
        porTipo.put(byte[].class, new ByteArraySerializer());
        JsonSerializer<Object> json = new JsonSerializer<>();
        json.setAddTypeInfo(false);
        porTipo.put(Object.class, json);

        Map<String, Object> cfg = configProductor(SERVICIO + "-producer-dlt");
        cfg.remove(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG);
        cfg.remove(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG);
        // El JsonSerializer de arriba ya se configuro con setAddTypeInfo(false): si
        // tambien llega la propiedad, configure() lanza IllegalStateException ("not
        // both") y TODA publicacion al DLT falla (lo detecto la prueba con EmbeddedKafka).
        cfg.remove(JsonSerializer.ADD_TYPE_INFO_HEADERS);
        @SuppressWarnings({"unchecked", "rawtypes"})
        Serializer<Object> claves = (Serializer) new StringSerializer();
        // assignable=true: Object.class atrapa a los DTO; byte[] va primero y gana.
        DefaultKafkaProducerFactory<Object, Object> fabrica = new DefaultKafkaProducerFactory<>(
                cfg, claves, new DelegatingByTypeSerializer(porTipo, true));
        return new KafkaTemplate<>(fabrica);
    }

    // =====================================================================
    // Consumidores
    // =====================================================================

    private Map<String, Object> configConsumidor(Class<?> tipoValor, String offsetInicial) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        cfg.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // ErrorHandlingDeserializer envuelve al JSON: un mensaje roto no tumba el
        // consumidor en un bucle infinito, se convierte en DeserializationException
        // que el DefaultErrorHandler manda directo al DLT.
        cfg.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        cfg.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        cfg.put(JsonDeserializer.VALUE_DEFAULT_TYPE, tipoValor.getName());
        cfg.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        cfg.put(JsonDeserializer.TRUSTED_PACKAGES, PAQUETE_DTO);
        // Commit manejado por el contenedor (AckMode.RECORD): el offset avanza solo
        // cuando el registro se proceso o termino en el DLT, nunca antes.
        cfg.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        cfg.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, offsetInicial);
        cfg.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100);
        // 5 min entre polls: cubre el peor caso de 100 registros con reintentos
        // (backoff de hasta 10 s) sin que el broker expulse al consumidor.
        cfg.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000);
        // heartbeat = 1/3 de la sesion, la proporcion recomendada por Kafka.
        cfg.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 45000);
        cfg.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 15000);
        // Solo mensajes confirmados: si algun productor usa transacciones, no se
        // procesa lo que luego se aborta.
        cfg.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        // Base del client.id; cada @KafkaListener le agrega su sufijo (clientIdPrefix).
        cfg.put(ConsumerConfig.CLIENT_ID_CONFIG, SERVICIO + "-consumer");
        return cfg;
    }

    /** Interacciones para grupo-recomendaciones: earliest, se reconstruye todo el historial. */
    @Bean
    public ConsumerFactory<String, EventoInteraccionDTO> fabricaConsumidorInteracciones() {
        return new DefaultKafkaConsumerFactory<>(configConsumidor(EventoInteraccionDTO.class, "earliest"));
    }

    /** Interacciones para grupo-dashboard: latest, al tablero solo le importa lo que pasa ahora. */
    @Bean
    public ConsumerFactory<String, EventoInteraccionDTO> fabricaConsumidorDashboard() {
        return new DefaultKafkaConsumerFactory<>(configConsumidor(EventoInteraccionDTO.class, "latest"));
    }

    /** Catalogo compactado: earliest, para reconstruir el estado completo al arrancar. */
    @Bean
    public ConsumerFactory<String, ProductoCatalogoDTO> fabricaConsumidorCatalogo() {
        return new DefaultKafkaConsumerFactory<>(configConsumidor(ProductoCatalogoDTO.class, "earliest"));
    }

    /**
     * Los DLT se leen como texto: lo que esta ahi justamente pudo no ser JSON
     * valido, y el objetivo es registrarlo, no volver a interpretarlo.
     */
    @Bean
    public ConsumerFactory<String, String> fabricaConsumidorDlt() {
        Map<String, Object> cfg = configConsumidor(String.class, "earliest");
        cfg.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        cfg.remove(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS);
        cfg.remove(JsonDeserializer.VALUE_DEFAULT_TYPE);
        cfg.remove(JsonDeserializer.USE_TYPE_INFO_HEADERS);
        cfg.remove(JsonDeserializer.TRUSTED_PACKAGES);
        return new DefaultKafkaConsumerFactory<>(cfg);
    }

    // =====================================================================
    // Manejo de errores (criterio 6)
    // =====================================================================

    /**
     * Reintenta lo transitorio con backoff exponencial y manda al DLT, en la misma
     * particion del original, lo que no tiene arreglo.
     */
    DefaultErrorHandler crearManejadorErrores(KafkaTemplate<Object, Object> plantillaDlt) {
        DeadLetterPublishingRecoverer recuperador = new DeadLetterPublishingRecoverer(plantillaDlt,
                (registro, ex) -> new TopicPartition(props.dltDe(registro.topic()), registro.partition()));

        KafkaTopicosProperties.Reintentos r = props.getReintentos();
        ExponentialBackOffWithMaxRetries backoff = new ExponentialBackOffWithMaxRetries(r.getMaximo());
        backoff.setInitialInterval(r.getIntervaloInicialMs());
        backoff.setMultiplier(r.getMultiplicador());
        backoff.setMaxInterval(r.getIntervaloMaximoMs());

        DefaultErrorHandler manejador = new DefaultErrorHandler((registro, ex) -> {
            log.error("Mensaje enviado al DLT: topico={} particion={} offset={} key={} causa={}",
                    registro.topic(), registro.partition(), registro.offset(), registro.key(),
                    ex.getMessage());
            recuperador.accept(registro, ex);
        }, backoff);

        // Reintentar un JSON roto o un DTO invalido no lo arregla: directo al DLT.
        manejador.addNotRetryableExceptions(
                DeserializationException.class,
                MethodArgumentNotValidException.class,
                ConstraintViolationException.class,
                PayloadInvalidoException.class);

        manejador.setRetryListeners((registro, ex, intento) ->
                log.warn("Reintento {} de {}: topico={} particion={} offset={} key={} causa={}",
                        intento, r.getMaximo(), registro.topic(), registro.partition(),
                        registro.offset(), registro.key(), ex.getMessage()));
        return manejador;
    }

    private <V> ConcurrentKafkaListenerContainerFactory<String, V> crearFabricaListener(
            ConsumerFactory<String, V> consumidor, KafkaTemplate<Object, Object> plantillaDlt) {
        ConcurrentKafkaListenerContainerFactory<String, V> fabrica = new ConcurrentKafkaListenerContainerFactory<>();
        fabrica.setConsumerFactory(consumidor);
        fabrica.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        fabrica.setCommonErrorHandler(crearManejadorErrores(plantillaDlt));
        return fabrica;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, EventoInteraccionDTO> fabricaListenerInteracciones(
            KafkaTemplate<Object, Object> plantillaDlt) {
        return crearFabricaListener(fabricaConsumidorInteracciones(), plantillaDlt);
    }

    /**
     * El dashboard NO publica en el DLT: el dueno de interacciones-user.DLT es
     * grupo-recomendaciones, que recibe exactamente los mismos mensajes. Si los
     * dos grupos publicaran, cada mensaje roto apareceria DOS veces en el DLT.
     * Tampoco reintenta: contar en memoria no tiene fallas transitorias, asi que
     * lo que no se puede contar se descarta con un WARN y el tablero sigue.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, EventoInteraccionDTO> fabricaListenerDashboard() {
        ConcurrentKafkaListenerContainerFactory<String, EventoInteraccionDTO> fabrica =
                new ConcurrentKafkaListenerContainerFactory<>();
        fabrica.setConsumerFactory(fabricaConsumidorDashboard());
        fabrica.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        fabrica.setCommonErrorHandler(new DefaultErrorHandler(
                (registro, ex) -> log.warn("Dashboard descarta mensaje (el DLT lo registra grupo-recomendaciones): "
                                + "topico={} particion={} offset={} key={} causa={}",
                        registro.topic(), registro.partition(), registro.offset(), registro.key(), ex.getMessage()),
                new FixedBackOff(0L, 0L)));
        return fabrica;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, ProductoCatalogoDTO> fabricaListenerCatalogo(
            KafkaTemplate<Object, Object> plantillaDlt) {
        return crearFabricaListener(fabricaConsumidorCatalogo(), plantillaDlt);
    }

    /**
     * Auditoría (auditoria-eventos -> DynamoDB): se lee como texto y el
     * consumidor interpreta el JSON. Mismos reintentos con backoff y DLT
     * (auditoria-eventos.DLT) que los demás flujos: una caída momentánea de
     * DynamoDB se reintenta, un JSON roto va directo al DLT.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> fabricaListenerAuditoria(
            KafkaTemplate<Object, Object> plantillaDlt) {
        return crearFabricaListener(fabricaConsumidorDlt(), plantillaDlt);
    }

    /**
     * El listener del DLT no tiene a donde mandar sus propios fallos: si no puede
     * registrar un mensaje, lo loguea y sigue (sin reintentos ni DLT del DLT).
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> fabricaListenerDlt() {
        ConcurrentKafkaListenerContainerFactory<String, String> fabrica = new ConcurrentKafkaListenerContainerFactory<>();
        fabrica.setConsumerFactory(fabricaConsumidorDlt());
        fabrica.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        fabrica.setCommonErrorHandler(new DefaultErrorHandler(
                (registro, ex) -> log.error("No se pudo registrar el mensaje muerto topico={} offset={}: {}",
                        registro.topic(), registro.offset(), ex.getMessage()),
                new FixedBackOff(0L, 0L)));
        return fabrica;
    }
}
