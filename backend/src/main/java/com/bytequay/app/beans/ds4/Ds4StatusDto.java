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
package com.bytequay.app.beans.ds4;

import com.bytequay.app.service.local.ds4.Ds4State;
import com.bytequay.app.service.local.ds4.Ds4Status;

import java.time.Instant;

/**
 * Wire shape for {@code GET /api/ds4/status}. Adds a derived
 * {@code uptimeSec} so the widget doesn't have to subtract
 * timestamps client-side. {@code requiresConfirm} is set on the
 * response shape from Stop when the lifecycle service is attached
 * to someone else's server.
 */
public record Ds4StatusDto(
        Ds4State state,
        String endpoint,
        long pid,
        Instant startedAt,
        boolean spawnedByUs,
        int restartAttempts,
        long uptimeSec,
        String lastError)
{
    public static Ds4StatusDto from(Ds4Status status)
    {
        long uptime = status.startedAt() == null
                ? 0L
                : Math.max(0L, Instant.now().getEpochSecond() - status.startedAt().getEpochSecond());
        return new Ds4StatusDto(
                status.state(),
                status.endpoint(),
                status.pid(),
                status.startedAt(),
                status.spawnedByUs(),
                status.restartAttempts(),
                uptime,
                status.lastError());
    }
}
