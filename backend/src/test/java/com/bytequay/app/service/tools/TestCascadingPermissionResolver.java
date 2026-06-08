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
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.PermissionGrantStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestCascadingPermissionResolver
{
    private final ThreadStore threadStore = mock(ThreadStore.class);
    private final TaskStore taskStore = mock(TaskStore.class);
    private final PermissionGrantStore grantStore = mock(PermissionGrantStore.class);

    private final CascadingPermissionResolver resolver =
            new CascadingPermissionResolver(threadStore, taskStore, grantStore);

    @Test
    void zeroTaskThreadResolvesToTrunkBaseWhenNoGrants()
    {
        String threadId = "t-trunk";
        when(taskStore.listTasksByThread(threadId)).thenReturn(List.of());
        when(threadStore.findThreadById(threadId)).thenReturn(Optional.of(thread(threadId, "ws-1")));
        when(taskStore.findActiveTaskForThread(threadId)).thenReturn(Optional.empty());
        noGrants();

        assertThat(resolver.roleFor(threadId)).isEqualTo(AgentRole.TRUNK);
        assertThat(resolver.grants(threadId))
                .isEqualTo(RoleCapabilities.forRole(AgentRole.TRUNK))
                .contains(SecurityType.TASK_MANAGE, SecurityType.CODE_READ)
                .doesNotContain(SecurityType.GIT_PUSH);
    }

    @Test
    void taskThreadResolvesToTaskBaseWhenNoGrants()
    {
        String threadId = "t-task";
        when(taskStore.listTasksByThread(threadId)).thenReturn(List.of(task("task-1")));
        when(threadStore.findThreadById(threadId)).thenReturn(Optional.of(thread(threadId, "ws-1")));
        when(taskStore.findActiveTaskForThread(threadId)).thenReturn(Optional.of(task("task-1")));
        noGrants();

        assertThat(resolver.roleFor(threadId)).isEqualTo(AgentRole.TASK);
        assertThat(resolver.grants(threadId))
                .contains(SecurityType.GIT_PUSH, SecurityType.CODE_WRITE, SecurityType.VCS_PUBLISH);
    }

    @Test
    void workspaceDenyRemovesCapabilityFromTaskThread()
    {
        String threadId = "t-task";
        when(taskStore.listTasksByThread(threadId)).thenReturn(List.of(task("task-1")));
        when(threadStore.findThreadById(threadId)).thenReturn(Optional.of(thread(threadId, "ws-locked")));
        when(taskStore.findActiveTaskForThread(threadId)).thenReturn(Optional.of(task("task-1")));
        when(grantStore.findGlobal()).thenReturn(List.of());
        when(grantStore.findForScope("workspace", "ws-locked"))
                .thenReturn(List.of(deny("workspace", "ws-locked", SecurityType.GIT_PUSH)));
        when(grantStore.findForScope(eq("thread"), anyString())).thenReturn(List.of());
        when(grantStore.findForScope(eq("task"), anyString())).thenReturn(List.of());

        assertThat(resolver.grants(threadId))
                .doesNotContain(SecurityType.GIT_PUSH)
                .contains(SecurityType.CODE_WRITE);
    }

    @Test
    void taskLevelAllowCannotReAddAWorkspaceDeniedCapability()
    {
        // Tighten-only: a deny at the workspace stays denied even when
        // a deeper task scope explicitly allows it.
        String threadId = "t-task";
        when(taskStore.listTasksByThread(threadId)).thenReturn(List.of(task("task-1")));
        when(threadStore.findThreadById(threadId)).thenReturn(Optional.of(thread(threadId, "ws-locked")));
        when(taskStore.findActiveTaskForThread(threadId)).thenReturn(Optional.of(task("task-1")));
        when(grantStore.findGlobal()).thenReturn(List.of());
        when(grantStore.findForScope("workspace", "ws-locked"))
                .thenReturn(List.of(deny("workspace", "ws-locked", SecurityType.VCS_PUBLISH)));
        when(grantStore.findForScope(eq("thread"), anyString())).thenReturn(List.of());
        when(grantStore.findForScope("task", "task-1"))
                .thenReturn(List.of(grant("task", "task-1", SecurityType.VCS_PUBLISH, "allow")));

        assertThat(resolver.grants(threadId)).doesNotContain(SecurityType.VCS_PUBLISH);
    }

    @Test
    void globalDenyAppliesToEveryThread()
    {
        String threadId = "t-trunk";
        when(taskStore.listTasksByThread(threadId)).thenReturn(List.of());
        when(threadStore.findThreadById(threadId)).thenReturn(Optional.of(thread(threadId, "ws-1")));
        when(taskStore.findActiveTaskForThread(threadId)).thenReturn(Optional.empty());
        when(grantStore.findGlobal())
                .thenReturn(List.of(deny("global", null, SecurityType.TASK_MANAGE)));
        when(grantStore.findForScope(anyString(), anyString())).thenReturn(List.of());

        assertThat(resolver.grants(threadId))
                .doesNotContain(SecurityType.TASK_MANAGE)
                .contains(SecurityType.CODE_READ);
    }

    @Test
    void unknownCapabilityInAGrantIsIgnored()
    {
        String threadId = "t-trunk";
        when(taskStore.listTasksByThread(threadId)).thenReturn(List.of());
        when(threadStore.findThreadById(threadId)).thenReturn(Optional.of(thread(threadId, "ws-1")));
        when(taskStore.findActiveTaskForThread(threadId)).thenReturn(Optional.empty());
        when(grantStore.findGlobal()).thenReturn(List.of(new PermissionGrant(
                1L, "global", null, "DECOMMISSIONED_CAP", "deny", null,
                Instant.now(), Instant.now())));
        when(grantStore.findForScope(anyString(), anyString())).thenReturn(List.of());

        // The stray capability name is skipped — the base set is intact.
        assertThat(resolver.grants(threadId)).isEqualTo(RoleCapabilities.forRole(AgentRole.TRUNK));
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

    private static Task task(String id)
    {
        Instant now = Instant.parse("2026-05-28T00:00:00Z");
        return new Task(
                id, "t-task", 1L, TaskStatus.RUNNING,
                "branch", "/tmp/wt", "main", "/tmp/repo",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, now, null, null, null, null, null);
    }
}
