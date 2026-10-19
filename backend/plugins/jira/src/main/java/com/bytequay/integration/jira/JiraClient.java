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
package com.bytequay.integration.jira;

import com.bytequay.core.domain.JiraIssueSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Explicit remote operations and cache-only projections for Jira tickets. */
public interface JiraClient
        extends AutoCloseable
{
    void close();

    List<JiraDtos.Board> boards(String connectionId, String cloudId, boolean refresh);

    List<JiraDtos.Sprint> sprints(String connectionId, String cloudId, long boardId, boolean refresh);

    JiraDtos.IssuePage issues(String connectionId, String cloudId, long boardId, Optional<Long> sprintId, boolean mine, boolean coassigned, boolean completed,
            boolean refresh, Optional<String> nextPageToken);

    JiraIssue issue(String connectionId, String cloudId, String issueId);

    Optional<JiraIssue> cachedIssue(String cloudId, String issueId);

    JiraIssue refreshIssue(String connectionId, String cloudId, String issueId);

    JiraIssue refreshIssueByKey(String connectionId, String cloudId, String key);

    JiraDtos.Detail detail(String connectionId, String cloudId, String issueId, boolean refresh);

    List<JiraDtos.Choice> publicationTypes(String connectionId, String cloudId, String project);

    JiraIssueSource publishWorkItem(String connectionId, String cloudId, String project, String type, String title, String body);

    List<JiraDtos.LinkType> linkTypes(String connectionId, String cloudId);

    JiraDtos.Detail link(String connectionId, String cloudId, String issueId, String typeId, boolean outward, String key);

    JiraDtos.Detail update(String connectionId, String cloudId, String issueId, Instant expected, Map<String, Object> fields);

    JiraDtos.Detail transition(String connectionId, String cloudId, String issueId, Instant expected, String transitionId, Map<String, Object> fields);

    JiraDtos.Detail move(String connectionId, String cloudId, String issueId, Instant expected, Optional<Long> sprintId);

    JiraDtos.Detail comment(String connectionId, String cloudId, String issueId, String body, String operationId, Optional<String> parentId);

    JiraDtos.Detail editComment(String connectionId, String cloudId, String issueId, String commentId, String body, String expectedUpdated);

    List<JiraIssue> cachedIssues();

    List<JiraIssue> attention();

    void refreshWatched();
}
