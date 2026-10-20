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

import static com.bytequay.core.domain.Checks.checkText;
import static java.util.Objects.requireNonNull;

/**
 * The user accepted, dropped or reopened a Goal's work item, published once the decision is saved.
 *
 * @param operationId the decision's operation, so the Goal's agent hears it once
 * @param detail what the decision recorded: the accepted result, or the reason to drop or reopen
 */
public record WorkItemDecided(String workspaceId, String goalId, String workItemId, String title, WorkItem.Kind kind, WorkItemCommand.Action action,
        String operationId, String detail)
{
    public WorkItemDecided
    {
        checkText(workspaceId, "workspaceId");
        checkText(goalId, "goalId");
        checkText(workItemId, "workItemId");
        checkText(title, "title");
        requireNonNull(kind, "kind is null");
        requireNonNull(action, "action is null");
        checkText(operationId, "operationId");
        requireNonNull(detail, "detail is null");
    }
}
