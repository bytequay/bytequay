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
 * One event in a {@link LocalPR}'s unified timeline (design #55). The
 * {@code eventType} wire values match the TypeScript union: {@code commit},
 * {@code ci}, {@code amend}, {@code branch}, {@code status}, {@code review},
 * {@code comment}, {@code follow-up}. {@code localOnly} events render with a
 * lock marker and are stripped on push — {@code strippedOnPushAt} is stamped
 * (never migrated to GitHub). {@code payloadJson} is the event-specific
 * payload as raw JSON text, or null.
 */
public record LocalPRTimelineEvent(
        String id,
        String localPrId,
        String eventType,
        String actor,
        boolean localOnly,
        Instant strippedOnPushAt,
        Instant createdAt,
        String payloadJson)
{
    public static final String TYPE_COMMIT = "commit";
    public static final String TYPE_CI = "ci";
    public static final String TYPE_AMEND = "amend";
    public static final String TYPE_BRANCH = "branch";
    public static final String TYPE_STATUS = "status";
    public static final String TYPE_REVIEW = "review";
    public static final String TYPE_COMMENT = "comment";
    public static final String TYPE_FOLLOW_UP = "follow-up";

    public static final String ACTOR_AGENT = "claude-code";
    public static final String ACTOR_USER = "you";
}
