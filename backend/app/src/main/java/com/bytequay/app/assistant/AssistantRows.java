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
package com.bytequay.app.assistant;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.app.agenda.AgendaReader;
import com.bytequay.app.agenda.AgendaRefs;
import com.bytequay.app.jira.JiraService;
import com.bytequay.core.domain.AgendaTarget;
import com.bytequay.core.domain.Epic;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.RepositoryIssuePage;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.EpicStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkItemStore;
import com.bytequay.core.store.WorkspaceStore;
import com.bytequay.integration.jira.JiraIssue;
import com.google.common.base.Splitter;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Finds the items the assistant names in what ByteQuay stores: pull requests, repository issues, cached Jira tickets, tasks, work items
 * and Epics. No GitHub, Jira or provider request is made here.
 */
@Component
class AssistantRows
{
    // ponytail: a search reads each source and filters here; a query in the store once one of them outgrows these bounds.
    private static final int ISSUE_PAGE = 200;
    private static final int ISSUE_PAGES = 5;
    private static final int TASKS = 500;
    private static final Pattern NUMBER = Pattern.compile("#?(\\d{1,9})");

    private final PullRequestStore pullRequests;
    private final RepositoryIssueStore issues;
    private final JiraService jira;
    private final AgentTaskStore tasks;
    private final WorkItemStore workItems;
    private final EpicStore epics;
    private final WorkspaceStore workspaces;
    private final AgendaReader agenda;

