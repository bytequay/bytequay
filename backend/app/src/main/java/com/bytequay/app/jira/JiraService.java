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

import com.bytequay.app.agent.JiraIssueLocks;
import com.bytequay.app.service.settings.AppSettingsService;
import com.bytequay.app.workspace.WorkspaceException;
import com.bytequay.core.store.JiraIssueTaskStore;
import com.bytequay.core.store.WorkspaceStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.bytequay.app.jira.JiraConnections.segment;
import static com.bytequay.app.jira.JiraMapping.nodes;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Explicit remote refresh and edits, with cache-only reads for the UI and agents. */
@Service
public class JiraService
{
    private static final String FIELDS = "summary,description,issuetype,status,assignee,labels,priority,parent,subtasks,updated,duedate,resolutiondate,statuscategorychangedate";
    private static final int MAX_PAGES = 20;
    private final JiraConnections connections;
    private final JiraCache cache;
    private final JiraIssueTaskStore tasks;
    private final WorkspaceStore workspaces;
    private final ObjectProvider<AppSettingsService> appSettings;

    public JiraService(JiraConnections connections, JiraCache cache, JiraIssueTaskStore tasks, WorkspaceStore workspaces, ObjectProvider<AppSettingsService> appSettings)
    {
        this.connections = requireNonNull(connections, "connections is null");
        this.cache = requireNonNull(cache, "cache is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        // Agent initialization reads Jira context and also depends on settings; resolve settings only for refresh.
        this.appSettings = requireNonNull(appSettings, "appSettings is null");
    }

    public List<JiraDtos.Board> boards(String connectionId, String cloudId, boolean refresh)
    {
        String key = siteKey(connectionId, cloudId) + ".boards";
        if (refresh) {
            List<JiraDtos.Board> boards = paged(connectionId, cloudId, "/rest/agile/1.0/board", "values", ImmutableMap.of()).stream()
                    .map(item -> new JiraDtos.Board(item.path("id").asLong(), item.path("name").asText(), item.path("type").asText())).collect(toImmutableList());
            cache.write(key, boards);
        }
        return cache.read(key, JiraDtos.Board[].class).map(ImmutableList::copyOf).orElseGet(ImmutableList::of);
    }

    public List<JiraDtos.Sprint> sprints(String connectionId, String cloudId, long boardId, boolean refresh)
    {
        checkArgument(boardId > 0, "Invalid board id");
        String key = siteKey(connectionId, cloudId) + ".sprints." + boardId;
        if (refresh) {
            List<JiraDtos.Sprint> sprints = paged(connectionId, cloudId, "/rest/agile/1.0/board/" + boardId + "/sprint", "values", ImmutableMap.of("state",
                    "active,future")).stream()
                    .map(item -> new JiraDtos.Sprint(item.path("id").asLong(), item.path("name").asText(), item.path("state").asText(), JiraMapping.text(item,
                            "startDate"), JiraMapping.text(item, "endDate"))).collect(toImmutableList());
            cache.write(key, sprints);
        }
        return cache.read(key, JiraDtos.Sprint[].class).map(ImmutableList::copyOf).orElseGet(ImmutableList::of);
    }

    public JiraDtos.IssuePage issues(String connectionId, String cloudId, long boardId, Optional<Long> sprintId, boolean mine, boolean completed, boolean refresh)
    {
        Scope scope = new Scope(segment(connectionId), segment(cloudId), boardId, completed ? Optional.empty() : sprintId, mine, completed);
        checkArgument(boardId > 0 && sprintId.map(id -> id > 0).orElse(true), "Invalid board or sprint id");
        if (refresh) {
            if (!completed) {
                remember(scope);
            }
            return refreshScope(scope);
        }
        return page(scope);
    }

    private synchronized JiraDtos.IssuePage refreshScope(Scope scope)
    {
        String siteUrl = connections.site(scope.connectionId(), scope.cloudId()).url();
        String path = "/rest/software/1.0/board/" + scope.boardId()
                + (scope.completed() ? "/issue" : scope.sprintId().map(id -> "/sprint/" + id + "/issue").orElse("/backlog"));
        List<JiraIssue> result = new ArrayList<>();
        Optional<String> next = Optional.empty();
        boolean partial = false;
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("maxResults", 100);
            params.put("fields", FIELDS);
            if (scope.completed()) {
                params.put("jql", "statusCategory = Done" + (scope.mine() ? " AND assignee = currentUser()" : "") + " ORDER BY statusCategoryChangedDate DESC, resolved DESC");
            }
            else if (scope.mine()) {
                params.put("jql", "assignee = currentUser()");
            }
            next.ifPresent(token -> params.put("nextPageToken", token));
            JsonNode response = get(scope.connectionId(), scope.cloudId(), query(path, params));
            for (JsonNode item : nodes(response.path("issues"))) {
                JiraIssue issue = JiraMapping.issue(scope.connectionId(), scope.cloudId(), siteUrl, item);
                JiraIssue saved = saveIssue(issue, false, true);
                if (!scope.completed() || "done".equals(saved.status().category())) {
                    result.add(saved);
                }
            }
            Optional<String> token = JiraMapping.text(response, "nextPageToken");
            if (token.isEmpty() || response.path("isLast").asBoolean()) {
                break;
            }
            if (token.equals(next) || page == MAX_PAGES - 1) {
                partial = true;
                break;
            }
            next = token;
        }
        JiraDtos.IssuePage page = new JiraDtos.IssuePage(ImmutableList.copyOf(result), Optional.of(Instant.now()), Optional.empty(), partial);
        cache.write(scope.key(), page);
        return page;
    }

