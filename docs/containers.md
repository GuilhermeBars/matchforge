# Containers and CI

The multi-stage Dockerfile builds with `eclipse-temurin:21-jdk` and the wrapper,
then extracts the Boot jar into dependency, loader, snapshot and application
layers. The `eclipse-temurin:21-jre` runtime runs as UID/GID 10001 and checks
`/actuator/health` using curl. This follows Spring Boot's
[layer extraction approach](https://docs.spring.io/spring-boot/reference/packaging/container-images/dockerfiles.html).
Only the wrapper, build definitions and source enter the build; portable tools,
local caches and sandbox workarounds are excluded. Tests run separately in CI.

```sh
docker compose up --build --detach --wait --wait-timeout 180
curl --fail http://localhost:8080/actuator/health
curl --fail http://localhost:8080/api/v1/markets
docker compose down
```

Postgres 16 has a healthcheck and a named persistent volume. The app waits for
database health, uses `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, and publishes port
8080. The default password is for local development. `docker compose down`
preserves data; use `docker compose down --volumes` to deliberately discard it.
The API has no authentication and is not suitable for public deployment.

## Optional Kafka

A profile enables Redpanda; selecting a profile alone does not change the
application publisher. Wait for the broker first, then switch the app:

```sh
docker compose --profile kafka up --detach --wait --wait-timeout 180 redpanda
EVENTS_PUBLISHER=kafka docker compose --profile kafka up --build --detach --wait --wait-timeout 180
docker compose --profile kafka down
```

PowerShell equivalent for the second command:

```powershell
$env:EVENTS_PUBLISHER = 'kafka'
docker compose --profile kafka up --build --detach --wait --wait-timeout 180
# After teardown, restore the default for subsequent runs:
Remove-Item Env:EVENTS_PUBLISHER
```

The app uses `redpanda:9092` on the Compose network and publishes
`matchforge.events`. The default in-process mode requires no broker.
Redpanda uses its own named volume. Publication remains best effort across
crashes, as described in [architecture](architecture.md).
Prometheus can scrape `/actuator/prometheus`; no separate observability stack
is bundled.

## GitHub Actions

Pushes and pull requests targeting `main` run two bounded jobs. The Java job
uses Temurin 21 and the Gradle cache, runs `build jacocoTestReport` (including
Spotless), and uploads test and coverage reports even on failure. Docker-gated
Testcontainers tests run on the Ubuntu runner. The Docker job builds the image,
waits for Compose health, checks markets and account creation, captures logs,
and always tears down containers and volumes. Concurrency cancels superseded
runs. LF checkout rules and explicit `chmod +x gradlew` avoid Windows wrapper
line-ending/executable-bit problems without requiring local Git commands.

Docker was unavailable on the Session 5 local machine; image build and Compose
runtime validation must therefore be confirmed by CI, not inferred from the
local Java build.
