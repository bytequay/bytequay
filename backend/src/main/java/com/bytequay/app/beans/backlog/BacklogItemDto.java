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
package com.bytequay.app.beans.backlog;

import com.bytequay.app.domain.BacklogItem;

import java.util.List;

/** Wire shape of a {@link BacklogItem}. {@code createdAt} / {@code startedAt}
 *  are epoch-millis ({@code startedAt} null until started). */
public record BacklogItemDto(
        String id,
        String threadId,
        String title,
        String body,
        List<String> tags,
        long createdAt,
        Long startedAt,
        String linkedTaskId)
{
    public static BacklogItemDto from(BacklogItem item)
    {
        return new BacklogItemDto(
                item.id(),
                item.threadId(),
                item.title(),
                item.body(),
                item.tags(),
                item.createdAt().toEpochMilli(),
                item.startedAt() == null ? null : item.startedAt().toEpochMilli(),
                item.linkedTaskId());
    }
}
