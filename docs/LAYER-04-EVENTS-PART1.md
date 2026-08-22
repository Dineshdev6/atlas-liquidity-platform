# Layer 4, part 1 — Kafka and the transactional outbox

**Goal:** the platform can tell other services that something happened, and the
database and the event stream can never disagree about what.

**Prerequisite:** Layer 3 merged and green. Docker Desktop running.

**One new dependency:** `spring-kafka`. **One new container:** Kafka, in KRaft
mode. Everything else is Java and one SQL migration.

---

## 1. The problem, before the solution

An adjustment lands. The buffer must change *and* the rest of the platform must
be told. The obvious code is two lines:

```java
accounts.updateLiquidityBuffer(accountId, newBuffer);   // database
kafka.send("buffer-changed", event);                    // network
```

That is a **dual write**, and there is no order that makes it correct.

**Send first, commit second.** The send lands, the transaction rolls back. The
platform now believes in a change that never happened, and nothing will ever
correct it, because there is no second event saying "actually, no".

**Commit first, send second.** The balance changes, then the broker blips or the
pod is evicted. Nobody is told. **This is the worse one**, because there is no
error anywhere — the API returned 200, the database is right, and the divergence
surfaces during reconciliation days later.

And `@Transactional` does not save you. Kafka is not in your database
transaction. A rollback cannot un-send a message.

**The outbox:** don't send anything. Write the event into a table, in the same
transaction as the business change. One transaction, one outcome. A separate
relay reads that table and publishes afterwards.

---

## 2. What changed

```
NEW  db/migration/V4__create_outbox_event.sql
NEW  common/events/LiquidityBufferChangedEvent.java   the shared contract
NEW  outbox/OutboxEventEntity.java
NEW  outbox/OutboxEventJpaRepository.java
NEW  outbox/OutboxWriter.java                         records events, MANDATORY transaction
NEW  outbox/OutboxPublisher.java                      sends the backlog, @Transactional
NEW  outbox/OutboxRelay.java                          the timer, and nothing else
NEW  config/KafkaProducerConfig.java                  acks=all, idempotence, batching
NEW  config/SchedulingConfig.java
NEW  test outbox/OutboxPublisherTest.java             5 tests, no broker, no database
NEW  test outbox/OutboxRelayTest.java                 3 tests
NEW  test outbox/OutboxIT.java                        8 tests against real Postgres
NEW  docs/adr/0007-transactional-outbox-over-dual-write.md

CHG  docker-compose.yml                               + Kafka (KRaft) + Kafka UI
CHG  reference-data-service/pom.xml                   + spring-kafka
CHG  application.yml                                  + kafka, + atlas.outbox
CHG  test resources/application-it.yml                relay disabled for tests
CHG  application/LiquidityBufferAdjustmentService.java  records an event per change
```

Sixteen new tests; you should finish at **134**.

---

## 3. Run it

Docker will pull the Kafka image the first time — about 400MB, so give it a
minute.

```powershell
docker compose up -d
```

```powershell
docker compose ps
```

You want three containers running: `atlas-postgres`, `atlas-kafka` and
`atlas-kafka-ui`. Kafka reports `healthy` after 20–30 seconds; if it says
`starting`, wait and look again.

```powershell
mvn clean verify
```

**134 tests, `Failures: 0`, `Skipped: 0`.** Note the whole suite passes with
Kafka stopped as well — nothing in it needs a broker. That is deliberate, and
section 6 explains why.

---

## 4. Watch an event travel

Start the service:

```powershell
mvn clean install -DskipTests
```

```powershell
mvn -pl reference-data-service spring-boot:run
```

**In a second terminal**, tail the topic. This blocks and prints messages as they
arrive, so leave it running:

```powershell
docker exec atlas-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic atlas.liquidity.buffer-changed.v1 --from-beginning --property print.key=true --property print.partition=true
```

**In a third terminal**, make a change:

```powershell
'{"amount":"5000000.00"}' | Set-Content -Encoding ascii adj.json
```

```powershell
curl.exe -s -X POST "http://localhost:8081/api/v1/accounts/ACC-GB-0001/liquidity-buffer-adjustments" -H "Content-Type: application/json" -H "Idempotency-Key: $([guid]::NewGuid())" -d "@adj.json"
```

Within a second the consumer terminal prints the event, prefixed by its
partition and its key. **Look at the key: it is the account id.** That is what
buys you ordering.

Now send three adjustments to three different accounts and watch the partition
number change. Then send three to the *same* account and watch them all land on
one partition, in order. That is the entire ordering guarantee, visible.

**The browser view:** http://localhost:8080 — topics, partitions, offsets,
consumer groups and message payloads. Open the topic and look at the three
partitions.

---

## 5. Break it on purpose — this is the part that teaches

**Stop Kafka while the service is running:**

```powershell
docker compose stop kafka
```

Now make another adjustment. The API still returns **200**. The buffer still
changes. The service does not care that Kafka is down, because nothing on the
request path talks to Kafka. The relay logs a warning once a second and the
backlog grows:

