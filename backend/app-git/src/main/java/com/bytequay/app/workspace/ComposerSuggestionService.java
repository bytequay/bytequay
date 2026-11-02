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

import com.bytequay.app.service.hosting.HostingIntegrations;
import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.hosting.IssueClient;
import com.bytequay.core.hosting.IssueSyncPage;
import com.bytequay.core.hosting.PullRequestListPage;
import com.bytequay.core.hosting.PullRequestReader;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.UncheckedExecutionException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Throwables.throwIfUnchecked;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Searches provider pages until the newest ten matching items are known, including matches beyond recent local history. */
@Service
public class ComposerSuggestionService
{
    private final WorkspaceHostingService hosting;
    private final HostingIntegrations integrations;
    private final Cache<CacheKey, CandidatePage> pages = CacheBuilder.newBuilder()
            .maximumSize(500).expireAfterWrite(Duration.ofMinutes(1)).build();

    public ComposerSuggestionService(WorkspaceHostingService hosting, HostingIntegrations integrations)
    {
        this.hosting = requireNonNull(hosting, "hosting is null");
        this.integrations = requireNonNull(integrations, "integrations is null");
    }

    public List<ComposerSuggestion> list(String workspaceId, Command command, String search)
    {
        requireNonNull(command, "command is null");
        requireNonNull(search, "search is null");
        checkArgument(search.length() <= 500, "search must be at most 500 characters");
        String query = search.strip().toLowerCase(Locale.ROOT);
        WorkspaceHostingRead route = hosting.storedRead(workspaceId);
        Map<Integer, Candidate> found = new HashMap<>();
        for (int page = 1; ; page++) {
            CandidatePage result = page(route, command, page);
            result.items().stream()
                    .filter(item -> item.suggestion().title().toLowerCase(Locale.ROOT).contains(query) || ("#" + item.suggestion().number()).contains(query))
                    .forEach(item -> found.put(item.suggestion().number(), item));
            List<Candidate> newest = found.values().stream()
                    .sorted(Comparator.comparing(Candidate::sortAt).thenComparingInt(item -> item.suggestion().number()).reversed())
                    .limit(10)
                    .collect(toImmutableList());
            // Issue pages bound creation time; PR pages bound update time, which cannot precede creation or merge. Preserve timestamp ties.
            if (!result.hasNext() || newest.size() == 10 && result.remainingBefore().filter(at -> at.isBefore(newest.getLast().sortAt())).isPresent()) {
                return newest.stream().map(Candidate::suggestion).collect(toImmutableList());
            }
        }
    }

    private CandidatePage page(WorkspaceHostingRead route, Command command, int page)
    {
        CacheKey key = new CacheKey(route.session().cacheScope(), route.repository().id(), route.login(), command, page);
        try {
            // Coalesce page loads across keystrokes; failed loads are never cached.
            return pages.get(key, () -> load(route, command, page));
        }
        catch (ExecutionException | UncheckedExecutionException failure) {
            throwIfUnchecked(failure.getCause());
            throw new UncheckedExecutionException(failure.getCause());
        }
    }

    private CandidatePage load(WorkspaceHostingRead route, Command command, int page)
    {
        if (command == Command.FIX) {
            IssueClient issues = integrations.of(route.target()).issues();
            IssueSyncPage result = issues.list(route.target(), Optional.empty(), page);
            return new CandidatePage(result.openIssues().stream().map(issue -> new Candidate(
                    new ComposerSuggestion(issue.number(), issue.title(), issue.htmlUrl(), issue.author(), issue.updatedAt()), issue.createdAt())).collect(toImmutableList()),
                    result.openIssues().stream().map(RepositoryIssue::createdAt).min(Comparator.naturalOrder()), result.hasNext());
        }
        PullRequestReader requests = integrations.of(route.target()).pullRequests();
        PullRequestListPage result = requests.list(route.target(), command == Command.REVIEW, page, 100);
        List<Candidate> candidates = result.items().stream()
                .filter(pr -> command == Command.BACKPORT ? "merged".equalsIgnoreCase(pr.state())
                        : "open".equalsIgnoreCase(pr.state()) && !pr.author().equalsIgnoreCase(route.login()))
                .map(pr -> new Candidate(new ComposerSuggestion(pr.number(), pr.title(), pr.htmlUrl(), pr.author(), pr.updatedAt()),
                        command == Command.BACKPORT ? Optional.ofNullable(pr.mergedAt()).orElse(pr.updatedAt()) : pr.createdAt()))
                .collect(toImmutableList());
        return new CandidatePage(candidates, result.items().stream().map(FetchedPullRequest::updatedAt).min(Comparator.naturalOrder()), result.hasNext());
    }

    public enum Command
    {
        BACKPORT,
        FIX,
        REVIEW
    }

    private record CacheKey(String accountScope, long repositoryId, String login, Command command, int page) {}

    private record Candidate(ComposerSuggestion suggestion, Instant sortAt) {}

    private record CandidatePage(List<Candidate> items, Optional<Instant> remainingBefore, boolean hasNext)
    {
        private CandidatePage
        {
            items = ImmutableList.copyOf(items);
        }
    }
}
