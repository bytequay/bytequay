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
package com.bytequay.core.domain;

import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * A card the agent floats under the Goal control. What it names starts only when the user presses Start; Not now dismisses it, and the
 * same card is not offered again.
 *
 * @param taskId the run Start began, kept once it finished; a lesson card starts no run
 * @param exchangeId the exchange whose answer recommended it
 */
public record GoalRecommendation(
        String id,
        String goalId,
        String workspaceId,
        Card card,
        State state,
        Optional<String> taskId,
        String exchangeId,
        Instant createdAt,
        Instant updatedAt)
{
    public enum Kind
    {
        RESEARCH,
        LESSON,
        WORK
    }

    public enum State
    {
        SHOWN,
        STARTED,
        DONE,
        DISMISSED
    }

    public GoalRecommendation
    {
        checkText(id, "id");
        checkText(goalId, "goalId");
        checkText(workspaceId, "workspaceId");
        requireNonNull(card, "card is null");
        requireNonNull(state, "state is null");
        requireNonNull(taskId, "taskId is null");
        checkArgument(taskId.isEmpty() || state == State.STARTED || state == State.DONE, "only a started card has a run");
        checkText(exchangeId, "exchangeId");
        requireNonNull(createdAt, "createdAt is null");
        requireNonNull(updatedAt, "updatedAt is null");
    }

    public GoalRecommendation started(Optional<String> task, Instant at)
    {
        return new GoalRecommendation(id, goalId, workspaceId, card, State.STARTED, task, exchangeId, createdAt, at);
    }

    public GoalRecommendation done(Instant at)
    {
        return new GoalRecommendation(id, goalId, workspaceId, card, State.DONE, taskId, exchangeId, createdAt, at);
    }

    /** A finished run completes the card; a failed one shows it again, so Start can retry. */
    public GoalRecommendation settled(boolean done, Instant at)
    {
        return new GoalRecommendation(id, goalId, workspaceId, card, done ? State.DONE : State.SHOWN, done ? taskId : Optional.empty(), exchangeId, createdAt, at);
    }

    public GoalRecommendation dismissed(Instant at)
    {
        return new GoalRecommendation(id, goalId, workspaceId, card, State.DISMISSED, Optional.empty(), exchangeId, createdAt, at);
    }

    /**
     * What the card offers. A research card reads its scope at the workspace's monitored head, the checkouts of the workspaces it depends
     * on, and the pages it names; a lesson card opens the lesson of its work item; a work card prepares a task for one open issue, its scope
     * the instruction the composer opens with.
     */
    public record Card(Kind kind, String title, String reason, String scope, List<String> pages, Optional<String> workItemId, Optional<Integer> issue)
    {
        public static final int MAX_PAGES = 8;

        public Card
        {
            requireNonNull(kind, "kind is null");
            checkText(title, "title", 120);
            checkText(reason, "reason", 300);
            checkText(scope, "scope", 2_000);
            pages = ImmutableList.copyOf(pages);
            checkArgument(pages.size() <= MAX_PAGES, "a card names at most %s pages", MAX_PAGES);
            pages.forEach(page -> checkArgument(page.startsWith("https://") && page.length() <= 500 && page.chars().noneMatch(Character::isWhitespace),
                    "pages are https addresses: %s", page));
            requireNonNull(workItemId, "workItemId is null");
            checkArgument(workItemId.isPresent() == (kind == Kind.LESSON), "a lesson card names its lesson, and only a lesson card does");
            requireNonNull(issue, "issue is null");
            checkArgument(issue.isPresent() == (kind == Kind.WORK), "a work card names its issue, and only a work card does");
        }
    }
}
