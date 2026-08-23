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
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Locally persisted activity and type data for one repository issue. */
public record RepositoryIssueDetail(
        long repositoryId,
        int issueNumber,
        List<PrTimelineEvent> timeline,
        String currentType,
        List<String> availableTypes,
        Optional<Instant> refreshedAt)
{
    public RepositoryIssueDetail
    {
        checkArgument(repositoryId > 0, "repositoryId must be positive");
        checkArgument(issueNumber > 0, "issueNumber must be positive");
        timeline = ImmutableList.copyOf(requireNonNull(timeline, "timeline is null"));
        availableTypes = ImmutableList.copyOf(requireNonNull(availableTypes, "availableTypes is null"));
        refreshedAt = requireNonNull(refreshedAt, "refreshedAt is null");
    }

    public static RepositoryIssueDetail empty(long repositoryId, int issueNumber)
    {
        return new RepositoryIssueDetail(
                repositoryId, issueNumber, ImmutableList.of(), null, ImmutableList.of(), Optional.empty());
    }

    public RepositoryIssueDetail withType(String type)
    {
        return new RepositoryIssueDetail(
                repositoryId, issueNumber, timeline, type, availableTypes, refreshedAt);
    }

    public RepositoryIssueDetail append(PrTimelineEvent event)
    {
        return new RepositoryIssueDetail(
                repositoryId,
                issueNumber,
                ImmutableList.<PrTimelineEvent>builder().addAll(timeline).add(event).build(),
                currentType,
                availableTypes,
                refreshedAt);
    }
}
