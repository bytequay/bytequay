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
 * One comment on a {@link LocalPR} (design #47). {@code origin = local}
 * comments never migrate to GitHub — the push transition stamps
 * {@code strippedOnPushAt} on every one and they are excluded from any
 * GitHub call; {@code origin = remote} comments are GitHub-sourced.
 * {@code scope} is {@code pr} (a PR-level comment — {@code filePath} /
 * {@code lineNumber} null) or {@code file-line} (inline). {@code
 * parentCommentId} self-references for a single-reply thread.
 *
 * <p>Overlaps the existing unified {@code review_comment} store (V116); the
 * two are reconciled when the Code Diff / PR comment UI is wired.
 */
public record LocalPRComment(
        String id,
        String localPrId,
        String origin,
        String scope,
        String filePath,
        Integer lineNumber,
        String author,
        String body,
        Instant createdAt,
        Instant resolvedAt,
        Instant strippedOnPushAt,
        String parentCommentId)
{
    public static final String ORIGIN_LOCAL = "local";
    public static final String ORIGIN_REMOTE = "remote";

    public static final String SCOPE_PR = "pr";
    public static final String SCOPE_FILE_LINE = "file-line";

    /** Copy marked resolved at {@code when} (no-op fields otherwise). */
    public LocalPRComment withResolved(Instant when)
    {
        return new LocalPRComment(
                id, localPrId, origin, scope, filePath, lineNumber, author, body,
                createdAt, when, strippedOnPushAt, parentCommentId);
    }

    /** Copy stamped stripped-on-push — a local comment never migrates to
     *  GitHub, so the push transition marks it here (design #47). */
    public LocalPRComment withStripped(Instant when)
    {
        return new LocalPRComment(
                id, localPrId, origin, scope, filePath, lineNumber, author, body,
                createdAt, resolvedAt, when, parentCommentId);
    }
}
