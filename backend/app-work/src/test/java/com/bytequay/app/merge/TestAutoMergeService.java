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
package com.bytequay.app.merge;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AutomationLevel;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.agent.runtime.AgentServingGate;
import com.bytequay.app.discussion.ReviewFixService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.settings.AppPreferences;
import com.bytequay.app.service.settings.AppSettingsService;
import com.bytequay.app.service.settings.AppTheme;
import com.bytequay.app.workspace.TaskPullRequestOwnership;
import com.bytequay.core.ci.CiAutofixStore;
import com.bytequay.core.domain.MergeResult;
import com.bytequay.core.domain.PrMergeQueueRemoval;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PrTimelinePayload;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.merge.AutoMerge;
import com.bytequay.core.merge.AutoMergeStore;
import com.bytequay.core.store.PrDetailStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.bytequay.agent.core.AgentTaskFixtures.created;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestAutoMergeService
{
    private static final Instant CREATED = Instant.parse("2026-10-01T00:00:00Z");
    private static final String REPO = "owner/repo";
    private static final String HEAD = "c".repeat(40);

    private final MutableClock clock = new MutableClock(CREATED.plus(Duration.ofDays(1)));
    private final TaskPullRequestOwnership ownership = mock(TaskPullRequestOwnership.class);
    private final PullRequestService pullRequests = mock(PullRequestService.class);
    private final PrDetailStore details = mock(PrDetailStore.class);
    private final CiAutofixStore ciRuns = mock(CiAutofixStore.class);
    private final ReviewFixService reviewFixes = mock(ReviewFixService.class);
    private final AppSettingsService settings = mock(AppSettingsService.class);
    private final Map<String, AutoMerge> rows = new HashMap<>();
    private final AutoMergeService service = new AutoMergeService(mock(AgentTaskStore.class), mock(AgentServingGate.class, withSettings().mockMaker(MockMakers.INLINE)),
            ownership, pullRequests, details, ciRuns, reviewFixes, new MemoryStore(), settings, clock);
    private final AgentTask task = task();

    @BeforeEach
    void setUp()
    {
        when(ownership.linkedPullRequest(any())).thenReturn(Optional.of(pullRequest()));
        when(reviewFixes.settled(any(), any())).thenReturn(true);
        when(settings.preferences()).thenReturn(new AppPreferences(7, 14, AppTheme.SYSTEM, 30, 60));
        when(pullRequests.mergePullRequest(anyString(), anyInt(), anyLong(), anyString(), anyString())).thenReturn(new MergeResult(HEAD, true, "Merged"));
        cache(HEAD, false, "clean");
    }

    @Test
    void waitsForTheDelayAfterThePullRequestBecomesMergeableThenRebaseMergesTheCheckedHead()
    {
        service.advance(task);
        assertThat(summary().state()).isEqualTo("READY");
        assertThat(summary().mergeAt()).contains(clock.instant().plus(Duration.ofHours(1)));

        clock.advance(Duration.ofMinutes(59));
        service.advance(task);
        verify(pullRequests, never()).mergePullRequest(anyString(), anyInt(), anyLong(), anyString(), anyString());

        clock.advance(Duration.ofMinutes(1));
        service.advance(task);
        verify(pullRequests).mergePullRequest(REPO, 12, 12L, "rebase", HEAD);
        assertThat(summary().state()).isEqualTo("MERGED");

        service.advance(task);
        verify(pullRequests, times(1)).mergePullRequest(anyString(), anyInt(), anyLong(), anyString(), anyString());
    }

    @Test
    void joinsTheMergeQueueWhenTheBaseBranchHasOne()
    {
        when(settings.preferences()).thenReturn(new AppPreferences(7, 14, AppTheme.SYSTEM, 30, 0));
        when(pullRequests.hasMergeQueue(REPO, "main")).thenReturn(true);
        when(pullRequests.mergePullRequest(REPO, 12, 12L, "queue", HEAD)).thenReturn(MergeResult.enqueued("Queued at position 2"));

        service.advance(task);

        assertThat(summary()).isEqualTo(new AutoMergeService.AutoMergeSummary("QUEUED", Optional.empty(), Optional.of("Queued at position 2")));
    }

    @Test
    void aRebaseGitHubTurnsIntoAQueueEntryIsQueued()
    {
        when(settings.preferences()).thenReturn(new AppPreferences(7, 14, AppTheme.SYSTEM, 30, 0));
        when(pullRequests.mergePullRequest(REPO, 12, 12L, "rebase", HEAD)).thenReturn(MergeResult.enqueued("Queued at position 1"));

        service.advance(task);

        assertThat(summary()).isEqualTo(new AutoMergeService.AutoMergeSummary("QUEUED", Optional.empty(), Optional.of("Queued at position 1")));
    }

    @Test
    void aQueuedMergeStaysUnderwayUntilGitHubMergesThePullRequest()
    {
        joinQueue();
        cache(HEAD, false, "clean", queueEvent("added_to_merge_queue", clock.instant().plusSeconds(5), PrTimelinePayload.empty()));
        clock.advance(Duration.ofHours(2));

        service.advance(task);

        verify(pullRequests, times(1)).mergePullRequest(anyString(), anyInt(), anyLong(), anyString(), anyString());
        assertThat(summary().state()).isEqualTo("QUEUED");
        assertThat(service.summary(task.id().toString(), Optional.of(pullRequest("merged"))).orElseThrow().state()).isEqualTo("MERGED");
        // A closed pull request is never linked.
        assertThat(service.summary(task.id().toString(), Optional.empty()).orElseThrow().state()).isEqualTo("CANCELLED");
    }

    @Test
    void aPullRequestTheQueueDropsWaitsForANewHeadAndThenQueuesAgain()
    {
        Instant sent = joinQueue();
        PrMergeQueueRemoval checksFailed = new PrMergeQueueRemoval(Optional.of("checks failed"), Optional.empty(), ImmutableList.of());
        PrTimelineEvent added = queueEvent("added_to_merge_queue", sent.plusSeconds(5), PrTimelinePayload.empty());
        cache(HEAD, false, "clean", added, queueEvent("removed_from_merge_queue", sent.plusSeconds(60), PrTimelinePayload.builder().mergeQueueRemoval(checksFailed).build()));

        service.advance(task);
        service.advance(task);

        assertThat(summary().state()).isEqualTo("FAILED");
        assertThat(summary().message()).contains("The merge queue removed the pull request (checks failed); a new push starts the wait again");
        verify(pullRequests, times(1)).mergePullRequest(anyString(), anyInt(), anyLong(), anyString(), anyString());

        String next = "d".repeat(40);
        cache(next, false, "clean");
        when(pullRequests.mergePullRequest(REPO, 12, 12L, "queue", next)).thenReturn(MergeResult.enqueued("Queued at position 1"));
        service.advance(task);
        verify(pullRequests).mergePullRequest(REPO, 12, 12L, "queue", next);
        assertThat(summary().state()).isEqualTo("QUEUED");
    }

    @Test
    void turningAutoMergeOffBeforeThePullRequestJoinsTheQueueStopsWaitingForIt()
    {
        Instant sent = joinQueue();
        cache(HEAD, false, "clean", queueEvent("auto_merge_disabled", sent.plusSeconds(30), PrTimelinePayload.empty()));

        service.advance(task);

        assertThat(summary().state()).isEqualTo("FAILED");
        assertThat(summary().message().orElseThrow()).startsWith("Auto-merge was turned off before the pull request joined the merge queue");
    }

    @Test
    void aRemovalFromBeforeTheMergeWasSentIsNotADrop()
    {
        Instant sent = joinQueue();
        cache(HEAD, false, "clean", queueEvent("removed_from_merge_queue", sent.minus(Duration.ofHours(1)), PrTimelinePayload.empty()));

        service.advance(task);

        assertThat(summary().state()).isEqualTo("QUEUED");
    }

    /** Sends the merge to the base branch's queue at once and returns when it was sent. */
    private Instant joinQueue()
    {
        when(settings.preferences()).thenReturn(new AppPreferences(7, 14, AppTheme.SYSTEM, 30, 0));
        when(pullRequests.hasMergeQueue(REPO, "main")).thenReturn(true);
        when(pullRequests.mergePullRequest(REPO, 12, 12L, "queue", HEAD)).thenReturn(MergeResult.enqueued("Queued at position 2"));
        Instant sent = clock.instant();
        service.advance(task);
        assertThat(summary().state()).isEqualTo("QUEUED");
        return sent;
    }

    @Test
    void anyBlockerRestartsTheWaitAndSaysWhy()
    {
        service.advance(task);
        Instant firstReady = summary().mergeAt().orElseThrow();

        cache(HEAD, false, "blocked");
        clock.advance(Duration.ofMinutes(30));
        service.advance(task);
        assertThat(summary()).isEqualTo(new AutoMergeService.AutoMergeSummary("WAITING", Optional.empty(), Optional.of("Waiting for GitHub to allow the merge (blocked)")));

        cache(HEAD, true, "clean");
        service.advance(task);
        assertThat(summary().message()).contains("Waiting for the pull request to leave draft");

        cache(HEAD, false, "clean");
        when(reviewFixes.settled(any(), any())).thenReturn(false);
        service.advance(task);
        assertThat(summary().message()).contains("Waiting for open review threads");

        when(reviewFixes.settled(any(), any())).thenReturn(true);
        service.advance(task);
        assertThat(summary().mergeAt()).contains(clock.instant().plus(Duration.ofHours(1))).isNotEqualTo(Optional.of(firstReady));
    }

    @Test
    void unavailableReviewThreadsBlockMergeAndRestartTheDelay()
    {
        service.advance(task);
        clock.advance(Duration.ofHours(1));
        StoredPrDetail complete = details.find(12L).orElseThrow();
        when(details.find(12L)).thenReturn(Optional.of(new StoredPrDetail(complete.raw(), complete.reviews(), complete.timeline(), complete.checkRuns(),
                ImmutableList.of(StoredPrDetail.REVIEW_THREADS), complete.viewerCanWrite())));

        service.advance(task);

        assertThat(summary()).isEqualTo(new AutoMergeService.AutoMergeSummary("WAITING", Optional.empty(), Optional.of("Waiting for review threads to refresh")));
        verify(pullRequests, never()).mergePullRequest(anyString(), anyInt(), anyLong(), anyString(), anyString());

        when(details.find(12L)).thenReturn(Optional.of(complete));
        service.advance(task);
        assertThat(summary().mergeAt()).contains(clock.instant().plus(Duration.ofHours(1)));
        clock.advance(Duration.ofMinutes(59));
        service.advance(task);
        verify(pullRequests, never()).mergePullRequest(anyString(), anyInt(), anyLong(), anyString(), anyString());

        clock.advance(Duration.ofMinutes(1));
        service.advance(task);
        verify(pullRequests).mergePullRequest(REPO, 12, 12L, "rebase", HEAD);
    }

    @Test
    void aFailedMergeWaitsForANewHeadAndAnUnfinishedOneIsNeverSentAgain()
    {
        when(settings.preferences()).thenReturn(new AppPreferences(7, 14, AppTheme.SYSTEM, 30, 0));
        doThrow(new IllegalStateException("Head branch was modified")).when(pullRequests).mergePullRequest(REPO, 12, 12L, "rebase", HEAD);

        service.advance(task);
        service.advance(task);

        verify(pullRequests, times(1)).mergePullRequest(anyString(), anyInt(), anyLong(), anyString(), anyString());
        assertThat(summary().state()).isEqualTo("FAILED");
        assertThat(summary().message().orElseThrow()).contains("Head branch was modified");

        String next = "d".repeat(40);
        cache(next, false, "clean");
        when(pullRequests.mergePullRequest(REPO, 12, 12L, "rebase", next)).thenReturn(new MergeResult(next, true, "Merged"));
        service.advance(task);
        assertThat(summary().state()).isEqualTo("MERGED");

        // A merge interrupted before its outcome was saved is reported, never repeated.
        rows.put(task.id().toString(), AutoMerge.waiting(task.id().toString(), HEAD, "ready").ready(clock.instant()).started(clock.instant()));
        cache(HEAD, false, "clean");
        service.advance(task);
        assertThat(summary().state()).isEqualTo("FAILED");
        assertThat(summary().message().orElseThrow()).startsWith("The merge's outcome is unknown");
        verify(pullRequests, times(1)).mergePullRequest(REPO, 12, 12L, "rebase", HEAD);
    }

    @Test
    void stoppingAWaitingMergeKeepsItStopped()
    {
        service.advance(task);
        service.cancel(task.id().toString());
        clock.advance(Duration.ofHours(2));

        service.advance(task);

        verify(pullRequests, never()).mergePullRequest(anyString(), anyInt(), anyLong(), anyString(), anyString());
        assertThat(summary().state()).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> service.cancel(task.id().toString())).isInstanceOf(IllegalStateException.class);
    }

    private AutoMergeService.AutoMergeSummary summary()
    {
        return service.summary(task.id().toString(), Optional.of(pullRequest())).orElseThrow();
    }

    private void cache(String head, boolean draft, String mergeableState, PrTimelineEvent... timeline)
    {
        PrRawDetail raw = new PrRawDetail(null, null, "open", "me", null, null, null, null, "Body", ImmutableList.of(), null, ImmutableList.of(), null, draft, true,
                mergeableState, 0, 0, 0, 0, ImmutableList.of(), head, "branch", "main", "b".repeat(40));
        when(details.find(12L)).thenReturn(Optional.of(new StoredPrDetail(raw, ImmutableList.of(), ImmutableList.copyOf(timeline), ImmutableList.of(), ImmutableList.of(), false)));
    }

    private static AgentTask task()
    {
        TaskId id = TaskId.random();
        TaskWorktree worktree = new TaskWorktree(id, "w1", new RepositoryIdentity("owner", "repo"), Path.of("/managed/worktrees", id.toString()), "bytequay/task/" + id,
                "a".repeat(40));
        AgentTask created = created(id, true, "Task", TitleSource.USER, AgentLaneId.CLAUDE_CLI, worktree,
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), CREATED);
        return created.withDefaults(created.defaults().withDevelopmentAutomation(new DevelopmentAutomation(true, AutomationLevel.MERGE, Optional.empty())), CREATED);
    }

    private static PrTimelineEvent queueEvent(String event, Instant at, PrTimelinePayload payload)
    {
        return new PrTimelineEvent(1L, event, "github", at, null, payload, Optional.empty());
    }

    private static PullRequest pullRequest()
    {
        return pullRequest("open");
    }

    private static PullRequest pullRequest(String state)
    {
        return new PullRequest(12, REPO, 12, "Task", "me", "https://github.com/owner/repo/pull/12", CREATED, CREATED, PullRequest.Origin.AUTHORED, ImmutableList.of(),
                ImmutableMap.of(), false, null, null, null, ImmutableList.of(), null, 1, 1, 0, state, null, null, null, null, CREATED, "branch", null, null, null, null);
    }

    private final class MemoryStore
            implements AutoMergeStore
    {
        @Override
        public Optional<AutoMerge> find(String taskId)
        {
            return Optional.ofNullable(rows.get(taskId));
        }

        @Override
        public void save(AutoMerge autoMerge)
        {
            rows.put(autoMerge.taskId(), autoMerge);
        }
    }

    private static final class MutableClock
            extends Clock
    {
        private Instant now;

        MutableClock(Instant now)
        {
            this.now = now;
        }

        void advance(Duration duration)
        {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone()
        {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone)
        {
            return this;
        }

        @Override
        public Instant instant()
        {
            return now;
        }
    }
}
