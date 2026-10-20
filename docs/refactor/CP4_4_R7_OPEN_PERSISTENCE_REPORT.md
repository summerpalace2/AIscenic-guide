# CP4.4 R7 — committed-state reads during Open persistence

Status: frozen pending independent B/C review; not accepted.

## Change

R6 serialized mutations and rolled Maps back after failed writes, but synchronous getters could still observe the candidate during the `await` before rollback. R7 stages planner-draft and workspace changes in cloned Maps. The staged JSON snapshot is written through the same serial write queue; only after the atomic temp-file rename succeeds are the committed Maps replaced. `getTripWorkspace`, `getPlannerDraft`, and the exposed `tripWorkspaces` Map therefore show the last committed state while a write is pending and after a failed write.

The same queue serializes workspace save/delete, draft save/delete, and `clearPlannerDrafts`. A later same-key mutation begins from the prior mutation's committed result, or from the unchanged prior state if that write fails. The write queue still surfaces each caller's error while allowing subsequent writes to proceed. Open remains draft → workspace → BFF session registration; on workspace failure the workspace Map is not committed and Open best-effort deletes the already-written draft with diagnostics if cleanup fails.

## Deterministic regression coverage

`persistent-store-open-rollback.test.mjs` gates the actual imported `rename` call:

- Pending workspace save and draft save leave getters on their previous committed values until rename succeeds.
- Injected workspace and draft rename failures leave getters on their previous committed values during the wait and after rejection.
- Pending workspace and draft deletion keep the committed value visible until rename succeeds.
- Pending `clearPlannerDrafts` keeps committed drafts visible until rename succeeds.
- A separate temporary-directory test uses a real target directory to force atomic rename failures for overlapping same-key workspace writes and a draft write. It verifies no speculative state is visible, then removes the obstruction and proves later writes succeed on disk and in memory.

`formal-trip-bff-contract.test.mjs` verifies Open persistence order, draft cleanup after workspace failure, no final draft/workspace Map residue for both tested failures, and no BFF session registration on failure.

## Verification

```text
node --test server/formal-trip-bff-contract.test.mjs server/draft-restore-route.test.mjs server/planner-bridge-contract.test.mjs server/trip-delete-version.test.mjs server/persistent-store-open-rollback.test.mjs
31 tests, 31 pass, 0 fail, 0 skipped

node --check server/index.mjs
node --check server/persistent-store.mjs
node --check server/formal-trip-bff-contract.test.mjs
node --check server/persistent-store-open-rollback.test.mjs
git diff --check
```

Raw output: `CP4_4_R7_BFF_TEST.log`.

## Limits

- Java Open creates the remote session before BFF writes. The current Java contract has no delete-session compensation, so a failed local Open may leave a Java session; this slice does not claim otherwise.
- Draft cleanup after a workspace failure remains best-effort if the filesystem continues failing. The route records both the primary and cleanup failure.
- The rename-gate test uses Node's built-in module mock to pause/fail the imported `rename`; the separate rename-failure recovery test uses a real temporary directory and no rename mock.
- No live BFF/Java service or production data was used.

No stage or commit was performed. R5 and R6 frozen artifacts were left unchanged; unrelated dirty files remain untouched.
