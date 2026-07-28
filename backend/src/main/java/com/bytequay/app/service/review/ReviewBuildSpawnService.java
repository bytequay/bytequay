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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.ReviewFinding;
import com.bytequay.app.domain.ReviewFindingSeverity;
import com.bytequay.app.domain.ReviewFindingStatus;
import com.bytequay.app.domain.ReviewPass;
import com.bytequay.app.domain.ReviewPhase;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.domain.Workspace;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.ReviewStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.threads.ThreadService;
import com.bytequay.app.service.workspaces.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.bytequay.app.utils.PullRequestRefUtil.parseRef;
import static java.util.Objects.requireNonNull;

/**
 * The "→ Spawn build thread" handoff: a TERMINATE-d review pass spins up
 * a BUILD thread pre-seeded with its AGREED findings, keyed off PR
 * authorship.
 *
 * <ul>
 *   <li><b>author-is-reviewer</b> (you authored the PR) — the build
 *       thread fetches {@code pr.head} locally and edits it; pushes go
 *       through the standard parked-publish gate.</li>
 *   <li><b>suggested-change</b> (someone else's PR) — no checkout; the
 *       build thread proposes GitHub suggested-change comments, still
 *       through {@code PublishService}.</li>
 * </ul>
 *
 * <p>The pass is never auto-closed: it stays TERMINATE until every
 * AGREED finding is resolved (by the {@code ReviewPassResolver} when the
 * spawned work ships) or dropped by the user. One spawn per pass.
 */
@Service
public class ReviewBuildSpawnService
{
    private static final Logger log = LoggerFactory.getLogger(ReviewBuildSpawnService.class);

    /** "[reviewer-label] body" prefix that disputed/debated findings
     *  carry; lets us recover the source reviewer for the opening turn. */
    private static final Pattern REPORTER_PREFIX = Pattern.compile("^\\[([^\\]]+)\\]\\s*(.*)$", Pattern.DOTALL);

    public static final String MODE_AUTHOR = "author_is_reviewer";
    public static final String MODE_SUGGESTED = "suggested_change";

    private final ReviewStore reviewStore;
    private final PullRequestRepository pullRequests;
    private final PatResolver patResolver;
    private final WorkspaceService workspaceService;
    private final WatchedRepoStore watchedRepos;
    private final GitRunner git;
    private final ReviewBuildSpawnCommitter committer;

