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
package com.bytequay.app.review;

import com.bytequay.core.domain.PrTimelineComment;
import com.bytequay.core.domain.PrTimelinePayload;
import com.bytequay.core.domain.PullRequestDetail;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * The inline review threads the user started on a pull request, read from its stored activity. They are the comments
 * a later review owes an answer to: was each one addressed, or is the author waiting for the user.
 */
final class ReviewOwnThreads
{
    static final int MAX_THREADS = 40;
    private static final int MAX_OPENING = 200;

    private ReviewOwnThreads() {}

    /**
     * @param line the line GitHub last attached the thread to, which for an outdated thread is its original line
     * @param opening the start of the user's first comment, on one line
     * @param awaitingReply unresolved, and the last word is another person's
     */
    record Thread(
            String id,
            String path,
            Optional<Integer> line,
            boolean resolved,
            boolean outdated,
            int comments,
            String opening,
            String lastActor,
            Optional<Instant> lastAt,
            boolean awaitingReply)
    {
        Thread
        {
            requireNonNull(id, "id is null");
            requireNonNull(path, "path is null");
            requireNonNull(line, "line is null");
            requireNonNull(opening, "opening is null");
            requireNonNull(lastActor, "lastActor is null");
            requireNonNull(lastAt, "lastAt is null");
        }
    }

    /** Every thread whose first comment is the user's: unresolved ones first, then the most recently active. */
    static List<Thread> of(PullRequestDetail detail, String login)
    {
        return detail.recentActivity().stream()
                .filter(item -> "review_thread".equals(item.eventType()) && item.githubId() != null && item.payload() != null && !item.payload().comments().isEmpty())
                .filter(item -> login.equalsIgnoreCase(item.payload().comments().getFirst().actor()))
                .map(item -> thread(item.githubId().toString(), item.payload(), login))
                .sorted(Comparator.comparing(Thread::resolved).thenComparing(thread -> thread.lastAt().orElse(Instant.EPOCH), Comparator.reverseOrder()))
                .collect(toImmutableList());
    }

    private static Thread thread(String id, PrTimelinePayload payload, String login)
    {
        PrTimelineComment last = payload.comments().getLast();
        String opening = Optional.ofNullable(payload.comments().getFirst().body()).orElse("").strip().replaceAll("\\s+", " ");
        String lastActor = Optional.ofNullable(last.actor()).orElse("");
        return new Thread(
                id,
                Optional.ofNullable(payload.path()).orElse(""),
                Optional.ofNullable(payload.outdated() ? payload.originalLine() : payload.line()),
                payload.resolved(),
                payload.outdated(),
                payload.comments().size(),
                opening.length() <= MAX_OPENING ? opening : opening.substring(0, MAX_OPENING) + "…",
                lastActor,
                Optional.ofNullable(last.timestamp()),
                !payload.resolved() && !last.actorBot() && !lastActor.isEmpty() && !lastActor.equalsIgnoreCase(login));
    }
}
