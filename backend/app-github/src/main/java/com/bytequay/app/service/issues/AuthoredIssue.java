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
package com.bytequay.app.service.issues;

import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** An open issue the viewer opened, in any repository. */
public record AuthoredIssue(
        String repo,
        int number,
        String title,
        String htmlUrl,
        List<String> labels,
        List<String> assignees,
        int commentCount,
        Instant createdAt,
        Instant updatedAt)
{
    public AuthoredIssue
    {
        requireNonNull(repo, "repo is null");
        checkArgument(number > 0, "number must be positive");
        requireNonNull(title, "title is null");
        requireNonNull(htmlUrl, "htmlUrl is null");
        labels = ImmutableList.copyOf(labels);
        assignees = ImmutableList.copyOf(assignees);
        requireNonNull(createdAt, "createdAt is null");
        requireNonNull(updatedAt, "updatedAt is null");
    }
}
