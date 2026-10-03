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
 * One message the user sent a goal's agent, and its answer. The conversation is ByteQuay's own record: each request is rebuilt from
 * the goal and these exchanges, never from a provider's session memory.
 *
 * @param id the operation id the client chose, so a resent message returns this exchange instead of asking again
 * @param inputRevision the goal revision the request was built from; its proposal cannot be accepted once the goal moved on
 * @param model the provider configuration and model that answered or failed, such as "codex/gpt-6.1"
 */
public record GoalExchange(
        String id,
        String goalId,
        String workspaceId,
        String message,
        long inputRevision,
        State state,
        Optional<String> model,
        Optional<Reply> reply,
        Optional<String> failure,
        Instant askedAt,
        Optional<Instant> answeredAt)
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
    }

    public static GoalExchange ask(String id, String goalId, String workspaceId, String message, long inputRevision, Instant at)
    {
        return new GoalExchange(id, goalId, workspaceId, message, inputRevision, State.PENDING, Optional.empty(), Optional.empty(), Optional.empty(), at, Optional.empty());
    }

    public GoalExchange answered(String answeredBy, Reply answer, Instant at)
    {
        return new GoalExchange(id, goalId, workspaceId, message, inputRevision, State.ANSWERED, Optional.of(answeredBy), Optional.of(answer), Optional.empty(),
                askedAt, Optional.of(at));
    }

    public GoalExchange failed(Optional<String> triedBy, String reason, Instant at)
    {
        return new GoalExchange(id, goalId, workspaceId, message, inputRevision, State.FAILED, triedBy, Optional.empty(), Optional.of(reason), askedAt, Optional.of(at));
    }

    /** Asks again, built from the goal as it now stands. */
    public GoalExchange retried(long revision)
    {
        return new GoalExchange(id, goalId, workspaceId, message, revision, State.PENDING, Optional.empty(), Optional.empty(), Optional.empty(), askedAt, Optional.empty());
    }

    /**
     * The agent's answer: where it thinks the goal stands, at most one question, and a definition to accept once it understands the goal.
     */
    public record Reply(String assessment, Optional<Question> question, Optional<Proposal> proposal)
    {
        public Reply
        {
            checkText(assessment, "assessment", MAX_FIELD);
            requireNonNull(question, "question is null");
            requireNonNull(proposal, "proposal is null");
            checkArgument(question.isPresent() || proposal.isPresent(), "a reply asks a question or proposes a definition");
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
