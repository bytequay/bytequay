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

import static com.bytequay.core.domain.Checks.checkOptionalText;
import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * One message the user sent a goal's agent, and its answer. The conversation is ByteQuay's own record: each request is rebuilt from
 * the goal and these exchanges, never from a provider's session memory.
 *
 * @param id the operation id the client chose, so a resent message returns this exchange instead of asking again
 * @param automatic whether ByteQuay reported an event, such as a finished research run, rather than the user writing the message
 * @param lessonId the lesson the user asked about, whose content the agent reads with the question
 * @param inputRevision the goal revision the request was built from; its proposal cannot be accepted once the goal moved on
 * @param model the provider configuration and model that answered or failed, such as "codex/gpt-6.1"
 */
public record GoalExchange(
        String id,
        String goalId,
        String workspaceId,
        String message,
        boolean automatic,
        Optional<String> lessonId,
        long inputRevision,
        State state,
        Optional<String> model,
        Optional<Reply> reply,
        Optional<String> failure,
        Instant askedAt,
        Optional<Instant> answeredAt,
        Optional<GoalExchange.Coverage> coverage)
{
    public static final int MAX_MESSAGE = 4_000;
    public static final int MAX_FIELD = 2_000;

    public enum State
    {
        PENDING,
        ANSWERED,
        FAILED
    }

    public GoalExchange
    {
        checkText(id, "id", 100);
        checkText(goalId, "goalId");
        checkText(workspaceId, "workspaceId");
        checkText(message, "message", MAX_MESSAGE);
        requireNonNull(lessonId, "lessonId is null");
        checkArgument(inputRevision >= 0, "inputRevision is negative");
        requireNonNull(state, "state is null");
        requireNonNull(model, "model is null");
        requireNonNull(reply, "reply is null");
        requireNonNull(failure, "failure is null");
        requireNonNull(askedAt, "askedAt is null");
        requireNonNull(answeredAt, "answeredAt is null");
        checkArgument(reply.isPresent() == (state == State.ANSWERED), "only an answered exchange has a reply");
        checkArgument(failure.isPresent() == (state == State.FAILED), "only a failed exchange has a failure");
        checkArgument(answeredAt.isPresent() == (state != State.PENDING), "only a settled exchange has an answer time");
        requireNonNull(coverage, "coverage is null");
    }

    public static GoalExchange ask(String id, String goalId, String workspaceId, String message, long inputRevision, Instant at)
    {
        return new GoalExchange(id, goalId, workspaceId, message, false, Optional.empty(), inputRevision, State.PENDING, Optional.empty(), Optional.empty(), Optional.empty(), at,
                Optional.empty(), Optional.empty());
    }

    /** Reports an event to the agent, which answers it like a message. */
    public static GoalExchange told(String id, String goalId, String workspaceId, String event, long inputRevision, Instant at)
    {
        return new GoalExchange(id, goalId, workspaceId, event, true, Optional.empty(), inputRevision, State.PENDING, Optional.empty(), Optional.empty(), Optional.empty(), at,
                Optional.empty(), Optional.empty());
    }

    public GoalExchange answered(String answeredBy, Reply answer, Instant at)
    {
        return new GoalExchange(id, goalId, workspaceId, message, automatic, lessonId, inputRevision, State.ANSWERED, Optional.of(answeredBy), Optional.of(answer),
                Optional.empty(), askedAt, Optional.of(at), Optional.empty());
    }

    /** The answer with what it rested on. */
    public GoalExchange covering(Optional<Coverage> value)
    {
        return new GoalExchange(id, goalId, workspaceId, message, automatic, lessonId, inputRevision, state, model, reply, failure, askedAt, answeredAt, value);
    }

    public GoalExchange failed(Optional<String> triedBy, String reason, Instant at)
    {
        return new GoalExchange(id, goalId, workspaceId, message, automatic, lessonId, inputRevision, State.FAILED, triedBy, Optional.empty(), Optional.of(reason), askedAt,
                Optional.of(at), Optional.empty());
    }

    public GoalExchange about(Optional<String> lesson)
    {
        return new GoalExchange(id, goalId, workspaceId, message, automatic, lesson, inputRevision, state, model, reply, failure, askedAt, answeredAt, coverage);
    }

    /** Asks again, built from the goal as it now stands. */
    public GoalExchange retried(long revision)
    {
        return new GoalExchange(id, goalId, workspaceId, message, automatic, lessonId, revision, State.PENDING, Optional.empty(), Optional.empty(), Optional.empty(), askedAt,
                Optional.empty(), Optional.empty());
    }

    /**
     * What an answer rested on: the knowledge notes it read in full and those it saw by topic only, the user's questions from private cards,
     * and the practice candidates it was offered out of the open issues.
     *
     * @param facts a digest of everything it was given about the goal but the conversation, so an unchanged goal can be told apart
     */
    public record Coverage(int notesInFull, int notesByTopic, int questions, int candidates, long issuesOpen, Optional<String> facts)
    {
        public Coverage
        {
            checkArgument(notesInFull >= 0 && notesByTopic >= 0 && questions >= 0 && candidates >= 0 && issuesOpen >= 0, "an answer's coverage is not negative");
            requireNonNull(facts, "facts is null");
        }
    }

    /**
     * The agent's answer: where it thinks the goal stands, at most one question, a definition to accept once it understands the goal, a
     * course to accept once research gave it notes, a card when it judges the next step is ready, and what it now believes the user knows.
     */
    /**
     * What the agent answered.
     *
     * @param title a short readable name for the goal, like a mission, while its definition is open
     */
    public record Reply(String assessment, Optional<Question> question, Optional<Proposal> proposal, Optional<GoalRecommendation.Card> recommendation,
            Optional<GoalCourse.Draft> course, List<GoalTopic.Change> topics, Optional<String> title, Optional<PlanChange> plan)
    {
        public static final int MAX_NAME = 80;

        public Reply
        {
            checkText(assessment, "assessment", MAX_FIELD);
            requireNonNull(question, "question is null");
            requireNonNull(proposal, "proposal is null");
            requireNonNull(recommendation, "recommendation is null");
            requireNonNull(course, "course is null");
            // A reply saved before topics existed has none.
            topics = topics == null ? ImmutableList.of() : ImmutableList.copyOf(topics);
            checkArgument(topics.size() <= 8, "a reply changes at most eight topics");
            // A reply saved before names existed has none.
            title = title == null ? Optional.empty() : title.map(String::strip);
            title.ifPresent(value -> checkArgument(!value.isEmpty() && value.length() <= MAX_NAME && value.lines().count() == 1,
                    "a goal's name is one line of at most %s characters", MAX_NAME));
            // A reply saved before plan changes existed has none.
            plan = plan == null ? Optional.empty() : plan;
        }
    }

    /**
     * A change to the priority, order and dependencies of the goal's open work, which the user accepts or not.
     *
     * @param changes the work items whose priority, or whole list of the open work they wait on, should change
     * @param order every open work item of the goal in its new order, when the order should change
     */
    public record PlanChange(List<WorkChange> changes, Optional<List<String>> order, String reason)
    {
        public static final int MAX_CHANGES = 30;

        public PlanChange
        {
            // A reply that only reorders may leave changes out.
            changes = changes == null ? ImmutableList.of() : ImmutableList.copyOf(changes);
            checkArgument(changes.size() <= MAX_CHANGES, "a plan change changes at most %s work items", MAX_CHANGES);
            checkArgument(changes.stream().map(WorkChange::workItemId).distinct().count() == changes.size(), "a plan change names each work item once");
            requireNonNull(order, "order is null");
            order = order.map(ImmutableList::copyOf);
            order.ifPresent(ids -> checkArgument(ids.size() <= MAX_CHANGES && ids.stream().distinct().count() == ids.size(),
                    "a plan change orders each work item once"));
            checkArgument(!changes.isEmpty() || order.isPresent(), "a plan change changes something");
            checkText(reason, "plan change reason", 300);
        }
    }

    /**
     * One work item's new priority, the whole list of the open work it should wait on, its new acceptance criteria, or several of them.
     *
     * @param waitsOn the open work it waits on afterward, none for an empty list
     * @param acceptanceCriteria what the work must achieve afterward, when its scope should change
     */
    public record WorkChange(String workItemId, Optional<WorkItem.Priority> priority, Optional<List<String>> waitsOn, Optional<String> acceptanceCriteria)
    {
        public static final int MAX_CRITERIA = 2_000;

        public WorkChange
        {
            checkText(workItemId, "workItemId", 200);
            requireNonNull(priority, "priority is null");
            requireNonNull(waitsOn, "waitsOn is null");
            waitsOn = waitsOn.map(ImmutableList::copyOf);
            checkOptionalText(acceptanceCriteria, "acceptanceCriteria", MAX_CRITERIA);
            checkArgument(priority.isPresent() || waitsOn.isPresent() || acceptanceCriteria.isPresent(),
                    "a work change sets a priority, what the work waits on or its criteria");
            waitsOn.ifPresent(ids -> checkArgument(ids.size() <= PlanChange.MAX_CHANGES && ids.stream().distinct().count() == ids.size() && !ids.contains(workItemId),
                    "work waits on other work, each once"));
        }
    }

    /** One consequential question, with short answers the user can pick instead of typing. */
    public record Question(String text, List<String> suggestions)
    {
        public Question
        {
            checkText(text, "question", 600);
            suggestions = ImmutableList.copyOf(suggestions);
            checkArgument(suggestions.size() <= 4, "a question offers at most four suggestions");
            suggestions.forEach(suggestion -> checkText(suggestion, "suggestion", 200));
        }
    }

    /** A definition the user can accept, which renames the goal and saves the rest as its {@link GoalDefinition}. */
    public record Proposal(String title, String objective, String successDefinition, String startingKnowledge, String constraints)
    {
        public Proposal
        {
            checkText(title, "title", 200);
            checkText(objective, "objective", MAX_FIELD);
            checkText(successDefinition, "successDefinition", MAX_FIELD);
            requireNonNull(startingKnowledge, "startingKnowledge is null");
            checkArgument(startingKnowledge.length() <= MAX_FIELD, "startingKnowledge is too long");
            requireNonNull(constraints, "constraints is null");
            checkArgument(constraints.length() <= MAX_FIELD, "constraints is too long");
        }
    }
}
