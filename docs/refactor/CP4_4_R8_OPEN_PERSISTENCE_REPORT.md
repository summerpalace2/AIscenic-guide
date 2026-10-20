# CP4.4 R8 — Atomic BFF Open state persistence

Status: implementation candidate frozen for independent B/C review; not accepted.

## Defect and change

The R7 Open route saved the session draft, awaited a separate workspace save, then registered the live session. During that gap, another request could restore the newly persisted draft and register it in `sessions`; if the workspace write failed, the Open handler's draft cleanup could not remove that concurrently restored live session.

R8 adds `persistentStore.savePlannerOpenState(session, workspaceWrite)`. It prepares the draft and workspace projection in cloned maps, serializes one combined JSON state through the existing write queue, and replaces both committed maps only after the same atomic rename succeeds. Synchronous getters therefore keep returning the previous values while rename is pending, and either both values commit or neither does. It validates that the draft and workspace owner/formal-trip identities match.

The Open route now builds its workspace projection with the shared `formalTripWorkspaceWrite` helper and awaits the combined operation. It registers the BFF session only after the returned draft and workspace are present. Separate draft/workspace writes and best-effort draft deletion were removed from this route. `persistFormalTripWorkspace` still uses the same projection helper for other routes.

## Scope

Production files:

- `server/index.mjs`
- `server/persistent-store.mjs`

Regression tests:

- `server/formal-trip-bff-contract.test.mjs`
- `server/persistent-store-open-rollback.test.mjs`

No Java, frontend, schema, deployment, or service changes. No staging or commit was performed. Existing dirty files outside this four-file slice were left untouched.

## Verification

Command:

```powershell
node --test server/formal-trip-bff-contract.test.mjs server/draft-restore-route.test.mjs server/planner-bridge-contract.test.mjs server/trip-delete-version.test.mjs server/persistent-store-open-rollback.test.mjs
```

Result: 32 tests passed, 0 failed, 0 skipped on Node v24.14.0. The raw output is in `D:\scenic-guide\docs\refactor\CP4_4_R8_BFF_TEST.log`.

The route test pauses Open inside the atomic persistence call, sends a concurrent `GET /api/planner/sessions/:id`, and verifies a 404, no candidate draft visibility, and no live-session registration. It then injects the persistence failure and verifies the pre-existing draft/workspace remain unchanged. The persistent-store test gates the imported filesystem `rename` and verifies both getters continue showing their prior values until the combined rename succeeds. A real temporary-directory target obstruction separately verifies the combined operation leaves both maps unchanged on rename failure and subsequent queued writes recover after the obstruction is removed.

Syntax checks (`node --check` on both production files and both tests) and `git diff --check` completed successfully. Git emitted only its existing LF-to-CRLF advisory for dirty working files.

## Limits

The route test uses a controlled store fake to place the concurrent GET during the awaited persistence window; the persistent-store tests exercise the actual store and imported `rename` gate. No live BFF or Java service was run. Java Open may already have created its remote Planner Session before local persistence fails; the current API has no delete-session compensation, so a remote Java session can remain orphaned. This candidate does not claim to solve that cross-service boundary.

## Frozen identity

- Baseline HEAD: `6eb2a07a98fc55eb51c051b57934badddc828bd6`
- Parent R7 candidate: `D:\scenic-guide\docs\refactor\CP4_4_R7_CANDIDATE.json` (SHA-256 `dda695dae6f286b44dee18c3e83c124ce3e553bdd34dcd6f6a404d79012971eb`)
- The scoped tracked patch is the reproducible output of `git diff --binary HEAD -- server/index.mjs server/persistent-store.mjs`; its raw bytes are saved as `D:\scenic-guide\docs\refactor\CP4_4_R8_BFF_TRACKED_PATCH.patch`.
- Full per-file hashes, test-log hash, patch hash, and this report's hash are recorded in `D:\scenic-guide\docs\refactor\CP4_4_R8_CANDIDATE.json`.
