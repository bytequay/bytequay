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

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskNotFoundException;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TaskSummary;
import com.bytequay.app.git.GitBrowseService;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.repository.RepositoryEnrichmentService;
import com.bytequay.app.review.ReviewScopeFiles;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.workspace.WorkspaceIssueService;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.context.ContextMatch;
import com.bytequay.core.context.RepositoryNotebook;
import com.bytequay.core.domain.CommitEnrichment;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.RepositoryIssueDetail;
import com.bytequay.core.domain.RepositoryIssuePage;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.domain.StoredPrDiff;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewInvalidRequestException;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.review.ReviewSummary;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.PrDiffStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.bytequay.app.context.ContextText.excerpt;
import static com.bytequay.app.context.RepositoryContextMatcher.matchesTerm;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;
import static java.util.function.BinaryOperator.minBy;
import static java.util.function.Function.identity;

/** Local notebook and history selection. No credentials, remote clients, refreshers, or model calls belong here. */
@Service
public class RepositoryContextReader
{
    private static final int SOURCE_EXCERPT_BYTES = 1536;
    private static final int MATCH_TEXT_BYTES = 256 * 1024;
    private static final int PR_DESCRIPTION_BYTES = 128 * 1024;
    private static final int COMMIT_MESSAGE_BYTES = 1000;
    private static final int MAX_MATCHING_PATHS = 10_000;
    private static final int MAX_MATCHING_PATH_BYTES = 1024 * 1024;
    private static final int MAX_DIRECT_REFERENCES = 10;
    private static final int MAX_HISTORY_COMMITS = 50;
    private static final int PR_SEARCH_PAGE_SIZE = 20;
    private static final int ISSUE_SEARCH_PAGE_SIZE = 100;
    private static final int RECENT_REVIEW_RUNS = 10;
    private static final int RECENT_TASKS = 10;
    private static final TopKSelector CURRENT_COMMIT_TEXT = new TopKSelector(100);
    private static final TopKSelector SCOPE_TERMS = new TopKSelector(20);
    private static final TopKSelector PR_SEARCH_TERMS = new TopKSelector(5);
    private static final TopKSelector RELATED_ITEMS = new TopKSelector(3);
    private static final TopKSelector HISTORY_PATHS = new TopKSelector(20);
    private static final TopKSelector ACTIVITY_PREVIEWS = new TopKSelector(2);
    private static final TopKSelector DIFF_PREVIEW_PATHS = new TopKSelector(5);
    private static final TopKSelector DIRECT_TASK_REFERENCES = new TopKSelector(10);
    private static final Pattern REFERENCES = Pattern.compile(
            "https://github\\.com/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)/(pull|issues)/([1-9][0-9]{0,9})\\b|(?<![\\p{L}\\p{N}_/])#([1-9][0-9]{0,9})\\b");
    private static final Pattern TASK_REFERENCES = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}_])task:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})(?![\\p{L}\\p{N}_-])");

    private final RepositoryContextMatcher matcher;
    private final GitBrowseService browse;
    private final GitCommandRunner git;
    private final RepositoryEnrichmentService enrichment;
    private final PullRequestService pullRequests;
    private final PrDetailStore details;
    private final PrDiffStore diffs;
    private final WorkspaceIssueService issues;
    private final AgentReviewStore reviews;
    private final AgentTaskService tasks;

    public RepositoryContextReader(
            RepositoryContextMatcher matcher, GitBrowseService browse, GitCommandRunner git, RepositoryEnrichmentService enrichment,
            PullRequestService pullRequests, PrDetailStore details, PrDiffStore diffs, WorkspaceIssueService issues, AgentReviewStore reviews, AgentTaskService tasks)
    {
        this.matcher = requireNonNull(matcher, "matcher is null");
        this.browse = requireNonNull(browse, "browse is null");
        this.git = requireNonNull(git, "git is null");
        this.enrichment = requireNonNull(enrichment, "enrichment is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.details = requireNonNull(details, "details is null");
        this.diffs = requireNonNull(diffs, "diffs is null");
        this.issues = requireNonNull(issues, "issues is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
    }

    /**
     * Captures a bounded local selection against frozen review identities.
     *
     * @param review owning review, resolved by the application
     * @param run newly frozen run, with no dependency on an accepted provider turn
     * @param workspace verified managed repository and monitored upstream routing
     * @param previous previous accepted run used by the existing incremental scope rules
     * @param notebook one immutable local notebook read; no notebook mutation occurs
     * @return notes, up to three PRs, three issues, and three combined task/review excerpts, with bounds and gaps
     * @throws IllegalArgumentException when ownership or source routing does not match
     *
     * A missing cache entry remains a gap. Selection never fetches a page, resolves a missing
     * commit association remotely, or treats a parsed commit-title suffix as a verified PR mapping.
     */
    public Selection select(AgentReview review, ReviewRun run, WorkspaceRepositoryContext workspace, Optional<ReviewRun> previous, RepositoryNotebook notebook)
    {
        checkArgument(review.id().equals(run.reviewId()), "Run belongs to another review");
        checkArgument(review.workspaceId().equals(workspace.workspaceId()), "Review belongs to another workspace");
        checkArgument(review.repo().equalsIgnoreCase(workspace.monitoredRepository().fullName()), "Review repository differs from monitored repository");
        ImmutableList.Builder<String> gaps = ImmutableList.builder();
        Optional<String> baseline = baseline(workspace, gaps);
        List<String> paths = matchingPaths(workspace, run, previous, gaps);
        Optional<PullRequest> current = pullRequests.findStored(review.repo(), review.number());
        Optional<StoredPrDetail> currentDetail = current.flatMap(row -> details.find(row.id()));
        StringBuilder text = new StringBuilder(current.map(PullRequest::title).orElse(""));
        String body = currentDetail.map(detail -> Optional.ofNullable(detail.raw().body()).orElse("")).orElse("");
        text.append('\n').append(excerpt(body, PR_DESCRIPTION_BYTES));
        CURRENT_COMMIT_TEXT.select(run.commits().stream()).forEach(commit -> text.append('\n').append(excerpt(commit.message(), COMMIT_MESSAGE_BYTES)));
        if (currentDetail.isEmpty()) {
            gaps.add("The current PR description is not cached; text matching uses available title and commit messages.");
        }
        if (run.commits().size() > CURRENT_COMMIT_TEXT.limit() || ContextLimits.bytes(body) > PR_DESCRIPTION_BYTES
                || CURRENT_COMMIT_TEXT.select(run.commits().stream()).anyMatch(commit -> ContextLimits.bytes(commit.message()) > COMMIT_MESSAGE_BYTES)) {
            gaps.add("Text matching is bounded to the PR description preview and the first " + CURRENT_COMMIT_TEXT.limit() + " commit messages.");
        }
        String taskText = matchingText(text.toString(), "Current PR text", gaps);
        RepositoryContextMatcher.Selection notes = matcher.select(notebook, paths, taskText, ImmutableList.of());
        List<String> terms = SCOPE_TERMS.select(notes.scopes().stream().map(scope -> scope.scope().match())
                .filter(ContextMatch.Selected.class::isInstance).map(ContextMatch.Selected.class::cast)
                .flatMap(match -> match.terms().stream()).distinct()).collect(toImmutableList());
        ImmutableList.Builder<PrCandidate> prCandidates = ImmutableList.builder();
        ImmutableMap.Builder<Integer, RepositoryIssue> directIssues = ImmutableMap.builder();
        directReferences(review, workspace, taskText, prCandidates, directIssues, gaps);
        baseline.ifPresent(sha -> history(workspace, sha, paths, notes, prCandidates, gaps));
        for (String term : PR_SEARCH_TERMS.select(terms)) {
            pullRequests.listPullRequests(workspace.monitoredRepository(), 0, PR_SEARCH_PAGE_SIZE, term).pullRequests().stream()
                    .filter(row -> row.number() != review.number() && row.repo().equalsIgnoreCase(review.repo()))
                    .filter(row -> matchesTerm(matchingText(row.title(), "PR #" + row.number() + " title", gaps), term))
                    .forEach(row -> prCandidates.add(new PrCandidate(
                            row.number(), row.title(), 3, "Configured term in cached PR title: " + term, Optional.empty(), row.updatedAt())));
        }
        if (!terms.isEmpty()) {
            gaps.add("PR title discovery checks at most " + PR_SEARCH_TERMS.limit() + " configured terms and " + PR_SEARCH_PAGE_SIZE
                    + " local results per term; expired or uncached PRs are not searched.");
        }
        Map<Integer, PrCandidate> prs = prCandidates.build().stream().collect(toImmutableMap(PrCandidate::number, identity(), minBy(prOrder())));
        ImmutableList.Builder<Related> related = ImmutableList.builder();
        RELATED_ITEMS.select(prs.values().stream().filter(candidate -> candidate.number() != review.number()), prOrder())
                .map(candidate -> pullRequest(workspace, run, candidate, gaps)).forEach(related::add);
        if (prs.size() > RELATED_ITEMS.limit()) {
            gaps.add("Only " + RELATED_ITEMS.limit() + " related PR previews are selected; additional candidates remain outside this packet.");
        }
        related.addAll(issuePreviews(workspace, terms, directIssues.buildOrThrow(), gaps));
        related.addAll(historyPreviews(review, run, workspace, paths, terms, taskText, gaps));
        gaps.add("Selected notebook claims have not had their source versions checked against this PR; verify original sources before relying on them.");
        gaps.add("Local preparation does not refresh sources; selected PR, issue and registered documentation reads use the existing review tools.");
        gaps.add("Shared preparation, generated knowledge and automatic context delivery remain unavailable.");
        return new Selection(baseline, notes, related.build(), gaps.build());
    }

    private List<String> matchingPaths(WorkspaceRepositoryContext workspace, ReviewRun run, Optional<ReviewRun> previous, ImmutableList.Builder<String> gaps)
    {
        List<String> discovered;
        try {
            discovered = ReviewScopeFiles.matching(git, workspace.repository(), run, previous);
        }
        catch (ReviewInvalidRequestException failure) {
            gaps.add("Frozen matching paths are unavailable or truncated; path matching and history were skipped, while repository and text matching remain available.");
            return ImmutableList.of();
        }
        List<String> selected = new ArrayList<>();
        int bytes = 0;
        for (String path : discovered) {
            try {
                ContextLimits.pathPrefix(path + "/");
            }
            catch (IllegalArgumentException failure) {
                gaps.add("Some Git paths are outside the supported context path grammar and were excluded from path matching and history.");
                continue;
            }
            int pathBytes = ContextLimits.bytes(path);
            if (selected.size() >= MAX_MATCHING_PATHS || bytes + pathBytes > MAX_MATCHING_PATH_BYTES) {
                gaps.add("Matching paths exceed the " + MAX_MATCHING_PATHS + "-path or " + MAX_MATCHING_PATH_BYTES / 1024
                        + " KiB UTF-8 budget; only complete supported paths within the budget were considered.");
                break;
            }
            selected.add(path);
            bytes += pathBytes;
        }
        return ImmutableList.copyOf(selected);
    }

    private Optional<String> baseline(WorkspaceRepositoryContext workspace, ImmutableList.Builder<String> gaps)
    {
        String ref = "refs/remotes/" + workspace.sourceRemote() + "/" + workspace.defaultBranch() + "^{commit}";
        GitCommandRunner.Result result = git.run(workspace.repository(), "rev-parse", "--verify", "--end-of-options", ref);
        if (result.successful() && !result.truncated() && result.output().strip().matches("[0-9a-f]{40}|[0-9a-f]{64}")) {
            return Optional.of(result.output().strip());
        }
        gaps.add("The monitored default-branch commit is unavailable locally; baseline history was not searched and no fetch was started.");
        return Optional.empty();
    }

    private void directReferences(
            AgentReview review, WorkspaceRepositoryContext workspace, String text, ImmutableList.Builder<PrCandidate> prs,
            ImmutableMap.Builder<Integer, RepositoryIssue> selectedIssues, ImmutableList.Builder<String> gaps)
    {
        Matcher references = REFERENCES.matcher(text);
        Set<Integer> seen = new LinkedHashSet<>();
        while (references.find() && seen.size() < MAX_DIRECT_REFERENCES) {
            if (references.group(1) != null && !references.group(1).equalsIgnoreCase(review.repo())) {
                gaps.add("A cross-repository reference was excluded from this workspace's local lookup.");
                continue;
            }
            long parsed = Long.parseLong(references.group(4) == null ? references.group(3) : references.group(4));
            if (parsed > Integer.MAX_VALUE || !seen.add((int) parsed) || parsed == review.number()) {
                continue;
            }
            int number = (int) parsed;
            Optional<PullRequest> pr = pullRequests.findStored(review.repo(), number);
            if (pr.isPresent()) {
                prs.add(new PrCandidate(number, pr.orElseThrow().title(), 0, "Explicit reference in the reviewed PR text", Optional.empty(), pr.orElseThrow().updatedAt()));
                continue;
            }
            issues.find(workspace.workspaceId(), number).ifPresentOrElse(issue -> selectedIssues.put(number, issue),
                    () -> gaps.add("Reference #" + number + " is not in the local PR/open-issue cache; it requires a selected source read."));
        }
        if (seen.size() == MAX_DIRECT_REFERENCES) {
            gaps.add("Direct reference lookup is limited to " + MAX_DIRECT_REFERENCES + " distinct PR or issue numbers.");
        }
    }

    private void history(
            WorkspaceRepositoryContext workspace, String baseline, List<String> paths,
            RepositoryContextMatcher.Selection notes, ImmutableList.Builder<PrCandidate> prs, ImmutableList.Builder<String> gaps)
    {
        if (paths.isEmpty()) {
            return;
        }
        List<String> selectedPaths = HISTORY_PATHS.select(paths.stream().distinct()).collect(toImmutableList());
        if (paths.size() > selectedPaths.size()) {
            gaps.add("Local history uses the first " + HISTORY_PATHS.limit()
                    + " supported matching paths; additional supported paths were still considered for notebook matching.");
        }
        try {
            List<GitBrowseService.Commit> exact = browse.log(workspace.repository(), baseline, selectedPaths, MAX_HISTORY_COMMITS);
            joinHistory(workspace, exact, 1, "Local commit history touches a required review path", prs, gaps);
            if (exact.size() < MAX_HISTORY_COMMITS && RELATED_ITEMS.select(prs.build().stream().map(PrCandidate::number).distinct()).count() < RELATED_ITEMS.limit()) {
                List<String> components = HISTORY_PATHS.select(notes.scopes().stream().map(match -> match.scope().match())
                        .filter(ContextMatch.Selected.class::isInstance).map(ContextMatch.Selected.class::cast)
                        .flatMap(match -> match.pathPrefixes().stream()).distinct()).collect(toImmutableList());
                if (!components.isEmpty()) {
                    joinHistory(workspace, browse.log(workspace.repository(), baseline, components, MAX_HISTORY_COMMITS - exact.size()),
                            2, "Local commit history touches a selected component", prs, gaps);
                }
            }
            gaps.add("History searches at most " + MAX_HISTORY_COMMITS + " local commits after path filtering; multi-path rename following is not exhaustive.");
        }
        catch (RuntimeException failure) {
            gaps.add("Local path history is unavailable or truncated; cached text and direct references remain available.");
        }
    }

    private void joinHistory(
            WorkspaceRepositoryContext workspace, List<GitBrowseService.Commit> commits, int rank, String reason,
            ImmutableList.Builder<PrCandidate> prs, ImmutableList.Builder<String> gaps)
    {
        if (commits.isEmpty()) {
            return;
        }
        long repositoryId = repositoryId(workspace);
        Set<String> selected = new LinkedHashSet<>(commits.stream().map(GitBrowseService.Commit::sha).toList());
        Map<String, CommitEnrichment> mappings = new LinkedHashMap<>();
        enrichment.findCommits(repositoryId, selected).commits().stream()
                .filter(mapping -> mapping.repositoryId() == repositoryId && selected.contains(mapping.sha()))
                .forEach(mapping -> mappings.put(mapping.sha(), mapping));
        int missing = 0;
        for (GitBrowseService.Commit commit : commits) {
            Optional<CommitEnrichment> mapping = Optional.ofNullable(mappings.get(commit.sha()));
            if (mapping.flatMap(CommitEnrichment::pullRequestNumber).isEmpty()) {
                missing++;
                continue;
            }
            CommitEnrichment known = mapping.orElseThrow();
            prs.add(new PrCandidate(known.pullRequestNumber().orElseThrow(), known.pullRequestTitle().orElse("Associated pull request"),
                    rank, reason + " (" + commit.sha() + "); association is stored for repository " + repositoryId,
                    Optional.of(commit.sha()), Instant.parse(commit.committedAt())));
        }
        if (missing > 0) {
            gaps.add(missing + " inspected commits have no stored PR association; commit-title suffixes were not accepted as mappings.");
        }
    }

    private Related pullRequest(WorkspaceRepositoryContext workspace, ReviewRun run, PrCandidate candidate, ImmutableList.Builder<String> gaps)
    {
        String repo = workspace.monitoredRepository().fullName();
        Optional<PullRequest> row = pullRequests.findStored(repo, candidate.number());
        StringBuilder text = new StringBuilder(candidate.title());
        StringBuilder provenance = new StringBuilder("ByteQuay local PR cache for ").append(repo).append('#').append(candidate.number());
        row.ifPresentOrElse(value -> {
            text.append("\nCached state: ").append(value.state()).append("; updated: ").append(value.updatedAt());
            provenance.append("; row updated ").append(value.updatedAt());
            Optional<StoredPrDetail> detail = details.find(value.id());
            detail.ifPresentOrElse(saved -> {
                text.append("\nDescription:\n").append(excerpt(Optional.ofNullable(saved.raw().body()).orElse(""), 700));
                ACTIVITY_PREVIEWS.select(saved.reviews().stream()).forEach(item -> text.append("\nReview ").append(item.state()).append(" by ").append(item.login())
                        .append(": ").append(excerpt(Optional.ofNullable(item.body()).orElse(""), 250)));
                ACTIVITY_PREVIEWS.select(saved.timeline().stream().filter(item -> item.body() != null && !item.body().isBlank()))
                        .forEach(item -> text.append("\nDiscussion ").append(item.actor()).append(": ").append(excerpt(item.body(), 250)));
                if (!saved.unavailableSections().isEmpty()) {
                    gaps.add("PR #" + candidate.number() + " has unavailable cached sections: " + String.join(", ", saved.unavailableSections()));
                }
                if (saved.raw().updatedAt() == null || saved.raw().updatedAt().isBefore(value.updatedAt())) {
                    gaps.add("PR #" + candidate.number() + " detail may lag its cached row; current state has not been refreshed by selection.");
                }
            }, () -> gaps.add("PR #" + candidate.number() + " has no cached detail or discussion."));
            Optional<StoredPrDiff> diff = diffs.find(value.id(), null, null);
            diff.ifPresentOrElse(saved -> text.append("\nCached default diff paths: ")
                            .append(String.join(", ", DIFF_PREVIEW_PATHS.select(saved.files().stream()).map(file -> file.filename()).collect(toImmutableList()))),
                    () -> gaps.add("PR #" + candidate.number() + " has no cached default diff."));
        }, () -> gaps.add("PR #" + candidate.number() + " is known only through its saved commit association; its detail is not cached."));
        Optional<Boolean> present = candidate.commit().flatMap(sha -> ancestry(workspace, sha, run.mergeBaseSha()));
        candidate.commit().ifPresent(sha -> provenance.append("; ancestry concerns associated history commit ").append(sha)
                .append(" only, not every change in the final PR; checked against frozen diff base ").append(run.mergeBaseSha())
                .append("; reachability does not establish that the reviewed head preserves the change"));
        provenance.append("; bounded description/review/path previews, not a complete final-diff or merge-rationale account");
        return new Related("pr:" + repositoryId(workspace) + ":" + candidate.number(), repo + "#" + candidate.number() + ": " + excerpt(candidate.title(), 150),
                excerpt(text.toString(), SOURCE_EXCERPT_BYTES), provenance.toString(), ImmutableList.of(candidate.reason()), present);
    }

    private List<Related> issuePreviews(
            WorkspaceRepositoryContext workspace, List<String> terms, Map<Integer, RepositoryIssue> direct, ImmutableList.Builder<String> gaps)
    {
        Map<Integer, RepositoryIssue> candidates = new LinkedHashMap<>(direct);
        if (!terms.isEmpty()) {
            RepositoryIssuePage page = issues.list(workspace.workspaceId(), 0, ISSUE_SEARCH_PAGE_SIZE);
            checkArgument(page.repositoryId() == repositoryId(workspace) && page.repository().equals(workspace.monitoredRepository()), "Issue page belongs to another repository");
            page.issues().stream().filter(issue -> {
                String text = matchingText(issue.title() + " " + issue.body() + " " + String.join(" ", issue.labels()), "Issue #" + issue.number() + " matching text", gaps);
                return terms.stream().anyMatch(term -> matchesTerm(text, term));
            })
                    .sorted(Comparator.comparing(RepositoryIssue::updatedAt).reversed().thenComparingInt(RepositoryIssue::number))
                    .forEach(issue -> candidates.putIfAbsent(issue.number(), issue));
            gaps.add("Issue discovery checks at most " + ISSUE_SEARCH_PAGE_SIZE + " cached open issues; closed issues and older pages are not searched. Last sync: "
                    + page.syncedAt().map(Instant::toString).orElse("unknown") + ".");
        }
        if (candidates.size() > RELATED_ITEMS.limit()) {
            gaps.add("Only " + RELATED_ITEMS.limit() + " related issue previews are selected.");
        }
        return RELATED_ITEMS.select(candidates.values().stream(), Comparator.<RepositoryIssue>comparingInt(issue -> direct.containsKey(issue.number()) ? 0 : 1)
                .thenComparing(RepositoryIssue::updatedAt, Comparator.reverseOrder()).thenComparingInt(RepositoryIssue::number)).map(issue -> {
                    RepositoryIssueDetail detail = issues.detail(workspace.workspaceId(), issue.number());
                    checkArgument(detail.repositoryId() == repositoryId(workspace) && detail.issueNumber() == issue.number(), "Issue detail identity differs");
                    String text = excerpt(issue.body(), 1000) + "\nCached activity: "
                            + ACTIVITY_PREVIEWS.select(detail.timeline().stream().filter(item -> item.body() != null))
                                    .map(item -> excerpt(item.body(), 200)).reduce("", (left, right) -> left + "\n" + right);
                    if (detail.refreshedAt().isEmpty() || detail.refreshedAt().orElseThrow().isBefore(issue.updatedAt())) {
                        gaps.add("Issue #" + issue.number() + " activity is missing or may be stale; selection did not refresh it.");
                    }
                    String provenance = "ByteQuay cached open-issue projection; row updated " + issue.updatedAt()
                            + "; activity refreshed " + detail.refreshedAt().map(Instant::toString).orElse("unknown") + "; bounded excerpt, completeness unverified";
                    String reason = direct.containsKey(issue.number()) ? "Explicit reference in the reviewed PR text" : "Configured term in cached issue title, body or labels";
                    return new Related("issue:" + repositoryId(workspace) + ":" + issue.number(), workspace.monitoredRepository().fullName() + "#" + issue.number() + ": "
                            + excerpt(issue.title(), 150), excerpt(text, SOURCE_EXCERPT_BYTES), provenance, ImmutableList.of(reason), Optional.empty());
                }).collect(toImmutableList());
    }

    private List<Related> historyPreviews(
            AgentReview review, ReviewRun run, WorkspaceRepositoryContext workspace, List<String> paths, List<String> terms,
            String taskText, ImmutableList.Builder<String> gaps)
    {
        ImmutableList.Builder<HistoryCandidate> candidates = ImmutableList.builder();
        for (ReviewRun previous : reviews.listRecentRuns(workspace.workspaceId(), review.repo(), RECENT_REVIEW_RUNS)) {
            if (previous.id().equals(run.id()) || previous.summary().isEmpty()) {
                continue;
            }
            ReviewSummary summary = previous.summary().orElseThrow();
            boolean overlap = previous.diff().stream().anyMatch(file -> paths.contains(file.filename()));
            String text = matchingText(summary.purpose() + " " + summary.combinedResult(), "Previous review " + previous.id() + " matching text", gaps);
            if (!overlap && terms.stream().noneMatch(term -> matchesTerm(text, term))) {
                continue;
            }
            Related related = new Related("review:" + previous.id(), "Previous review " + previous.reviewId() + ", run " + previous.ordinal(),
                    excerpt(summary.purpose() + "\n" + summary.combinedResult(), SOURCE_EXCERPT_BYTES),
                    "Retained agent conclusion, not verified fact; state " + previous.state() + "; head " + previous.headSha() + "; updated " + previous.updatedAt(),
                    ImmutableList.of(overlap ? "Previous review has an exact changed-path overlap" : "Configured term in a previous review conclusion"), Optional.empty());
            candidates.add(new HistoryCandidate(related, overlap ? 1 : 3, previous.updatedAt()));
        }
        candidates.addAll(taskPreviews(review, workspace, terms, taskText, gaps));
        gaps.add("Previous-review discovery checks " + RECENT_REVIEW_RUNS + " recent runs in this workspace and monitored repository.");
        gaps.add("At most " + RELATED_ITEMS.limit() + " task/review excerpts are selected together; other candidates remain outside this packet.");
        return RELATED_ITEMS.select(candidates.build().stream(), Comparator.comparingInt(HistoryCandidate::rank)
                        .thenComparing(HistoryCandidate::recency, Comparator.reverseOrder()).thenComparing(candidate -> candidate.related().id()))
                .map(HistoryCandidate::related).collect(toImmutableList());
    }

    private List<HistoryCandidate> taskPreviews(
            AgentReview review, WorkspaceRepositoryContext workspace, List<String> terms, String taskText, ImmutableList.Builder<String> gaps)
    {
        RepositoryIdentity repository = RepositoryIdentity.valueOf(workspace.selectedRepository().fullName());
        List<TaskId> references = DIRECT_TASK_REFERENCES.select(TASK_REFERENCES.matcher(taskText).results()
                .map(match -> TaskId.valueOf(match.group(1))).distinct()).collect(toImmutableList());
        if (references.size() == DIRECT_TASK_REFERENCES.limit()) {
            gaps.add("Explicit task lookup is limited to " + DIRECT_TASK_REFERENCES.limit() + " distinct task identifiers.");
        }
        Map<TaskId, AgentTask> candidates = new LinkedHashMap<>();
        for (TaskId id : references) {
            if (id.toString().equals(review.taskId())) {
                continue;
            }
            try {
                candidates.put(id, tasks.getInteractive(workspace.workspaceId(), id));
            }
            catch (AgentTaskNotFoundException failure) {
                gaps.add("Task reference " + id + " is unavailable in this workspace's interactive task history.");
            }
        }
        if (!terms.isEmpty()) {
            tasks.listCompleted(workspace.workspaceId(), repository, RECENT_TASKS).forEach(task -> candidates.putIfAbsent(task.id(), task));
            gaps.add("Task discovery checks " + RECENT_TASKS + " recent completed interactive tasks in this workspace and selected repository " + repository
                    + "; older unreferenced tasks and open-task learning reports are not searched.");
        }
        ImmutableList.Builder<HistoryCandidate> result = ImmutableList.builder();
        for (AgentTask task : candidates.values()) {
            if (task.id().toString().equals(review.taskId())) {
                continue;
            }
            if (!task.workspaceId().equals(Optional.of(workspace.workspaceId())) || !task.repository().toString().equalsIgnoreCase(repository.toString())
                    || !task.interactive() || task.state() != TaskState.COMPLETED || task.summary().isEmpty()) {
                gaps.add("A task candidate was excluded because it is not a completed interactive task in this workspace and selected repository.");
                continue;
            }
            TaskSummary summary = task.summary().orElseThrow();
            String conclusion = summary.finalResponse() + summary.notes().map(notes -> "\n" + notes).orElse("");
            if (conclusion.isBlank()) {
                gaps.add("Task " + task.id() + " has no retained conclusion text.");
                continue;
            }
            boolean direct = references.contains(task.id());
            String text = matchingText(task.title() + "\n" + conclusion, "Task " + task.id() + " matching text", gaps);
            if (!direct && terms.stream().noneMatch(term -> matchesTerm(text, term))) {
                continue;
            }
            if (reviews.findReviewByTask(task.id().toString()).isPresent()) {
                gaps.add("A review-owned task summary was omitted to avoid repeating review history; only the bounded review-run search applies to it.");
                continue;
            }
            Related related = new Related("task:" + task.id(), "Previous task: " + excerpt(task.title(), 150), excerpt(conclusion, SOURCE_EXCERPT_BYTES),
                    "Retained agent conclusion, not verified fact; task " + task.id() + "; task version " + task.version()
                            + "; workspace " + workspace.workspaceId() + "; selected repository " + repository
                            + "; state " + task.state() + "; updated " + task.updatedAt() + "; recorded head " + summary.worktree().map(value -> value.head()).orElse("unknown")
                            + "; changed paths and applicability to the reviewed code are unverified; bounded summary only",
                    ImmutableList.of(direct ? "Explicit task reference in the reviewed PR text" : "Configured term in a retained task title or conclusion"), Optional.empty());
            result.add(new HistoryCandidate(related, direct ? 0 : 3, task.updatedAt()));
        }
        return result.build();
    }

    private Optional<Boolean> ancestry(WorkspaceRepositoryContext workspace, String commit, String base)
    {
        GitCommandRunner.Result result = git.run(workspace.repository(), "merge-base", "--is-ancestor", commit, base);
        if (result.truncated() || (result.exitCode() != 0 && result.exitCode() != 1)) {
            return Optional.empty();
        }
        return Optional.of(result.successful());
    }

    private static Comparator<PrCandidate> prOrder()
    {
        return Comparator.comparingInt(PrCandidate::rank).thenComparing(PrCandidate::recency, Comparator.reverseOrder()).thenComparingInt(PrCandidate::number);
    }

    private static String matchingText(String text, String label, ImmutableList.Builder<String> gaps)
    {
        if (ContextLimits.bytes(text) > MATCH_TEXT_BYTES) {
            gaps.add(label + " exceeds the local matching text budget; only its UTF-8 preview was searched.");
        }
        return excerpt(text, MATCH_TEXT_BYTES);
    }

    static long repositoryId(WorkspaceRepositoryContext workspace)
    {
        return workspace.selectedRepository().equals(workspace.monitoredRepository()) ? workspace.selectedRepositoryId() : workspace.upstreamRepositoryId().orElseThrow();
    }

    public record Selection(Optional<String> baselineSha, RepositoryContextMatcher.Selection notes, List<Related> related, List<String> gaps)
    {
        public Selection
        {
            related = ImmutableList.copyOf(related);
            gaps = ImmutableList.copyOf(new LinkedHashSet<>(gaps));
        }
    }

    public record Related(String id, String title, String excerpt, String provenance, List<String> matchReasons, Optional<Boolean> presentInReviewedBase)
    {
        public Related
        {
            matchReasons = ImmutableList.copyOf(matchReasons);
        }
    }

    private record PrCandidate(int number, String title, int rank, String reason, Optional<String> commit, Instant recency) {}

    private record HistoryCandidate(Related related, int rank, Instant recency) {}
}
