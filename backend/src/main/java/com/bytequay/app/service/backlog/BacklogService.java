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
package com.bytequay.app.service.backlog;

import com.bytequay.app.domain.BacklogItem;

import java.util.List;

/**
 * Per-thread backlog — the parking lot behind the trunk's Backlog tab.
 * Items are free-form (title + body + tags) until the user clicks "Start
 * development", which appends the item to the thread's task queue (title +
 * body as the seed prompt) and marks the row started.
 */
public interface BacklogService
{
    /** The handle {@link #startDevelopment} returns: the updated item and
     *  the materialised task id (null when the entry queued behind a
     *  running task instead of starting immediately). */
    record StartResult(BacklogItem item, String taskId) {}

    /** Items on a thread, oldest-first. */
    List<BacklogItem> list(String threadId);

    /** Create a new backlog item on the thread. */
    BacklogItem create(String threadId, String title, String body, List<String> tags);

    /** Partial update — null fields are left unchanged. 404 when unknown. */
    BacklogItem update(String id, String title, String body, List<String> tags);

    /** Remove an item. No-op when the id is unknown. */
    void delete(String id);

    /** Cut a task from the item: append it to the thread's queue (title +
     *  body as the seed prompt) and stamp started_at. 404 when unknown,
     *  409 when already started. */
    StartResult startDevelopment(String id);
}
