# Phase 3 removal manifest (historical; resolved)

Root for every path below: `/Users/lucas.xavier/repos/lucas/RegataSimulator/`.

**Resolved — 2026-10-09:** all 51 paths below and the earlier `flows/ApplicationFailure.java` are physically absent and independently verified as Git deletions (52 total; no extra deletions). BackupService cleanup and active regression validation are complete: clean build **702 tests / 25 suites**, zero failures/errors/skips, Node **5/5**. See [final implemented results](phase-3-service-results.md). The path list is retained as the historical removal record, not pending work.

Earlier editor Delete attempts reported success without changing disk/Git; that blocker is now resolved. Independent disk/Git verification, not a patch success message alone, establishes the completed removal.

`src/main/java/com/boatarde/regatasimulator/flows/ApplicationFailure.java` is deleted and is **not** in the 51-path list. Replacement contracts are in commit `b30a42c`. `ReviewCallbackStepsTest.java` is retained and migrated to active adapters, not deleted.

## Production paths (36)

- `src/main/java/com/boatarde/regatasimulator/flows/WorkflowStep.java`
- `src/main/java/com/boatarde/regatasimulator/flows/WorkflowManager.java`
- `src/main/java/com/boatarde/regatasimulator/flows/WorkflowDataKey.java`
- `src/main/java/com/boatarde/regatasimulator/flows/WorkflowStepRegistration.java`
- `src/main/java/com/boatarde/regatasimulator/flows/WorkflowAction.java`
- `src/main/java/com/boatarde/regatasimulator/flows/WorkflowDataBag.java`
- `src/main/java/com/boatarde/regatasimulator/flows/backup/BackupSourcesStep.java`
- `src/main/java/com/boatarde/regatasimulator/flows/backup/SendReportStep.java`
- `src/main/java/com/boatarde/regatasimulator/flows/backup/BackupJsonDBStep.java`
- `src/main/java/com/boatarde/regatasimulator/flows/backup/BackupTemplatesStep.java`
- `src/main/java/com/boatarde/regatasimulator/flows/ping/BuildPongMessageStep.java`
- `src/main/java/com/boatarde/regatasimulator/flows/simulator/ReviewCallbackSupport.java`
- `src/main/java/com/boatarde/regatasimulator/flows/simulator/BuildMemeStep.java`
- `src/main/java/com/boatarde/regatasimulator/flows/simulator/SendSourceApprovedMessageStep.java`
- `src/main/java/com/boatarde/regatasimulator/flows/simulator/SendSourceRejectedMessageStep.java`
- `src/main/java/com/boatarde/regatasimulator/flows/simulator/SendTemplateRejectedMessageStep.java`
- `src/main/java/com/boatarde/regatasimulator/flows/simulator/SendTemplateApprovedMessageStep.java`
- `src/main/java/com/boatarde/regatasimulator/flows/simulator/CreateSourceStep.java`
- `src/main/java/com/boatarde/regatasimulator/flows/simulator/DeleteReviewSourceStep.java`
- `src/main/java/com/boatarde/regatasimulator/flows/simulator/CreateTemplateStep.java`
- `src/main/java/com/boatarde/regatasimulator/flows/simulator/DeleteReviewTemplateStep.java`
- `src/main/java/com/boatarde/regatasimulator/flows/simulator/SendMemeStep.java`
- `src/main/java/com/boatarde/regatasimulator/flows/simulator/GetRandomTemplateStep.java`
- `src/main/java/com/boatarde/regatasimulator/flows/simulator/GetRandomSourceStep.java`
- `src/main/java/com/boatarde/regatasimulator/flows/simulator/ConfirmReviewTemplateStep.java`
- `src/main/java/com/boatarde/regatasimulator/flows/simulator/ConfirmReviewSourceStep.java`
- `src/main/java/com/boatarde/regatasimulator/flows/common/SendMessageStep.java`
- `src/main/java/com/boatarde/regatasimulator/flows/common/SendPhotoStep.java`
- `src/main/java/com/boatarde/regatasimulator/routes/Route.java`
- `src/main/java/com/boatarde/regatasimulator/routes/PingRoute.java`
- `src/main/java/com/boatarde/regatasimulator/routes/SendMemeRoute.java`
- `src/main/java/com/boatarde/regatasimulator/routes/SendReportRoute.java`
- `src/main/java/com/boatarde/regatasimulator/routes/BackupJsonDBRoute.java`
- `src/main/java/com/boatarde/regatasimulator/routes/CreateSourceRoute.java`
- `src/main/java/com/boatarde/regatasimulator/routes/CreateTemplateRoute.java`
- `src/main/java/com/boatarde/regatasimulator/service/RouterService.java`

## Obsolete duplicate test paths (15)

- `src/test/java/com/boatarde/regatasimulator/service/RouterServiceTest.java`
- `src/test/java/com/boatarde/regatasimulator/routes/PingRouteTest.java`
- `src/test/java/com/boatarde/regatasimulator/routes/SubmissionRoutesTest.java`
- `src/test/java/com/boatarde/regatasimulator/flows/backup/BackupWorkflowTest.java`
- `src/test/java/com/boatarde/regatasimulator/flows/common/SendMessageStepTest.java`
- `src/test/java/com/boatarde/regatasimulator/flows/ping/BuildPongMessageWorkflowStepTest.java`
- `src/test/java/com/boatarde/regatasimulator/flows/simulator/BuildMemeStepTest.java`
- `src/test/java/com/boatarde/regatasimulator/flows/simulator/CreateSourceStepTest.java`
- `src/test/java/com/boatarde/regatasimulator/flows/simulator/CreateTemplateStepTest.java`
- `src/test/java/com/boatarde/regatasimulator/flows/simulator/GetRandomSourceStepTest.java`
- `src/test/java/com/boatarde/regatasimulator/flows/simulator/GetRandomTemplateStepTest.java`
- `src/test/java/com/boatarde/regatasimulator/flows/simulator/DecisionNotificationTest.java`
- `src/test/java/com/boatarde/regatasimulator/flows/simulator/MemeWorkflowTest.java`
- `src/test/java/com/boatarde/regatasimulator/flows/simulator/PreviewCallbackIntegrationTest.java`
- `src/test/java/com/boatarde/regatasimulator/flows/simulator/SendMemeStepTest.java`

## Completed follow-up

Removed BackupService's legacy constructor/`zipToTelegram` method and associated bot imports, made injected fields final, verified no obsolete production imports/callers in tests/source, and passed focused tests, `./gradlew clean build`, Node security tests and `git diff --check`. Actual XML totals and current map/plan/AGENTS record completion. JsonDB message compatibility and rendering/process algorithms remain unchanged; Phases 4–5 are outside this cleanup.