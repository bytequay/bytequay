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
package com.bytequay.app.workitem;

import com.bytequay.core.domain.WorkItem;

import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * A user's decision on one work item.
 *
 * @param blocker the same Epic's work that BLOCK makes this item wait on, or UNBLOCK stops it waiting on
 * @param releaseDependents whether DROP also stops the work waiting on this item from waiting on it
 */
public record WorkItemCommand(
        String operationId,
        long expectedRevision,
        Action action,
        Optional<String> title,
        Optional<String> acceptanceCriteria,
        Optional<WorkItem.CompletionRule> completionRule,
        String detail,
        Optional<WorkItem.Priority> priority,
        Optional<String> blocker,
        boolean releaseDependents)
{
    public WorkItemCommand
    {
        checkText(operationId, "operationId", 200);
        checkArgument(expectedRevision >= 0, "revision is negative");
        requireNonNull(action, "action is null");
        requireNonNull(title, "title is null");
        requireNonNull(acceptanceCriteria, "acceptanceCriteria is null");
        requireNonNull(completionRule, "completionRule is null");
        requireNonNull(detail, "detail is null");
        checkArgument(detail.length() <= 10000, "result is too long");
        requireNonNull(priority, "priority is null");
        requireNonNull(blocker, "blocker is null");
    }

    public enum Action { EDIT, BEGIN, ACCEPT, FINISH, DROP, REOPEN, PRIORITIZE, BLOCK, UNBLOCK }
}
