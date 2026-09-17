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
package com.bytequay.core.review;

import java.util.List;
import java.util.Optional;

/** Durable local test runs, owned by their task; they outlive a review's end and the task's runtime retention. */
public interface LocalRunStore
{
    LocalRun insert(LocalRun run);

    Optional<LocalRun> find(String runId);

    /** A task's runs, highest ordinal first. */
    List<LocalRun> listByTask(String taskId, int limit);

    /** Optimistic update: the stored version must match, and the returned run carries the next version. */
    LocalRun update(LocalRun run);

    /** An optimistic update that also stores the run's bounded output, in one transaction. */
    LocalRun update(LocalRun run, String output);

    /** The stored output, kept apart from the run so listing runs never reads it; empty until the run finishes. */
    Optional<String> findOutput(String runId);

    /** Runs a previous backend left running or holding an unverified process tree. */
    List<LocalRun> listUnsettled();
}
