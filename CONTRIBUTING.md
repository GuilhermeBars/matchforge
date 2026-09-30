# Contributing

Use JDK 21 and the checked-in Gradle wrapper. Docker is needed for the
PostgreSQL/Kafka integration tests; memory-profile development needs neither.

```sh
./gradlew --no-daemon bootRun --args='--spring.profiles.active=memory'
./gradlew --no-daemon spotlessApply
./gradlew --no-daemon build
```

Use `.\gradlew.bat` on Windows. The app defaults to port 8080; stop it when
finished. Tests annotated `@Testcontainers(disabledWithoutDocker = true)` skip
without Docker. Report those skips rather than describing them as passed.

- Keep engine, domain, risk and ledger independent of Spring and I/O. Supply
  time/sequence through command envelopes and preserve deterministic replay,
  FIFO priority, exact arithmetic and balanced settlement.
- For behavior changes, add focused tests and update API/architecture docs.
  Changes to commands, snapshots or matching rules must account for existing
  journal replay. Add Flyway migrations; do not rewrite applied ones.
- Keep changes focused. Describe the problem, resulting behavior and verification
  commands in the pull request, including skipped checks.
- Never commit credentials, local tooling/caches, build output or `.codex-logs`.
  Label development defaults clearly. Record benchmark workload, machine and
  limitations before quoting measurements; do not infer production capacity.

Read [architecture](docs/architecture.md), [benchmarks](docs/benchmarks.md) and
[containers](docs/containers.md) for the relevant contracts and workflows.
