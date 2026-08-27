# Layer 4, part 2 — a second service, and consuming events safely

**Goal:** something on the other end of the drum. A separate service, with its own
database, that learns everything from the topic — and survives duplicates,
poison messages and out-of-order delivery.

**Prerequisite:** part 1 merged and green. Docker Compose running.

**New Maven module:** `position-service`, on port 8082. **No new dependency**
beyond what part 1 already introduced.

---

## 1. Three problems, and why each is unavoidable

**Duplicates are contractual, not accidental.** Part 1's relay publishes, waits
for Kafka's acknowledgement, then commits the row as published. Crash in that gap
and the event is published again on restart. You saw why that gap cannot be
closed — closing it needs the send and the database commit to be one atomic act,
which is the dual-write problem one level down. So the consumer must recognise an
event it has already applied. **That is the Layer 3 idempotency key, on the other
side of the wire.**

**One bad message must not stop the queue.** Kafka does not skip a record because
you failed to handle it; it redelivers it. A message you can never process is
therefore redelivered forever, the offset never advances, and every well-formed
message behind it on that partition waits. The only symptom is lag that will not
come down. That is a **poison message**, and a **dead letter topic** is how you
stop one bad record halting a payment feed.

**Order is only guaranteed within a partition.** The producer keys by account, so
in the normal case an account's events arrive in order. A retry breaks that. A
dead-letter replay breaks that. An event applied out of order sets the balance
backwards, silently — so the consumer compares timestamps and declines.

---

## 2. What changed

```
NEW MODULE position-service (port 8082, database atlas_positions)
  PositionServiceApplication.java
  projection/AccountPositionEntity.java        the read model
  projection/ProcessedEventEntity.java         the idempotency register
  projection/*JpaRepository.java
  application/PositionUpdateService.java       ALL the correctness lives here
  consumer/LiquidityBufferChangedListener.java thin: parse, delegate, throw
  consumer/PoisonMessageException.java         permanent vs transient failure
  config/KafkaConsumerConfig.java              group, concurrency, retries, DLT
  api/PositionController.java + PositionResponse.java
  db/migration/V1__create_account_position.sql   note: V1, not V5
  test application/PositionUpdateServiceIT.java     9 tests, real Postgres
  test consumer/LiquidityBufferChangedListenerTest.java  4 tests
  test api/PositionControllerTest.java               3 tests

NEW  docker/postgres-init/01-create-databases.sql
NEW  docs/adr/0008-idempotent-consumers-and-dead-letter-topics.md

CHG  pom.xml (root)          + <module>position-service</module>
CHG  docker-compose.yml      + the init-script mount
```

Sixteen new tests; you should finish at **150**.

---

## 3. Set it up

**Create the two new databases.** The init script only runs on a fresh Postgres
volume, and yours is not fresh:

```powershell
docker exec atlas-postgres psql -U atlas -d postgres -c "CREATE DATABASE atlas_positions OWNER atlas;"
```

```powershell
docker exec atlas-postgres psql -U atlas -d postgres -c "CREATE DATABASE atlas_positions_test OWNER atlas;"
```

**Add the module to the root POM.** One line, inserted deterministically rather
than by hand:

```powershell
$p = "C:\dev\atlas-liquidity-platform\pom.xml"; $c = [System.IO.File]::ReadAllText($p); [System.IO.File]::WriteAllText($p, $c.Replace('<module>reference-data-service</module>', '<module>reference-data-service</module>' + [Environment]::NewLine + '        <module>position-service</module>'))
```

Check it landed:

```powershell
Select-String -Path "C:\dev\atlas-liquidity-platform\pom.xml" -Pattern "<module>"
```

Three modules: `liquidity-common`, `reference-data-service`, `position-service`.
Order matters — Maven builds them in dependency order, and `liquidity-common`
must come first.

**Build:**

```powershell
mvn clean verify
```

