package com.atlas.liquidity.position.config;

import com.atlas.liquidity.position.consumer.PoisonMessageException;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
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
     * while it does, so a long backoff here means every message queued behind
     * this one waits too. Ten retries with exponential backoff sounds more robust
     * and is how you stall a partition for several minutes.
     *
     * <p>The alternative, when you need genuinely patient retries without blocking,
     * is non-blocking retry topics - Spring Kafka's {@code @RetryableTopic}
     * forwards a failed record to a separate {@code -retry-0} topic and moves on,
     * so the main partition keeps flowing. It costs you ordering, because a
     * retried message now arrives after messages that came later. That trade-off
     * - blocking retries preserve order and stall throughput, non-blocking retries
     * preserve throughput and lose order - is the interesting answer to "how do
     * you handle retries in Kafka?".
     */
    private static final long RETRY_INTERVAL_MS = 1_000L;
    private static final long MAX_RETRIES = 2L;

    private final String bootstrapServers;
    private final boolean listenerAutoStartup;

    public KafkaConsumerConfig(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${spring.kafka.listener.auto-startup:true}") boolean listenerAutoStartup) {
        this.bootstrapServers = bootstrapServers;
        this.listenerAutoStartup = listenerAutoStartup;
    }

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();

        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        // Deserialise to String and let Jackson run inside our own code. A
        // JsonDeserializer that fails does so INSIDE the Kafka client, before any
        // of our code sees it, which produces a genuinely horrible failure mode -
        // and needs an ErrorHandlingDeserializer wrapper to be survivable at all.
        // Parsing where we can see it is simpler and makes the poison-message
        // path explicit rather than magical.

        // NEVER auto-commit. With auto-commit the offset advances on a timer,
        // whether or not the message was successfully handled - so a crash
        // between the commit and the work loses the message, and there is no
        // arrangement of settings that fixes it. Spring commits after the
        // listener returns instead, which is what makes at-least-once true.
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        // Where a brand-new consumer group starts. "earliest" means a new group
        // reads the whole topic from the beginning, which is what makes a
        // projection rebuildable: delete the table, change the group id, and the
        // history replays. "latest" would silently skip everything that happened
        // before the consumer first started - a great way to lose a day's events
        // during a deployment.
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        // How many records one poll() returns. This interacts with
        // max.poll.interval.ms in a way that causes real production incidents:
        // if processing a batch takes longer than that interval, the broker
        // decides this consumer is dead, revokes its partitions and rebalances -
        // and the consumer then fails to commit offsets for work it actually did.
        // Symptom: "my consumer keeps rebalancing and reprocessing". Cause:
        // usually a slow handler and too many records per poll.
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300_000);

        // Heartbeats run on a background thread and tell the broker this member is
        // alive. Note this is NOT the same as making progress - which is exactly
        // why max.poll.interval.ms exists as a separate liveness check.
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 45_000);
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 3_000);

        props.put(ConsumerConfig.CLIENT_ID_CONFIG, "atlas-position-service");

        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            DefaultErrorHandler errorHandler) {

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);

        // Three consumer threads in this one process, because the topic has three
        // partitions. Concurrency above the partition count buys nothing at all -
        // the extra threads sit idle, because a partition is assigned to exactly
        // one consumer in a group. Partition count is the hard ceiling on
        // parallelism for a topic, which is why choosing it is a decision you
        // cannot easily undo.
        factory.setConcurrency(3);

        // Commit after each record rather than after each batch. Slightly more
        // chatty, and it narrows the window in which a crash causes redelivery -
        // it cannot close it, which is why the consumer is idempotent anyway.
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);

        // Whether the listener starts with the application. Always true in
        // production; switched off in the integration-test profile, because
        // otherwise every @SpringBootTest would start three consumer threads,
        // join the real group, and eat the real topic - making the tests slow,
        // non-deterministic, and destructive to whatever you were about to
        // demonstrate by hand. The container is still built either way, so the
        // wiring is verified at context load.
        //
        // Note that spring.kafka.listener.auto-startup is Spring Boot's own
        // property name, but it only reaches the AUTO-CONFIGURED factory. Because
        // we declare our own, we have to honour it ourselves - which is the
        // recurring cost of taking control of a bean: you inherit the settings
        // you were previously getting for free.
        factory.setAutoStartup(listenerAutoStartup);

        return factory;
    }

    /**
     * Retry a couple of times, then set the message aside and move on.
     *
     * <p>Without this, a message that always fails is redelivered forever and its
     * partition never advances. Every well-formed message behind it waits on it,
     * indefinitely, and the only symptom is lag that will not come down. That is
     * the poison message problem, and a dead letter topic is how you keep one bad
     * record from stopping a payment feed.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                // Same partition number on the DLT as on the source topic, so a
                // failed message keeps its ordering relationship with its
                // siblings. This requires the DLT to have at least as many
                // partitions as the source; ours are both auto-created with three.
                (record, exception) -> {
                    log.error("Dead-lettering record from {}-{}@{} after retries: {}",
                            record.topic(), record.partition(), record.offset(),
                            exception.getMessage());
                    return new TopicPartition(record.topic() + ".DLT", record.partition());
                });

        DefaultErrorHandler handler =
                new DefaultErrorHandler(recoverer, new FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRIES));

        // A message that cannot be parsed will never parse. Retrying it wastes
        // three seconds of a partition's life to reach the conclusion we already
        // have. Straight to the dead letter topic.
        //
        // Classifying failures is the part people skip, and it is what separates a
        // retry policy from a shrug: transient failures (database briefly
        // unreachable, a downstream timeout) deserve patience; permanent ones
        // (malformed payload, a business rule that will never pass) deserve none.
        handler.addNotRetryableExceptions(PoisonMessageException.class);

        return handler;
    }

    /**
     * A producer, purely so the error handler can write to the dead letter topic.
     *
     * <p>Worth noticing: a consumer needs a producer. That is not an oddity - it
     * is what dead-lettering means. The record has to go somewhere, and "somewhere"
     * is another topic.
     */
    @Bean
    public ProducerFactory<String, String> dltProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "atlas-position-service-dlt");
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> dltProducerFactory) {
        return new KafkaTemplate<>(dltProducerFactory);
    }
}
