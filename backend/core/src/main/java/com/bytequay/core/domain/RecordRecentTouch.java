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
package com.bytequay.core.domain;

import java.time.Instant;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** A single "I worked on this" signal, before same-day dedupe. */
public record RecordRecentTouch(
        RecentItemType type,
        String itemKey,
        Instant touchedAt,
        RecentReason reason,
        int reasonCount,
        Integer number,
        String title,
        String repo,
        String workspaceId,
        String htmlUrl,
        boolean mine,
        Long prId,
        String subtitle)
{
    public RecordRecentTouch
    {
        requireNonNull(type, "type is null");
        requireNonNull(itemKey, "itemKey is null");
        requireNonNull(touchedAt, "touchedAt is null");
        requireNonNull(reason, "reason is null");
        requireNonNull(title, "title is null");
        checkArgument(!itemKey.isBlank(), "itemKey is blank");
        checkArgument(reasonCount >= 0, "reasonCount is negative: %s", reasonCount);
    }

    /** Cross-type item key for an item GitHub identifies by repository and number. */
    public static String itemKey(RecentItemType type, String repo, int number)
    {
        requireNonNull(type, "type is null");
        requireNonNull(repo, "repo is null");
        return type.prefix() + ":" + repo + "#" + number;
    }
}
