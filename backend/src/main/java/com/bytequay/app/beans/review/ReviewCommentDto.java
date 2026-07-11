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

import com.bytequay.app.domain.PRComment;
import com.bytequay.app.domain.PRTimelineEntry;
import com.bytequay.app.domain.ReviewComment;

/** Wire shape of a review comment for the diff page. {@code createdAt}
 *  is epoch-millis; {@code source} is kept for the legacy renderer contract. */
public record ReviewCommentDto(
        String id,
        String taskId,
        String file,
        int line,
        String side,
        Integer startLine,
        String startSide,
        String body,
        long createdAt,
        String source,
        String author,
        boolean resolved)
{
    public static ReviewCommentDto from(ReviewComment c)
    {
        return new ReviewCommentDto(
                c.id().toString(),
                c.taskId(),
                c.file(),
                c.line(),
                c.side(),
                c.startLine(),
                c.startSide(),
                c.body(),
                c.createdAt().toEpochMilli(),
                c.source().name(),
                null,
                c.resolved());
    }

    public static ReviewCommentDto from(PRComment c, String taskId)
    {
        return new ReviewCommentDto(
                c.id(),
                taskId,
                c.filePath(),
                c.lineNumber() == null ? 0 : c.lineNumber(),
                c.side(),
                c.startLine(),
                c.startSide(),
                c.body(),
                c.createdAt().toEpochMilli(),
                source(c),
                c.author(),
                c.resolvedAt() != null || c.dismissedAt() != null);
    }

    private static String source(PRComment c)
    {
        if (PRComment.ORIGIN_REMOTE.equals(c.origin())) {
            return "REMOTE_REVIEWER";
        }
        return PRTimelineEntry.ACTOR_AGENT.equals(c.author())
                || PRTimelineEntry.ACTOR_BRAIN.equals(c.author())
                ? "LOCAL_AGENT"
                : "LOCAL_USER";
    }
}
