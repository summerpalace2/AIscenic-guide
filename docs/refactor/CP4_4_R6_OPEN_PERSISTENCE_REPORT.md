# CP4.4 R6 — Open local-persistence rollback

Status: frozen pending independent B/C review; not accepted.

## Change

The Open route now writes the new BFF planner draft before its workspace and only registers the session after both writes complete. If either persistence step fails, it best-effort deletes the draft and emits a local persistence diagnostic; a cleanup failure gets its own diagnostic. The draft-first ordering means workspace persistence has not started if draft persistence fails. If the workspace write fails, the store restores that workspace key to its preceding in-memory value before rejecting.

`persistent-store.mjs` serializes the Open-relevant planner draft and workspace mutations through one recoverable queue. Each save/delete captures the preceding key value, changes the Map only when its turn runs, persists, and restores the prior key on failure. Same-key work cannot observe another uncommitted mutation as its rollback base. The lower-level write queue now catches an earlier rejected write before attempting a later one, while each caller still receives its own write error. This avoids a single failed filesystem write permanently poisoning future persistence.

The implementation covers `saveTripWorkspace`, `deleteTripWorkspace`, `savePlannerDraft`, and `deletePlannerDraft`. `clearPlannerDrafts` is not in the queue because a repository-wide search found only its declaration and no runtime caller; this is recorded as a limitation if a new caller is introduced.

The route contract test verifies `draft-save → workspace-save → register` on success. For draft failure it verifies workspace is untouched; for workspace failure it verifies workspace and draft Maps have no Open residue and the cleanup call occurs before failure returns. Neither case registers a usable BFF session.

## Real filesystem regression

`persistent-store-open-rollback.test.mjs` creates a temporary store, writes an initial workspace, then replaces the destination JSON file with a directory. This makes the real `rename(tempPath, filePath)` fail with a filesystem error. Two overlapping writes to the same workspace key both reject and restore the original in-memory value. A draft write under the same obstruction also rejects without leaving a draft Map entry. After removing the obstruction, subsequent workspace and draft writes succeed and are present both in memory and on disk. The test does not mock `rename`.

## Verification

```text
node --test server/formal-trip-bff-contract.test.mjs server/draft-restore-route.test.mjs server/planner-bridge-contract.test.mjs server/trip-delete-version.test.mjs server/persistent-store-open-rollback.test.mjs
30 tests, 30 pass, 0 fail, 0 skipped

node --check server/index.mjs
node --check server/persistent-store.mjs
node --check server/formal-trip-bff-contract.test.mjs
node --check server/persistent-store-open-rollback.test.mjs
git diff --check
```

Raw test output: `CP4_4_R6_BFF_TEST.log`.

## Limits

- Java Open has already committed the remote planner session before BFF persistence. Java has no delete-session compensation protocol, so a failed BFF Open may leave an orphan Java session. This slice does not claim to solve it.
- If the filesystem remains unwritable during best-effort draft cleanup after workspace failure, the cleanup can fail; the route records the cleanup error, but cannot promise durable deletion under persistent I/O failure.
- BFF route tests use controlled persistence fakes. The store regression uses a real temporary directory and real filesystem rename failure. No running services or production data were used.
- Repository `clearPlannerDrafts()` has no current callers; if one is added concurrently with Open, it remains outside this serialized mutation queue.

No stage or commit was performed. R5 frozen artifacts were left unchanged; other pre-existing dirty files were preserved.
