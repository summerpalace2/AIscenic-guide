# CP4.4 R10 — Java Open session compensation acceptance

Date: 2026-10-10  
Result: **compensation implementation and isolated contract tests pass; CP4 remains open**

## Finding

The BFF can receive a successful Java Open response (which has already persisted a Java planner session) and then fail while atomically storing the matching BFF draft/workspace. Before this slice, no Java operation could remove that newly created, now-unreachable session. A malformed Java Open response carrying a session ID had the same possible leak.

## Change under review

- Java now exposes `DELETE /ai/planner/v1/trips/{tripId}/open-sessions/{sessionId}` through the planner application boundary.
- The application use case authorizes the authenticated user and only accepts a session whose formal/source trip IDs match the route trip, whose restore marker is `RESTORED_FROM_TRIP`, and whose version/revision remain at the untouched initial state.
- The repository conditionally removes only a matching USER-owned session at the expected version and initial sync revision, then removes its initial revision in the same transaction. Missing sessions are treated as idempotent success; changed, mismatched, or other-owner sessions are retained.
- BFF calls compensation when it has a returned Java session ID and either rejects an invalid Open contract or cannot persist the corresponding local Open state. Compensation failures are recorded as diagnostics and do not get misreported as successful cleanup.
- The repository deletion test was moved out of the Spring application context and now uses a per-test temporary SQLite database. Existing `PlannerSessionRepositoryTest` remains a persistent-database integration test and must not be included in routine acceptance runs without isolation.

## Verification performed

| Scope | Command | Result |
|---|---|---:|
| Java application, controller, repository deletion, architecture | `./mvnw.cmd test -Dtest=ColaArchitectureGuardTest,PlannerOpenApplicationServiceTest,PlannerControllerTest,PlannerSessionRepositoryDeletionTest` | 43 passed, 0 failed/errors/skipped; BUILD SUCCESS |
| BFF, scripts, frontend feature tests | `node --test server/*.test.mjs scripts/*.test.mjs frontend/web/src/features/**/*.test.mjs` | 140 passed, 0 failed/cancelled/skipped |
| Java temporary SQLite repository tests | Included in the Java command above | 2 passed; test database is under JUnit `@TempDir` |

The BFF tests verify compensation invocation for invalid Open contracts and local persistence failures, no BFF session/draft/workspace publication on failure, diagnostic behavior when Java cleanup itself fails, and the Java client DELETE route. Java tests verify authentication, owner/trip/source/version/revision guards, idempotent absent-session handling, and repository deletion behavior.

## Test-data incident and cleanup

An earlier targeted test invocation accidentally included the existing `@SpringBootTest` class `PlannerSessionRepositoryTest`, whose application configuration points at `D:\scenic-guide\ai\data\knowledge.db`. This was an unsafe test-isolation choice. I removed only rows matching the unique fixtures of those test methods and the two new Open-compensation fixtures, plus their associated revision rows: **10 synthetic planner sessions and 14 revision rows**. A follow-up query confirmed zero matching rows remain. No whole database restore or broad cleanup was performed.

The Spring context also ran normal knowledge-database initialization; its test log reported that 50 dining venue seed rows were inserted. There was no pre-test database snapshot, so their prior absence cannot be proven and they were not removed because they may be valid project seed data. Future repository tests in this area must use an isolated temporary database or mocks.

## Limits and remaining CP4 gates

- This is a compensating action across Java and BFF, not a distributed transaction. If the Java Open response is lost before the BFF learns the session ID, or the compensation DELETE itself cannot reach Java, an orphan may remain. BFF diagnostics expose the latter; automatic reconciliation/TTL cleanup is not implemented.
- Tests used Java unit/MockMvc coverage and BFF fakes; no live Java↔BFF HTTP path was run. The browser lifecycle remains the separately documented synthetic-fixture verification, not a real online service integration.
- Real PostgreSQL/Redis, multi-instance, process-crash, and network-loss validation remain out of scope for this CP4 slice.
- Therefore R10 passes within its implementation/test boundary, but CP4 is **not complete**. A safe isolated live Java+BFF flow and the remaining stale-response/browser evidence must still be assembled before the checkpoint exit criteria can be declared met.
