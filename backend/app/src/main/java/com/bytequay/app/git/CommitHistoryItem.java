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
package com.bytequay.app.git;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Complete local display model for one commit-history card. */
public record CommitHistoryItem(
        String sha,
        String title,
        String body,
        String author,
        Optional<String> authorAvatarUrl,
        String authoredAt,
        Optional<Integer> pullRequestNumber,
        Optional<String> pullRequestTitle)
{
    public CommitHistoryItem
    {
        requireNonNull(sha, "sha is null");
        requireNonNull(title, "title is null");
        requireNonNull(body, "body is null");
        requireNonNull(author, "author is null");
        requireNonNull(authorAvatarUrl, "authorAvatarUrl is null");
        requireNonNull(authoredAt, "authoredAt is null");
        requireNonNull(pullRequestNumber, "pullRequestNumber is null");
        requireNonNull(pullRequestTitle, "pullRequestTitle is null");
        checkArgument(!sha.isBlank(), "sha is blank");
        checkArgument(!title.isBlank(), "title is blank");
        checkArgument(!author.isBlank(), "author is blank");
        checkArgument(!authoredAt.isBlank(), "authoredAt is blank");
        authorAvatarUrl.ifPresent(value -> checkArgument(!value.isBlank(), "authorAvatarUrl is blank"));
        pullRequestNumber.ifPresent(value -> checkArgument(value > 0, "pullRequestNumber is not positive"));
        pullRequestTitle.ifPresent(value -> checkArgument(!value.isBlank(), "pullRequestTitle is blank"));
    }
}
