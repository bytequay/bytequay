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
        /** True iff GitHub's REST returned a null `position` for this
         *  comment, meaning the line it anchored to no longer exists in
         *  the current diff. Drives the "Outdated" badge in the UI. */
        boolean outdated,
        /** First line of a multi-line comment range (V27). Null for
         *  single-line comments. The pair (startLine, lineNumber) is
         *  the inclusive range covered. */
        Integer startLine,
        /** Side of {@link #startLine} ("LEFT"/"RIGHT") — usually matches
         *  {@link #side} but kept separate to mirror GitHub's API shape. */
        String startSide,
        /** Author's association with the repo (MEMBER / CONTRIBUTOR /
         *  OWNER / NONE / FIRST_TIME_CONTRIBUTOR / …). Powers the role
         *  pill in the UI. Null for legacy rows persisted before this
         *  field was added. */
        String authorAssociation,
        /** GraphQL node id for the *thread* this message is part of
         *  (only meaningful on the thread root, where {@link #inReplyTo}
         *  is null). Required by the resolve / unresolve mutations.
         *  Null until the GraphQL fetcher writes it. */
        String graphqlNodeId,
        /** True iff GitHub considers the parent thread resolved.
         *  Stored on the thread root only; replies inherit. Null for
         *  legacy rows or threads that haven't been GraphQL-fetched. */
        Boolean resolved) {}
