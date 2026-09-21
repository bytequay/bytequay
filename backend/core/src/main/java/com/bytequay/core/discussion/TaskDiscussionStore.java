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
package com.bytequay.core.discussion;

import java.util.List;
import java.util.Optional;

public interface TaskDiscussionStore
{
    /**
     * Stores a new discussion, or returns the stored one when its passage already has one. A
     * repeated submission of the same passage therefore joins that discussion rather than failing.
     */
    TaskDiscussion insertOrGet(TaskDiscussion discussion);

    /** Replaces one discussion. Fails when the stored version has moved on. */
    TaskDiscussion update(TaskDiscussion discussion);

    Optional<TaskDiscussion> find(String discussionId);

    /** The discussion anchored to one passage, when it exists. */
    Optional<TaskDiscussion> findByAnchor(String taskId, String turnId, String quoteHash);

    /** Every discussion on one task, oldest first. */
    List<TaskDiscussion> listByTask(String taskId);
}
