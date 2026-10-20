# CP4 Save / Open / Restore 与 BFF 契约只读基线

采集日期：2026-10-09（Asia/Shanghai）  
审查范围：正式行程保存、打开、离线草稿备份/恢复/丢弃，及 BFF 与前端调用契约。  
审查性质：只读盘点；未改业务代码、未改既有文档、未运行 Maven 或 Node 测试。

## 1. 工作区身份与边界

| 代码库 | 路径 | HEAD | 状态 |
|---|---|---|---|
| Java | `D:/scenic-guide` | `46b1653e81050e3cdd9f23506b327bc6676bcbd8` | 有既有未提交改动；其中包含 CP0–CP3 与用户更早的改动 |
| BFF | `C:/Users/summer/Documents/Codex/2026-08-12/loop-p0-functional-inventory-1-home` | `6eb2a07a98fc55eb51c051b57934badddc828bd6` | 有既有未提交改动及未跟踪的 `server/draft-restore-route.test.mjs` |
| Frontend | `C:/Users/summer/Documents/Codex/2026-08-12/loop-p0-functional-inventory-1-home/frontend` | `6dd081da50078d39cd76337c018cd5cb4042dd18` | 有既有未提交改动及未跟踪的 `web/src/features/trip-save/draft-backup-restore.test.mjs` |

以上状态是本次观察时的共享工作区状态，不是 CP4 冻结候选。已有改动均按基线保留。工作树内未发现 `AGENTS.md` 覆盖文件。

## 2. 实际纵向调用链

```mermaid
sequenceDiagram
    participant UI as Frontend save-service
    participant BFF as Node BFF /api/trips/*
    participant PC as Java PlannerController
    participant PS as PlannerService
    participant TS as TripService
    participant DB as TripRepository + planner session repository
    UI->>BFF: POST /api/trips/save
    BFF->>BFF: web auth + planner session ownership/device check
    BFF->>PC: POST /ai/planner/v1/sessions/{id}/save
    PC->>PS: save(sessionId, session capability)
    PS->>TS: savePlannerSnapshot(owner, server session snapshot, session/version key)
    TS->>DB: idempotency/link + create/update/no-op/CAS
    DB-->>UI: saved trip identity/version via BFF
    UI->>BFF: POST /api/trips/{tripId}/open
    BFF->>PC: POST /ai/planner/v1/trips/{tripId}/open
    PC->>PS: openFormalTrip(tripId)
    PS->>TS: owner-scoped formal trip read; create a new planner session snapshot
    UI->>BFF: POST /api/trips/{tripId}/draft/restore
    BFF->>PC: POST /ai/planner/v1/sessions/{id}/restore-draft
    PC->>PS: restoreDraft(sessionId, trip, expectedVersion, capability)
```

Java routes are `PlannerController` POST `/trips/{tripId}/open`, `/sessions/{sessionId}/restore-draft`, and `/sessions/{sessionId}/save` ([PlannerController.java:88-101](../../ai/src/main/java/com/ai/guide/adapter/planner/web/PlannerController.java:88), [PlannerController.java:139-143](../../ai/src/main/java/com/ai/guide/adapter/planner/web/PlannerController.java:139)). They currently invoke `PlannerService` directly. Generic formal-trip CRUD instead goes through `TripController` → `TripApplicationService` → `TripService`; that application service is a thin forwarding facade ([TripController.java:61-87](../../ai/src/main/java/com/ai/guide/adapter/trip/web/TripController.java:61), [TripApplicationService.java:34-57](../../ai/src/main/java/com/ai/guide/application/trip/TripApplicationService.java:34)).

## 3. Formal ID, owner, revision and CAS semantics

### Save

The visible save button is `data-action="save"` ([plan-page.js:152](C:/Users/summer/Documents/Codex/2026-08-12/loop-p0-functional-inventory-1-home/frontend/web/src/pages/plan-page.js:152)); the frontend delegates to `saveTrip` ([save-service.js:10-35](C:/Users/summer/Documents/Codex/2026-08-12/loop-p0-functional-inventory-1-home/frontend/web/src/features/trip-save/save-service.js:10)). For V1 sessions, it sends the current planner session ID and saved-trip ID to BFF `/api/trips/save`. The BFF resolves that owned session and calls Java `savePlannerSession`; it does not use the request's submitted `trip` as the V1 persistence snapshot ([server/index.mjs:2334-2371](C:/Users/summer/Documents/Codex/2026-08-12/loop-p0-functional-inventory-1-home/server/index.mjs:2334)). This makes the Java planner session the V1 save authority; the body trip is consumed by legacy/no-session paths.

