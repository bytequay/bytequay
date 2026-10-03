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
import java.util.Locale;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * What the user knows about one topic of a goal, and the evidence behind it: the questions they asked, the lessons they finished and the
 * work they did. The agent proposes levels; a level the user set is theirs.
 *
 * @param evidence the ids of the questions, lessons and work items the level rests on
 * @param userSet whether the user set the level, which the agent then leaves alone
 */
public record GoalTopic(String id, String goalId, String workspaceId, String topic, Level level, List<String> evidence, boolean userSet, Instant updatedAt)
{
    public static final int MAX_EVIDENCE = 10;

    public enum Level
    {
        NEW,
        LEARNING,
        PRACTICED,
        STRONG
    }

    public GoalTopic
    {
        checkText(id, "id");
        checkText(goalId, "goalId");
        checkText(workspaceId, "workspaceId");
        checkText(topic, "topic", 120);
        requireNonNull(level, "level is null");
        evidence = ImmutableList.copyOf(evidence);
        checkArgument(evidence.size() <= MAX_EVIDENCE, "a level rests on at most %s pieces of evidence", MAX_EVIDENCE);
        requireNonNull(updatedAt, "updatedAt is null");
    }

    /** One topic of a goal is one record, whatever case the agent writes it in. */
    public static String id(String goalId, String topic)
    {
        return goalId + ":" + topic.strip().toLowerCase(Locale.ROOT);
    }

    /** A level the agent proposes for a topic, with the evidence it cites. */
    public record Change(String topic, Level level, List<String> evidence)
    {
        public Change
        {
            checkText(topic, "topic", 120);
            requireNonNull(level, "level is null");
            evidence = ImmutableList.copyOf(evidence);
            checkArgument(!evidence.isEmpty() && evidence.size() <= MAX_EVIDENCE, "a level change cites one to %s pieces of evidence", MAX_EVIDENCE);
        }
    }
}