    public ReviewBuildSpawnService(
            ReviewStore reviewStore,
            PullRequestRepository pullRequests,
            PatResolver patResolver,
            WorkspaceService workspaceService,
            WatchedRepoStore watchedRepos,
            GitRunner git,
            ReviewBuildSpawnCommitter committer)
    {
        this.reviewStore = requireNonNull(reviewStore, "reviewStore is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.patResolver = requireNonNull(patResolver, "patResolver is null");
        this.workspaceService = requireNonNull(workspaceService, "workspaceService is null");
        this.watchedRepos = requireNonNull(watchedRepos, "watchedRepos is null");
        this.git = requireNonNull(git, "git is null");
        this.committer = requireNonNull(committer, "committer is null");
    }

    /** Result of a spawn: the new build thread, its active task (null
     *  until the trunk materialises one), and the resolved mode. */
    public record BuildSpawn(String threadId, String taskId, String mode) {}

    public BuildSpawn spawn(String passId, String workspaceId, String openingTitle)
    {
        return spawn(passId, workspaceId, openingTitle, null);
    }

    public BuildSpawn spawn(
            String passId,
            String workspaceId,
            String openingTitle,
            List<String> selectedFindingIds)
    {
        requireNonNull(passId, "passId is null");
        ReviewPass pass = reviewStore.findPassById(passId)
                .orElseThrow(() -> status(404, "no review pass: " + passId));
        if (pass.phase() != ReviewPhase.TERMINATE) {
            throw status(409, "pass " + passId + " is not TERMINATE (phase " + pass.phase() + ")");
        }
        if (pass.spawnedBuildThreadId() != null) {
            throw status(409, "pass " + passId + " already spawned build thread "
                    + pass.spawnedBuildThreadId());
        }

        // Gate: at least one AGREED finding at severity >= MAJOR.
        List<ReviewFinding> eligible = reviewStore.listFindingsForPass(passId).stream()
                .filter(f -> f.status() == ReviewFindingStatus.AGREED)
                .filter(f -> f.severity() == ReviewFindingSeverity.BLOCKER
                        || f.severity() == ReviewFindingSeverity.MAJOR)
                .sorted(Comparator
                        .comparingInt((ReviewFinding finding) ->
                                severityWeight(finding.severity())).reversed()
                        .thenComparing(ReviewFinding::id))
                .toList();
        if (eligible.isEmpty()) {
            throw status(422, "no_eligible_findings");
        }
        List<ReviewFinding> selected = select(eligible, selectedFindingIds);

        String repo = pass.repoFullName();
        int prNumber = pass.prNumber();
        PullRequestRef ref = parseRef(repo, prNumber);
        String pat = patResolver.resolve(repo);
        PullRequest pr = pullRequests.getPullRequest(pat, ref);
        PrRawDetail raw = pullRequests.fetchPrDetail(pat, ref);
        if (pass.headSha() == null || pass.headSha().isBlank()
                || raw.headSha() == null
                || !pass.headSha().equals(raw.headSha())) {
            throw status(409, "review_head_moved");
        }
        String currentLogin = pullRequests.fetchUserProfile(pat).login();
        boolean authorIsReviewer = pr.author() != null && currentLogin != null
                && pr.author().equalsIgnoreCase(currentLogin);
        String mode = authorIsReviewer ? MODE_AUTHOR : MODE_SUGGESTED;

        String ws = resolveWorkspace(workspaceId, repo);

        // author-mode: best-effort pre-fetch of pr.head so the branch is
        // available locally for the trunk to cut a task worktree off.
        if (authorIsReviewer) {
            Path clone = resolveClonePath(repo);
            if (clone != null) {
                try {
                    git.fetchPrRefs(clone, prNumber, raw.baseRef());
                }
                catch (Exception e) {
                    log.warn("Pre-fetch of PR #{} head into {} failed (the build agent can fetch "
                            + "it itself): {}", prNumber, clone, e.getMessage());
                }
            }
        }

        String opening = renderOpeningTurn(
                prNumber, pr.title(), selected, mode, raw.headRef());
        String title = openingTitle == null || openingTitle.isBlank()
                ? "Fix review findings on PR #" + prNumber
                : openingTitle.strip();
        Path clonePath = resolveClonePath(repo);

        ThreadService.NewTaskRequest request = new ThreadService.NewTaskRequest(
                ThreadKind.CLI_AGENT,
                /* provider */ null,
                /* model */ null,
                title,
                /* workingDir */ clonePath == null ? null : clonePath.toString(),
                /* branchName */ null,
                /* initialPrompt */ opening,
                /* initialGroupIds */ List.of(),
                /* taskType */ null,
                /* linkedPrNumber */ prNumber,
                /* linkedIssueNumber */ null,
                ThreadFlow.BUILD,
                ws,
                /* workModel */ null);
        Thread thread = committer.commit(request, pass, selected, Instant.now());

        // A freshly spawned build thread is 0-task — its first task
        // materialises later — so there's no task id to report yet.
        String taskId = null;
        log.info("Spawned build thread {} ({}) from review pass {} on PR {}#{}",
                thread.id(), mode, passId, repo, prNumber);
        return new BuildSpawn(thread.id(), taskId, mode);
    }

    private static List<ReviewFinding> select(
            List<ReviewFinding> eligible, List<String> requestedIds)
    {
        if (requestedIds == null) {
            return eligible;
        }
        if (requestedIds.isEmpty()) {
            throw status(422, "invalid_selected_findings");
        }
        Set<String> requested = new HashSet<>();
        for (String id : requestedIds) {
            if (id == null || id.isBlank() || !requested.add(id)) {
                throw status(422, "invalid_selected_findings");
            }
        }
        List<ReviewFinding> selected = eligible.stream()
                .filter(finding -> requested.contains(finding.id()))
                .toList();
        if (selected.size() != requested.size()) {
            throw status(422, "selected_finding_is_not_eligible");
        }
        return selected;
    }

    /** Render the structured AGREED-findings block — the spawned trunk's
     *  opening turn. Deterministic (severity desc, then id asc) so the
     *  same inputs produce byte-identical text. */
    String renderOpeningTurn(int prNumber, String prTitle, List<ReviewFinding> eligible, String mode, String headRef)
    {
        String branch = headRef == null || headRef.isBlank() ? "the PR head branch" : headRef;
        String modeSuffix = MODE_AUTHOR.equals(mode)
                ? ""
                : " (or as suggested-change comments on PR #" + prNumber + " if you can't push)";
        // Deterministic order: severity desc, then id asc — so the same
        // findings always render byte-identically.
        List<ReviewFinding> ordered = eligible.stream()
                .sorted(Comparator
                        .comparingInt((ReviewFinding f) -> severityWeight(f.severity())).reversed()
                        .thenComparing(ReviewFinding::id))
                .toList();
        StringBuilder sb = new StringBuilder();
        sb.append("Review of PR #").append(prNumber)
                .append(" (").append(prTitle == null ? "" : prTitle).append(") terminated with the ")
                .append("following AGREED findings (").append(ordered.size())
                .append(", all severity >= MAJOR):\n\n");
        int n = 1;
        for (ReviewFinding f : ordered) {
            String reporter = reporterOf(f);
            String summary = summaryOf(f);
            sb.append("  ").append(n++).append(". [")
                    .append(f.severity().dbValue().toUpperCase(Locale.ROOT)).append("] ")
                    .append(f.path() == null ? "(whole PR)" : f.path());
            if (f.line() != null) {
                sb.append(":").append(f.line());
            }
            sb.append(" — ").append(summary).append('\n');
            sb.append("     Source: @").append(reporter);
            if ("converged".equals(f.debateStatus())) {
                sb.append(" (debate converged)");
            }
            sb.append(" · #finding-").append(f.id()).append('\n');
        }
        sb.append("\nAddress them on `").append(branch).append('`').append(modeSuffix)
                .append(". Split into separate Tasks if it helps; the trunk plans, the Tasks do.\n");
        return sb.toString();
    }

    private static String reporterOf(ReviewFinding f)
    {
        Matcher m = REPORTER_PREFIX.matcher(f.body() == null ? "" : f.body());
        return m.matches() ? m.group(1) : "panel";
    }

    private static String summaryOf(ReviewFinding f)
    {
        String body = f.body() == null ? "" : f.body();
        Matcher m = REPORTER_PREFIX.matcher(body);
        return (m.matches() ? m.group(2) : body).strip();
    }

    private String resolveWorkspace(String workspaceId, String repo)
    {
        if (workspaceId != null && !workspaceId.isBlank() && workspaceWatches(workspaceId, repo)) {
            return workspaceId;
        }
        List<Workspace> candidates = workspaceService.list().stream()
                .filter(w -> workspaceWatches(w.id(), repo))
                .toList();
        if (candidates.isEmpty()) {
            throw status(422, "no_workspace_for_repo");
        }
        if (candidates.size() > 1) {
            throw status(422, "ambiguous_workspace_picker_required");
        }
        return candidates.get(0).id();
    }

    private boolean workspaceWatches(String workspaceId, String repo)
    {
        return workspaceService.listRepos(workspaceId).stream()
                .anyMatch(r -> r.repoFullName().equalsIgnoreCase(repo));
    }

    private Path resolveClonePath(String repo)
    {
        String[] parts = repo.split("/", 2);
        if (parts.length != 2) {
            return null;
        }
        return watchedRepos.find(parts[0], parts[1])
                .map(WatchedRepo::localClonePath)
                .filter(p -> p != null && !p.isBlank())
                .map(Path::of)
                .orElse(null);
    }

    private static int severityWeight(ReviewFindingSeverity s)
    {
        return switch (s) {
            case BLOCKER -> 4;
            case MAJOR -> 3;
            case NIT -> 2;
            case QUESTION -> 1;
        };
    }

    private static ResponseStatusException status(int code, String message)
    {
        return new ResponseStatusException(HttpStatusCode.valueOf(code), message);
    }
}
