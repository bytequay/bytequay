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
package com.bytequay.core.domain;

import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public record GitHubIssuePreview(
        String title, String body, String author, List<String> labels, Instant updatedAt, String state, boolean pullRequest, String htmlUrl, String authorAvatarUrl)
{
    public GitHubIssuePreview
    {
        requireNonNull(title, "title is null");
        requireNonNull(body, "body is null");
        requireNonNull(author, "author is null");
        labels = ImmutableList.copyOf(labels);
        requireNonNull(updatedAt, "updatedAt is null");
        requireNonNull(htmlUrl, "htmlUrl is null");
        requireNonNull(authorAvatarUrl, "authorAvatarUrl is null");
        checkArgument("open".equals(state) || "closed".equals(state), "state must be open or closed");
    }
}
