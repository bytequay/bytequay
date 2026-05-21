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

import com.bytequay.app.domain.ThreadGroup;
import com.bytequay.app.domain.ThreadGroupMembership;
import com.bytequay.app.service.threads.ThreadService;
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
 * REST surface for the Groups rail and the new threads-group page:
 * list / create / rename / delete groups, plus add/remove members.
 *
 * <p>Group ↔ thread membership is many-to-many — one thread can sit in
 * several groups. The frontend pulls the full membership snapshot
 * once via {@link #memberships()} and joins it in memory.
 */
@RestController
@RequestMapping("/api/thread-groups")
public class ThreadGroupController
{
    private final ThreadService threads;

    public ThreadGroupController(ThreadService threads)
    {
        this.threads = requireNonNull(threads, "threads is null");
    }

    @GetMapping
    public List<ThreadGroup> list()
    {
        return threads.listGroups();
    }

    /** Single flat list of (threadId, groupId, addedAt) — the frontend
     *  derives thread→groups and group→threads indexes in memory. */
    @GetMapping("/memberships")
    public List<ThreadGroupMembership> memberships()
    {
        return threads.listAllMemberships();
    }

    /**
     * Create a group with an initial set of members. {@code
     * initialTaskIds} must contain at least one existing thread and no
     * more than {@link ThreadService#GROUP_MAX_MEMBERS}; the whole
     * create is transactional in {@link ThreadService#createGroup}.
     */
    @PostMapping
    public ThreadGroup create(@RequestBody NewGroupBody body)
    {
        requireNonNull(body, "body is null");
        if (body.name() == null || body.name().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        return threads.createGroup(new ThreadService.NewGroupRequest(
                body.name(),
                body.glyph(),
                body.color(),
                body.sortOrder(),
                body.initialTaskIds() == null ? List.of() : body.initialTaskIds()));
    }

    @PatchMapping("/{id}")
    public ThreadGroup update(@PathVariable String id, @RequestBody PatchGroupBody body)
    {
        requireNonNull(body, "body is required");
        return threads.updateGroup(id, new ThreadService.GroupPatch(
                body.name(), body.glyph(), body.color()));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id)
    {
        threads.deleteGroup(id);
    }

    /** Add a thread to a group. Rejects when the group is already at
     *  the {@link ThreadService#GROUP_MAX_MEMBERS} cap. Idempotent on
     *  existing members. */
    @PostMapping("/{groupId}/members/{threadId}")
    public void addMember(@PathVariable String groupId, @PathVariable String threadId)
    {
        threads.addTaskToGroup(threadId, groupId);
    }

    /** Remove a thread from a group. Rejects when the thread is the
     *  group's only remaining member — callers must delete the group
     *  itself instead. */
    @DeleteMapping("/{groupId}/members/{threadId}")
    public void removeMember(@PathVariable String groupId, @PathVariable String threadId)
    {
        threads.removeTaskFromGroup(threadId, groupId);
    }

    /**
     * New thread-group request body.
     *
     * @param initialTaskIds required existing thread ids.
     */
    public record NewGroupBody(
            String name,
            String glyph,
            String color,
            int sortOrder,
            List<String> initialTaskIds) {}

    public record PatchGroupBody(String name, String glyph, String color) {}
}
