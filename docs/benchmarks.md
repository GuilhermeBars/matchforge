# Benchmark and load harnesses

Use JDK 21 and the checked-in wrapper. Neither harness needs `.tools/`.
On Windows use `.\gradlew.bat`; on Linux/macOS use `./gradlew`.

## Engine and journal microbenchmarks

```powershell
.\gradlew.bat --no-daemon jmh
```

Defaults: one thread, one fork, two 1-second warmup iterations and three
1-second measurement iterations per benchmark, with a 256 MiB fixed heap.
Including compilation, this is intended to take a few minutes. JSON results
are written under `build/results/jmh/`.

`EngineBenchmark.mixedOrderFlow` processes one BTC-USD book around a $50,000
midpoint, seeded with 20 bid and 20 ask levels. Each batch has 1,024 commands:
50% resting GTC limit placements, 25% crossing buy IOC placements and 25%
cancels. Distinct buyer/seller accounts avoid self-trades. Each order is 1,000
base atoms (0.00001000 BTC); spreads cycle through 1–20 price ticks. Every
cross trades and every cancel targets a live bid. Rejections fail the run.
JMH normalizes the batch with `@OperationsPerInvocation(1024)`, so ops/s means
**commands/second**, including cancels, rather than orders or trades/second.

A fresh funded engine and precomputed commands are prepared outside the timed
method before each invocation. This bounds retained order, ledger and
idempotency history. Setup allocations can still affect GC and cache behavior.
Results describe short bounded batches, not steady-state performance with an
ever-growing history. The timed path includes matching, risk, settlement,
idempotency and returned events, but excludes REST, writer queues, read-model
publication, snapshots and durable I/O.

`JournalBenchmark.serializeCommand` measures `PersistenceCodec.encode` on a
representative limit command, producing the actual polymorphic journal JSON
payload. It does not measure PostgreSQL, fsync, network latency or replay.

## REST load generator

Start an isolated disposable server, either with Compose (see
[containers](containers.md)) or an in-memory process:

```powershell
.\gradlew.bat --no-daemon bootRun --args="--spring.profiles.active=memory"
```

In another terminal:

```powershell
.\gradlew.bat --no-daemon loadTest -PbaseUrl=http://localhost:8080 -Pclients=8 -Pseconds=30
```

The `src/loadtest` CLI uses Java 21 virtual threads and one synchronous,
outstanding HTTP request per client. It creates distinct accounts and deposits
$1,000 each before timing. During measurement it submits unique BTC-USD buy
limit IOC orders at $0.01 for 0.00000001 BTC. On an empty/default book these
expire, exercising REST validation, sequencing, journal append, risk,
reservation release and read-model publication without exhausting funds or
leaving open orders. This is an expiring-order write workload; it is not the
mixed matching workload used by JMH.

Output includes attempted and successful requests/second, error counts, and
nearest-rank p50/p95/p99/max latencies in milliseconds from a sorted array of
all attempts (including errors). Setup is excluded; in-flight drain time is
included in elapsed time. Requests have a five-second timeout. HTTP non-2xx
and transport failures count as errors and make the task fail after reporting.
This closed-loop test has no arrival-rate control or coordinated-omission
correction. Sample storage grows with completed requests. Use short runs:
the server retains history and copies read models per command, so duration
and existing history materially affect performance. No production capacity
claim follows from this harness. Stop the server when finished.

## Formatting and verification

```powershell
.\gradlew.bat --no-daemon spotlessApply
.\gradlew.bat --no-daemon build
```

Spotless uses Palantir Java Format, removes unused imports and rejects wildcard
imports in main, test, JMH and loadtest sources. `check` includes
`spotlessCheck` and compiles both harnesses. CI runs the full test suite and
publishes test/JaCoCo reports plus coverage counters in the job summary.

## Local measurement

Measured on 2026-09-30 with **one invocation of JMH**, using the defaults above
(JMH 1.36, compiler blackholes, 1 fork, 1 thread, 2 × 1s warmup, 3 × 1s
measurement, `-Xms256m -Xmx256m`). Both benchmarks completed successfully.

| Benchmark | Mean throughput (ops/s) | JMH error (99.9%, ± ops/s) |
|---|---:|---:|
| `EngineBenchmark.mixedOrderFlow` | 1045704.797 | 94278.623 |
| `JournalBenchmark.serializeCommand` | 1505266.658 | 1396313.017 |

The engine row is commands/second; serialization is JSON payloads/second.
These are **indicative, single-machine numbers**, not production capacity or
REST throughput. Three short samples cannot establish stable performance; the
serialization confidence interval is especially wide. No database or load
server ran during JMH, and no benchmark rerun was selected to improve results.

Machine information retrieved locally:

- CPU: Intel(R) Core(TM) i5-10400 CPU @ 2.90GHz (Windows registry).
- 12 logical processors available to Java. Physical core count could not be
  queried through WMI because sandbox access was denied.
- Physical RAM: 34232926208 bytes (31.882 GiB), from Java's OS MXBean.
- OS: Windows 10, version 10.0, amd64, as reported by Java system properties.
- JDK: Eclipse Adoptium Temurin 21.0.12.1+1-LTS, 64-bit Server VM.

Actual local command (Windows sandbox compiler workaround):

```powershell
.\gradlew.bat --no-daemon jmh --init-script .codex-logs/sandbox.init.gradle
```

Result: `BUILD SUCCESSFUL in 42s`; JMH reported 11 seconds for the benchmarks.
Raw JSON: `build/results/jmh/results.json`. The local console transcript is
`.codex-logs/session5-jmh.log` (both paths are ignored build artifacts).
The init script only packages identical classpath classes into JARs and patches
the compiler's ZIP filesystem cleanup to accommodate sandbox path restrictions;
the benchmark forks ran with the stock JDK and the heap flags shown above.
Normal machines and CI use the wrapper without this local-only init script.

An additional CLI smoke check used a fresh memory-profile server on port 8080:

```powershell
.\gradlew.bat --no-daemon loadTest -Pclients=2 -Pseconds=3 --init-script .codex-logs/sandbox.init.gradle
```

Actual output (a functional smoke check, not a durable-REST capacity benchmark):

```text
clients=2 elapsed=3.001s requests=3059 successes=3059 errors=0 throughput=1019.285 req/s successful=1019.285 req/s
latency (all attempts): p50=1.656ms p95=3.301ms p99=4.244ms max=76.627ms
```

The full test suite after formatting reported 123 tests, zero failures/errors,
and six Docker-gated skips (Postgres and Kafka unavailable locally). Compose
configuration validated for both default and Kafka profiles. Docker image
build and container smoke execution could not run locally; CI provides those
checks. Boot jar layer extraction was verified locally.
