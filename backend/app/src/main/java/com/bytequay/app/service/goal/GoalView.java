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

import com.bytequay.core.domain.GoalCourse;
import com.bytequay.core.domain.GoalDefinition;
import com.bytequay.core.domain.GoalRecommendation;
import com.bytequay.core.domain.GoalSource;
import com.bytequay.core.domain.GoalTopic;
import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * A goal with each item read from local stores, in the order the items were added.
 *
 * @param course the learning path the user accepted
 * @param paused whether its agent hears no automatic events
 * @param recommendations the cards still shown or running, newest first
 * @param topics what the user knows, topic by topic
 */
public record GoalView(
        String id,
        String workspaceId,
        String title,
        String description,
        boolean pinned,
        Optional<GoalSource> source,
        Optional<GoalDefinition> definition,
        long revision,
        Optional<GoalCourse> course,
        boolean paused,
        List<WorkItemView> items,
        List<GoalRecommendation> recommendations,
        List<GoalTopic> topics,
        Instant createdAt,
        Instant updatedAt)
{
    public GoalView
    {
        requireNonNull(id, "id is null");
        requireNonNull(workspaceId, "workspaceId is null");
        requireNonNull(title, "title is null");
        requireNonNull(description, "description is null");
        requireNonNull(source, "source is null");
        requireNonNull(definition, "definition is null");
        requireNonNull(course, "course is null");
        items = ImmutableList.copyOf(items);
        recommendations = ImmutableList.copyOf(recommendations);
        topics = ImmutableList.copyOf(topics);
        requireNonNull(createdAt, "createdAt is null");
        requireNonNull(updatedAt, "updatedAt is null");
    }
}
