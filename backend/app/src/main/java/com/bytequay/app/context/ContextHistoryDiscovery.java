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
package com.bytequay.app.context;

import com.bytequay.app.context.ContextHistoryReader.SourceKind;
import com.bytequay.app.git.GitBrowseService;
import com.bytequay.app.repository.RepositoryEnrichmentService;
import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.repository.github.GitHubSearchResponse;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.context.ContextMatch;
import com.bytequay.core.context.ContextScope;
import com.bytequay.core.domain.CommitEnrichment;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestPage;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.store.PrDetailStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.bytequay.app.context.RepositoryContextMatcher.matchesTerm;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;
import static java.util.function.Function.identity;

/** Bounded suggestions for an explicit Learn request. Discovery never hydrates sources or writes knowledge. */
@Service
public class ContextHistoryDiscovery
{
    private static final TopKSelector PREFIXES = new TopKSelector(20);
    private static final TopKSelector COMMITS = new TopKSelector(50);
    private static final TopKSelector ROWS = new TopKSelector(20);
    private static final TopKSelector CANDIDATES = new TopKSelector(5);
    private final GitBrowseService git;
    private final RepositoryEnrichmentService enrichment;
    private final PullRequestService pullRequests;
    private final PrDetailStore details;
    private final GitHubRepoClient remote;
    private final CredentialService credentials;

