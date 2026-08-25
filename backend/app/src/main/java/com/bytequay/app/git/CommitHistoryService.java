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
package com.bytequay.app.git;

import com.bytequay.app.git.CommitHistoryRouting.ResolvedCommitHistoryRef;
import com.bytequay.app.git.GitBrowseService.Commit;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.domain.CommitEnrichment;
import com.bytequay.core.domain.CommitRefreshStatus;
import com.bytequay.core.store.CommitHistoryStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

/** Local-first application boundary for commit history. */
@Service
public class CommitHistoryService
{
    private static final int MAX_COMMITS = 300;

    private final WorkspaceRepositoryService workspaces;
    private final GitBrowseService git;
    private final CommitHistoryStore store;
    private final CommitHistoryRefresher refresher;
    private final String avatarBaseUrl;

    public CommitHistoryService(
            WorkspaceRepositoryService workspaces,
            GitBrowseService git,
            CommitHistoryStore store,
            CommitHistoryRefresher refresher,
            @Value("${server.port:53123}") int serverPort)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.git = requireNonNull(git, "git is null");
        this.store = requireNonNull(store, "store is null");
        this.refresher = requireNonNull(refresher, "refresher is null");
        checkArgument(serverPort >= 0 && serverPort <= 65_535, "serverPort is invalid");
        this.avatarBaseUrl = "http://127.0.0.1:" + serverPort + "/api/avatars/";
    }

    /**
     * Reads commits from the managed checkout and joins durable local enrichment.
     * This method never contacts a provider or starts refresh work.
     */
    public CommitHistorySnapshot listHistory(String workspaceId, CommitHistoryRef ref, int limit)
    {
        requireNonNull(ref, "ref is null");
        checkArgument(limit > 0, "limit must be positive");
        WorkspaceRepositoryContext context = workspaces.requireReadableRepository(workspaceId);
        ResolvedCommitHistoryRef resolved = CommitHistoryRouting.resolve(context, ref);
        // Refresh metadata is committed after enrichment. Reading its token
        // first guarantees a concurrent completion causes one later reload.
        CommitRefreshStatus refresh = refresher.status(workspaceId);
        List<Commit> commits = git.log(context.repository(), resolved.gitRef(), Math.min(limit, MAX_COMMITS));
        Map<String, CommitEnrichment> enrichment = resolved.repositoryId().isEmpty()
                ? ImmutableMap.of()
                : store.findEnrichment(
                        resolved.repositoryId().orElseThrow(),
                        commits.stream().map(Commit::sha).toList())
                .commits().stream()
                .collect(toImmutableMap(CommitEnrichment::sha, Function.identity()));
        ImmutableList.Builder<CommitHistoryItem> items = ImmutableList.builderWithExpectedSize(commits.size());
        for (Commit commit : commits) {
            items.add(item(commit, Optional.ofNullable(enrichment.get(commit.sha()))));
        }
        return new CommitHistorySnapshot(items.build(), refresh);
    }

    /** Fetches, enriches, and persists commits before returning. */
    public CommitRefreshStatus refresh(
            String workspaceId,
            List<CommitHistoryRef> refs)
    {
        return refresher.refresh(workspaceId, validatedRefs(workspaceId, refs));
    }

    /** Refreshes only if no manual or scheduled attempt made this workspace fresh while queued. */
    public CommitRefreshStatus refreshIfDue(
            String workspaceId,
            List<CommitHistoryRef> refs,
            Instant staleBefore)
    {
        requireNonNull(staleBefore, "staleBefore is null");
        return refresher.refreshIfDue(workspaceId, validatedRefs(workspaceId, refs), staleBefore);
    }

    private List<CommitHistoryRef> validatedRefs(String workspaceId, List<CommitHistoryRef> refs)
    {
        requireNonNull(refs, "refs is null");
        List<CommitHistoryRef> requested = refs.stream().distinct().toList();
        checkArgument(!requested.isEmpty(), "refs is empty");
        WorkspaceRepositoryContext context = workspaces.requireReadyRepository(workspaceId);
        requested.forEach(ref -> CommitHistoryRouting.resolve(context, ref));
        return requested;
    }

    /** Reads the last completed refresh outcome without Git or provider access. */
    public CommitRefreshStatus getRefreshStatus(String workspaceId)
    {
        workspaces.getWorkspace(workspaceId);
        return refresher.status(workspaceId);
    }

    private CommitHistoryItem item(Commit commit, Optional<CommitEnrichment> enrichment)
    {
        Optional<Integer> pullRequestNumber = enrichment.flatMap(CommitEnrichment::pullRequestNumber)
                .or(commit::pullRequestNumber);
        return new CommitHistoryItem(
                commit.sha(),
                commit.title(),
                commit.body(),
                enrichment.flatMap(CommitEnrichment::authorLogin).orElse(commit.author()),
                enrichment.flatMap(CommitEnrichment::avatarKey).map(avatarBaseUrl::concat),
                enrichment.flatMap(CommitEnrichment::committerLogin).orElse(commit.committer()),
                enrichment.flatMap(CommitEnrichment::committerAvatarKey).map(avatarBaseUrl::concat),
                commit.authoredAt(),
                commit.committedAt(),
                pullRequestNumber,
                enrichment.flatMap(CommitEnrichment::pullRequestTitle));
    }
}
