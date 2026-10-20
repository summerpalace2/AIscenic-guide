# CP4.4 R5 — USER Open capability contract correction

Status: frozen for independent B/C review; not accepted.

This candidate reverses the R4 assumption that a USER-owned planner session returns a raw session capability. The repository issues raw access tokens only for ANONYMOUS sessions; authenticated USER sessions are authorized by owner identity. `PlannerOpenApplicationService` therefore returns no `sessionAccessToken` field. The application test stubs `CreateResult` with an empty token and verifies neither the Open response nor its Trip snapshot serializes a capability. The formal Trip GET no-leak assertion remains in `TripControllerTest`.

The BFF Open validator accepts an absent or string `sessionAccessToken` (including the empty string) for the authenticated USER Open route, while rejecting any present non-string value. BFF creates its owner-bound session with an empty capability when absent. A follow-up session GET test verifies the BFF passes the Java owner token and empty capability value to the Java route; the Java client contract test verifies the owner bearer token is sent and the empty capability does not become an `X-Plan-Session-Token` header.

R3's single Java Open snapshot, no-second-`getTrip` behavior, consistency checks, and delayed BFF session registration remain in place. Invalid Java Open snapshots fail closed before BFF workspace/draft persistence or live-session registration. Open failure persistence can still leave an orphan Java session; no remote cancellation protocol was added.

## Scoped changes

- Java `PlannerOpenApplicationService`: no raw capability field is serialized for USER Open.
- Java `PlannerOpenApplicationServiceTest`: realistic `CreateResult.created(..., "")` behavior and response/Trip no-capability assertions.
- Java `PlannerControllerTest`: removed R4-only fabricated Open capability JSON tests; retained owner-context Open delegation and ordinary planner session GET no-leak coverage.
- BFF `server/index.mjs`: optional-string validation and empty fallback for USER Open capability.
- BFF `server/formal-trip-bff-contract.test.mjs`: fixture reflects actual Java Open shape; tests missing/empty/non-string capability, fail-closed side effects, owner auth on follow-up, and Java client header behavior.
- Existing R3 single-snapshot and delayed-registration production behavior is unchanged.

No stage or commit was performed. Existing dirty files outside the above scope were left untouched. The whole-repository dirty patch hashes in the candidate include pre-existing user/CP changes; reviewers should use the scoped file hashes and manifest to identify this R5 slice.

## Verification

Java was tested from a fresh source copy, with a separate Maven `target` and SQLite state:

```text
./mvnw.cmd "-Dtest=PlannerOpenApplicationServiceTest,PlannerControllerTest,TripControllerTest,ProposalRepositoryPortContractTest,PlannerSaveConsistencyTest" test
Tests run: 50, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

BFF regression command:

```text
node --test server/formal-trip-bff-contract.test.mjs server/draft-restore-route.test.mjs server/planner-bridge-contract.test.mjs server/trip-delete-version.test.mjs
29 tests, 29 pass, 0 fail, 0 skipped
node --check server/index.mjs
node --check server/formal-trip-bff-contract.test.mjs
git diff --check
```

## Limits

- Java suite used isolated SQLite and application/MockMvc tests, not a live Java/BFF network integration.
- BFF VM tests use controlled Java/persistence fakes. The Java HTTP client is tested with mocked `fetch`; no live services were called.
- USER capability omission depends on the established owner identity authorization contract. Anonymous session capability behavior is outside this Open path and unchanged.
- BFF persistence failure after successful Java Open can leave the Java-side session; there is no compensating remote delete endpoint in this slice.
