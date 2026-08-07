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
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadResourceLane;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.repository.sqlite.PermissionGrantStore;
import com.bytequay.app.service.agents.ActiveAgentContextRegistry;
import com.bytequay.app.service.agents.ResolvedAgentContext;
import com.bytequay.app.service.skills.ByteQuayRole;
import com.bytequay.app.service.skills.RoleDefinition;
import com.bytequay.app.service.skills.RoleRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The role + task target come from the scope the running turn was created
 * with (trunk turn → TRUNK, task/stage turn → TASK), not from a re-derived
 * thread task projection; the cascade then tightens with deny grants.
 */
class TestCascadingPermissionResolver
{
    private final ThreadStore threadStore = mock(ThreadStore.class);
    private final ThreadTurnStore turnStore = mock(ThreadTurnStore.class);
    private final PermissionGrantStore grantStore = mock(PermissionGrantStore.class);

    private final CascadingPermissionResolver resolver =
            new CascadingPermissionResolver(threadStore, turnStore, grantStore);

    @Test
    void trunkTurnResolvesToTrunkBaseWhenNoGrants()
    {
        String threadId = "t-trunk";
        runningTurn(threadId, trunkTurn(threadId));
        when(threadStore.findThreadById(threadId)).thenReturn(Optional.of(thread(threadId, "ws-1")));
        noGrants();

        assertThat(resolver.roleFor(threadId, PermissionResolver.TRUNK_AGENT_KEY))
                .isEqualTo(AgentRole.TRUNK);
        assertThat(resolver.grants(threadId, PermissionResolver.TRUNK_AGENT_KEY))
                .isEqualTo(RoleCapabilities.forRole(AgentRole.TRUNK))
                .contains(SecurityType.TASK_MANAGE, SecurityType.CODE_READ)
                .doesNotContain(SecurityType.GIT_PUSH);
    }

    @Test
    void taskTurnResolvesToTaskBaseWhenNoGrants()
    {
        String threadId = "t-task";
        runningTurn(threadId, taskTurn(threadId, "task-1"));
        when(threadStore.findThreadById(threadId)).thenReturn(Optional.of(thread(threadId, "ws-1")));
        noGrants();

        assertThat(resolver.roleFor(threadId, "task-1")).isEqualTo(AgentRole.TASK);
        assertThat(resolver.grants(threadId, "task-1"))
                .contains(SecurityType.GIT_PUSH, SecurityType.CODE_WRITE, SecurityType.VCS_PUBLISH);
    }

    /**
     * No turn in flight (e.g. the chain ran dry and the trunk hasn't started
     * planning yet): the read-only trunk role is the safe default rather than
     * an escalation.
     */
    @Test
    void noRunningTurnResolvesToTrunk()
    {
        String threadId = "t-idle";
        runningTurn(threadId, null);
        when(threadStore.findThreadById(threadId)).thenReturn(Optional.of(thread(threadId, "ws-1")));
        noGrants();

        assertThat(resolver.roleFor(threadId, PermissionResolver.TRUNK_AGENT_KEY))
                .isEqualTo(AgentRole.TRUNK);
        assertThat(resolver.grants(threadId, PermissionResolver.TRUNK_AGENT_KEY))
                .isEqualTo(RoleCapabilities.forRole(AgentRole.TRUNK))
                .doesNotContain(SecurityType.GIT_PUSH);
    }

    @Test
    void workspaceDenyRemovesCapabilityFromTaskTurn()
    {
        String threadId = "t-task";
        runningTurn(threadId, taskTurn(threadId, "task-1"));
        when(threadStore.findThreadById(threadId)).thenReturn(Optional.of(thread(threadId, "ws-locked")));
        when(grantStore.findGlobal()).thenReturn(List.of());
        when(grantStore.findForScope("workspace", "ws-locked"))
                .thenReturn(List.of(deny("workspace", "ws-locked", SecurityType.GIT_PUSH)));
        when(grantStore.findForScope(eq("thread"), anyString())).thenReturn(List.of());
        when(grantStore.findForScope(eq("task"), anyString())).thenReturn(List.of());

        assertThat(resolver.grants(threadId, "task-1"))
                .doesNotContain(SecurityType.GIT_PUSH)
                .contains(SecurityType.CODE_WRITE);
    }

