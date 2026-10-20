# CP1 implementation report — proposal repository port

Date: 2026-10-09
Scope: CP1 proposal-storage slice only. This report documents implementation evidence for independent review; it is not an acceptance verdict.

## Change summary

The local proposal cache implementation was moved out of `domain.planner.service` into `infrastructure.planner.proposal`. `PlanAdjustmentService` now depends on the domain `ProposalRepositoryPort` contract (`put/get/remove`), while the infrastructure adapter retains Caffeine L1 and optional Redis L2 behavior. Proposal expiry remains an absolute `expiresAt`; the 15-minute default is defined once as `PlanAdjustmentProposal.DEFAULT_TTL_MS` and is used by both the service and Caffeine L1 expiry configuration. Redis read/write/delete failures continue to degrade to local behavior. A proposal absent from both the current local cache epoch and Redis remains unavailable, so apply fails closed.

No user-visible behavior change is intended: proposal lifetime, cache capacity, Redis optionality, and degradation behavior are preserved. The migration changes the dependency direction and where the cache implementation lives.

## Files in the CP1 slice

Production:

- Added `ai/src/main/java/com/ai/guide/domain/planner/gateway/ProposalRepositoryPort.java`.
- Added `ai/src/main/java/com/ai/guide/infrastructure/planner/proposal/CaffeineRedisProposalRepository.java`.
- Updated `ai/src/main/java/com/ai/guide/domain/planner/model/PlanAdjustmentProposal.java` to own the TTL constant without importing a service-layer type.
- Updated `ai/src/main/java/com/ai/guide/domain/planner/service/PlanAdjustmentService.java` to depend on the port and shared TTL constant.
- Removed `ai/src/main/java/com/ai/guide/domain/planner/service/ProposalStore.java`.

Architecture/tests:

- Updated `ai/src/test/java/com/ai/guide/architecture/ColaArchitectureGuardTest.java` with the domain/infrastructure dependency boundary assertion.
- Added `ai/src/test/java/com/ai/guide/infrastructure/planner/proposal/ProposalRepositoryPortContractTest.java`.
- Updated existing tests that directly instantiated or typed `ProposalStore` to use the port or infrastructure adapter: `AgentARedTeamAssaultTest`, `AgentBRedTeamAssaultCampaignTest`, `CancellationProposalLifecycleTest`, `CombinedLogicRegressionTest`, `FourthPlanningTemporalRegressionTest`, `P2DeepExploitAssaultTest`, `Phase6FinalClosureTest`, `PlanAdjustmentServiceTest`, `PlannerSafetyBoundaryRegressionTest`, `ProposalStoreAndChatContextTest`, `RealUserDiningExploitAssaultTest`, `RedTeamAdversarialAssaultTest`, and `ScopedReplanAndMultiSlotRegressionTest`.

The worktree contains other pre-existing CP0/CP1-adjacent and user changes. They are outside this CP1 slice and are not attributed to this implementation.

## Verification evidence

The following targeted command ran in an isolated temporary source copy, not the shared `D:/scenic-guide/ai/target`:

```powershell
.\mvnw.cmd -f .\ai\pom.xml clean test "-Dtest=ProposalRepositoryPortContractTest,ProposalStoreAndChatContextTest,PlanAdjustmentServiceTest,CancellationProposalLifecycleTest,ColaArchitectureGuardTest"
```

Result from `C:/Users/summer/Documents/Codex/2026-10-05/x20-time-window-lunch-x20-x20/outputs/20261009-CP1-maven-clean-tests.log`:

