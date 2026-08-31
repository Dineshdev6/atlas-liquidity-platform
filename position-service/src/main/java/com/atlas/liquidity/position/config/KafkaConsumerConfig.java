package com.atlas.liquidity.position.config;

import com.atlas.liquidity.position.consumer.PoisonMessageException;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.util.backoff.FixedBackOff;

/**
 * The consumer, its retry policy, and the dead letter topic.
 *
 * <p>Every setting here is a decision worth defending, which is why it is Java
 * rather than YAML.
 */
@Configuration
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    /**
     * How many times to retry before dead-lettering, and how long to wait.
     *
     * <p>Two retries a second apart is short on purpose. These are BLOCKING
     * retries: the consumer thread sleeps and that partition makes no progress
     * while it does, so a long backoff means every message queued behind this one
     * waits too. Ten retries with exponential backoff sounds more robust and is
     * how you stall a partition for several minutes.
     *
     * <p>The alternative, when you need genuinely patient retries without
     * blocking, is non-blocking retry topics - Spring Kafka's
     * {@code @RetryableTopic} forwards a failed record to a separate
     * {@code -retry-0} topic and moves on, so the main partition keeps flowing. It
     * costs you ordering, because a retried message now arrives after messages
     * that came later. Blocking retries preserve order and stall throughput;
     * non-blocking retries preserve throughput and lose order. That trade-off is
     * the interesting answer to "how do you handle retries in Kafka?".
     */
    private static final long RETRY_INTERVAL_MS = 1_000L;
    private static final long MAX_RETRIES = 2L;

    private final String bootstrapServers;
    private final String schemaRegistryUrl;
    private final boolean listenerAutoStartup;

    public KafkaConsumerConfig(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${atlas.schema-registry.url:http://localhost:8085}") String schemaRegistryUrl,
            @Value("${spring.kafka.listener.auto-startup:true}") boolean listenerAutoStartup) {
        this.bootstrapServers = bootstrapServers;
        this.schemaRegistryUrl = schemaRegistryUrl;
        this.listenerAutoStartup = listenerAutoStartup;
    }

    @Bean
    public ConsumerFactory<String, Object> consumerFactory() {
        Map<String, Object> props = new HashMap<>();

        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        // LAYER 4 PART 3, and this pair of lines is the most important change in
        // the consumer.
        //
        // The value deserialiser is ErrorHandlingDeserializer WRAPPING the Avro
        // one. Without the wrapper, a message that cannot be deserialised throws
        // inside the Kafka client during poll() - before any of our code, before
        // the error handler, before anything that could dead-letter it. The
        // container logs it and polls again, gets the same record, and the
        // partition never advances. That is an unrecoverable poison message, and
        // it is the single most common way an Avro consumer wedges itself.
        //
        // The wrapper catches the failure, hands the listener a null value and
        // puts the exception in a header, so the failure travels through the
        // normal error-handling path and reaches the dead letter topic.
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, KafkaAvroDeserializer.class.getName());

        props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);

        // Hand us the GENERATED class rather than a GenericRecord. Without this
        // the listener receives an untyped record and every field access is a
        // string lookup returning Object - which compiles, and turns a schema
        // change into a ClassCastException at runtime instead of a build failure.
        props.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);

        // NEVER auto-commit. With auto-commit the offset advances on a timer,
        // whether or not the message was handled - so a crash between the commit
        // and the work loses the message, and no other setting fixes it. Spring
        // commits after the listener returns instead, which is what makes
        // at-least-once true.
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        // Where a brand-new consumer group starts. "earliest" is what makes a
        // projection rebuildable: delete the table, reset the group, and the
        // history replays. "latest" would silently skip everything published
        // before the consumer first started.
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        // These two interact in a way that causes real production incidents: if
        // processing a batch takes longer than max.poll.interval.ms, the broker
        // decides this consumer is dead, revokes its partitions and rebalances -
        // and the consumer then fails to commit offsets for work it actually did.
        // Symptom: "my consumer keeps rebalancing and reprocessing". Cause:
        // usually a slow handler and too many records per poll.
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300_000);

        // Heartbeats run on a background thread and say this member is alive.
        // That is NOT the same as making progress, which is exactly why
        // max.poll.interval.ms exists as a separate liveness check.
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 45_000);
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 3_000);

        props.put(ConsumerConfig.CLIENT_ID_CONFIG, "atlas-position-service");

        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory,
            DefaultErrorHandler errorHandler) {

        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);

        // Three consumer threads, because the topic has three partitions.
        // Concurrency above the partition count buys nothing - the extra threads
        // sit idle, because a partition is assigned to exactly one consumer in a
        // group. Partition count is the hard ceiling on parallelism for a topic,
        // which is why choosing it is a decision you cannot easily undo.
        factory.setConcurrency(3);

        // Commit after each record rather than each batch. Slightly chattier, and
        // it narrows the window in which a crash causes redelivery - it cannot
        // close it, which is why the consumer is idempotent anyway.
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);

        // Switched off in the integration-test profile, or every @SpringBootTest
        // would start three consumer threads, join the real group and eat the real
        // topic. Spring Boot's own handling of this property only reaches the
        // AUTO-CONFIGURED factory; because we declare our own, we honour it
        // ourselves - the recurring cost of taking control of a bean is inheriting
        // the settings you used to get for free.
        factory.setAutoStartup(listenerAutoStartup);

        return factory;
    }

    /**
     * Retry a couple of times, then set the message aside and move on.
     *
     * <p>Without this, a message that always fails is redelivered forever and its
     * partition never advances. Every well-formed message behind it waits,
     * indefinitely, and the only symptom is lag that will not come down.
     *
     * <p><b>Two templates, and the reason is Avro.</b> A record that failed to
     * deserialise has no value object at all - only the original bytes, which
     * {@code ErrorHandlingDeserializer} preserved. Those must be republished with
     * a byte-array serialiser. A record that deserialised fine but failed in our
     * code still holds its Avro object, and that must go through the Avro
     * serialiser. One template cannot do both, so the recoverer is given a map
     * from value type to template and picks per record. The map is ordered:
     * {@code byte[]} is checked first, {@code Object} is the fallback.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(
            @Qualifier("dltBytesTemplate") KafkaTemplate<String, byte[]> bytesTemplate,
            @Qualifier("dltAvroTemplate") KafkaTemplate<String, Object> avroTemplate) {

        Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
        templates.put(byte[].class, bytesTemplate);
        templates.put(Object.class, avroTemplate);

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                templates,
                // Same partition number on the DLT as on the source topic, so a
                // failed message keeps its ordering relationship with its
                // siblings. This needs the DLT to have at least as many
                // partitions as the source.
                (record, exception) -> {
                    log.error("Dead-lettering record from {}-{}@{} after retries: {}",
                            record.topic(), record.partition(), record.offset(),
                            exception.getMessage());
                    return new TopicPartition(record.topic() + ".DLT", record.partition());
                });

        DefaultErrorHandler handler =
                new DefaultErrorHandler(recoverer, new FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRIES));

        // A message that cannot be parsed will never parse. Retrying wastes three
        // seconds of a partition's life to reach a conclusion we already have.
        //
        // Classifying failures is the part people skip, and it is what separates a
        // retry policy from a shrug: transient failures (database briefly
        // unreachable, a downstream timeout) deserve patience; permanent ones
        // (malformed payload, a business rule that will never pass) deserve none.
        //
        // DeserializationException is already in DefaultErrorHandler's built-in
        // not-retryable list, which is another reason to wrap the Avro
        // deserialiser rather than let it throw inside the client.
        handler.addNotRetryableExceptions(PoisonMessageException.class);

        return handler;
    }

    // --- the two dead-letter producers ------------------------------------

    @Bean
    public ProducerFactory<String, byte[]> dltBytesProducerFactory() {
        Map<String, Object> props = baseProducerProps();
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, byte[]> dltBytesTemplate(
            ProducerFactory<String, byte[]> dltBytesProducerFactory) {
        return new KafkaTemplate<>(dltBytesProducerFactory);
    }

    @Bean
    public ProducerFactory<String, Object> dltAvroProducerFactory() {
        Map<String, Object> props = baseProducerProps();
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);
        props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, Object> dltAvroTemplate(
            ProducerFactory<String, Object> dltAvroProducerFactory) {
        return new KafkaTemplate<>(dltAvroProducerFactory);
    }

    private Map<String, Object> baseProducerProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "atlas-position-service-dlt");
        return props;
    }
}
