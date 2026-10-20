# CP6 disposable integration services

This profile starts isolated PostgreSQL and Redis containers for integration testing. It binds only to loopback, uses test-only credentials, and stores data in container `tmpfs`; stopping the containers discards their data. It does not connect to production services.

Start the dependencies and run connection smoke checks from the repository root:

```powershell
.\tools\cp6-infra\verify-infra.ps1
```

The services remain available for application tests at PostgreSQL `127.0.0.1:55432` and Redis `127.0.0.1:56379`. Configure Java with:

```powershell
$env:KNOWLEDGE_DB_TYPE = 'postgresql'
$env:KNOWLEDGE_DB_URL = 'jdbc:postgresql://127.0.0.1:55432/cp6_test'
$env:KNOWLEDGE_DB_USERNAME = 'cp6_test'
$env:KNOWLEDGE_DB_PASSWORD = 'cp6_test_only'
$env:YUYOUZHICE_REDIS_HOST = '127.0.0.1'
$env:YUYOUZHICE_REDIS_PORT = '56379'
```

Stop the profile when finished:

```powershell
docker compose -f tools/cp6-infra/compose.yaml --profile cp6-it down --remove-orphans
```

Starting the services only proves infrastructure readiness. PostgreSQL migration tests, Redis-backed behavior, two-instance concurrency, and process-kill recovery require their own integration probes before CP6 item 2 can pass.

The opt-in repository probes and Java child-process crash protocol are documented in [INTEGRATION.md](INTEGRATION.md). Run `./tools/cp6-infra/run-integration.ps1` after supplying its explicit test-only environment variables. Without opt-in, the runner reports skips and exits 2; it does not certify acceptance.