Java `PlannerService.save` authorizes the session, copies its stored trip, stamps planner session/revision metadata, and uses idempotency key `planner-save-{sessionId}-{currentVersion}` ([PlannerService.java:521-556](../../ai/src/main/java/com/ai/guide/domain/planner/service/PlannerService.java:521)). It then links the stable formal Trip identity into the planner session; link retries preserve a concurrent newer planner snapshot ([PlannerService.java:553-581](../../ai/src/main/java/com/ai/guide/domain/planner/service/PlannerService.java:553)). `TripService.savePlannerSnapshot` reserves/replays `PLANNER_SAVE` and `PLANNER_LINK` records, creates a formal Trip on first save, uses the existing formal ID on later saves, and returns `NOOP` when business snapshots match ([TripService.java:298-359](../../ai/src/main/java/com/ai/guide/domain/trip/service/TripService.java:298), [TripService.java:362-420](../../ai/src/main/java/com/ai/guide/domain/trip/service/TripService.java:362)). Planner metadata is removed from business comparison ([TripService.java:427-437](../../ai/src/main/java/com/ai/guide/domain/trip/service/TripService.java:427)).

Formal `TripService.update` requires `expectedVersion`, scopes reads and writes by owner ID, performs a no-op check before increment, and advances the current pointer with `WHERE id=? AND owner_id=? AND current_version=?`; the version snapshot insert follows the CAS inside the service transaction ([TripService.java:102-149](../../ai/src/main/java/com/ai/guide/domain/trip/service/TripService.java:102), [TripRepository.java:118-125](../../ai/src/main/java/com/ai/guide/infrastructure/trip/repository/TripRepository.java:118)). CAS retries may rebase non-conflicting fields; conflicting changes produce conflict metadata ([TripService.java:145-197](../../ai/src/main/java/com/ai/guide/domain/trip/service/TripService.java:145)).

The BFF's non-V1 direct update path derives/validates a formal base version, returns 428 when absent, and forwards `expectedVersion`; V1 planner save delegates to the Java session route instead ([server/index.mjs:2346-2399](C:/Users/summer/Documents/Codex/2026-08-12/loop-p0-functional-inventory-1-home/server/index.mjs:2346)). Generic Java CRUD also requires authenticated `UserContext` and passes that owner into repository operations ([TripController.java:67-99](../../ai/src/main/java/com/ai/guide/adapter/trip/web/TripController.java:67)).

### Open

Frontend list activation calls `openSavedTrip` ([app.js:580](C:/Users/summer/Documents/Codex/2026-08-12/loop-p0-functional-inventory-1-home/frontend/web/app.js:580)); local backup recovery also calls it with `preserveDraft: true` ([app.js:1262-1270](C:/Users/summer/Documents/Codex/2026-08-12/loop-p0-functional-inventory-1-home/frontend/web/app.js:1262)). Java `openFormalTrip` first reads the formal Trip using the authenticated owner, copies that snapshot, sets `sourceTripId`/`sourceTripVersion` from the same Trip object, resets the new planner-session revision to 1, and returns formalTripVersion with the created session ([PlannerService.java:585-621](../../ai/src/main/java/com/ai/guide/domain/planner/service/PlannerService.java:585)).

BFF `/open` calls Java open, separately reads the formal Trip for workspace initialization, then sets the BFF session trip from Java's returned `restored.trip` and returns Java `sessionId`, capability, savedTripId, formalTripVersion and trip ([server/index.mjs:2440-2482](C:/Users/summer/Documents/Codex/2026-08-12/loop-p0-functional-inventory-1-home/server/index.mjs:2440)). The returned trip/session/version are thus sourced from the Java open result; the extra formal read supports BFF session/workspace setup. Whether that extra read can produce inconsistent workspace metadata during a concurrent formal update is not explicitly tested.

### Draft restore

