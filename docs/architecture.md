# Matchforge architecture

Sessions 1–2 deliver domain values, validated configuration, the local journal,
schema, build/test infrastructure, and the framework-free matching/risk/ledger core.
The HTTP API, durable adapters, dispatcher, publishers and recovery orchestration
below remain the implementation plan for subsequent sessions.

```mermaid
flowchart LR
    REST[api.OrderController / AccountController] --> D[engine.CommandDispatcher]
    D --> S[engine.CommandSequencer]
    S --> WAL[journal.Journal: command WAL]
    WAL --> E[engine.MatchingEngine: one writer]
    E --> I[engine-owned idempotency outcomes]
    E --> R[risk.RiskManager / AccountBook]
    E --> B[engine.OrderBook / PriceLevel]
    E --> L[ledger.Ledger]
    E --> EV[engine.DomainEvent batches]
    EV --> P[events.EventPublisher]
    P --> WS[ws.MarketDataWebSocketHandler]
    P --> M[events.MetricsEventListener]
    P --> K[events.KafkaEventPublisher: optional]
    EV --> V[api.ExchangeReadModel / ledger.LedgerReadModel]
    E --> SS[journal.SnapshotStore]
    SS --> REC[journal.RecoveryService]
    WAL --> REC
    REC --> E
```

## Boundaries and concurrency

All packages live under `io.github.guilhermebars.matchforge`. `domain`, `engine`,
`risk`, `ledger` core and `idempotency` core have no Spring dependency.
`MatchforgeApplication`, `config.MatchforgeProperties` and adapter configurations
own framework wiring. REST DTOs do not enter the engine directly.

`CommandDispatcher` owns a bounded queue and one executor thread, with one
`CompletableFuture<CommandResult>` per submitted command. `CommandSequencer`
assigns sequence and timestamp before persistence. Account IDs are caller-supplied;
the engine allocates monotonic order/trade IDs, with counters included in snapshots.
`MatchingEngine.process(CommandEnvelope)` receives sealed command records
`PlaceOrder`, `CancelOrder`, `ReplaceOrder`, `CreateAccount`, `Deposit`, and
`Withdraw`. It returns an immutable `CommandResult` and ordered `DomainEvent`
batch; it never reads a clock, generates random IDs or performs I/O.

`OrderBook` stores bids descending and asks ascending in `TreeMap<Long, PriceLevel>`.
`PriceLevel` uses an insertion-ordered `LinkedHashMap<OrderId, OpenOrder>` as its
FIFO queue; replacing a value preserves its position. A per-book `OrderId` index
locates the level for O(1) queue removal (level lookup/removal costs O(log levels)).
Cancel/replace currently locates the symbol across the configured books; this is
O(number of symbols), followed by indexed removal. `ReplaceOrder` keeps priority for a
quantity decrease at the same price; a price change or increase loses priority.
IOC and market remainders expire. FOK preflights liquidity, risk and self-trade
constraints before any mutation. Self-trade policy is **reject newest**: preflight
rejects the entire incoming command if its executable path would hit its own
resting order, preventing partial side effects before rejection.

Replace quantity means **new remaining quantity**, and the order ID stays stable.
A requeued replacement emits `OrderCancelled(REPLACED)` and `OrderReplaced`, then
any trades/resting event. Same-price equal quantity also retains priority. A failed
replace leaves the original order and reservation intact. FOK kill changes no
books, balances, ledger or trade IDs; command sequence, an allocated cancelled
order ID and its idempotency outcome are still recorded. Events carry the caller's
sequence/timestamp; trades additionally have a globally increasing trade ID.

Reads use atomically published immutable `ExchangeReadModel` views, or queue a
read on the engine thread. They never traverse mutable books from HTTP threads.
The single writer simplifies deterministic replay and cross-asset settlement but
limits throughput to one core. Symbol sharding is a roadmap item requiring a
coordinated account reservation design. Shutdown stops admission, drains accepted
commands, then closes publishers, the executor and database resources.

## Fixed point and risk

