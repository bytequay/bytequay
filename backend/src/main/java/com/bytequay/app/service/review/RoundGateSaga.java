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
package com.bytequay.app.service.review;

import com.bytequay.app.config.AsyncConfig;
import com.bytequay.app.developmentflow.execution.RetiredSagaGate;
import com.bytequay.app.domain.ReviewRound;
import com.bytequay.app.domain.RoundGateAuthorization;
import com.bytequay.app.repository.ReviewRoundStore;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.sqlite.RoundGateStore;
import com.bytequay.app.service.checks.CodeFingerprints;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.bytequay.app.service.threads.TaskPhaseMachine;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/** Read-only compatibility surface for historical review-gate authorizations. */
@Service
public class RoundGateSaga
{
    static final String EFFECT_PUSH_BRANCH = "push_branch";
    public static final int DEFAULT_RECOVERY_ALLOWANCE = 1;

    private final RoundGateStore gates;
    private final ObjectMapper mapper;

    public RoundGateSaga(
            ReviewRoundStore rounds,
            RoundGateStore gates,
            TaskStore tasks,
            StageStore stages,
            ReviewRoundStateMachine roundMachine,
            TaskPhaseMachine taskMachine,
            TaskCommandExecutor commands,
            PRService prs,
            PullRequestService pullRequests,
            GitRunner git,
            CodeFingerprints fingerprints,
            RetiredSagaGate capacity,
            ObjectMapper mapper,
            @Qualifier(AsyncConfig.APPLICATION_EXECUTOR) Executor executor)
    {
        requireNonNull(rounds, "rounds is null");
        this.gates = requireNonNull(gates, "gates is null");
        requireNonNull(tasks, "tasks is null");
        requireNonNull(stages, "stages is null");
        requireNonNull(roundMachine, "roundMachine is null");
        requireNonNull(taskMachine, "taskMachine is null");
        requireNonNull(commands, "commands is null");
        requireNonNull(prs, "prs is null");
        requireNonNull(pullRequests, "pullRequests is null");
        requireNonNull(git, "git is null");
        requireNonNull(fingerprints, "fingerprints is null");
        requireNonNull(capacity, "capacity is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        requireNonNull(executor, "executor is null");
    }

    public ReviewRound approve(String roundId)
    {
        throw retired();
    }

    public void drive(String token)
    {
        throw retired();
    }

    public Optional<String> activeToken(String taskId)
    {
        return gates.findActiveByTask(taskId).map(RoundGateAuthorization::token);
    }

    public <T> T editPayload(String taskId, String roundId, Supplier<T> mutation)
    {
        throw retired();
    }

    public void editPayload(String taskId, String roundId, Runnable mutation)
    {
        throw retired();
    }

    public Optional<RecoveryPlan> prepareRecovery(String taskId, int addedAllowance)
    {
        throw retired();
    }

    public Optional<RecoveryPlan> verifyRecoveryRequest(String taskId)
    {
        throw retired();
    }

    public String recoveryPayload(RecoveryPlan plan)
    {
        try {
            return mapper.writeValueAsString(plan);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("serializing round gate payload failed", e);
        }
    }

    public void resumeExternalSagaInCommand(RecoveryPlan plan)
    {
        throw retired();
    }

    public void onAuthorized(RoundGateAuthorizedEvent event)
    {
        throw retired();
    }

    public void reconcileActive()
    {
        throw retired();
    }

    private static ResponseStatusException retired()
    {
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                "LEGACY review-round gates are read-only; use typed V2 remote actions");
    }

    public record RecoveryPlan(
            String taskId,
            String roundId,
            String runId,
            String token,
            String effectKey,
            String reason,
            int addedAllowance,
            String headSha,
            String codeFingerprint) {}
}
