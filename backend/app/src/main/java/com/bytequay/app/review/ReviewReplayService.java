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
package com.bytequay.app.review;

import com.bytequay.app.context.ContextText;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.memory.MemoryService;
import com.bytequay.app.repository.github.GitHubHarvestClient;
import com.bytequay.app.repository.github.GitHubHarvestClient.PullReplay;
import com.bytequay.app.repository.github.GitHubHarvestClient.SubmittedReview;
import com.bytequay.app.repository.github.GitHubHarvestClient.ThreadStart;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewConflictException;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.review.ReviewInvalidRequestException;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.store.AppSettingsStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Striped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.function.UnaryOperator;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Holdout replays: Agent Review run twice on a merged pull request at the head its maintainers first reviewed, once with repository
 * memory and once without, next to the maintainers' review comments on lines. A comment counts as raised by a side when one of its
 * findings sits on the same file and side within a few lines, and the user can correct that. Replays start only on a click, are
 * never posted, and GitHub is only read.
 */
@Service
public class ReviewReplayService
{
    static final String STATE = "workspace.reviewReplays.";
    static final int MAX_REPLAYS = 20;
    static final int NEAR_LINES = 3;
    static final int MAX_COMMENT_BYTES = 600;
    private static final Logger log = LoggerFactory.getLogger(ReviewReplayService.class);

    private final GitHubHarvestClient github;
    private final CredentialService credentials;
    private final WorkspaceRepositoryService workspaces;
    private final AgentReviewService reviews;
    private final AgentReviewStore store;
    private final MemoryService memories;
    private final GitCommandRunner git;
    private final AppSettingsStore settings;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Striped<Lock> locks = Striped.lock(16);

