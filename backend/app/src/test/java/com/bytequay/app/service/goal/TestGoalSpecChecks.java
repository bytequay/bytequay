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
import com.bytequay.core.domain.Goal;
import com.bytequay.core.domain.GoalRecommendation;
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
    private final GoalSpecChecks specChecks = new GoalSpecChecks(goals, cards, beans.getBeanProvider(ContextSpecCheckService.class),
            beans.getBeanProvider(ContextBuildService.class), beans.getBeanProvider(GoalConversationService.class), Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp()
    {
        beans.registerSingleton("contextSpecCheckService", checks);
        beans.registerSingleton("contextBuildService", builds);
        beans.registerSingleton("goalConversationService", conversation);
        when(goals.list("workspace")).thenReturn(ImmutableList.of(new Goal("goal-1", "workspace", "Delta Lake correctness", "", true, false, Optional.empty(),
                Optional.empty(), Optional.empty(), 0, Optional.empty(), NOW, NOW)));
        cards.save(new GoalRecommendation("card-1", "goal-1", "workspace", new GoalRecommendation.Card(GoalRecommendation.Kind.SPEC_CHECK,
                "Check the Delta checkpoint writer", "You know how checkpoints reconcile actions", "Checkpoint action reconciliation", ImmutableList.of(PROTOCOL),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("plugin/trino-delta-lake/"), Optional.of(3)), GoalRecommendation.State.SHOWN,
                Optional.empty(), Optional.empty(), Optional.empty(), "op-1", NOW, NOW));
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
