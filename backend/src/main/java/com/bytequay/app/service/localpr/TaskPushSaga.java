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
package com.bytequay.app.service.localpr;

import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.TaskPushAuthorization;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.sqlite.TaskPushStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Read-only compatibility surface for historical task-push authorizations. */
@Service
public class TaskPushSaga
{
    static final String EFFECT_PUSH_BRANCH = "push_branch";
    static final String EFFECT_ENSURE_PULL_REQUEST = "ensure_pull_request";
    public static final int DEFAULT_RECOVERY_ALLOWANCE = 1;

    private final TaskStore tasks;
    private final TaskPushStore pushes;
    private final ObjectMapper mapper;

    public TaskPushSaga(TaskStore tasks, TaskPushStore pushes, ObjectMapper mapper)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.pushes = requireNonNull(pushes, "pushes is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    public PR push(String prId, boolean humanOverride)
    {
        throw retired();
    }

    public void drive(String token)
    {
        throw retired();
    }

    public Optional<String> activeToken(String taskId)
    {
        if (tasks.isV2Task(taskId)) {
            return Optional.empty();
        }
        return pushes.findActiveByTask(taskId).map(TaskPushAuthorization::token);
    }

    public boolean adoptRemotePullRequest(String taskId, String repo, int number, String url)
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
            throw new IllegalStateException("serializing push saga payload failed", e);
        }
    }

    public void resumeExternalSagaInCommand(RecoveryPlan plan)
    {
        throw retired();
    }

    public void reconcileActive()
    {
        throw retired();
    }

    public boolean revokeUnclaimedInCommand(String taskId, String reason)
    {
        throw retired();
    }

    private static ResponseStatusException retired()
    {
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                "TaskPushSaga is retired; V2 publish is owned by the typed remote runtime");
    }

    /** Immutable payload persisted on an EXTERNAL_SAGA recovery request. */
    public record RecoveryPlan(
            String token,
            String effectKey,
            String reason,
            int addedAllowance,
            String headSha,
            String codeFingerprint) {}
}
