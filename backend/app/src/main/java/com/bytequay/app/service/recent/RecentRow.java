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
package com.bytequay.app.service.recent;

import com.bytequay.core.domain.RecentItemType;
import com.bytequay.core.domain.RecentReason;

import java.time.Instant;

/**
 * One line of the Recent stream.
 *
 * @param reasonLabel the badge text, formatted here so the page, the palette
 * and any later surface all say the same thing.
 * @param snippet the opening of what the user wrote on this item, when there is
 * any, so the common "I only wanted my own words back" errand never needs a
 * second request.
 */
public record RecentRow(
        String id,
        RecentItemType type,
        String itemKey,
        Integer number,
        String title,
        String repo,
        String workspaceId,
        boolean mine,
        Instant touchedAt,
        RecentReason reason,
        String reasonLabel,
        String htmlUrl,
        String subtitle,
        String snippet,
        String textId) {}