    /** No network or credential lookup: also usable after the connection is removed. */
    public JiraIssue issue(String connectionId, String cloudId, String issueId)
    {
        return metadata(cache.read(issueKey(connectionId, cloudId, issueId), JiraIssue.class)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket is not cached. Refresh it in Jira first.")));
    }

    public JiraIssue refreshIssue(String connectionId, String cloudId, String issueId)
    {
        JiraIssue issue = JiraMapping.issue(connectionId, cloudId, connections.site(connectionId, cloudId).url(),
                get(connectionId, cloudId, query(issuePath(issueId), ImmutableMap.of("fields", FIELDS))));
        if (!issue.hasChildren()) {
            JsonNode children = get(connectionId, cloudId, query("/rest/api/3/search/jql",
                    ImmutableMap.of("jql", "parent = " + segment(issue.id()), "maxResults", 1, "fields", "id")));
            issue = issue.withChildren(!nodes(children.path("issues")).isEmpty());
        }
        return saveIssue(issue, false, false);
    }

    public JiraDtos.Detail detail(String connectionId, String cloudId, String issueId, boolean refresh)
    {
        if (refresh) {
            return refreshDetail(connectionId, cloudId, issueId);
        }
        JiraIssue issue = issue(connectionId, cloudId, issueId);
        return cache.read(issueKey(connectionId, cloudId, issueId) + ".detail", JiraDtos.Detail.class)
                .map(saved -> new JiraDtos.Detail(issue, issue.comments(), saved.transitions(), saved.editableFields(), saved.assignees(), saved.fetchedAt(),
                        saved.error(), issue.commentsTruncated()))
                .orElseGet(() -> new JiraDtos.Detail(issue, issue.comments(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), Optional.empty(),
                        Optional.empty(), issue.commentsTruncated()));
    }

    public JiraDtos.Detail refreshDetail(String connectionId, String cloudId, String issueId, boolean automatic)
    {
        return detail(connectionId, cloudId, issueId, !automatic || !appSettings.getObject().inQuietHours());
    }

    private JiraDtos.Detail refreshDetail(String connectionId, String cloudId, String issueId)
    {
        JiraIssue issue = refreshIssue(connectionId, cloudId, issueId);
        String path = issuePath(issueId);
        PageItems commentPage = pageItems(connectionId, cloudId, path + "/comment", "comments", ImmutableMap.of("orderBy", "-created"));
        List<JiraDtos.Comment> comments = commentPage.items().stream().map(JiraMapping::comment)
                .sorted(java.util.Comparator.comparing(JiraDtos.Comment::created)).collect(toImmutableList());
        List<JiraDtos.Transition> transitions = nodes(get(connectionId, cloudId, path + "/transitions?expand=transitions.fields").path("transitions")).stream()
                .map(item -> new JiraDtos.Transition(item.path("id").asText(), item.path("name").asText(), JiraMapping.status(item.path("to")),
                        JiraMapping.fields(item.path("fields")))).collect(toImmutableList());
        List<JiraDtos.Field> fields = ImmutableList.of();
        List<JiraDtos.Person> assignees = ImmutableList.of();
        Optional<String> error = Optional.empty();
        try {
            fields = JiraMapping.fields(get(connectionId, cloudId, path + "/editmeta").path("fields"));
            assignees = nodes(get(connectionId, cloudId, query("/rest/api/3/user/assignable/search", ImmutableMap.of("issueKey", issue.key(), "maxResults",
                    100)))).stream().map(JiraMapping::person).collect(toImmutableList());
        }
        catch (ResponseStatusException e) {
            error = Optional.of("Some editing options are unavailable. Open in Jira for additional fields or assignees.");
        }
        JiraIssue complete = saveIssue(issue.withComments(comments, commentPage.partial()), true, false);
        JiraDtos.Detail detail = new JiraDtos.Detail(complete, complete.comments(), transitions, fields, assignees,
                Optional.of(Instant.now()), error, complete.commentsTruncated());
        cache.write(issueKey(connectionId, cloudId, issueId) + ".detail", detail);
        return detail;
    }

    public boolean isGoal(String cloudId, String issueId)
    {
        return cache.read("goal." + segment(cloudId) + "." + segment(issueId), Boolean.class).orElse(false);
    }

    public JiraDtos.Detail goal(String connectionId, String cloudId, String issueId, boolean goal)
    {
        return JiraIssueLocks.withLock(cloudId, issueId, () -> {
            JiraIssue issue = issue(connectionId, cloudId, issueId);
            if (goal && tasks.findBySource(cloudId, issueId).isPresent()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This ticket already has a development task.");
            }
            if (goal && issue.workspace().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Assign a ByteQuay workspace before marking this ticket as a Goal.");
            }
            cache.write("goal." + segment(cloudId) + "." + segment(issueId), goal);
            return detail(connectionId, cloudId, issueId, false);
        });
    }

    public JiraDtos.Detail assignWorkspace(String connectionId, String cloudId, String issueId, String workspaceId)
    {
        checkArgument(workspaceId != null && !workspaceId.isBlank(), "Workspace is required");
        return JiraIssueLocks.withLock(cloudId, issueId, () -> {
            JiraIssue issue = issue(connectionId, cloudId, issueId);
            WorkspaceException.requireWorkspace(workspaces, workspaceId);
            if (issue.task().filter(task -> !task.workspaceId().equals(workspaceId)).isPresent()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This ticket's development task belongs to another workspace.");
            }
            cache.write("workspace." + segment(cloudId) + "." + segment(issueId), workspaceId);
            return detail(connectionId, cloudId, issueId, false);
        });
    }

    public JiraDtos.Detail update(String connectionId, String cloudId, String issueId, Instant expected, Map<String, Object> fields)
    {
        return JiraIssueLocks.withLock(cloudId, issueId, () -> {
            fresh(connectionId, cloudId, issueId, expected);
            List<JiraDtos.Field> allowed = JiraMapping.fields(get(connectionId, cloudId, issuePath(issueId) + "/editmeta").path("fields"));
            checkArgument(!fields.isEmpty() && ImmutableList.of("summary", "labels", "assignee", "priority", "duedate").containsAll(fields.keySet()), "Unsupported Jira field");
            Map<String, Object> values = fieldValues(fields, allowed, false);
            write(connectionId, cloudId, issuePath(issueId), "PUT", ImmutableMap.of("fields", values));
            return afterWrite(connectionId, cloudId, issueId);
        });
    }

    public JiraDtos.Detail transition(String connectionId, String cloudId, String issueId, Instant expected, String transitionId, Map<String, Object> fields)
    {
        return JiraIssueLocks.withLock(cloudId, issueId, () -> {
            fresh(connectionId, cloudId, issueId, expected);
            JsonNode transition = nodes(get(connectionId, cloudId, issuePath(issueId) + "/transitions?expand=transitions.fields").path("transitions")).stream()
                    .filter(item -> item.path("id").asText().equals(transitionId)).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "This transition is no longer available. Refresh the ticket."));
            Map<String, Object> values = fieldValues(fields, JiraMapping.fields(transition.path("fields")), true);
            write(connectionId, cloudId, issuePath(issueId) + "/transitions", "POST", ImmutableMap.of("transition", ImmutableMap.of("id", transitionId), "fields", values));
            return afterWrite(connectionId, cloudId, issueId);
        });
    }

    public JiraDtos.Detail move(String connectionId, String cloudId, String issueId, Instant expected, Optional<Long> sprintId)
    {
        return JiraIssueLocks.withLock(cloudId, issueId, () -> {
            JiraIssue issue = fresh(connectionId, cloudId, issueId, expected);
            checkArgument(sprintId.map(id -> id > 0).orElse(true), "Invalid sprint id");
            String path = sprintId.map(id -> "/rest/agile/1.0/sprint/" + id + "/issue").orElse("/rest/agile/1.0/backlog/issue");
            write(connectionId, cloudId, path, "POST", ImmutableMap.of("issues", ImmutableList.of(issue.key())));
            return afterWrite(connectionId, cloudId, issueId);
        });
    }

    public JiraDtos.Detail comment(String connectionId, String cloudId, String issueId, String body, String operationId)
    {
        checkArgument(body != null && !body.isBlank() && body.length() <= 32000, "Comment must contain 1–32000 characters");
        checkArgument(operationId != null && operationId.matches("[A-Za-z0-9_-]{16,128}"), "Invalid comment operation id");
        return JiraIssueLocks.withLock(cloudId, issueId, () -> {
            String key = "comment." + segment(cloudId) + "." + segment(issueId) + "." + operationId;
            Optional<CommentAttempt> previous = cache.read(key, CommentAttempt.class);
            if (previous.isPresent()) {
                checkArgument(previous.get().body().equals(body), "This comment operation belongs to a different message");
                if (!previous.get().completed()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "The previous send has an unknown result. Refresh comments and check Jira before composing another message.");
                }
                return afterWrite(connectionId, cloudId, issueId);
            }
            issue(connectionId, cloudId, issueId);
            connections.site(connectionId, cloudId);
            // Persist before sending: a timeout or process exit must never cause an automatic duplicate comment.
            cache.write(key, new CommentAttempt(body, false));
            write(connectionId, cloudId, issuePath(issueId) + "/comment", "POST", ImmutableMap.of("body", JiraMapping.document(body), "properties",
                    ImmutableList.of(ImmutableMap.of("key", "bytequay.operation", "value", operationId))));
            cache.write(key, new CommentAttempt(body, true));
            return afterWrite(connectionId, cloudId, issueId);
        });
    }

    public List<JiraIssue> attention()
    {
        Map<String, JiraIssue> result = new LinkedHashMap<>();
        for (Scope scope : scopes()) {
            if (scope.completed() || !scope.mine() || scope.sprintId().isEmpty() || connections.list()
                    .stream().noneMatch(connection -> connection.id().equals(scope.connectionId()) && connection.state().equals("connected"))) {
                continue;
            }
            boolean active = sprints(scope.connectionId(), scope.cloudId(), scope.boardId(), false).stream()
                    .anyMatch(sprint -> sprint.id() == scope.sprintId().orElseThrow() && sprint.state().equals("active"));
            if (active) {
                page(scope).issues().stream().filter(issue -> !issue.isGoal() && !issue.hasChildren() && !issue.status().category().equals("done") && issue.task().isEmpty())
                        .forEach(issue -> result.put(issue.cloudId() + ":" + issue.id(), issue));
            }
        }
        return ImmutableList.copyOf(result.values());
    }

    @Scheduled(fixedDelay = 600000, initialDelay = 60000)
    public void poll()
    {
        if (appSettings.getObject().inQuietHours()) {
            return;
        }
        List<String> connected = connections.list().stream().filter(connection -> connection.state().equals("connected")).map(JiraDtos.Connection::id).collect(toImmutableList());
        java.util.Set<String> refreshedBoards = new java.util.HashSet<>();
        for (Scope scope : scopes()) {
            if (scope.completed() || !connected.contains(scope.connectionId())) {
                continue;
            }
            try {
                String board = siteKey(scope.connectionId(), scope.cloudId()) + "." + scope.boardId();
                if (refreshedBoards.add(board)) {
                    for (JiraDtos.Sprint sprint : sprints(scope.connectionId(), scope.cloudId(), scope.boardId(), true)) {
                        if (sprint.state().equals("active")) {
                            Scope active = new Scope(scope.connectionId(), scope.cloudId(), scope.boardId(), Optional.of(sprint.id()), true, false);
                            remember(active);
                            if (!active.equals(scope)) {
                                refreshScope(active);
                            }
                        }
                    }
                }
                refreshScope(scope);
            }
            catch (RuntimeException ignored) {
                JiraDtos.IssuePage previous = page(scope);
                cache.write(scope.key(), new JiraDtos.IssuePage(previous.issues(), previous.fetchedAt(),
                        Optional.of("Jira refresh failed. Showing saved tickets."), previous.partial()));
            }
        }
    }

    private JiraDtos.Detail afterWrite(String connectionId, String cloudId, String issueId)
    {
        try {
            return refreshDetail(connectionId, cloudId, issueId);
        }
        catch (RuntimeException ignored) {
            JiraDtos.Detail saved = detail(connectionId, cloudId, issueId, false);
            return new JiraDtos.Detail(saved.issue(), saved.comments(), ImmutableList.of(), ImmutableList.of(), saved.assignees(), saved.fetchedAt(),
                    Optional.of("Saved to Jira, but refreshing failed. Refresh before making another change."), saved.commentsTruncated());
        }
    }

    private JiraIssue fresh(String connectionId, String cloudId, String issueId, Instant expected)
    {
        JiraIssue issue = refreshIssue(connectionId, cloudId, issueId);
        if (!issue.updatedAt().equals(requireNonNull(expected, "expectedUpdatedAt is required"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This ticket changed in Jira. Refresh it before editing.");
        }
        return issue;
    }

    private static Map<String, Object> fieldValues(Map<String, Object> values, List<JiraDtos.Field> allowed, boolean requireFields)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            JiraDtos.Field field = allowed.stream().filter(item -> item.id().equals(entry.getKey())).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Field is not editable: " + entry.getKey()));
            Object value = entry.getValue();
            checkArgument(value == null || value instanceof String || value instanceof Number
                    || (value instanceof List<?> items && items.stream().allMatch(String.class::isInstance)),
                    "Unsupported field value");
            if (value == null && field.required()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Required field: " + field.name());
            }
            if (entry.getKey().equals("assignee") && value instanceof String accountId) {
                value = ImmutableMap.of("accountId", accountId);
            }
            else if (value instanceof String id && !field.allowedValues().isEmpty()) {
                checkArgument(field.allowedValues().stream().anyMatch(choice -> choice.id().equals(id)), "Invalid field choice");
                value = ImmutableMap.of("id", id);
            }
            if (field.schemaType().equals("number") && value instanceof String number) {
                value = new java.math.BigDecimal(number);
            }
            result.put(entry.getKey(), value);
        }
        if (requireFields) {
            for (JiraDtos.Field field : allowed) {
                if (field.required() && (!result.containsKey(field.id()) || result.get(field.id()) == null || result.get(field.id()).equals(""))) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Required field: " + field.name());
                }
            }
        }
        return result;
    }

    private List<JsonNode> paged(String connectionId, String cloudId, String path, String field, Map<String, Object> parameters)
    {
        PageItems page = pageItems(connectionId, cloudId, path, field, parameters);
        if (page.partial()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "This Jira collection exceeds the supported page limit. Open it in Jira.");
        }
        return page.items();
    }

    private PageItems pageItems(String connectionId, String cloudId, String path, String field, Map<String, Object> parameters)
    {
        List<JsonNode> result = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, Object> params = new LinkedHashMap<>(parameters);
            params.put("startAt", result.size());
            params.put("maxResults", 100);
            JsonNode response = get(connectionId, cloudId, query(path, params));
            List<JsonNode> items = nodes(response.path(field));
            result.addAll(items);
            if (items.isEmpty() || response.path("isLast").asBoolean() || result.size() >= response.path("total").asInt(Integer.MAX_VALUE)) {
                return new PageItems(ImmutableList.copyOf(result), false);
            }
        }
        return new PageItems(ImmutableList.copyOf(result), true);
    }

    private JiraIssue saveIssue(JiraIssue incoming, boolean includeComments, boolean summary)
    {
        return JiraIssueLocks.withLock(incoming.cloudId(), incoming.id(), () -> {
            String key = issueKey(incoming.connectionId(), incoming.cloudId(), incoming.id());
            Optional<JiraIssue> previous = cache.read(key, JiraIssue.class);
            if (previous.isPresent() && previous.get().updatedAt().isAfter(incoming.updatedAt())) {
                return metadata(previous.get());
            }
            JiraIssue saved = incoming;
            if (!includeComments && previous.isPresent()) {
                saved = incoming.withComments(previous.get().comments(), previous.get().commentsTruncated());
                if (summary && previous.get().updatedAt().equals(incoming.updatedAt()) && previous.get().hasChildren()) {
                    saved = saved.withChildren(true);
                }
            }
            cache.write(key, saved);
            return metadata(saved);
        });
    }

    private JiraIssue metadata(JiraIssue issue)
    {
        Optional<JiraDtos.TaskLink> task = tasks.findBySource(issue.cloudId(), issue.id()).map(link -> new JiraDtos.TaskLink(link.taskId(), link.workspaceId()));
        Optional<JiraDtos.WorkspaceLink> workspace = task.map(JiraDtos.TaskLink::workspaceId)
                .or(() -> cache.read("workspace." + segment(issue.cloudId()) + "." + segment(issue.id()), String.class))
                .flatMap(workspaces::findById).map(value -> new JiraDtos.WorkspaceLink(value.id(), value.name()));
        return issue.withMetadata(isGoal(issue.cloudId(), issue.id()), task, workspace);
    }

    private JiraDtos.IssuePage page(Scope scope)
    {
        JiraDtos.IssuePage page = cache.read(scope.key(), JiraDtos.IssuePage.class).orElseGet(() -> new JiraDtos.IssuePage(ImmutableList.of(), Optional.empty(),
                Optional.empty(), false));
        List<JiraIssue> issues = page.issues().stream().map(item -> issue(item.connectionId(), item.cloudId(), item.id()))
                .filter(issue -> !scope.completed() || "done".equals(issue.status().category())).collect(toImmutableList());
        return new JiraDtos.IssuePage(issues, page.fetchedAt(), page.error(), page.partial());
    }

    private synchronized void remember(Scope scope)
    {
        List<Scope> scopes = new ArrayList<>(scopes());
        scopes.remove(scope);
        scopes.addFirst(scope);
        cache.write("scopes", scopes.stream().limit(10).collect(toImmutableList()));
    }

    private List<Scope> scopes()
    {
        return cache.read("scopes", Scope[].class).map(ImmutableList::copyOf).orElseGet(ImmutableList::of);
    }

    private JsonNode get(String connectionId, String cloudId, String path)
    {
        return connections.request(connectionId, cloudId, path, "GET", Optional.empty());
    }

    private void write(String connectionId, String cloudId, String path, String method, Object body)
    {
        connections.request(connectionId, cloudId, path, method, Optional.of(body));
    }

    private static String query(String path, Map<String, Object> parameters)
    {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath(path);
        parameters.forEach(builder::queryParam);
        return builder.build().encode().toUriString();
    }

    private static String issuePath(String issueId)
    {
        return "/rest/api/3/issue/" + segment(issueId);
    }

    private static String siteKey(String connectionId, String cloudId)
    {
        return segment(connectionId) + "." + segment(cloudId);
    }

    private static String issueKey(String connectionId, String cloudId, String issueId)
    {
        return siteKey(connectionId, cloudId) + ".issue." + segment(issueId);
    }

    private record Scope(String connectionId, String cloudId, long boardId, Optional<Long> sprintId, boolean mine, boolean completed)
    {
        String key()
        {
            return siteKey(connectionId, cloudId) + ".scope." + boardId + "."
                    + (completed ? "completed" : sprintId.map(String::valueOf).orElse("backlog")) + "." + mine;
        }
    }

    private record PageItems(List<JsonNode> items, boolean partial) {}

    private record CommentAttempt(String body, boolean completed) {}
}
