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
import java.util.List;

/**
 * A stored AI-drafted PR review — top-level summary plus per-line comments.
 * Each "Run AI review" click produces one of these; older drafts for the
 * same PR are kept so they can be compared.
 */
public record AiReviewDraft(
        long id,
        long prId,
        /** Repo + number captured at run time so publish doesn't need to look them
         *  up in pull_requests (drafts can target PRs the user only browses to,
         *  which never land in the local table). Null for legacy rows whose
         *  originating PR has dropped out of pull_requests by the time the
         *  publish runs — the service falls back to the local lookup then. */
        String repo,
        Integer number,
        String summary,
        String providerId,
        String model,
        String headSha,
        String status,
        Instant createdAt,
        Instant updatedAt,
        List<DraftComment> comments)
{
    public record DraftComment(
            long id,
            String filePath,
            int lineNumber,
            String body,
            /** User-edited replacement for {@link #body}. When non-null, the
             *  publish path sends this to GitHub instead of the original. The
             *  original stays in {@link #body} as the "before" reference. */
            String editedBody,
            String severity,
            /** Soft-deleted: kept on the row so the user can restore it, but
             *  excluded from the publish payload and dimmed in the UI. */
            boolean dismissed,
            /** Origin of the comment — {@code AI} for AI-drafted findings,
             *  {@code HUMAN} for user-authored inline comments staged into
             *  the unified review draft. */
            String source,
            /** Diff side: {@code LEFT} (deleted) or {@code RIGHT} (added).
             *  AI comments always target RIGHT; human comments either. */
            String side,
            /** First line of a multi-line range comment, or null for a
             *  single-line comment. */
            Integer startLine,
            /** Diff side of {@link #startLine}, or null for single-line. */
            String startSide) {}
}