    AssistantRows(PullRequestStore pullRequests, RepositoryIssueStore issues, JiraService jira, AgentTaskStore tasks, WorkItemStore workItems, EpicStore epics,
            WorkspaceStore workspaces, AgendaReader agenda)
    {
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.issues = requireNonNull(issues, "issues is null");
        this.jira = requireNonNull(jira, "jira is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.workItems = requireNonNull(workItems, "workItems is null");
        this.epics = requireNonNull(epics, "epics is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.agenda = requireNonNull(agenda, "agenda is null");
    }

    /**
     * One item a search found.
     *
     * @param exact whether the query is its number or key, which ranks it first
     */
    record Found(String ref, String title, Optional<String> state, Optional<Instant> at, AgendaTarget target, boolean exact) {}

    /**
     * The items whose title holds every word of the query, or whose number or key is the query: exact numbers and keys first, then
     * the newest.
     */
    List<Found> search(String query)
    {
        String asked = query.strip();
        List<String> words = Splitter.on(Pattern.compile("\\s+")).omitEmptyStrings().splitToList(asked.toLowerCase(Locale.ROOT));
        Matcher numbered = NUMBER.matcher(asked);
        Optional<Integer> number = numbered.matches() ? Optional.of(Integer.parseInt(numbered.group(1))) : Optional.empty();
        List<Workspace> watched = workspaces.findAll();
        Map<String, Found> found = new LinkedHashMap<>();
        for (PullRequest pr : pullRequests.findAttentionCandidates()) {
            boolean exact = number.filter(value -> value == pr.number()).isPresent();
            if (exact || matches(pr.title(), words)) {
                add(found, new Found(AgendaRefs.pull(pr.repo(), pr.number()), pr.title(), Optional.of(state(pr)), at(pr),
                        AgendaTarget.pull(pr.repo(), pr.number(), workspace(watched, pr.repo()).map(Workspace::id)), exact));
            }
        }
        for (Workspace workspace : watched) {
            issues(workspace, words, number, found);
            List<WorkItem> items = workItems.list(workspace.id());
            for (WorkItem item : items) {
                if (matches(item.title(), words)) {
                    add(found, new Found(AgendaRefs.work(item.id()), item.title(), Optional.of(lower(item.state().name())), Optional.of(item.updatedAt()),
                            AgendaTarget.of(AgendaTarget.Kind.WORK, workspace.id(), item.id()), false));
                }
            }
            epics(workspace, items, words, found);
        }
        for (JiraIssue ticket : jira.cachedIssues()) {
            boolean exact = ticket.key().equalsIgnoreCase(asked);
            if (exact || matches(ticket.key() + " " + ticket.title(), words)) {
                add(found, new Found(AgendaRefs.jira(ticket.cloudId(), ticket.id()), ticket.key() + " " + ticket.title(), Optional.of(ticket.status().name()),
                        Optional.of(ticket.updatedAt()), AgendaTarget.jira(ticket.connectionId(), ticket.cloudId(), ticket.id(), ticket.workspace().map(link -> link.id())),
                        exact));
            }
        }
        for (AgentTask task : tasks.listRecent(TASKS)) {
            // A review task is found by its pull request; only the user's own tasks are found by title.
            if (task.interactive() && task.workspaceId().isPresent() && matches(task.title(), words)) {
                add(found, new Found(AgendaRefs.task(task.id().toString()), task.title(), Optional.of(lower(task.state().name())), Optional.of(task.updatedAt()),
                        AgendaTarget.of(AgendaTarget.Kind.TASK, task.workspaceId().orElseThrow(), task.id().toString()), false));
            }
        }
        return found.values().stream()
                .sorted(Comparator.comparing((Found item) -> !item.exact()).thenComparing(item -> item.at().orElse(Instant.MIN), Comparator.reverseOrder()))
                .collect(toImmutableList());
    }

    /** The repository's open issues that match, the most recently updated first, and the issue the query numbers wherever it is. */
    private void issues(Workspace workspace, List<String> words, Optional<Integer> number, Map<String, Found> found)
    {
        String repo = workspace.monitoredRepository().fullName();
        number.flatMap(value -> issues.find(workspace.monitoredRepositoryId(), value)).ifPresent(issue -> add(found, issue(workspace, repo, issue, true)));
        for (int page = 0; page < ISSUE_PAGES; page++) {
            RepositoryIssuePage read = issues.findPage(workspace.monitoredRepositoryId(), workspace.monitoredRepository(), page, ISSUE_PAGE);
            read.issues().stream().filter(issue -> matches(issue.title(), words)).forEach(issue -> add(found, issue(workspace, repo, issue, false)));
            if (!read.hasMore()) {
                return;
            }
        }
    }

    /** The workspace's Epics, named by their sources, with the work that belongs to them. */
    private void epics(Workspace workspace, List<WorkItem> items, List<String> words, Map<String, Found> found)
    {
        Set<String> epicIds = new LinkedHashSet<>();
        epics.list(workspace.id()).forEach(epic -> epicIds.add(epic.id()));
        items.forEach(item -> item.epicId().ifPresent(epicIds::add));
        for (String epicId : epicIds) {
            agenda.epicSource(workspace, epicId).filter(source -> matches(source.title(), words)).ifPresent(source -> {
                Optional<Instant> touched = items.stream().filter(item -> item.epicId().filter(epicId::equals).isPresent()).map(WorkItem::updatedAt)
                        .max(Comparator.naturalOrder());
                Optional<String> state = epics.find(epicId).flatMap(Epic::concludedAt).map(ignored -> "concluded");
                add(found, new Found(AgendaRefs.epic(epicId), source.title(), state, touched, source.target(), false));
            });
        }
    }

    private static Found issue(Workspace workspace, String repo, RepositoryIssue issue, boolean exact)
    {
        // The store keeps a repository's open issues only.
        return new Found(AgendaRefs.issue(repo, issue.number()), issue.title(), Optional.of("open"), Optional.of(issue.updatedAt()),
                AgendaTarget.issue(repo, issue.number(), workspace.id()), exact);
    }

    /** The first source to find an item names it; a match by its number or key ranks it first wherever it was found. */
    private static void add(Map<String, Found> found, Found item)
    {
        found.merge(item.ref(), item, (first, again) -> again.exact() && !first.exact() ? again : first);
    }

    private static boolean matches(String title, List<String> words)
    {
        String text = title.toLowerCase(Locale.ROOT);
        return !words.isEmpty() && words.stream().allMatch(text::contains);
    }

    static String state(PullRequest pr)
    {
        if (pr.mergedAt() != null) {
            return "merged";
        }
        if (pr.closedAt() != null || "closed".equalsIgnoreCase(pr.state())) {
            return "closed";
        }
        return pr.draft() ? "draft" : "open";
    }

    private static Optional<Instant> at(PullRequest pr)
    {
        return Optional.ofNullable(pr.activityAt()).or(() -> Optional.ofNullable(pr.updatedAt()));
    }

    private static Optional<Workspace> workspace(List<Workspace> watched, String repo)
    {
        return watched.stream().filter(workspace -> workspace.monitoredRepository().fullName().equalsIgnoreCase(repo)).findFirst();
    }

    private static String lower(String value)
    {
        return value.toLowerCase(Locale.ROOT);
    }
}
