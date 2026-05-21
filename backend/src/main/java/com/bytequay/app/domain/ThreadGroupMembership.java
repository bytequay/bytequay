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

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * One row of the {@code thread_group_members} join table — records that
 * {@link Thread#id thread} belongs to {@link ThreadGroup#id group}.
 *
 * <p>A thread may appear in multiple memberships (one per group it's
 * pinned to). A group must always have at least one membership and at
 * most {@link com.bytequay.app.service.threads.ThreadService#GROUP_MAX_MEMBERS}
 * members — both invariants are enforced server-side in
 * {@code ThreadService}.
 */
public record ThreadGroupMembership(
        // JSON key kept as "taskId" through Phase 4; the frontend renames in lockstep then.
        @JsonProperty("taskId") String threadId,
        String groupId,
        Instant addedAt)
{
}