```text
Tests run: 83, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

`clean` compiled the isolated copy's production and test sources. The port contract suite covers put/get/remove, expiry, optional Redis failure with local fallback, and fail-closed application through a newly constructed service/repository against the same mocked Redis facade. That last scenario uses an independent temporary SQLite database via JUnit `@TempDir`, verifies HTTP/domain conflict behavior, unchanged session version/revision, and zero repository update calls.

The new-instance scenario is a mock-backed, single-process semantic test. It demonstrates that a newly constructed repository/service with no local proposal and no matching serialized Redis entry cannot apply the old proposal. It is not evidence of a real Redis process restart, Redis persistence, or a multi-node integration test.

`git diff --check` was run on the relevant changed tracked files and reported no whitespace errors (Git emitted line-ending normalization warnings). No Maven build was run against or cleaned in the shared repository target.

## Not verified

- Real Redis outage/recovery, restart persistence, network partitions, and serializer compatibility against a live Redis service.
- Cross-process races or multi-node cache consistency under concurrent proposal mutation.
- Live Spring application wiring with the deployed Redis configuration.
- Full Java test suite; only the named targeted test set ran.
- Independent architecture/red-team acceptance; pending reviewer B/C.

## Rollback approach

Rollback only this slice by restoring the former `ProposalStore` implementation and deleting the new port/adapter; restore `PlanAdjustmentService`'s field and constructors to the old type and restore its TTL reference; remove the new contract test and architecture assertion; revert only the listed test type/import migrations. Do not use a blanket reset or revert unrelated dirty files. The captured CP1 candidate and starting candidate records identify the broader shared worktree state for review.

## R1 follow-up — asynchronous L2 write/remove ordering

After the independent red-team review identified R1, the L2 write path was updated so each proposal ID has a local generation state. Every `put` captures a generation before scheduling. The task performs its Redis set only while that generation is still current, under the per-ID state monitor. `remove` increments the generation and performs the Redis delete under the same monitor. Therefore a queued stale write is skipped; if a write has already entered the critical section, `remove` waits for it to finish and deletes afterward. Multiple puts for one ID also invalidate older queued work, so an older snapshot cannot overwrite a newer put. Completed per-ID state is discarded after the final scheduled write finishes.

A package-private executor constructor allows deterministic scheduling tests without changing Spring wiring or production behavior. `ProposalRepositoryPortContractTest` now includes:

- `removeInvalidatesQueuedRedisPutSoItCannotResurrectTheKey`: queues the L2 set, calls remove, then releases the queued task and verifies no Redis set occurred and get stays empty.
- `olderQueuedSameIdPutCannotOverwriteNewerProposalSnapshot`: deliberately runs the newer put task before the older queued task, clears L1, and verifies L2 restores the newer snapshot.

Both tests use a controllable in-memory executor and Mockito Redis facade. They are deterministic concurrency-ordering simulations; they do not exercise a real Redis server or network timing.

The first isolated clean test run exposed that the architecture guard treated the new nested per-ID state class as though it were a second infrastructure adapter. The guard was narrowed to top-level infrastructure classes, preserving the intended rule that actual adapters implement the port. The corrected isolated clean run used:

```powershell
.\mvnw.cmd -f .\ai\pom.xml clean test "-Dtest=ProposalRepositoryPortContractTest,ProposalStoreAndChatContextTest,PlanAdjustmentServiceTest,CancellationProposalLifecycleTest,ColaArchitectureGuardTest"
```

Result from `C:/Users/summer/Documents/Codex/2026-10-05/x20-time-window-lunch-x20-x20/outputs/20261009-CP1-R1-maven-clean-tests.log`:

```text
Tests run: 85, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

This run was in the isolated temporary source copy, not the shared Maven target. It does not upgrade the earlier stated limitations for live Redis, multi-process behavior, or full-suite coverage. This follow-up is submitted for independent review; it is not a CP1 acceptance claim.

## CP1-R2 follow-up — complete per-key lifecycle coordination

The second red-team review identified additional interleavings around L1 publication, L2 read-backfill, and executor rejection. The adapter now uses a fixed 256-slot lock stripe array (bounded memory) to coordinate each proposal ID's L1 lookup/write/invalidation, generation changes, Redis read/backfill/delete, and the Redis set critical section. This makes the L1 update and generation registration one linearized operation; get holds the matching stripe through L2 read and L1 restoration; remove uses the same stripe and cannot finish before an in-flight read or set releases it. Generation state remains only while Redis writes are pending and is removed once the final accepted or rejected task is accounted for.

If `Executor.execute` throws (including `RejectedExecutionException`), `put` keeps the already-published L1 value, decrements pending-write accounting, removes idle per-key state, logs the L2 fallback, and returns without surfacing an exception. Redis remains an optional best-effort snapshot layer.

The package-private constructor accepts a hook used only by deterministic tests to pause between the L1 write and generation registration while the key stripe is held. The R2 regression tests use latches/thread barriers and controllable executors to cover:

- `overlappingSameIdPutsKeepL1AndL2AtTheSameLinearizationOrder`: pauses the first overlapping put after L1 publication; proves the second put blocks on the same stripe, then deliberately executes Redis tasks in reverse order and verifies both L1 and L2 resolve to the newer proposal.
- `delayedRedisGetCannotRepopulateL1AfterRemoveCompletes`: pauses L2 get, starts remove and verifies it blocks, then releases the read and confirms removal clears both tiers.
- `removeWaitsForRedisSetAlreadyInsideCriticalSectionThenDeletesIt`: pauses a set after it has entered the critical section; remove waits, then deletes the just-written value.
- `rejectedRedisExecutorKeepsL1AndReleasesPerKeyState`: verifies executor rejection is non-fatal, L1 remains readable, and scheduled-write state is reclaimed.

Together with the two R1 tests, the targeted clean run used:

```powershell
.\mvnw.cmd -f .\ai\pom.xml clean test "-Dtest=ProposalRepositoryPortContractTest,ProposalStoreAndChatContextTest,PlanAdjustmentServiceTest,CancellationProposalLifecycleTest,ColaArchitectureGuardTest"
```

The isolated-copy raw log is `C:/Users/summer/Documents/Codex/2026-10-05/x20-time-window-lunch-x20-x20/outputs/20261009-CP1-R2-maven-clean-tests.log`:

