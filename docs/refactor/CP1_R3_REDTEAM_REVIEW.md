# CP1-R3 red-team review

Date: 2026-10-09

## Verdict

**FAIL — the R3 improvements close the R2 examples in the ordinary single-process path, but acceptance should wait for two boundary fixes and stronger lifecycle evidence.** R3 adds a local deletion tombstone, deletes old L2 data for expired same-ID writes, and validates several restored fields. However, a future-dated `createdAt` can make a snapshot with a plausible 15-minute lifetime restorable far beyond the intended absolute lifetime, and `expiresAt` equality is still considered live. The tombstone is also a bounded process-local cache, so the repository's remove contract is not durable after eviction/restart; application epoch guards mitigate restart re-application but the tests do not fully prove cancellation of a previously valid proposal across that boundary.

No source was changed and Maven was not run.

## Frozen identity

- Candidate JSON SHA-256 observed: `fa9bf57a2785b0ee490fb983f888810667e6b8d4e32fe7e0569d6992f554d97a`.
- Tracked patch SHA-256 observed: `dedb565477e4fc688a80d13ba5060e7537f5b44d698bb272520ec70c1da6100b`.
- R3 freeze records adapter SHA-256 `1f37b0c1f84acc1154a1977c74f1ea33314c3a261612b6d73c322ab751926961` and contract-test SHA-256 `664a84b1cb5bbb87349bb400897c988e8af4602016e7a0b080b4e36f91fca83b`; both match the current files.
- Review is limited to this frozen candidate.

## Review evidence

Primary files:

- `ai/src/main/java/com/ai/guide/infrastructure/planner/proposal/CaffeineRedisProposalRepository.java`
- `ai/src/test/java/com/ai/guide/infrastructure/planner/proposal/ProposalRepositoryPortContractTest.java`
- `ai/src/main/java/com/ai/guide/domain/planner/model/PlanAdjustmentProposal.java`
- `ai/src/main/java/com/ai/guide/domain/planner/service/PendingIntentContinuations.java`
- `ai/src/main/java/com/ai/guide/domain/planner/service/PlanAdjustmentService.java`
- `docs/refactor/COLA_REFACTOR_EXECUTION_PLAN.md:82-94`
- `docs/refactor/CP1_IMPLEMENTATION_REPORT.md` section “CP1-R3 follow-up”

