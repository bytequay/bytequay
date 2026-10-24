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

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.ReviewTool;
import com.bytequay.agent.spi.RunningTurn;
import com.bytequay.app.git.command.GitCommandRunner;
import com.bytequay.app.memory.MemoryDelivery;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.ReviewCandidate;
import com.bytequay.core.review.ReviewPerspectiveAssessment.Perspective;
import com.bytequay.core.review.ReviewRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.agent.core.AgentTaskFixtures.created;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestReviewSweep
{
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final TaskId TASK_ID = TaskId.valueOf("11111111-1111-4111-8111-111111111111");
    private static final Path CHECKOUT = Path.of("/tmp/bytequay/worktrees").resolve(TASK_ID.toString());
    private static final String BASE = "a".repeat(40);
    private static final String HEAD = "b".repeat(40);
    private static final String ANSWER = """
            {"leads":[{"path":"src/App.java","line":7,"lens":"words","kind":"nit","claim":"The comment is stale.","ask":"Reword it."}]}
            """;

    private final GitCommandRunner git = mock(GitCommandRunner.class);
    private final PullRequestService pullRequests = mock(PullRequestService.class);
    private final MemoryDelivery memories = mock(MemoryDelivery.class);
    private final AtomicInteger settled = new AtomicInteger();

    private static String read(ReviewTool tool, String kind)
    {
        try {
            return new ObjectMapper().readTree(tool.call().apply("{\"kind\":\"" + kind + "\"}")).path("text").asText();
        }
        catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    void theFactsNameTestsThatArriveAfterTheirCode()
    {
        String first = "c".repeat(40);
        when(git.runLocalBoundedStdout(eq(CHECKOUT), any(Duration.class), anyInt(), anyList())).thenAnswer(invocation -> {
            List<String> arguments = invocation.getArgument(3);
            if (arguments.getFirst().equals("log")) {
                return new GitCommandRunner.Result(0, first + "\u001fAdd the loader\u001f\u001e\n" + HEAD + "\u001fTest the loader\u001f\u001e\n", false);
            }
            // Git lists the newest commit first here, and only the commits that add a file.
            return arguments.contains("--diff-filter=A")
                    ? new GitCommandRunner.Result(0, "\u001e" + HEAD + "\n\nsrc/test/java/TestLoader.java\n\u001e" + first + "\n\nsrc/main/java/Loader.java\n", false)
                    : new GitCommandRunner.Result(128, "fatal: path does not exist", false);
        });
        when(pullRequests.getPullRequestDetail("owner/repo", 7)).thenReturn(detail("Add the loader", ""));
        List<String> prompts = new ArrayList<>();
        ReviewSweep sweep = new ReviewSweep(git, pullRequests, memories, Runnable::run, (task, prompt, context, started) -> {
            assertThat(prompt).contains("review_get_context").hasSizeLessThan(600);
            prompts.add(read(context, "scope") + read(context, "pr") + read(context, "facts") + read(context, "assignment"));
            return Optional.of(ANSWER);
        });

        sweep.start(review(Optional.empty()), run(), task(), settled::incrementAndGet);

        assertThat(prompts.getFirst()).contains("commit bbbbbbbbbbb adds TestLoader.java, the test of Loader.java, which commit ccccccccccc added");
    }

    @Test
    void readsOnceWithTheGatheredReferenceAndHandsItsLeadsOverOnce()
    {
        when(git.runLocalBoundedStdout(eq(CHECKOUT), any(Duration.class), anyInt(), anyList())).thenAnswer(invocation -> {
            List<String> arguments = invocation.getArgument(3);
            if (arguments.getFirst().equals("log")) {
                return new GitCommandRunner.Result(0, HEAD + "\u001fAdd the loader\u001fWhy it exists.\n\u001e\n", false);
            }
            return arguments.equals(ImmutableList.of("show", BASE + ":.github/pull_request_template.md"))
                    ? new GitCommandRunner.Result(0, "## Release notes\n( ) Not needed\n( ) Please propose a note\n", false)
                    : new GitCommandRunner.Result(128, "fatal: path does not exist", false);
        });
        when(pullRequests.getPullRequestDetail("owner/repo", 7)).thenReturn(detail("Add the loader", "## Release notes\n( ) Not needed\n(x) Please propose a note\n"));
        List<String> prompts = new ArrayList<>();
        ReviewSweep sweep = new ReviewSweep(git, pullRequests, memories, Runnable::run, (task, prompt, context, started) -> {
            assertThat(prompt).contains("review_get_context").hasSizeLessThan(600);
            prompts.add(read(context, "scope") + read(context, "pr") + read(context, "facts") + read(context, "assignment"));
            return Optional.of(ANSWER);
        });

        sweep.start(review(Optional.empty()), run(), task(), settled::incrementAndGet);

        assertThat(settled).hasValue(1);
        assertThat(prompts).hasSize(1);
        assertThat(prompts.getFirst()).contains("Repository: owner/repo\nFrozen head: " + HEAD, "git diff " + BASE + " " + HEAD, "src/App.java",
                "Add the loader\n\n## Release notes", "commit bbbbbbbbbbb \"Add the loader\": subject 14 characters, body 3 words",
                "description section \"Release notes\": marked \"Please propose a note\"", ReviewLead.SHAPE);
        assertThat(sweep.pending("review", "run")).isFalse();
        assertThat(sweep.take("review", "other-run")).isEmpty();
        assertThat(sweep.take("review", "run")).containsExactly(
                new ReviewLead("src/App.java", OptionalInt.of(7), ReviewLead.Lens.WORDS, ReviewLead.Kind.NIT, "The comment is stale.", "Reword it."));
        assertThat(sweep.take("review", "run")).isEmpty();
    }

    @Test
    void aReplayReadsWithoutThePullRequestItMustNotSee()
    {
        when(git.runLocalBoundedStdout(eq(CHECKOUT), any(Duration.class), anyInt(), anyList())).thenReturn(new GitCommandRunner.Result(128, "", false));
        AtomicReference<String> prompt = new AtomicReference<>();
        ReviewSweep sweep = new ReviewSweep(git, pullRequests, memories, Runnable::run, (task, request, context, started) -> {
            prompt.set(read(context, "scope") + read(context, "pr"));
            return Optional.of("{\"leads\":[]}");
        });

        sweep.start(review(Optional.of(new AgentReview.Replay("replay", 7, false, NOW))), run(), task(), settled::incrementAndGet);

        verify(pullRequests, never()).getPullRequestDetail(any(), anyInt());
        assertThat(prompt.get()).contains("Repository: owner/repo\nFrozen head: " + HEAD).doesNotContain("## Description", "## Facts ByteQuay measured", "## Repository rules");
        assertThat(sweep.take("review", "run")).isEmpty();
    }

    @Test
    void aFailedOrUnreadableReadSettlesWithoutLeads()
    {
        when(git.runLocalBoundedStdout(eq(CHECKOUT), any(Duration.class), anyInt(), anyList())).thenReturn(new GitCommandRunner.Result(128, "", false));
        when(pullRequests.getPullRequestDetail("owner/repo", 7)).thenThrow(new IllegalStateException("offline"));
        ReviewSweep failing = new ReviewSweep(git, pullRequests, memories, Runnable::run, (task, prompt, context, started) -> {
            throw new IllegalStateException("The model did not answer in time");
        });
        ReviewSweep unreadable = new ReviewSweep(git, pullRequests, memories, Runnable::run, (task, prompt, context, started) -> Optional.of("No leads, sorry."));
        ReviewSweep silent = new ReviewSweep(git, pullRequests, memories, Runnable::run, (task, prompt, context, started) -> Optional.empty());

        for (ReviewSweep sweep : ImmutableList.of(failing, unreadable, silent)) {
            sweep.start(review(Optional.empty()), run(), task(), settled::incrementAndGet);
            assertThat(sweep.pending("review", "run")).isFalse();
            assertThat(sweep.take("review", "run")).isEmpty();
        }
        assertThat(settled).hasValue(3);
    }

    @Test
    void cancellingBeforeTheReaderStartsSkipsItsTurn()
    {
        when(git.runLocalBoundedStdout(eq(CHECKOUT), any(Duration.class), anyInt(), anyList())).thenReturn(new GitCommandRunner.Result(128, "", false));
        List<Runnable> queued = new ArrayList<>();
        RunningTurn turn = mock(RunningTurn.class);
        ReviewSweep sweep = new ReviewSweep(git, pullRequests, memories, queued::add, (task, prompt, context, started) -> {
            started.accept(turn);
            return Optional.empty();
        });

        sweep.start(review(Optional.empty()), run(), task(), settled::incrementAndGet);

        assertThat(sweep.pending("review", "run")).isTrue();
        assertThat(sweep.pending("review", "other-run")).isFalse();
        assertThat(sweep.take("review", "run")).isEmpty();
        sweep.cancel("review");
        verify(turn, never()).cancel();

        queued.getFirst().run();

        verify(turn, never()).cancel();
        verify(git, never()).runLocalBoundedStdout(any(), any(), anyInt(), anyList());
        assertThat(sweep.pending("review", "run")).isFalse();
        assertThat(settled).hasValue(1);
    }

    @Test
    void closingInterruptsTheReaderAndCancelsItsTurn()
            throws Exception
    {
        when(git.runLocalBoundedStdout(eq(CHECKOUT), any(Duration.class), anyInt(), anyList())).thenReturn(new GitCommandRunner.Result(128, "", false));
        CountDownLatch entered = new CountDownLatch(1);
        RunningTurn turn = mock(RunningTurn.class);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ReviewSweep sweep = new ReviewSweep(git, pullRequests, memories, executor, (task, prompt, context, started) -> {
                started.accept(turn);
                entered.countDown();
                try {
                    new CountDownLatch(1).await();
                }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return Optional.of(ANSWER);
            });
            try {
                sweep.start(review(Optional.empty()), run(), task(), settled::incrementAndGet);
                assertThat(entered.await(5, SECONDS)).isTrue();
            }
            finally {
                sweep.close();
            }
            assertThat(executor.awaitTermination(5, SECONDS)).isTrue();
            verify(turn).cancel();
            assertThat(sweep.pending("review", "run")).isFalse();
            assertThat(sweep.take("review", "run")).isEmpty();
            assertThat(settled).hasValue(0);
        }
    }

    @Test
    void aRejectedReaderStillSettlesValidation()
    {
        ReviewSweep sweep = new ReviewSweep(git, pullRequests, memories, command -> {
            throw new RejectedExecutionException("closing");
        }, (task, prompt, context, started) -> Optional.of(ANSWER));

        sweep.start(review(Optional.empty()), run(), task(), settled::incrementAndGet);

        assertThat(settled).hasValue(1);
        assertThat(sweep.pending("review", "run")).isFalse();
    }

    @Test
    void capturedContextIsRevokedAfterCancellationReplacementOrCompletion()
    {
        when(git.runLocalBoundedStdout(eq(CHECKOUT), any(Duration.class), anyInt(), anyList())).thenReturn(new GitCommandRunner.Result(128, "", false));
        List<Runnable> queued = new ArrayList<>();
        List<ReviewTool> captured = new ArrayList<>();
        AtomicReference<ReviewSweep> current = new AtomicReference<>();
        ReviewSweep sweep = new ReviewSweep(git, pullRequests, memories, queued::add, (task, prompt, context, started) -> {
            captured.add(context);
            assertThat(read(context, "scope")).contains(HEAD);
            if (captured.size() == 1) {
                current.get().cancel("review");
            }
            else if (captured.size() == 2) {
                current.get().start(review(Optional.empty()), run(), task(), () -> {});
            }
            else {
                return Optional.empty();
            }
            assertThat(context.call().apply("{\"kind\":\"scope\"}")).contains("stopped or been replaced");
            return Optional.empty();
        });
        current.set(sweep);
        sweep.start(review(Optional.empty()), run(), task(), () -> {});
        queued.getFirst().run();
        sweep.start(review(Optional.empty()), run(), task(), () -> {});
        queued.get(1).run();
        queued.get(2).run();
        assertThat(captured).hasSize(3).allSatisfy(context -> assertThat(context.call().apply("{\"kind\":\"scope\"}")).contains("stopped or been replaced"));
    }

    @Test
    void aLeadBecomesAnUnverifiedCandidateAttributedToTheSecondReader()
    {
        ReviewCandidate nit = ReviewSweep.candidate(
                new ReviewLead("src/App.java", OptionalInt.of(7), ReviewLead.Lens.WORDS, ReviewLead.Kind.NIT, "The comment is stale.", "Reword it."), 4, 1);
        ReviewCandidate defect = ReviewSweep.candidate(
                new ReviewLead("src/App.java", OptionalInt.empty(), ReviewLead.Lens.TESTS, ReviewLead.Kind.DEFECT, "The loop skips the last file.", "Fix the bound."), 5, 1);
        ReviewCandidate sibling = ReviewSweep.candidate(
                new ReviewLead("", OptionalInt.empty(), ReviewLead.Lens.SIBLINGS, ReviewLead.Kind.QUESTION, "x".repeat(600), "y".repeat(600)), 6, 3);

        assertThat(nit).isEqualTo(new ReviewCandidate(4, Perspective.DESIGN, "Second reader, words lens",
                "Nit at src/App.java:7: The comment is stale. The author should: Reword it.", ImmutableList.of(),
                "Read src/App.java:7 at the frozen head, then confirm or refute this unverified lead.", Optional.empty(), 1, "second-reader", Optional.of("second reader")));
        assertThat(defect.perspective()).isEqualTo(Perspective.BEHAVIOR);
        assertThat(defect.hypothesis()).startsWith("Defect at src/App.java: ");
        assertThat(sibling.perspective()).isEqualTo(Perspective.INTERACTIONS);
        assertThat(sibling.hypothesis()).startsWith("Question at the commits or the description: ").hasSizeLessThanOrEqualTo(2000);
        assertThat(sibling.originStep()).isEqualTo(3);
    }

    private static AgentReview review(Optional<AgentReview.Replay> replay)
    {
        return new AgentReview("review", "workspace", "owner/repo", replay.isPresent() ? OptionalInt.empty() : OptionalInt.of(7), OptionalLong.empty(),
                TASK_ID.toString(), Optional.empty(), AgentReview.State.OPEN, false, Optional.empty(), Optional.empty(), Optional.empty(), 0, NOW, NOW, Optional.empty(), replay);
    }

    private static ReviewRun run()
    {
        return new ReviewRun("run", "review", 1, ReviewRun.Scope.FULL, "main", BASE, BASE, HEAD, Optional.empty(), Optional.empty(), ImmutableList.of(),
                ImmutableList.of(new DiffFile("src/App.java", "modified", 1, 0, "@@ -1 +1 @@\n-old\n+new")), ImmutableList.of(), ReviewRun.State.RUNNING, Optional.empty(),
                Optional.empty(), Optional.empty(), ImmutableList.of(), false, ImmutableMap.of(), ImmutableMap.of(), Optional.empty(), 0, NOW, NOW, Optional.empty());
    }

    private static AgentTask task()
    {
        TaskWorktree worktree = new TaskWorktree(TASK_ID, "workspace", new RepositoryIdentity("owner", "repo"), CHECKOUT, "bytequay/task/" + TASK_ID, HEAD);
        return created(TASK_ID, true, "Review owner/repo#7", TitleSource.USER, new AgentLaneId("codex"), worktree,
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), NOW);
    }

    private static PullRequestDetail detail(String title, String body)
    {
        return new PullRequestDetail(12L, "owner/repo", 7, title, "https://github.test/owner/repo/pull/7", "open", false, true, "clean", "alice", null, NOW, NOW, null,
                "main", "feature", "head", body, ImmutableList.of(), ImmutableList.of(), null, ImmutableList.of(), 1, 0, 1, 0, PullRequestDetail.CiStatus.PASSING,
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), true);
    }
}
