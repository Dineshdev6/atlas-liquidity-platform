package com.atlas.liquidity.refdata.config;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * The Kafka producer, configured explicitly rather than left to defaults.
 *
 * <p>Spring Boot would auto-configure a perfectly serviceable producer from
 * {@code application.yml}. We declare one anyway, because every setting below is
 * a decision worth being able to defend, and because a producer for money
 * movements should not be running on whatever the library authors happened to
 * choose.
 *
 * <p>Serialising to {@code String} rather than to objects is also deliberate.
 * The payload was serialised once, when the event was recorded, and has been
 * sitting in the outbox as text ever since. Deserialising it back into an object
 * only to re-serialise it here would be work that can only introduce
 * differences. Part 3 replaces this with Avro and a schema registry.
 */
@Configuration
public class KafkaProducerConfig {

    private final String bootstrapServers;

    public KafkaProducerConfig(@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> props = new HashMap<>();

        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        // acks=all: do not consider a send successful until every in-sync replica
        // has the record. The default in modern clients, and non-negotiable for
        // anything financial.
        //
        // Understand what the alternatives actually mean, because this is a
        // standard interview question. acks=0 is fire-and-forget: the producer
        // never even waits for the leader, so a broker restart loses records and
        // nobody finds out. acks=1 waits for the leader only - and if the leader
        // fails before a follower has replicated, the record is gone and the
        // producer was told it succeeded. acks=all is the only setting where a
        // successful send means the data survives losing a broker.
        //
        // Note it only means that IF the cluster is configured for it:
        // acks=all with replication factor 1 (this laptop) survives nothing, and
        // acks=all with min.insync.replicas=1 on a 3-broker cluster is quietly
        // acks=1. The producer setting and the broker setting have to agree.
        props.put(ProducerConfig.ACKS_CONFIG, "all");

        // The producer's own idempotence, which is NOT the same thing as the
        // idempotency keys from Layer 3 and the difference is worth being precise
        // about.
        //
        // This one is narrow: the producer stamps each batch with a producer id
        // and sequence number, so if a send is retried after a network timeout,
        // the broker recognises the duplicate and discards it. It prevents
        // duplicates caused by the CLIENT LIBRARY retrying, within one producer
        // session.
        //
        // It does nothing about the relay crashing after Kafka acknowledged but
        // before we marked the row published - that is a new producer session
        // sending what is, as far as Kafka can tell, a genuinely new record.
        // So this narrows the duplicate window; it does not remove it, and
        // consumers still have to be idempotent.
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

        // With idempotence on, the broker can deduplicate and reorder-protect up
        // to 5 in-flight batches per connection. Without idempotence this would
        // have to be 1 to preserve ordering, at a large throughput cost - a nice
        // example of a safety feature that also makes things faster.
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);

        // The real bound on retrying is time, not a count. delivery.timeout.ms is
        // the total budget from send() to success-or-failure, covering every
        // retry. The relay waits 10 seconds for its future, so this is the outer
        // limit and the relay's own timeout is what actually fires first here.
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30_000);

        // Wait up to 5ms to fill a batch. Counter-intuitively this usually
        // REDUCES latency under load, because larger batches mean fewer requests
        // and less broker overhead per record. At zero the producer sends
        // single-record batches as fast as it can and both sides work harder.
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 32 * 1024);

        // gzip because it needs nothing beyond the JDK. snappy, lz4 and zstd all
        // compress faster for the same or better ratio and are what you would
        // actually run - they just pull in native libraries, and this is a
        // teaching build. Compression is applied per batch, so it works with
        // linger.ms rather than against it.
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "gzip");

        // Fail rather than block forever when the broker is unreachable. The
        // default is 60 seconds of blocking inside send(), which on a scheduled
        // relay means the thread is gone for a minute per attempt.
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000);

        // Shows up in broker logs and metrics. Anonymous clients are miserable to
        // debug in a cluster with forty of them.
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "atlas-refdata-outbox-relay");

        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
}
