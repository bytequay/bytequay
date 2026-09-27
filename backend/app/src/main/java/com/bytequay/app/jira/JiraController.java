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
package com.bytequay.app.jira;

import com.google.common.collect.ImmutableMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@RestController
@RequestMapping("/api/jira")
public class JiraController
{
    private static final String SITE = "/connections/{connectionId}/sites/{cloudId}";
    private static final String ISSUE = SITE + "/issues/{issueId}";
    private final JiraConnections connections;
    private final JiraService jira;

    public JiraController(JiraConnections connections, JiraService jira)
    {
        this.connections = requireNonNull(connections, "connections is null");
        this.jira = requireNonNull(jira, "jira is null");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalidBody()
    {
        // JSON binding failures can otherwise echo a submitted API token.
        return ImmutableMap.of("message", "Invalid Jira request body.");
    }

    @GetMapping("/config")
    public Config config()
    {
        return new Config(connections.configured());
    }

    @GetMapping("/connections")
    public List<JiraDtos.Connection> connections()
    {
        return connections.list();
    }

    @PostMapping("/connections")
    public JiraDtos.Connection connect()
    {
        return connections.connect();
    }

    @PostMapping("/connections/token")
    public JiraDtos.Connection connectToken(@RequestBody Token request)
    {
        return connections.connectToken(request.siteUrl(), request.email(), request.token());
    }

    @PutMapping("/connections/{connectionId}/token")
    public JiraDtos.Connection replaceToken(@PathVariable String connectionId, @RequestBody Token request)
    {
        return connections.replaceToken(connectionId, request.siteUrl(), request.email(), request.token());
    }

    @PostMapping("/connections/{connectionId}/refresh")
    public JiraDtos.Connection refreshConnection(@PathVariable String connectionId)
    {
        return connections.refresh(connectionId);
    }

    @PostMapping("/connections/{connectionId}/reconnect")
    public JiraDtos.Connection reconnect(@PathVariable String connectionId)
    {
        return connections.reconnect(connectionId);
    }

    @DeleteMapping("/connections/{connectionId}")
    public void disconnect(@PathVariable String connectionId)
    {
        connections.disconnect(connectionId);
    }

    @GetMapping(SITE + "/boards")
    public List<JiraDtos.Board> boards(@PathVariable String connectionId, @PathVariable String cloudId)
    {
        return jira.boards(connectionId, cloudId, false);
    }

    @PostMapping(SITE + "/boards/refresh")
    public List<JiraDtos.Board> refreshBoards(@PathVariable String connectionId, @PathVariable String cloudId)
    {
        return jira.boards(connectionId, cloudId, true);
    }

    @GetMapping(SITE + "/boards/{boardId}/sprints")
    public List<JiraDtos.Sprint> sprints(@PathVariable String connectionId, @PathVariable String cloudId, @PathVariable long boardId)
    {
        return jira.sprints(connectionId, cloudId, boardId, false);
    }

    @PostMapping(SITE + "/boards/{boardId}/sprints/refresh")
    public List<JiraDtos.Sprint> refreshSprints(@PathVariable String connectionId, @PathVariable String cloudId, @PathVariable long boardId)
    {
        return jira.sprints(connectionId, cloudId, boardId, true);
    }

    @GetMapping(SITE + "/issues")
    public JiraDtos.IssuePage issues(@PathVariable String connectionId, @PathVariable String cloudId, @RequestParam long boardId,
            @RequestParam Optional<Long> sprintId, @RequestParam(defaultValue = "true") boolean mine, @RequestParam(defaultValue = "false") boolean completed)
    {
        return jira.issues(connectionId, cloudId, boardId, sprintId, mine, completed, false);
    }

    @PostMapping(SITE + "/issues/refresh")
    public JiraDtos.IssuePage refreshIssues(@PathVariable String connectionId, @PathVariable String cloudId, @RequestParam long boardId,
            @RequestParam Optional<Long> sprintId, @RequestParam(defaultValue = "true") boolean mine, @RequestParam(defaultValue = "false") boolean completed)
    {
        return jira.issues(connectionId, cloudId, boardId, sprintId, mine, completed, true);
    }

    @PostMapping(SITE + "/keys/{key}/refresh")
    public JiraIssue refreshKey(@PathVariable String connectionId, @PathVariable String cloudId, @PathVariable String key)
    {
        return jira.refreshIssueByKey(connectionId, cloudId, key);
    }

    @GetMapping(ISSUE)
    public JiraDtos.Detail detail(@PathVariable String connectionId, @PathVariable String cloudId, @PathVariable String issueId)
    {
        return jira.detail(connectionId, cloudId, issueId, false);
    }

    @PostMapping(ISSUE + "/refresh")
    public JiraDtos.Detail refreshDetail(@PathVariable String connectionId, @PathVariable String cloudId, @PathVariable String issueId,
            @RequestParam(defaultValue = "false") boolean automatic)
    {
        return jira.refreshDetail(connectionId, cloudId, issueId, automatic);
    }

    @PatchMapping(ISSUE)
    public JiraDtos.Detail update(@PathVariable String connectionId, @PathVariable String cloudId, @PathVariable String issueId, @RequestBody Edit request)
    {
        return jira.update(connectionId, cloudId, issueId, request.expectedUpdatedAt(), request.fields());
    }

    @PostMapping(ISSUE + "/transitions")
    public JiraDtos.Detail transition(@PathVariable String connectionId, @PathVariable String cloudId, @PathVariable String issueId, @RequestBody Transition request)
    {
        return jira.transition(connectionId, cloudId, issueId, request.expectedUpdatedAt(), request.transitionId(), request.fields());
    }

    @PostMapping(ISSUE + "/sprint")
    public JiraDtos.Detail move(@PathVariable String connectionId, @PathVariable String cloudId, @PathVariable String issueId, @RequestBody Move request)
    {
        return jira.move(connectionId, cloudId, issueId, request.expectedUpdatedAt(), request.sprintId());
    }

    @PostMapping(ISSUE + "/comments")
    public JiraDtos.Detail comment(@PathVariable String connectionId, @PathVariable String cloudId, @PathVariable String issueId, @RequestBody Comment request)
    {
        return jira.comment(connectionId, cloudId, issueId, request.body(), request.operationId(), request.parentId());
    }

    @PutMapping(ISSUE + "/comments/{commentId}")
    public JiraDtos.Detail editComment(@PathVariable String connectionId, @PathVariable String cloudId, @PathVariable String issueId, @PathVariable String commentId,
            @RequestBody CommentEdit request)
    {
        return jira.editComment(connectionId, cloudId, issueId, commentId, request.body(), request.expectedUpdated());
    }

    @PutMapping(ISSUE + "/goal")
    public JiraDtos.Detail goal(@PathVariable String connectionId, @PathVariable String cloudId, @PathVariable String issueId, @RequestBody Goal request)
    {
        return jira.goal(connectionId, cloudId, issueId, request.isGoal());
    }

    @GetMapping("/attention")
    public List<JiraIssue> attention()
    {
        return jira.attention();
    }

    @PutMapping(ISSUE + "/workspace")
    public JiraDtos.Detail assignWorkspace(@PathVariable String connectionId, @PathVariable String cloudId, @PathVariable String issueId, @RequestBody Workspace request)
    {
        return jira.assignWorkspace(connectionId, cloudId, issueId, request.workspaceId());
    }

    /** Reads Jira for the create form; like the refresh routes it is a POST, so every GET stays cache-only. */
    @PostMapping(ISSUE + "/child-types")
    public List<JiraDtos.Choice> childTypes(@PathVariable String connectionId, @PathVariable String cloudId, @PathVariable String issueId)
    {
        return jira.childTypes(connectionId, cloudId, issueId);
    }

    @PostMapping(ISSUE + "/children")
    public JiraDtos.Detail createChild(@PathVariable String connectionId, @PathVariable String cloudId, @PathVariable String issueId, @RequestBody Child request)
    {
        return jira.createChild(connectionId, cloudId, issueId, request.issueTypeId(), request.summary());
    }

    @PostMapping(SITE + "/link-types")
    public List<JiraDtos.LinkType> linkTypes(@PathVariable String connectionId, @PathVariable String cloudId)
    {
        return jira.linkTypes(connectionId, cloudId);
    }

    @PostMapping(ISSUE + "/links")
    public JiraDtos.Detail link(@PathVariable String connectionId, @PathVariable String cloudId, @PathVariable String issueId, @RequestBody Link request)
    {
        return jira.link(connectionId, cloudId, issueId, request.typeId(), request.outward(), request.key());
    }

    public record Config(boolean configured) {}

    public record Token(String siteUrl, String email, String token)
    {
        @Override
        public String toString()
        {
            return "JiraTokenRequest[redacted]";
        }
    }

    public record Edit(Instant expectedUpdatedAt, Map<String, Object> fields) {}

    public record Transition(String transitionId, Instant expectedUpdatedAt, Map<String, Object> fields) {}

    public record Move(Optional<Long> sprintId, Instant expectedUpdatedAt) {}

    public record Comment(String body, String operationId, Optional<String> parentId) {}

    public record CommentEdit(String body, String expectedUpdated) {}

    public record Goal(boolean isGoal) {}

    public record Workspace(String workspaceId) {}

    public record Child(String issueTypeId, String summary) {}

    public record Link(String typeId, boolean outward, String key) {}
}
