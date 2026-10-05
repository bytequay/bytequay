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

import com.bytequay.core.domain.GoalDefinition;
import com.bytequay.core.domain.GoalExchange;
import com.bytequay.core.store.GoalExchangeStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestGoalDiscovery
{
    private static final Instant ASKED = Instant.parse("2026-10-05T10:00:00Z");

    private final GoalCandidates candidates = mock(GoalCandidates.class);
    private final GoalExchangeStore exchanges = mock(GoalExchangeStore.class);
    private final GoalConversationService conversations = mock(GoalConversationService.class);
    private final GoalDiscovery discovery = new GoalDiscovery(mock(WorkspaceStore.class), mock(GoalService.class), candidates, exchanges, conversations);

    @Test
    void anIssueOpenedNearTheGoalSinceItsAgentLastLookedIsTold()
    {
        GoalView goal = goal(Optional.of(new GoalDefinition("Rows survive a checkpoint", "", "", "op-0", ASKED)), false);
        when(exchanges.list("goal")).thenReturn(ImmutableList.of(GoalExchange.ask("op-1", "goal", "workspace", "What next?", 1, ASKED)));
        when(candidates.pool(goal)).thenReturn(new GoalCandidates.Pool(ImmutableList.of(
                candidate("#31300", "Delta checkpoint drops rows", Optional.of(ASKED.plusSeconds(60))),
                candidate("#31301", "Refresh the theme", Optional.of(ASKED.plusSeconds(60))),
                candidate("#31200", "Delta checkpoint retries", Optional.of(ASKED.minusSeconds(60))),
                candidate("TRINO-12", "Delta checkpoint loses rows", Optional.empty())), new GoalCandidates.Coverage(3, 3, Optional.empty(), 1)));

        discovery.look(goal);
        verify(conversations).tell("workspace", "goal", "issues:goal:#31300", "Issues opened near the goal since you last looked: #31300 Delta checkpoint drops rows.");
    }

    @Test
    void nothingNewTellsNothingAndAnUndefinedOrPausedGoalDoesNotLook()
    {
        GoalView defined = goal(Optional.of(new GoalDefinition("Rows survive a checkpoint", "", "", "op-0", ASKED)), false);
        when(exchanges.list("goal")).thenReturn(ImmutableList.of(GoalExchange.ask("op-1", "goal", "workspace", "What next?", 1, ASKED)));
        when(candidates.pool(defined)).thenReturn(new GoalCandidates.Pool(ImmutableList.of(
                candidate("#31200", "Delta checkpoint retries", Optional.of(ASKED.minusSeconds(60)))), new GoalCandidates.Coverage(1, 1, Optional.empty(), 0)));
        discovery.look(defined);
        discovery.look(goal(Optional.empty(), false));
        discovery.look(goal(Optional.of(new GoalDefinition("Rows survive a checkpoint", "", "", "op-0", ASKED)), true));
        verifyNoInteractions(conversations);
    }

    private static GoalView goal(Optional<GoalDefinition> definition, boolean paused)
    {
        return new GoalView("goal", "workspace", "Delta Lake checkpoint correctness", "", true, Optional.empty(), definition, 1, Optional.empty(), paused,
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ASKED, ASKED);
    }

    private static GoalCandidates.Candidate candidate(String id, String title, Optional<Instant> openedAt)
    {
        return new GoalCandidates.Candidate(id, title, ImmutableList.of(), Optional.empty(), Optional.empty(), ASKED, openedAt);
    }
}
