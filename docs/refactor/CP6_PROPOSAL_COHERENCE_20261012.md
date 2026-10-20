# CP6 proposal shared-snapshot coherence

## Confirmed defect and correction

`CaffeineRedisProposalRepository.get` previously returned a warmed L1 entry without checking Redis. A different repository instance could delete or replace the shared snapshot while the first instance continued returning its old proposal until local expiry.

The L1 entry now records whether its snapshot has been successfully published to, or restored from, Redis. Shared entries read the authoritative Redis snapshot before returning a proposal. A missing snapshot invalidates local state, a replacement is reflected immediately, and a failed Redis read does not return an unverifiable shared proposal. An unpublished local entry retains the existing behavior when its asynchronous write is pending, rejected, or unsuccessful. The existing per-key generation/lock mechanism is unchanged.

This deliberately favors consistency over serving a shared proposal during a Redis outage. A later successful read can recover the still-valid shared snapshot. It does not turn a local publication failure into a distributed availability guarantee.

Cancellation now atomically replaces the single Redis snapshot key with a removal sentinel. A single-key Lua publication command checks the sentinel and writes the snapshot atomically, so an already queued asynchronous publication cannot run after cancellation and resurrect that ID. The sentinel lasts the maximum accepted lifetime (15 minutes). `put` rejects future creation times, expired inputs and lifetimes above that maximum; execution revalidates lifetime and recomputes remaining milliseconds before writing. Proposal IDs are terminal after cancellation, matching production's newly generated `prop-UUID` per preview. New previews require new IDs.

Invalid/expired input is rejected without changing an existing live proposal; only `remove` is a cancellation command. This intentionally corrects the old test contract that treated an expired `put` as cancellation. Cleanup of a deserialized invalid/expired Redis snapshot uses exact-value compare-delete, preventing a concurrent replacement from being removed. Malformed JSON is safely rejected but is not claimed to be cleaned up.

## Executed evidence

`ProposalRepositoryCoherenceTest` uses two or three real repository objects with a single mocked Redis backing map. Its eight scenarios cover remote deletion, remote replacement, outage/recovery, stale local expiry, queued publication after cancellation, remaining-TTL recalculation/expired queued work, invalid old puts preserving live replacements, and compare-delete during concurrent replacement. The script stub models the commands' atomic semantics; it does not execute Lua in a real Redis server. These are repeatable repository behavior tests, not real Redis acceptance.

Before the correction, the original three tests all failed. The original failing JUnit report is retained at `C:/Users/summer/AppData/Local/Temp/cp6-proposal-coherence-before/`.

The queued-publication reproduction also failed before its fix: an unpublished local proposal remained visible after remote cancellation, and running the queued write resurrected the snapshot. Its failing report is retained at `C:/Users/summer/AppData/Local/Temp/cp6-proposal-queued-cancel-before/`.

The final serialized targeted Maven run passed all eight coherence tests, 12 `ProposalRepositoryPortContractTest` scenarios, and three `Cp6InfrastructureTargetTest` guards: **27 run, 23 passed, 0 failed, 0 errors, 4 skipped**, `BUILD SUCCESS` at 19:32:10 on 2026-10-12. Four `Cp6RealInfrastructureIntegrationTest` cases were explicitly skipped because isolated PostgreSQL/Redis targets were not configured. Final JUnit reports are retained at `C:/Users/summer/AppData/Local/Temp/cp6-proposal-coherence-after/`. The opt-in real-Redis test was subsequently extended to assert queued-publication rejection; the main thread's full Maven run will compile that extension, but real execution remains unavailable.

Command:

```powershell
./mvnw.cmd -pl ai test '-Dtest=ProposalRepositoryCoherenceTest,ProposalRepositoryPortContractTest,Cp6InfrastructureTargetTest,Cp6RealInfrastructureIntegrationTest'
```

## Remaining scope and independent review

- Successful Redis cancellation now rejects queued publication across repository instances. If the cancellation write itself fails, only the cancelling process's local tombstone is guaranteed; cross-process cancellation cannot be claimed during that failure. The repository does not implement a general version-order protocol for arbitrary concurrent updates reusing one proposal ID; production previews generate fresh IDs.
- Real PostgreSQL migration, real Redis behavior, two JVMs, and Java process loss remain opt-in checks. Skips are not passes. See `tools/cp6-infra/INTEGRATION.md` for the exact scope.
- The real-service probes consume only explicit `CP6_IT_*` targets, use random PostgreSQL schemas and one random Redis key in database 15, and never flush Redis or import the user's SQLite database. Test targets must be dedicated to this purpose.
- Initial schema DDL executes before the migration ledger advisory lock. The two-JVM empty-schema test is intended to detect any startup DDL race; that path has not been verified on PostgreSQL here.
- No remote service, production dataset, or user credential was accessed by this repository correction. A separate agent independently reviewed the final Lua cancellation, lifetime bounds, shared/unpublished read behavior and compare-delete cleanup; no further concrete blocker was identified within this scope. Real-service execution remains unaccepted.