    public ReviewReplayService(GitHubHarvestClient github, CredentialService credentials, WorkspaceRepositoryService workspaces, AgentReviewService reviews,
            AgentReviewStore store, MemoryService memories, GitCommandRunner git, AppSettingsStore settings, ObjectMapper mapper, Clock clock)
    {
        this.github = requireNonNull(github, "github is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.store = requireNonNull(store, "store is null");
        this.memories = requireNonNull(memories, "memories is null");
        this.git = requireNonNull(git, "git is null");
        this.settings = requireNonNull(settings, "settings is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public List<ReplayView> list(String workspaceId)
    {
        return replays(workspaceId).stream().map(this::view).collect(toImmutableList());
    }

    /**
     * Reads how the maintainers reviewed merged pull request {@code number}, finds the head of their first review in the workspace
     * repository, and starts the two reviews there. A head GitHub no longer has gives way to the next maintainer-reviewed head, then the
     * merged head, and the replay says so.
     */
    public ReplayView start(String workspaceId, String repo, int number)
    {
        WorkspaceRepositoryContext workspace = workspaces.requireReadyRepository(workspaceId);
        if (!workspace.monitoredRepository().fullName().equalsIgnoreCase(repo)) {
            throw new ReviewInvalidRequestException("Pull request " + repo + "#" + number + " does not belong to this workspace");
        }
        if (replays(workspaceId).stream().anyMatch(replay -> replay.number() == number && running(replay))) {
            throw new ReviewConflictException("A replay of #" + number + " is still running");
        }
        PullReplay pull = github.replay(credentials.resolveGitHubCredential().token(), workspace.monitoredRepository(), number)
                .orElseThrow(() -> new ReviewInvalidRequestException("Pull request #" + number + " is gone"));
        if (!pull.merged()) {
            throw new ReviewInvalidRequestException("Only a merged pull request can be replayed");
        }
        List<SubmittedReview> reviewed = pull.reviews().stream().filter(review -> maintainer(review.author(), review.bot(), review.maintainer(), pull))
                .sorted(Comparator.comparing(SubmittedReview::at)).collect(toImmutableList());
        List<ThreadStart> threads = pull.threads().stream()
                .filter(thread -> maintainer(thread.comment().author(), thread.comment().bot(), thread.comment().maintainer(), pull)).collect(toImmutableList());
        if (reviewed.isEmpty() || threads.isEmpty()) {
            throw new ReviewInvalidRequestException("No maintainer commented on a line of #" + number + ", so a replay has nothing to compare with");
        }
        SubmittedReview first = reviewed.getFirst();
        Path repository = workspace.repository();
        String remote = workspace.selectedRepository().equals(workspace.monitoredRepository()) ? "origin" : workspace.sourceRemote();
        Head head = head(repository, remote, number, reviewed);
        String base = landedOn(repository, remote, pull);
        String mergeBase = run(repository, "merge-base", head.sha(), base);
        if (mergeBase.equals(head.sha())) {
            throw new ReviewConflictException("Cannot find where #" + number + " branched from " + pull.baseRef());
        }
        String id = UUID.randomUUID().toString();
        int excluded = (int) memories.list(workspaceId).stream()
                .filter(memory -> !MemoryService.knownBefore(memory, new AgentReview.Replay(id, number, true, first.at()))).count();
        List<Comment> comments = threads.stream().map(thread -> comment(thread, head.sha())).collect(toImmutableList());
        List<Side> sides = new ArrayList<>();
        try {
            for (boolean memory : ImmutableList.of(true, false)) {
                String reviewId = reviews.startReplay(workspaceId, new AgentReviewService.ReplayStart(new AgentReview.Replay(id, number, memory, first.at()),
                        head.sha(), pull.baseRef(), mergeBase)).review().id();
                sides.add(new Side(memory, reviewId));
            }
        }
        catch (RuntimeException failure) {
            // One side alone compares nothing; stopping it lets it end like any finished replay.
            sides.forEach(side -> reviews.stop(side.reviewId()));
            throw failure;
        }
        Replay replay = new Replay(id, number, pull.title(), pull.url(), head.sha(), head.fallback(), first.at(), first.author(), excluded, comments, sides,
                ImmutableMap.of(), clock.instant());
        update(workspaceId, stored -> ImmutableList.<Replay>builder().add(replay).addAll(stored.stream().limit(MAX_REPLAYS - 1).iterator()).build());
        return view(replay);
    }

    /** The user's own call on whether one side raised one maintainer comment, which overrides the line match. */
    public ReplayView mark(String workspaceId, String replayId, String commentId, boolean memory, boolean raised)
    {
        Replay updated = update(workspaceId, stored -> stored.stream().map(replay -> {
            if (!replay.id().equals(replayId)) {
                return replay;
            }
            checkArgument(replay.comments().stream().anyMatch(comment -> comment.id().equals(commentId)), "Comment %s is not part of this replay", commentId);
            Map<String, Boolean> marks = new LinkedHashMap<>(replay.marks());
            marks.put(markKey(commentId, memory), raised);
            return replay.withMarks(marks);
        }).collect(toImmutableList())).stream().filter(replay -> replay.id().equals(replayId)).findFirst()
                .orElseThrow(() -> new ReviewInvalidRequestException("Replay " + replayId + " is gone"));
        return view(updated);
    }

    /** Forgets a finished replay, ending any of its reviews still open; refused while one runs, since Stop belongs to the review. */
    public void delete(String workspaceId, String replayId)
    {
        Replay replay = replays(workspaceId).stream().filter(value -> value.id().equals(replayId)).findFirst()
                .orElseThrow(() -> new ReviewInvalidRequestException("Replay " + replayId + " is gone"));
        if (running(replay)) {
            throw new ReviewConflictException("Stop the replay's running reviews before deleting it");
        }
        for (Side side : replay.sides()) {
            if (store.findReview(side.reviewId()).filter(review -> review.state() == AgentReview.State.OPEN).isPresent()) {
                reviews.end(side.reviewId());
            }
        }
        update(workspaceId, stored -> stored.stream().filter(value -> !value.id().equals(replayId)).collect(toImmutableList()));
    }

    private ReplayView view(Replay replay)
    {
        List<SideView> sides = replay.sides().stream().map(this::side).collect(toImmutableList());
        List<CommentView> comments = replay.comments().stream().map(comment -> new CommentView(comment.id(), comment.author(), comment.path(), comment.side(),
                comment.line(), comment.startLine(), comment.onReplayedHead(), comment.body(), comment.url(), comment.at(), sides.stream().map(side -> {
                    List<String> findings = side.findings().stream().filter(finding -> raises(finding, comment)).map(FindingView::id).collect(toImmutableList());
                    Optional<Boolean> marked = Optional.ofNullable(replay.marks().get(markKey(comment.id(), side.memory())));
                    return new Mark(side.memory(), findings, marked.orElse(!findings.isEmpty()), marked.filter(value -> value == findings.isEmpty()).isPresent());
                }).collect(toImmutableList()))).collect(toImmutableList());
        return new ReplayView(replay.id(), replay.number(), replay.title(), replay.url(), replay.head(), replay.fallback(), replay.cutoff(), replay.reviewer(),
                replay.excludedMemories(), replay.createdAt(), sides, comments);
    }

    private SideView side(Side side)
    {
        List<ReviewRun> runs = store.findReview(side.reviewId()).map(review -> store.listRuns(review.id())).orElse(ImmutableList.of());
        Optional<ReviewRun> run = runs.stream().filter(value -> !value.explanationOnly()).max(Comparator.comparingInt(ReviewRun::ordinal));
        boolean active = runs.stream().anyMatch(ReviewRun::active);
        String state = active ? "RUNNING" : run.map(value -> value.state() == ReviewRun.State.ACCEPTED ? "DONE" : value.state().name()).orElse("GONE");
        boolean waiting = active && reviews.get(side.reviewId()).pendingApprovals() > 0;
        List<FindingView> findings = store.findReview(side.reviewId()).isEmpty() ? ImmutableList.of() : store.listFindings(side.reviewId()).stream()
                .filter(finding -> finding.state() != ReviewFinding.State.WITHDRAWN)
                .filter(finding -> ReviewSelection.selected(finding, runs))
                .map(finding -> new FindingView(finding.id(), finding.label(), finding.title(), finding.severity(), finding.anchor().map(anchor -> anchor.path()),
                        finding.anchor().map(anchor -> anchor.side()), finding.anchor().map(anchor -> anchor.startLine()), finding.anchor().map(anchor -> anchor.endLine())))
                .collect(toImmutableList());
        return new SideView(side.memory(), side.reviewId(), state, run.flatMap(ReviewRun::outcome), waiting, findings);
    }

    /** A finding raises a comment when it sits on the same file and side, with lines within {@value #NEAR_LINES} of the comment's. */
    private static boolean raises(FindingView finding, Comment comment)
    {
        return finding.path().filter(comment.path()::equals).isPresent() && finding.side().filter(comment.side()::equals).isPresent() && comment.line().isPresent()
                && finding.startLine().orElseThrow() - NEAR_LINES <= comment.line().orElseThrow()
                && comment.startLine().orElse(comment.line().orElseThrow()) <= finding.endLine().orElseThrow() + NEAR_LINES;
    }

    private static boolean maintainer(String author, boolean bot, boolean maintainer, PullReplay pull)
    {
        return maintainer && !bot && !author.equalsIgnoreCase(pull.author());
    }

    private static Comment comment(ThreadStart thread, String head)
    {
        // Lines at the commit the comment was written on, where the maintainer saw them; the latest-head lines describe later code.
        return new Comment(thread.comment().id(), thread.comment().author(), thread.path(), thread.side(), thread.originalLine().or(thread::line),
                thread.originalStartLine().or(thread::startLine), thread.originalCommit().filter(head::equals).isPresent(),
                ContextText.excerpt(thread.comment().body().strip(), MAX_COMMENT_BYTES), thread.comment().url(), thread.comment().at());
    }

    private boolean running(Replay replay)
    {
        return replay.sides().stream().anyMatch(side -> store.listRuns(side.reviewId()).stream().anyMatch(ReviewRun::active));
    }

    /** The first maintainer-reviewed head the workspace repository has or can fetch, else the merged head. */
    private Head head(Path repository, String remote, int number, List<SubmittedReview> reviewed)
    {
        String pullRef = "refs/bytequay/replay/" + number;
        boolean fetchedPull = false;
        List<String> candidates = reviewed.stream().flatMap(review -> review.commit().stream()).filter(sha -> sha.matches("[0-9a-f]{40}"))
                .distinct().collect(toImmutableList());
        Optional<String> first = reviewed.getFirst().commit();
        for (String candidate : candidates) {
            if (!present(repository, candidate) && !fetchedPull) {
                fetchedPull = true;
                fetch(repository, remote, "+refs/pull/" + number + "/head:" + pullRef);
            }
            if (present(repository, candidate) || (fetch(repository, remote, candidate) && present(repository, candidate))) {
                return new Head(candidate, first.filter(candidate::equals).isPresent() ? Optional.empty()
                        : Optional.of("The head of the first maintainer review is gone from GitHub, so this replays " + candidate.substring(0, 7)
                                + ", the head of a later maintainer review."));
            }
        }
        if (!fetchedPull && !fetch(repository, remote, "+refs/pull/" + number + "/head:" + pullRef)) {
            throw new ReviewConflictException("Cannot fetch pull request #" + number);
        }
        return new Head(run(repository, "rev-parse", "--verify", pullRef + "^{commit}"),
                Optional.of("The heads the maintainers reviewed are gone from GitHub, so this replays the merged head."));
    }

    /** The base branch just before the change landed: the merge commit's first parent, or the base branch tip when GitHub names no merge. */
    private String landedOn(Path repository, String remote, PullReplay pull)
    {
        checkArgument(pull.baseRef().matches("[A-Za-z0-9._/-]+") && !pull.baseRef().startsWith("-"), "Unexpected base branch %s", pull.baseRef());
        String baseTip = "refs/remotes/" + remote + "/" + pull.baseRef();
        Optional<String> merge = pull.mergeCommit().filter(sha -> sha.matches("[0-9a-f]{40}"));
        if (merge.map(sha -> !present(repository, sha)).orElse(!present(repository, baseTip))) {
            fetch(repository, remote, "+refs/heads/" + pull.baseRef() + ":" + baseTip);
        }
        return merge.map(sha -> run(repository, "rev-parse", "--verify", sha + "^1"))
                .orElseGet(() -> run(repository, "rev-parse", "--verify", baseTip + "^{commit}"));
    }

    private boolean present(Path repository, String revision)
    {
        return git.run(repository, "cat-file", "-e", revision + "^{commit}").successful();
    }

    private boolean fetch(Path repository, String remote, String refspec)
    {
        GitCommandRunner.Result fetched = git.run(repository, "fetch", "--no-tags", "--no-recurse-submodules", "--no-write-fetch-head", remote, refspec);
        if (!fetched.successful()) {
            log.info("Replay fetch of {} from {} failed: {}", refspec, remote, fetched.output().strip());
        }
        return fetched.successful();
    }

    private String run(Path repository, String... arguments)
    {
        GitCommandRunner.Result result = git.run(repository, arguments);
        if (!result.successful() || result.output().isBlank()) {
            throw new ReviewConflictException("Cannot run git " + String.join(" ", arguments) + " in the workspace repository");
        }
        return result.output().strip();
    }

    private static String markKey(String commentId, boolean memory)
    {
        return commentId + (memory ? ":with" : ":without");
    }

    private List<Replay> replays(String workspaceId)
    {
        return settings.get(STATE + workspaceId).map(json -> {
            try {
                return mapper.readValue(json, Replays.class).replays();
            }
            catch (JsonProcessingException failure) {
                log.warn("The replays of workspace {} cannot be read; they start over", workspaceId, failure);
                return ImmutableList.<Replay>of();
            }
        }).orElse(ImmutableList.of());
    }

    private List<Replay> update(String workspaceId, UnaryOperator<List<Replay>> change)
    {
        Lock lock = locks.get(workspaceId);
        lock.lock();
        try {
            List<Replay> updated = ImmutableList.copyOf(change.apply(replays(workspaceId)));
            settings.put(STATE + workspaceId, mapper.writeValueAsString(new Replays(updated)));
            return updated;
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("Replays cannot be saved", failure);
        }
        finally {
            lock.unlock();
        }
    }

    private record Head(String sha, Optional<String> fallback) {}

    record Replays(List<Replay> replays)
    {
        Replays
        {
            replays = ImmutableList.copyOf(replays);
        }
    }

    /**
     * A replay as stored: what was replayed and where, the maintainers' comments to compare with, the two reviews, and the user's marks.
     *
     * @param fallback why the replay is not at the head of the first maintainer review, when it is not
     * @param cutoff when the first maintainer review arrived
     * @param excludedMemories how many memories the side with memory did not get because they came from this pull request or after the cutoff
     * @param marks the user's calls, keyed by comment and side, that override the line match
     */
    record Replay(String id, int number, String title, String url, String head, Optional<String> fallback, Instant cutoff, String reviewer, int excludedMemories,
            List<Comment> comments, List<Side> sides, Map<String, Boolean> marks, Instant createdAt)
    {
        Replay
        {
            comments = ImmutableList.copyOf(comments);
            sides = ImmutableList.copyOf(sides);
            marks = ImmutableMap.copyOf(marks);
        }

        Replay withMarks(Map<String, Boolean> updated)
        {
            return new Replay(id, number, title, url, head, fallback, cutoff, reviewer, excludedMemories, comments, sides, updated, createdAt);
        }
    }

    record Side(boolean memory, String reviewId) {}

    /**
     * A maintainer's first comment in a review thread, with its lines at the commit it was written on.
     *
     * @param onReplayedHead whether it was written on the replayed head; a later one may be about code the replay never saw
     */
    record Comment(String id, String author, String path, String side, Optional<Integer> line, Optional<Integer> startLine, boolean onReplayedHead, String body,
            String url, Instant at) {}

    public record ReplayView(String id, int number, String title, String url, String head, Optional<String> fallback, Instant cutoff, String reviewer,
            int excludedMemories, Instant createdAt, List<SideView> sides, List<CommentView> comments)
    {
        public ReplayView
        {
            sides = ImmutableList.copyOf(sides);
            comments = ImmutableList.copyOf(comments);
        }
    }

    /**
     * One side of a replay.
     *
     * @param state RUNNING, DONE, INCOMPLETE, STOPPED, FAILED, or GONE once its review is gone
     * @param waiting whether the running review waits for the user to answer an approval
     */
    public record SideView(boolean memory, String reviewId, String state, Optional<String> outcome, boolean waiting, List<FindingView> findings)
    {
        public SideView
        {
            findings = ImmutableList.copyOf(findings);
        }
    }

    /** A finding of one side, with where it sits; a finding about the whole change has no path. */
    public record FindingView(String id, String label, String title, Optional<String> severity, Optional<String> path, Optional<String> side,
            Optional<Integer> startLine, Optional<Integer> endLine) {}

    /** A maintainer comment and whether each side raised it. */
    public record CommentView(String id, String author, String path, String side, Optional<Integer> line, Optional<Integer> startLine, boolean onReplayedHead,
            String body, String url, Instant at, List<Mark> marks)
    {
        public CommentView
        {
            marks = ImmutableList.copyOf(marks);
        }
    }

    /**
     * Whether one side raised a comment.
     *
     * @param findings the side's findings on the same file and side within a few lines
     * @param corrected whether the user's mark disagrees with the line match
     */
    public record Mark(boolean memory, List<String> findings, boolean raised, boolean corrected)
    {
        public Mark
        {
            findings = ImmutableList.copyOf(findings);
        }
    }
}