`Price(long units, int scale)` and `Quantity(long units, int scale)` accept scales
0..18, parse unsigned plain decimal strings exactly, and format without exponents.
Scale is part of identity; arithmetic/comparison requires canonical scales.
Zero represents empty balances/remainders; `InstrumentConfig` rejects zero order
price/quantity and enforces exact tick/lot multiples and configured scales.
Identifiers preserve case and reject whitespace. Assets/symbols are uppercase;
account, order and client order IDs allow 1..128 ASCII identifier characters.

`Price.multiply(Quantity, resultScale)` uses `Math.multiplyExact` before exact
rescaling; overflow (even an intermediate product) and fractional output atoms
are rejected, never silently rounded. Engine command amounts are canonical long
atoms: base settlement scale equals quantity scale, quote settlement scale equals
price scale + quantity scale. Thus notional is `Math.multiplyExact(price, qty)`.
The current instruments use price scale 2, base scale 8 and USD scale 10. The engine
constructor enforces one consistent scale per shared asset and a maximum scale of
18; incompatible market configurations fail immediately. Total customer holdings
per asset are capped at `Long.MAX_VALUE`: deposits preflight this bound, ensuring
all subsequent transfers and reservation releases can be represented without
overflow. Bounds checks precede state mutation.

`AccountBook` owns available/reserved amounts per `(AccountId, Asset)`.
`RiskManager` checks known instruments, positive tick/lot-compliant sizes and
sufficient funds. Buy limits reserve limit price times quantity in quote atoms;
sells reserve base quantity. Market buys require an explicit positive quote
budget (in quote settlement atoms), reserve that entire budget, and stop before
exceeding it. A budget above available funds is rejected. Affordable quantity is
rounded down to whole lots; market remainders never rest. On fills, reservations settle to the
counterparty; buy price improvement is released immediately. Cancel/expiry
releases the remaining reservation. Replace preflights its complete reservation
delta before modifying the existing order. Fees default to zero; later fee
support must include fees in reservations and credit a `FEES` house account.

## WAL, snapshots and recovery

`Journal` currently has a volatile `InMemoryJournal`; `PostgresJournal` will
implement the same contiguous sequence contract with committed inserts into
`journal_entry(seq, type, payload, created_at)`. Payloads are versioned command
envelopes containing all replay inputs; event envelopes may be stored too, but
recovery applies commands once, never both commands and their derived events.
If event entries use journal sequences, their ordering must be deterministic and
snapshots must always end on a fully completed command/event batch boundary.

The planned writer commits the command WAL, processes the engine (including its
authoritative idempotency check),
records its result, updates read models and acknowledges the request. A WAL failure
has no engine effects. An unexpected failure after commit fences the writer:
stop admission and recover before continuing. Business rejections are deterministic
results and are replayable. A crash after commit but before response replays the
command and returns its original result on retry.

`SnapshotCoordinator` schedules a snapshot every `matchforge.snapshot.interval`
commands and handles the future admin endpoint. `SnapshotStore` will have
`InMemorySnapshotStore` and `PostgresSnapshotStore` implementations. Snapshots are
versioned immutable copies captured at an engine command boundary, containing
books/FIFO order, balances/reservations, IDs, sequence, idempotency results,
instrument precision/configuration fingerprint and required read-model state.
The implemented version-1 `EngineSnapshot` includes concrete instrument records,
account/balance records, open orders in side/price/FIFO order, complete ledger,
next order/trade IDs, last command sequence and idempotency request/outcome pairs.
`MatchingEngine.restore(snapshot)` validates version, precision and financial/book
invariants. Snapshots use deeply immutable lists and concrete record components;
Jackson plus `JavaTimeModule` round-trips them without core annotations or mixins.
The complete ledger/idempotency history is retained in memory for now; compaction
and bounded retention require a later explicit policy.
Persist only completed snapshots to `snapshot(seq, payload, created_at)`.
`RecoveryService` loads the latest compatible snapshot, replays later commands
without external publication, rebuilds projections and then opens admission.
Malformed/unsupported persisted versions fail startup rather than skip data.
Postgres operation assumes one active writer instance; a database advisory lock
will enforce this. No journal pruning is planned until recovery is proven.

## Idempotency, settlement and delivery