```powershell
docker exec atlas-postgres psql -U atlas -d atlas_liquidity -c "SELECT id, event_type, partition_key, published_at FROM outbox_event ORDER BY id;"
```

`published_at` is NULL on the new rows. Nothing is lost — it is simply not sent
yet.

**Start Kafka again:**

```powershell
docker compose start kafka
```

Within a few seconds the backlog drains, the consumer terminal prints everything
that queued up, in order, and `published_at` fills in. **A broker outage cost
latency and not data.** That property is the whole reason the pattern exists,
and having watched it is worth more than having read about it.

**Then the failure the pattern prevents.** Refuse an adjustment — take the buffer
below zero:

```powershell
'{"amount":"-99000000.00"}' | Set-Content -Encoding ascii bad.json
```

```powershell
curl.exe -s -X POST "http://localhost:8081/api/v1/accounts/ACC-GB-0001/liquidity-buffer-adjustments" -H "Content-Type: application/json" -H "Idempotency-Key: $([guid]::NewGuid())" -d "@bad.json"
```

400, and **no event is emitted** — check the consumer terminal. With a dual write
that publishes before committing, that event would already be gone, and every
downstream service would now believe in a change the API rejected.

---

## 6. The eight things to actually understand

**1. Why the dual write has no correct ordering.** Send-then-commit publishes
changes that did not happen. Commit-then-send loses changes that did. There is
no third option, and `@Transactional` does not help because the broker is not in
the transaction. Being able to state both failure directions is the difference
between having read about this and having thought about it.

**2. Why delivery is at-least-once and always will be.** The relay sends, Kafka
acknowledges, and *then* the row is marked published. Kill the process in that
gap and the next run sends it again. Closing that gap needs the send and the
database commit to be atomic — which is the dual-write problem, one level down.
So duplicates are a published contract, and consumers deduplicate on `eventId`.
That is Layer 3's idempotency mechanism arriving over a different transport,
which is exactly why `IdempotencyService` was written generic.

**3. Why send-then-mark and not mark-then-send.** Marking first risks recording
an event as published that never left. A lost event is unrecoverable; a
duplicate is merely annoying. When exactly-once is off the table, choose the
failure you can recover from.

**4. `Propagation.MANDATORY` on the writer.** It throws if there is no
transaction, rather than quietly starting one. A private transaction there would
let the event commit while the business change rolled back — a dual write with
extra steps, and silent. The annotation converts the one way this pattern can be
defeated into an immediate, obvious failure. `OutboxIT` has a test for it.

**5. The message key is the ordering guarantee.** Kafka guarantees order within
a partition and routes by hash of the key. Key by account and one account's
changes can never overtake each other. Leave the key null and they round-robin
across partitions and the guarantee silently disappears. The cost is hot
partitions: one very busy account maps to one partition, and one partition is
consumed by exactly one member of a group, so it cannot be scaled out. Raise that
trade-off before an interviewer does.

**6. `acks=all` versus `acks=1` versus `acks=0`.** `0` never waits, so a broker
restart loses records and nobody finds out. `1` waits for the leader only — if
the leader dies before a follower replicates, the record is gone and the producer
was told it succeeded. `all` is the only setting where success means the data
survives losing a broker. And it only means that if the cluster agrees:
`acks=all` with replication factor 1, as on this laptop, survives nothing.

**7. Producer idempotence is not the same as an idempotency key.**
`enable.idempotence=true` stamps batches with a producer id and sequence number
so the *broker* discards duplicates caused by the *client library* retrying,
within one producer session. It does nothing about the relay crashing and
re-sending on restart. It narrows the duplicate window; it does not remove it.
Candidates conflate these two constantly.

**8. Why the publisher and the relay are two beans.** Spring implements
`@Transactional` with a proxy. A call from one method of a bean to another method
of the *same instance* is a plain `this.` call and never touches that proxy, so
the annotation is **silently ignored**. Had `poll()` and `publishPending()` lived
on one class, there would have been no transaction, the entities would have been
detached, `markPublished` would have mutated objects Hibernate was not watching,
and every event would have been republished on every tick forever - with no error
anywhere. Same failure shape as the Layer 3 merge-versus-persist bug, reached by
a completely different route. "Why doesn't my `@Transactional` work?" is one of
the most common Spring interview questions, and self-invocation is the answer
about half the time.

**9. Kafka is a log, not a queue.** A consumer reading a message does not remove
it; it advances an offset. That is why a new consumer group can replay the topic
from the beginning, why the partition count sets your maximum parallelism, and
why retention is a time-and-size policy rather than "until someone reads it".
Layer 5 leans on replay heavily.

---

## 7. Exercises

1. **Do the dual write and watch it break.** In
   `LiquidityBufferAdjustmentService`, replace the `outbox.record(...)` call with
   a direct `kafkaTemplate.send(...)`. Run `OutboxIT.rejectedAdjustmentLeavesNoEvent`
   and watch the event get published for an adjustment that was refused. Revert.
   That is the bug this layer prevents, seen once.

