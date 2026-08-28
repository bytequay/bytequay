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

/**
 * A piece of text the user wrote on an item. This is the only corpus Words mode
 * searches; nothing anyone else wrote is indexed.
 *
 * @param id stable per source, so re-saving a draft replaces it instead of
 * accumulating revisions.
 */
public record RecentText(
        String id,
        RecentItemType type,
        String itemKey,
        RecentTextKind kind,
        String body,
        Instant writtenAt,
        Integer number,
        String repo,
        String title,
        String htmlUrl)
{
    public RecentText
    {
        requireNonNull(id, "id is null");
        requireNonNull(type, "type is null");
        requireNonNull(itemKey, "itemKey is null");
        requireNonNull(kind, "kind is null");
        requireNonNull(body, "body is null");
        requireNonNull(writtenAt, "writtenAt is null");
        checkArgument(!body.isBlank(), "body is blank");
    }
}
