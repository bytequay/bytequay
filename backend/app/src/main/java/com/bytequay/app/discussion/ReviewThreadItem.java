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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** An open review thread: the comment that opened it, which replies address, and its latest comment. */
record ReviewThreadItem(long rootCommentId, ReviewCommentItem latest)
{
    ReviewThreadItem
    {
        checkArgument(rootCommentId > 0, "rootCommentId must be positive");
        requireNonNull(latest, "latest is null");
    }

    long latestCommentId()
    {
        return Long.parseLong(latest.anchor().key().substring("review_comment:".length()));
    }
}
