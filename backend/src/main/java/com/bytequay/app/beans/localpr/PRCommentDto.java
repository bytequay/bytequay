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

import com.bytequay.app.domain.PRComment;

import java.time.Instant;

/** Wire shape of a {@link PRComment}. */
public record PRCommentDto(
        String id,
        String prId,
        String origin,
        String scope,
        String filePath,
        Integer lineNumber,
        String author,
        String body,
        long createdAt,
        Long resolvedAt,
        Long dismissedAt,
        Long strippedOnPushAt,
        String parentCommentId,
        Long publishedAt)
{
    public static PRCommentDto from(PRComment c)
    {
        return new PRCommentDto(
                c.id(),
                c.prId(),
                c.origin(),
                c.scope(),
                c.filePath(),
                c.lineNumber(),
                c.author(),
                c.body(),
                c.createdAt().toEpochMilli(),
                epochOrNull(c.resolvedAt()),
                epochOrNull(c.dismissedAt()),
                epochOrNull(c.strippedOnPushAt()),
                c.parentCommentId(),
                epochOrNull(c.publishedAt()));
    }

    private static Long epochOrNull(Instant instant)
    {
        return instant == null ? null : instant.toEpochMilli();
    }
}
