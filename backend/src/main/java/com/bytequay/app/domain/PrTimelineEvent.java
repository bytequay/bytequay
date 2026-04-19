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
package com.bytequay.app.domain;

import java.time.Instant;

/**
 * A single event from a pull-request timeline, with GitHub-specific fields pre-resolved into
 * a uniform shape. Which events to display and how many to keep are decided by the service
 * layer, not here.
 *
 * <p>{@code body} is non-null only for events that carry comment text — currently
 * {@code commented} and {@code reviewed}. PrAttention scans it for {@code @login}
 * mentions to drive the MENTIONED reason.
 */
public record PrTimelineEvent(
        /** Stable GitHub event id. Used as the dedup key across
         *  incremental fetches — overlapping `since=` windows can return
         *  the same row twice, and the unique (pr_id, github_id) index in
         *  the store turns the second write into a no-op. Null for the
         *  rare timeline event GitHub returns without an id. */
        Long githubId,
        String event,
        String actor,
        String state,
        Instant timestamp,
        String body,
        /** GitHub's head_ref_force_pushed event carries before/after SHAs.
         *  Null for every other event type. Used by the conversation panel
         *  to render "force-pushed · 12 → 14 commits". */
        String beforeSha,
        String afterSha,
        /** For {@code review_requested} events, the login of the user being
         *  invited to review (NOT the actor — actor is the inviter). Null
         *  for every other event type. */
        String requestedReviewer,
        /** For {@code reviewed} events, the GitHub review id — the same id
         *  referenced from per-line review comments via {@code pull_request_review_id}.
         *  Lets the UI link a review event to its line-comment threads
         *  exactly. Null for every other event type. */
        Long reviewId,
        /** Author's relationship to the repo (MEMBER / CONTRIBUTOR /
         *  OWNER / NONE / FIRST_TIME_CONTRIBUTOR / …) for {@code commented}
         *  and {@code reviewed} events. Null for structural events. */
        String authorAssociation,
        /** Reactions tally for {@code commented} events (issue / PR
         *  comments). Null for non-comment events. */
        Reactions reactions) {}
