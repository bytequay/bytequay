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

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * One day's worth of work on one item. Display fields are denormalized onto the
 * row so the stream reads from a single local table and survives the item being
 * dropped from the dashboard projection.
 *
 * @param itemKey cross-type identity, {@code <type prefix>:<repo>#<number>} for
 * pull requests and issues.
 * @param reasonCount the number carried by the reason badge: dwell minutes for
 * {@link RecentReason#READ}, commit count for {@link RecentReason#PUSHED}, and
 * zero everywhere else.
 * @param prId the stored pull-request row id, present only for pull requests
 * and only once the dashboard has seen them. Joins the touch to its
 * {@code pr_view_state} row for the changed-since-I-looked signal.
 */
public record RecentTouch(
        String id,
        RecentItemType type,
        String itemKey,
        LocalDate day,
        Instant touchedAt,
        RecentReason reason,
        int reasonCount,
        Integer number,
        String title,
        String repo,
        String workspaceId,
        String htmlUrl,
        boolean mine,
        Long prId,
        String subtitle)
{
    public RecentTouch
    {
        requireNonNull(id, "id is null");
        requireNonNull(type, "type is null");
        requireNonNull(itemKey, "itemKey is null");
        requireNonNull(day, "day is null");
        requireNonNull(touchedAt, "touchedAt is null");
        requireNonNull(reason, "reason is null");
        requireNonNull(title, "title is null");
        checkArgument(reasonCount >= 0, "reasonCount is negative: %s", reasonCount);
    }

    public Optional<String> workspace()
    {
        return Optional.ofNullable(workspaceId);
    }

    /**
     * Same-day dedupe. The strongest reason wins the badge, and the later touch
     * wins the clock and the display fields, because a title or CI summary read
     * a minute ago is the more truthful one.
     */
    public RecentTouch mergedWith(RecentTouch fresh)
    {
        requireNonNull(fresh, "fresh is null");
        checkArgument(id.equals(fresh.id), "cannot merge touches of different rows");
        RecentTouch later = fresh.touchedAt.isBefore(touchedAt) ? this : fresh;
        boolean keepThisReason = reason.strongerThan(fresh.reason);
        return new RecentTouch(
                id,
                later.type,
                later.itemKey,
                later.day,
                later.touchedAt,
                keepThisReason ? reason : fresh.reason,
                keepThisReason ? reasonCount : fresh.reasonCount,
                later.number,
                later.title,
                later.repo,
                later.workspaceId,
                later.htmlUrl,
                later.mine,
                later.prId == null ? prId : later.prId,
                later.subtitle);
    }
}
