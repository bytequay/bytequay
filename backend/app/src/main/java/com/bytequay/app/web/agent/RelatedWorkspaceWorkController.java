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
package com.bytequay.app.web.agent;

import com.bytequay.app.agent.RelatedWorkspaceWork;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}")
public class RelatedWorkspaceWorkController
{
    private final RelatedWorkspaceWork work;

    public RelatedWorkspaceWorkController(RelatedWorkspaceWork work)
    {
        this.work = requireNonNull(work, "work is null");
    }

    @GetMapping("/linked-work")
    public List<RelatedWorkspaceWork.View> list(@PathVariable String workspaceId)
    {
        return work.list(workspaceId);
    }

    @GetMapping("/tasks/{taskId}/related-request")
    public Optional<RelatedWorkspaceWork.View> incoming(@PathVariable String workspaceId, @PathVariable String taskId)
    {
        return work.incoming(workspaceId, taskId);
    }
}
