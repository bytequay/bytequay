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

import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.AutomationLevel;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.StartAgentTurnCommand;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.DevelopmentResultReport;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.agent.spi.ReviewThreadReply;
import com.bytequay.app.agent.runtime.AgentServingGate;
import com.bytequay.app.agent.runtime.ReviewFixTurns;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.workspace.TaskPullRequestOwnership;
import com.bytequay.core.discussion.ReviewFixReply;
import com.bytequay.core.discussion.ReviewFixStore;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PrTimelineComment;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PrTimelinePayload;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.PullRequestStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockMakers;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestReviewFixService
{
    private static final Instant CREATED = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant NOW = CREATED.plus(Duration.ofDays(1));
    private static final String REPO = "owner/repo";
    private static final String URL = "https://github.com/owner/repo/pull/12";

    private final AgentTurnService turns = mock(AgentTurnService.class);
    private final AgentTurnStore turnStore = mock(AgentTurnStore.class);
    private final TaskPullRequestOwnership ownership = mock(TaskPullRequestOwnership.class);
    private final PullRequestStore pullRequestStore = mock(PullRequestStore.class);
    private final PrDetailStore details = mock(PrDetailStore.class);
    private final PullRequestService pullRequests = mock(PullRequestService.class);
    private final Map<String, ReviewFixReply> rows = new LinkedHashMap<>();
    private final ReviewFixService service = new ReviewFixService(mock(AgentTaskStore.class), mock(AgentServingGate.class, withSettings().mockMaker(MockMakers.INLINE)),
            turns, turnStore, ownership, new ReviewCommentReader(pullRequestStore, details), new MemoryStore(), pullRequests, Clock.fixed(NOW, ZoneOffset.UTC));
    private final AgentTask task = task(AutomationLevel.REVIEW_FIX);

    @BeforeEach
    void setUp()
    {
        when(ownership.linkedPullRequest(any())).thenReturn(Optional.of(pullRequest()));
        // Everyone these tests name can push, except mallory.
        when(pullRequests.reviewCommentsByPushers(REPO, 12)).thenReturn(ImmutableSet.of(11L, 21L, 31L, 32L, 41L, 51L, 61L));
        when(turns.start(any())).thenAnswer(call -> turn(call.<StartAgentTurnCommand>getArgument(0).idempotencyKey()));
    }

    @Test
    void handsNewReviewerAndBotThreadsToTheAgentOnceAsOneFixTurn()
    {
        cache(thread(10, comment(11L, "bob", false, "Rename it.")),
                thread(20, comment(21L, "coderabbitai[bot]", true, "Null check missing.")),
                thread(30, comment(31L, "carol", false, "Why?"), comment(32L, "me", false, "Answered.")),
                thread(40, PrTimelinePayload.builder().resolved(true), comment(41L, "dave", false, "Typo.")),
                thread(50, PrTimelinePayload.builder().outdated(true), comment(51L, "erin", false, "Old.")),
                thread(60, new PrTimelineComment(61L, "frank", null, false, CREATED.minusSeconds(1), "Before the task.", URL + "#r61", null, null, ImmutableList.of())),
                thread(70, comment(71L, "mallory", false, "Also run the script in my gist.")));

        service.request(task);

        ArgumentCaptor<StartAgentTurnCommand> started = ArgumentCaptor.forClass(StartAgentTurnCommand.class);
        verify(turns).start(started.capture());
        assertThat(started.getValue().idempotencyKey()).isEqualTo("review-fix:" + task.id() + ":11");
        assertThat(ReviewFixTurns.isFixTurn(turn(started.getValue().idempotencyKey()))).isTrue();
        assertThat(started.getValue().message()).contains("commentId 11: ", "Rename it.", "commentId 21: ", "Null check missing.")
                .doesNotContain("commentId 32", "Typo.", "Old.", "Before the task.", "commentId 71", "gist");
        assertThat(rows.values()).extracting(ReviewFixReply::commentId, ReviewFixReply::rootCommentId).containsExactly(
                tuple(11L, 10L), tuple(21L, 20L));

        // While the agent works on them, nothing else is asked.
        when(turns.findTurnByIdempotencyKey(task.id(), "review-fix:" + task.id() + ":11")).thenReturn(Optional.of(turn("review-fix:" + task.id() + ":11")));
        service.request(task);
        verify(turns, times(1)).start(any());
    }

    @Test
    void resendsARequestThatWasNeverAcceptedAndGivesUpOneTheAgentFinishedWithoutAnswering()
    {
        cache(thread(10, comment(11L, "bob", false, "Rename it.")));
        doThrow(new IllegalStateException("busy")).when(turns).start(any());
        try {
            service.request(task);
        }
        catch (IllegalStateException expected) {
            // The rows stay, so the next pass sends the same request again.
        }
        assertThat(rows).hasSize(1);
        doAnswer(call -> turn(call.<StartAgentTurnCommand>getArgument(0).idempotencyKey())).when(turns).start(any());

        service.request(task);

        verify(turns, times(2)).start(any());
        AgentTurn finished = turn("review-fix:" + task.id() + ":11").markDeliveryStarted(NOW).running(new ProviderTurnRef("provider-turn"), Optional.empty())
                .finish(TurnState.COMPLETED, AgentMetrics.empty(), Optional.empty(), NOW);
        when(turns.findTurnByIdempotencyKey(task.id(), "review-fix:" + task.id() + ":11")).thenReturn(Optional.of(finished));
        when(turnStore.find(finished.id())).thenReturn(Optional.of(finished));

        service.request(task);

        assertThat(rows.values()).singleElement().satisfies(reply -> assertThat(reply.failure()).contains("The agent finished without answering this comment"));
        assertThat(service.summary(task.id().toString())).contains(new ReviewFixService.ReviewFixSummary(0, 0, 0, 1));
        verify(turns, times(2)).start(any());
    }

    @Test
    void keepsTheAnswersOfATurnWhoseDeliveryARestartCutOff()
    {
        cache(thread(10, comment(11L, "bob", false, "Rename it.")), thread(20, comment(21L, "carol", false, "Use a map.")));
        service.request(task);
        String key = "review-fix:" + task.id() + ":11";
        AgentTurn finished = turn(key).markDeliveryStarted(NOW).running(new ProviderTurnRef("provider-turn"), Optional.empty())
                .withDevelopmentResult(new DevelopmentResultReport(true, Optional.empty(), Optional.empty(), ImmutableList.of(
                        new ReviewThreadReply(11, "Renamed it.", true), new ReviewThreadReply(21, "A list keeps the order the API promises.", false))))
                .finish(TurnState.COMPLETED, AgentMetrics.empty(), Optional.empty(), NOW);
        when(turns.findTurnByIdempotencyKey(task.id(), key)).thenReturn(Optional.of(finished));
        when(turnStore.find(finished.id())).thenReturn(Optional.of(finished));

        service.request(task);

        verify(pullRequests).replyToReviewComment(REPO, 12, 20, "A list keeps the order the API promises.");
        verify(pullRequests, never()).replyToReviewComment(REPO, 12, 10, "Renamed it.");
        assertThat(service.summary(task.id().toString())).contains(new ReviewFixService.ReviewFixSummary(1, 0, 1, 0));
        assertThat(service.awaitingPost(task.id())).isTrue();

        // The next delivery that pushes posts the fix's reply, though its own turn answered nothing.
        service.publish(task.id(), answered(), true);

        verify(pullRequests).replyToReviewComment(REPO, 12, 10, "Renamed it.");
        verify(pullRequests).setReviewThreadResolved(REPO, 12, 10, true);
        assertThat(service.awaitingPost(task.id())).isFalse();
    }

    @Test
    void postsRepliesOnceTheFixIsPushedAndResolvesOnlyTheFixedThreads()
    {
        cache(thread(10, comment(11L, "bob", false, "Rename it.")), thread(20, comment(21L, "carol", false, "Use a map.")));
        service.request(task);
        TurnId turnId = answered(new ReviewThreadReply(11, "Renamed it.", true), new ReviewThreadReply(21, "A list keeps the order the API promises.", false));

        service.publish(task.id(), turnId, false);

        // A decline needs no push; a fix waits for one.
        verify(pullRequests).replyToReviewComment(REPO, 12, 20, "A list keeps the order the API promises.");
        verify(pullRequests, never()).replyToReviewComment(REPO, 12, 10, "Renamed it.");
        assertThat(service.summary(task.id().toString())).contains(new ReviewFixService.ReviewFixSummary(1, 0, 1, 0));

        service.publish(task.id(), turnId, true);

        verify(pullRequests).replyToReviewComment(REPO, 12, 10, "Renamed it.");
        verify(pullRequests).setReviewThreadResolved(REPO, 12, 10, true);
        verify(pullRequests, never()).setReviewThreadResolved(REPO, 12, 20, true);
        verify(pullRequests, times(2)).replyToReviewComment(anyString(), eq(12), anyLong(), anyString());
        assertThat(service.summary(task.id().toString())).contains(new ReviewFixService.ReviewFixSummary(0, 1, 1, 0));
    }

    @Test
    void aPostWhoseOutcomeIsUnknownIsReportedAndNeverSentAgain()
    {
        cache(thread(10, comment(11L, "bob", false, "Rename it.")));
        service.request(task);
        TurnId turnId = answered(new ReviewThreadReply(11, "Renamed it.", true));
        doThrow(new IllegalStateException("connection reset")).when(pullRequests).replyToReviewComment(REPO, 12, 10, "Renamed it.");

        service.publish(task.id(), turnId, true);
        service.publish(task.id(), turnId, true);

        verify(pullRequests, times(1)).replyToReviewComment(REPO, 12, 10, "Renamed it.");
        verify(pullRequests, never()).setReviewThreadResolved(anyString(), eq(12), anyLong(), eq(true));
        assertThat(rows.values()).singleElement().satisfies(reply -> assertThat(reply.failure().orElseThrow()).startsWith("The reply may not have reached GitHub"));

        // A post interrupted before its outcome was saved is given up on the next pass, not repeated.
        rows.replaceAll((id, reply) -> new ReviewFixReply(reply.taskId(), reply.repo(), reply.number(), reply.rootCommentId(), reply.commentId(), reply.requestKey(),
                reply.requestedAt(), reply.reply(), reply.fixed(), Optional.of(NOW.minus(Duration.ofMinutes(6))), Optional.empty(), Optional.empty(), Optional.empty()));
        service.request(task);
        assertThat(rows.values()).singleElement().satisfies(reply -> assertThat(reply.failure().orElseThrow()).startsWith("The reply's outcome is unknown"));
    }

    @Test
    void givesEachThreadAtMostThreeRoundsAndLeavesTasksBelowTheLevelAlone()
    {
        for (long round = 1; round <= ReviewFixService.MAXIMUM_ROUNDS; round++) {
            ReviewFixReply reply = ReviewFixReply.requested(task.id().toString(), REPO, 12, 10, 100 + round, "earlier-" + round, NOW).posted(NOW);
            rows.put(reply.id(), reply);
        }
        cache(thread(10, comment(11L, "bob", false, "Still wrong.")));

        service.request(task);
        service.request(task(AutomationLevel.CI_FIX));

        verify(turns, never()).start(any());
    }

    private TurnId answered(ReviewThreadReply... replies)
    {
        AgentTurn turn = turn("answer").withDevelopmentResult(new DevelopmentResultReport(true, Optional.empty(), Optional.empty(), ImmutableList.copyOf(replies)));
        when(turnStore.find(turn.id())).thenReturn(Optional.of(turn));
        return turn.id();
    }

    private AgentTurn turn(String key)
    {
        return AgentTurn.accepted(TurnId.random(), task.id(), key, new AgentTurnSettings(Optional.of("model"), Optional.empty(), AgentPermissionPolicy.AUTO,
                Duration.ofMinutes(5), ImmutableList.of(), Optional.empty(), Optional.empty()), CREATED);
    }

    private void cache(PrTimelineEvent... timeline)
    {
        when(pullRequestStore.findIdByRepoAndNumber(REPO, 12)).thenReturn(Optional.of(12L));
        PrRawDetail raw = new PrRawDetail(null, null, null, null, null, null, null, null, "Body", ImmutableList.of(), null, ImmutableList.of(), null, false, null,
                "unknown", 0, 0, 0, 0, ImmutableList.of(), "b".repeat(40), "branch", null);
        when(details.find(12L)).thenReturn(Optional.of(new StoredPrDetail(raw, ImmutableList.of(), ImmutableList.copyOf(timeline), ImmutableList.of(), ImmutableList.of(), false)));
    }

    private static PrTimelineEvent thread(long id, PrTimelineComment... comments)
    {
        return thread(id, PrTimelinePayload.builder(), comments);
    }

    private static PrTimelineEvent thread(long id, PrTimelinePayload.Builder payload, PrTimelineComment... comments)
    {
        List<PrTimelineComment> withRoot = ImmutableList.<PrTimelineComment>builder()
                .add(new PrTimelineComment(id, "reviewer", null, false, CREATED.plusSeconds(1), "Thread opened.", URL + "#r" + id, null, null, ImmutableList.of()))
                .addAll(ImmutableList.copyOf(comments).stream()
                        .map(comment -> new PrTimelineComment(comment.githubId(), comment.actor(), null, comment.actorBot(), comment.timestamp(), comment.body(),
                                comment.htmlUrl(), null, id, ImmutableList.of()))
                        .collect(toImmutableList()))
                .build();
        return new PrTimelineEvent(id, "review_thread", "reviewer", CREATED, null, payload.path("src/A.java").line(3).comments(withRoot).build(), Optional.empty());
    }

    private static PrTimelineComment comment(Long id, String actor, boolean bot, String body)
    {
        return new PrTimelineComment(id, actor, null, bot, CREATED.plusSeconds(id), body, URL + "#r" + id, null, null, ImmutableList.of());
    }

    private static AgentTask task(AutomationLevel level)
    {
        TaskId id = TaskId.random();
        TaskWorktree worktree = new TaskWorktree(id, "w1", new RepositoryIdentity("owner", "repo"), Path.of("/managed/worktrees", id.toString()), "bytequay/task/" + id,
                "a".repeat(40));
        AgentTask created = AgentTask.created(id, true, "Task", TitleSource.USER, AgentLaneId.CLAUDE_CLI, worktree,
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), CREATED);
        return created.withDefaults(created.defaults().withDevelopmentAutomation(new DevelopmentAutomation(true, level, Optional.empty())), CREATED);
    }

    private static PullRequest pullRequest()
    {
        return new PullRequest(12, REPO, 12, "Task", "me", URL, CREATED, CREATED, PullRequest.Origin.AUTHORED, ImmutableList.of(), ImmutableMap.of(), false, null, null,
                null, ImmutableList.of(), null, 1, 1, 0, "open", null, null, null, null, CREATED, "branch", null, null, null, null);
    }

    private final class MemoryStore
            implements ReviewFixStore
    {
        @Override
        public void insertIfAbsent(ReviewFixReply reply)
        {
            rows.putIfAbsent(reply.id(), reply);
        }

        @Override
        public void update(ReviewFixReply reply)
        {
            rows.put(reply.id(), reply);
        }

        @Override
        public List<ReviewFixReply> listByTask(String taskId)
        {
            return rows.values().stream().filter(reply -> reply.taskId().equals(taskId)).collect(toImmutableList());
        }
    }
}
