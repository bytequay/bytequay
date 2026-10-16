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
package com.bytequay.core.review;

import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * The user's local pending review for one pull request: the review text and event they chose and
 * the inline items they added, manual or promoted from agent findings. It exists without any agent
 * review and outlives the pull request cache.
 *
 * <p>A finding about the whole pull request joins the review text instead of becoming an item. Its
 * id waits in {@code summaryFindingIds} until a submitted review carries its text, then moves to
 * {@code postedSummaryFindingIds}, so it is never offered again after the text is cleared.
 */
public record ReviewDraft(
        String id,
        String repo,
        int number,
        String body,
        String event,
        List<ReviewDraftItem> items,
        List<String> summaryFindingIds,
        List<String> postedSummaryFindingIds,
        long version,
        Instant updatedAt)
{
    public static final String DEFAULT_EVENT = "COMMENT";

    public ReviewDraft
    {
        requireNonNull(id, "id is null");
        requireNonNull(repo, "repo is null");
        checkArgument(number > 0, "number is not positive");
        requireNonNull(body, "body is null");
        requireNonNull(event, "event is null");
        items = ImmutableList.copyOf(items);
        summaryFindingIds = ImmutableList.copyOf(summaryFindingIds);
        postedSummaryFindingIds = ImmutableList.copyOf(postedSummaryFindingIds);
        checkArgument(version >= 0, "version is negative");
        requireNonNull(updatedAt, "updatedAt is null");
    }

    public static ReviewDraft empty(String repo, int number, Instant now)
    {
        return new ReviewDraft(repo.toLowerCase(Locale.ROOT) + "#" + number, repo.toLowerCase(Locale.ROOT), number, "", DEFAULT_EVENT, ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of(), 0, now);
    }

    public ReviewDraft withVersion(long updatedVersion)
    {
        return new ReviewDraft(id, repo, number, body, event, items, summaryFindingIds, postedSummaryFindingIds, updatedVersion, updatedAt);
    }

    public ReviewDraft withItems(List<ReviewDraftItem> updatedItems, Instant now)
    {
        return new ReviewDraft(id, repo, number, body, event, updatedItems, summaryFindingIds, postedSummaryFindingIds, version, now);
    }

    public ReviewDraft withReview(String updatedBody, String updatedEvent, Instant now)
    {
        return new ReviewDraft(id, repo, number, updatedBody, updatedEvent, items, summaryFindingIds, postedSummaryFindingIds, version, now);
    }

    public ReviewDraft withSummaryFindings(List<String> pending, List<String> posted, Instant now)
    {
        return new ReviewDraft(id, repo, number, body, event, items, pending, posted, version, now);
    }
}