Frontend stores a backup at `draft_conflict_backup_{userId}_{savedTripId}` when local source-formal version or local draft contents diverge from the opened cloud snapshot ([save-service.js:106-190](C:/Users/summer/Documents/Codex/2026-08-12/loop-p0-functional-inventory-1-home/frontend/web/src/features/trip-save/save-service.js:106)). A successful backup allows state/local plan to move to the authoritative new server session; failed storage write sets a visible failure state and avoids overwriting the active local-plan record ([save-service.js:135-179](C:/Users/summer/Documents/Codex/2026-08-12/loop-p0-functional-inventory-1-home/frontend/web/src/features/trip-save/save-service.js:135), [save-service.js:227-240](C:/Users/summer/Documents/Codex/2026-08-12/loop-p0-functional-inventory-1-home/frontend/web/src/features/trip-save/save-service.js:227)).

The planning page renders restore/discard controls when a backup is available and a retry path when backup storage failed ([plan-page.js:156-168](C:/Users/summer/Documents/Codex/2026-08-12/loop-p0-functional-inventory-1-home/frontend/web/src/pages/plan-page.js:156)). `restoreDraftBackup` requires the same formal trip to be open, sends current BFF session ID and current server session version plus the saved backup trip, then checks returned session ID before adopting; local backup is deleted only after local persistence matches the returned session/trip/version ([save-service.js:450-521](C:/Users/summer/Documents/Codex/2026-08-12/loop-p0-functional-inventory-1-home/frontend/web/src/features/trip-save/save-service.js:450)). `discardDraftBackup` removes only the user/trip-scoped localStorage key ([save-service.js:524-541](C:/Users/summer/Documents/Codex/2026-08-12/loop-p0-functional-inventory-1-home/frontend/web/src/features/trip-save/save-service.js:524)).

BFF restore checks logged-in owner, BFF session owner, matching formal Trip ID and Java session presence before forwarding ([server/index.mjs:2412-2426](C:/Users/summer/Documents/Codex/2026-08-12/loop-p0-functional-inventory-1-home/server/index.mjs:2412)). Java checks authenticated actor, session capability, expected planner revision, formal Trip ownership and that the backup's sourceTripVersion equals the current formal version; it then replaces browser-supplied formal identity/version with server values and updates the planner session using expected current revision ([PlannerService.java:336-370](../../ai/src/main/java/com/ai/guide/domain/planner/service/PlannerService.java:336)). If BFF's post-Java draft-cache persistence fails, it logs the failure but still returns the already-committed Java snapshot ([server/index.mjs:2422-2438](C:/Users/summer/Documents/Codex/2026-08-12/loop-p0-functional-inventory-1-home/server/index.mjs:2422)).

## 4. Identity, stale-response and version guards

- BFF write endpoints are POST/PUT and pass the common mutation-security check, which calls `assertMutationSecurity`; frontend request attaches the CSRF header for mutation verbs ([server/index.mjs:1110-1120](C:/Users/summer/Documents/Codex/2026-08-12/loop-p0-functional-inventory-1-home/server/index.mjs:1110), [client.js:31-64](C:/Users/summer/Documents/Codex/2026-08-12/loop-p0-functional-inventory-1-home/frontend/web/src/shared/api/client.js:31)).
- BFF planner-session rehydration checks both authenticated user and device ownership before restoring a Java planner session via server-held capability ([server/index.mjs:863-915](C:/Users/summer/Documents/Codex/2026-08-12/loop-p0-functional-inventory-1-home/server/index.mjs:863)).
- Frontend `captureAuthentication` guards account generation/user ID; `captureWorkspace` guards workspace generation, and in `exact` mode also session ID, formal ID and trip object identity ([workspace-lifecycle.js:3-31](C:/Users/summer/Documents/Codex/2026-08-12/loop-p0-functional-inventory-1-home/frontend/web/src/app-core/workspace-lifecycle.js:3)). Save uses exact ownership; restore uses exact ownership; open currently uses default non-exact ownership (`captureWorkspace()`), plus an `openingTripId` busy guard and owner check after asynchronous calls ([save-service.js:21-27](C:/Users/summer/Documents/Codex/2026-08-12/loop-p0-functional-inventory-1-home/frontend/web/src/features/trip-save/save-service.js:21), [save-service.js:81-105](C:/Users/summer/Documents/Codex/2026-08-12/loop-p0-functional-inventory-1-home/frontend/web/src/features/trip-save/save-service.js:81), [save-service.js:473-484](C:/Users/summer/Documents/Codex/2026-08-12/loop-p0-functional-inventory-1-home/frontend/web/src/features/trip-save/save-service.js:473)).
- Individual trip mutations capture session/formal ID/user ID and starting version; response adoption rejects a non-owned ticket or a response older than either the ticket or current trip version ([trip-mutation.js:3-37](C:/Users/summer/Documents/Codex/2026-08-12/loop-p0-functional-inventory-1-home/frontend/web/src/entities/trip/trip-mutation.js:3)). Workspace writes additionally track per-formal-trip authority generation, chatEpoch and workspaceRevision ([workspace-service.js:13-25](C:/Users/summer/Documents/Codex/2026-08-12/loop-p0-functional-inventory-1-home/frontend/web/src/features/trip-workspace/workspace-service.js:13), [workspace-service.js:36-90](C:/Users/summer/Documents/Codex/2026-08-12/loop-p0-functional-inventory-1-home/frontend/web/src/features/trip-workspace/workspace-service.js:36)).
- No save/open delayed-response test was located that switches account/workspace while the request is pending and then verifies the final UI. The restore result is guarded with exact session/workspace ownership, but the unit tests do not exercise browser scheduling or a real page lifecycle.

