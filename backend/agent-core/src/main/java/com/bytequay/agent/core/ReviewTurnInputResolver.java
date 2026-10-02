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
package com.bytequay.agent.core;

import com.google.common.collect.ImmutableList;

import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Supplies frozen reference data, repository guidelines, memory and review context, at an accepted turn's provider launch boundary. */
@FunctionalInterface
public interface ReviewTurnInputResolver
{
    ReviewTurnInputResolver NONE = (task, turn, message) -> message;

    /**
     * Composes the provider input after turn acceptance, including when queued input starts or resumes.
     *
     * <p>The application resolves the accepted assignment and persists the exact reference binding
     * before returning. The original message remains the transcript input; additional reference
     * bodies belong to the review snapshot. Ordinary tasks and context-disabled runs return it
     * unchanged. This method runs outside the task monitor and provider session, without an enclosing
     * transaction. The caller rechecks cancellation and worktree ownership before provider launch.
     *
     * <p>Preparation is not proof of provider delivery. The existing turn's delivery-start timestamp,
     * known-unsent flag, and provider reference retain that distinction; preparation must neither
     * submit input itself nor retry a submission with an uncertain result.
     *
     * @param task task owning the accepted turn and its worktree claim
     * @param turn persisted accepted turn whose immutable settings govern this launch
     * @param message original accepted input, before any review reference data
     * @return nonblank complete provider input with bounded, delimited reference material
     * @throws RuntimeException when required reference preparation fails; the provider is not started
     */
    String prepare(AgentTask task, AgentTurn turn, String message);

    /** Called after the provider acknowledged the exact prepared input; usage bookkeeping must not affect the running turn. */
    default void delivered(AgentTask task, AgentTurn turn) {}

    /**
     * Reads the reference data that goes with every accepted turn's provider input: the repository's guideline files at a commit the
     * turn cannot change, then the repository memories that match the turn. It runs before {@link #prepare} and never fails the turn:
     * a part that cannot be read is left out.
     */
    default Reference reference(AgentTask task, AgentTurn turn)
    {
        return Reference.NONE;
    }

    /** The guideline files and memories a turn carries, by path and as delivered, and the delimited reference text that carries them. */
    record Reference(List<String> guidelines, List<TurnMemory> memories, String text)
    {
        public static final Reference NONE = new Reference(ImmutableList.of(), ImmutableList.of(), "");

        public Reference
        {
            guidelines = ImmutableList.copyOf(guidelines);
            memories = ImmutableList.copyOf(memories);
            requireNonNull(text, "text is null");
            checkArgument((guidelines.isEmpty() && memories.isEmpty()) == text.isEmpty(), "reference parts and text disagree");
        }

        /** This reference followed by another, as one block of reference data. */
        public Reference plus(Reference next)
        {
            String joined = text.isEmpty() || next.text().isEmpty() ? text + next.text() : text + "\n\n" + next.text();
            return new Reference(ImmutableList.<String>builder().addAll(guidelines).addAll(next.guidelines()).build(),
                    ImmutableList.<TurnMemory>builder().addAll(memories).addAll(next.memories()).build(), joined);
        }

        public String append(String message)
        {
            return text.isEmpty() ? message : message + "\n\n" + text;
        }
    }
}
