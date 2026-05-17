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
 * One row of the {@code task_group_members} join table — records that
 * {@link Task#id task} belongs to {@link TaskGroup#id group}.
 *
 * <p>A task may appear in multiple memberships (one per group it's
 * pinned to). A group must always have at least one membership and at
 * most {@link com.bytequay.app.service.tasks.TaskService#GROUP_MAX_MEMBERS}
 * members — both invariants are enforced server-side in
 * {@code TaskService}.
 */
public record TaskGroupMembership(
        String taskId,
        String groupId,
        Instant addedAt)
{
}
