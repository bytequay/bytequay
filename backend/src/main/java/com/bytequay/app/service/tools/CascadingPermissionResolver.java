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
import com.bytequay.app.repository.PermissionGrantStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.google.common.collect.ImmutableSet;
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
public class CascadingPermissionResolver
        implements PermissionResolver
{
    private static final String DENY = "deny";

    /** Upper bound on RUNNING turns scanned to find the one a given agent
     *  key connects under. A thread can have at most the CLI lane cap of
     *  concurrent agents (4) plus the API lane; this leaves generous head
     *  room while bounding the read. */
    private static final int RUNNING_TURN_SCAN_LIMIT = 32;

    private final ThreadStore threadStore;
    private final ThreadTurnStore turnStore;
    private final PermissionGrantStore grantStore;

    public CascadingPermissionResolver(
            ThreadStore threadStore,
            ThreadTurnStore turnStore,
            PermissionGrantStore grantStore)
    {
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.turnStore = requireNonNull(turnStore, "turnStore is null");
        this.grantStore = requireNonNull(grantStore, "grantStore is null");
    }

    @Override
    public AgentRole roleFor(String threadId, String agentKey)
    {
        return roleForTurn(runningTurn(threadId, agentKey));
    }

    @Override
    public RunningScope runningScope(String threadId, String agentKey)
    {
        return runningTurn(threadId, agentKey)
                .map(turn -> new RunningScope(turn.taskId(), turn.stageId(), turn.agentRunId()))
                .orElse(RunningScope.NONE);
    }

    @Override
    public Set<SecurityType> grants(String threadId, String agentKey)
    {
        Optional<ThreadTurn> turn = runningTurn(threadId, agentKey);
        Set<SecurityType> effective = EnumSet.noneOf(SecurityType.class);
        effective.addAll(RoleCapabilities.forRole(roleForTurn(turn)));

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
        turn.map(ThreadTurn::taskId)
                .filter(id -> id != null && !id.isBlank())
                .ifPresent(taskId -> applyDenials(effective, grantStore.findForScope("task", taskId)));

        return ImmutableSet.copyOf(effective);
    }

    /**
     * The in-flight turn whose stamped scope + task id are the authoritative
     * role and task target for this resolution.
     *
     * <p>When {@code agentKey} is null the resolver returns the thread's
     * first RUNNING turn — the legacy single-agent behaviour, unchanged.
     * When {@code agentKey} is given (concurrent stage agents on one thread),
     * the RUNNING turns are filtered to the one this agent connects under:
     * {@link PermissionResolver#TRUNK_AGENT_KEY} selects the trunk turn
     * (task_id null), any other key selects the turn whose registry stage
     * key ({@code stage_id}, else {@code task_id}) equals it. Filtering is
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
            return running.stream().findFirst();
        }
        return running.stream()
                .filter(turn -> agentKey.equals(agentKeyOf(turn)))
                .findFirst();
    }

    /** The registry stage key a running turn connects under — the same
     *  derivation the scheduler's run gate and the MCP URL use: stage id,
     *  else task id, else the reserved trunk key for a task-less turn. */
    private static String agentKeyOf(ThreadTurn turn)
    {
        return PermissionResolver.agentKeyFor(turn.taskId(), turn.stageId());
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
