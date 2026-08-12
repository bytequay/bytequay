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
package com.bytequay.app.service.teams;

import com.bytequay.app.config.AsyncConfig;
import com.bytequay.app.domain.HandledAction;
import com.bytequay.app.domain.MyPrColumn;
import com.bytequay.app.domain.PrViewState;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.Team;
import com.bytequay.app.domain.TeamSummary;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.GitHubPullRequestReadRepository;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.repository.sqlite.PrViewStateStore;
import com.bytequay.app.repository.sqlite.TeamStore;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.pr.PullRequestService;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Service
public class TeamService
{
    private static final Logger log = LoggerFactory.getLogger(TeamService.class);

    // GitHub search OR's repeated `author:` qualifiers but caps the URL at
    // 256 chars. base="is:pr is:open repo:owner/repo" ≈ 35 chars; each
    // " author:login" is ~7 + login_length chars. 10 authors at avg 12-char
    // logins ≈ 35 + 10*19 = 225 chars — comfortable headroom. Larger chunks
    // mean fewer round-trips per repo.
    private static final int AUTHOR_CHUNK_SIZE = 10;

    /** TTL for the cached merged team-pulls fan-out. Keeps per-column
     *  pagination cheap: the first hit pays the full GitHub round-trip,
     *  subsequent column-page requests within this window are O(1) cache
     *  lookups + slice. The "↻ Refresh" button passes force=true to
     *  bypass and re-fan-out. */
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final TeamStore teamStore;
    private final PullRequestService pullRequestService;
    private final WatchedRepoStore watchedRepoStore;
    private final GitHubPullRequestReadRepository gitHub;
    /** Local view-state (viewedAt / reviewedAt / handledAction) per PR id.
     *  GitHub-search results don't carry it, so we overlay it here so the
     *  team kanban can categorize cards based on whether the user has
     *  already opened / reviewed / dismissed them. */
    private final PrViewStateStore viewStateStore;
    /** Virtual-thread executor for fanning out the per-(repo × chunk)
     *  searches in parallel — sequential they used to dominate the
     *  team-detail page latency for any team larger than 5. */
    private final Executor ioExecutor;
    private final PatResolver patResolver;
    /** Per-team in-memory TTL cache keyed on (teamId, watched-repos
     *  fingerprint). Stores the merged + view-state-overlaid PR list so
     *  per-column pagination can serve from memory without re-running
     *  the GitHub fan-out for each "+ N more" click. */
    private final Map<Long, CachedTeamPulls> teamPullsCache = new ConcurrentHashMap<>();

    public TeamService(
            TeamStore teamStore,
            PullRequestService pullRequestService,
            WatchedRepoStore watchedRepoStore,
            GitHubPullRequestReadRepository gitHub,
            PrViewStateStore viewStateStore,
            @Qualifier(AsyncConfig.IO_EXECUTOR) Executor ioExecutor,
            PatResolver patResolver)
    {
        this.teamStore = requireNonNull(teamStore, "teamStore is null");
        this.pullRequestService = requireNonNull(pullRequestService, "pullRequestService is null");
        this.watchedRepoStore = requireNonNull(watchedRepoStore, "watchedRepoStore is null");
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.viewStateStore = requireNonNull(viewStateStore, "viewStateStore is null");
        this.ioExecutor = requireNonNull(ioExecutor, "ioExecutor is null");
        this.patResolver = requireNonNull(patResolver, "patResolver is null");
    }

    public List<TeamSummary> listSummaries()
    {
        // The inbox count next to each team chip on the home page intentionally
        // uses the *local* PR table — fast, cache-friendly, and correct for the
        // common case where the user is the author or a requested reviewer. PRs
        // by team members in watched repos that the user is not on may be
        // missed (the team detail page does the proper search-per-repo fetch).
        List<PullRequest> inboxPrs = pullRequestService.listPullRequests().stream()
                .filter(p -> p.handledAction() != HandledAction.MERGED
                          && p.handledAction() != HandledAction.DISMISSED
                          && p.handledAction() != HandledAction.MANUAL)
                .collect(toImmutableList());

        return teamStore.findAll().stream()
                .map(t -> {
                    Set<String> members = t.members();
                    int inbox = (int) inboxPrs.stream()
                            .filter(p -> p.author() != null && members.contains(p.author().toLowerCase(Locale.ROOT)))
                            .count();
                    return new TeamSummary(t.id(), t.name(), t.avatar(), t.color(), t.description(), members.size(), inbox);
                })
                .collect(toImmutableList());
    }

