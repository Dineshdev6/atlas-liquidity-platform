# Layer 4, part 3a — Avro and a schema registry

**Goal:** the event contract stops living in a shared jar and starts living
somewhere both services can evolve against independently — and becomes
*enforceable*.

**Prerequisite:** part 2 merged and green.

**One new container** (Confluent Schema Registry), **one new dependency**
(Confluent's Avro serialisers, from a repository that is **not** Maven Central),
and **build-time code generation** from a schema file.

Part 3b is the IBM MQ bridge, kept separate on purpose.

---

## 1. What changed

```
NEW  liquidity-common/src/main/avro/LiquidityBufferChanged.avsc   the contract
NEW  refdata outbox/OutboxAvroSerialiser.java     domain JSON -> Avro wire type
NEW  test outbox/OutboxAvroSerialiserTest.java    3 tests, no registry needed
NEW  docs/adr/0009-avro-and-a-schema-registry.md

CHG  liquidity-common/pom.xml         + avro + avro-maven-plugin (codegen)
CHG  reference-data-service/pom.xml   + confluent repo + kafka-avro-serializer
CHG  position-service/pom.xml         same
CHG  docker-compose.yml               + schema-registry on 8085, wired into kafka-ui
CHG  refdata KafkaProducerConfig      KafkaAvroSerializer, KafkaTemplate<String,Object>
CHG  refdata OutboxPublisher          converts to Avro before sending
CHG  position KafkaConsumerConfig     ErrorHandlingDeserializer wrapping KafkaAvroDeserializer,
                                      two DLT templates (bytes and Avro)
CHG  position LiquidityBufferChangedListener   receives the Avro type, maps at the edge
CHG  both application.yml + application-it.yml  topic v2, registry URL
CHG  test OutboxPublisherTest, LiquidityBufferChangedListenerTest
```

`PositionUpdateService` and its nine integration tests are **untouched** — that is
the payoff of having converted the wire type at the edge in part 2.

Three new tests; you should finish at **153**.

---

## 2. Run it

```powershell
docker compose up -d
```

```powershell
docker compose ps
```

`atlas-schema-registry` should reach `healthy` (it waits for Kafka first, so give
it 30–40 seconds).

```powershell
mvn clean verify
```

**Expect this first build to be slow and to download a lot.** Confluent's
serialisers come from `packages.confluent.io`, not Maven Central, and they pull in
a sizeable tree.

**Likely failure points, in order** — if it breaks, it is probably one of these:

1. **`Could not resolve dependencies … io.confluent:kafka-avro-serializer`** — the
   Confluent repository is unreachable from your network. Nothing to fix in the
   code; tell me and we will pin a different route.
2. **`cannot find symbol: class LiquidityBufferChanged`** — code generation did not
   run. Check `liquidity-common/target/generated-sources/avro/` exists after a
   build. If it does not, the `avro-maven-plugin` execution is not firing.
3. **`NoSuchMethodError` from Avro** — a version mismatch between the generated
   code (1.11.3) and whatever the Confluent artefacts dragged in. Check with
   `mvn -pl liquidity-common dependency:tree`.
4. **Registry connection refused on 8085** — the container is up but not ready, or
   port 8085 is taken.

---

## 3. See the contract

```powershell
mvn clean install -DskipTests
```

Start reference-data-service (terminal 1), position-service (terminal 2), and use
terminal 3 for the rest.

Fire one adjustment, then look at what the registry now knows:

```powershell
curl.exe -s http://localhost:8085/subjects
```

```powershell
curl.exe -s http://localhost:8085/subjects/atlas.liquidity.buffer-changed.v2-value/versions/1
```

That is your `.avsc`, stored outside anyone's codebase, with an id. **Every
message on the topic carries that id instead of its field names.**

Now try to read the topic the old way:

```powershell
docker exec atlas-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic atlas.liquidity.buffer-changed.v2 --from-beginning --timeout-ms 8000
```

Unreadable bytes. The field names are not there. Now the right way — note this
runs in the **schema registry** container, because that is where Confluent's tools
live:

```powershell
docker exec atlas-schema-registry kafka-avro-console-consumer --bootstrap-server kafka:19092 --topic atlas.liquidity.buffer-changed.v2 --from-beginning --property schema.registry.url=http://localhost:8085 --timeout-ms 8000
```

Readable JSON, reconstructed from the registry. **Nothing changed on the wire —
only whether the reader knew how to interpret it.**

The browser view: http://localhost:8080 → your topic, and a **Schema Registry**
section listing the subject and its versions.

---

## 4. Prove the compatibility rule

This is the part worth doing slowly.

**A compatible change.** Add a field to `LiquidityBufferChanged.avsc` — it must
have a default:

```json
{ "name": "correlationId", "type": ["null", "string"], "default": null }
```

Rebuild and restart the producer only. **Leave position-service running on the old
schema.** Fire an adjustment. It still works, untouched, because Avro resolves the
writer's schema against the reader's and fills the missing field with its default.
That is `BACKWARD` compatibility, and it is why consumers can lag behind producers.

**An incompatible change.** Add a field with **no default** and rebuild the
producer. The registry **refuses to register the schema** and the publish fails.
Read the error: it names the incompatibility.

Note carefully which direction breaks, because it is easy to get backwards — the
first draft of this document had it wrong. Under `BACKWARD`, a reader on the NEW
schema must be able to read data written with the OLD one. So:

| Change | Allowed under BACKWARD? | Why |
|---|---|---|
| Add a field **with** a default | yes | old data lacks it; the reader uses the default |
| Add a field **without** a default | **no** | old data lacks it and there is nothing to fall back on |
| Delete a field | yes | old data has it; the reader ignores it |
| Rename a field | **no** | that is a delete plus an add-without-default |
| Change a field's type | usually no | only within Avro's promotion rules (int to long, float to double) |

Under `FORWARD` every row flips, because the old reader is the one that has to
cope. `FULL` requires both, which in practice means: only ever add and remove
optional fields.

Put both changes back. What you have just seen is a breaking change caught on your
machine at deploy time instead of in production, silently, weeks later.

---

## 5. The seven things to understand

**1. The schema is the source of truth; the class is an output.** Generated on
every build, never edited, never committed. Deriving a schema from a Java class
instead makes your wire contract a side effect of a refactor.

**2. `BACKWARD` compatibility dictates deployment order.** New schema reads old
data → **upgrade consumers first**. `FORWARD` is the mirror and means producers
first. `FULL` is both. Knowing which mode implies which order is the follow-up
question that separates candidates.

**3. A field that may be absent needs a union with null *and* a default.** Either
one alone is not enough. This is the single most common Avro evolution mistake.

**4. The registry governs compatible change; a topic version handles the rest.**
JSON to Avro is not compatible with anything, so it got `v2`. Using only one of
these two mechanisms is the mistake.

**5. `stringType=String` in the codegen.** Without it Avro generates `CharSequence`
holding its own `Utf8`, and `equals` against a `String` silently returns false.

**6. `ErrorHandlingDeserializer` is not optional with Avro.** Without it, a message
that cannot be deserialised throws inside the Kafka client during `poll()` —
before your code, before the error handler, before anything that could
dead-letter it. The container polls again, gets the same record, and the partition
never advances. That is the most common way an Avro consumer wedges itself.

**7. Two dead-letter templates, because there are two kinds of failure.** A record
that failed to deserialise has only its original bytes; one that failed in your
code still has its Avro object. One serialiser cannot handle both.

---

## 6. Interview questions this slice prepares you for

1. How do you evolve an event schema without breaking consumers?
2. What does `BACKWARD` compatibility mean, and what does it imply about the order
   you deploy producers and consumers in?
3. What has to be true of a field you want to add? And to remove?
4. What is actually on the wire in an Avro Kafka message?
5. Why can't `kafka-console-consumer` read it?
6. Where does the schema registry store its schemas?
7. What happens to your consumers if the registry goes down?
8. Avro versus JSON versus Protobuf — when would you pick each?
9. When is a new topic version the right answer instead of a schema change?
10. Why generate the class from the schema rather than the other way round?
11. What is `TopicNameStrategy`, and when would you use `RecordNameStrategy`?
12. Should applications auto-register schemas in production?
13. Why must an Avro consumer wrap its deserialiser?
14. How do you represent money in Avro, and why not a `double`?

---

## 7. Commit

```powershell
git checkout -b feat/L04-avro-schema-registry
```

```powershell
git add .
```

```powershell
git commit -m "feat(events): publish Avro against a schema registry" -m "The event contract moves from a shared Java record to an .avsc file registered with Confluent Schema Registry, with the Java class generated at build time. Compatibility is BACKWARD, so the registry refuses a schema an existing consumer could not read - a breaking change now fails at deploy time instead of silently in production." -m "New topic v2: JSON to Avro is not a compatible change, which is exactly what a version in the topic name is for. v1 keeps its history and nothing had to be migrated." -m "Conversion happens in the outbox relay rather than at record time, to keep the registry off the business write path - a registry outage must not fail payments. The consumer wraps KafkaAvroDeserializer in ErrorHandlingDeserializer, without which an undeserialisable record blocks its partition forever. ADR 0009."
```

```powershell
git push -u origin feat/L04-avro-schema-registry
```

---

## Definition of done

- [ ] `mvn clean verify` green, 153 tests, `Skipped: 0`
- [ ] `curl http://localhost:8085/subjects` lists your subject
- [ ] you have seen `kafka-console-consumer` print bytes and
      `kafka-avro-console-consumer` print JSON for the same messages
- [ ] you have added a compatible field and watched an un-upgraded consumer carry
      on working
- [ ] you have made an incompatible change and watched the registry refuse it
- [ ] merged to `main`
- [ ] you can answer 10 of the 14 questions out loud

Then part 3b: the IBM MQ bridge — a real IBM MQ container, JMS rather than Kafka,
and the question of how a legacy payment feed reaches an event-driven platform.
