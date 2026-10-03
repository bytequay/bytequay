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

import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** The canonical identity is independent of the display key and Jira credential connection. */
public record WorkItemLink(String identity, GoalItem reference, Optional<GitHubIssueSource> issueSource, Optional<JiraIssueSource> jiraSource)
{
    public WorkItemLink
    {
        checkText(identity, "identity");
        requireNonNull(reference, "reference is null");
        requireNonNull(issueSource, "issueSource is null");
        requireNonNull(jiraSource, "jiraSource is null");
        checkArgument(issueSource.isEmpty() || jiraSource.isEmpty(), "link has two accepted sources");
    }

    public WorkItemLink(String identity, GoalItem reference)
    {
        this(identity, reference, Optional.empty(), Optional.empty());
    }

    public static String issueIdentity(String host, long issueId)
    {
        return "ISSUE:" + host + ":" + issueId;
    }

    public static String jiraIdentity(String cloudId, String issueId)
    {
        return "JIRA:" + cloudId.length() + ":" + cloudId + ":" + issueId;
    }

    public static String taskIdentity(String taskId)
    {
        return "TASK:" + taskId;
    }
}
