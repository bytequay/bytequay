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

    /** Workspace-wide list, newest-first, with optional filters (a
     *  null/blank filter means "no filter"): exact {@code status}, exact
     *  originating {@code threadId}, a {@code tag} the item carries, and a
     *  free-text {@code query} over title/body. */
    List<BacklogItem> listForWorkspace(String workspaceId, String status, String threadId, String tag, String query);

    /** Create a new (manual) backlog item on the thread. {@code priority}
     *  defaults to {@code medium} when null/blank. */
    BacklogItem create(String threadId, String title, String body, List<String> tags, String priority);

    /** Partial update — null fields are left unchanged. 404 when unknown. */
    BacklogItem update(String id, String title, String body, List<String> tags, String priority);

    /** Remove an item. No-op when the id is unknown. */
    void delete(String id);

    /** Mark an item {@code not-to-proceed} with an optional reason. 404 when
     *  unknown, 409 when it's already resolved. */
    BacklogItem skip(String id, String reason);

    /** Restore a {@code not-to-proceed} item to {@code created}. 404 when
     *  unknown, 409 when the item isn't in {@code not-to-proceed}. */
    BacklogItem revive(String id);

    /** Cut a task from the item: append it to the thread's queue (title +
     *  body as the seed prompt) and stamp started_at. 404 when unknown,
     *  409 when already started. */
    StartResult startDevelopment(String id);
}
