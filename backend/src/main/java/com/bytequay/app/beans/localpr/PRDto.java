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
package com.bytequay.app.beans.localpr;

import com.bytequay.app.domain.PR;

import java.time.Instant;

/** Wire shape of a {@link PR}. Timestamps are epoch-millis or null. */
public record PRDto(
        String id,
        String taskId,
        String branchName,
        String baseBranch,
        String title,
        String description,
        String status,
        long createdAt,
        Long pushedAt,
        Integer remotePrNumber,
        String remotePrUrl,
        Long mergedAt,
        Long closedAt,
        String origin,
        String repo,
        String author,
        Long syncedAt)
{
    public static PRDto from(PR pr)
    {
        return new PRDto(
                pr.id(),
                pr.taskId(),
                pr.branchName(),
                pr.baseBranch(),
                pr.title(),
                pr.description(),
                pr.status(),
                pr.createdAt().toEpochMilli(),
                epochOrNull(pr.pushedAt()),
                pr.remotePrNumber(),
                pr.remotePrUrl(),
                epochOrNull(pr.mergedAt()),
                epochOrNull(pr.closedAt()),
                pr.origin(),
                pr.repo(),
                pr.author(),
                epochOrNull(pr.syncedAt()));
    }

    private static Long epochOrNull(Instant instant)
    {
        return instant == null ? null : instant.toEpochMilli();
    }
}
