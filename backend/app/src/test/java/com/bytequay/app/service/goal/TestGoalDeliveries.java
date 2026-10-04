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
package com.bytequay.app.service.goal;

import com.bytequay.app.review.PullRequestDetailRefreshed;
import com.bytequay.app.review.PullRequestsRefreshed;
import com.bytequay.app.service.pr.PullRequestRefreshService;
import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.WorkItemEvent;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestGoalDeliveries
{
    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final WorkItemService work = mock(WorkItemService.class);
    private final GoalConversationService conversation = mock(GoalConversationService.class);
    private final PullRequestRefreshService refresh = mock(PullRequestRefreshService.class);
    private final PullRequestStore pullRequests = mock(PullRequestStore.class);
    private final PrDetailStore details = mock(PrDetailStore.class);
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private final GoalDeliveries deliveries = new GoalDeliveries(workspaces, beans.getBeanProvider(WorkItemService.class),
            beans.getBeanProvider(GoalConversationService.class), beans.getBeanProvider(PullRequestRefreshService.class), pullRequests, details, Runnable::run);

    @BeforeEach
    void setUp()
    {
        beans.registerSingleton("workItemService", work);
        beans.registerSingleton("goalConversationService", conversation);
        beans.registerSingleton("pullRequestRefreshService", refresh);
        Workspace workspace = mock(Workspace.class, withSettings().mockMaker(MockMakers.INLINE));
        when(workspace.id()).thenReturn("workspace");
        when(workspaces.findAll()).thenReturn(ImmutableList.of(workspace));
    }

    @Test
    void aMergeIsRecordedOnceOnTheGoalWorkItDeliversAndToldToTheAgent()
    {
        when(work.list("workspace")).thenReturn(ImmutableList.of(item(Optional.of("goal-1"), WorkItem.State.WORKING, ImmutableList.of(), pull(31260, GoalItemState.MERGED))));
        deliveries.detailRefreshed(new PullRequestDetailRefreshed("trinodb/trino", 31261, Optional.empty()));
        verify(work, never()).receipt(anyString(), anyString(), anyString(), anyString());

        deliveries.detailRefreshed(new PullRequestDetailRefreshed("trinodb/trino", 31260, Optional.empty()));
        verify(work).receipt("workspace", "work-1", "merged:trinodb/trino#31260", "Pull request #31260 merged: Key actions by path and deletion vector");
        verify(conversation).tell("workspace", "goal-1", "merged:trinodb/trino#31260:work-1",
                "Pull request #31260 for \"Fix #31252\" merged. The user can now accept the result.");

        WorkItemEvent recorded = new WorkItemEvent("merged:trinodb/trino#31260", "", "RECEIPT", "Pull request #31260 merged", NOW);
        when(work.list("workspace")).thenReturn(ImmutableList.of(item(Optional.of("goal-1"), WorkItem.State.WORKING, ImmutableList.of(recorded),
                pull(31260, GoalItemState.MERGED))));
        deliveries.restore();
        verify(work, times(1)).receipt(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void aReviewWhoseReviewerStillRequestsChangesIsRecordedOnceAndToldToTheAgent()
    {
        when(work.list("workspace")).thenReturn(ImmutableList.of(item(Optional.of("goal-1"), WorkItem.State.WORKING, ImmutableList.of(), pull(31260, GoalItemState.WORKING))));
        when(pullRequests.findIdByRepoAndNumber("trinodb/trino", 31260)).thenReturn(Optional.of(7L));
        StoredPrDetail detail = mock(StoredPrDetail.class, withSettings().mockMaker(MockMakers.INLINE));
        when(detail.reviewsComplete()).thenReturn(true);
        when(detail.reviews()).thenReturn(ImmutableList.of(review(1, "alice", "CHANGES_REQUESTED", 120, "Rename the field"), review(2, "alice", "APPROVED", 60, ""),
                review(3, "bob", "CHANGES_REQUESTED", 30, "Add a test for deletion vectors"), review(4, "carol", "COMMENTED", 10, "Nice")));
        when(details.find(7L)).thenReturn(Optional.of(detail));

        deliveries.detailRefreshed(new PullRequestDetailRefreshed("trinodb/trino", 31260, Optional.empty()));
        verify(work).receipt("workspace", "work-1", "changes:3", "@bob requested changes on #31260: Add a test for deletion vectors");
        verify(conversation).tell("workspace", "goal-1", "changes:3:work-1",
                "@bob requested changes on pull request #31260 for \"Fix #31252\": Add a test for deletion vectors");
        verify(work, never()).receipt(anyString(), anyString(), eq("changes:1"), anyString());

        WorkItemEvent recorded = new WorkItemEvent("changes:3", "", "RECEIPT", "@bob requested changes on #31260", NOW);
        when(work.list("workspace")).thenReturn(ImmutableList.of(item(Optional.of("goal-1"), WorkItem.State.WORKING, ImmutableList.of(recorded),
                pull(31260, GoalItemState.WORKING))));
        deliveries.restore();
        verify(work, times(1)).receipt(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void aListRefreshPicksUpTheTasksPullRequestAndRefreshesOnlyTheOpenOnes()
    {
        WorkItemView item = item(Optional.of("goal-1"), WorkItem.State.WORKING, ImmutableList.of());
        when(work.list("workspace")).thenReturn(ImmutableList.of(item));
        when(work.linkRelated("workspace", "work-1")).thenReturn(item(Optional.of("goal-1"), WorkItem.State.WORKING, ImmutableList.of(),
                pull(31260, GoalItemState.WORKING), pull(31100, GoalItemState.ENDED)));
        deliveries.listsRefreshed(new PullRequestsRefreshed());
        verify(refresh).refreshDetail("trinodb/trino", 31260);
        verify(refresh, never()).refreshDetail("trinodb/trino", 31100);
    }

    @Test
    void concludedWorkAndWorkOutsideAGoalAreNotFollowed()
    {
        when(work.list("workspace")).thenReturn(ImmutableList.of(item(Optional.of("goal-1"), WorkItem.State.DONE, ImmutableList.of(), pull(31260, GoalItemState.MERGED)),
                item(Optional.empty(), WorkItem.State.WORKING, ImmutableList.of(), pull(31260, GoalItemState.WORKING))));
        deliveries.listsRefreshed(new PullRequestsRefreshed());
        verify(work, never()).linkRelated(anyString(), anyString());
        verify(refresh, never()).refreshDetail(anyString(), anyInt());
        verify(work, never()).receipt(anyString(), anyString(), anyString(), anyString());
        verify(conversation, never()).tell(any(), any(), any(), any());
    }

    private static WorkItemView item(Optional<String> goalId, WorkItem.State state, List<WorkItemEvent> history, GoalItemView... links)
    {
        return new WorkItemView("work-1", "workspace", goalId, "Fix #31252", WorkItem.Kind.DEVELOP, "Rows survive a checkpoint", WorkItem.CompletionRule.MERGED_DELIVERY,
                state, 3, ImmutableList.copyOf(links), history, NOW, NOW, WorkItem.Priority.NORMAL, 0, ImmutableList.of());
    }

    private static PrReviewState review(long id, String login, String state, int minutesAgo, String body)
    {
        return new PrReviewState(id, login, "", false, state, NOW.minusSeconds(minutesAgo * 60L), body, "", "head");
    }

    private static GoalItemView pull(int number, GoalItemState state)
    {
        return new GoalItemView(GoalItemKind.PULL_REQUEST, "trinodb/trino#" + number, "#" + number, "Key actions by path and deletion vector", state,
                state == GoalItemState.MERGED ? "Merged" : state == GoalItemState.ENDED ? "Closed" : "Open", false, Optional.empty(), Optional.empty(), Optional.of(NOW));
    }
}