**150 tests, `Failures: 0`, `Skipped: 0`.** The suite still needs no broker: the
listener containers do not start under the test profile, and the consumer's
correctness is tested through the service directly.

---

## 4. See it work

Three terminals. **Terminal 1** — the producer side:

```powershell
mvn clean install -DskipTests
```

```powershell
mvn -pl reference-data-service spring-boot:run
```

**Terminal 2** — the consumer side:

```powershell
mvn -pl position-service spring-boot:run
```

Watch its startup log for the partition assignment — a line naming
`atlas.liquidity.buffer-changed.v1-0`, `-1` and `-2`. That is Kafka handing all
three partitions to this single member of the group.

Because `auto-offset-reset` is `earliest` and this group has never run before, it
will immediately consume **everything already on the topic** and build the
projection from history. That is not a quirk; it is the property that makes a
projection rebuildable.

**Terminal 3** — look at what it built:

```powershell
curl.exe -s http://localhost:8082/api/v1/positions
```

Now make a change on the producer and watch it arrive:

```powershell
'{"amount":"5000000.00"}' | Set-Content -Encoding ascii adj.json
```

```powershell
curl.exe -s -X POST "http://localhost:8081/api/v1/accounts/ACC-GB-0001/liquidity-buffer-adjustments" -H "Content-Type: application/json" -H "Idempotency-Key: $([guid]::NewGuid())" -d "@adj.json"
```

```powershell
curl.exe -s http://localhost:8082/api/v1/positions/ACC-GB-0001
```

Two services, two databases, no call between them. Note `appliedCount` — that is
the honest witness that the event was applied once.

---

## 5. Prove the independence

**Stop reference-data-service** (Ctrl+C in terminal 1), then:

```powershell
curl.exe -s http://localhost:8082/api/v1/positions/ACC-GB-0001
```

Still answers, instantly. It is not asking anyone anything. Had this been a REST
call to the other service, this read would now be a 500 — and in a chain of six
services, one being down would take all six down. That is the actual argument for
event-driven architecture, and you have just watched it rather than read it.

Start reference-data-service again before continuing.

---

## 6. Poison a message and watch the dead letter topic

Publish something the consumer can never parse, straight onto the topic —
**terminal 3**:

```powershell
docker exec -i atlas-kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic atlas.liquidity.buffer-changed.v1
```

Type this and press Enter, then **Ctrl+C** to exit the producer:

```
{ this is not json
```

In terminal 2 you will see the consumer reject it — and because
`PoisonMessageException` is classified as not retryable, it goes to the dead
letter topic immediately rather than blocking the partition for three seconds
first. Look at it:

```powershell
docker exec atlas-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic atlas.liquidity.buffer-changed.v1.DLT --from-beginning --timeout-ms 10000
```

Then confirm the consumer is still healthy by making another adjustment on 8081
and watching the position update. **One bad message did not stop the feed.**

Now try the same thing with `KafkaConsumerConfig.addNotRetryableExceptions`
commented out. Same poison message, but now three attempts a second apart before
it is dead-lettered. Multiply that by a partition full of them.

---

## 7. Look at the consumer group

```powershell
docker exec atlas-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group atlas-position-service
```

One row per partition: current offset, log end offset, and **LAG** — how far
behind the consumer is. Lag is the single most important number to monitor on any
Kafka consumer, because it is the one that tells you the system is falling behind
before anyone notices the data is stale.

`CONSUMER-ID` and `HOST` show which member owns each partition. Start a **second
instance** of position-service on another port and watch the partitions
redistribute:

```powershell
mvn -pl position-service spring-boot:run "-Dspring-boot.run.arguments=--server.port=8083"
```

Run the `--describe` command again. Three partitions now split across two
members — that is a **rebalance**, and it is how you scale a consumer. Start a
fourth and fifth instance and the extras sit idle: **partition count is the hard
ceiling on parallelism**, which is why choosing it is a decision you cannot easily
undo.

