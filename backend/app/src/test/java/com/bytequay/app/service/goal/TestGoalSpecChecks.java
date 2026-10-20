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

import com.bytequay.app.context.ContextBuildService;
import com.bytequay.app.context.ContextSpecCheck;
import com.bytequay.app.context.ContextSpecCheckService;
import com.bytequay.app.context.ContextSpecModule;
import com.bytequay.app.workitem.SpecWorkItems;
import com.bytequay.app.workitem.WorkItemService;
import com.bytequay.app.workitem.WorkItemView;
import com.bytequay.core.domain.Goal;
import com.bytequay.core.domain.GoalRecommendation;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.store.GoalStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestGoalSpecChecks
{
    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");
    private static final String PROTOCOL = "https://github.com/delta-io/delta/blob/master/PROTOCOL.md";

    private final GoalStore goals = mock(GoalStore.class);
    private final InMemoryGoalRecommendationStore cards = new InMemoryGoalRecommendationStore();
    private final ContextSpecCheckService checks = mock(ContextSpecCheckService.class);
    private final ContextBuildService builds = mock(ContextBuildService.class);
    private final GoalConversationService conversation = mock(GoalConversationService.class);
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private final SpecWorkItems specWorkItems = mock(SpecWorkItems.class);
    private final WorkItemService work = mock(WorkItemService.class);
    private final GoalSpecChecks specChecks = new GoalSpecChecks(goals, cards, beans.getBeanProvider(WorkItemService.class), beans.getBeanProvider(ContextSpecCheckService.class),
            beans.getBeanProvider(ContextBuildService.class), beans.getBeanProvider(GoalConversationService.class),
            Clock.fixed(NOW, ZoneOffset.UTC), specWorkItems);

    @BeforeEach
    void setUp()
    {
        beans.registerSingleton("contextSpecCheckService", checks);
        beans.registerSingleton("contextBuildService", builds);
        beans.registerSingleton("goalConversationService", conversation);
        beans.registerSingleton("workItemService", work);
        when(goals.list("workspace")).thenReturn(ImmutableList.of(new Goal("goal-1", "workspace", "Delta Lake correctness", "", true, false, Optional.empty(),
                Optional.empty(), 0, Optional.empty(), false, NOW, NOW)));
        cards.save(new GoalRecommendation("card-1", "goal-1", "workspace", new GoalRecommendation.Card(GoalRecommendation.Kind.SPEC_CHECK,
                "Check the Delta checkpoint writer", "You know how checkpoints reconcile actions", "Checkpoint action reconciliation", ImmutableList.of(PROTOCOL),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("plugin/trino-delta-lake/"), Optional.of(3), Optional.empty(), Optional.empty(),
                Optional.empty()), GoalRecommendation.State.SHOWN, Optional.empty(), Optional.empty(), Optional.empty(), "op-1", NOW, NOW));
        when(builds.get("workspace")).thenReturn(new ContextBuildService.View(7, true, Optional.empty()));
    }

    @Test
    void startRunsTheCheckTheCardNamesAndItsEndSettlesTheCardAndTellsTheAgentOnce()
    {
        ContextSpecCheckService.Started running = new ContextSpecCheckService.Started(check(ContextSpecCheck.State.CHECKING),
                new ContextBuildService.View(8, false, Optional.empty()));
        when(checks.start(eq("workspace"), any())).thenReturn(running);
        GoalRecommendation started = specChecks.start("workspace", "goal-1", "card-1");
        verify(checks).start("workspace", new ContextSpecCheckService.StartRequest("goal-card-1", 7, "plugin/trino-delta-lake/", ImmutableList.of(PROTOCOL), 3));
        assertThat(started.checkId()).contains("check-1");
        assertThat(specChecks.start("workspace", "goal-1", "card-1")).isEqualTo(started);
        verify(checks, times(1)).start(any(), any());

        ContextSpecCheck.Candidate gap = candidate(ContextSpecCheck.Status.CONFIRMED, "Checkpoint keys actions by path only", Optional.empty());
        ContextSpecCheck.Candidate reported = candidate(ContextSpecCheck.Status.ALREADY_REPORTED, "Deletion vector on remove",
                Optional.of(new ContextSpecCheck.Duplicate(31000, "Remove loses its deletion vector", "open", "https://github.com/trinodb/trino/issues/31000")));
        ContextSpecCheck done = check(ContextSpecCheck.State.DONE, gap, reported);
        specChecks.ended("workspace", done);
        specChecks.ended("workspace", done);
        assertThat(cards.find("card-1")).map(GoalRecommendation::state).contains(GoalRecommendation.State.DONE);
        verify(conversation, times(1)).tell("workspace", "goal-1", "check:check-1",
                "The spec check of plugin/trino-delta-lake/ finished. It reproduced 1 gap: Checkpoint keys actions by path only. Already reported: #31000.");
    }

    @Test
    void aFailedCheckShowsItsCardAgainSoStartCanRetry()
    {
        ContextSpecCheckService.Started running = new ContextSpecCheckService.Started(check(ContextSpecCheck.State.CHECKING),
                new ContextBuildService.View(8, false, Optional.empty()));
        when(checks.start(eq("workspace"), any())).thenReturn(running);
        specChecks.start("workspace", "goal-1", "card-1");
        specChecks.ended("workspace", check(ContextSpecCheck.State.FAILED));
        GoalRecommendation card = cards.find("card-1").orElseThrow();
        assertThat(card.state()).isEqualTo(GoalRecommendation.State.SHOWN);
        assertThat(card.checkId()).isEmpty();
    }

    @Test
    void theLastChecksGapsBecomeGoalWorkOnceAndStayOpenUntilDoneOrDropped()
    {
        cards.save(cards.find("card-1").orElseThrow().checking("check-1", NOW).done(NOW));
        ContextSpecCheck.Candidate gap = candidate(ContextSpecCheck.Status.CONFIRMED, "Checkpoint keys actions by path only", Optional.empty());
        when(gap.id()).thenReturn("c-1");
        when(gap.rule()).thenReturn("Reconcile actions by path and deletion vector");
        when(gap.specUrl()).thenReturn(PROTOCOL);
        when(gap.test()).thenReturn(Optional.of(new ContextSpecCheck.Test("plugin/trino-delta-lake/src/test/java/TestCheckpoint.java", "mvn -Dtest=TestCheckpoint test", 1,
                "Rows lost", "provider")));
        when(gap.draft()).thenReturn(Optional.of(new ContextSpecCheck.Draft("Checkpoint loses rows with deletion vectors", "Body", ImmutableList.of("bug"))));
        ContextSpecCheck.Candidate refuted = candidate(ContextSpecCheck.Status.REFUTED, "Remove keeps its stats", Optional.empty());
        ContextSpecCheck check = check(ContextSpecCheck.State.DONE, gap, refuted);
        when(checks.checks("workspace")).thenReturn(ImmutableList.of(check));
        when(work.list("workspace")).thenReturn(ImmutableList.of());

        GoalGap open = specChecks.gaps("workspace", "goal-1").getFirst();
        assertThat(specChecks.gaps("workspace", "goal-1")).as("only reproduced gaps").hasSize(1);
        assertThat(open.open()).isTrue();
        assertThat(open.workItemId()).isEmpty();
        String id = SpecWorkItems.identity("workspace", "plugin/trino-delta-lake/", gap);
        when(work.list("workspace")).thenReturn(ImmutableList.of(new WorkItemView(id, "workspace", Optional.of("goal-1"), "Checkpoint loses rows", WorkItem.Kind.DEVELOP,
                "Passes", WorkItem.CompletionRule.MERGED_DELIVERY, WorkItem.State.DONE, 3, ImmutableList.of(), ImmutableList.of(), NOW, NOW,
                WorkItem.Priority.NORMAL, 0, ImmutableList.of(), Optional.empty(), 0, false, ImmutableList.of())));
        GoalGap done = specChecks.gaps("workspace", "goal-1").getFirst();
        assertThat(done.workItemId()).contains(id);
        assertThat(done.open()).as("a done gap lets the next spec check come").isFalse();
    }

    @Test
    void modulesAreReadOncePerMonitoredHead()
    {
        List<ContextSpecModule> found = ImmutableList.of(new ContextSpecModule("plugin/trino-delta-lake/", Optional.of(PROTOCOL), 42, 7));
        when(checks.modules("workspace")).thenReturn(found);
        when(builds.baseline("workspace")).thenReturn(Optional.of(new ContextBuildService.Baseline("master", "a".repeat(40))));
        assertThat(specChecks.modules("workspace")).isEqualTo(found);
        assertThat(specChecks.modules("workspace")).isEqualTo(found);
        verify(checks, times(1)).modules("workspace");
        when(builds.baseline("workspace")).thenReturn(Optional.of(new ContextBuildService.Baseline("master", "b".repeat(40))));
        specChecks.modules("workspace");
        verify(checks, times(2)).modules("workspace");
    }

    @Test
    void aGoalAdoptsTheExactRetainedLocalFinding()
    {
        cards.save(cards.find("card-1").orElseThrow().checking("check-1", NOW).done(NOW));
        ContextSpecCheck.Candidate gap = candidate(ContextSpecCheck.Status.CONFIRMED, "Checkpoint keys actions by path only", Optional.empty());
        when(gap.id()).thenReturn("c-1");
        when(gap.rule()).thenReturn("Keep deletion vectors");
        when(gap.specUrl()).thenReturn(PROTOCOL);
        when(gap.code()).thenReturn(ImmutableList.of("src/checkpoint.java:1"));
        when(gap.test()).thenReturn(Optional.empty());
        when(gap.draft()).thenReturn(Optional.of(new ContextSpecCheck.Draft("Checkpoint loses rows", "Full reproduction", ImmutableList.of("bug"))));
        ContextSpecCheck completed = check(ContextSpecCheck.State.DONE, gap);
        when(checks.checks("workspace")).thenReturn(ImmutableList.of(completed));
        String id = SpecWorkItems.identity("workspace", "plugin/trino-delta-lake/", gap);
        WorkItemView local = new WorkItemView(id, "workspace", Optional.empty(), "Checkpoint loses rows", WorkItem.Kind.DEVELOP, "Full reproduction",
                WorkItem.CompletionRule.MERGED_DELIVERY, WorkItem.State.PLANNED, 0, ImmutableList.of(), ImmutableList.of(), NOW, NOW,
                WorkItem.Priority.NORMAL, 0, ImmutableList.of(), Optional.empty(), 0, false, ImmutableList.of());
        WorkItemView adopted = new WorkItemView(id, "workspace", Optional.of("goal-1"), local.title(), local.kind(), local.acceptanceCriteria(), local.completionRule(),
                local.state(), 1, local.links(), local.history(), NOW, NOW, local.priority(), 0, ImmutableList.of(), Optional.empty(), 0, false, ImmutableList.of());
        when(work.list("workspace")).thenReturn(ImmutableList.of(local));
        assertThat(specChecks.gaps("workspace", "goal-1").getFirst().workItemId()).as("local work still offers Add to Goal").isEmpty();
        when(specWorkItems.save("workspace", "check-1", "c-1")).thenReturn(local);
        when(work.change(eq("workspace"), eq(id), any())).thenReturn(adopted);
        assertThat(specChecks.adopt("workspace", "goal-1", "c-1")).isEqualTo(adopted);
        when(work.list("workspace")).thenReturn(ImmutableList.of(adopted));
        assertThat(specChecks.gaps("workspace", "goal-1").getFirst().workItemId()).contains(id);
    }

    private static ContextSpecCheck check(ContextSpecCheck.State state, ContextSpecCheck.Candidate... candidates)
    {
        ContextSpecCheck check = mock(ContextSpecCheck.class, withSettings().mockMaker(MockMakers.INLINE));
        when(check.id()).thenReturn("check-1");
        when(check.directory()).thenReturn("plugin/trino-delta-lake/");
        when(check.state()).thenReturn(state);
        when(check.detail()).thenReturn(Optional.empty());
        when(check.candidates()).thenReturn(ImmutableList.copyOf(candidates));
        return check;
    }

    private static ContextSpecCheck.Candidate candidate(ContextSpecCheck.Status status, String title, Optional<ContextSpecCheck.Duplicate> duplicate)
    {
        ContextSpecCheck.Candidate candidate = mock(ContextSpecCheck.Candidate.class, withSettings().mockMaker(MockMakers.INLINE));
        when(candidate.status()).thenReturn(status);
        when(candidate.title()).thenReturn(title);
        when(candidate.duplicate()).thenReturn(duplicate);
        when(candidate.dismissed()).thenReturn(Optional.empty());
        return candidate;
    }
}
