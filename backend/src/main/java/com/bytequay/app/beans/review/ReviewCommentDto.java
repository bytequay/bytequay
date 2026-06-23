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
package com.bytequay.app.beans.review;

import com.bytequay.app.domain.ReviewComment;

/** Wire shape of a {@link ReviewComment} for the diff page. {@code createdAt}
 *  is epoch-millis; {@code source} is the enum name. */
public record ReviewCommentDto(
        String id,
        String taskId,
        String file,
        int line,
        String body,
        long createdAt,
        String source,
        boolean resolved)
{
    public static ReviewCommentDto from(ReviewComment c)
    {
        return new ReviewCommentDto(
                c.id().toString(),
                c.taskId(),
                c.file(),
                c.line(),
                c.body(),
                c.createdAt().toEpochMilli(),
                c.source().name(),
                c.resolved());
    }
}
