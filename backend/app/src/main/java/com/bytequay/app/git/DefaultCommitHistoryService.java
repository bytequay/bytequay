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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static com.bytequay.core.domain.CommitRefreshStatus.State.QUEUED;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

@Service
public class DefaultCommitHistoryService
        implements CommitHistoryService
{
    private static final int MAX_COMMITS = 300;

    private final WorkspaceRepositoryService workspaces;
    private final GitBrowseService git;
    private final CommitHistoryStore store;
    private final CommitHistoryRefreshWorker refreshWorker;
    private final Clock clock;
    private final String avatarBaseUrl;

    public DefaultCommitHistoryService(
            WorkspaceRepositoryService workspaces,
            GitBrowseService git,
            CommitHistoryStore store,
            CommitHistoryRefreshWorker refreshWorker,
            Clock clock,
            @Value("${server.port:53123}") int serverPort)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.git = requireNonNull(git, "git is null");
        this.store = requireNonNull(store, "store is null");
        this.refreshWorker = requireNonNull(refreshWorker, "refreshWorker is null");
        this.clock = requireNonNull(clock, "clock is null");
        checkArgument(serverPort > 0 && serverPort <= 65_535, "serverPort is invalid");
        this.avatarBaseUrl = "http://127.0.0.1:" + serverPort + "/api/avatars/";
    }

    @Override
    public CommitRefreshStatus requestRefresh(
            String workspaceId,
            List<CommitHistoryRef> refs,
            CommitRefreshCause cause)
    {
        requireNonNull(refs, "refs is null");
        requireNonNull(cause, "cause is null");
        List<CommitHistoryRef> requested = refs.stream().distinct().toList();
        checkArgument(!requested.isEmpty(), "refs is empty");
        WorkspaceRepositoryContext context = workspaces.requireReadyRepository(workspaceId);
        requested.forEach(ref -> CommitHistoryRouting.resolve(context, ref));
        CommitRefreshStatus previous = store.findRefreshStatus(workspaceId);
        CommitRefreshStatus queued = new CommitRefreshStatus(
                workspaceId,
                QUEUED,
                Optional.of(clock.instant()),
                previous.lastSucceededAt(),
                Optional.empty());
        store.saveRefreshStatus(queued);
        refreshWorker.enqueue(workspaceId, requested);
        return queued;
    }

    @Override
    public CommitRefreshStatus getRefreshStatus(String workspaceId)
    {
        return store.findRefreshStatus(workspaceId);
    }

    @Override
    public CommitHistorySnapshot listHistory(String workspaceId, CommitHistoryRef ref, int limit)
    {
        requireNonNull(ref, "ref is null");
        checkArgument(limit > 0, "limit must be positive");
        WorkspaceRepositoryContext context = workspaces.requireReadyRepository(workspaceId);
        ResolvedCommitHistoryRef resolved = CommitHistoryRouting.resolve(context, ref);
        List<Commit> commits = git.log(context.repository(), resolved.gitRef(), Math.min(limit, MAX_COMMITS));
        Map<String, CommitEnrichment> enrichment = store.findEnrichment(
                        resolved.repositoryId(),
                        commits.stream().map(Commit::sha).toList())
                .commits().stream()
                .collect(toImmutableMap(CommitEnrichment::sha, Function.identity()));
        ImmutableList.Builder<CommitHistoryItem> items = ImmutableList.builderWithExpectedSize(commits.size());
        for (Commit commit : commits) {
            items.add(item(commit, Optional.ofNullable(enrichment.get(commit.sha()))));
        }
        return new CommitHistorySnapshot(items.build(), store.findRefreshStatus(workspaceId));
    }

    private CommitHistoryItem item(Commit commit, Optional<CommitEnrichment> enrichment)
    {
        Optional<Integer> pullRequestNumber = enrichment.flatMap(CommitEnrichment::pullRequestNumber)
                .or(commit::pullRequestNumber);
        return new CommitHistoryItem(
                commit.sha(),
                commit.title(),
                commit.body(),
                commit.author(),
                enrichment.flatMap(CommitEnrichment::avatarKey).map(avatarBaseUrl::concat),
                commit.authoredAt(),
                pullRequestNumber,
                enrichment.flatMap(CommitEnrichment::pullRequestTitle));
    }
}
