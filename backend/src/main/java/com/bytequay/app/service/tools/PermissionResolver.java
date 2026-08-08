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
package com.bytequay.app.service.tools;

import com.bytequay.app.domain.PermissionGrant;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.repository.sqlite.PermissionGrantStore;
import com.bytequay.app.service.agents.ActiveAgentContextRegistry;
import com.bytequay.app.service.agents.ResolvedAgentContext;
import com.google.common.collect.ImmutableSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Resolves a caller's capabilities by tightening the role's base set
 * with deny grants walked across the cascade global → workspace →
 * thread → task.
 *
 * <p>Tighten-only: the resolved set starts as
 * {@link RoleCapabilities#forRole} and a {@code deny} grant at any
 * level removes that capability. {@code allow} / {@code inherit}
 * grants are recorded for intent but never widen the set — a child
 * scope can subtract from its parent, never re-add. So once a
 * capability is denied at, say, the workspace, no thread- or
 * task-level grant can bring it back.
 *
 * <p>Role derivation reads the <em>scope the running turn was created
 * with</em> ({@link ThreadScope}, stamped at enqueue) rather than
 * re-deriving it from the thread's task projection: a trunk turn (no
 * task) is {@link AgentRole#TRUNK}; a task- or stage-scoped turn is
 * {@link AgentRole#TASK}. The same running turn supplies the task
 * target for task-scoped deny grants. With no turn in flight the
 * read-only trunk role is the safe default. The {@code roles} filter on
 * each tool governs discovery; this resolver governs capability.
 */
@Component
public class PermissionResolver
{
    public static final String TRUNK_AGENT_KEY = "trunk";

    public static String agentKeyFor(ThreadScope scope, String taskId)
    {
        requireNonNull(scope, "scope is null");
        return switch (scope) {
            case TRUNK -> {
                if (taskId != null && !taskId.isBlank()) {
                    throw new IllegalArgumentException("TRUNK scope forbids taskId");
                }
                yield TRUNK_AGENT_KEY;
            }
            case TASK, STAGE -> {
                if (taskId == null || taskId.isBlank()) {
                    throw new IllegalArgumentException(scope + " scope requires taskId");
                }
                yield taskId;
            }
        };
    }

    public record RunningScope(ThreadScope scope, String taskId, String stageId, String agentRunId)
    {
        public static final RunningScope NONE = new RunningScope(null, null, null, null);
    }

    private static final String DENY = "deny";

    /** Upper bound on RUNNING turns scanned to find the one a given agent
     *  key connects under. A thread can have at most the CLI lane cap of
     *  concurrent agents (4) plus the API lane; this leaves generous head
     *  room while bounding the read. */
    private static final int RUNNING_TURN_SCAN_LIMIT = 32;

    private final ThreadStore threadStore;
    private final ThreadTurnStore turnStore;
    private final PermissionGrantStore grantStore;
    private final ActiveAgentContextRegistry activeContexts;

    @Autowired
    public PermissionResolver(
            ThreadStore threadStore,
            ThreadTurnStore turnStore,
            PermissionGrantStore grantStore,
            ActiveAgentContextRegistry activeContexts)
    {
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.turnStore = requireNonNull(turnStore, "turnStore is null");
        this.grantStore = requireNonNull(grantStore, "grantStore is null");
        this.activeContexts = requireNonNull(activeContexts, "activeContexts is null");
    }

    /** Compatibility constructor for focused legacy permission tests. */
    public PermissionResolver(
            ThreadStore threadStore,
            ThreadTurnStore turnStore,
            PermissionGrantStore grantStore)
    {
        this(threadStore, turnStore, grantStore, new ActiveAgentContextRegistry());
    }

    public AgentRole roleFor(String threadId, String agentKey)
    {
        return activeContexts.find(threadId, agentKey)
                .map(ResolvedAgentContext::permissionRole)
                .orElseGet(() -> roleForTurn(runningTurn(threadId, agentKey)));
    }

    public RunningScope runningScope(String threadId, String agentKey)
    {
        return activeContexts.findScope(threadId, agentKey)
                .orElseGet(() -> runningTurn(threadId, agentKey)
                .map(turn -> new RunningScope(
                        turn.scope(), turn.taskId(), turn.stageId(), turn.agentRunId()))
                .orElse(RunningScope.NONE));
    }

    public Set<SecurityType> grants(String threadId, String agentKey)
    {
        Optional<ResolvedAgentContext> active = activeContexts.find(threadId, agentKey);
        Optional<ThreadTurn> turn = runningTurn(threadId, agentKey);
        Set<SecurityType> effective = EnumSet.noneOf(SecurityType.class);
        effective.addAll(active.map(ResolvedAgentContext::capabilities)
                .orElseGet(() -> RoleCapabilities.forRole(roleForTurn(turn))));

        // Global first, then narrower scopes. Each deny subtracts and
        // stays subtracted — walking order doesn't matter for a pure
        // remove, but it keeps the intent (broad → specific) legible.
        applyDenials(effective, grantStore.findGlobal());

        Optional<Thread> thread = threadId == null || threadId.isBlank()
                ? Optional.empty()
                : threadStore.findThreadById(threadId);
        thread.map(Thread::workspaceId)
                .filter(ws -> ws != null && !ws.isBlank())
                .ifPresent(ws -> applyDenials(effective, grantStore.findForScope("workspace", ws)));

        if (threadId != null && !threadId.isBlank()) {
            applyDenials(effective, grantStore.findForScope("thread", threadId));
        }

        // Task-scoped denies target the task the running turn belongs to —
        // the same stamped fact that drives the role.
        Optional<String> taskId = activeContexts.findScope(threadId, agentKey)
                .filter(scope -> scope.scope() != ThreadScope.TRUNK)
                .map(RunningScope::taskId)
                .or(() -> turn.filter(value -> value.scope() != ThreadScope.TRUNK)
                        .map(ThreadTurn::requireTaskId));
        taskId.ifPresent(id ->
                applyDenials(effective, grantStore.findForScope("task", id)));

        return ImmutableSet.copyOf(effective);
    }

    /**
     * The in-flight turn whose stamped scope + task id are the authoritative
     * role and task target for this resolution.
     *
     * <p>The explicit {@code agentKey} selects one Task/trunk runtime.
     * RUNNING turns are filtered to the one this agent connects under:
     * {@link PermissionResolver#TRUNK_AGENT_KEY} selects the trunk turn;
     * any other key selects the turn owned by that Task. Filtering is
     * done in memory over the thread's RUNNING turns — at most a handful
     * with the CLI lane cap of 4.
     */
    private Optional<ThreadTurn> runningTurn(String threadId, String agentKey)
    {
        if (threadId == null || threadId.isBlank()) {
            return Optional.empty();
        }
        List<ThreadTurn> running = turnStore.listTurnsByTaskIdAndStatus(
                threadId, ThreadTurnStatus.RUNNING, RUNNING_TURN_SCAN_LIMIT);
        if (agentKey == null || agentKey.isBlank()) {
            throw new IllegalArgumentException("agentKey is required");
        }
        return running.stream()
                .filter(turn -> agentKey.equals(agentKeyOf(turn)))
                .findFirst();
    }

    /** The task-owned runtime key a running turn connects under. */
    private static String agentKeyOf(ThreadTurn turn)
    {
        return PermissionResolver.agentKeyFor(turn.scope(), turn.taskId());
    }

    /** TRUNK for a trunk-scoped (planning) turn, TASK for task- or
     *  stage-scoped work, and the read-only trunk role when nothing is
     *  running — never escalate on the absence of a known scope. */
    private static AgentRole roleForTurn(Optional<ThreadTurn> turn)
    {
        return turn.map(ThreadTurn::scope)
                .map(scope -> scope == ThreadScope.TRUNK ? AgentRole.TRUNK : AgentRole.TASK)
                .orElse(AgentRole.TRUNK);
    }

    /** Remove every capability a deny grant names. Unknown capability
     *  strings (a SecurityType that's been renamed / removed) are
     *  skipped rather than failing the whole resolution. */
    private static void applyDenials(Set<SecurityType> effective, List<PermissionGrant> grants)
    {
        for (PermissionGrant grant : grants) {
            if (!DENY.equals(grant.mode())) {
                continue;
            }
            parseCapability(grant.capability()).ifPresent(effective::remove);
        }
    }

    private static Optional<SecurityType> parseCapability(String capability)
    {
        try {
            return Optional.of(SecurityType.valueOf(capability));
        }
        catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }
}
