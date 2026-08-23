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

/** An open GitHub issue stored for a monitored repository. */
public record RepositoryIssue(
        long id,
        int number,
        String title,
        String body,
        String author,
        String authorAvatarUrl,
        String htmlUrl,
        List<String> labels,
        List<String> assignees,
        int commentCount,
        Instant createdAt,
        Instant updatedAt)
{
    public RepositoryIssue
    {
        checkArgument(id > 0, "id must be positive");
        checkArgument(number > 0, "number must be positive");
        requireNonNull(title, "title is null");
        requireNonNull(body, "body is null");
        requireNonNull(author, "author is null");
        requireNonNull(authorAvatarUrl, "authorAvatarUrl is null");
        requireNonNull(htmlUrl, "htmlUrl is null");
        labels = ImmutableList.copyOf(requireNonNull(labels, "labels is null"));
        assignees = ImmutableList.copyOf(requireNonNull(assignees, "assignees is null"));
        checkArgument(commentCount >= 0, "commentCount is negative");
        requireNonNull(createdAt, "createdAt is null");
        requireNonNull(updatedAt, "updatedAt is null");
    }
}
