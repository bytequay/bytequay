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

import java.time.Instant;
import java.util.Optional;

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
     * What the lesson teaches and asks.
     *
     * @param explanation Markdown with links to the specification sections and code the notes cite
     * @param exercise one focused exercise in the repository
     * @param observe what the user should see when the exercise works
     * @param check a short question the user can answer once they learned the lesson
     */
    public record Content(String explanation, String exercise, String observe, String check)
    {
        public Content
        {
            checkText(explanation, "explanation", 12_000);
            checkText(exercise, "exercise", 3_000);
            checkText(observe, "observe", 1_500);
            checkText(check, "check", 1_000);
        }
    }
}
