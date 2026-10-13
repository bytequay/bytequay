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
package com.bytequay.app.discussion;

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskSummary;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.agent.AgentServingGate;
import com.bytequay.app.agent.TaskPullRequestOwnership;
import com.bytequay.core.discussion.ReviewCommentAnchor;
import com.bytequay.core.discussion.TaskDiscussion;
import com.bytequay.core.discussion.TaskDiscussionStore;
import com.bytequay.core.domain.PullRequest;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockMakers;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestReviewSummaryWatcher
{
    private static final Instant CREATED = Instant.parse("2026-09-21T00:00:00Z");
    private static final String REPO = "owner/repo";

    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final TaskPullRequestOwnership ownership = mock(TaskPullRequestOwnership.class);
    private final ReviewCommentReader comments = mock(ReviewCommentReader.class, withSettings().mockMaker(MockMakers.INLINE));
    private final TaskDiscussionStore discussions = mock(TaskDiscussionStore.class);
    private final TaskDiscussionService service = mock(TaskDiscussionService.class);
    private final AgentServingGate gate = mock(AgentServingGate.class, withSettings().mockMaker(MockMakers.INLINE));
    private final ReviewSummaryWatcher watcher = new ReviewSummaryWatcher(tasks, ownership, comments, discussions, service, gate);

    @BeforeEach
    void setUp()
    {
        when(gate.isReady()).thenReturn(true);
        when(discussions.listByTask(anyString())).thenReturn(ImmutableList.of());
    }

    @Test
    void doesNothingForATaskWithTheOptionOff()
    {
        AgentTask task = task(false);
        when(tasks.listLive()).thenReturn(ImmutableList.of(task));

        watcher.pass();

        verifyNoInteractions(ownership, comments, service);
    }

    @Test
    void summarizesNewHumanReviewCommentsOnceAndNeverTheCoveredOnes()
    {
        AgentTask task = task(true);
        when(tasks.listLive()).thenReturn(ImmutableList.of(task));
        when(ownership.shownPullRequest(task)).thenReturn(Optional.of(pullRequest(12, "open")));
        ReviewCommentItem review = item("review:1", "alice", false, CREATED.plusSeconds(10));
        ReviewCommentItem comment = item("review_comment:2", "bob", false, CREATED.plusSeconds(20));
        when(comments.read(REPO, 12)).thenReturn(ImmutableList.of(review, comment));

        watcher.pass();

        verify(service).summarize("w1", task.id().toString(), ImmutableList.of(review.anchor(), comment.anchor()));

        when(discussions.listByTask(task.id().toString())).thenReturn(ImmutableList.of(thread(review.anchor()), thread(comment.anchor())));
        watcher.pass();

        verify(service).summarize(anyString(), anyString(), any());
    }

    @Test
    void leavesBotsOlderCommentsTheAuthorsOwnAndIssueCommentsAlone()
    {
        AgentTask task = task(true);
        when(tasks.listLive()).thenReturn(ImmutableList.of(task));
        when(ownership.shownPullRequest(task)).thenReturn(Optional.of(pullRequest(12, "open")));
        when(comments.read(REPO, 12)).thenReturn(ImmutableList.of(
                item("review:1", "dependabot[bot]", true, CREATED.plusSeconds(10)),
                item("review:2", "alice", false, CREATED.minusSeconds(1)),
                item("review_comment:3", "Me", false, CREATED.plusSeconds(10)),
                item("issue_comment:4", "carol", false, CREATED.plusSeconds(10)),
                new ReviewCommentItem(anchor("review:5"), "dave", false, Optional.empty())));

        watcher.pass();

        verify(service, never()).summarize(anyString(), anyString(), any());
    }

    @Test
    void skipsATaskWithARunningTurnAndCarriesOnPastARefusal()
    {
        AgentTask running = task(true).claimWorktreeMutation(WorktreeMutationClaim.turn(TurnId.random(), CREATED), CREATED);
        AgentTask busy = task(true);
        AgentTask next = task(true);
        when(tasks.listLive()).thenReturn(ImmutableList.of(running, busy, next));
        when(ownership.shownPullRequest(any())).thenReturn(Optional.of(pullRequest(12, "open")));
        ReviewCommentItem review = item("review:1", "alice", false, CREATED.plusSeconds(10));
        when(comments.read(REPO, 12)).thenReturn(ImmutableList.of(review));
        doThrow(new AgentConflictException("This task is already answering a question")).when(service).summarize(anyString(), eq(busy.id().toString()), any());

        watcher.pass();

        verify(ownership, never()).shownPullRequest(running);
        verify(service).summarize("w1", next.id().toString(), ImmutableList.of(review.anchor()));
    }

    @Test
    void summarizesOnlyThePullRequestTheTaskTabShowsWhileItIsOpen()
    {
        AgentTask task = task(true);
        when(tasks.listLive()).thenReturn(ImmutableList.of(task));
        when(ownership.shownPullRequest(task)).thenReturn(Optional.of(pullRequest(13, "open")));
        when(comments.read(REPO, 13)).thenReturn(ImmutableList.of(item("review:1", "alice", false, CREATED.plusSeconds(10))));

        watcher.pass();

        verify(comments).read(REPO, 13);
        verify(service).summarize(anyString(), anyString(), any());

        // A newer open pull request the tab does not show is left alone while the shown one is merged.
        when(ownership.shownPullRequest(task)).thenReturn(Optional.of(pullRequest(10, "merged")));
        watcher.pass();

        verify(comments, never()).read(REPO, 10);
        verify(service).summarize(anyString(), anyString(), any());
    }

    @Test
    void skipsArchivedReleasedAndNonInteractiveTasks()
    {
        TaskSummary summary = new TaskSummary("done", Optional.empty(), AgentMetrics.empty(), Optional.empty(), ImmutableList.of());
        AgentTask released = task(true).complete(summary, CREATED, CREATED.plusSeconds(60)).withWorktreeRemovedAt(CREATED.plusSeconds(60));
        TaskId unplaced = TaskId.random();
        AgentTask noWorktree = AgentTask.created(unplaced, Optional.of("w1"), true, "Task", AgentLaneId.CLAUDE_CLI, new RepositoryIdentity("owner", "repo"),
                Path.of("/managed/worktrees", unplaced.toString()), defaults(true), CREATED);
        TaskId background = TaskId.random();
        AgentTask nonInteractive = AgentTask.created(background, false, "Task", TitleSource.USER, AgentLaneId.CLAUDE_CLI, worktree(background), defaults(true), CREATED);
        when(tasks.listLive()).thenReturn(ImmutableList.of(task(true).withArchived(true, CREATED), released, noWorktree, nonInteractive));
        when(ownership.shownPullRequest(any())).thenReturn(Optional.of(pullRequest(12, "open")));
        when(comments.read(REPO, 12)).thenReturn(ImmutableList.of(item("review:1", "alice", false, CREATED.plusSeconds(10))));

        watcher.pass();

        verifyNoInteractions(ownership, comments, service);
    }

    @Test
    void keepsABatchWithinItsCharacterBudget()
    {
        AgentTask task = task(true);
        when(tasks.listLive()).thenReturn(ImmutableList.of(task));
        when(ownership.shownPullRequest(task)).thenReturn(Optional.of(pullRequest(12, "open")));
        List<ReviewCommentItem> items = IntStream.rangeClosed(1, 3)
                .mapToObj(index -> new ReviewCommentItem(new ReviewCommentAnchor(REPO, 12, "review_comment:" + index, "{\"body\":\"" + "x".repeat(20_000) + "\"}"),
                        "alice", false, Optional.of(CREATED.plusSeconds(index))))
                .collect(toImmutableList());
        when(comments.read(REPO, 12)).thenReturn(items);

        watcher.pass();

        // Two 20,000-character snapshots fit in 48,000; the third waits for the next pass.
        verify(service).summarize("w1", task.id().toString(), ImmutableList.of(items.get(0).anchor(), items.get(1).anchor()));
    }

    @Test
    void takesTenCommentsAtATime()
    {
        AgentTask task = task(true);
        when(tasks.listLive()).thenReturn(ImmutableList.of(task));
        when(ownership.shownPullRequest(task)).thenReturn(Optional.of(pullRequest(12, "open")));
        List<ReviewCommentItem> items = IntStream.rangeClosed(1, 12)
                .mapToObj(index -> item("review_comment:" + index, "alice", false, CREATED.plusSeconds(index)))
                .collect(toImmutableList());
        when(comments.read(REPO, 12)).thenReturn(items);

        watcher.pass();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ReviewCommentAnchor>> batch = ArgumentCaptor.forClass(List.class);
        verify(service).summarize(anyString(), anyString(), batch.capture());
        assertThat(batch.getValue()).extracting(ReviewCommentAnchor::key)
                .containsExactlyElementsOf(items.subList(0, 10).stream().map(item -> item.anchor().key()).collect(toImmutableList()));
    }

    @Test
    void doesNothingUntilTheAgentServiceIsServing()
    {
        when(gate.isReady()).thenReturn(false);

        watcher.pass();

        verifyNoInteractions(tasks, ownership, comments, service);
    }

    private static AgentTask task(boolean reviewSummary)
    {
        TaskId id = TaskId.random();
        return AgentTask.created(id, true, "Task", TitleSource.USER, AgentLaneId.CLAUDE_CLI, worktree(id), defaults(reviewSummary), CREATED);
    }

    private static TaskWorktree worktree(TaskId id)
    {
        return new TaskWorktree(id, "w1", new RepositoryIdentity("owner", "repo"), Path.of("/managed/worktrees", id.toString()), "bytequay/task/" + id, "a".repeat(40));
    }

    private static AgentTaskDefaults defaults(boolean reviewSummary)
    {
        return AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO)
                .withDevelopmentAutomation(new DevelopmentAutomation(true, true, true, Optional.empty(), reviewSummary));
    }

    private static ReviewCommentItem item(String key, String author, boolean bot, Instant createdAt)
    {
        return new ReviewCommentItem(anchor(key), author, bot, Optional.of(createdAt));
    }

    private static ReviewCommentAnchor anchor(String key)
    {
        return new ReviewCommentAnchor(REPO, 12, key, "{\"body\":\"" + key + "\"}");
    }

    private static TaskDiscussion thread(ReviewCommentAnchor comment)
    {
        return new TaskDiscussion("t-" + comment.key().replace(':', '-'), "task", comment.anchor(), Optional.of(comment), Optional.empty(), "claude",
                Optional.empty(), Optional.empty(), ImmutableList.of(), false, CREATED, CREATED, 0);
    }

    private static PullRequest pullRequest(int number, String state)
    {
        return new PullRequest(number, REPO, number, "Task", "me", "https://github.com/owner/repo/pull/" + number, CREATED, CREATED, PullRequest.Origin.AUTHORED,
                ImmutableList.of(), ImmutableMap.of(), false, null, null, null, ImmutableList.of(), null, 1, 1, 0, state, null, null, null, null, CREATED,
                "branch", null, null, null, null);
    }
}
