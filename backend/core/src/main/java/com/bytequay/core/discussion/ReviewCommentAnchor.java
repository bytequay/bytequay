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
package com.bytequay.core.discussion;

import com.google.common.hash.Hashing;

import static com.google.common.base.Preconditions.checkArgument;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/**
 * The GitHub comment a private thread is about.
 *
 * @param key      kind and GitHub id: review:&lt;id&gt;, review_comment:&lt;id&gt; or issue_comment:&lt;id&gt;;
 *                 GitHub ids are unique only within one resource type
 * @param snapshot the untrusted comment JSON, captured when the thread is created and never
 *                 recomputed, so a re-seed reproduces the comment after an edit or deletion
 */
public record ReviewCommentAnchor(String repo, int number, String key, String snapshot)
{
    /** The anchor turn of every comment thread; a real turn id is always a UUID, so it never collides. */
    public static final String TURN = "review-comment";
    public static final int MAX_SNAPSHOT_CHARACTERS = 32_000;

    public ReviewCommentAnchor
    {
        requireNonNull(repo, "repo is null");
        requireNonNull(key, "key is null");
        requireNonNull(snapshot, "snapshot is null");
        checkArgument(repo.matches("[^/\\s]+/[^/\\s]+"), "Invalid repository");
        checkArgument(number > 0, "Invalid pull request number");
        checkArgument(key.matches("(review|review_comment|issue_comment):[0-9]{1,19}"), "Invalid comment key");
        checkArgument(!snapshot.isBlank() && snapshot.length() <= MAX_SNAPSHOT_CHARACTERS, "Comment snapshot exceeds its budget");
    }

    /** Lowercase hex SHA-256 of the key. */
    public static String hash(String key)
    {
        return Hashing.sha256().hashString(key, UTF_8).toString();
    }

    /** Not a getter name, so it is never serialized into the discussion payload. */
    public DiscussionAnchor anchor()
    {
        return new DiscussionAnchor(TURN, key, 1, hash(key));
    }

    /** The key's prefix: review, review_comment or issue_comment. */
    public String kind()
    {
        return key.substring(0, key.indexOf(':'));
    }
}
