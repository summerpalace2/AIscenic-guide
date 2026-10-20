# CP4.4 R9 — Rebase Open on the queued workspace snapshot

Status: implementation candidate frozen for independent B/C review; not accepted.

## Defect and change

R8 made Open's draft/workspace persistence atomic, but it still assembled the workspace projection before entering the persistent-store queue. If a workspace update was already queued ahead of Open, that PUT could commit newer `chatEpoch` or `plannerConversationContext` while Open waited; Open would then write its stale projection over the newly committed fields.

`savePlannerOpenState(session)` now reads the latest workspace from the staged map at the head of the shared serialized queue. Before making the draft snapshot, it copies the current workspace's `chatEpoch` and `plannerConversationContext` into the opening session. It creates the workspace candidate from those values plus the new session's itinerary-memory snapshot. The existing workspace builder preserves the latest `chatMessages` and `chatMode` when Open does not explicitly replace them. Draft and workspace are still persisted in one JSON snapshot and both maps commit only after rename succeeds.

The Open route seeds a new session epoch and itinerary snapshot, awaits the atomic operation, hydrates the session from the returned committed workspace, and only then registers it in `sessions`.

## Scope

Production files:

- `server/index.mjs`
- `server/persistent-store.mjs`

Regression tests:

- `server/formal-trip-bff-contract.test.mjs`
- `server/persistent-store-open-rollback.test.mjs`

No Java, frontend, schema, deployment, or service changes. No staging or commit was performed. Existing dirty files outside this four-file slice were left untouched. R8's frozen artifacts were not modified.

## Verification

Command:

```powershell
node --test server/formal-trip-bff-contract.test.mjs server/draft-restore-route.test.mjs server/planner-bridge-contract.test.mjs server/trip-delete-version.test.mjs server/persistent-store-open-rollback.test.mjs
```

Result: 34 tests passed, 0 failed, 0 skipped on Node v24.14.0. Raw output: `D:\scenic-guide\docs\refactor\CP4_4_R9_BFF_TEST.log`.

The store-level queue test blocks an earlier workspace PUT at the imported filesystem rename, enqueues Open behind it, then releases the PUT. It asserts the Open draft/workspace use the committed PUT's latest epoch and context, preserve its chat messages and mode, and use the new Open session's itinerary snapshot. The route-level test interleaves a workspace update while Open waits and verifies both persisted draft and registered session reflect the latest continuity fields. R8 concurrent-GET/atomic-failure, rename-pending getter, real temp-directory rename failure, and recovery tests remain passing.

Syntax checks (`node --check` on both production files and both tests) and `git diff --check` completed successfully. Git emitted only its existing LF-to-CRLF advisory for dirty working files.

## Limits

The store-level queue test exercises the actual persistent store and imported `rename` gate. The route-level interleaving uses a controlled store fake. No live BFF or Java service was run. Java Open may create its remote Planner Session before BFF persistence; the current API has no delete-session compensation, so a remote Java session can remain orphaned.

## Frozen identity

- Baseline HEAD: `6eb2a07a98fc55eb51c051b57934badddc828bd6`
- Parent R8 candidate: `D:\scenic-guide\docs\refactor\CP4_4_R8_CANDIDATE.json` (SHA-256 `45470107C4B597F48A5AE4D0ACAE9E22791B06D0670B68D85D97C72409963B63`)
- Scoped tracked patch command: `git diff --binary HEAD -- server/index.mjs server/persistent-store.mjs`; raw bytes saved at `D:\scenic-guide\docs\refactor\CP4_4_R9_BFF_TRACKED_PATCH.patch`.
- Per-file, patch, test-log, and report hashes are recorded in `D:\scenic-guide\docs\refactor\CP4_4_R9_CANDIDATE.json`.
