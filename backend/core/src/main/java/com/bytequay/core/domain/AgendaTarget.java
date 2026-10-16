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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * Where an agenda item opens: the page that already shows the pull request, issue, ticket, task, review or work item.
 *
 * @param id the task, review or work item id, or the Jira ticket's issue id
 */
public record AgendaTarget(
        Kind kind,
        Optional<String> workspaceId,
        Optional<String> repo,
        Optional<Integer> number,
        Optional<String> id,
        Optional<String> connectionId,
        Optional<String> cloudId)
{
    public enum Kind
    {
        PULL, ISSUE, JIRA, TASK, REVIEW, WORK
    }

    public AgendaTarget
    {
        requireNonNull(kind, "kind is null");
        requireNonNull(workspaceId, "workspaceId is null");
        requireNonNull(repo, "repo is null");
        requireNonNull(number, "number is null");
        requireNonNull(id, "id is null");
        requireNonNull(connectionId, "connectionId is null");
        requireNonNull(cloudId, "cloudId is null");
        switch (kind) {
            case PULL -> checkArgument(repo.isPresent() && number.isPresent(), "A pull request target needs its repository and number");
            case ISSUE -> checkArgument(repo.isPresent() && number.isPresent() && workspaceId.isPresent(), "An issue target needs its repository, number and workspace");
            case JIRA -> checkArgument(id.isPresent() && connectionId.isPresent() && cloudId.isPresent(), "A Jira target needs its ticket, connection and site");
            case TASK, REVIEW, WORK -> checkArgument(id.isPresent() && workspaceId.isPresent(), "A %s target needs its id and workspace", kind);
        }
    }

    public static AgendaTarget pull(String repo, int number, Optional<String> workspaceId)
    {
        return new AgendaTarget(Kind.PULL, workspaceId, Optional.of(repo), Optional.of(number), Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static AgendaTarget issue(String repo, int number, String workspaceId)
    {
        return new AgendaTarget(Kind.ISSUE, Optional.of(workspaceId), Optional.of(repo), Optional.of(number), Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static AgendaTarget jira(String connectionId, String cloudId, String issueId, Optional<String> workspaceId)
    {
        return new AgendaTarget(Kind.JIRA, workspaceId, Optional.empty(), Optional.empty(), Optional.of(issueId), Optional.of(connectionId), Optional.of(cloudId));
    }

    public static AgendaTarget of(Kind kind, String workspaceId, String id)
    {
        return new AgendaTarget(kind, Optional.of(workspaceId), Optional.empty(), Optional.empty(), Optional.of(id), Optional.empty(), Optional.empty());
    }
}