---

## 8. Rebuild the projection from history

The demonstration that proves the projection is derived rather than authoritative.

Stop position-service (a consumer group cannot be reset while a member is
active), then wipe the projection:

```powershell
docker exec atlas-postgres psql -U atlas -d atlas_positions -c "DELETE FROM account_position; DELETE FROM processed_event;"
```

Rewind the group to the start of the topic:

```powershell
docker exec atlas-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group atlas-position-service --reset-offsets --to-earliest --topic atlas.liquidity.buffer-changed.v1 --execute
```

Start position-service again and watch it replay every event ever published, in
order, and arrive at exactly the same numbers:

```powershell
curl.exe -s http://localhost:8082/api/v1/positions
```

**That is what "a projection is disposable" means.** If it is ever wrong, you
delete it and rebuild it. A read model you cannot rebuild is a second source of
truth pretending to be a cache.

---

## 9. The nine things to actually understand

**1. Exactly-once delivery is not a thing you can buy.** The honest framing is
that delivery is at-least-once and *processing* is made idempotent. A natural
business key — here the event id — recorded in the same transaction as the
effect. Anyone who answers "we enable exactly-once semantics" has not thought
about what happens between the broker acknowledging and their database committing.

**2. The database constraint is the guarantee; the code check is an
optimisation.** `existsById` avoids throwing on the common path. The primary key
on `processed_event` is what makes double application impossible. Same argument
as ADR 0006, and it is the discriminator between reading about idempotent
consumers and building one.

**3. One transaction, again.** Apply the change and record the event together. If
the projection commits and the register does not, redelivery double-applies. If
the register commits and the projection does not, the change is lost and never
retried.

**4. A consumer group is the unit of parallelism, and the group id is the most
consequential string in the config.** Each partition goes to exactly one member.
Same id across instances = work is split. Different id = a separate subscription
that replays the whole topic from the beginning. Changing it in production is not
a rename, it is a reset-and-replay.

**5. Partition count caps parallelism.** Concurrency above the partition count
buys nothing; the extra threads sit idle. You cannot easily increase partitions
later either, because it changes which partition a key hashes to — and therefore
breaks the ordering guarantee for keys that move.

**6. Poison messages, and why classification matters.** A malformed payload will
never parse. Retrying it wastes partition time to reach a conclusion you already
have. A database blip deserves the opposite treatment. Systems that treat all
failures identically get one of these badly wrong.

**7. A dead letter topic is an operational commitment.** A record on `.DLT` has
not been processed and never will be unless a person acts. It needs monitoring,
an owner, and a replay runbook. A DLT nobody watches is silent data loss with good
branding.

**8. Blocking retries preserve order and cost throughput; non-blocking retry
topics do the reverse.** `@RetryableTopic` forwards a failure to a separate topic
and lets the partition keep flowing — at the price of the retried message
arriving after messages that came later. Neither is right in general. Say which
requirement you are optimising for.

**9. Database-per-service is what makes this a microservice.** Two modules
sharing a schema must be deployed together and break together. Because this
service learns everything from the topic, the producer can be down for an hour
and reads keep working — which you demonstrated in section 5.

---

## 10. Exercises

1. **Delete the idempotency register.** Comment out the `existsById` check in
   `PositionUpdateService`. Run `duplicateDeliveryIsIgnored` and watch
   `appliedCount` reach 2. Note that the *balance* still looks right, because the
   event carries an absolute value — which is exactly why the test asserts on the
   count and not the money. A weaker test would have passed.

2. **Break the transaction.** Move the `processedEvents.save(...)` call into its
   own method annotated `@Transactional(propagation = REQUIRES_NEW)`. Reason
   carefully about which failure each ordering now produces. Then revert.

3. **Remove the staleness guard** and write a test that applies two events in
   reverse order. Watch the balance go backwards with no error.

