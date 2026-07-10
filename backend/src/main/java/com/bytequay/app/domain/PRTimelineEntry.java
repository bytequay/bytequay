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
 * One event in a {@link PR}'s unified timeline (design #55). The
 * {@code eventType} wire values match the TypeScript union: {@code commit},
 * {@code ci}, {@code amend}, {@code branch}, {@code status}, {@code review},
 * {@code comment}, {@code follow-up}, {@code plan-finalized}. {@code
 * localOnly} events render with a lock marker and are stripped on push —
 * {@code strippedOnPushAt} is stamped (never migrated to GitHub). {@code
 * payloadJson} is the event-specific payload as raw JSON text, or null.
 * {@code remoteEventId} is the GitHub id of a remote-synced comment/review
 * event (null for every locally-authored event) — it's how a repeated sync
 * avoids re-inserting the same GitHub comment or review as a duplicate row.
 */
public record PRTimelineEntry(
        String id,
        String prId,
        String eventType,
        String actor,
        boolean localOnly,
        Instant strippedOnPushAt,
        Instant createdAt,
        String payloadJson,
        Long remoteEventId)
{
    public static final String TYPE_COMMIT = "commit";
    public static final String TYPE_CI = "ci";
    public static final String TYPE_AMEND = "amend";
    public static final String TYPE_BRANCH = "branch";
    public static final String TYPE_STATUS = "status";
    public static final String TYPE_REVIEW = "review";
    public static final String TYPE_COMMENT = "comment";
    public static final String TYPE_FOLLOW_UP = "follow-up";
    /** The user approved the plan (R20's finalize gate) — carries the
     *  approved PlanStage's id in the payload so the timeline row can link
     *  back to it (see {@code PRService#recordPlanApproved}). */
    public static final String TYPE_PLAN_FINALIZED = "plan-finalized";

    public static final String ACTOR_AGENT = "claude-code";
    public static final String ACTOR_USER = "you";
    /** The brain's own adversarial-review events (plan-rail-runs.md R20-R24)
     *  — always {@code localOnly}, never migrated to GitHub. */
    public static final String ACTOR_BRAIN = "brain";

    /** Copy stamped as stripped-on-push — a local-only event never migrates
     *  to GitHub, so the push transition marks it here (design #47). */
    public PRTimelineEntry withStripped(Instant when)
    {
        return new PRTimelineEntry(
                id, prId, eventType, actor, localOnly, when, createdAt, payloadJson, remoteEventId);
    }
}