## 5. Existing test and UI evidence

### Java

- `TripServiceTest.createUsesServerTripIdAndCreatesVersionOne`, `updateAppendsFullSnapshotAndNoopDoesNotCreateVersion`, `updateRequiresExpectedVersion`, `staleUpdateReturnsCasConflictMetadata`, and idempotency replay/fingerprint conflict: [TripServiceTest.java:24-124](../../ai/src/test/java/com/ai/guide/domain/trip/service/TripServiceTest.java:24).
- Owner scoping and owner-scoped idempotency keys: [TripOwnershipTest.java:23-53](../../ai/src/test/java/com/ai/guide/domain/trip/service/TripOwnershipTest.java:23).
- Save ID linking, concurrent draft edit preservation, service reconstruction after formal save, transaction rollback, and reservation contention: [PlannerSaveConsistencyTest.java:44-126](../../ai/src/test/java/com/ai/guide/domain/trip/service/PlannerSaveConsistencyTest.java:44). Uses temporary SQLite/JDBC fixtures, not live PostgreSQL.
- Draft restore checks current owned session/server revision, stale planner/formal versions and zero writes: [PlannerDraftRestoreTest.java:49-93](../../ai/src/test/java/com/ai/guide/domain/planner/service/PlannerDraftRestoreTest.java:49).
- Generic formal-trip HTTP contract and ownership/version response fields: [TripControllerTest.java:49-108](../../ai/src/test/java/com/ai/guide/adapter/trip/web/TripControllerTest.java:49). It is not the planner open/save/restore endpoint test.

### BFF and frontend

- `draft-restore-route.test.mjs` contains four extracted-route harness cases: owned restoration, owner/formal mismatch denial, formal-version conflict without mutation, and Java-committed success when BFF cache persistence fails ([draft-restore-route.test.mjs:31-67](C:/Users/summer/Documents/Codex/2026-08-12/loop-p0-functional-inventory-1-home/server/draft-restore-route.test.mjs:31)). This evaluates a source slice with mocks, not a running BFF process.
- `draft-backup-restore.test.mjs` has three Node tests: rendered restore/discard affordances plus mocked restore/current-session mutation flow; 409 preserves current UI/backup; failed backup retains original saved plan and exposes retry ([draft-backup-restore.test.mjs:31-78](C:/Users/summer/Documents/Codex/2026-08-12/loop-p0-functional-inventory-1-home/frontend/web/src/features/trip-save/draft-backup-restore.test.mjs:31)). These are DOM-markup/function tests with mocked fetch/localStorage, not browser interaction evidence.
- BFF test search found no direct `/api/trips/save` or `/api/trips/:id/open` route harness. Frontend test search found no `saveTrip` or `openSavedTrip` stale-response lifecycle test. No browser E2E proof for save→open→restore→edit→save→reload was located.

## 6. Baseline gaps and uncertainty

