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
 * Pure projection of one row in the {@code tasks} table — the
 * top-level record for an AI coding task. Lifecycle, ownership of the
 * agent loop, persistent metadata.
 *
 * <p>Several fields are conditional on {@link #kind}:
 * <ul>
 *   <li>{@code agentSessionId}, {@code processPid}, {@code logPath}
 *       are populated for {@link TaskKind#CLI_AGENT} tasks while a
 *       child process is alive, and {@code null} for
 *       {@link TaskKind#LOGIC_LOOP}.</li>
 *   <li>{@code branchName} is best-effort sniffed from the working
 *       directory's git head; null when not in a repo.</li>
 *   <li>{@code endedAt} / {@code errorMessage} only set on terminal
 *       transitions (COMPLETED / ERRORED).</li>
 * </ul>
 *
 * @param costUsdMilli running cost in USD × 1000; divide on read so
 *                     SQLite's lack of fixed-precision decimal type
 *                     doesn't cause display drift.
 */
public record Task(
        String id,
        TaskKind kind,
        String provider,
        String agentSessionId,
        String title,
        TaskStatus status,
        String workingDir,
        String branchName,
        String model,
        long costUsdMilli,
        long tokensIn,
        long tokensOut,
        Integer processPid,
        String logPath,
        Instant createdAt,
        Instant updatedAt,
        Instant endedAt,
        String errorMessage,
        String metadataJson,
        /** Optional {@link TaskGroup#id} — null when the task isn't
         *  pinned to any user-defined group. */
        String groupId)
{
}
