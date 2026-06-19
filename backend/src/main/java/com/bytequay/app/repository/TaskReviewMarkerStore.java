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
package com.bytequay.app.repository;

import java.time.Instant;
import java.util.Optional;

/**
 * Persists the per-task "last addressed review comment" marker for the
 * post-ship address-comments loop. The marker is the timestamp of the
 * newest reviewer comment the lifecycle reconciler has already surfaced
 * for addressing; comparing live review threads against it is how the
 * loop detects a <em>new</em> round of comments rather than re-firing on
 * the same ones each poll.
 */
public interface TaskReviewMarkerStore
{
    /** The marker for {@code taskId}, or empty when none has been
     *  recorded (nothing addressed yet — every unresolved comment is new). */
    Optional<Instant> find(String taskId);

    /** Records {@code addressedThrough} as the marker for {@code taskId},
     *  upserting the row. Idempotent. */
    void mark(String taskId, Instant addressedThrough);
}
