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

import com.bytequay.app.beans.threadgroup.NewGroupBody;
import com.bytequay.app.beans.threadgroup.PatchGroupBody;
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

import static com.bytequay.app.utils.StringInputUtil.requireNotBlank;
import static com.bytequay.app.web.RequestValidation.requireBody;
import static java.util.Objects.requireNonNull;

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

    @GetMapping("/memberships")
    public List<ThreadGroupMembership> memberships()
    {
        return threads.listAllMemberships();
    }

    @PostMapping
    public ThreadGroup create(@RequestBody NewGroupBody body)
    {
        body = requireBody(body);
        requireNotBlank(body.name(), "name is required");
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
        body = requireBody(body);
        return threads.updateGroup(id, new ThreadService.GroupPatch(
                body.name(), body.glyph(), body.color()));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id)
    {
        threads.deleteGroup(id);
    }

    @PostMapping("/{groupId}/members/{threadId}")
    public void addMember(@PathVariable String groupId, @PathVariable String threadId)
    {
        threads.addTaskToGroup(threadId, groupId);
    }

    @DeleteMapping("/{groupId}/members/{threadId}")
    public void removeMember(@PathVariable String groupId, @PathVariable String threadId)
    {
        threads.removeTaskFromGroup(threadId, groupId);
    }
}
