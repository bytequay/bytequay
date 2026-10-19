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
package com.bytequay.core.domain;

import static com.bytequay.core.domain.Checks.checkText;
import static java.util.Objects.requireNonNull;

/**
 * A new GitHub issue the assistant drafted for the user to edit and create as an Epic with a click; the assistant creates nothing.
 *
 * @param workspaceId the watched repository the issue goes to
 * @param repository that repository's full name, as the card names it
 * @param body the issue's description as drafted; the title is the item's text
 * @param workItemId the local work item the click creates, named now so a retried click creates it once
 */
public record AssistantDraft(String workspaceId, String repository, String body, String workItemId)
{
    public AssistantDraft
    {
        checkText(workspaceId, "workspaceId");
        checkText(repository, "repository");
        requireNonNull(body, "body is null");
        checkText(workItemId, "workItemId");
    }
}
