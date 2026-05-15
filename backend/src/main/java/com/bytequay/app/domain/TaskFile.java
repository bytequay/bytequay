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

import java.time.Instant;

/**
 * Per-file rollup for a task — one row per (task, path). Powers the
 * "Files touched" sidebar card. Aggregates many `Read`/`Edit`/`Write`
 * stream events into a single row per path: count of operations,
 * cumulative line deltas, most-recent op type and timestamp.
 *
 * <p>{@code operation} is kept as a string ("read" / "write" / "edit"
 * / "delete") rather than an enum; the sidebar renderer maps it to
 * the NEW / EDIT / READ / DELETE pill labels.
 */
public record TaskFile(
        String taskId,
        String path,
        String operation,
        int count,
        int linesAdded,
        int linesRemoved,
        Instant lastTouchedAt)
{
}