`MatchingEngine` keys placement by `(AccountId, ClientOrderId)`, stores the complete
immutable `PlaceOrder` payload and original `CommandResult.Outcome` (including
original fills). Identical retries return that outcome with `duplicate=true` and
an empty event batch; different payloads return distinct `CONFLICT` status and
`DUPLICATE_CLIENT_ORDER_ID` reason for a future HTTP 409 mapping. Every submitted
envelope still consumes its contiguous command sequence. Lookup/insertion occur
on the writer. Replay and snapshots restore outcomes, including rejections;
transport timeouts must not cause a second order.

`Ledger` creates immutable signed `Posting` records in balanced `Transaction`s: debit buyer
quote / credit seller quote, debit seller base / credit buyer base. Each trade's
postings sum to zero per asset. Deposits and withdrawals use an external clearing
account so all ledger transactions balance; customer plus house balances equal
net external funding. `LedgerReadModel` persists postings transactionally with
unique `(journal_seq, posting_index)` keys for replay-safe updates. The V1 table
is a rebuildable projection; cross-row balancing is enforced by the service,
not falsely claimed as a row-level SQL constraint.

`EXTERNAL` is reserved and cannot be created as a customer. Reserve/release only
moves holdings between available and reserved; it creates no settlement posting.
Deposits, withdrawals and every individual fill emit `LedgerPosted` with balanced
postings. Fees are fixed at zero in this session. `Ledger.trialBalance` uses
`BigInteger` for safe accumulation across arbitrarily long histories;
`assertConservation` reconciles ledger holdings with every account/asset.
`MatchingEngine.assertInvariants` additionally checks reservations against open
orders, book crossing, order validity, IDs and the aggregate holdings bound.

`InProcessEventPublisher` fans out immutable sequenced batches to WebSocket and
metrics listeners. `/ws/market-data` accepts book/trade subscriptions and publishes
aggregated top-N depth and trade prints with sequence numbers. Slow subscribers
use bounded queues and disconnect/resubscribe on gaps; they never block matching.
`KafkaEventPublisher` is conditional on `matchforge.events.publisher=kafka`, topic
`matchforge.events`. Producers are not started in the default in-process mode.
Future durable delivery needs a journal cursor/outbox and stable event IDs:
delivery is at least once with consumer deduplication, never claimed exactly once.
Publisher failures cannot roll back committed trades. Metrics include accepted/
rejected orders, trades, cancels, command/journal latency, book depth and sequence.

## Configuration, verification and limitations

Default configuration targets PostgreSQL on localhost:5432; `DB_URL`,
`DB_USERNAME`, `DB_PASSWORD`, `JOURNAL_TYPE`, `EVENTS_PUBLISHER` and
`SNAPSHOT_INTERVAL` override the defaults. `--spring.profiles.active=memory`
excludes DataSource and Flyway autoconfiguration and wires `InMemoryJournal`.
`local` includes `memory`; neither requires Docker. All local state is lost on exit.
Setting only `JOURNAL_TYPE=memory` does not disable database autoconfiguration;
use the profile for database-free startup. Port is 8080.

Run `./gradlew --no-daemon build` (`.\gradlew.bat --no-daemon build` on Windows).
Java 21 must already exist; toolchain auto-download is disabled. JUnit Jupiter and
jqwik both run, and test finalizes XML/HTML JaCoCo reporting. JMH sources belong
in `src/jmh/java`; no engine performance results exist yet. Later integration
tests must use `@Testcontainers(disabledWithoutDocker = true)` locally and run
with Docker in CI. Engine JUnit tests cover matching, risk, FIFO/replacements,
atomic FOK/self-trade rejection, settlement, idempotency and JSON snapshots.
Two jqwik properties each run 60 generated sequences of 30–100 actions across two
symbols and three funded accounts, checking invariants after each command and
identical replay/snapshot continuation. REST, durable restart and WebSocket
integration tests belong to later sessions.

Authentication is out of scope: requests will carry account IDs, so the planned
API must not be exposed as a production exchange. The core is implemented;
the API, Postgres journal/recovery, snapshot persistence and publication remain
for later sessions. Core reads and snapshots must run on the owning writer thread.