4. **Replace the timestamp with a sequence number.** The outbox's generated `id`
   is already monotonic per producer. Carry it in the payload and compare on that
   instead of `occurredAt`. This is the deliberate debt named in ADR 0008, and it
   is a genuinely small change.

5. **Make the DLT visible.** Add a `@KafkaListener` on
   `atlas.liquidity.buffer-changed.v1.DLT` that logs at ERROR with the original
   headers (`KafkaHeaders.DLT_EXCEPTION_MESSAGE`, `DLT_ORIGINAL_OFFSET`). Then
   think about what a real alert on this would look like.

6. **Rebalance under load.** Start two instances, then kill one mid-stream while
   firing adjustments in a loop. Watch the partitions move and confirm no event
   was lost or double-applied. This is the closest you will get to a production
   incident on a laptop.

7. **Try non-blocking retries.** Replace the `DefaultErrorHandler` with
   `@RetryableTopic(attempts = "3", backoff = @Backoff(delay = 2000))` on the
   listener. Watch the `-retry-0` topics get created. Then work out what has
   happened to ordering, and decide whether you would ship it here.

8. **Make the consumer slow.** Add `Thread.sleep(400000)` to the listener and
   watch `max.poll.interval.ms` expire, the broker evict the member, and the
   rebalance loop begin. This is the single most common Kafka production incident
   and it is worth having caused it once.

---

## 11. Interview questions this slice prepares you for

1. Kafka is at-least-once. How do you achieve exactly-once *processing*?
2. Where do you store the deduplication key, and why does it have to be a
   database constraint rather than an application check?
3. Why must applying the event and recording it share a transaction?
4. What is a consumer group, and what happens when you change the group id?
5. What determines the maximum parallelism of a consumer?
6. What happens during a rebalance, and what can go wrong?
7. What is a poison message, and what does it do to a partition?
8. How do you decide which failures to retry?
9. Blocking retries versus retry topics — what does each cost you?
10. What operational commitment does a dead letter topic create?
11. Kafka guarantees ordering — under exactly what conditions?
12. How would you handle an event that arrives out of order?
13. Why does this service have its own database?
14. How would you rebuild a projection that has gone wrong?
15. `max.poll.interval.ms` versus `session.timeout.ms` — what does each detect?
16. Your consumer lag is growing. Walk through how you diagnose it.

---

## 12. Commit

```powershell
git checkout -b feat/L04-consumers-and-dlt
```

```powershell
git add .
```

```powershell
git commit -m "feat(position): add position-service, an idempotent Kafka consumer" -m "Second service, own database, own Flyway history, no compile-time or runtime dependency on reference-data-service. Everything it knows arrives on a topic." -m "Consumption is idempotent: the event id is the primary key of processed_event, so double application is impossible rather than unlikely, and applying the change and recording the event share one transaction. Events older than the state held are discarded and recorded as not applied." -m "Failures are classified: transient ones retried twice a second apart, PoisonMessageException never retried, exhausted records published to <topic>.DLT so one bad message cannot stall a partition. ADR 0008."
```

```powershell
git push -u origin feat/L04-consumers-and-dlt
```

Merge it the same day.

---

## Definition of done

- [ ] `mvn clean verify` green, 150 tests, `Skipped: 0`
- [ ] both services running; an adjustment on 8081 visible on 8082
- [ ] you have stopped reference-data-service and confirmed position reads still work
- [ ] you have poisoned a message and found it on the `.DLT` topic
- [ ] you have run `kafka-consumer-groups --describe` and can explain LAG
- [ ] you have started a second instance and watched partitions redistribute
- [ ] you have wiped the projection, reset the group, and rebuilt it from history
- [ ] exercises #1 and #6 done
- [ ] merged to `main`
- [ ] you can answer 12 of the 16 questions out loud

Then part 3: Avro and a schema registry — so the event contract stops living in a
shared jar and starts living somewhere both services can evolve against
independently — plus the IBM MQ bridge for legacy payment feeds.
