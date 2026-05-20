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
 * "commented" and "reviewed". PrAttention scans it for {@code @login}
 * mentions to drive the MENTIONED reason.
 *
 * @param githubId stable GitHub event id, or null for rare timeline events
 * without an id.
 * @param beforeSha before SHA on head-ref force-pushed events.
 * @param requestedReviewer login invited to review on review-requested events.
 * @param reviewId GitHub review id for reviewed events.
 * @param authorAssociation author's relationship to the repo.
 * @param reactions reactions tally for commented events.
 */
public record PrTimelineEvent(
        Long githubId,
        String event,
        String actor,
        String state,
        Instant timestamp,
        String body,
        String beforeSha,
        String afterSha,
        String requestedReviewer,
        Long reviewId,
        String authorAssociation,
        Reactions reactions) {}
