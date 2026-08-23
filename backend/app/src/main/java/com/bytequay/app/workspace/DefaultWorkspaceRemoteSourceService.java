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
package com.bytequay.app.workspace;

import com.bytequay.app.workspace.WorkspaceRemoteSourceService.WorkspaceRemote.Role;
import com.bytequay.core.domain.RemoteSource;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.domain.Workspace.CodeState;
import com.bytequay.core.store.RemoteSourceStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

@Service
public class DefaultWorkspaceRemoteSourceService
        implements WorkspaceRemoteSourceService
{
    private static final Pattern REMOTE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private final WorkspaceStore workspaces;
    private final RemoteSourceStore store;
    private final ManagedWorkspaceGit git;
    private final WorkspaceRepositoryService repositoryService;

    public DefaultWorkspaceRemoteSourceService(
            WorkspaceStore workspaces,
            RemoteSourceStore store,
            ManagedWorkspaceGit git,
            WorkspaceRepositoryService repositoryService)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.store = requireNonNull(store, "store is null");
        this.git = requireNonNull(git, "git is null");
        this.repositoryService = requireNonNull(repositoryService, "repositoryService is null");
    }

    @Override
    public List<WorkspaceRemote> list(String workspaceId)
    {
        return remotes(requireWorkspace(workspaceId));
    }

    @Override
    public List<WorkspaceRemote> add(String workspaceId, String name, String url)
    {
        Workspace workspace = requireWorkspace(workspaceId);
        String remoteName = requireValidName(name);
        String remoteUrl = requireValidUrl(url);
        if (store.findByWorkspace(workspace.id()).stream()
                .anyMatch(source -> source.name().equals(remoteName))) {
            throw WorkspaceException.invalidRemoteSource(
                    "A remote source named " + remoteName + " already exists.");
        }
        if (workspace.codeState() == CodeState.READY) {
            try {
                git.probeRemoteSource(workspace, remoteUrl);
            }
            catch (ManagedWorkspaceGit.Failure failure) {
                throw WorkspaceException.invalidRemoteSource(failure.safeMessage());
            }
        }
        store.save(new RemoteSource(workspace.id(), remoteName, remoteUrl));
        // Reconcile and fetch happen inside the normal background sync so the
        // checkout, remote list, and freshness state stay in one flow.
        repositoryService.requestSync(workspace.id());
        return remotes(workspace);
    }

    @Override
    public List<WorkspaceRemote> remove(String workspaceId, String name)
    {
        Workspace workspace = requireWorkspace(workspaceId);
        String remoteName = requireValidName(name);
        store.delete(workspace.id(), remoteName);
        repositoryService.requestSync(workspace.id());
        return remotes(workspace);
    }

    private List<WorkspaceRemote> remotes(Workspace workspace)
    {
        ImmutableList.Builder<WorkspaceRemote> remotes = ImmutableList.builder();
        remotes.add(new WorkspaceRemote("origin", workspace.originCloneUrl(), Role.ORIGIN));
        workspace.upstreamCloneUrl().ifPresent(url ->
                remotes.add(new WorkspaceRemote("upstream", url, Role.UPSTREAM)));
        store.findByWorkspace(workspace.id()).forEach(source ->
                remotes.add(new WorkspaceRemote(source.name(), source.url(), Role.CUSTOM)));
        return remotes.build();
    }

    private Workspace requireWorkspace(String workspaceId)
    {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw WorkspaceException.notFound(String.valueOf(workspaceId));
        }
        return workspaces.findById(workspaceId)
                .orElseThrow(() -> WorkspaceException.notFound(workspaceId));
    }

    private static String requireValidName(String name)
    {
        String trimmed = name == null ? "" : name.trim();
        if (!REMOTE_NAME.matcher(trimmed).matches()) {
            throw WorkspaceException.invalidRemoteSource(
                    "Remote names use letters, digits, dots, dashes, or underscores.");
        }
        if (trimmed.equals("origin") || trimmed.equals("upstream")) {
            throw WorkspaceException.invalidRemoteSource(
                    "The names origin and upstream are managed by the workspace.");
        }
        return trimmed;
    }

    private static String requireValidUrl(String url)
    {
        String trimmed = url == null ? "" : url.trim();
        try {
            URI uri = URI.create(trimmed);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getHost().isBlank()
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException("unsafe remote URL");
            }
        }
        catch (IllegalArgumentException ignored) {
            throw WorkspaceException.invalidRemoteSource(
                    "Use a plain https repository URL, like https://github.com/owner/repo.git.");
        }
        return trimmed;
    }
}
