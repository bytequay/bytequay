# Test baseline

`mvn verify` on the working tree, recorded before the CLI-transport slice.
Every slice after this must add **no new failure**. Reproduced identically
before and after the surfaces/phase-3 work, so none of it is attributable to
that work — these live in the in-flight `developmentflow`, stage-projection
and skills areas, plus one genuine concurrency flake.

```text
Tests run: 15, Failures: 0, Errors: 3, Skipped: 0, -- com.bytequay.app.repository.sqlite.migration.TestV2StageApiService
Tests run: 35, Failures: 10, Errors: 1, Skipped: 0, -- com.bytequay.app.developmentflow.execution.agentturn.TestAgentTurnOperationHandler
Tests run: 5, Failures: 1, Errors: 0, Skipped: 0, -- com.bytequay.app.developmentflow.persistence.TestSqliteCapacityLeaseStore
Tests run: 1, Failures: 0, Errors: 1, Skipped: 0, -- com.bytequay.app.developmentflow.persistence.TestTerminalExecutionEvidenceRecoveryMigration
Tests run: 6, Failures: 0, Errors: 1, Skipped: 0, -- com.bytequay.app.developmentflow.TestLegacyExecutionRetirement
Tests run: 2, Failures: 1, Errors: 0, Skipped: 0, -- com.bytequay.app.service.skills.TestPonytailBundleService
Tests run: 5, Failures: 1, Errors: 0, Skipped: 0, -- com.bytequay.app.flow.runtime.TestNewFlowDatabase
Tests run: 3016, Failures: 13, Errors: 6, Skipped: 3

Tests run: 3016, Failures: 13, Errors: 6, Skipped: 3
```

## Notes

- `TestNewFlowDatabase.concurrentBootstrapsProduceOneCompleteMarker` fails on
  `SQLITE_IOERR_DELETE_NOENT` — a WAL/journal race between concurrent
  bootstraps. Passes 5/5 when the class runs alone.
- Backend timings here are unreliable while `dev.sh` is running: it shares
  `target/` and turned a 16-second class into 956 seconds, which produced a
  false `awaitStatus` timeout. Stop it before trusting a red result.
