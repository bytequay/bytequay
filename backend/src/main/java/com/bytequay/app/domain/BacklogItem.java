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
package com.bytequay.app.domain;

import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;

/**
 * One parked future-work item on a thread's backlog — the JIRA-like
 * parking lot behind the trunk's Backlog tab. {@code startedAt} flips
 * from {@code null} to wall-clock when the user clicks "Start
 * development", which appends the item (title + body as the seed prompt)
 * to the thread's task queue; {@code linkedTaskId} points at the task
 * that materialised, when it materialised immediately.
 */
public record BacklogItem(
        String id,
        String threadId,
        String title,
        String body,
        List<String> tags,
        Instant createdAt,
        Instant startedAt,
        String linkedTaskId)
{
    /** Defensively copy the tag list so callers can't mutate the spec
     *  the store handed out. */
    public BacklogItem
    {
        tags = tags == null ? List.of() : ImmutableList.copyOf(tags);
    }

    /** True once "Start development" has cut (or queued) a task. */
    public boolean isStarted()
    {
        return startedAt != null;
    }
}
