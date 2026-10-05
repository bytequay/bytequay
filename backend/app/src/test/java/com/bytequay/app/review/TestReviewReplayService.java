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

import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.memory.InMemoryMemoryStore;
import com.bytequay.app.memory.MemoryFixtures;
import com.bytequay.app.memory.MemoryService;
import com.bytequay.app.repository.github.GitHubHarvestClient;
import com.bytequay.app.repository.github.GitHubHarvestClient.Note;
import com.bytequay.app.repository.github.GitHubHarvestClient.PullReplay;
import com.bytequay.app.repository.github.GitHubHarvestClient.SubmittedReview;
import com.bytequay.app.repository.github.GitHubHarvestClient.ThreadStart;
import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.credentials.CredentialService.GitHubCredential;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.UserProfile;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewAnchor;
import com.bytequay.core.review.ReviewConflictException;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.review.ReviewInvalidRequestException;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.review.ReviewStep;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestReviewReplayService
{
    private static final String WORKSPACE = "workspace";
    private static final RepoRef REPOSITORY = RepoRef.of("trinodb", "trino");
    private static final int NUMBER = 31201;
    private static final Instant FIRST_REVIEW = MemoryFixtures.NOW.plusSeconds(3600);

    @TempDir
    private Path repository;

    private final GitCommandRunner git = new GitCommandRunner();
    private final GitHubHarvestClient github = mock(GitHubHarvestClient.class);
    private final CredentialService credentials = mock(CredentialService.class);
    private final WorkspaceRepositoryService workspaces = mock(WorkspaceRepositoryService.class);
    private final AgentReviewService reviews = mock(AgentReviewService.class);
    private final AgentReviewStore store = mock(AgentReviewStore.class);
    private final InMemoryMemoryStore memoryStore = new InMemoryMemoryStore();
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new Jdk8Module()).registerModule(new JavaTimeModule());
    private final Clock clock = Clock.fixed(MemoryFixtures.NOW, ZoneOffset.UTC);
    private final ReviewReplayService service = new ReviewReplayService(github, credentials, workspaces, reviews, store,
            new MemoryService(memoryStore, new InMemoryAppSettingsStore(), mapper, clock), git, new InMemoryAppSettingsStore(), mapper, clock);

    private String fork;
    private String firstHead;
    private String laterHead;
    private String merge;

    @BeforeEach
    void setUp()
            throws IOException
    {
        git.run(repository, "init", "-q", "-b", "master");
        fork = commit("README.md", "Project\n");
        git.run(repository, "checkout", "-q", "-b", "feature");
        firstHead = commit("plugin/x/A.java", "class A {}\n");
        laterHead = commit("plugin/x/B.java", "class B {}\n");
        git.run(repository, "checkout", "-q", "master");
        commit("NEWS.md", "Later work on master\n");
        assertThat(git.run(repository, ImmutableList.of("-c", "user.name=Test", "-c", "user.email=test@example.com", "merge", "--no-ff", "-q", "feature", "-m", "Merge"))
                .successful()).isTrue();
        merge = git.run(repository, "rev-parse", "HEAD").output().strip();
        when(workspaces.requireReadyRepository(WORKSPACE)).thenReturn(new WorkspaceRepositoryContext(WORKSPACE, repository, 1, REPOSITORY, OptionalLong.empty(),
                REPOSITORY, "origin", "origin", "master", ImmutableList.of()));
        when(credentials.resolveGitHubCredential()).thenReturn(new GitHubCredential("token", new UserProfile("alice", "", "", "", 0, 0, 0, "", "", "", "")));
        AgentReviewProjection with = projection("review-with");
        AgentReviewProjection without = projection("review-without");
        when(reviews.startReplay(eq(WORKSPACE), any())).thenReturn(with, without);
        when(reviews.get(anyString())).thenReturn(with);
        memoryStore.save(MemoryFixtures.rule("m1", WORKSPACE, "Rule from an earlier pull request.", ImmutableList.of(), 30000));
        memoryStore.save(MemoryFixtures.rule("m2", WORKSPACE, "Rule from the replayed pull request.", ImmutableList.of(), NUMBER));
    }

    @Test
    void replaysTheFirstMaintainerReviewsHeadTwiceAndComparesTheMaintainersCommentsWithEachSidesFindings()
    {
        when(github.replay("token", REPOSITORY, NUMBER)).thenReturn(Optional.of(pull(Optional.of(firstHead))));

        ReviewReplayService.ReplayView started = service.start(WORKSPACE, "TrinoDB/Trino", NUMBER);

        ArgumentCaptor<AgentReviewService.ReplayStart> starts = ArgumentCaptor.forClass(AgentReviewService.ReplayStart.class);
        verify(reviews, times(2)).startReplay(eq(WORKSPACE), starts.capture());
        assertThat(starts.getAllValues()).extracting(start -> start.replay().memory()).containsExactly(true, false);
        AgentReviewService.ReplayStart first = starts.getAllValues().getFirst();
        assertThat(first.headSha()).isEqualTo(firstHead);
        assertThat(first.mergeBaseSha()).isEqualTo(fork);
        assertThat(first.baseRef()).isEqualTo("master");
        assertThat(first.replay().cutoff()).isEqualTo(FIRST_REVIEW);
        assertThat(first.replay().number()).isEqualTo(NUMBER);
        assertThat(started.fallback()).isEmpty();
        assertThat(started.reviewer()).isEqualTo("bo");
        assertThat(started.excludedMemories()).isEqualTo(1);
        assertThat(started.comments()).extracting(ReviewReplayService.CommentView::author).containsExactly("bo", "cy");
        assertThat(started.comments()).extracting(ReviewReplayService.CommentView::onReplayedHead).containsExactly(true, false);

        sideOf("review-with", ReviewRun.State.ACCEPTED, finding("finding-1", "plugin/x/A.java", 15, 16));
        sideOf("review-without", ReviewRun.State.RUNNING, finding("finding-2", "plugin/x/A.java", 40, 41));
        ReviewReplayService.ReplayView view = service.list(WORKSPACE).getFirst();
        assertThat(view.sides()).extracting(ReviewReplayService.SideView::state).containsExactly("DONE", "RUNNING");
        ReviewReplayService.CommentView comment = view.comments().getFirst();
        assertThat(comment.marks()).extracting(ReviewReplayService.Mark::raised).containsExactly(true, false);
        assertThat(comment.marks().getFirst().findings()).containsExactly("finding-1");

        ReviewReplayService.ReplayView marked = service.mark(WORKSPACE, view.id(), comment.id(), false, true);
        assertThat(marked.comments().getFirst().marks()).extracting(ReviewReplayService.Mark::raised).containsExactly(true, true);
        assertThat(marked.comments().getFirst().marks()).extracting(ReviewReplayService.Mark::corrected).containsExactly(false, true);

        assertThatThrownBy(() -> service.start(WORKSPACE, "trinodb/trino", NUMBER)).isInstanceOf(ReviewConflictException.class)
                .hasMessage("A replay of #31201 is still running");
        assertThatThrownBy(() -> service.delete(WORKSPACE, view.id())).isInstanceOf(ReviewConflictException.class);
        sideOf("review-without", ReviewRun.State.ACCEPTED);
        service.delete(WORKSPACE, view.id());
        verify(reviews).end("review-with");
        verify(reviews).end("review-without");
        assertThat(service.list(WORKSPACE)).isEmpty();
    }

    @Test
    void aHeadGitHubNoLongerHasGivesWayToALaterMaintainerReviewsHead()
    {
        when(github.replay("token", REPOSITORY, NUMBER)).thenReturn(Optional.of(pull(Optional.of("f".repeat(40)))));

        ReviewReplayService.ReplayView started = service.start(WORKSPACE, "trinodb/trino", NUMBER);

        assertThat(started.head()).isEqualTo(laterHead);
        assertThat(started.fallback()).contains("The head of the first maintainer review is gone from GitHub, so this replays " + laterHead.substring(0, 7)
                + ", the head of a later maintainer review.");
        assertThat(started.cutoff()).isEqualTo(FIRST_REVIEW);
    }

    @Test
    void refusesWhatItCannotCompare()
    {
        assertThatThrownBy(() -> service.start(WORKSPACE, "trinodb/other", NUMBER)).isInstanceOf(ReviewInvalidRequestException.class)
                .hasMessage("Pull request trinodb/other#31201 does not belong to this workspace");
        PullReplay pull = pull(Optional.of(firstHead));
        when(github.replay("token", REPOSITORY, NUMBER)).thenReturn(Optional.of(new PullReplay(NUMBER, pull.title(), pull.url(), false, pull.author(), pull.baseRef(),
                pull.mergeCommit(), pull.reviews(), pull.threads())));
        assertThatThrownBy(() -> service.start(WORKSPACE, "trinodb/trino", NUMBER)).hasMessage("Only a merged pull request can be replayed");
        when(github.replay("token", REPOSITORY, NUMBER)).thenReturn(Optional.of(new PullReplay(NUMBER, pull.title(), pull.url(), true, pull.author(), pull.baseRef(),
                pull.mergeCommit(), pull.reviews(), pull.threads().stream().filter(thread -> !thread.comment().maintainer()).collect(toImmutableList()))));
        assertThatThrownBy(() -> service.start(WORKSPACE, "trinodb/trino", NUMBER))
                .hasMessage("No maintainer commented on a line of #31201, so a replay has nothing to compare with");
        verify(reviews, never()).startReplay(any(), any());
    }

    private PullReplay pull(Optional<String> firstReviewHead)
    {
        List<SubmittedReview> submitted = ImmutableList.of(
                new SubmittedReview("alice", false, true, "COMMENTED", FIRST_REVIEW.minusSeconds(60), Optional.of(firstHead)),
                new SubmittedReview("ci-bot", true, true, "COMMENTED", FIRST_REVIEW.minusSeconds(30), Optional.of(firstHead)),
                new SubmittedReview("bo", false, true, "CHANGES_REQUESTED", FIRST_REVIEW, firstReviewHead),
                new SubmittedReview("cy", false, true, "APPROVED", FIRST_REVIEW.plusSeconds(3600), Optional.of(laterHead)));
        List<ThreadStart> threads = ImmutableList.of(
                thread("bo", true, "plugin/x/A.java", 14, firstHead),
                thread("alice", true, "plugin/x/A.java", 1, firstHead),
                thread("eve", false, "plugin/x/A.java", 2, firstHead),
                thread("cy", true, "plugin/x/B.java", 1, laterHead));
        return new PullReplay(NUMBER, "Add a property", "https://github.com/trinodb/trino/pull/31201", true, "alice", "master", Optional.of(merge), submitted, threads);
    }

    private static ThreadStart thread(String author, boolean maintainer, String path, int line, String commit)
    {
        return new ThreadStart(path, "RIGHT", Optional.empty(), Optional.empty(), Optional.of(line), Optional.empty(),
                new Note("note-" + author, author, false, maintainer, "Please check this.", "https://github.com/trinodb/trino/pull/31201#discussion_" + author,
                        FIRST_REVIEW), Optional.of(commit));
    }

    private void sideOf(String reviewId, ReviewRun.State state, ReviewFinding... findings)
    {
        AgentReview review = new AgentReview(reviewId, WORKSPACE, "trinodb/trino", OptionalInt.empty(), OptionalLong.empty(), "task-" + reviewId, Optional.empty(),
                AgentReview.State.OPEN, false, Optional.empty(), Optional.empty(), Optional.empty(), 0, MemoryFixtures.NOW, MemoryFixtures.NOW, Optional.empty(),
                Optional.of(new AgentReview.Replay("replay", NUMBER, reviewId.equals("review-with"), FIRST_REVIEW)));
        when(store.findReview(reviewId)).thenReturn(Optional.of(review));
        when(store.listRuns(reviewId)).thenReturn(ImmutableList.of(new ReviewRun("run-" + reviewId, reviewId, 1, ReviewRun.Scope.FULL, "master", fork, fork, firstHead,
                Optional.empty(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), state, Optional.empty(), Optional.empty(), Optional.empty(),
                ImmutableList.of(ReviewStep.initial()), false, ImmutableMap.of(), ImmutableMap.of(), Optional.empty(), 0, MemoryFixtures.NOW, MemoryFixtures.NOW,
                state.terminal() ? Optional.of(MemoryFixtures.NOW) : Optional.empty())));
        if (findings.length > 0) {
            when(store.listFindings(reviewId)).thenReturn(ImmutableList.copyOf(findings));
        }
    }

    private ReviewFinding finding(String id, String path, int startLine, int endLine)
    {
        return new ReviewFinding(id, "review", "run", 1, "Missing documentation", "The property is not documented.", Optional.of("Medium"),
                Optional.of(new ReviewAnchor(path, "RIGHT", startLine, endLine, ImmutableList.of("line"))), ImmutableList.of(), 1, ReviewFinding.State.OPEN,
                Optional.empty(), firstHead, false, 0, MemoryFixtures.NOW, MemoryFixtures.NOW);
    }

    private static AgentReviewProjection projection(String reviewId)
    {
        AgentReview review = new AgentReview(reviewId, WORKSPACE, "trinodb/trino", OptionalInt.empty(), OptionalLong.empty(), "task-" + reviewId, Optional.empty(),
                AgentReview.State.OPEN, false, Optional.empty(), Optional.empty(), Optional.empty(), 0, MemoryFixtures.NOW, MemoryFixtures.NOW, Optional.empty(),
                Optional.of(new AgentReview.Replay("replay", NUMBER, true, FIRST_REVIEW)));
        return new AgentReviewProjection(review, ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                new AgentReviewProjection.TaskView("task-" + reviewId, WORKSPACE, "OPEN", 1, "codex", "Codex", Optional.empty(), Optional.empty(), false, false),
                Optional.empty(), ImmutableList.of(), Optional.empty(), 0, ImmutableList.of(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private String commit(String path, String content)
            throws IOException
    {
        Path file = repository.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        git.run(repository, "add", "-A");
        assertThat(git.run(repository, ImmutableList.of("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-qm", "Change " + path)).successful())
                .isTrue();
        return git.run(repository, "rev-parse", "HEAD").output().strip();
    }
}
