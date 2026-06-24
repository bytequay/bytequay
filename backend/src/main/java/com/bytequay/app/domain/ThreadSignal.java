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
 * One passive signal in a thread's Notifications feed — an agent, system,
 * or github event the user can browse. Distinct from {@link Notification}:
 * a signal is inert (no RESOLVING/RESOLVED gate lifecycle), it only flips
 * {@code readAt} when the user opens it.
 *
 * @param sourceKind one of {@code agent} / {@code system} / {@code github}
 * @param iconKind one of {@code info} / {@code success} / {@code warn} / {@code alert}
 * @param sourceUrl where a click navigates, or {@code null}
 */
public record ThreadSignal(
        String id,
        String threadId,
        String taskId,
        String sourceKind,
        String iconKind,
        String title,
        String body,
        String sourceUrl,
        Instant createdAt,
        Instant readAt)
{
    /** True until the user opens the row. */
    public boolean isUnread()
    {
        return readAt == null;
    }
}