```text
Tests run: 89, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

The tests use an in-memory executor and Mockito Redis facade. They establish deterministic single-process ordering and fallback semantics; they do not verify real Redis/network behavior, cross-process consistency, or the full Java suite. This candidate is submitted for independent review and is not a CP1 acceptance claim.

## CP1-R3 follow-up — deletion failure, expiry replacement, and snapshot validation

The R2 red-team review identified three remaining cache lifecycle cases. The adapter now keeps a bounded, expiring local tombstone when Redis deletion fails (or Redis is unavailable). `get` serves an already-published valid L1 value first, then checks the tombstone before consulting L2; thus an explicitly submitted replacement can remain available from L1 while an old L2 snapshot cannot be rehydrated. A successful later Redis set clears the tombstone. A failed delete leaves it active until its 15-minute expiry or bounded-cache eviction. Capacity is 10,000 entries, so this is a same-process guard while retained; it cannot guarantee physical Redis deletion, survival across process restart, or protection on another application node.

An expired `put` for a reused ID now invalidates the L1 entry, increments any pending generation, and best-effort deletes the L2 snapshot instead of simply declining to schedule a new write. If that deletion fails, the tombstone blocks the old L2 value in this process.

Redis-restored snapshots are accepted only when the stored proposal ID matches the requested key, session and conversation epoch are nonblank (and epoch is not `INITIAL`), base revision and creation time are positive, expiry follows creation, expiry is no more than the configured 15-minute TTL after creation, and the proposal is not already expired. Invalid snapshots are not cached; deletion is attempted and a tombstone is kept if it fails.

New deterministic contract cases:

- `deleteFailureTombstoneBlocksRehydrationAndReconstructedApplyWithoutSqlWrite` makes Redis GET return a previously cached proposal and DELETE throw; after L1 clear and service reconstruction, the old snapshot remains unreadable and apply produces no revision/database update.
- `expiredPutForExistingIdInvalidatesOldL1AndL2Snapshot` writes a valid same-ID snapshot, submits an expired replacement, clears L1, and verifies both tiers remain empty.
- `restoredRedisProposalMustMatchKeyAndRequiredShape` rejects mismatched IDs, missing session/epoch/revision/expiry fields, and an expiry beyond `createdAt + DEFAULT_TTL_MS`.

The isolated clean targeted command remains:

```powershell
.\mvnw.cmd -f .\ai\pom.xml clean test "-Dtest=ProposalRepositoryPortContractTest,ProposalStoreAndChatContextTest,PlanAdjustmentServiceTest,CancellationProposalLifecycleTest,ColaArchitectureGuardTest"
```

Raw log: `C:/Users/summer/Documents/Codex/2026-10-05/x20-time-window-lunch-x20-x20/outputs/20261009-CP1-R3-maven-clean-tests.log`:

```text
Tests run: 92, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

All Redis paths in these tests use Mockito; the database in the reconstruction/apply regression is an isolated temporary SQLite file. Real Redis deletion failure, physical key removal, process restart, multi-node behavior, and the full Java suite remain unverified. This is submitted for independent review, not as an acceptance declaration.

## CP1-R4 follow-up — clock boundaries and reconstruction evidence

The R3 review found two timestamp edges and noted that the R3 delete-failure apply case reused the repository tombstone. The restored-payload validator now rejects any `createdAt` later than the current wall clock, in addition to requiring a positive creation timestamp and an expiry within the proposal TTL. `PlanAdjustmentProposal.isExpired()` now delegates to `isExpired(nowMillis)` and uses an exclusive deadline (`nowMillis >= expiresAt`), matching `put`'s `expiresAt <= now` behavior. A deterministic exact-boundary test asserts live one millisecond before, expired exactly at, and expired one millisecond after the deadline.

The delete-failure regression now first creates a continuation epoch and proves the proposal's epoch is current before cancellation. It forces Redis DELETE to throw and confirms the original repository's local tombstone blocks L2 rehydration after L1 loss. It then constructs a new repository against the same stale Mockito Redis facade (so no process-local tombstone is present), confirms that the old snapshot can be restored there, and constructs a fresh `PlanAdjustmentService`; apply returns 409 because that service has no matching current epoch, and the temporary SQLite revision remains unchanged with `sessions.update` never called. This is a single-JVM semantic test using two repository/service objects and a shared mock. It is not a real process restart or live Redis test, and it does not guarantee physical Redis deletion or cross-node behavior.

The restored-snapshot shape test now also rejects a future-dated `createdAt` whose `expiresAt - createdAt` would otherwise appear within the allowed TTL. Existing expiry and proposal service tests passed with the exclusive-boundary change.

Isolated clean targeted command:

```powershell
.\mvnw.cmd -f .\ai\pom.xml clean test "-Dtest=ProposalRepositoryPortContractTest,ProposalStoreAndChatContextTest,PlanAdjustmentServiceTest,CancellationProposalLifecycleTest,ColaArchitectureGuardTest"
```

Raw log: `C:/Users/summer/Documents/Codex/2026-10-05/x20-time-window-lunch-x20-x20/outputs/20261009-CP1-R4-maven-clean-tests.log`:

```text
Tests run: 92, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

The local deletion tombstone remains bounded to 10,000 entries with 15-minute expiry, so it can be evicted under extreme volume and is lost on process restart. The apply epoch check is the separate fail-closed defense across service/repository reconstruction in the mocked semantic test. Real Redis, physical deletion, multi-node behavior, and the full Java test suite remain unverified. This candidate is submitted for independent review; no acceptance verdict is claimed.
