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
package com.bytequay.app.repository;

import com.bytequay.core.domain.CommitEnrichment;
import com.bytequay.core.domain.CommitEnrichmentSnapshot;
import com.bytequay.core.domain.RemoteSource;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.CommitHistoryStore;
import com.bytequay.core.store.RemoteSourceStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Owns durable enrichment data and the provider identity needed to reuse it. */
@Service
public class RepositoryEnrichmentService
{
    private final CommitHistoryStore commitHistory;
    private final RemoteSourceStore remoteSources;
    private final WorkspaceStore workspaces;

    public RepositoryEnrichmentService(
            CommitHistoryStore commitHistory,
            RemoteSourceStore remoteSources,
            WorkspaceStore workspaces)
    {
        this.commitHistory = requireNonNull(commitHistory, "commitHistory is null");
        this.remoteSources = requireNonNull(remoteSources, "remoteSources is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
    }

    public CommitEnrichmentSnapshot findCommits(long repositoryId, Collection<String> shas)
    {
        return commitHistory.findEnrichment(repositoryId, shas);
    }

    public void upsertCommits(Collection<CommitEnrichment> enrichment)
    {
        commitHistory.upsertEnrichment(enrichment);
    }

    /**
     * Attaches stable GitHub identities to custom remotes when the same repository is already watched.
     * This lookup is local-only; an unknown remote never causes a provider request while History loads.
     */
    public List<RemoteSource> enrichRemoteSources(String workspaceId)
    {
        requireNonNull(workspaceId, "workspaceId is null");
        List<KnownRepository> knownRepositories = knownRepositories();
        ImmutableList.Builder<RemoteSource> result = ImmutableList.builder();
        for (RemoteSource source : remoteSources.findByWorkspace(workspaceId)) {
            RemoteSource enriched = enrich(source, knownRepositories);
            if (!enriched.equals(source)) {
                remoteSources.save(enriched);
            }
            result.add(enriched);
        }
        return result.build();
    }

    private List<KnownRepository> knownRepositories()
    {
        List<KnownRepository> repositories = new ArrayList<>();
        for (Workspace workspace : workspaces.findAll()) {
            repositories.add(new KnownRepository(
                    workspace.selectedRepositoryId(), workspace.selectedRepository()));
            workspace.upstreamRepositoryId().ifPresent(repositoryId -> repositories.add(new KnownRepository(
                    repositoryId, workspace.upstreamRepository().orElseThrow())));
        }
        return repositories;
    }

    private static RemoteSource enrich(RemoteSource source, List<KnownRepository> repositories)
    {
        if (source.repositoryId().isPresent()) {
            long repositoryId = source.repositoryId().orElseThrow();
            return repositories.stream()
                    .filter(candidate -> candidate.repositoryId() == repositoryId)
                    .findFirst()
                    .map(value -> source.withRepositoryIdentity(value.repositoryId(), value.repository()))
                    .orElse(source);
        }
        Optional<KnownRepository> repository = githubRepository(source.url())
                .flatMap(coordinates -> repositories.stream()
                        .filter(candidate -> sameRepository(candidate.repository(), coordinates))
                        .findFirst());
        return repository
                .map(value -> source.withRepositoryIdentity(value.repositoryId(), value.repository()))
                .orElse(source);
    }

    private static Optional<RepoRef> githubRepository(String url)
    {
        try {
            URI remote = URI.create(url);
            if (!"https".equalsIgnoreCase(remote.getScheme())
                    || !"github.com".equalsIgnoreCase(remote.getHost())) {
                return Optional.empty();
            }
            String path = remote.getPath().replaceFirst("^/+", "").replaceFirst("/+$", "");
            if (path.toLowerCase(Locale.ROOT).endsWith(".git")) {
                path = path.substring(0, path.length() - 4);
            }
            String[] parts = path.split("/");
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                return Optional.empty();
            }
            return Optional.of(RepoRef.of(parts[0], parts[1]));
        }
        catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static boolean sameRepository(RepoRef left, RepoRef right)
    {
        return left.owner().equalsIgnoreCase(right.owner())
                && left.repo().equalsIgnoreCase(right.repo());
    }

    private record KnownRepository(long repositoryId, RepoRef repository) {}
}