    @Test
    void taskLevelAllowCannotReAddAWorkspaceDeniedCapability()
    {
        // Tighten-only: a deny at the workspace stays denied even when a deeper
        // task scope explicitly allows it. The task scope is the running turn's
        // task id.
        String threadId = "t-task";
        runningTurn(threadId, taskTurn(threadId, "task-1"));
        when(threadStore.findThreadById(threadId)).thenReturn(Optional.of(thread(threadId, "ws-locked")));
        when(grantStore.findGlobal()).thenReturn(List.of());
        when(grantStore.findForScope("workspace", "ws-locked"))
                .thenReturn(List.of(deny("workspace", "ws-locked", SecurityType.VCS_PUBLISH)));
        when(grantStore.findForScope(eq("thread"), anyString())).thenReturn(List.of());
        when(grantStore.findForScope("task", "task-1"))
                .thenReturn(List.of(grant("task", "task-1", SecurityType.VCS_PUBLISH, "allow")));

        assertThat(resolver.grants(threadId, "task-1"))
                .doesNotContain(SecurityType.VCS_PUBLISH);
    }

    @Test
    void globalDenyAppliesToEveryThread()
    {
        String threadId = "t-trunk";
        runningTurn(threadId, trunkTurn(threadId));
        when(threadStore.findThreadById(threadId)).thenReturn(Optional.of(thread(threadId, "ws-1")));
        when(grantStore.findGlobal())
                .thenReturn(List.of(deny("global", null, SecurityType.TASK_MANAGE)));
        when(grantStore.findForScope(anyString(), anyString())).thenReturn(List.of());

        assertThat(resolver.grants(threadId, PermissionResolver.TRUNK_AGENT_KEY))
                .doesNotContain(SecurityType.TASK_MANAGE)
                .contains(SecurityType.CODE_READ);
    }

    @Test
    void unknownCapabilityInAGrantIsIgnored()
    {
        String threadId = "t-trunk";
        runningTurn(threadId, trunkTurn(threadId));
        when(threadStore.findThreadById(threadId)).thenReturn(Optional.of(thread(threadId, "ws-1")));
        when(grantStore.findGlobal()).thenReturn(List.of(new PermissionGrant(
                1L, "global", null, "DECOMMISSIONED_CAP", "deny", null,
                Instant.now(), Instant.now())));
        when(grantStore.findForScope(anyString(), anyString())).thenReturn(List.of());

        // The stray capability name is skipped — the base set is intact.
        assertThat(resolver.grants(threadId, PermissionResolver.TRUNK_AGENT_KEY))
                .isEqualTo(RoleCapabilities.forRole(AgentRole.TRUNK));
    }

    /**
     * Two task agents running concurrently on one thread: the resolver must
     * read EACH agent's own running turn by its task runtime key, not the
     * thread's first running turn. Without the agent-key filter both calls
     * would collapse onto turn-A's task.
     */
    @Test
    void concurrentTaskAgentsResolveTheirOwnRunningTurnByAgentKey()
    {
        String threadId = "t-multi";
        ThreadTurn turnA = turnWithId("turn-a", threadId, "task-a", null);
        ThreadTurn turnB = turnWithId("turn-b", threadId, "task-b", null);
        when(turnStore.listTurnsByTaskIdAndStatus(eq(threadId), eq(ThreadTurnStatus.RUNNING), anyInt()))
                .thenReturn(List.of(turnA, turnB));

        // Each task agent keys by its task id (no stage); the resolver picks
        // the matching turn so its scope is its own task.
        assertThat(resolver.runningScope(threadId, "task-a").taskId()).isEqualTo("task-a");
        assertThat(resolver.runningScope(threadId, "task-b").taskId()).isEqualTo("task-b");
    }

    /**
     * A stage-scoped turn still addresses its Task-owned agent; the reserved
     * trunk key selects the task-less turn. Both can be in flight at once.
     */
    @Test
    void stageAndTrunkTurnsResolveByTaskIdAndTrunkKey()
    {
        String threadId = "t-mix";
        ThreadTurn stage = turnWithId("turn-s", threadId, "task-x", "stage-7");
        ThreadTurn trunk = turnWithId("turn-t", threadId, null, null);
        when(turnStore.listTurnsByTaskIdAndStatus(eq(threadId), eq(ThreadTurnStatus.RUNNING), anyInt()))
                .thenReturn(List.of(stage, trunk));
        when(threadStore.findThreadById(threadId)).thenReturn(Optional.of(thread(threadId, "ws-1")));
        noGrants();

        assertThat(resolver.runningScope(threadId, "task-x").stageId()).isEqualTo("stage-7");
        assertThat(resolver.roleFor(threadId, "task-x")).isEqualTo(AgentRole.TASK);
        assertThat(resolver.roleFor(threadId, PermissionResolver.TRUNK_AGENT_KEY))
                .isEqualTo(AgentRole.TRUNK);
        assertThat(resolver.runningScope(threadId, PermissionResolver.TRUNK_AGENT_KEY).taskId())
                .isNull();
    }

