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

import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Optional;

/**
 * Parameters for creating a new review on a pull request.
 * Maps to the request body of POST /repos/{owner}/{repo}/pulls/{pull_number}/reviews.
 *
 * @param commitId SHA of the commit to review; empty defaults to the most recent commit
 * @param body     summary text accompanying the review; required when event is COMMENT or REQUEST_CHANGES
 * @param event    review action: "APPROVE", "REQUEST_CHANGES", "COMMENT", or "PENDING" (leave as draft)
 * @param comments inline comments to post alongside the review (may be empty)
 */
public record CreateReviewCommand(
        Optional<String> commitId,
        Optional<String> body,
        String event,
        List<ReviewLineComment> comments)
{
    /**
     * An inline comment associated with a review, targeting a specific file line.
     *
     * @param path      relative path to the file being commented on
     * @param position  line index in the diff (deprecated; prefer {@code line})
     * @param line      line number in the file
     * @param side      "LEFT" (deleted) or "RIGHT" (added)
     * @param body      comment text
     * @param startLine first line of a multi-line range (empty for single-line)
     * @param startSide diff side of {@code startLine} (empty for single-line)
     */
    public record ReviewLineComment(
            String path,
            Optional<Integer> position,
            Optional<Integer> line,
            String side,
            String body,
            Optional<Integer> startLine,
            Optional<String> startSide)
    {
        public ReviewLineComment(String path, Optional<Integer> position, Optional<Integer> line, String side, String body)
        {
            this(path, position, line, side, body, Optional.empty(), Optional.empty());
        }
    }

    public static CreateReviewCommand approve(String body)
    {
        return new CreateReviewCommand(Optional.empty(), Optional.of(body), "APPROVE", ImmutableList.of());
    }

    public static CreateReviewCommand requestChanges(String body)
    {
        return new CreateReviewCommand(Optional.empty(), Optional.of(body), "REQUEST_CHANGES", ImmutableList.of());
    }

    public static CreateReviewCommand comment(String body)
    {
        return new CreateReviewCommand(Optional.empty(), Optional.of(body), "COMMENT", ImmutableList.of());
    }
}
