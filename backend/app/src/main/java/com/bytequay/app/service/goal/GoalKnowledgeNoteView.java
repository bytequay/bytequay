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

import com.bytequay.core.domain.GoalKnowledgeNote;
import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * A knowledge note as the Goal window reads it.
 *
 * @param stale whether a file it cites changed at the monitored head since the research read it
 * @param memoryId the repository memory the user made of it, while that memory is kept
 */
public record GoalKnowledgeNoteView(String id, String topic, String body, List<GoalKnowledgeNote.Source> sources, String taskId, Instant savedAt, boolean stale,
        Optional<String> memoryId)
{
    public GoalKnowledgeNoteView
    {
        requireNonNull(id, "id is null");
        requireNonNull(topic, "topic is null");
        requireNonNull(body, "body is null");
        sources = ImmutableList.copyOf(sources);
        requireNonNull(taskId, "taskId is null");
        requireNonNull(savedAt, "savedAt is null");
        requireNonNull(memoryId, "memoryId is null");
    }

    static GoalKnowledgeNoteView of(GoalKnowledgeNote note, boolean stale, Set<String> memories)
    {
        return new GoalKnowledgeNoteView(note.id(), note.topic(), note.body(), note.sources(), note.taskId(), note.savedAt(), stale, note.memoryId().filter(memories::contains));
    }
}
