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
 * Persists the per-task "last queried the CI-fixing log" marker for the
 * {@code get_new_updated_ci_fixing_log} read tool. The marker is the
 * timestamp of the newest CI-fixing iteration summary the caller has
 * already been handed; comparing live summaries against it is how the tool
 * returns only genuinely newer rows on a later call.
 */
public interface CiFixingLogQueryMarkerStore
{
    /** The marker for {@code taskId}, or empty when none has been
     *  recorded (the caller has never queried — every summary is new). */
    Optional<Instant> find(String taskId);

    /** Records {@code queriedThrough} as the marker for {@code taskId},
     *  upserting the row. Idempotent. */
    void mark(String taskId, Instant queriedThrough);
}
