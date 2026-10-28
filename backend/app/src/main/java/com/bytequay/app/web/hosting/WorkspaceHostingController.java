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
package com.bytequay.app.web.hosting;

import com.bytequay.app.workspace.WorkspaceHostingService;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.hosting.HostingPullRequestSnapshot;
import com.bytequay.core.hosting.WorkspaceHostingBinding;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/hosting")
public class WorkspaceHostingController
{
    private final WorkspaceHostingService hosting;
    private final WorkspaceRepositoryService repositories;

    public WorkspaceHostingController(WorkspaceHostingService hosting, WorkspaceRepositoryService repositories)
    {
        this.hosting = requireNonNull(hosting, "hosting is null");
        this.repositories = requireNonNull(repositories, "repositories is null");
    }

    @GetMapping
    public Optional<WorkspaceHostingBinding> binding(@PathVariable String workspaceId)
    {
        return hosting.findBinding(workspaceId);
    }

    @PostMapping
    public WorkspaceHostingBinding bind(@PathVariable String workspaceId, @RequestBody BindRequest request)
    {
        WorkspaceHostingBinding binding = hosting.bind(workspaceId, request.accountId());
        repositories.requestSyncAfterConfigurationChange(workspaceId);
        return binding;
    }

    @GetMapping("/requests/{number}")
    public HostingPullRequestSnapshot request(@PathVariable String workspaceId, @PathVariable int number)
    {
        return hosting.readPullRequest(workspaceId, number);
    }

    public record BindRequest(long accountId) {}
}
