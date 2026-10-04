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
 * A goal's accepted learning path: parts, each with ordered lessons. Each lesson is a Learn work item whose acceptance criteria say what the
 * user can do afterward; its part is the item's milestone.
 *
 * @param exchangeId the exchange whose proposal was accepted
 */
public record GoalCourse(String exchangeId, List<Part> parts, Instant acceptedAt)
{
    public static final int MAX_PARTS = 4;
    public static final int MAX_LESSONS = 16;

    public GoalCourse
    {
        checkText(exchangeId, "exchangeId");
        parts = ImmutableList.copyOf(parts);
        checkArgument(!parts.isEmpty() && parts.size() <= MAX_PARTS, "a course has one to %s parts", MAX_PARTS);
        requireNonNull(acceptedAt, "acceptedAt is null");
    }

    /**
     * One part of the course.
     *
     * @param lessons the part's Learn work items, in order
     */
    public record Part(String title, List<String> lessons)
    {
        public Part
        {
            checkText(title, "part title", 120);
            lessons = ImmutableList.copyOf(lessons);
            checkArgument(!lessons.isEmpty(), "a part has at least one lesson");
        }
    }

    /**
     * A course the agent proposes; accepting it saves each new lesson as a Learn work item.
     *
     * @param reason why the accepted course should change, when this one revises it
     */
    public record Draft(List<DraftPart> parts, Optional<String> reason)
    {
        public Draft
        {
            parts = ImmutableList.copyOf(parts);
            checkArgument(!parts.isEmpty() && parts.size() <= MAX_PARTS, "a course has one to %s parts", MAX_PARTS);
            checkArgument(parts.stream().mapToInt(part -> part.lessons().size()).sum() <= MAX_LESSONS, "a course has at most %s lessons", MAX_LESSONS);
            List<String> kept = parts.stream().flatMap(part -> part.lessons().stream()).flatMap(lesson -> lesson.lessonId().stream()).toList();
            checkArgument(kept.size() == kept.stream().distinct().count(), "a course keeps each lesson once");
            reason.ifPresent(value -> checkText(value, "course change reason", 300));
        }
    }

    public record DraftPart(String title, List<DraftLesson> lessons)
    {
        public DraftPart
        {
            checkText(title, "part title", 120);
            lessons = ImmutableList.copyOf(lessons);
            checkArgument(!lessons.isEmpty() && lessons.size() <= 6, "a part has one to six lessons");
        }
    }

    /**
     * One proposed lesson.
     *
     * @param outcome what the user can do after the lesson, which becomes its acceptance criteria
     * @param lessonId the accepted lesson a revision keeps in this place, whose own title and outcome stay
     */
    public record DraftLesson(String title, String outcome, Optional<String> lessonId)
    {
        public DraftLesson
        {
            checkText(title, "lesson title", 120);
            checkText(outcome, "lesson outcome", 400);
            requireNonNull(lessonId, "lessonId is null");
        }
    }
}
