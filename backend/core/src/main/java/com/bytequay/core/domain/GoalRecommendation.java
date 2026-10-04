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

import com.bytequay.core.context.ContextLimits;
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
 * @param workItemId the Goal work a work card's Start made of its source; the card stays started until that work is done or dropped
 * @param checkId the spec check a spec-check card's Start began
 * @param exchangeId the exchange whose answer recommended it
 */
public record GoalRecommendation(
        String id,
        String goalId,
        String workspaceId,
        Card card,
        State state,
        Optional<String> taskId,
        Optional<String> workItemId,
        Optional<String> checkId,
        String exchangeId,
        Instant createdAt,
        Instant updatedAt)
{
    public enum Kind
    {
        RESEARCH,
        LESSON,
        WORK,
        SPEC_CHECK
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
        requireNonNull(workItemId, "workItemId is null");
        checkArgument(workItemId.isEmpty() || card.kind() == Kind.WORK && (state == State.STARTED || state == State.DONE), "only a started work card has its work");
        requireNonNull(checkId, "checkId is null");
        checkArgument(checkId.isEmpty() || card.kind() == Kind.SPEC_CHECK && (state == State.STARTED || state == State.DONE), "only a started spec-check card has a check");
        checkText(exchangeId, "exchangeId");
        requireNonNull(createdAt, "createdAt is null");
        requireNonNull(updatedAt, "updatedAt is null");
    }

    public GoalRecommendation started(Optional<String> task, Instant at)
    {
        return new GoalRecommendation(id, goalId, workspaceId, card, State.STARTED, task, Optional.empty(), Optional.empty(), exchangeId, createdAt, at);
    }

    /** Start on a spec-check card: the check it began, which settles the card when it ends. */
    public GoalRecommendation checking(String check, Instant at)
    {
        return new GoalRecommendation(id, goalId, workspaceId, card, State.STARTED, Optional.empty(), Optional.empty(), Optional.of(check), exchangeId, createdAt, at);
    }

    /** Start on a work card: its source became this Goal work, whose task the user sends from the composer. */
    public GoalRecommendation practicing(String workItem, Instant at)
    {
        return new GoalRecommendation(id, goalId, workspaceId, card, State.STARTED, Optional.empty(), Optional.of(workItem), Optional.empty(), exchangeId, createdAt, at);
    }

    public GoalRecommendation done(Instant at)
    {
        return new GoalRecommendation(id, goalId, workspaceId, card, State.DONE, taskId, workItemId, checkId, exchangeId, createdAt, at);
    }

    /** A finished run or check completes the card; a failed one shows it again, so Start can retry. */
    public GoalRecommendation settled(boolean done, Instant at)
    {
        return new GoalRecommendation(id, goalId, workspaceId, card, done ? State.DONE : State.SHOWN, done ? taskId : Optional.empty(), Optional.empty(),
                done ? checkId : Optional.empty(), exchangeId, createdAt, at);
    }

    public GoalRecommendation dismissed(Instant at)
    {
        return new GoalRecommendation(id, goalId, workspaceId, card, State.DISMISSED, Optional.empty(), Optional.empty(), Optional.empty(), exchangeId, createdAt, at);
    }

    /**
     * What the card offers. A research card reads its scope at the workspace's monitored head, the checkouts of the workspaces it depends
     * on, and the pages it names; a lesson card opens the lesson of its work item; a work card prepares a task for one open issue or one
     * Jira ticket mapped to the workspace, its scope the instruction the composer opens with; a spec-check card checks one module against the
     * specification pages it names.
     *
     * @param ticket the Jira ticket a work card names: its key in the agent's answer, its connection:cloud:issue reference once floated
     * @param module the directory a spec-check card checks, such as plugin/trino-delta-lake/
     * @param reproductions how many of the gaps it finds a spec check may reproduce with a test
     */
    public record Card(Kind kind, String title, String reason, String scope, List<String> pages, Optional<String> workItemId, Optional<Integer> issue,
            Optional<String> ticket, Optional<String> module, Optional<Integer> reproductions)
    {
        public static final int MAX_PAGES = 8;
        public static final int MAX_REPRODUCTIONS = 8;

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
            requireNonNull(ticket, "ticket is null");
            ticket.ifPresent(value -> checkText(value, "ticket", 300));
            checkArgument(kind == Kind.WORK ? issue.isPresent() != ticket.isPresent() : issue.isEmpty() && ticket.isEmpty(),
                    "a work card names one issue or one Jira ticket, and only a work card does");
            requireNonNull(module, "module is null");
            requireNonNull(reproductions, "reproductions is null");
            checkArgument(module.isPresent() == (kind == Kind.SPEC_CHECK) && reproductions.isPresent() == (kind == Kind.SPEC_CHECK),
                    "a spec-check card names its module and how many gaps it reproduces, and only a spec-check card does");
            // What a spec check accepts: a repository directory, and public specification pages.
            module.ifPresent(ContextLimits::pathPrefix);
            if (kind == Kind.SPEC_CHECK) {
                pages.forEach(ContextLimits::documentationUrl);
            }
            reproductions.ifPresent(value -> checkArgument(value >= 1 && value <= MAX_REPRODUCTIONS, "a spec check reproduces 1 to %s gaps", MAX_REPRODUCTIONS));
        }

        /** The same card naming the ticket by its stable reference. */
        public Card withTicket(String reference)
        {
            return new Card(kind, title, reason, scope, pages, workItemId, issue, Optional.of(reference), module, reproductions);
        }
    }
}
