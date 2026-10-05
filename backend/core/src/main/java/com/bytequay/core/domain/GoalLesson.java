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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.bytequay.core.domain.Checks.checkOptionalText;
import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * A lesson of a goal's course, written once by a tool-free request from the goal's knowledge notes, so reopening it needs no model call.
 *
 * @param id the lesson's Learn work item
 * @param model the provider configuration and model that wrote it or failed to
 */
public record GoalLesson(
        String id,
        String goalId,
        String workspaceId,
        State state,
        Optional<Content> content,
        Optional<String> failure,
        Optional<String> model,
        Instant requestedAt,
        Optional<Instant> writtenAt)
{
    public enum State
    {
        WRITING,
        READY,
        FAILED
    }

    public GoalLesson
    {
        checkText(id, "id");
        checkText(goalId, "goalId");
        checkText(workspaceId, "workspaceId");
        requireNonNull(state, "state is null");
        requireNonNull(content, "content is null");
        requireNonNull(failure, "failure is null");
        requireNonNull(model, "model is null");
        requireNonNull(requestedAt, "requestedAt is null");
        requireNonNull(writtenAt, "writtenAt is null");
        checkArgument(content.isPresent() == (state == State.READY), "only a ready lesson has content");
        checkArgument(failure.isPresent() == (state == State.FAILED), "only a failed lesson has a failure");
        checkArgument(writtenAt.isPresent() == (state != State.WRITING), "only a settled lesson has a written time");
    }

    public static GoalLesson writing(String id, String goalId, String workspaceId, Instant at)
    {
        return new GoalLesson(id, goalId, workspaceId, State.WRITING, Optional.empty(), Optional.empty(), Optional.empty(), at, Optional.empty());
    }

    public GoalLesson written(String writtenBy, Content written, Instant at)
    {
        return new GoalLesson(id, goalId, workspaceId, State.READY, Optional.of(written), Optional.empty(), Optional.of(writtenBy), requestedAt, Optional.of(at));
    }

    public GoalLesson failed(Optional<String> triedBy, String reason, Instant at)
    {
        return new GoalLesson(id, goalId, workspaceId, State.FAILED, Optional.empty(), Optional.of(reason), triedBy, requestedAt, Optional.of(at));
    }

    /**
     * What the lesson teaches and asks, as a chapter of a book: a short summary, the invariants to remember and a few short sections, each
     * with an optional diagram, then one exercise, what to observe and a check.
     *
     * @param explanation the Markdown a lesson written before chapters has instead of a summary and sections; it is written again when opened
     * @param exercise one focused exercise in the repository
     * @param observe what the user should see when the exercise works
     * @param check a short question the user can answer once they learned the lesson
     */
    public record Content(Optional<String> summary, List<String> invariants, List<Section> sections, Optional<String> explanation, String exercise,
            String observe, String check)
    {
        public static final int MAX_INVARIANTS = 6;
        public static final int MAX_SECTIONS = 6;

        public Content
        {
            checkOptionalText(summary, "summary", 600);
            // A lesson written before chapters has no invariants or sections.
            invariants = invariants == null ? ImmutableList.of() : ImmutableList.copyOf(invariants);
            checkArgument(invariants.size() <= MAX_INVARIANTS, "a chapter names at most %s invariants", MAX_INVARIANTS);
            invariants.forEach(value -> checkText(value, "invariant", 300));
            sections = sections == null ? ImmutableList.of() : ImmutableList.copyOf(sections);
            checkArgument(sections.size() <= MAX_SECTIONS, "a chapter has at most %s sections", MAX_SECTIONS);
            checkOptionalText(explanation, "explanation", 12_000);
            checkArgument(explanation.isPresent() || summary.isPresent() && !sections.isEmpty(), "a chapter has a summary and sections");
            checkText(exercise, "exercise", 3_000);
            checkText(observe, "observe", 1_500);
            checkText(check, "check", 1_000);
        }

        /** Whether it was written before lessons were chapters, so opening it writes it again. */
        public boolean beforeChapters()
        {
            return sections.isEmpty();
        }
    }

    /** One short part of a chapter: a title, a little Markdown and, where it shows a process or a structure, a diagram. */
    public record Section(String title, String body, Optional<Diagram> diagram)
    {
        public Section
        {
            checkText(title, "section title", 80);
            checkText(body, "section body", 1_500);
            requireNonNull(diagram, "diagram is null");
        }
    }

    /**
     * A small picture the app draws from its nodes: FLOW for steps in order, each node an arrow from the one before; TREE for a hierarchy,
     * each node under its parent and the first node the root.
     */
    public record Diagram(Kind kind, String caption, List<Node> nodes)
    {
        public static final int MAX_NODES = 10;

        public enum Kind
        {
            FLOW,
            TREE
        }

        public Diagram
        {
            requireNonNull(kind, "kind is null");
            checkText(caption, "diagram caption", 160);
            nodes = ImmutableList.copyOf(nodes);
            checkArgument(nodes.size() >= 2 && nodes.size() <= MAX_NODES, "a diagram has two to %s nodes", MAX_NODES);
            Set<String> earlier = new HashSet<>();
            for (Node node : nodes) {
                if (kind == Kind.TREE) {
                    checkArgument(earlier.isEmpty() ? node.parent().isEmpty() : node.parent().filter(earlier::contains).isPresent(),
                            "a tree's first node is its root and every other node names an earlier node as its parent: %s", node.id());
                }
                checkArgument(earlier.add(node.id()), "a diagram's node ids are unique: %s", node.id());
            }
        }
    }

    /** One box of a diagram, with a short label and an optional note under it. */
    public record Node(String id, String label, Optional<String> note, Optional<String> parent)
    {
        public Node
        {
            checkText(id, "node id", 40);
            checkText(label, "node label", 60);
            checkOptionalText(note, "node note", 120);
            requireNonNull(parent, "parent is null");
        }
    }
}
