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
import com.bytequay.app.service.threadgroup.ThreadGroupService;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static java.util.Objects.requireNonNull;

@RestController
@RequestMapping("/api/thread-groups")
public class ThreadGroupController
{
    private final ThreadGroupService service;

    public ThreadGroupController(ThreadGroupService service)
    {
        this.service = requireNonNull(service, "service is null");
    }

    @GetMapping
    public List<ThreadGroup> list()
    {
        return service.list();
    }

    @GetMapping("/memberships")
    public List<ThreadGroupMembership> memberships()
    {
        return service.memberships();
    }

    @PostMapping
    public ThreadGroup create(@RequestBody NewGroupBody body)
    {
        if (body == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "body is required");
        }
        if (body.name() == null || body.name().isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "name is required");
        }
        return service.create(body);
    }

    @PatchMapping("/{id}")
    public ThreadGroup update(@PathVariable String id, @RequestBody PatchGroupBody body)
    {
        if (body == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "body is required");
        }
        return service.update(id, body);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id)
    {
        service.delete(id);
    }

    @PostMapping("/{groupId}/members/{threadId}")
    public void addMember(@PathVariable String groupId, @PathVariable String threadId)
    {
        service.addMember(groupId, threadId);
    }

    @DeleteMapping("/{groupId}/members/{threadId}")
    public void removeMember(@PathVariable String groupId, @PathVariable String threadId)
    {
        service.removeMember(groupId, threadId);
    }
}
