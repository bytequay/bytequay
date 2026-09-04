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
package com.bytequay.app.ci.e2e;

import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.spi.AgentAttachment;
import com.bytequay.agent.spi.AgentCapabilities;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentEventSink;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentSession;
import com.bytequay.agent.spi.AgentSessionContext;
import com.bytequay.agent.spi.AgentSessionRef;
import com.bytequay.agent.spi.AgentTurnCommand;
import com.bytequay.agent.spi.AgentTurnResult;
import com.bytequay.agent.spi.CostProvenance;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.agent.spi.ProviderUsageSample;
import com.bytequay.agent.spi.RunningTurn;
import com.bytequay.app.ci.CiMemoryService;
import com.bytequay.app.ci.CiMemoryView;
import com.bytequay.app.ci.ReadCiMemoryCommand;
import com.bytequay.core.domain.PullRequestRef;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static com.google.common.base.Ascii.truncate;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

final class ScriptedAgentDriver
        implements AgentDriver
{
    static final AgentLaneId LANE = new AgentLaneId("ci-e2e");

    private final Map<String, AgentScript> scripts = new HashMap<>();
    private final List<TurnCall> turns = new ArrayList<>();
    private final List<MemoryRead> memoryReads = new ArrayList<>();
    private final List<GitAction> gitActions = new ArrayList<>();
    private final List<TurnCall> cancellations = new ArrayList<>();
    private final CiMemoryService memory;

    ScriptedAgentDriver(CiMemoryService memory)
    {
        this.memory = requireNonNull(memory, "memory is null");
    }

    synchronized void reset()
    {
        scripts.clear();
        turns.clear();
        memoryReads.clear();
        gitActions.clear();
        cancellations.clear();
    }

    synchronized void bind(PullRequestRef pullRequest)
    {
        requireNonNull(pullRequest, "pullRequest is null");
        scripts.put(remoteUrl(pullRequest), new AgentScript(pullRequest, new ArrayDeque<>()));
    }

    synchronized void enqueueBlocked(PullRequestRef pullRequest)
    {
        AgentScript script = requireScript(pullRequest);
        script.actions().add(AgentAction.blockedAction());
    }

    synchronized void enqueueCompleted(
            PullRequestRef pullRequest,
            String untrustedFinalResponse,
            WorktreeAction action)
    {
        AgentScript script = requireScript(pullRequest);
        script.actions().add(AgentAction.completed(
                untrustedFinalResponse,
                action,
                "worktree-action",
                Optional.empty(),
                Optional.empty()));
    }

    synchronized void enqueueCompleted(
            PullRequestRef pullRequest,
            String untrustedFinalResponse,
            WorktreeAction action,
            String actionName,
            Optional<String> memoryQuery,
            Optional<String> changedPath)
    {
        AgentScript script = requireScript(pullRequest);
        script.actions().add(AgentAction.completed(
                untrustedFinalResponse,
                action,
                actionName,
                memoryQuery,
                changedPath));
    }

    synchronized void enqueueFailed(
            PullRequestRef pullRequest,
            AgentTurnResult.State state,
            String reason)
    {
        AgentScript script = requireScript(pullRequest);
        script.actions().add(AgentAction.failed(state, reason));
    }

    synchronized List<TurnCall> turns()
    {
        return ImmutableList.copyOf(turns);
    }

    synchronized List<TurnCall> cancellations()
    {
        return ImmutableList.copyOf(cancellations);
    }

    synchronized List<MemoryRead> memoryReads()
    {
        return ImmutableList.copyOf(memoryReads);
    }

    synchronized List<GitAction> gitActions()
    {
        return ImmutableList.copyOf(gitActions);
    }

    @Override
    public AgentLaneId id()
    {
        return LANE;
    }

    @Override
    public AgentCapabilities capabilities()
    {
        return new AgentCapabilities(
                true,
                false,
                false,
                true,
                false,
                false,
                false,
                false,
                true,
                false,
                CostProvenance.UNAVAILABLE,
                ImmutableSet.of(AgentPermissionPolicy.CI_AUTOFIX),
                ImmutableSet.of("text/plain"));
    }

    @Override
    public AgentProviderStatus probe()
    {
        return new AgentProviderStatus(
                LANE,
                AgentProviderStatus.Readiness.READY,
                Optional.of("test"),
                "ready",
                capabilities());
    }

    @Override
    public AgentSession open(AgentSessionContext context, AgentEventSink events)
    {
        requireNonNull(context, "context is null");
        requireNonNull(events, "events is null");
        Path canonical = context.worktree().toAbsolutePath().normalize();
        AgentScript script;
        synchronized (this) {
            script = scripts.get(E2eGitRepository.git(canonical, "config", "--get", "remote.origin.url"));
        }
        checkState(script != null, "No agent script bound to worktree " + canonical);
        AgentSessionRef reference = context.sessionReference().orElseGet(() -> new AgentSessionRef(
                LANE,
                1,
                "ci-e2e-" + context.taskId()));
        return new ScriptedSession(context, events, script, reference);
    }

    private synchronized StartedAction takeAction(
            AgentScript script,
            Path worktree,
            AgentTurnCommand command)
    {
        checkState(!script.actions().isEmpty(), "No agent turn scripted for " + script.pullRequest().fullName());
        AgentAction action = script.actions().removeFirst();
        TurnCall call = new TurnCall(
                script.pullRequest(),
                worktree,
                command.turnId(),
                truncate(command.message(), 4096, "…"),
                command.attachments());
        turns.add(call);
        return new StartedAction(action, call);
    }

    private synchronized void recordCancellation(TurnCall call)
    {
        cancellations.add(call);
    }

    private synchronized void recordMemoryRead(MemoryRead read)
    {
        memoryReads.add(read);
    }

    private synchronized void recordGitAction(GitAction action)
    {
        gitActions.add(action);
    }

    private AgentScript requireScript(PullRequestRef pullRequest)
    {
        requireNonNull(pullRequest, "pullRequest is null");
        AgentScript script = scripts.get(remoteUrl(pullRequest));
        checkState(script != null && script.pullRequest().equals(pullRequest),
                "No agent script bound for " + pullRequest.fullName());
        return script;
    }

    private static String remoteUrl(PullRequestRef pullRequest)
    {
        return "https://github.com/" + pullRequest.repoFullName() + ".git";
    }

    record TurnCall(
            PullRequestRef pullRequest,
            Path worktree,
            String turnId,
            String message,
            List<AgentAttachment> attachments)
    {
        TurnCall
        {
            attachments = ImmutableList.copyOf(attachments);
        }
    }

    record MemoryRead(PullRequestRef pullRequest, String taskId, Optional<String> query, CiMemoryView result) {}

    record GitAction(PullRequestRef pullRequest, Path worktree, String name) {}

    private record StartedAction(AgentAction action, TurnCall call) {}

    @FunctionalInterface
    interface WorktreeAction
    {
        void run(Path worktree);
    }

    private final class ScriptedSession
            implements AgentSession
    {
        private final AgentSessionContext context;
        private final AgentEventSink events;
        private final AgentScript script;
        private final AgentSessionRef reference;

        private ScriptedSession(
                AgentSessionContext context,
                AgentEventSink events,
                AgentScript script,
                AgentSessionRef reference)
        {
            this.context = context;
            this.events = events;
            this.script = script;
            this.reference = reference;
        }

        @Override
        public AgentSessionRef reference()
        {
            return reference;
        }

        @Override
        public RunningTurn start(AgentTurnCommand command)
        {
            StartedAction started = takeAction(script, context.worktree(), command);
            AgentAction action = started.action();
            TurnCall call = started.call();
            events.emit(new ProviderAgentEvent.Lifecycle(
                    Optional.of("started-" + command.turnId()),
                    Optional.empty(),
                    ProviderAgentEvent.LifecyclePhase.TURN_STARTED,
                    "Scripted agent turn started"));
            action.memoryQuery().ifPresent(query -> {
                CiMemoryView result = memory.read(new ReadCiMemoryCommand(
                        TaskId.valueOf(context.taskId()),
                        Optional.of(query)));
                recordMemoryRead(new MemoryRead(script.pullRequest(), context.taskId(), Optional.of(query), result));
            });
            action.action().run(context.worktree());
            if (!action.waitsForCancellation()) {
                recordGitAction(new GitAction(script.pullRequest(), context.worktree(), action.actionName()));
                events.emit(new ProviderAgentEvent.Command(
                        Optional.of("command-" + command.turnId()),
                        Optional.empty(),
                        "command-" + command.turnId(),
                        action.result().state() == AgentTurnResult.State.COMPLETED
                                ? ProviderAgentEvent.ActivityState.COMPLETED
                                : ProviderAgentEvent.ActivityState.FAILED,
                        ImmutableList.of("ci-e2e", action.actionName()),
                        Optional.empty(),
                        Optional.of(action.result().state() == AgentTurnResult.State.COMPLETED ? 0 : 1),
                        false));
                action.changedPath().ifPresent(path -> events.emit(new ProviderAgentEvent.FileChange(
                        Optional.of("file-" + command.turnId()),
                        Optional.empty(),
                        path,
                        ProviderAgentEvent.ChangeKind.MODIFIED,
                        Optional.empty())));
                events.emit(new ProviderAgentEvent.Usage(new ProviderUsageSample(
                        Optional.of("usage-" + command.turnId()),
                        ProviderUsageSample.Semantics.FINAL_FOR_INVOCATION,
                        1,
                        0,
                        1,
                        Optional.empty(),
                        CostProvenance.UNAVAILABLE,
                        Instant.EPOCH)));
            }
            if (action.untrustedFinalResponse().isPresent()) {
                events.emit(new ProviderAgentEvent.Message(
                        Optional.of("final-" + command.turnId()),
                        Optional.empty(),
                        ProviderAgentEvent.MessagePhase.FINAL,
                        action.untrustedFinalResponse().orElseThrow(),
                        Optional.empty()));
            }
            if (!action.waitsForCancellation()) {
                events.emit(new ProviderAgentEvent.Lifecycle(
                        Optional.of("finished-" + command.turnId()),
                        Optional.empty(),
                        lifecycle(action.result().state()),
                        action.result().state().name()));
            }
            CompletableFuture<AgentTurnResult> completion = action.waitsForCancellation()
                    ? new CompletableFuture<>()
                    : CompletableFuture.completedFuture(action.result());
            return new ScriptedRunningTurn(call, completion);
        }

        @Override
        public void close() {}
    }

    private final class ScriptedRunningTurn
            implements RunningTurn
    {
        private final TurnCall call;
        private final CompletableFuture<AgentTurnResult> completion;

        private ScriptedRunningTurn(TurnCall call, CompletableFuture<AgentTurnResult> completion)
        {
            this.call = call;
            this.completion = completion;
        }

        @Override
        public ProviderTurnRef reference()
        {
            return new ProviderTurnRef("ci-e2e-" + call.turnId());
        }

        @Override
        public CompletionStage<AgentTurnResult> completion()
        {
            return completion;
        }

        @Override
        public void cancel()
        {
            recordCancellation(call);
            completion.complete(new AgentTurnResult(
                    AgentTurnResult.State.CANCELLED,
                    Optional.empty(),
                    Optional.of("cancelled by test"),
                    Duration.ZERO));
        }
    }

    private record AgentScript(PullRequestRef pullRequest, ArrayDeque<AgentAction> actions) {}

    private record AgentAction(
            Optional<String> untrustedFinalResponse,
            WorktreeAction action,
            AgentTurnResult result,
            boolean waitsForCancellation,
            String actionName,
            Optional<String> memoryQuery,
            Optional<String> changedPath)
    {
        private AgentAction
        {
            requireNonNull(untrustedFinalResponse, "untrustedFinalResponse is null");
            requireNonNull(action, "action is null");
            requireNonNull(result, "result is null");
            requireNonNull(actionName, "actionName is null");
            requireNonNull(memoryQuery, "memoryQuery is null");
            requireNonNull(changedPath, "changedPath is null");
        }

        private static AgentAction blockedAction()
        {
            return new AgentAction(
                    Optional.empty(),
                    ignored -> {},
                    new AgentTurnResult(
                            AgentTurnResult.State.CANCELLED,
                            Optional.empty(),
                            Optional.of("cancelled by test"),
                            Duration.ZERO),
                    true,
                    "blocked",
                    Optional.empty(),
                    Optional.empty());
        }

        private static AgentAction completed(
                String untrustedFinalResponse,
                WorktreeAction action,
                String actionName,
                Optional<String> memoryQuery,
                Optional<String> changedPath)
        {
            return new AgentAction(
                    Optional.of(untrustedFinalResponse),
                    action,
                    new AgentTurnResult(
                            AgentTurnResult.State.COMPLETED,
                            Optional.of(untrustedFinalResponse),
                            Optional.empty(),
                            Duration.ZERO),
                    false,
                    actionName,
                    memoryQuery,
                    changedPath);
        }

        private static AgentAction failed(AgentTurnResult.State state, String reason)
        {
            checkArgument(state == AgentTurnResult.State.FAILED || state == AgentTurnResult.State.INTERRUPTED, "Failed action state must be FAILED or INTERRUPTED");
            return new AgentAction(
                    Optional.empty(),
                    ignored -> {},
                    new AgentTurnResult(state, Optional.empty(), Optional.of(reason), Duration.ZERO),
                    false,
                    state.name().toLowerCase(Locale.ROOT),
                    Optional.empty(),
                    Optional.empty());
        }
    }

    private static ProviderAgentEvent.LifecyclePhase lifecycle(AgentTurnResult.State state)
    {
        return state == AgentTurnResult.State.COMPLETED
                ? ProviderAgentEvent.LifecyclePhase.TURN_COMPLETED
                : ProviderAgentEvent.LifecyclePhase.INTERRUPTED;
    }
}
