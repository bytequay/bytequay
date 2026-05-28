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
import com.bytequay.app.domain.Thread;
import com.bytequay.app.repository.PermissionGrantStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
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
 * <p>Role derivation is unchanged from the earlier role-map resolver:
 * a 0-task (planning) thread is {@link AgentRole#TRUNK}; a thread with
 * any task is {@link AgentRole#TASK}. The {@code roles} filter on each
 * tool still governs discovery; this resolver governs capability.
 */
@Component
public class CascadingPermissionResolver
        implements PermissionResolver
{
    private static final String DENY = "deny";

    private final ThreadStore threadStore;
    private final TaskStore taskStore;
    private final PermissionGrantStore grantStore;

    public CascadingPermissionResolver(
            ThreadStore threadStore,
            TaskStore taskStore,
            PermissionGrantStore grantStore)
    {
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.grantStore = requireNonNull(grantStore, "grantStore is null");
    }

    @Override
    public AgentRole roleFor(String threadId)
    {
        if (threadId == null || threadId.isBlank()) {
            return AgentRole.TRUNK;
        }
        return taskStore.listTasksByThread(threadId).isEmpty()
                ? AgentRole.TRUNK
                : AgentRole.TASK;
    }

    @Override
    public Set<SecurityType> grants(String threadId)
    {
        Set<SecurityType> effective = EnumSet.noneOf(SecurityType.class);
        effective.addAll(RoleCapabilities.forRole(roleFor(threadId)));

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

        taskStore.findActiveTaskForThread(threadId)
                .map(Task::id)
                .ifPresent(taskId -> applyDenials(effective, grantStore.findForScope("task", taskId)));

        return ImmutableSet.copyOf(effective);
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
