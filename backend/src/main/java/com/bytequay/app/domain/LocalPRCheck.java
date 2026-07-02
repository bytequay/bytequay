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
 * One check run on a {@link LocalPR} (design #51). {@code kind} is
 * {@code local} (the {@code mvn verify} / {@code tsc} / {@code vitest}
 * validation scripts run every dev iteration) or {@code remote} (GitHub
 * Actions, populated after push — {@code runId} is the Actions run id,
 * remote-only). {@code status} wire values match the TypeScript union.
 */
public record LocalPRCheck(
        String id,
        String localPrId,
        String kind,
        String name,
        String status,
        Long durationMs,
        Instant startedAt,
        Instant finishedAt,
        String runId)
{
    public static final String KIND_LOCAL = "local";
    public static final String KIND_REMOTE = "remote";

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_PASSED = "passed";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_NEUTRAL = "neutral";
}
