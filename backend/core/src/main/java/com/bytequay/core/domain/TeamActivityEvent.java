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
import com.google.common.collect.ImmutableSet;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** One deduplicated GitHub fact, scoped to its collecting account by the storage port. */
public record TeamActivityEvent(
        String id,
        String kind,
        long actorId,
        String actorLogin,
        String repository,
        int number,
        String title,
        String url,
        Instant occurredAt,
        Optional<Long> subjectAuthorId,
        Optional<String> subjectAuthorLogin,
        String source,
        List<Long> viewerInteractionMemberIds)
{
    private static final Set<String> KINDS = ImmutableSet.of("PR_OPENED", "PR_MERGED", "PR_REVIEWED", "PR_COMMENT", "PR_REVIEW_COMMENT",
            "REVIEW_REQUESTED", "ISSUE_OPENED", "ISSUE_CLOSED", "ISSUE_COMMENT");
    private static final Set<String> SOURCES = ImmutableSet.of("PUBLIC_EVENTS", "REPOSITORY", "CACHED_DETAIL");

    public TeamActivityEvent
    {
        requireNonNull(id, "id is null");
        requireNonNull(kind, "kind is null");
        requireNonNull(actorLogin, "actorLogin is null");
        requireNonNull(repository, "repository is null");
        requireNonNull(title, "title is null");
        requireNonNull(url, "url is null");
        requireNonNull(occurredAt, "occurredAt is null");
        requireNonNull(subjectAuthorId, "subjectAuthorId is null");
        requireNonNull(subjectAuthorLogin, "subjectAuthorLogin is null");
        requireNonNull(source, "source is null");
        checkArgument(!id.isBlank() && id.length() <= 300, "event id must be present and bounded");
        checkArgument(KINDS.contains(kind), "Unknown Team activity kind");
        checkArgument(SOURCES.contains(source), "Unknown Team activity source");
        checkArgument(actorId > 0 || actorId == 0 && kind.equals("PR_MERGED") && subjectAuthorId.isPresent(), "event actor must be identified");
        checkArgument(subjectAuthorId.isEmpty() || subjectAuthorId.orElseThrow() > 0, "subject author ID must be positive");
        checkArgument(viewerInteractionMemberIds.stream().allMatch(idValue -> idValue > 0), "interaction member IDs must be positive");
        checkArgument(number > 0, "number must be positive");
        viewerInteractionMemberIds = ImmutableList.copyOf(viewerInteractionMemberIds);
    }

    public boolean pullRequest()
    {
        return kind.startsWith("PR_") || kind.equals("REVIEW_REQUESTED");
    }

    /** Sources may enrich one fact, but never turn it into a second interaction. */
    public TeamActivityEvent merge(TeamActivityEvent next)
    {
        checkArgument(id.equals(next.id), "different event identities");
        return new TeamActivityEvent(id, kind, actorId > 0 ? actorId : next.actorId,
                actorId > 0 ? actorLogin : next.actorLogin, repository, number,
                title.equals(repository + "#" + number) ? next.title : title, url, occurredAt,
                subjectAuthorId.or(next::subjectAuthorId), subjectAuthorLogin.or(next::subjectAuthorLogin), source,
                Stream.concat(viewerInteractionMemberIds.stream(), next.viewerInteractionMemberIds.stream()).distinct().collect(ImmutableList.toImmutableList()));
    }
}
