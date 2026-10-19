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
package com.bytequay.integration.jira.impl;

import com.bytequay.core.domain.JiraIssueSource;
import com.bytequay.core.domain.RecentReason;
import com.bytequay.integration.jira.JiraClient;
import com.bytequay.integration.jira.JiraDtos;
import com.bytequay.integration.jira.JiraIssue;
import com.bytequay.integration.jira.JiraIssueContext;
import com.bytequay.integration.jira.JiraIssueLocks;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.function.Function;

import static com.bytequay.integration.jira.JiraIdentifiers.segment;
import static com.bytequay.integration.jira.impl.JiraMapping.nodes;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Throwables.throwIfUnchecked;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

/** Explicit remote refresh and edits, with cache-only reads for the UI and agents. */
public class DefaultJiraClient
        implements JiraClient
{
    private static final String BOARD_FIELDS = "summary,issuetype,status,assignee,labels,priority,parent,subtasks,updated,duedate,resolutiondate,statuscategorychangedate";
    private static final String FIELDS = BOARD_FIELDS + ",description,issuelinks";
    private static final String KEY = "[A-Z][A-Z0-9_]*-[1-9][0-9]*";
    private static final int MAX_PAGES = 20;
    private final DefaultJiraConnections connections;
    private final JiraCache cache;
    private final JiraActivityLog activity;
    private final JiraIssueContext context;
    private final ObjectMapper responses;
    private final ExecutorService reads = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<Scope, FutureTask<JiraDtos.IssuePage>> refreshes = new ConcurrentHashMap<>();

    public DefaultJiraClient(DefaultJiraConnections connections, JiraCache cache, JiraActivityLog activity, JiraIssueContext context, ObjectMapper responses)
    {
        this.connections = requireNonNull(connections, "connections is null");
        this.cache = requireNonNull(cache, "cache is null");
        this.activity = requireNonNull(activity, "activity is null");
        this.context = requireNonNull(context, "context is null");
        this.responses = requireNonNull(responses, "responses is null").copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Override
    public void close()
    {
        reads.shutdownNow();
    }

    @Override
    public List<JiraDtos.Board> boards(String connectionId, String cloudId, boolean refresh)
    {
        String key = siteKey(connectionId, cloudId) + ".boards";
        if (refresh) {
            List<JiraDtos.Board> boards = paged(connectionId, cloudId, "/rest/agile/1.0/board", "values", ImmutableMap.of(),
                    item -> JiraMapping.response(item, JiraDtos.Board.class, responses));
            cache.write(key, boards);
        }
        return cache.read(key, JiraDtos.Board[].class).map(ImmutableList::copyOf).orElseGet(ImmutableList::of);
    }

    @Override
    public List<JiraDtos.Sprint> sprints(String connectionId, String cloudId, long boardId, boolean refresh)
    {
        checkArgument(boardId > 0, "Invalid board id");
        String key = siteKey(connectionId, cloudId) + ".sprints." + boardId;
        if (refresh) {
            try {
                cache.write(key, paged(connectionId, cloudId, "/rest/agile/1.0/board/" + boardId + "/sprint", "values", ImmutableMap.of("state", "active,future"),
                        item -> JiraMapping.response(item, JiraDtos.Sprint.class, responses)));
            }
            catch (ResponseStatusException failure) {
                if (failure.getStatusCode() != HttpStatus.UNPROCESSABLE_ENTITY) {
                    throw failure;
                }
                // Jira rejects the sprint list of a board without sprints, such as a Kanban board.
                cache.write(key, ImmutableList.of());
            }
        }
        return cache.read(key, JiraDtos.Sprint[].class).map(ImmutableList::copyOf).orElseGet(ImmutableList::of);
    }

    @Override
    public JiraDtos.IssuePage issues(String connectionId, String cloudId, long boardId, Optional<Long> sprintId, boolean mine, boolean coassigned, boolean completed,
            boolean refresh, Optional<String> nextPageToken)
    {
        Scope scope = new Scope(segment(connectionId), segment(cloudId), boardId, completed ? Optional.empty() : sprintId, mine, coassigned, completed);
        checkArgument(boardId > 0 && sprintId.map(id -> id > 0).orElse(true), "Invalid board or sprint id");
        checkArgument(nextPageToken.map(token -> !token.isBlank() && token.length() <= 16384).orElse(true), "Invalid page token");
        checkArgument(refresh || nextPageToken.isEmpty(), "Page tokens require a refresh");
        if (refresh) {
            if (!completed) {
                remember(scope);
            }
            return refreshScope(scope, nextPageToken);
        }
        return page(scope);
    }

    private JiraDtos.IssuePage refreshScope(Scope scope, Optional<String> nextPageToken)
    {
        // A slow history must not hold up another board; concurrent readers of the same scope share its refresh.
        FutureTask<JiraDtos.IssuePage> work = new FutureTask<>(() -> fetchScope(scope, nextPageToken));
        FutureTask<JiraDtos.IssuePage> running = refreshes.putIfAbsent(scope, work);
        if (running != null) {
            return join(running);
        }
        try {
            work.run();
            return join(work);
        }
        finally {
            refreshes.remove(scope, work);
        }
    }

    /** One remote page per request; the returned projection contains the tickets loaded for this scope so far. */
    private JiraDtos.IssuePage fetchScope(Scope scope, Optional<String> nextPageToken)
    {
        JiraDtos.IssuePage previous = page(scope);
        // Another reader or the scheduled poll may already have loaded this cursor. Return its newer projection.
        if (nextPageToken.isPresent() && !nextPageToken.equals(previous.nextPageToken())) {
            return previous;
        }
        try {
            String siteUrl = connections.site(scope.connectionId(), scope.cloudId()).url();
            String path = "/rest/software/1.0/board/" + scope.boardId()
                    + (scope.completed() ? "/issue" : scope.sprintId().map(id -> "/sprint/" + id + "/issue").orElse("/backlog"));
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("maxResults", 100);
            params.put("fields", BOARD_FIELDS);
            String assignment = assignmentQuery(scope);
            if (scope.completed()) {
                params.put("jql", "statusCategory = Done" + (assignment.isEmpty() ? "" : " AND " + assignment) + " ORDER BY statusCategoryChangedDate DESC, resolved DESC");
            }
            else if (!assignment.isEmpty()) {
                params.put("jql", assignment);
            }
            nextPageToken.ifPresent(token -> params.put("nextPageToken", token));
            JsonNode response;
            try {
                response = get(scope.connectionId(), scope.cloudId(), query(path, params));
            }
            catch (ResponseStatusException failure) {
                if (failure.getStatusCode() != HttpStatus.UNPROCESSABLE_ENTITY || !path.endsWith("/backlog")) {
                    throw failure;
                }
                // Jira rejects the backlog of a Kanban board that has none; its open tickets are the board's own.
                // ponytail: one rejected request per refresh of such a board; remember the answer per board if that shows.
                params.put("jql", "statusCategory != Done" + (assignment.isEmpty() ? "" : " AND " + assignment));
                response = get(scope.connectionId(), scope.cloudId(), query("/rest/software/1.0/board/" + scope.boardId() + "/issue", params));
            }
            Optional<String> next = response.path("isLast").asBoolean() ? Optional.empty() : JiraMapping.text(response, "nextPageToken");
            if (next.isPresent() && next.equals(nextPageToken)) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Jira repeated a page token. Refresh tickets to start again.");
            }
            List<JsonNode> items = nodes(response.path("issues"));
            Map<String, JsonNode> descriptions = descriptions(scope.connectionId(), scope.cloudId(), items);
            Map<String, JiraIssue> result = new LinkedHashMap<>();
            if (nextPageToken.isPresent()) {
                previous.issues().forEach(issue -> result.put(issue.id(), issue));
            }
            for (JsonNode item : items) {
                JiraIssue issue = JiraMapping.issue(scope.connectionId(), scope.cloudId(), siteUrl, item,
                        descriptions.getOrDefault(item.path("id").asText(), MissingNode.getInstance()));
                JiraIssue saved = saveIssue(issue, false, true);
                if (!scope.completed() || "done".equals(saved.status().category())) {
                    result.put(saved.id(), saved);
                }
                else {
                    result.remove(saved.id());
                }
            }
            JiraDtos.IssuePage page = new JiraDtos.IssuePage(ImmutableList.copyOf(result.values()), Optional.of(Instant.now()), Optional.empty(), next);
            cache.write(scope.key(), page);
            return page;
        }
        catch (ResponseStatusException failure) {
            JiraDtos.IssuePage failed = new JiraDtos.IssuePage(previous.issues(), previous.fetchedAt(),
                    Optional.of("Jira refresh failed. Showing saved tickets. " + Optional.ofNullable(failure.getReason()).orElse("Refresh to try again.")),
                    previous.nextPageToken());
            cache.write(scope.key(), failed);
            return failed;
        }
    }

    private String assignmentQuery(Scope scope)
    {
        List<String> clauses = new ArrayList<>();
        if (scope.mine()) {
            clauses.add("assignee = currentUser()");
        }
        if (scope.coassigned()) {
            // Co-assignee is a site-defined user field; numeric ids avoid ambiguous or renamed JQL field names.
            JsonNode fields = get(scope.connectionId(), scope.cloudId(), "/rest/api/3/field");
            List<String> coassignees = nodes(fields).stream()
                    .filter(field -> field.path("name").asText().matches("(?i)co[\\s_-]*assignees?"))
                    .filter(field -> field.path("schema").path("type").asText().equals("user")
                            || (field.path("schema").path("type").asText().equals("array") && field.path("schema").path("items").asText().equals("user")))
                    .map(field -> field.path("id").asText())
                    .filter(id -> id.matches("customfield_[0-9]+"))
                    .map(id -> "cf[" + id.substring("customfield_".length()) + "] = currentUser()")
                    .collect(toImmutableList());
            if (coassignees.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "No accessible Co-assignee user field was found on this Jira site.");
            }
            clauses.addAll(coassignees);
        }
        return clauses.size() > 1 ? "(" + String.join(" OR ", clauses) + ")" : String.join("", clauses);
    }

    private void refreshWatchedScope(Scope scope)
    {
        Optional<String> next = Optional.empty();
        for (int page = 0; page < MAX_PAGES; page++) {
            JiraDtos.IssuePage result = refreshScope(scope, next);
            if (result.error().isPresent() || result.nextPageToken().isEmpty()) {
                break;
            }
            next = result.nextPageToken();
        }
    }

    /** Board endpoints return descriptions as wiki markup, so a board page caches the same rich-text projection as a ticket read. */
    private Map<String, JsonNode> descriptions(String connectionId, String cloudId, List<JsonNode> issues)
    {
        if (issues.isEmpty()) {
            return ImmutableMap.of();
        }
        // ponytail: one request, since a board page never exceeds maxResults; follow nextPageToken if Jira starts splitting these pages.
        String jql = issues.stream().map(issue -> segment(issue.path("id").asText())).collect(joining(",", "id in (", ")"));
        JsonNode response = get(connectionId, cloudId, query("/rest/api/3/search/jql", ImmutableMap.of("jql", jql, "fields", "description", "maxResults", 100)));
        return nodes(response.path("issues")).stream().collect(toImmutableMap(issue -> issue.path("id").asText(), issue -> issue.path("fields").path("description")));
    }

    /** No network or credential lookup: also usable after the connection is removed. */
    @Override
    public JiraIssue issue(String connectionId, String cloudId, String issueId)
    {
        return metadata(cache.read(issueKey(connectionId, cloudId, issueId), JiraIssue.class)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket is not cached. Refresh it in Jira first.")));
    }

    /** A ticket as any saved connection last cached it; no network. */
    @Override
    public Optional<JiraIssue> cachedIssue(String cloudId, String issueId)
    {
        return connections.list().stream().flatMap(connection -> cache.read(issueKey(connection.id(), cloudId, issueId), JiraIssue.class).stream()).findFirst()
                .map(this::metadata);
    }

    @Override
    public JiraIssue refreshIssue(String connectionId, String cloudId, String issueId)
    {
        return refreshIssue(connectionId, cloudId, fetch(connectionId, cloudId, issueId));
    }

    private JsonNode fetch(String connectionId, String cloudId, String issueId)
    {
        return get(connectionId, cloudId, query(issuePath(issueId), ImmutableMap.of("fields", FIELDS)));
    }

    private JiraIssue refreshIssue(String connectionId, String cloudId, JsonNode value)
    {
        JiraIssue issue = JiraMapping.issue(connectionId, cloudId, connections.site(connectionId, cloudId).url(), value);
        if (!issue.hasChildren()) {
            JsonNode children = get(connectionId, cloudId, query("/rest/api/3/search/jql",
                    ImmutableMap.of("jql", "parent = " + segment(issue.id()), "maxResults", 1, "fields", "id")));
            issue = issue.withChildren(!nodes(children.path("issues")).isEmpty());
        }
        return saveIssue(issue, false, false);
    }

    /** Resolves the key in a ticket link; the ticket stays cached and addressed by its issue id, never by the key. */
    @Override
    public JiraIssue refreshIssueByKey(String connectionId, String cloudId, String key)
    {
        checkArgument(key.matches(KEY), "Invalid Jira issue key");
        return refreshIssue(connectionId, cloudId, key);
    }

    @Override
    public JiraDtos.Detail detail(String connectionId, String cloudId, String issueId, boolean refresh)
    {
        if (refresh) {
            return refreshDetail(connectionId, cloudId, issueId);
        }
        JiraIssue issue = issue(connectionId, cloudId, issueId);
        return cache.read(issueKey(connectionId, cloudId, issueId) + ".detail", JiraDtos.Detail.class)
                .map(saved -> new JiraDtos.Detail(issue, issue.comments(), saved.transitions(), saved.editableFields(), saved.assignees(), saved.fetchedAt(),
                        saved.error(), issue.commentsTruncated(), saved.hierarchyLevel(), saved.children(), saved.childrenTruncated(), saved.links(), saved.richTextFields(),
                        saved.project(), saved.reporter(), saved.peopleFields(), saved.teamFields()))
                .orElseGet(() -> new JiraDtos.Detail(issue, issue.comments(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), Optional.empty(),
                        Optional.empty(), issue.commentsTruncated(), 0, ImmutableList.of(), false, ImmutableList.of(), ImmutableList.of(), Optional.empty(), Optional.empty(),
                        ImmutableList.of(), ImmutableList.of()));
    }

    private JiraDtos.Detail refreshDetail(String connectionId, String cloudId, String issueId)
    {
        // Custom field ids differ per site, so the dialog asks for every field with the names and schemas that label and type them.
        JsonNode value = get(connectionId, cloudId, query(issuePath(issueId), ImmutableMap.of("fields", "*all,-comment,-worklog", "expand", "names,schema")));
        JiraIssue issue = refreshIssue(connectionId, cloudId, value);
        String path = issuePath(issueId);
        // These reads only need the ticket, so they run together. They must not take JiraIssueLocks: afterWrite waits for them while holding a stripe.
        String siteUrl = connections.site(connectionId, cloudId).url();
        Optional<String> account = connections.get(connectionId).account().map(JiraDtos.Account::accountId);
        FutureTask<PageItems<JiraDtos.Comment>> commentRead = new FutureTask<>(() -> pageItems(connectionId, cloudId, path + "/comment", "comments",
                ImmutableMap.of("orderBy", "-created"), comment -> JiraMapping.comment(siteUrl, account, comment)));
        FutureTask<JsonNode> transitionRead = new FutureTask<>(() -> get(connectionId, cloudId, path + "/transitions?expand=transitions.fields"));
        FutureTask<JsonNode> fieldRead = new FutureTask<>(() -> get(connectionId, cloudId, path + "/editmeta"));
        FutureTask<JsonNode> assigneeRead = new FutureTask<>(() -> get(connectionId, cloudId, query("/rest/api/3/user/assignable/search", ImmutableMap.of("issueKey", issue.key(),
                "maxResults", 100))));
        // refreshIssue already asked Jira whether a ticket without subtasks has children, so only parents need the listing.
        FutureTask<JsonNode> childRead = new FutureTask<>(() -> issue.hasChildren() ? get(connectionId, cloudId, query("/rest/api/3/search/jql", ImmutableMap.of(
                "jql", "parent = " + segment(issue.id()) + " ORDER BY created ASC", "maxResults", 100, "fields", "summary,issuetype,status,priority,assignee")))
                : MissingNode.getInstance());
        List<FutureTask<?>> pending = ImmutableList.of(commentRead, transitionRead, fieldRead, assigneeRead, childRead);
        try {
            pending.forEach(reads::execute);
            PageItems<JiraDtos.Comment> commentPage = join(commentRead);
            List<JiraDtos.Comment> comments = commentPage.items().stream()
                    .sorted(java.util.Comparator.comparing(JiraDtos.Comment::created)).collect(toImmutableList());
            List<JiraDtos.Transition> transitions = nodes(join(transitionRead).path("transitions")).stream()
                    .map(item -> new JiraDtos.Transition(item.path("id").asText(), item.path("name").asText(), JiraMapping.status(item.path("to")),
                            JiraMapping.fields(item.path("fields")))).collect(toImmutableList());
            List<JiraDtos.Field> fields = ImmutableList.of();
            List<JiraDtos.Person> assignees = ImmutableList.of();
            Optional<String> error = Optional.empty();
            try {
                fields = JiraMapping.fields(join(fieldRead).path("fields"));
                assignees = nodes(join(assigneeRead)).stream().map(JiraMapping::person).collect(toImmutableList());
            }
            catch (ResponseStatusException e) {
                if (Thread.currentThread().isInterrupted()) {
                    throw e;
                }
                error = Optional.of("Some editing options are unavailable. Open in Jira for additional fields or assignees.");
            }
            JsonNode children = join(childRead);
            JiraIssue complete = saveIssue(issue.withComments(comments, commentPage.partial()), true, false);
            JiraDtos.Detail detail = new JiraDtos.Detail(complete, complete.comments(), transitions, fields, assignees,
                    Optional.of(Instant.now()), error, complete.commentsTruncated(), value.path("fields").path("issuetype").path("hierarchyLevel").asInt(),
                    nodes(children.path("issues")).stream().map(JiraMapping::related).collect(toImmutableList()), JiraMapping.text(children, "nextPageToken").isPresent(),
                    JiraMapping.links(value.path("fields").path("issuelinks")), JiraMapping.richTextFields(siteUrl, value),
                    JiraMapping.text(value.path("fields").path("project"), "name"), JiraMapping.reporter(value),
                    JiraMapping.peopleFields(value), JiraMapping.teamFields(value));
            cache.write(issueKey(connectionId, cloudId, issueId) + ".detail", detail);
            return detail;
        }
        finally {
            pending.forEach(read -> read.cancel(true));
        }
    }

    @Override
    public List<JiraDtos.Choice> publicationTypes(String connectionId, String cloudId, String project)
    {
        checkArgument(project.matches("[A-Z][A-Z0-9_]*"), "Enter a Jira project key such as ENG");
        return paged(connectionId, cloudId, "/rest/api/3/issue/createmeta/" + segment(project) + "/issuetypes", "issueTypes", ImmutableMap.of(),
                type -> type.path("subtask").asBoolean() ? Optional.<JiraDtos.Choice>empty() : Optional.of(JiraMapping.choice(type)))
                .stream().flatMap(Optional::stream).collect(toImmutableList());
    }

    @Override
    public JiraIssueSource publishWorkItem(String connectionId, String cloudId, String project, String type, String title, String body)
    {
        checkArgument(project.matches("[A-Z][A-Z0-9_]*") && type.matches("[0-9]+"), "Invalid Jira project or work type");
        checkArgument(!title.isBlank() && title.length() <= 255, "Jira titles must contain 1–255 characters");
        String siteUrl = connections.site(connectionId, cloudId).url();
        JsonNode created = write(connectionId, cloudId, "/rest/api/3/issue", "POST", ImmutableMap.of("fields", ImmutableMap.of(
                "project", ImmutableMap.of("key", project), "issuetype", ImmutableMap.of("id", type),
                "summary", title, "description", JiraMapping.document(body))));
        String id = created.path("id").asText();
        String key = created.path("key").asText();
        checkArgument(id.matches("[0-9]+") && key.matches(KEY), "Jira creation returned no ticket identity; check Jira before trying again");
        // Retain the successful response without making another network request that could lose its receipt.
        return new JiraIssueSource(connectionId, cloudId, id, key, siteUrl + "/browse/" + key, title, body, Instant.now());
    }

    @Override
    public List<JiraDtos.LinkType> linkTypes(String connectionId, String cloudId)
    {
        return nodes(get(connectionId, cloudId, "/rest/api/3/issueLinkType").path("issueLinkTypes")).stream()
                .map(type -> new JiraDtos.LinkType(type.path("id").asText(), type.path("name").asText(), type.path("inward").asText(), type.path("outward").asText()))
                .collect(toImmutableList());
    }

    /** {@code outward} reads this ticket with the type's outward description, as in "this ticket blocks ENG-7". */
    @Override
    public JiraDtos.Detail link(String connectionId, String cloudId, String issueId, String typeId, boolean outward, String key)
    {
        checkArgument(typeId != null && typeId.matches("[0-9]+"), "Invalid link type");
        checkArgument(key != null && key.matches(KEY), "Invalid Jira issue key");
        return JiraIssueLocks.withLock(cloudId, issueId, () -> {
            checkArgument(!issue(connectionId, cloudId, issueId).key().equals(key), "A ticket cannot be linked to itself");
            Map<String, String> self = ImmutableMap.of("id", issueId);
            Map<String, String> other = ImmutableMap.of("key", key);
            // Jira reads a created link's inwardIssue with the outward description: inward A, outward B and Blocks mean "A blocks B".
            write(connectionId, cloudId, "/rest/api/3/issueLink", "POST", ImmutableMap.of("type", ImmutableMap.of("id", typeId),
                    "inwardIssue", outward ? self : other, "outwardIssue", outward ? other : self));
            return afterWrite(connectionId, cloudId, issueId, RecentReason.EDITED);
        });
    }

    @Override
    public JiraDtos.Detail update(String connectionId, String cloudId, String issueId, Instant expected, Map<String, Object> fields)
    {
        return JiraIssueLocks.withLock(cloudId, issueId, () -> {
            fresh(connectionId, cloudId, issueId, expected);
            List<JiraDtos.Field> allowed = JiraMapping.fields(get(connectionId, cloudId, issuePath(issueId) + "/editmeta").path("fields"));
            checkArgument(!fields.isEmpty() && ImmutableList.of("summary", "labels", "assignee", "priority", "duedate").containsAll(fields.keySet()), "Unsupported Jira field");
            Map<String, Object> values = fieldValues(fields, allowed, false);
            write(connectionId, cloudId, issuePath(issueId), "PUT", ImmutableMap.of("fields", values));
            return afterWrite(connectionId, cloudId, issueId, RecentReason.EDITED);
        });
    }

    @Override
    public JiraDtos.Detail transition(String connectionId, String cloudId, String issueId, Instant expected, String transitionId, Map<String, Object> fields)
    {
        return JiraIssueLocks.withLock(cloudId, issueId, () -> {
            fresh(connectionId, cloudId, issueId, expected);
            JsonNode transition = nodes(get(connectionId, cloudId, issuePath(issueId) + "/transitions?expand=transitions.fields").path("transitions")).stream()
                    .filter(item -> item.path("id").asText().equals(transitionId)).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "This transition is no longer available. Refresh the ticket."));
            Map<String, Object> values = fieldValues(fields, JiraMapping.fields(transition.path("fields")), true);
            write(connectionId, cloudId, issuePath(issueId) + "/transitions", "POST", ImmutableMap.of("transition", ImmutableMap.of("id", transitionId), "fields", values));
            return afterWrite(connectionId, cloudId, issueId, RecentReason.EDITED);
        });
    }

    @Override
    public JiraDtos.Detail move(String connectionId, String cloudId, String issueId, Instant expected, Optional<Long> sprintId)
    {
        return JiraIssueLocks.withLock(cloudId, issueId, () -> {
            JiraIssue issue = fresh(connectionId, cloudId, issueId, expected);
            checkArgument(sprintId.map(id -> id > 0).orElse(true), "Invalid sprint id");
            String path = sprintId.map(id -> "/rest/agile/1.0/sprint/" + id + "/issue").orElse("/rest/agile/1.0/backlog/issue");
            write(connectionId, cloudId, path, "POST", ImmutableMap.of("issues", ImmutableList.of(issue.key())));
            return afterWrite(connectionId, cloudId, issueId, RecentReason.EDITED);
        });
    }

    @Override
    public JiraDtos.Detail comment(String connectionId, String cloudId, String issueId, String body, String operationId, Optional<String> parentId)
    {
        checkArgument(body != null && !body.isBlank() && body.length() <= 32000, "Comment must contain 1–32000 characters");
        checkArgument(operationId != null && operationId.matches("[A-Za-z0-9_-]{16,128}"), "Invalid comment operation id");
        checkArgument(parentId.map(id -> id.matches("[0-9]+")).orElse(true), "Invalid parent comment id");
        return JiraIssueLocks.withLock(cloudId, issueId, () -> {
            String key = "comment." + segment(cloudId) + "." + segment(issueId) + "." + operationId;
            Optional<CommentAttempt> previous = cache.read(key, CommentAttempt.class);
            if (previous.isPresent()) {
                checkArgument(previous.get().body().equals(body), "This comment operation belongs to a different message");
                if (!previous.get().completed()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "The previous send has an unknown result. Refresh comments and check Jira before composing another message.");
                }
                return afterWrite(connectionId, cloudId, issueId, RecentReason.COMMENTED);
            }
            issue(connectionId, cloudId, issueId);
            connections.site(connectionId, cloudId);
            // Persist before sending: a timeout or process exit must never cause an automatic duplicate comment.
            cache.write(key, new CommentAttempt(body, false));
            ImmutableMap.Builder<String, Object> request = ImmutableMap.<String, Object>builder().put("body", JiraMapping.document(body))
                    .put("properties", ImmutableList.of(ImmutableMap.of("key", "bytequay.operation", "value", operationId)));
            // Jira's published schema has no reply field; its own replies carry the parentId it returns on reads.
            parentId.ifPresent(id -> request.put("parentId", id));
            JsonNode created = write(connectionId, cloudId, issuePath(issueId) + "/comment", "POST", request.buildOrThrow());
            cache.write(key, new CommentAttempt(body, true));
            JiraDtos.Detail detail = afterWrite(connectionId, cloudId, issueId, RecentReason.COMMENTED);
            context.wroteOnJira(detail.issue(), created.path("id").asText(operationId), body);
            if (parentId.isPresent() && !parentId.equals(JiraMapping.text(created, "parentId"))) {
                return new JiraDtos.Detail(detail.issue(), detail.comments(), detail.transitions(), detail.editableFields(), detail.assignees(), detail.fetchedAt(),
                        Optional.of("Jira saved your reply as a new comment instead of a reply."), detail.commentsTruncated(), detail.hierarchyLevel(),
                        detail.children(), detail.childrenTruncated(), detail.links(), detail.richTextFields(), detail.project(), detail.reporter(), detail.peopleFields(),
                        detail.teamFields());
            }
            return detail;
        });
    }

    @Override
    public JiraDtos.Detail editComment(String connectionId, String cloudId, String issueId, String commentId, String body, String expectedUpdated)
    {
        checkArgument(body != null && !body.isBlank() && body.length() <= 32000, "Comment must contain 1–32000 characters");
        checkArgument(commentId != null && commentId.matches("[0-9]+"), "Invalid comment id");
        checkArgument(expectedUpdated != null, "expectedUpdated is required");
        return JiraIssueLocks.withLock(cloudId, issueId, () -> {
            String path = issuePath(issueId) + "/comment/" + segment(commentId);
            JsonNode current = get(connectionId, cloudId, path);
            if (!JiraMapping.mine(connections.get(connectionId).account().map(JiraDtos.Account::accountId), current)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only your own comments can be edited.");
            }
            if (!current.path("updated").asText().equals(expectedUpdated)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This comment changed in Jira. Refresh the ticket before editing it.");
            }
            if (JiraMapping.editableText(current).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This comment has formatting ByteQuay cannot edit. Edit it in Jira.");
            }
            write(connectionId, cloudId, path, "PUT", ImmutableMap.of("body", JiraMapping.document(body, JiraMapping.mentions(current))));
            JiraDtos.Detail detail = afterWrite(connectionId, cloudId, issueId, RecentReason.COMMENTED);
            context.wroteOnJira(detail.issue(), commentId, body);
            return detail;
        });
    }

    /** Every cached ticket of every saved board scope, each once; no network. */
    @Override
    public List<JiraIssue> cachedIssues()
    {
        Map<String, JiraIssue> result = new LinkedHashMap<>();
        for (Scope scope : scopes()) {
            cache.read(scope.key(), JiraDtos.IssuePage.class).ifPresent(page -> page.issues().forEach(item ->
                    cache.read(issueKey(item.connectionId(), item.cloudId(), item.id()), JiraIssue.class).map(this::metadata)
                            .ifPresent(issue -> result.putIfAbsent(issue.cloudId() + ":" + issue.id(), issue))));
        }
        return ImmutableList.copyOf(result.values());
    }

    /** Watched-board tickets assigned to the connected account; the rest of a board is nobody's attention item. */
    @Override
    public List<JiraIssue> attention()
    {
        Map<String, JiraIssue> result = new LinkedHashMap<>();
        Map<String, String> accounts = connections.list().stream().filter(connection -> connection.state().equals("connected") && connection.account().isPresent())
                .collect(toImmutableMap(JiraDtos.Connection::id, connection -> connection.account().orElseThrow().accountId()));
        for (Scope scope : scopes()) {
            Optional<String> account = Optional.ofNullable(accounts.get(scope.connectionId()));
            if (account.isEmpty()) {
                continue;
            }
            page(scope).issues().stream().filter(issue -> issue.assignee().map(JiraDtos.Person::accountId).equals(account))
                    .forEach(issue -> result.put(issue.cloudId() + ":" + issue.id(), issue));
        }
        return ImmutableList.copyOf(result.values());
    }

    @Override
    public void refreshWatched()
    {
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
                            Scope active = new Scope(scope.connectionId(), scope.cloudId(), scope.boardId(), Optional.of(sprint.id()), true, false, false);
                            remember(active);
                            if (!active.equals(scope)) {
                                refreshWatchedScope(active);
                            }
                        }
                    }
                }
                refreshWatchedScope(scope);
            }
            catch (RuntimeException ignored) {
                JiraDtos.IssuePage previous = page(scope);
                cache.write(scope.key(), new JiraDtos.IssuePage(previous.issues(), previous.fetchedAt(),
                        Optional.of("Jira refresh failed. Showing saved tickets."), previous.nextPageToken()));
            }
        }
        readNewComments();
    }

    /**
     * Reads the newest comment of each ticket in Needs attention whose refresh moved its update time
     * without changing anything ByteQuay saves, so a comment becomes the ticket's latest activity.
     */
    private void readNewComments()
    {
        Map<String, Instant> unexplained = activity.takeUnexplained();
        if (unexplained.isEmpty()) {
            return;
        }
        for (JiraIssue issue : attention()) {
            Instant since = unexplained.get(JiraActivityLog.key(issue));
            if (since == null) {
                continue;
            }
            try {
                JsonNode page = get(issue.connectionId(), issue.cloudId(), query(issuePath(issue.id()) + "/comment", ImmutableMap.of("orderBy", "-created", "maxResults", 1)));
                String siteUrl = connections.site(issue.connectionId(), issue.cloudId()).url();
                // A card keeps only who commented and what they said, so whether the viewer may edit it does not matter.
                nodes(page.path("comments")).stream().findFirst().map(comment -> JiraMapping.comment(siteUrl, Optional.empty(), comment))
                        .ifPresent(comment -> activity.commented(issue, comment, since));
            }
            catch (RuntimeException ignored) {
                // The ticket's next change asks again.
            }
        }
    }

    private JiraDtos.Detail afterWrite(String connectionId, String cloudId, String issueId, RecentReason reason)
    {
        JiraDtos.Detail result;
        try {
            result = refreshDetail(connectionId, cloudId, issueId);
        }
        catch (RuntimeException ignored) {
            JiraDtos.Detail saved = detail(connectionId, cloudId, issueId, false);
            result = new JiraDtos.Detail(saved.issue(), saved.comments(), ImmutableList.of(), ImmutableList.of(), saved.assignees(), saved.fetchedAt(),
                    Optional.of("Saved to Jira, but refreshing failed. Refresh before making another change."), saved.commentsTruncated(), saved.hierarchyLevel(),
                    saved.children(), saved.childrenTruncated(), saved.links(), saved.richTextFields(), saved.project(), saved.reporter(), saved.peopleFields(),
                    saved.teamFields());
        }
        context.jiraAction(result.issue(), reason, 0);
        return result;
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

    private <T> List<T> paged(String connectionId, String cloudId, String path, String field, Map<String, Object> parameters, Function<JsonNode, T> decode)
    {
        PageItems<T> page = pageItems(connectionId, cloudId, path, field, parameters, decode);
        if (page.partial()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "This Jira collection exceeds the supported page limit. Open it in Jira.");
        }
        return page.items();
    }

    private <T> PageItems<T> pageItems(String connectionId, String cloudId, String path, String field, Map<String, Object> parameters, Function<JsonNode, T> decode)
    {
        List<T> result = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, Object> params = new LinkedHashMap<>(parameters);
            params.put("startAt", result.size());
            params.put("maxResults", 100);
            JsonNode response = get(connectionId, cloudId, query(path, params));
            Page<T> decoded = Page.read(response, field, decode);
            result.addAll(decoded.items());
            if (decoded.items().isEmpty() || decoded.isLast().orElse(false) || result.size() >= decoded.total().orElse(Integer.MAX_VALUE)) {
                return new PageItems<>(result, false);
            }
        }
        return new PageItems<>(result, true);
    }

    /** Rethrows a read's own failure, so a Jira status still reaches the client and the editing fallback. */
    private static <T> T join(Future<T> read)
    {
        try {
            return read.get();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Jira request was interrupted.");
        }
        catch (ExecutionException e) {
            throwIfUnchecked(e.getCause());
            throw new IllegalStateException("Jira request failed", e.getCause());
        }
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
            activity.observe(previous, saved, includeComments);
            cache.write(key, saved);
            return metadata(saved);
        });
    }

    private JiraIssue metadata(JiraIssue issue)
    {
        return context.metadata(issue);
    }

    private JiraDtos.IssuePage page(Scope scope)
    {
        JiraDtos.IssuePage page = cache.read(scope.key(), JiraDtos.IssuePage.class).orElseGet(() -> new JiraDtos.IssuePage(ImmutableList.of(), Optional.empty(),
                Optional.empty(), Optional.empty()));
        List<JiraIssue> issues = page.issues().stream().map(item -> issue(item.connectionId(), item.cloudId(), item.id()))
                .filter(issue -> !scope.completed() || "done".equals(issue.status().category())).collect(toImmutableList());
        return new JiraDtos.IssuePage(issues, page.fetchedAt(), page.error(), page.nextPageToken());
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

    private JsonNode write(String connectionId, String cloudId, String path, String method, Object body)
    {
        return connections.request(connectionId, cloudId, path, method, Optional.of(body));
    }

    private static String query(String path, Map<String, Object> parameters)
    {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath(path);
        parameters.forEach((name, value) -> builder.queryParam(name, "{" + name + "}"));
        // Tokens can contain reserved characters such as '+', which must remain literal query values.
        return builder.encode().buildAndExpand(parameters).toUriString();
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

    private record Scope(String connectionId, String cloudId, long boardId, Optional<Long> sprintId, boolean mine, boolean coassigned, boolean completed)
    {
        String key()
        {
            return siteKey(connectionId, cloudId) + ".scope." + boardId + "."
                    + (completed ? "completed" : sprintId.map(String::valueOf).orElse("backlog")) + "." + mine + (coassigned ? ".coassigned" : "");
        }
    }

    private record PageItems<T>(List<T> items, boolean partial)
    {
        private PageItems
        {
            items = ImmutableList.copyOf(items);
        }
    }

    private record Page<T>(List<T> items, Optional<Boolean> isLast, Optional<Integer> total)
    {
        private Page
        {
            items = ImmutableList.copyOf(items);
            requireNonNull(isLast, "isLast is null");
            requireNonNull(total, "total is null");
            total.ifPresent(value -> checkArgument(value >= 0, "total is negative"));
        }

        static <T> Page<T> read(JsonNode response, String field, Function<JsonNode, T> decode)
        {
            try {
                checkArgument(response.isObject() && response.path(field).isArray(), "%s must be an array", field);
                Optional<JsonNode> last = Optional.ofNullable(response.get("isLast"));
                last.ifPresent(value -> checkArgument(value.isBoolean(), "isLast must be a boolean"));
                Optional<JsonNode> total = Optional.ofNullable(response.get("total"));
                total.ifPresent(value -> checkArgument(value.isIntegralNumber() && value.canConvertToInt(), "total must be an integer"));
                ImmutableList.Builder<T> items = ImmutableList.builder();
                for (JsonNode item : response.get(field)) {
                    checkArgument(item.isObject(), "%s must contain objects", field);
                    items.add(decode.apply(item));
                }
                return new Page<>(items.build(), last.map(JsonNode::booleanValue), total.map(JsonNode::intValue));
            }
            catch (IllegalArgumentException | NullPointerException failure) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Jira returned an invalid " + field + " page", failure);
            }
        }
    }

    private record CommentAttempt(String body, boolean completed) {}
}
