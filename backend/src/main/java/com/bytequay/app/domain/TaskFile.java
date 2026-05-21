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
 * Per-file rollup of what one {@link Task}'s agent has touched.
 * One row per (task, path); the renderer maps {@code operation} to
 * the NEW / EDIT / READ / DELETE pill labels in the "Files touched"
 * sidebar.
 *
 * <p>Replaces the legacy {@link ThreadFile} ledger: file edits belong
 * to the branch the work happened on, which is a Task property, not
 * a Thread one. A thread that spans multiple tasks naturally has
 * a distinct list per task.
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
