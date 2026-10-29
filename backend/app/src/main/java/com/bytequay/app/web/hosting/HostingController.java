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

import com.bytequay.app.service.hosting.HostingConnection;
import com.bytequay.app.service.hosting.HostingConnectionService;
import com.bytequay.app.service.hosting.HostingPullRequestService;
import com.bytequay.app.workspace.WorkspaceHostingService;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.app.workspace.WorkspaceSummary;
import com.bytequay.core.hosting.HostingPermissions;
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.hosting.HostingPullRequestSnapshot;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.HostingServer;
import com.bytequay.core.hosting.PullRequestListPage;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

@RestController
@RequestMapping("/api/hosting")
public class HostingController
{
    private final HostingConnectionService connections;
    private final HostingPullRequestService requests;
    private final WorkspaceHostingService workspaces;
    private final WorkspaceRepositoryService repositories;

    public HostingController(HostingConnectionService connections, HostingPullRequestService requests, WorkspaceHostingService workspaces, WorkspaceRepositoryService repositories)
    {
        this.connections = requireNonNull(connections, "connections is null");
        this.requests = requireNonNull(requests, "requests is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.repositories = requireNonNull(repositories, "repositories is null");
    }

    @GetMapping("/accounts")
    public List<HostingConnection> accounts()
    {
        return connections.connections();
    }

    @PostMapping("/accounts")
    public HostingConnection connect(@RequestBody ConnectRequest request)
    {
        return connections.connect(request.provider(), request.webBaseUrl(), request.apiBaseUrl(), request.token());
    }

    @PostMapping("/accounts/{accountId}/permissions")
    public HostingPermissions permissions(@PathVariable long accountId)
    {
        return connections.checkPermissions(accountId);
    }

    @DeleteMapping("/accounts/{accountId}/credential")
    public void disconnect(@PathVariable long accountId)
    {
        connections.disconnect(accountId);
    }

    @PostMapping("/cli/login")
    public HostingConnection connectCli(@RequestBody HostingServer server)
    {
        return connections.connectCli(server.provider(), server.webBaseUrl(), server.apiBaseUrl());
    }

    @PostMapping("/accounts/{accountId}/repositories/resolve")
    public HostingRepository repository(@PathVariable long accountId, @RequestBody RepositoryRequest request)
    {
        return connections.resolveRepository(accountId, request.fullPath());
    }

    @GetMapping("/accounts/{accountId}/repositories")
    public List<HostingRepository> searchRepositories(@PathVariable long accountId,
            @RequestParam(defaultValue = "") String query, @RequestParam(defaultValue = "30") int limit)
    {
        return connections.searchRepositories(accountId, query, limit);
    }

    @PostMapping("/accounts/{accountId}/workspaces")
    public WorkspaceSummary watch(@PathVariable long accountId, @RequestBody WatchRequest request)
    {
        var workspace = workspaces.watch(accountId, request.repositoryId(), Optional.ofNullable(request.name()));
        repositories.requestSync(workspace.id());
        return WorkspaceSummary.of(workspace);
    }

    @GetMapping("/accounts/{accountId}/repositories/{repositoryId}/requests")
    public PullRequestListPage pullRequests(@PathVariable long accountId, @PathVariable long repositoryId,
            @RequestParam(defaultValue = "false") boolean openOnly, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "100") int pageSize)
    {
        return requests.refreshPage(connections.readSession(accountId), repositoryId, openOnly, page, pageSize);
    }

    @GetMapping("/accounts/{accountId}/repositories/{repositoryId}/requests/cached")
    public PullRequestListPage cachedPullRequests(@PathVariable long accountId, @PathVariable long repositoryId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "100") int pageSize)
    {
        return requests.cachedPage(connections.readSession(accountId), repositoryId, page, pageSize);
    }

    @GetMapping("/accounts/{accountId}/repositories/{repositoryId}/requests/{number}")
    public HostingPullRequestSnapshot pullRequest(@PathVariable long accountId, @PathVariable long repositoryId, @PathVariable int number)
    {
        return requests.refresh(accountId, repositoryId, number);
    }

    @GetMapping("/accounts/{accountId}/repositories/{repositoryId}/requests/{number}/cached")
    public Optional<HostingPullRequestSnapshot> cachedPullRequest(@PathVariable long accountId, @PathVariable long repositoryId, @PathVariable int number)
    {
        return requests.cached(accountId, repositoryId, number);
    }

    public record ConnectRequest(HostingProvider provider, URI webBaseUrl, URI apiBaseUrl, String token)
    {
        public ConnectRequest
        {
            checkArgument(provider != null && webBaseUrl != null && apiBaseUrl != null, "hosting coordinates are required");
            checkText(token, "token");
        }

        @Override
        public String toString()
        {
            return "ConnectRequest[provider=" + provider + ", token=<redacted>]";
        }
    }

    public record RepositoryRequest(String fullPath) {}

    public record WatchRequest(long repositoryId, String name) {}
}
