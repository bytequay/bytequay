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
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteFeedbackLoopStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteFeedbackLoopStore.ValidationContext;
import com.bytequay.app.service.checks.CodeFingerprints;
import com.bytequay.app.service.checks.RepoTestValidationCheck;
import com.bytequay.app.service.checks.ValidationFailure;
import com.bytequay.app.service.local.GitRunner;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.AsyncFamily.VALIDATION;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.SUCCEEDED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.OwnerKind.STAGE;
import static java.util.Objects.requireNonNull;

/** Runs canonical checks against one immutable Remote feedback repair subject. */
public final class RemoteFeedbackValidationOperationHandler
        implements ExecutionPorts.OperationHandler
{
    public static final String OPERATION_KIND = "VALIDATE_REMOTE_FEEDBACK";
    public static final String CALLBACK_ROUTE = "REMOTE_FEEDBACK_VALIDATION_RESULT";

    private final SqliteRemoteFeedbackLoopStore store;
    private final RepoTestValidationCheck check;
    private final CodeFingerprints fingerprints;
    private final GitRunner git;
    private final ObjectMapper json;
    private final Clock clock;

    public RemoteFeedbackValidationOperationHandler(
            SqliteRemoteFeedbackLoopStore store,
            RepoTestValidationCheck check,
            CodeFingerprints fingerprints,
            GitRunner git,
            ObjectMapper json,
            Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.check = requireNonNull(check, "check is null");
        this.fingerprints = requireNonNull(fingerprints, "fingerprints is null");
        this.git = requireNonNull(git, "git is null");
        this.json = requireNonNull(json, "json is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public DispatchTicket.DispatchResult execute(ExecutionContext execution)
            throws Exception
    {
        DispatchTicket.DispatchEnvelope envelope = execution.envelope();
        ValidationContext context = store.requireValidationContext(
                envelope.fence().operationId());
        requireEnvelope(envelope, context);
        Instant startedAt = clock.instant();
        Path worktree = Path.of(context.worktreePath());
        ObservedCode before = observe(worktree, context.baseSha());
        boolean current = context.current() && before.matches(context);
        List<ValidationFailure> failures = new ArrayList<>();
        if (current) {
            if (execution.isCancellationRequested()) {
                throw new ExecutionPorts.OperationCanceledException(
                        "Remote feedback validation was canceled");
            }
            failures.addAll(check.run(context.taskId(), worktree));
        }
        ObservedCode after = observe(worktree, context.baseSha());
        current = current && after.matches(context) && before.equals(after);
        ValidationResult result = new ValidationResult(
                1, context.id(), context.operationId(), context.taskId(),
                context.taskEpoch(), context.stageId(), context.stageGeneration(),
                context.semanticAttempt(), current, current && failures.isEmpty(),
                List.copyOf(failures), after.fingerprint(), after.headSha(),
                after.baseSha(), startedAt.toEpochMilli(),
                clock.instant().toEpochMilli());
        String value = write(result);
        return new DispatchTicket.DispatchResult(
                envelope.fence(), SUCCEEDED, value, value, null);
    }

    private static void requireEnvelope(
            DispatchTicket.DispatchEnvelope envelope, ValidationContext context)
    {
        DispatchTicket.OperationFence fence = envelope.fence();
        if (!OPERATION_KIND.equals(envelope.operationKind())
                || envelope.family() != VALIDATION
                || envelope.owner().kind() != STAGE
                || !context.stageId().equals(envelope.owner().id())
                || !CALLBACK_ROUTE.equals(envelope.owner().callbackRoute())
                || !context.operationId().equals(fence.operationId())
                || !Objects.equals(context.taskEpoch(), fence.taskEpoch())
                || !context.stageId().equals(fence.stageId())
                || !Objects.equals(context.stageGeneration(), fence.stageGeneration())
                || context.semanticAttempt() != fence.attempt()
                || !Objects.equals(context.codeFingerprint(),
                        fence.expectedCodeFingerprint())
                || !Objects.equals(context.headSha(), fence.expectedHeadSha())
                || !Objects.equals(context.baseSha(), fence.expectedBaseSha())) {
            throw new IllegalArgumentException(
                    "Remote validation ticket differs from its exact repair");
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
                    "Could not serialize Remote validation result", e);
        }
    }

    public record ValidationResult(
            int schemaVersion,
            String validationOperationId,
            String operationId,
            String taskId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            int semanticAttempt,
            boolean subjectCurrent,
            boolean passed,
            List<ValidationFailure> failures,
            String observedCodeFingerprint,
            String observedHeadSha,
            String observedBaseSha,
            long startedAtMs,
            long completedAtMs)
    {
        public ValidationResult
        {
            if (schemaVersion != 1 || taskEpoch < 1 || stageGeneration < 1
                    || semanticAttempt < 1 || startedAtMs < 0
                    || completedAtMs < startedAtMs) {
                throw new IllegalArgumentException(
                        "Remote validation result identity is invalid");
            }
            failures = List.copyOf(requireNonNull(failures, "failures is null"));
            if (!subjectCurrent && passed || passed && !failures.isEmpty()) {
                throw new IllegalArgumentException(
                        "Remote validation verdict is inconsistent");
            }
        }
    }

    private record ObservedCode(String fingerprint, String headSha, String baseSha)
    {
        private boolean matches(ValidationContext context)
        {
            return fingerprint.equals(context.codeFingerprint())
                    && headSha.equals(context.headSha())
                    && baseSha.equals(context.baseSha());
        }
    }
}
