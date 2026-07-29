/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.bytequay.app.developmentflow.stage;

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionContext;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.stage.persistence.SqliteManualPrValidationStore;
import com.bytequay.app.service.checks.CodeFingerprints;
import com.bytequay.app.service.checks.RepoTestValidationCheck;
import com.bytequay.app.service.checks.RepoTestValidationCheck.TestRun;
import com.bytequay.app.service.checks.ValidationFailure;
import com.bytequay.app.service.local.GitRunner;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.AsyncFamily.VALIDATION;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.SUCCEEDED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.OwnerKind.TASK;
import static java.util.Objects.requireNonNull;

/** Executes one user-requested test run inside the shared V2 validation lane. */
@Component
@SuppressWarnings("StringConcatToTextBlock")
public final class ManualPrValidationOperationHandler
        implements ExecutionPorts.OperationHandler
{
    public static final String OPERATION_KIND = "VALIDATE_PR_MANUALLY";
    public static final String CALLBACK_ROUTE = "MANUAL_PR_VALIDATION_RESULT";

    private final SqliteManualPrValidationStore store;
    private final RepoTestValidationCheck tests;
    private final CodeFingerprints fingerprints;
    private final GitRunner git;
    private final ObjectMapper json;
    private final Clock clock;

    @Autowired
    public ManualPrValidationOperationHandler(
            SqliteManualPrValidationStore store,
            RepoTestValidationCheck tests,
            CodeFingerprints fingerprints,
            GitRunner git,
            ObjectMapper json)
    {
        this(store, tests, fingerprints, git, json, Clock.systemUTC());
    }

    ManualPrValidationOperationHandler(
            SqliteManualPrValidationStore store,
            RepoTestValidationCheck tests,
            CodeFingerprints fingerprints,
            GitRunner git,
            ObjectMapper json,
            Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.tests = requireNonNull(tests, "tests is null");
        this.fingerprints = requireNonNull(fingerprints, "fingerprints is null");
        this.git = requireNonNull(git, "git is null");
        this.json = requireNonNull(json, "json is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public DispatchTicket.DispatchResult execute(ExecutionContext execution)
            throws Exception
    {
        requireNonNull(execution, "execution is null");
        try {
            return executeExact(execution);
        }
        catch (Exception failure) {
            if (execution.isCancellationRequested()) {
                throw new ExecutionPorts.OperationCanceledException(
                        "Manual PR validation was canceled");
            }
            throw failure;
        }
    }

    private DispatchTicket.DispatchResult executeExact(ExecutionContext execution)
            throws Exception
    {
        DispatchTicket.DispatchEnvelope envelope = execution.envelope();
        SqliteManualPrValidationStore.ExecutionContext context =
                store.requireExecutionContext(envelope.fence().operationId());
        requireEnvelope(envelope, context);
        execution.onCancellation(Thread.currentThread()::interrupt);
        if (execution.isCancellationRequested()) {
            throw new ExecutionPorts.OperationCanceledException(
                    "Manual PR validation was canceled");
        }
        Instant started = clock.instant();
        Path worktree = Path.of(context.worktreePath());
        ObservedCode before = observe(worktree, context.baseSha());
        boolean current = context.current() && before.matches(context);
        List<ValidationFailure> failures = List.of();
        TestRun testRun = null;
        if (current) {
            if (execution.isCancellationRequested()) {
                throw new ExecutionPorts.OperationCanceledException(
                        "Manual PR validation was canceled");
            }
            Optional<TestRun> observed = tests.runWithoutRecording(worktree);
            testRun = observed.orElse(null);
            failures = observed.map(TestRun::failures).orElseGet(List::of);
            if (execution.isCancellationRequested()) {
                throw new ExecutionPorts.OperationCanceledException(
                        "Manual PR validation was canceled");
            }
        }
        ObservedCode after = observe(worktree, context.baseSha());
        current = current && before.equals(after) && after.matches(context);
        ValidationResult result = new ValidationResult(
                1, context.operationId(), context.prId(), context.taskId(),
                context.taskEpoch(), current, current && failures.isEmpty(),
                testRun, failures, after.fingerprint(), after.headSha(), after.baseSha(),
                started.toEpochMilli(), clock.instant().toEpochMilli());
        String value = write(result);
        return new DispatchTicket.DispatchResult(
                envelope.fence(), SUCCEEDED, value, value, null);
    }

    /** Running tests is repeatable; restart reconciliation reruns the exact subject. */
    @Override
    public DispatchTicket.DispatchResult reconcile(ExecutionContext execution)
            throws Exception
    {
        return execute(execution);
    }

    private static void requireEnvelope(
            DispatchTicket.DispatchEnvelope envelope,
            SqliteManualPrValidationStore.ExecutionContext context)
    {
        DispatchTicket.OperationFence fence = envelope.fence();
        if (!OPERATION_KIND.equals(envelope.operationKind())
                || envelope.family() != VALIDATION
                || envelope.owner().kind() != TASK
                || !context.taskId().equals(envelope.owner().id())
                || !CALLBACK_ROUTE.equals(envelope.owner().callbackRoute())
                || !context.operationId().equals(fence.operationId())
                || !Objects.equals(context.taskEpoch(), fence.taskEpoch())
                || fence.stageId() != null || fence.stageGeneration() != null
                || fence.attempt() != 1
                || !context.codeFingerprint().equals(
                        fence.expectedCodeFingerprint())
                || !context.headSha().equals(fence.expectedHeadSha())
                || !context.baseSha().equals(fence.expectedBaseSha())) {
            throw new IllegalArgumentException(
                    "Manual PR validation ticket differs from its exact Task subject");
        }
    }

    private ObservedCode observe(Path worktree, String baseSha)
            throws Exception
    {
        return new ObservedCode(
                fingerprints.fingerprint(worktree), git.headSha(worktree), baseSha);
    }

    private String write(Object value)
    {
        try {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Could not serialize Manual PR validation result", e);
        }
    }

    public record ValidationResult(
            int schemaVersion,
            String operationId,
            String prId,
            String taskId,
            long taskEpoch,
            boolean subjectCurrent,
            boolean passed,
            TestRun testRun,
            List<ValidationFailure> failures,
            String observedCodeFingerprint,
            String observedHeadSha,
            String observedBaseSha,
            long startedAtMs,
            long completedAtMs)
    {
        public ValidationResult
        {
            if (schemaVersion != 1 || taskEpoch < 1 || startedAtMs < 0
                    || completedAtMs < startedAtMs) {
                throw new IllegalArgumentException(
                        "Manual PR validation result identity is invalid");
            }
            failures = List.copyOf(requireNonNull(failures, "failures is null"));
            if (!subjectCurrent && passed || passed && !failures.isEmpty()
                    || testRun != null && !testRun.failures().equals(failures)) {
                throw new IllegalArgumentException(
                        "Manual PR validation verdict is inconsistent");
            }
        }
    }

    private record ObservedCode(String fingerprint, String headSha, String baseSha)
    {
        private boolean matches(SqliteManualPrValidationStore.ExecutionContext context)
        {
            return fingerprint.equals(context.codeFingerprint())
                    && headSha.equals(context.headSha())
                    && baseSha.equals(context.baseSha());
        }
    }
}