    @Test
    void typedTurnContextWinsWithoutARegisteredLegacyTurn()
    {
        String threadId = "v2-trunk";
        String agentKey = "v2-stage-turn:turn-1:operation-1";
        ActiveAgentContextRegistry active = new ActiveAgentContextRegistry();
        RoleDefinition role = RoleRegistry.definition(ByteQuayRole.TASK);
        active.put(
                threadId,
                agentKey,
                new ResolvedAgentContext(
                        role.role(), role.version(), role.permissionRole(), null,
                        role.capabilities(), List.of(), List.of(),
                        role.resources(), Set.of("approval_prompt")),
                new PermissionResolver.RunningScope(
                        ThreadScope.STAGE, "task-v2", "stage-v2", "turn-1"));
        CascadingPermissionResolver typed = new CascadingPermissionResolver(
                threadStore, turnStore, grantStore, active);
        runningTurn(threadId, null);
        when(threadStore.findThreadById(threadId))
                .thenReturn(Optional.of(thread(threadId, "ws-v2")));
        noGrants();

        assertThat(typed.roleFor(threadId, agentKey)).isEqualTo(AgentRole.TASK);
        assertThat(typed.runningScope(threadId, agentKey))
                .isEqualTo(new PermissionResolver.RunningScope(
                        ThreadScope.STAGE, "task-v2", "stage-v2", "turn-1"));
        assertThat(typed.grants(threadId, agentKey))
                .isEqualTo(role.capabilities());
    }

    private void runningTurn(String threadId, ThreadTurn turn)
    {
        when(turnStore.listTurnsByTaskIdAndStatus(eq(threadId), eq(ThreadTurnStatus.RUNNING), anyInt()))
                .thenReturn(turn == null ? List.of() : List.of(turn));
    }

    private void noGrants()
    {
        when(grantStore.findGlobal()).thenReturn(List.of());
        when(grantStore.findForScope(anyString(), any())).thenReturn(List.of());
    }

    private static PermissionGrant deny(String scopeKind, String scopeId, SecurityType cap)
    {
        return grant(scopeKind, scopeId, cap, "deny");
    }

    private static PermissionGrant grant(String scopeKind, String scopeId, SecurityType cap, String mode)
    {
        return new PermissionGrant(
                1L, scopeKind, scopeId, cap.name(), mode, null,
                Instant.parse("2026-05-28T00:00:00Z"),
                Instant.parse("2026-05-28T00:00:00Z"));
    }

    private static Thread thread(String id, String workspaceId)
    {
        Instant now = Instant.parse("2026-05-28T00:00:00Z");
        return new Thread(
                id, ThreadKind.CLI_AGENT, "claude-code", null, "fixture",
                ThreadStatus.IDLE, "test", 0L, 0L, 0L, now, now, null, null,
                ThreadFlow.BUILD, workspaceId, null, null);
    }

    /** A trunk-scoped running turn (no task). */
    private static ThreadTurn trunkTurn(String threadId)
    {
        return turn(threadId, null);
    }

    /** A task-scoped running turn. */
    private static ThreadTurn taskTurn(String threadId, String taskId)
    {
        return turn(threadId, taskId);
    }

    private static ThreadTurn turn(String threadId, String taskId)
    {
        Instant now = Instant.parse("2026-05-28T00:00:00Z");
        return new ThreadTurn(
                "turn-1", threadId, taskId, ThreadResourceLane.CLI, ThreadTurnStatus.RUNNING,
                "input", now, now, now, null, null, TurnInitiator.user(),
                null, taskId == null ? ThreadScope.TRUNK : ThreadScope.TASK);
    }

    /** A RUNNING turn with explicit id + stamped task/stage scope, used to
     *  seed several concurrent turns on one thread. */
    private static ThreadTurn turnWithId(String id, String threadId, String taskId, String stageId)
    {
        Instant now = Instant.parse("2026-05-28T00:00:00Z");
        return new ThreadTurn(
                id, threadId, taskId, ThreadResourceLane.CLI, ThreadTurnStatus.RUNNING,
                "input", now, now, now, null, null, TurnInitiator.user(),
                stageId, stageId == null
                        ? (taskId == null ? ThreadScope.TRUNK : ThreadScope.TASK)
                        : ThreadScope.STAGE);
    }
}
