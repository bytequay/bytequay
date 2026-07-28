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

import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.AutomationPolicy;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.ReadinessEvidence;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteDevelopmentRuntimeStore.RemoteContext;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteMergeRuntimeStore.AuthorityKind;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/** Re-proves current Remote readiness after an immutable Task policy revision. */
@Component
public final class RemotePolicyRedriveRuntime
{
    private static final String ACTOR = "task-policy-redrive";

    private final TaskCommandExecutor commands;
    private final RemoteDevelopmentStageManager remote;
    private final SqliteRemoteDevelopmentRuntimeStore store;
    private final RemoteMergeRuntimeCoordinator merges;
    private final Clock clock;

    @Autowired
    public RemotePolicyRedriveRuntime(
            TaskCommandExecutor commands,
            RemoteDevelopmentStageManager remote,
            SqliteRemoteDevelopmentRuntimeStore store,
            RemoteMergeRuntimeCoordinator merges)
    {
        this(commands, remote, store, merges, Clock.systemUTC());
    }

    RemotePolicyRedriveRuntime(
            TaskCommandExecutor commands,
            RemoteDevelopmentStageManager remote,
            SqliteRemoteDevelopmentRuntimeStore store,
            RemoteMergeRuntimeCoordinator merges,
            Clock clock)
    {
        this.commands = requireNonNull(commands, "commands is null");
        this.remote = requireNonNull(remote, "remote is null");
        this.store = requireNonNull(store, "store is null");
        this.merges = requireNonNull(merges, "merges is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public boolean redrive(String taskId)
    {
        requireNonNull(taskId, "taskId is null");
        return commands.execute(taskId, () -> redriveInCommand(taskId));
    }

    private boolean redriveInCommand(String taskId)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        RemoteContext context = store.findPolicyRedriveContext(taskId)
                .orElse(null);
        if (context == null) {
            return false;
        }
        AutomationPolicy policy = store.requireAutomationPolicy(taskId);
        ReadinessEvidence readiness = store.findCurrentReadiness(
                        context.snapshotId(), policy.id())
                .orElseGet(() -> store.proveReadiness(
                        id("remote-policy-readiness",
                                context.snapshotId() + ":" + policy.id()),
                        taskId, context.stageId(),
                        store.findAutomationEligibilityEvidenceId(
                                        taskId, context.taskEpoch())
                                .orElse(null),
                        "Task automation policy " + policy.id()
                                + " re-evaluated accepted snapshot "
                                + context.snapshotId(),
                        clock.instant()));

        boolean changed = false;
        if ("WAITING_REMOTE_REVIEW".equals(context.checkpoint())
                && readiness.ready()) {
            CommandResult<StageManager.State> accepted =
                    remote.acceptReadinessEvidenceInCommand(gate(
                            context, readiness,
                            id("accept-policy-readiness", readiness.id())));
            if (accepted.disposition() == CommandResult.Disposition.SUPERSEDED) {
                throw new IllegalStateException(
                        "Current policy readiness became stale inside its Task command");
            }
            changed = true;
        }
        else if ("READY_TO_MERGE".equals(context.checkpoint())
                && !readiness.ready()) {
            remote.reconsiderReadinessPolicyInCommand(
                    new RemoteDevelopmentStageManager.PolicyReadinessCommand(
                            stageCommand(
                                    context,
                                    id("reconsider-policy-readiness", readiness.id())),
                            readiness.id(), readiness.automationPolicyId(),
                            readiness.headSha(), readiness.baseSha()));
            return true;
        }

        if (readiness.ready() && policy.autoMerge()
                && !policy.stewardshipException()) {
            String subject = readiness.id();
            merges.startInCommand(new RemoteMergeRuntimeCoordinator.Command(
                    id("auto-merge-command", subject), "auto-merge-policy",
                    taskId, context.stageId(), readiness.id(),
                    id("auto-merge-authorization", subject),
                    id("auto-merge-operation", subject),
                    id("auto-merge-ticket", subject),
                    AuthorityKind.AUTO_MERGE_POLICY, "squash", 3));
            return true;
        }
        return changed;
    }

    private static RemoteDevelopmentStageManager.RemoteGateCommand gate(
            RemoteContext context, ReadinessEvidence readiness, String commandId)
    {
        return new RemoteDevelopmentStageManager.RemoteGateCommand(
                stageCommand(context, commandId), readiness.id(),
                readiness.headSha(), readiness.baseSha());
    }

    private static StageManager.Command stageCommand(
            RemoteContext context, String commandId)
    {
        return new StageManager.Command(
                commandId, ACTOR, context.taskId(), context.taskEpoch(),
                context.stageId(), context.stageGeneration(),
                context.stageVersion());
    }

    private static String id(String namespace, String value)
    {
        return UUID.nameUUIDFromBytes(
                (namespace + "\u001f" + value).getBytes(StandardCharsets.UTF_8))
                .toString();
    }
}
