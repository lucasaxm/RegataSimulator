# Phase 3 remaining removal manifest

Root for every path below: `/Users/lucas.xavier/repos/lucas/RegataSimulator/`.

The exact editor batch Delete operation reported success for these 51 paths, but independent search/disk/Git verification showed no deletions. Single-file retry input was exactly `*** Begin Patch`, newline, `*** Delete File: /Users/lucas.xavier/repos/lucas/RegataSimulator/src/main/java/com/boatarde/regatasimulator/flows/WorkflowStep.java`, newline, `*** End Patch`. Result: “The following files were successfully edited” naming that path; disk check: `DELETE_VERIFY WorkflowStep.java STILL_EXISTS`. Do not infer deletion from that success message.

`src/main/java/com/boatarde/regatasimulator/flows/ApplicationFailure.java` is already actually deleted and is **not** in this pending list. Replacement contracts are in commit `b30a42c`. `ReviewCallbackStepsTest.java` is retained and migrated to active adapters, not deleted.

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

## After successful physical removal

Remove BackupService's legacy constructor/`zipToTelegram` method and associated bot imports, make its injected fields final, verify no obsolete production imports in tests/source, then run focused tests, `./gradlew clean build`, Node security tests and `git diff --check`. Record actual XML totals and update current map/plan/AGENTS to complete only after those checks. Keep historical reports clearly historical. JsonDB message compatibility and rendering/process algorithms remain unchanged; Phases 4–5 are outside this task.