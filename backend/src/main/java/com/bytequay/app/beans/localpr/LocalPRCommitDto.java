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

import com.bytequay.app.domain.LocalPRCommit;

/** Wire shape of a {@link LocalPRCommit}. */
public record LocalPRCommitDto(
        String id,
        String localPrId,
        String sha,
        String message,
        int additions,
        int deletions,
        long authoredAt,
        Long pushedAt)
{
    public static LocalPRCommitDto from(LocalPRCommit c)
    {
        return new LocalPRCommitDto(
                c.id(),
                c.localPrId(),
                c.sha(),
                c.message(),
                c.additions(),
                c.deletions(),
                c.authoredAt().toEpochMilli(),
                c.pushedAt() == null ? null : c.pushedAt().toEpochMilli());
    }
}