1. **Application boundary:** `TripApplicationService` is present for formal CRUD but forwards to `TripService`; V1 Planner Save/Open/Restore are direct `PlannerController` → `PlannerService` domain calls. This is an architectural migration opportunity, not evidence by itself of broken behavior.
2. **Open round-trip:** there is no test tying Java saved formal ID/version to a newly opened session's exact trip snapshot and then a subsequent mutation/save. Unit pieces exist but do not establish the CP4 end-to-end invariant.
3. **BFF route compatibility:** direct save/open BFF routes lack dedicated route contract tests. Fields currently observable include `saved.id`, `isUpdate`, `workspace`, `sessionId`, `javaSessionId`, `sessionAccessToken`, `savedTripId`, `formalTripVersion`, `trip`, and restore's `{sessionId, trip}`. Error mapping relies on `javaErrorResponse`; exact response bodies for stale/missing/unauthorized cases need a focused matrix before implementation.
4. **Open exact guard:** `openSavedTrip` captures non-exact workspace ownership; generation/account changes invalidate a response, but the call does not itself check captured trip object or formal version. Concurrent open is blocked by `openingTripId`; interactions that do not invalidate generation should be tested before deciding this is a defect.
5. **Local backup matching:** opened local draft is considered matching by `savedTripId`; diff detection compares source formal version, planner version and serialized days. Other trip metadata differences are not part of `hasLocalModifications` comparison. Whether those fields are user-editable/business-significant is not established here.
6. **Response after Java commit:** restore may commit in Java and fail persisting BFF's cache, then still return success. The Java session remains the authority and BFF can rehydrate later, but user-visible retry/reload behavior across actual process restart is not covered by current tests.
7. **Runtime UI:** current tests prove generated markup and mocked callback behavior only. The CP4 plan's real-interaction requirement remains unverified.

## 7. Recommended smallest CP4 slice

Start with **CP4.1: extract the V1 Planner Save use case into the Java application layer only**, keeping the existing `PlannerService.save` entry as a compatibility delegate and leaving BFF/front-end wire behavior unchanged. Preserve the current session owner/capability checks, `{sessionId, revision}` idempotency key, stable formal Trip ID/link record, no-op comparison, sourceTripVersion CAS/rebase, response fields, and race behavior. This one workflow can be frozen and independently tested against the existing SQLite-backed `PlannerSaveConsistencyTest` plus new application/Controller contract tests. It makes the app boundary real without coupling first change to Open/Restore and avoids touching BFF or UI while their route/UI test gaps are separately captured.

After CP4.1 review, follow with an Open/Restore slice that returns/adopts one coherent server snapshot and verifies source formal version, planner revision, BFF session mapping and local-backup lifecycle. A later BFF/frontend slice should add save/open route tests and stale account/workspace response tests, then satisfy the CP4 save→open→edit→save→reload exit path with real browser evidence.

## 8. Command-safe verification plan for implementation

Do not run `mvn clean` in the shared `D:/scenic-guide/ai` tree. For Java, copy the repository to a unique `%TEMP%` directory while excluding `.git` and existing `target`; run Maven from the copy's `ai` directory, with a unique SQLite URL under that copy/temp directory and fake provider URLs/keys per execution plan §6.1. Keep the full command and raw logs with the candidate. Do not reuse a currently running process's SQLite database.

For Node and frontend, make a separate unique temp copy of the BFF/frontend worktrees (preserving the current dirty/untracked test files); omit `.git`, `node_modules`, and generated build directories. Install dependencies only inside that copy from its lockfiles if required. Enumerate test paths explicitly as in execution plan §6.2, run `node --test`, `node --check server/index.mjs`, and the frontend build in the copy. If dependency installation or browser tooling is unavailable, record that layer as unverified; do not point commands at the shared `target`, workspace database, or active local services.

No test was run during this read-only baseline task. The report is evidence mapping, not CP4 acceptance.

## 9. Experience use record

Task receipt: `20261009-15af54966195` ([task JSON](C:/Users/summer/Documents/ggy的自动化流程/wiki/tasks/20261009-15af54966195.json)). Search for save/CAS/backup/workspace recovery experience found no applicable topic; no historical advice was adopted. The baseline conclusions above come from current source and test inspection only. No tests or UI validation were executed.
