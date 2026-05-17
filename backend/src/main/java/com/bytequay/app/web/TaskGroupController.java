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
package com.bytequay.app.web;

import com.bytequay.app.domain.TaskGroup;
import com.bytequay.app.domain.TaskGroupMembership;
import com.bytequay.app.service.tasks.TaskService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * REST surface for the Groups rail and the new tasks-group page:
 * list / create / rename / delete groups, plus add/remove members.
 *
 * <p>Group ↔ task membership is many-to-many — one task can sit in
 * several groups. The frontend pulls the full membership snapshot
 * once via {@link #memberships()} and joins it in memory.
 */
@RestController
@RequestMapping("/api/task-groups")
public class TaskGroupController
{
    private final TaskService tasks;

    public TaskGroupController(TaskService tasks)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
    }

    @GetMapping
    public List<TaskGroup> list()
    {
        return tasks.listGroups();
    }

    /** Single flat list of (taskId, groupId, addedAt) — the frontend
     *  derives task→groups and group→tasks indexes in memory. */
    @GetMapping("/memberships")
    public List<TaskGroupMembership> memberships()
    {
        return tasks.listAllMemberships();
    }

    /**
     * Create a group with an initial set of members. {@code
     * initialTaskIds} must contain at least one existing task and no
     * more than {@link TaskService#GROUP_MAX_MEMBERS}; the whole
     * create is transactional in {@link TaskService#createGroup}.
     */
    @PostMapping
    public TaskGroup create(@RequestBody NewGroupBody body)
    {
        requireNonNull(body, "body is null");
        if (body.name() == null || body.name().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        return tasks.createGroup(new TaskService.NewGroupRequest(
                body.name(),
                body.glyph(),
                body.color(),
                body.sortOrder(),
                body.initialTaskIds() == null ? List.of() : body.initialTaskIds()));
    }

    @PatchMapping("/{id}")
    public TaskGroup update(@PathVariable String id, @RequestBody PatchGroupBody body)
    {
        requireNonNull(body, "body is required");
        return tasks.updateGroup(id, new TaskService.GroupPatch(
                body.name(), body.glyph(), body.color()));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id)
    {
        tasks.deleteGroup(id);
    }

    /** Add a task to a group. Rejects when the group is already at
     *  the {@link TaskService#GROUP_MAX_MEMBERS} cap. Idempotent on
     *  existing members. */
    @PostMapping("/{groupId}/members/{taskId}")
    public void addMember(@PathVariable String groupId, @PathVariable String taskId)
    {
        tasks.addTaskToGroup(taskId, groupId);
    }

    /** Remove a task from a group. Rejects when the task is the
     *  group's only remaining member — callers must delete the group
     *  itself instead. */
    @DeleteMapping("/{groupId}/members/{taskId}")
    public void removeMember(@PathVariable String groupId, @PathVariable String taskId)
    {
        tasks.removeTaskFromGroup(taskId, groupId);
    }

    public record NewGroupBody(
            String name,
            String glyph,
            String color,
            int sortOrder,
            /** Required — at least one existing task id. The group
             *  invariant is enforced server-side. */
            List<String> initialTaskIds) {}

    public record PatchGroupBody(String name, String glyph, String color) {}
}
