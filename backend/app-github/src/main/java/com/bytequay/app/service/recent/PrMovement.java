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
package com.bytequay.app.service.recent;

import com.bytequay.core.domain.PrChangeSnapshot;
import com.bytequay.core.domain.PrCheckRunState;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.StoredPrDetail;
import com.google.common.collect.ImmutableSet;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * What moved on a stored pull request after one moment: replies from people, commits, and checks that failed.
 *
 * @param replies comments and reviews by people other than the viewer; bot accounts are not people
 */
public record PrMovement(long replies, long commits, boolean ciRed)
{
    /** Timeline events that count as somebody replying to the user. */
    private static final Set<String> REPLY_EVENTS = ImmutableSet.of("commented", "reviewed", "review_commented");
    private static final Set<String> FAILED_CONCLUSIONS = ImmutableSet.of("failure", "timed_out", "action_required", "startup_failure");

    public static PrMovement since(StoredPrDetail detail, Instant since, Optional<String> viewer)
    {
        requireNonNull(detail, "detail is null");
        return since(detail.timeline(), detail.checkRuns(), since, viewer);
    }

    public static PrMovement since(PrChangeSnapshot snapshot, Instant since, Optional<String> viewer)
    {
        requireNonNull(snapshot, "snapshot is null");
        return since(snapshot.timeline(), snapshot.checkRuns(), since, viewer);
    }

    private static PrMovement since(List<PrTimelineEvent> timeline, List<PrCheckRunState> checkRuns, Instant since, Optional<String> viewer)
    {
        requireNonNull(since, "since is null");
        requireNonNull(viewer, "viewer is null");
        long replies = timeline.stream()
                .filter(event -> REPLY_EVENTS.contains(event.event()))
                .filter(event -> after(event, since))
                .filter(event -> !bot(event.actor()))
                .filter(event -> viewer.map(login -> !login.equalsIgnoreCase(event.actor())).orElse(true))
                .count();
        long commits = timeline.stream()
                .filter(event -> "committed".equals(event.event()))
                .filter(event -> after(event, since))
                .count();
        boolean ciRed = checkRuns.stream().anyMatch(PrMovement::failed)
                && checkRuns.stream().anyMatch(run -> run.completedAt() != null && run.completedAt().isAfter(since));
        return new PrMovement(replies, commits, ciRed);
    }

    public boolean moved()
    {
        return replies > 0 || commits > 0 || ciRed;
    }

    // ponytail: GitHub's REST login suffix names app accounts; a bot without it counts as a person until one shows up
    public static boolean bot(String login)
    {
        return login != null && login.endsWith("[bot]");
    }

    private static boolean failed(PrCheckRunState run)
    {
        return run.conclusion() != null && FAILED_CONCLUSIONS.contains(run.conclusion().toLowerCase(Locale.ROOT));
    }

    private static boolean after(PrTimelineEvent event, Instant since)
    {
        return event.timestamp() != null && event.timestamp().isAfter(since);
    }
}
