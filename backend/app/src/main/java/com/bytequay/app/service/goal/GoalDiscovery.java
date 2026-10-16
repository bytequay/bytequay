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

import com.bytequay.core.domain.GoalExchange;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.GoalExchangeStore;
import com.bytequay.core.store.WorkspaceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

/**
 * Lets discovery follow the repository: each guided Goal's agent hears, as one background event, about the open issues near the Goal opened
 * since it last looked. When nothing new was opened nothing is told, so no model is asked. Local reads only.
 */
@Component
@ConditionalOnProperty(name = "bytequay.experiments.goals", havingValue = "true")
public class GoalDiscovery
{
    static final int MAX_FRESH = 5;
    private static final Logger log = LoggerFactory.getLogger(GoalDiscovery.class);

    private final WorkspaceStore workspaces;
    private final GoalService goals;
    private final GoalCandidates candidates;
    private final GoalExchangeStore exchanges;
    private final GoalConversationService conversations;

    public GoalDiscovery(WorkspaceStore workspaces, GoalService goals, GoalCandidates candidates, GoalExchangeStore exchanges, GoalConversationService conversations)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.goals = requireNonNull(goals, "goals is null");
        this.candidates = requireNonNull(candidates, "candidates is null");
        this.exchanges = requireNonNull(exchanges, "exchanges is null");
        this.conversations = requireNonNull(conversations, "conversations is null");
    }

    // The issue sync runs every half hour too; a newly opened issue reaches the agent within an hour.
    @Scheduled(initialDelayString = "${bytequay.goals.discovery-initial-delay:PT5M}", fixedDelayString = "${bytequay.goals.discovery-interval:PT30M}")
    public void look()
    {
        for (Workspace workspace : workspaces.findAll()) {
            for (GoalView goal : goals.list(workspace.id())) {
                try {
                    look(goal);
                }
                catch (RuntimeException failure) {
                    log.debug("Goal {} did not look for new issues", goal.id(), failure);
                }
            }
        }
    }

    /** Tells a defined, guided goal's agent about the issues near it opened since its last exchange, nearest first. */
    void look(GoalView goal)
    {
        if (goal.definition().isEmpty() || goal.paused()) {
            return;
        }
        Optional<Instant> since = exchanges.list(goal.id()).stream().map(GoalExchange::askedAt).max(Comparator.naturalOrder());
        if (since.isEmpty()) {
            return;
        }
        Set<String> words = GoalCandidates.words(GoalCandidates.context(goal));
        List<GoalCandidates.Candidate> fresh = candidates.pool(goal).candidates().stream()
                .filter(candidate -> candidate.openedAt().filter(since.orElseThrow()::isBefore).isPresent() && GoalCandidates.shared(words, candidate) > 0)
                .limit(MAX_FRESH).collect(toImmutableList());
        if (fresh.isEmpty()) {
            return;
        }
        // The same issues are told once, however often they are found again before the agent hears them.
        conversations.tell(goal.workspaceId(), goal.id(), "issues:" + goal.id() + ":" + fresh.stream().map(GoalCandidates.Candidate::id).collect(joining(",")),
                "Issues opened near the goal since you last looked: " + fresh.stream().map(candidate -> candidate.id() + " " + candidate.title()).collect(joining("; "))
                        + ".");
    }
}
