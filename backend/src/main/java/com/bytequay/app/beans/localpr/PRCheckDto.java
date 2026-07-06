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

import com.bytequay.app.domain.PRCheck;

/** Wire shape of a {@link PRCheck}. */
public record PRCheckDto(
        String id,
        String prId,
        String kind,
        String name,
        String status,
        Long durationMs,
        long startedAt,
        Long finishedAt,
        String runId)
{
    public static PRCheckDto from(PRCheck c)
    {
        return new PRCheckDto(
                c.id(),
                c.prId(),
                c.kind(),
                c.name(),
                c.status(),
                c.durationMs(),
                c.startedAt().toEpochMilli(),
                c.finishedAt() == null ? null : c.finishedAt().toEpochMilli(),
                c.runId());
    }
}
