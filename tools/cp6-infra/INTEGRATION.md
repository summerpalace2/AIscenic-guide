# Opt-in PostgreSQL / Redis probes

The repository tests connect to real services only. They never launch Spring, use SQLite, import `data/knowledge.db`, substitute mocks, flush Redis, or start/install infrastructure. The normal test run reports seven explicit skips. A skip is not infrastructure acceptance. The separately opt-in HTTP probes below do launch actual Spring application JVMs.

Use exclusively isolated test services, such as the sibling Compose profile or a user-authorized remote test environment. Explicit permission acknowledges that these targets are dedicated to tests; a hostname cannot prove that a service is disposable. PostgreSQL URL database must match the separately specified `CP6_IT_PG_DATABASE`; URL query options and embedded credentials are rejected. Database/schema deletion is confined to the randomly generated test schema. Redis database 15 must be available; optional `CP6_IT_REDIS_USER`, `CP6_IT_REDIS_PASSWORD`, and `CP6_IT_REDIS_TLS=true` support authenticated/TLS services. Do not point these variables at production services.

After starting the disposable services independently:

```powershell
$env:CP6_IT_ENABLE = 'true'
$env:CP6_IT_PERMISSION = 'DISPOSABLE_CP6_TEST_ONLY'
$env:CP6_IT_PG_URL = 'jdbc:postgresql://127.0.0.1:55432/cp6_test'
$env:CP6_IT_PG_DATABASE = 'cp6_test'
$env:CP6_IT_PG_USER = 'cp6_test'
$env:CP6_IT_PG_PASSWORD = 'cp6_test_only'
$env:CP6_IT_REDIS_HOST = '127.0.0.1'
$env:CP6_IT_REDIS_PORT = '56379'
./tools/cp6-infra/run-integration.ps1
```

Every test uses a random `cp6_<uuid>` PostgreSQL schema and random proposal ID in Redis database 15. Cleanup drops only that schema and removes only that key. An interrupted test may leave these disposable resources; discard the dedicated test services after collecting logs. No production application environment variables are consumed. Child JVM working directories are fresh OS temp directories. Credentials are inherited through environment variables, not command arguments.

The runner writes Maven output and a JUnit copy into an OS temporary evidence directory. Exit 2 means probes skipped/not accepted; a failed assertion exits unsuccessfully. Do not describe Maven BUILD SUCCESS with seven skips as passing real infrastructure validation.

Alternatively, pass `-TestEnvFile <absolute-test-file-path>` to the runner. The explicitly selected file must contain only the above `CP6_IT_*` assignments (plus optional Redis user/password/TLS), including the enable flag and disposable-service acknowledgement. Plain `KEY=value`, optional surrounding quotes, blank lines and full-line comments are supported; values are literal and are never expanded or executed. Duplicate/unknown keys, unrelated application keys and missing required values fail before Maven. This mode clears inherited CP6 test settings for the run and restores them afterwards. It does not discover or alter the application's `.env`; do not pass the shared application file.

## Executable coverage

1. Actual PostgreSQL schema creation and repeated initialization preserve the migration ledger; no formal trips are imported.
2. Two independent Java JVMs wait at a start barrier, then initialize the same empty isolated schema concurrently. This exercises cross-process DDL and advisory locks rather than only JVM synchronization.
3. Two real repository objects share Redis. The second warms L1 from Redis; deletion by the first must invalidate the second's observable proposal. This deliberately fails if current L1 coherence is insufficient; do not clear the second cache to hide that defect.
4. A child Java JVM commits a PostgreSQL marker and a real Redis proposal, signals readiness, and is forcibly killed. A fresh JVM reads both snapshots. PostgreSQL and Redis remain running throughout.
5. Two child JVMs read the same formal trip version, then perform competing overlapping edits through actual `TripService`/`TripRepository` inside real transactions. Exactly one wins and one conflicts; version history and final snapshot must equal the winner.
6. Two child JVMs both read the absent idempotency record before a barrier releases their first saves. Both must return the same trip, with one trip row, one version and one completed idempotency record. The barrier prevents a serial replay from being mistaken for concurrent reservation validation.
7. Two child JVMs open one formal trip with the same operation key after a forced absent-record barrier. The real Open application/repository must return one identical pending session without transaction-abort errors or order-dependent fingerprints.

## Two BFFs and two Java applications

`run-bff-cluster.ps1 -TestEnvFile <dedicated-file> -JavaExecutable <java> -ApplicationJar <current-jar> -BffRepository <bff-root> -PsqlExecutable <psql>` invokes the actual cluster probe through the same strict test configuration parser. It requires already running dedicated loopback PostgreSQL/Redis. Each application starts in a separate fresh working directory with minimal explicitly selected environment settings. The BFFs use `YUYOUZHICE_BFF_STATE_STORE=redis`, separate local files and one isolated Redis key prefix; no sticky routing or second login is used to simulate shared authentication.

The probe records current source behavior with real auth/CSRF, formal saves/Open, preview/apply, cancellation and model-history requests. A local HTTP proxy deliberately discards successful Java Open/apply/ack receipts before the BFF receives them; another BFF must recover with the same operation key. A controlled local model and map supplier records actual requests but does not prebuild planning responses or claim model reasoning quality. JSON evidence and first-failure logs belong to the emitted temporary directory. It drops only its random PG schema and stops only child application processes; it never flushes Redis. The orchestrator stops the dedicated database/cache services separately. Feedback/operational metrics, Linux deployment and real suppliers are outside this probe.

## Actual HTTP application instances

After packaging the current application, add `-Http -JavaExecutable <java-exe> -ApplicationJar <jar> -PsqlExecutable <psql-exe>` to the runner. Its Python probe receives the runner's temporary CP6 process configuration, never reads a second env file, and refuses optimized Python because optimization would disable assertions. It only supports explicitly disposable local loopback services; remote/TLS target support of the repository probes is not extended to this local harness.

Two actual HTTP application JVMs start in separate fresh directories and share a random PostgreSQL schema plus isolated Redis database 15. Real registration/JWT authentication, same-key first save, same-version overlapping edits, persisted version counts and read-after-peer-kill are checked. Only that schema is dropped; no Redis flush is performed. The dedicated services are discarded/stopped separately after evidence collection. Application processes stop in `finally`.

This HTTP probe does not certify BFF flows, provider calls, proposal application exactly once or database/Redis service crash recovery. Its process-kill case proves the surviving peer can read the committed formal trip; it does not claim the killed peer restarted.

## Remaining acceptance scope

The crash test verifies **Java client process loss after committed writes**, not loss of PostgreSQL/Redis, a crash during an uncommitted transaction, or full HTTP application recovery. Redis persistence is disabled and PostgreSQL uses tmpfs in the disposable Compose profile; infrastructure process recovery requires a different durable profile.

Full product acceptance still requires two HTTP backend instances and real BFF flows: competing formal saves/version conflicts/idempotency, proposal application exactly once, anonymous/user session claims, acknowledged/pending formal-open cleanup, chat continuation across instances, and timeout/retry behavior. Run both application JVMs from separate fresh working directories so legacy SQLite discovery cannot import user data. These probes do not claim that broader coverage.
