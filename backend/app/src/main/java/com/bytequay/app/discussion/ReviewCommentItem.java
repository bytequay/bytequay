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
package com.bytequay.app.discussion;

import com.bytequay.core.discussion.ReviewCommentAnchor;

import java.time.Instant;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * One keyable comment in a pull request's cached detail, with what the watcher filters on.
 *
 * @param author the commenter's login; empty when GitHub reported none
 * @param createdAt the review's submission time, or the comment's creation time
 */
record ReviewCommentItem(ReviewCommentAnchor anchor, String author, boolean bot, Optional<Instant> createdAt)
{
    ReviewCommentItem
    {
        requireNonNull(anchor, "anchor is null");
        requireNonNull(author, "author is null");
        requireNonNull(createdAt, "createdAt is null");
    }
}