    public ContextHistoryDiscovery(GitBrowseService git, RepositoryEnrichmentService enrichment, PullRequestService pullRequests,
            PrDetailStore details, GitHubRepoClient remote, CredentialService credentials)
    {
        this.git = requireNonNull(git, "git is null");
        this.enrichment = requireNonNull(enrichment, "enrichment is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.details = requireNonNull(details, "details is null");
        this.remote = requireNonNull(remote, "remote is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
    }

    public Result discover(WorkspaceRepositoryContext workspace, String baselineSha, List<ContextScope> scopes, String query, boolean includeRemote)
    {
        requireNonNull(workspace, "workspace is null");
        checkArgument(baselineSha != null && baselineSha.matches("[0-9a-f]{40}|[0-9a-f]{64}"), "A frozen baseline commit is required");
        List<ContextScope> selected = ImmutableList.copyOf(scopes);
        checkArgument(!selected.isEmpty() && selected.size() <= 100 && selected.stream().allMatch(ContextScope::enabled), "Select enabled history scopes");
        requireNonNull(query, "query is null");
        checkArgument(ContextLimits.bytes(query) <= 200 && query.matches("[\\p{L}\\p{N} _./-]*"), "History query must contain at most 200 UTF-8 bytes of plain words");
        String search = query.strip();
        boolean project = selected.stream().anyMatch(scope -> scope.match() instanceof ContextMatch.Repository);
        List<String> prefixes = PREFIXES.select(selected.stream().map(ContextScope::match).filter(ContextMatch.Selected.class::isInstance)
                .map(ContextMatch.Selected.class::cast).flatMap(match -> match.pathPrefixes().stream()).distinct()).collect(toImmutableList());
        List<String> terms = PREFIXES.select(selected.stream().map(ContextScope::match).filter(ContextMatch.Selected.class::isInstance)
                .map(ContextMatch.Selected.class::cast).flatMap(match -> match.terms().stream()).distinct()).collect(toImmutableList());
        List<String> gaps = new ArrayList<>();
        gaps.add("Discovery inspects at most 50 local commits, 20 path prefixes, 50 associated PR rows and one 20-row cached PR page; omitted history may be relevant.");
        Map<Integer, Association> associations = associations(workspace, baselineSha, prefixes, gaps);
        Map<Integer, PullRequest> rows = new LinkedHashMap<>();
        try {
            PullRequestPage page = pullRequests.listPullRequests(workspace.monitoredRepository(), 0, ROWS.limit(), "");
            ROWS.select(page.pullRequests().stream()).forEach(row -> rows.putIfAbsent(row.number(), row));
            if (page.hasMore()) {
                gaps.add("The local PR list has additional pages outside this discovery window.");
            }
            associations.keySet().forEach(number -> pullRequests.findStored(workspace.monitoredRepository().fullName(), number)
                    .ifPresent(row -> rows.putIfAbsent(number, row)));
        }
        catch (RuntimeException failure) {
            gaps.add("Some cached PR rows could not be read; discovery retained the local rows already inspected.");
        }
        Map<Integer, Ranked> prs = new LinkedHashMap<>();
        Set<Integer> inspectedPrs = new LinkedHashSet<>();
        for (PullRequest row : rows.values()) {
            inspectedPrs.add(row.number());
            local(workspace, row, Optional.ofNullable(associations.get(row.number())), prefixes, terms, search, project, gaps)
                    .ifPresent(candidate -> prs.put(row.number(), candidate));
        }
        Map<Integer, Ranked> issues = new LinkedHashMap<>();
        Set<Integer> inspectedIssues = new LinkedHashSet<>();
        if (includeRemote) {
            try {
                String token = credentials.resolveGitHubToken();
                String remoteTerms = search.isEmpty() ? terms.stream().filter(term -> ContextLimits.bytes(term) <= 200 && term.matches("[\\p{L}\\p{N} _./-]+"))
                        .findFirst().orElse("") : search;
                if (prs.size() < CANDIDATES.limit()) {
                    searchRemote(workspace, token, SourceKind.PR, remoteTerms, prefixes, terms, search, project, prs, inspectedPrs, gaps);
                }
                searchRemote(workspace, token, SourceKind.ISSUE, remoteTerms, prefixes, terms, search, project, issues, inspectedIssues, gaps);
            }
            catch (RuntimeException failure) {
                gaps.add("Explicit remote discovery could not obtain GitHub access; local candidates remain available.");
            }
        }
        else {
            gaps.add("Remote discovery was not selected. The open-issue cache contains no closed-issue candidate list.");
        }
        gaps.add("Merge and closure are historical observations, not proof of a fix or current applicability; selected sources are verified again before learning.");
        List<Candidate> candidates = ImmutableList.<Candidate>builder().addAll(select(prs)).addAll(select(issues)).build();
        return new Result(candidates, gaps, inspectedPrs.size(), inspectedIssues.size());
    }

    private Map<Integer, Association> associations(WorkspaceRepositoryContext workspace, String baseline, List<String> prefixes, List<String> gaps)
    {
        try {
            List<GitBrowseService.Commit> commits = COMMITS.select(git.localLog(workspace.repository(), baseline, prefixes, COMMITS.limit()).stream())
                    .collect(toImmutableList());
            if (commits.isEmpty()) {
                return ImmutableMap.of();
            }
            Map<String, GitBrowseService.Commit> bySha = commits.stream().collect(ImmutableMap.toImmutableMap(GitBrowseService.Commit::sha, identity(), (first, ignored) -> first));
            long repositoryId = RepositoryContextReader.repositoryId(workspace);
            Map<Integer, Association> selected = new LinkedHashMap<>();
            List<CommitEnrichment> mappings = enrichment.findCommits(repositoryId, bySha.keySet()).commits();
            for (CommitEnrichment mapping : mappings) {
                if (mapping.repositoryId() != repositoryId || !bySha.containsKey(mapping.sha())) {
                    gaps.add("A cached commit association with a different repository or commit identity was excluded.");
                    continue;
                }
                mapping.pullRequestNumber().ifPresent(number -> {
                    GitBrowseService.Commit commit = bySha.get(mapping.sha());
                    String reason = prefixes.isEmpty() ? "Stored association from recent monitored-baseline commit " : "Stored association from a commit touching selected paths: ";
                    selected.putIfAbsent(number, new Association(reason + mapping.sha(), ContextText.excerpt(commit.title() + "\n" + commit.body(), 2000)));
                });
            }
            if (mappings.size() < commits.size()) {
                gaps.add("Some local commits have no cached PR association; discovery did not fetch or refresh those mappings.");
            }
            return selected;
        }
        catch (RuntimeException failure) {
            gaps.add("Local baseline commit history or its stored associations were unavailable; cached PR text remains usable.");
            return ImmutableMap.of();
        }
    }

    private Optional<Ranked> local(WorkspaceRepositoryContext workspace, PullRequest row, Optional<Association> association,
            List<String> prefixes, List<String> terms, String query, boolean project, List<String> gaps)
    {
        RepoRef repo = workspace.monitoredRepository();
        if (!row.repo().equalsIgnoreCase(repo.fullName()) || !webIdentity(row.htmlUrl(), repo, SourceKind.PR, row.number())) {
            gaps.add("A cached PR outside the monitored repository or GitHub host was excluded.");
            return Optional.empty();
        }
        Optional<StoredPrDetail> cached;
        try {
            cached = details.find(row.id()).filter(value -> webIdentity(value.raw().htmlUrl(), repo, SourceKind.PR, row.number()));
        }
        catch (RuntimeException failure) {
            cached = Optional.empty();
            gaps.add("A cached PR description could not be read; title and stored association matching were retained.");
        }
        Instant mergedAt = row.mergedAt() == null ? cachedMerge(row, cached) : row.mergedAt();
        if (mergedAt == null) {
            return Optional.empty();
        }
        Instant updatedAt = cached.map(value -> value.raw().updatedAt()).filter(value -> value.isAfter(row.updatedAt())).orElse(row.updatedAt());
        String text = row.title() + "\n" + cached.map(value -> ContextText.excerpt(Optional.ofNullable(value.raw().body()).orElse(""), 8192)).orElse("")
                + "\n" + association.map(Association::text).orElse("");
        List<String> reasons = reasons(text, prefixes, terms, query, project, association);
        if (reasons.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Ranked(new Candidate(SourceKind.PR, row.number(), ContextText.excerpt(row.title(), 500), row.htmlUrl(), "MERGED", "MERGED",
                updatedAt, reasons, "Local stored PR row" + (cached.isPresent() ? " and cached description" : "") + "; mergedAt=" + mergedAt
                        + "; freshness and complete discussion are unverified."), association.isPresent() ? 0 : 1));
    }

    private static Instant cachedMerge(PullRequest row, Optional<StoredPrDetail> detail)
    {
        return detail.filter(value -> value.raw().updatedAt() != null && !value.raw().updatedAt().isBefore(row.updatedAt()))
                .map(value -> value.raw().mergedAt()).orElse(null);
    }

    private void searchRemote(WorkspaceRepositoryContext workspace, String token, SourceKind kind, String remoteTerms, List<String> prefixes,
            List<String> terms, String query, boolean project, Map<Integer, Ranked> candidates, Set<Integer> inspected, List<String> gaps)
    {
        try {
            GitHubSearchResponse response = remote.searchHistory(token, workspace.monitoredRepository(), kind == SourceKind.PR, remoteTerms);
            List<GitHubSearchResponse.Item> rows = Optional.ofNullable(response.items()).orElse(ImmutableList.of());
            if (!Boolean.FALSE.equals(response.incompleteResults())) {
                gaps.add(kind + " search reported incomplete results or omitted its completeness flag.");
            }
            if (response.totalCount() > rows.size() || rows.size() >= ROWS.limit()) {
                gaps.add(kind + " discovery used only the first 20-result search page; additional history may exist.");
            }
            for (GitHubSearchResponse.Item item : ROWS.select(rows)) {
                inspected.add(item.number());
                if (!remoteIdentity(item, workspace.monitoredRepository(), kind)) {
                    gaps.add(kind + " search returned an invalid repository, host, state or source kind; that row was excluded.");
                    continue;
                }
                String text = item.title() + "\n" + ContextText.excerpt(Optional.ofNullable(item.body()).orElse(""), 8192);
                List<String> reasons = reasons(text, prefixes, terms, query, project, Optional.empty());
                if (reasons.isEmpty()) {
                    continue;
                }
                String outcome = kind == SourceKind.PR ? "MERGED" : switch (Optional.ofNullable(item.stateReason()).orElse("").toLowerCase(Locale.ROOT)) {
                    case "completed" -> "COMPLETED";
                    case "not_planned" -> "NOT_PLANNED";
                    default -> "UNKNOWN";
                };
                String provenance = "Explicit GitHub search; repository=" + workspace.monitoredRepository().fullName() + "; first page ordered by updated time; "
                        + (kind == SourceKind.PR ? "mergedAt=" + item.pullRequest().mergedAt() : "closedAt=" + item.closedAt() + "; stateReason="
                                + ContextText.excerpt(Optional.ofNullable(item.stateReason()).orElse("unknown"), 100)) + "; full history and applicability unverified.";
                Ranked candidate = new Ranked(new Candidate(kind, item.number(), ContextText.excerpt(item.title(), 500), item.htmlUrl(),
                        kind == SourceKind.PR ? "MERGED" : "CLOSED", outcome, item.updatedAt(), reasons, provenance), 1);
                candidates.putIfAbsent(item.number(), candidate);
            }
        }
        catch (RuntimeException failure) {
            gaps.add(kind + " remote discovery failed; existing local candidates were retained and no empty-history conclusion was recorded.");
        }
    }

    private static List<String> reasons(String text, List<String> prefixes, List<String> terms, String query, boolean project, Optional<Association> association)
    {
        if (!query.isEmpty() && !Arrays.stream(query.split(" +")).allMatch(word -> matchesTerm(text, word))) {
            return ImmutableList.of();
        }
        ImmutableList.Builder<String> reasons = ImmutableList.builder();
        Optional<String> matchingTerm = PREFIXES.select(terms.stream().filter(term -> matchesTerm(text, term))).findFirst();
        Optional<String> matchingPath = PREFIXES.select(prefixes.stream().filter(prefix -> text.toLowerCase(Locale.ROOT).contains(prefix.toLowerCase(Locale.ROOT)))).findFirst();
        if (!project && prefixes.isEmpty() && query.isEmpty() && matchingTerm.isEmpty()) {
            return ImmutableList.of();
        }
        association.ifPresent(value -> reasons.add(value.reason()));
        if (!query.isEmpty()) {
            reasons.add("Title, cached description or associated commit matches the requested words: " + query);
        }
        matchingTerm.ifPresent(term -> reasons.add("Matches selected scope term: " + ContextText.excerpt(term, 300)));
        matchingPath.ifPresent(prefix -> reasons.add("Historical text names selected path: " + ContextText.excerpt(prefix, 300)));
        List<String> matched = reasons.build();
        return matched.isEmpty() && project
                ? ImmutableList.of("Recent repository history considered for the selected Project overview; topical applicability is unverified.") : matched;
    }

    private static boolean remoteIdentity(GitHubSearchResponse.Item item, RepoRef repo, SourceKind kind)
    {
        return item.number() > 0 && item.title() != null && !item.title().isBlank() && item.updatedAt() != null
                && "closed".equalsIgnoreCase(item.state()) && apiIdentity(item.repositoryUrl(), repo)
                && webIdentity(item.htmlUrl(), repo, kind, item.number())
                && (kind == SourceKind.PR ? item.pullRequest() != null && item.pullRequest().mergedAt() != null : item.pullRequest() == null);
    }

    private static boolean apiIdentity(String url, RepoRef repo)
    {
        return urlIdentity(url, "api.github.com", "/repos/" + repo.fullName());
    }

    private static boolean webIdentity(String url, RepoRef repo, SourceKind kind, int number)
    {
        return urlIdentity(url, "github.com", "/" + repo.fullName() + (kind == SourceKind.PR ? "/pull/" : "/issues/") + number);
    }

    private static boolean urlIdentity(String url, String host, String path)
    {
        try {
            URI uri = URI.create(requireNonNull(url, "source URL is null"));
            return "https".equalsIgnoreCase(uri.getScheme()) && host.equalsIgnoreCase(uri.getHost()) && (uri.getPort() == -1 || uri.getPort() == 443)
                    && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null && path.equalsIgnoreCase(uri.getRawPath());
        }
        catch (IllegalArgumentException | NullPointerException ignored) {
            return false;
        }
    }

    private static List<Candidate> select(Map<Integer, Ranked> candidates)
    {
        return CANDIDATES.select(candidates.values().stream(), Comparator.comparingInt(Ranked::rank)
                        .thenComparing(value -> value.candidate().updatedAt(), Comparator.reverseOrder())
                        .thenComparing(value -> value.candidate().number(), Comparator.reverseOrder()))
                .map(Ranked::candidate).collect(toImmutableList());
    }

    public record Candidate(SourceKind kind, int number, String title, String url, String state, String outcome, Instant updatedAt, List<String> reasons, String provenance)
    {
        public Candidate
        {
            reasons = ImmutableList.copyOf(reasons);
        }
    }

    public record Result(List<Candidate> candidates, List<String> gaps, int inspectedPullRequests, int inspectedIssues)
    {
        public Result
        {
            candidates = ImmutableList.copyOf(candidates);
            gaps = new TopKSelector(20).select(gaps.stream().distinct()).map(gap -> ContextText.excerpt(gap, 1000)).collect(toImmutableList());
        }
    }

    private record Association(String reason, String text) {}

    private record Ranked(Candidate candidate, int rank) {}
}
