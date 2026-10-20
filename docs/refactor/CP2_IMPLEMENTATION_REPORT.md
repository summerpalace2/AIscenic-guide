# CP2 preview and clarification use-case implementation

Date: 2026-10-09

## Scope and starting identity

This report records the CP2 implementation candidate for §3.3 of `COLA_REFACTOR_EXECUTION_PLAN.md`. It is a frozen implementation candidate for independent review, not an acceptance decision.

Before editing, the CP2 start identity was captured in `outputs/20261009-CP2-start-candidate.json` (outside the repository):

- HEAD: `46b1653e81050e3cdd9f23506b327bc6676bcbd8`
- Tracked patch SHA-256: `44cbd37f06e4b6dfdc3c7ad9154922673e128165560e83dbdf12da7692e3d04a`
- CP1 R4 port, adapter, service, proposal model, contract test, and report hashes are preserved in that JSON.

The starting scan confirmed the application service was a pass-through. Authorization and revision checks, preview dispatch, continuation handling, proposal persistence and response assembly were concentrated in `PlanAdjustmentService`. The HTTP adapter forwarded `PlannerService.ServiceResult.status()` directly. CP0 already documented domain-level preview and continuation tests, but no application-use-case behavior test.

## Implementation

Both `/sessions/{id}/conversation` and `/sessions/{id}/adjust/preview` now enter the same typed application use case. The adapter passes a `PlanningActor` derived from the authenticated request context and retains the existing session-token header fallback. The application layer resolves the user-owned or anonymous-capability session and checks the requested base revision before calling preview policy. The old `PlannerService` entry points remain intact for compatibility and continue to use the original authorization path.

The application passes a proposal-writer callback backed by `ProposalRepositoryPort`; proposal creation and lifecycle rules remain with the domain preview policy, while the application use case supplies the persistence capability. Intent parsing, target clarification, pending-intent continuation, time/scope rules, protection constraints and proposal eligibility remain in the domain service. Apply and cancel methods and their commit protocol were not changed.

The domain/application boundary for this new path returns `AdjustmentPreviewExecution` and `PreviewAdjustmentResult`, both with business outcomes and response data but no numeric HTTP status. Clarification, no-candidate and non-applicable preview outcomes are distinct from an applicable preview; the adapter still maps them to HTTP 200 to preserve the existing wire contract, while `canApply:false` remains authoritative. The controller maps typed failures to the same HTTP codes and preserves response body fields. Legacy `PlannerService.ServiceResult` remains inside the compatibility path and inside the current domain engine pending later extraction; it is not the public result of the new application use case.

Changed CP2 files:

- `ai/src/main/java/com/ai/guide/application/planner/PlanningActor.java` — explicit actor input.
- `ai/src/main/java/com/ai/guide/application/planner/PreviewAdjustmentResult.java` — typed application outcome and unchanged wire body.
- `ai/src/main/java/com/ai/guide/domain/planner/model/AdjustmentPreviewExecution.java` — typed business result without HTTP status.
- `ai/src/main/java/com/ai/guide/application/planner/PlanningApplicationService.java` and `PlannerApplicationService.java` — typed use-case contract, identity/revision checks, proposal persistence callback, and outcome assembly.
- `ai/src/main/java/com/ai/guide/domain/planner/service/PlanAdjustmentService.java` — preauthorized-session entry point and proposal-writer capability; legacy entry remains.
- `ai/src/main/java/com/ai/guide/domain/planner/service/PlannerService.java` — exposes the existing feature flag to the application layer; legacy preview/conversation methods remain.
- `ai/src/main/java/com/ai/guide/adapter/planner/web/PlannerController.java` — explicit actor handoff and typed outcome to HTTP mapping for conversation and preview.
- `ai/src/test/java/com/ai/guide/application/planner/PlannerPreviewUseCaseTest.java` — end-to-end application/service/repository behavior tests.
- `ai/src/test/java/com/ai/guide/adapter/planner/web/PlannerControllerTest.java` — token forwarding, actor handoff, compatible body and HTTP outcome mapping.

Some files above already contained unrelated CP0/CP1 work before the CP2 start identity; the frozen patch/hash binds the complete working tree. This implementation did not edit BFF, frontend, apply/cancel logic, or unrelated business rules.

## Verification

Final command, run from the isolated copy at `C:\Users\summer\AppData\Local\Temp\scenic-guide-cp2-279216691b104a4c933ac4c38f5f8ec4`:

```powershell
.\mvnw.cmd -f .\ai\pom.xml clean test "-Dtest=PlannerPreviewUseCaseTest,PlannerControllerTest,PlanAdjustmentServiceTest,ColaArchitectureGuardTest"
```

Result: **95 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**. The suite includes four application-use-case tests (preview leaves the durable trip and revision unchanged; a short answer preserves the original day target; generated proposals are persisted through the application-provided port; an unauthorized session is rejected), controller compatibility tests including non-applicable preview mapping, the existing 66 `PlanAdjustmentServiceTest` cases, and eight architecture guards. In the continuation case, the second short answer still leaves one required clarification field unresolved; the assertion verifies the day-2 target remains attached to the pending intent. Raw final log: `outputs/20261009-CP2-maven-tests-final.log` outside the repository.

An earlier isolated test attempt failed compilation because CP2 temporarily added a duplicate `PlannerService.isPlannerV1Enabled()` method. This was removed. The original attempt log was overwritten by a later rerun, so a separate isolated compile with the same duplicate-method condition was reproduced and retained at `outputs/20261009-CP2-diagnostic-duplicate-getter.log`. The subsequent clean isolated test without extra compiler flags passed. The host and prior CP1 run both used Maven 3.9.14, Java 23.0.1, compiler plugin 3.13.0 and Lombok 1.18.36. The first error was the duplicate method; the apparent Lombok missing-symbol diagnostics were cascading compiler errors, not an annotation processor/classpath difference.

## Limits and review boundary

- No BFF, browser, or live HTTP integration test was run; controller mapping and application behavior are separately tested.
- No full Java suite was run. The existing service regression subset plus new use-case/controller tests and architecture guard were run.
- External Qdrant was unavailable during Spring test startup; the configured fallback was active. No external Redis behavior is asserted by CP2.
- Proposal creation and continuation remain domain policy. The application owns session/revision orchestration, the proposal persistence capability, and typed use-case result assembly; this is an intentionally bounded CP2 extraction, not a claim that the entire adjustment engine has moved out of the domain service.
- Apply/cancel protocol and real browser behavior remain for later checkpoints.

The frozen candidate identity and hashes are recorded in `outputs/20261009-CP2-candidate.json` outside the repository. This candidate is ready for independent architecture and red-team review; no CP2 acceptance claim is made here.