2. **Remove the message key.** Change the relay to `kafka.send(topic, payload)`
   with no key. Send five adjustments to one account and watch them scatter
   across partitions in the console consumer. Now explain to yourself why a
   consumer could apply them in the wrong order.

3. **Prove the transaction boundary.** Delete `Propagation.MANDATORY` from
   `OutboxWriter.record` and change it to `REQUIRES_NEW`. Run
   `rejectedAdjustmentLeavesNoEvent`. It fails — the event survives the
   rollback. Then reason about how you would ever have found that in production.

4. **Kill the relay mid-flight.** Add a `Thread.sleep(5000)` between the Kafka
   send and `markPublished`. Make an adjustment, and kill the service with Ctrl+C
   during the sleep. Restart it. The event is published a second time. You have
   now personally produced the duplicate that part 2's consumer has to handle.

5. **Watch the partial index earn its keep.** Insert 100,000 published rows,
   then run `EXPLAIN ANALYZE SELECT * FROM outbox_event WHERE published_at IS
   NULL ORDER BY id LIMIT 100;`. Then drop `idx_outbox_event_unpublished` and run
   it again. Compare the plans.

6. **Break the topic name.** Point `atlas.outbox.topic` at a topic that does not
   exist. It gets auto-created, silently, because this broker has auto-creation
   on. Then set `KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false"` in `docker-compose.yml`
   and watch what a typo does instead. Decide which behaviour you would want in
   production.

7. **Read the producer config the broker actually got.** Set
   `org.apache.kafka: INFO` in `application.yml` and restart. The client logs its
   full resolved configuration on startup. Find `acks`, `enable.idempotence` and
   `linger.ms` in it, and notice how many settings you never chose.

8. **Recreate the self-invocation bug.** Move `publishPending()` onto
   `OutboxRelay` as a method of that class and have `poll()` call it directly,
   keeping `@Transactional` on it. Run the service with Kafka up and watch the
   console consumer: the same events are republished every second, forever,
   because `markPublished` is now mutating detached entities. No exception, no
   warning, nothing in the logs. Then put it back. This is the most useful five
   minutes in the layer.

---

## 8. Interview questions this slice prepares you for

1. How do you keep a database and a message broker consistent?
2. What exactly goes wrong with a dual write, in both orderings?
3. Why does `@Transactional` not solve it?
4. What is the transactional outbox, and what does it cost you?
5. Why not two-phase commit across the database and Kafka?
6. Outbox polling versus change data capture — when would you pick each?
7. Why is at-least-once delivery unavoidable here, and what must consumers do?
8. What does the Kafka message key control, and what breaks without one?
9. What are the trade-offs of partitioning by account id?
10. `acks=0`, `acks=1`, `acks=all` — what does each actually guarantee?
11. What does `enable.idempotence` do, and what does it *not* do?
12. Kafka is a log, not a queue. What follows from that?
13. What is KRaft and why did Kafka move off ZooKeeper?
14. Why does calling a `@Transactional` method from another method of the same
    bean not start a transaction?
15. Three instances run the same `@Scheduled` relay. What happens, and how would
    you fix it if the job were not idempotent?
16. How would you stop the outbox table growing without bound?

---

## 9. Commit

```powershell
git checkout -b feat/L04-kafka-and-outbox
```

```powershell
git add .
```

```powershell
git commit -m "feat(events): publish domain events via a transactional outbox" -m "Adds Kafka in KRaft mode to Docker Compose and an outbox_event table written in the same transaction as the business change, so the database and the event stream cannot disagree." -m "OutboxWriter uses Propagation.MANDATORY so recording an event outside a transaction fails immediately rather than degrading into a dual write. OutboxRelay polls oldest-first, sends before marking published, and stops at the first failure to preserve per-account ordering." -m "Producer runs acks=all with idempotence enabled. Events are keyed by account id for per-partition ordering. Delivery is at-least-once by contract; part 2 adds idempotent consumers. ADR 0007."
```

```powershell
git push -u origin feat/L04-kafka-and-outbox
```

Then merge it the same day.

---

## Definition of done

- [ ] `mvn clean verify` green, 134 tests, `Skipped: 0`
- [ ] you have watched an event appear in `kafka-console-consumer` with the
      account id as its key
- [ ] you have stopped Kafka, seen the API keep working, watched the backlog
      build in `outbox_event`, and watched it drain on restart
- [ ] you have seen a rejected adjustment emit no event
- [ ] exercises #1, #4 and #8 done — #1 shows you the bug this layer prevents,
      #4 shows you the duplicate part 2 exists to handle, #8 shows you why the
      publisher is its own bean
- [ ] merged to `main`
- [ ] you can answer 12 of the 16 questions out loud, especially 1 through 8

Then part 2: consumers, consumer groups, rebalancing, idempotent consumption,
retry topics and the dead letter topic — and a second service to receive all of
this, which is where "microservices" stops being a word in the job description
and starts being something you have built.
