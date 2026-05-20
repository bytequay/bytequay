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
 * One per-line review comment from GitHub's
 * /repos/{owner}/{repo}/pulls/{pull_number}/comments endpoint. Top-level
 * messages have {@code inReplyTo == null}; replies link to the thread root
 * via {@code inReplyTo}. The service layer groups these into threads at
 * read time.
 *
 * @param outdated true iff GitHub's REST returned a null position for this
 * comment.
 * @param startLine first line of a multi-line comment range, or null for
 * single-line comments.
 * @param startSide side of {@link #startLine}; usually matches {@link #side}.
 * @param originalLine original file-side line coordinate matching
 * {@link #diffHunk}.
 * @param originalStartLine original file-side start line coordinate matching
 * {@link #diffHunk}.
 * @param authorAssociation author's association with the repo.
 * @param graphqlNodeId GraphQL node id for the thread this message is part of.
 * @param resolved true iff GitHub considers the parent thread resolved.
 */
public record PrReviewThreadMessage(
        long githubId,
        Long inReplyTo,
        Long reviewId,
        String author,
        String body,
        String filePath,
        Integer lineNumber,
        String side,
        String diffHunk,
        String commitId,
        Instant createdAt,
        Reactions reactions,
        boolean outdated,
        Integer startLine,
        String startSide,
        Integer originalLine,
        Integer originalStartLine,
        String authorAssociation,
        String graphqlNodeId,
        Boolean resolved) {}
