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
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.execution.remote.RemoteFeedbackEffectOperationHandler;
import com.bytequay.app.developmentflow.stage.persistence.SqliteReadinessAssistanceStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteReadinessAssistanceStore.Action;
import com.bytequay.app.developmentflow.stage.persistence.SqliteReadinessAssistanceStore.AuthorizationRequest;
import com.bytequay.app.developmentflow.stage.persistence.SqliteReadinessAssistanceStore.DeliveryReceipt;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.ACCEPTED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.REJECTED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.SUPERSEDED;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;

/** Human command boundary for ready-but-unmergeable Remote assistance. */
public final class V2ReadinessAssistanceRuntime
        implements ExecutionPorts.ResultDeliveryPort
{
    private final SqliteReadinessAssistanceStore store;
    private final TaskCommandExecutor commands;
    private final ObjectMapper json;
    private final Clock clock;

    public V2ReadinessAssistanceRuntime(
            SqliteReadinessAssistanceStore store,
            TaskCommandExecutor commands,
            ObjectMapper json)
    {
        this(store, commands, json, Clock.systemUTC());
    }

    V2ReadinessAssistanceRuntime(
            SqliteReadinessAssistanceStore store,
            TaskCommandExecutor commands,
            ObjectMapper json,
            Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.commands = requireNonNull(commands, "commands is null");
        this.json = requireNonNull(json, "json is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public Action authorize(AuthorizationRequest request)
    {
        try {
            return commands.execute(request.taskId(), () ->
                    store.authorize(request, clock.instant()));
        }
        catch (DataAccessException | IllegalStateException failure) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Readiness assistance no longer matches the exact ready PR",
                    failure);
        }
    }

    @Override
    public DispatchTicket.DeliveryReceipt deliver(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence fence,
            DispatchTicket.DispatchResult result)
    {
        requireNonNull(owner, "owner is null");
        requireNonNull(fence, "fence is null");
        requireNonNull(result, "result is null");
        if (owner.kind() != DispatchTicket.OwnerKind.STAGE
                || !RemoteFeedbackEffectOperationHandler
                        .READINESS_ASSISTANCE_CALLBACK_ROUTE.equals(
                                owner.callbackRoute())
                || !fence.equals(result.fence())) {
            return receipt(SUPERSEDED,
                    "Readiness assistance delivery fence is stale");
        }
        Action action = store.require(fence.operationId());
        return commands.execute(action.taskId(), () ->
                deliverInCommand(owner, fence, result, action));
    }

    private DispatchTicket.DeliveryReceipt deliverInCommand(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence fence,
            DispatchTicket.DispatchResult result,
            Action action)
    {
        String rawDigest = digest(write(result));
        DeliveryReceipt duplicate = store.findReceipt(action.operationId())
                .orElse(null);
        if (duplicate != null) {
            if (!duplicate.rawResultDigest().equals(rawDigest)) {
                return receipt(REJECTED,
                        "Readiness assistance operation received a new result");
            }
            return receipt(
                    DispatchTicket.Acceptance.valueOf(duplicate.acceptance()),
                    duplicate.evidence());
        }

        DispatchTicket.Acceptance acceptance;
        String evidence;
        if (!matches(owner, fence, action)) {
            acceptance = SUPERSEDED;
            evidence = "Readiness assistance no longer owns this result";
        }
        else if (result.outcome() == DispatchTicket.Outcome.SUCCEEDED
                && !"SUCCEEDED".equals(action.status())) {
            acceptance = REJECTED;
            evidence = "Successful assistance lacks durable effect proof";
        }
        else {
            if (result.outcome() == DispatchTicket.Outcome.SUCCEEDED) {
                acceptance = ACCEPTED;
                evidence = "readiness-assistance-complete:" + action.id();
            }
            else {
                try {
                    store.abandon(
                            action.operationId(), terminalReason(result),
                            clock.instant());
                    acceptance = ACCEPTED;
                    evidence = "readiness-assistance-ended:"
                            + result.outcome();
                }
                catch (IllegalStateException failure) {
                    acceptance = REJECTED;
                    evidence = failure.getMessage();
                }
            }
        }
        store.insertReceipt(new DeliveryReceipt(
                action.operationId(), rawDigest, acceptance.name(), evidence,
                clock.instant()));
        return receipt(acceptance, evidence);
    }

    private static boolean matches(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence fence,
            Action action)
    {
        return action.stageId().equals(owner.id())
                && action.operationId().equals(fence.operationId())
                && Objects.equals(action.taskEpoch(), fence.taskEpoch())
                && action.stageId().equals(fence.stageId())
                && Objects.equals(
                        action.stageGeneration(), fence.stageGeneration())
                && action.headSha().equals(fence.expectedHeadSha())
                && action.baseSha().equals(fence.expectedBaseSha());
    }

    private String write(DispatchTicket.DispatchResult result)
    {
        try {
            return json.writeValueAsString(result);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Could not encode readiness assistance result", e);
        }
    }

    private static String terminalReason(DispatchTicket.DispatchResult result)
    {
        return result.outcome() + ": "
                + requireNonNullElse(
                        result.error(), "terminal dispatch result");
    }

    private static String digest(String value)
    {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static DispatchTicket.DeliveryReceipt receipt(
            DispatchTicket.Acceptance acceptance, String evidence)
    {
        return new DispatchTicket.DeliveryReceipt(acceptance, evidence);
    }
}