| Area | Verdict | Exact evidence and limits |
|---|---|---|
| R2 queued put/remove, same-ID put ordering, in-flight SET/remove, and L2 GET/remove | **PASS (single-process test seam)** | R2 deterministic cases remain in `ProposalRepositoryPortContractTest`: queued put/remove lines 82-105; out-of-order same-ID tasks 110-138; overlapping puts 142-198; delayed GET/remove 202-260; in-flight SET/remove 264-314. Production uses the same 256-stripe monitor for get, put, write, remove and generation accounting (`CaffeineRedisProposalRepository.java:96-193`). Tests use a manual executor and Mockito Redis, not real Redis. |
| Redis DELETE exception in same process | **PASS (tombstone retained)** | `deleteFailureTombstoneBlocksRehydrationAndReconstructedApplyWithoutSqlWrite`, test lines 338-379, restores a mock L2 proposal, forces DELETE to throw, clears L1, then asserts subsequent get is null. Production `deleteRedisSnapshot` writes a tombstone on missing Redis or caught delete exception (`repository.java:210-221`); `get` checks it before L2 (`153-192`). The same repository instance retains the tombstone; this is not repository reconstruction or process restart. |
| Tombstone lifecycle on successful delete / successful replacement | **PASS by inspection** | Successful delete invalidates tombstone (`repository.java:215-221`); successful matching-generation Redis SET invalidates it (`126-141`). Per-key stripe makes these operations serial in one process. |
| Tombstone capacity and restart behavior | **PARTIAL / contract limitation** | Tombstones use a Caffeine cache with `maximumSize(10_000)` and 15-minute expire-after-write (`repository.java:27-34`). A size eviction can remove a still-needed tombstone before the referenced proposal's absolute expiry; a process restart loses all tombstones. A later GET may then rehydrate stale L2. The report acknowledges bounded same-process protection; no eviction/restart test exists. `PlanAdjustmentService.doApply` additionally rejects stale epochs (`PlanAdjustmentService.java:747`), and `PendingIntentContinuations.begin/reset` rotates random epochs (`PendingIntentContinuations.java:16-39`), which mitigates apply after service reconstruction. But the R3 “reconstructed apply” test reconstructs only `PlanAdjustmentService` while reusing the same repository/tombstone (test 367-378), and its `epoch-from-old-service` is not established as current in the fixture. It therefore does not prove a previously valid-current proposal is cancelled safely after actual repository/process reconstruction. |
| Expired put reusing an ID | **PASS for tested delete success; PARTIAL for delete failure** | `expiredPutForExistingIdInvalidatesOldL1AndL2Snapshot` lines 383-410 creates L2, puts expired same-ID value, clears L1, and asserts L2 deleted. Production expired branch invalidates L1, increments generation, and calls best-effort delete (`repository.java:91-113`). If DELETE fails, tombstone blocks same-process restoration, but protection has the capacity/restart limits above. |
| Snapshot ID / required-field validation | **PASS for asserted cases** | `isRestorable` checks requested ID equality, nonblank session/epoch, rejects `INITIAL`, requires positive revision/createdAt, bounded lifetime, and not expired (`repository.java:224-232`). Test 414-448 covers mismatched ID, missing session/epoch/revision/expiry, and expiry beyond createdAt+TTL, then asserts no L1 restore. |
| Future-dated creation time | **FAIL** | `isRestorable` only checks `createdAt > 0` and `expiresAt-createdAt <= DEFAULT_TTL_MS` (`repository.java:229-231`); it never requires `createdAt <= now` or bounds expiry against now. A snapshot with `createdAt = now + N days` and `expiresAt = createdAt + 15 minutes` passes all current checks and `isExpired()` (`PlanAdjustmentProposal.java:56-59`), so it can be accepted as a valid proposal far past its real creation/expected TTL. No test includes future `createdAt`. Validate against current time with safe arithmetic and reject implausible future creation timestamps. |
| Malformed JSON | **PARTIAL (parse failure is fail-closed)** | Deserialization is inside catch at repository lines 173-190, returning null on parse/type exception; no malformed-JSON-specific test is present. Missing-field semantic cases are tested as above. Invalid snapshot deletion is best effort; failed delete leaves tombstone. |
| Exact expiry boundary | **FAIL / untested edge** | `PlanAdjustmentProposal.isExpired()` returns `now > expiresAt` (`PlanAdjustmentProposal.java:56-59`), so equality is still accepted by `get` and apply. Conversely, `put` classifies `remainingMs <= 0` as expired (`repository.java:91-93`). Thus at the exact boundary put and get disagree. No test controls the clock or asserts equality. Use an exclusive deadline (`now >= expiresAt`) consistently and test just-before/equal/just-after. |
| Database side effects on failed apply | **PARTIAL** | The R3 deletion-failure test uses a temporary SQLite database and asserts unchanged revision plus `sessions.update` never called (test lines 338-379). It does not create a currently valid continuation epoch before removal; the fixture uses `epoch-from-old-service`, so apply may fail on the epoch check independently of the tombstone. The separate restored-old-proposal test at lines 452 onward also tests missing/stale epoch. Add a valid-current-epoch proposal, cancel/remove it with DELETE failing, force L1 loss (or tombstone eviction/repository recreation as contract intends), and assert no DB update. |
| Redis TTL argument and real Redis semantics | **BLOCKED / not verified** | `put` uses `max(60, remainingMs/1000)` (`repository.java:91-94`) and restored payload checks absolute expiry, but tests do not assert the actual `Duration` or run real Redis. The R3 raw log states 92 tests passed (0 failures/errors/skips); report says Redis paths use Mockito and SQLite is temporary. I inspected that existing log only; I did not run Maven. |

## Required closure

1. Reject future-dated `createdAt` snapshots and add a bounded clock-skew policy; compare absolute expiry safely against current time, not only against creation time.
2. Make expiry semantics consistent and exclusive (`now >= expiresAt`) and add deterministic before/equal/after boundary tests.
3. Add a cancellation/apply regression with a proposal whose epoch is demonstrably current before cancellation; make Redis DELETE fail, lose the L1 entry, and prove no DB update. Test repository reconstruction if that is part of the claimed protection.
4. Document and accept the process-local tombstone capacity/restart limits explicitly, or use a cross-process/durable invalidation mechanism if repository `remove` must be authoritative under Redis failure. Add a capacity-eviction test if the bounded local behavior remains part of the contract.
5. Assert Redis TTL duration and preserve the explicit limitation that Mockito does not establish real Redis/network or multi-node behavior.

## Verification boundary

I only read source, tests, freeze metadata, and the pre-existing isolated Maven log. The log reports 92 targeted tests passing, but all Redis interactions in these cases are mocked; no real Redis, cross-process behavior, full Java suite, or new test run was performed.