    public Team get(long id)
    {
        return teamStore.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatusCode.valueOf(404), "team " + id + " not found"));
    }

    public Team create(String name, String avatar, String color, String description, Set<String> members)
    {
        return teamStore.create(name, avatar, color, description, members);
    }

    public Team update(long id, String name, String avatar, String color, String description)
    {
        return teamStore.update(id, name, avatar, color, description);
    }

    public Team replaceMembers(long id, Set<String> members)
    {
        return teamStore.replaceMembers(id, members);
    }

    public void delete(long id)
    {
        teamStore.delete(id);
    }

    /**
     * Returns open PRs in the user's watched repos authored by any team
     * member. Hits GitHub directly per watched repo with author qualifiers
     * because the local {@code pull_requests} table only holds the user's own
     * + review-requested PRs — a PR by a team member the user is not on
     * would never appear.
     *
     * <p>Per-repo PAT overrides are honoured via {@link PatResolver},
     * which the service injects itself rather than asking the caller
     * to supply credentials.
     */
    public List<PullRequest> listPullRequestsForTeam(long id)
    {
        Team team = get(id);
        Set<String> members = team.members();
        if (members.isEmpty()) {
            return ImmutableList.of();
        }
        List<WatchedRepo> watched = watchedRepoStore.findAll();
        if (watched.isEmpty()) {
            return ImmutableList.of();
        }

        List<List<String>> authorChunks = Lists.partition(ImmutableList.copyOf(members), AUTHOR_CHUNK_SIZE);

        // Fan every (repo × author-chunk) search out onto the virtual-thread
        // executor and join. Previously this loop was sequential so latency
        // grew linearly with watched-repo count × chunk count — a 10-repo /
        // 10-member team did 20 round-trips back to back. Virtual threads
        // make the parallel form essentially free.
        record Job(WatchedRepo repo, List<String> chunk, CompletableFuture<List<PullRequest>> future) {}
        List<Job> jobs = new ArrayList<>();
        for (WatchedRepo repo : watched) {
            String repoFullName = repo.owner() + "/" + repo.repo();
            String pat;
            try {
                pat = patResolver.resolve(repoFullName);
            }
            catch (ResponseStatusException e) {
                // No PAT for this repo — skip it rather than failing the
                // whole team fan-out. Matches the prior shape where
                // patForRepo could return null/blank.
                continue;
            }
            for (List<String> chunk : authorChunks) {
                String query = buildSearchQuery(repoFullName, chunk);
                CompletableFuture<List<PullRequest>> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        return gitHub.searchPullRequests(pat, query);
                    }
                    catch (Exception e) {
                        // Don't let one failing repo blow up the whole list — log
                        // and treat as no results so the rest can render.
                        log.warn("team-search failed for {} (chunk size {}): {}", repoFullName, chunk.size(), e.getMessage());
                        return ImmutableList.of();
                    }
                }, ioExecutor);
                jobs.add(new Job(repo, chunk, future));
            }
        }

        // Dedupe across the (repo × author-chunk) Cartesian product since a
        // roster that spans two chunks can return the same PR twice.
        // LinkedHashMap + iteration order = jobs.add order preserves the
        // watched-repo ordering for first-seen rows.
        LinkedHashMap<Long, PullRequest> merged = Maps.newLinkedHashMap();
        for (Job job : jobs) {
            for (PullRequest pr : job.future().join()) {
                merged.putIfAbsent(pr.id(), pr);
            }
        }

        // Overlay local view-state so the frontend kanban can demote PRs
        // the user has already opened/reviewed/dismissed. Without this,
        // viewedAt / reviewedAt / handledAction are always null on team
        // PRs and a "Mark handled" click never sticks past the next refresh.
        Map<Long, PrViewState> states = viewStateStore.findAll();
        return merged.values().stream()
                .map(pr -> applyViewState(pr, states.get(pr.id())))
                .collect(toImmutableList());
    }

    private static PullRequest applyViewState(PullRequest pr, PrViewState state)
    {
        if (state == null) {
            return pr;
        }
        return new PullRequest(
                pr.id(), pr.repo(), pr.number(), pr.title(), pr.author(), pr.htmlUrl(),
                pr.createdAt(), pr.updatedAt(), pr.origin(), pr.labels(), pr.labelColors(),
                pr.draft(),
                state.viewedAt(),
                state.reviewedAt(),
                state.handledAction(),
                pr.requestedReviewers(),
                pr.ciStatus(), pr.additions(), pr.deletions(), pr.commentCount(),
                pr.attentionReason(),
                pr.state(), pr.closedAt(), pr.mergedAt(),
                pr.mergeable(), pr.mergeableState(),
                pr.headPushedAt(), pr.reviewerVerdicts(),
                state.snoozedUntil(), state.snoozeWakeReason(),
                pr.headRef());
    }

    static String buildSearchQuery(String repoFullName, List<String> authors)
    {
        StringBuilder sb = new StringBuilder("is:pr is:open repo:").append(repoFullName);
        for (String login : authors) {
            sb.append(" author:").append(login);
        }
        return sb.toString();
    }

    /**
     * Total number of merged PRs authored by team members in the user's
     * watched repos within the last {@code days} days. Powers the
     * "Merged this week" stat on the team home page.
     *
     * <p>The team kanban path's {@link #listPullRequestsForTeam} only
     * surfaces currently-open PRs (the GitHub search uses
     * {@code is:open}), so the categorizer's {@code RECENTLY_MERGED}
     * column ends up almost always empty in practice. This method runs
     * a separate {@code is:merged merged:>{date}} fan-out and reads the
     * server-reported {@code totalCount} on each search response —
     * cheaper than fetching the actual PR rows since we only need the
     * count.
     *
     * <p>Caching lives on the frontend (10-minute TTL keyed by team).
     * No backend cache here; with that much frontend-side throttling
     * the upstream load is already minimal.
     */
    public int countMergedRecently(long teamId, int days)
    {
        if (days <= 0) {
            return 0;
        }
        Team team = get(teamId);
        Set<String> members = team.members();
        if (members.isEmpty()) {
            return 0;
        }
        List<WatchedRepo> watched = watchedRepoStore.findAll();
        if (watched.isEmpty()) {
            return 0;
        }

        // GitHub's `merged:>YYYY-MM-DD` filter is exclusive, so passing
        // (today - days) returns PRs merged on any day strictly after
        // that date — i.e. within the last `days` days, day-precision.
        String since = LocalDate.now(ZoneOffset.UTC).minusDays(days).toString();
        List<List<String>> authorChunks = Lists.partition(ImmutableList.copyOf(members), AUTHOR_CHUNK_SIZE);

        List<CompletableFuture<Integer>> jobs = new ArrayList<>();
        for (WatchedRepo repo : watched) {
            String repoFullName = repo.owner() + "/" + repo.repo();
            String pat;
            try {
                pat = patResolver.resolve(repoFullName);
            }
            catch (ResponseStatusException e) {
                continue;
            }
            for (List<String> chunk : authorChunks) {
                String query = buildMergedSearchQuery(repoFullName, chunk, since);
                jobs.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        // perPage=1 minimises payload — only totalCount
                        // is read, the items list is discarded.
                        return gitHub.searchPullRequestsPaged(pat, query, 1, 1).totalCount();
                    }
                    catch (Exception e) {
                        log.warn("merged-recently search failed for {} (chunk size {}): {}",
                                repoFullName, chunk.size(), e.getMessage());
                        return 0;
                    }
                }, ioExecutor));
            }
        }

        // Different author chunks within the same repo cover disjoint
        // author sets, so a PR (with exactly one author) appears in at
        // most one chunk and the sum is double-count free.
        int total = 0;
        for (CompletableFuture<Integer> job : jobs) {
            total += job.join();
        }
        return total;
    }

    static String buildMergedSearchQuery(String repoFullName, List<String> authors, String since)
    {
        StringBuilder sb = new StringBuilder("is:pr is:merged repo:")
                .append(repoFullName)
                .append(" merged:>")
                .append(since);
        for (String login : authors) {
            sb.append(" author:").append(login);
        }
        return sb.toString();
    }

    // ── Per-column pagination ──────────────────────────────────────────────────
    //
    // The team kanban groups PRs into 5 fixed columns (drafting / waiting on
    // review / needs changes / ready to merge / recently merged). Categorization
    // depends on v26 fields (mergeable, reviewerVerdicts, etc.) plus local
    // view-state, so it runs server-side here. The frontend asks for
    // "first N per column" on initial paint, then "next page of column X"
    // when the user clicks "+ N more". Both are served from the cached
    // fan-out so pagination never re-fans-out to GitHub.

    /** Page of one column for one team. */
    public record ColumnPage(MyPrColumn column, int total, int offset, List<PullRequest> items) {}

    /** Initial-paint payload: first N per column + per-column totals +
     *  per-repo totals across all columns. The frontend uses
     *  {@code repoTotals} to render the "All N · owner/repo N · …"
     *  chip row in the team-detail header. */
    public record TeamColumnsResponse(
            Map<MyPrColumn, List<PullRequest>> columns,
            Map<MyPrColumn, Integer> totals,
            Map<String, Integer> repoTotals) {}

    /**
     * Returns the first {@code perColumn} items for every column.
     * {@code force=true} bypasses the TTL cache and re-fans-out to GitHub
     * (wired to the frontend "↻ Refresh" button).
     */
    public TeamColumnsResponse listPullRequestsForTeamByColumn(
            long teamId,
            int perColumn,
            boolean force)
    {
        Map<MyPrColumn, List<PullRequest>> grouped = grouped(teamId, force);
        Map<MyPrColumn, List<PullRequest>> firstPage = new EnumMap<>(MyPrColumn.class);
        // Tally per-repo PR counts across every column so the team header
        // can render "owner/repo N" chips alongside the cumulative total.
        // LinkedHashMap preserves the order PRs are encountered, which —
        // since the columns iterate in their declared order — gives the
        // user a stable ordering even after pagination.
        Map<String, Integer> repoTotals = new LinkedHashMap<>();
        for (Map.Entry<MyPrColumn, List<PullRequest>> e : grouped.entrySet()) {
            List<PullRequest> col = e.getValue();
            firstPage.put(e.getKey(), col.subList(0, Math.min(perColumn, col.size())));
            for (PullRequest pr : col) {
                repoTotals.merge(pr.repo(), 1, Integer::sum);
            }
        }
        return new TeamColumnsResponse(firstPage, TeamPullCategorizer.counts(grouped), repoTotals);
    }

    /**
     * Returns one page (offset + limit) of the requested column. Always
     * served from cache when available; doesn't bypass even if force=true
     * because pagination clicks should never re-fan-out — that defeats
     * the point. If the cache has expired, it does fan out one more time
     * to refresh, but that's a side effect of the cache miss, not the
     * paginate call.
     */
    public ColumnPage listPullRequestsForTeamColumnPage(
            long teamId,
            MyPrColumn column,
            int offset,
            int limit)
    {
        Map<MyPrColumn, List<PullRequest>> grouped = grouped(teamId, /* force */ false);
        List<PullRequest> col = grouped.getOrDefault(column, ImmutableList.of());
        int from = Math.clamp(offset, 0, col.size());
        int to = Math.clamp(from + limit, from, col.size());
        return new ColumnPage(column, col.size(), from, ImmutableList.copyOf(col.subList(from, to)));
    }

    /** Internal: returns the grouped+sorted PR map for a team, served
     *  from the TTL cache when fresh. */
    private Map<MyPrColumn, List<PullRequest>> grouped(
            long teamId,
            boolean force)
    {
        CachedTeamPulls cached = teamPullsCache.get(teamId);
        if (!force && cached != null && cached.isValid()) {
            return cached.grouped();
        }
        Instant now = Instant.now();
        List<PullRequest> all = listPullRequestsForTeam(teamId);
        Map<MyPrColumn, List<PullRequest>> grouped = TeamPullCategorizer.groupAndSort(all, now);
        teamPullsCache.put(teamId, new CachedTeamPulls(grouped, now.plus(CACHE_TTL)));
        return grouped;
    }

    private record CachedTeamPulls(Map<MyPrColumn, List<PullRequest>> grouped, Instant expiresAt)
    {
        boolean isValid()
        {
            return Instant.now().isBefore(expiresAt);
        }
    }
}
