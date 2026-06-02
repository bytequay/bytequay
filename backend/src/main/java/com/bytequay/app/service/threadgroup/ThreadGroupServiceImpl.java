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
package com.bytequay.app.service.threadgroup;

import com.bytequay.app.beans.threadgroup.NewGroupBody;
import com.bytequay.app.beans.threadgroup.PatchGroupBody;
import com.bytequay.app.domain.ThreadGroup;
import com.bytequay.app.domain.ThreadGroupMembership;
import com.bytequay.app.service.threads.ThreadService;
import org.springframework.stereotype.Service;

import java.util.List;

import static java.util.Objects.requireNonNull;

@Service
public class ThreadGroupServiceImpl
        implements ThreadGroupService
{
    private final ThreadService threads;

    public ThreadGroupServiceImpl(ThreadService threads)
    {
        this.threads = requireNonNull(threads, "threads is null");
    }

    @Override
    public List<ThreadGroup> list()
    {
        return threads.listGroups();
    }

    @Override
    public List<ThreadGroupMembership> memberships()
    {
        return threads.listAllMemberships();
    }

    @Override
    public ThreadGroup create(NewGroupBody body)
    {
        // Defensive only — controller already returned 400 on a null
        // body or blank name. The requireNonNull guards a cross-service
        // reuse path, where the caller skipped the HTTP layer; full
        // input validation belongs at the boundary, not here.
        requireNonNull(body, "body is null");
        return threads.createGroup(new ThreadService.NewGroupRequest(
                body.name(),
                body.glyph(),
                body.color(),
                body.sortOrder(),
                body.initialTaskIds() == null ? List.of() : body.initialTaskIds()));
    }

    @Override
    public ThreadGroup update(String id, PatchGroupBody body)
    {
        // Defensive only — see {@link #create}.
        requireNonNull(body, "body is null");
        return threads.updateGroup(id, new ThreadService.GroupPatch(
                body.name(), body.glyph(), body.color()));
    }

    @Override
    public void delete(String id)
    {
        threads.deleteGroup(id);
    }

    @Override
    public void addMember(String groupId, String threadId)
    {
        threads.addTaskToGroup(threadId, groupId);
    }

    @Override
    public void removeMember(String groupId, String threadId)
    {
        threads.removeTaskFromGroup(threadId, groupId);
    }
}
