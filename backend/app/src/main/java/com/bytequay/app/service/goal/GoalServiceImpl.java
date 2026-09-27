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
package com.bytequay.app.service.goal;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskNotFoundException;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.app.jira.JiraIssue;
import com.bytequay.app.jira.JiraService;
import com.bytequay.core.domain.Goal;
import com.bytequay.core.domain.GoalItem;
import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.GoalSource;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.GoalStore;
import com.bytequay.core.store.IssueDevelopmentStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static com.bytequay.app.workspace.WorkspaceException.requireWorkspace;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Service
public class GoalServiceImpl
        implements GoalService
{
    private static final String HOST = "github.com";
    private static final int CANDIDATES_PER_KIND = 8;
    private static final int ISSUE_SCAN = 500;
    private static final int TASK_SCAN = 100;

    private final GoalStore goals;
    private final WorkspaceStore workspaces;
    private final PullRequestStore pullRequests;
    private final RepositoryIssueStore issues;
    private final IssueDevelopmentStore development;
    // Lazy: marking a Jira ticket as a Goal seeds its goal through this service.
    private final ObjectProvider<JiraService> jira;
    private final AgentTaskService tasks;
    private final Clock clock;
    // ponytail: one lock for every workspace's goals; per-workspace locks if goal edits ever contend.
    private final Object writes = new Object();

    public GoalServiceImpl(GoalStore goals, WorkspaceStore workspaces, PullRequestStore pullRequests, RepositoryIssueStore issues,
            IssueDevelopmentStore development, ObjectProvider<JiraService> jira, AgentTaskService tasks, Clock clock)
    {
        this.goals = requireNonNull(goals, "goals is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.issues = requireNonNull(issues, "issues is null");
        this.development = requireNonNull(development, "development is null");
        this.jira = requireNonNull(jira, "jira is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public List<GoalView> list(String workspaceId)
    {
        Resolver resolver = new Resolver(requireWorkspace(workspaces, workspaceId));
        return goals.list(workspaceId).stream().map(resolver::view).collect(toImmutableList());
    }

    @Override
    public GoalView create(String workspaceId, String title, String description)
    {
        Workspace workspace = requireWorkspace(workspaces, workspaceId);
        String name = text(title, "title");
        synchronized (writes) {
            Instant now = clock.instant();
            Goal goal = new Goal(UUID.randomUUID().toString(), workspaceId, name, description.trim(), unpinned(workspaceId), Optional.empty(), ImmutableList.of(), now, now);
            goals.save(goal);
            return new Resolver(workspace).view(goal);
        }
    }

    @Override
    public GoalView update(String workspaceId, String goalId, Optional<String> title, Optional<String> description, boolean pin)
    {
        Workspace workspace = requireWorkspace(workspaces, workspaceId);
        synchronized (writes) {
            Goal goal = goal(workspaceId, goalId);
            Instant now = clock.instant();
            Goal updated = goal.withText(title.map(value -> text(value, "title")).orElse(goal.title()), description.map(String::trim).orElse(goal.description()), now);
            if (pin && !goal.pinned()) {
                goals.list(workspaceId).stream().filter(Goal::pinned).forEach(other -> goals.save(other.withPinned(false, now)));
                updated = updated.withPinned(true, now);
            }
            goals.save(updated);
            return new Resolver(workspace).view(updated);
        }
    }

    @Override
    public void delete(String workspaceId, String goalId)
    {
        requireWorkspace(workspaces, workspaceId);
        synchronized (writes) {
            remove(goal(workspaceId, goalId));
        }
    }

    @Override
    public GoalView addItem(String workspaceId, String goalId, GoalItemKind kind, String key)
    {
        Resolver resolver = new Resolver(requireWorkspace(workspaces, workspaceId));
        // The item is saved under the label and title it has now, so it must be stored locally.
        GoalItemView found = resolver.find(kind, canonical(kind, key))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ByteQuay has not stored this item. Refresh it first."));
        synchronized (writes) {
            Goal goal = goal(workspaceId, goalId);
            if (goal.contains(kind, found.key())) {
                return resolver.view(goal);
            }
            Instant now = clock.instant();
            Goal updated = goal.withItems(ImmutableList.<GoalItem>builder().addAll(goal.items())
                    .add(new GoalItem(kind, found.key(), found.label(), found.title(), now)).build(), now);
            goals.save(updated);
            return resolver.view(updated);
        }
    }

    @Override
    public GoalView removeItem(String workspaceId, String goalId, GoalItemKind kind, String key)
    {
        Resolver resolver = new Resolver(requireWorkspace(workspaces, workspaceId));
        String item = canonical(kind, key);
        synchronized (writes) {
            Goal goal = goal(workspaceId, goalId);
            if (!goal.contains(kind, item)) {
                return resolver.view(goal);
            }
            Goal updated = goal.withItems(goal.items().stream().filter(existing -> !existing.is(kind, item)).collect(toImmutableList()), clock.instant());
            goals.save(updated);
            return resolver.view(updated);
        }
    }

    @Override
    public List<GoalItemView> candidates(String workspaceId, String query)
    {
        Workspace workspace = requireWorkspace(workspaces, workspaceId);
        Resolver resolver = new Resolver(workspace);
        String term = query.trim().toLowerCase(Locale.ROOT);
        Stream<GoalItemView> pulls = pullRequests.findRepositoryPage(workspace.monitoredRepository().fullName(), 0, CANDIDATES_PER_KIND, term)
                .pullRequests().stream().map(resolver::pullRequestView);
        Stream<GoalItemView> repositoryIssues = issues.findPage(workspace.monitoredRepositoryId(), workspace.monitoredRepository(), 0, ISSUE_SCAN).issues().stream()
                .filter(issue -> matches(term, "#" + issue.number(), issue.title())).limit(CANDIDATES_PER_KIND).map(resolver::issueView);
        Stream<GoalItemView> tickets = jira.getObject().cachedIssues().stream()
                .filter(issue -> matches(term, issue.key(), issue.title())).limit(CANDIDATES_PER_KIND).map(resolver::jiraView);
        Stream<GoalItemView> workspaceTasks = tasks.list(workspaceId, TASK_SCAN).stream()
                .filter(task -> matches(term, "", task.title())).limit(CANDIDATES_PER_KIND).map(resolver::taskView);
        return Stream.of(pulls, repositoryIssues, tickets, workspaceTasks).flatMap(stream -> stream).collect(toImmutableList());
    }

    @Override
    public void seed(String workspaceId, GoalSource source, String title)
    {
        synchronized (writes) {
            if (goals.findBySource(source).stream().anyMatch(goal -> goal.workspaceId().equals(workspaceId))) {
                return;
            }
            Instant now = clock.instant();
            goals.save(new Goal(UUID.randomUUID().toString(), workspaceId, text(title, "title"), "", unpinned(workspaceId), Optional.of(source), ImmutableList.of(), now, now));
        }
    }

    @Override
    public void unseed(GoalSource source)
    {
        synchronized (writes) {
            goals.findBySource(source).forEach(this::remove);
        }
    }

    private void remove(Goal goal)
    {
        goals.delete(goal.id());
        if (goal.pinned()) {
            goals.list(goal.workspaceId()).stream().findFirst().ifPresent(next -> goals.save(next.withPinned(true, clock.instant())));
        }
    }

    private boolean unpinned(String workspaceId)
    {
        return goals.list(workspaceId).stream().noneMatch(Goal::pinned);
    }

    private Goal goal(String workspaceId, String goalId)
    {
        return goals.find(goalId).filter(goal -> goal.workspaceId().equals(workspaceId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Goal not found"));
    }

    private static String text(String value, String name)
    {
        requireNonNull(value, name + " is null");
        checkArgument(!value.isBlank(), "%s is blank", name);
        return value.trim();
    }

    private static boolean matches(String term, String label, String title)
    {
        return term.isEmpty() || label.toLowerCase(Locale.ROOT).contains(term) || title.toLowerCase(Locale.ROOT).contains(term);
    }

    private static String canonical(GoalItemKind kind, String key)
    {
        return switch (kind) {
            case PULL_REQUEST, ISSUE -> NumberedKey.parse(key).key();
            case JIRA, TASK -> text(key, "key");
        };
    }

    /** {@code owner/repo#number}, the key of a pull request or issue item. */
    private record NumberedKey(String repo, int number)
    {
        static NumberedKey parse(String key)
        {
            int hash = key.lastIndexOf('#');
            checkArgument(hash > 0 && key.indexOf('/') > 0, "Invalid item key: %s", key);
            try {
                return new NumberedKey(key.substring(0, hash), Integer.parseInt(key.substring(hash + 1)));
            }
            catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid item key: " + key, e);
            }
        }

        String key()
        {
            return GoalItem.numberedKey(repo, number);
        }
    }

    /** Reads items of one workspace from local stores; never from GitHub or Jira. */
    private final class Resolver
    {
        private final Workspace workspace;
        private Optional<Boolean> issuesSynced = Optional.empty();

        Resolver(Workspace workspace)
        {
            this.workspace = workspace;
        }

        GoalView view(Goal goal)
        {
            List<GoalItemView> items = goal.items().stream()
                    .map(item -> find(item.kind(), item.key()).orElseGet(() -> missing(item)).withAddedAt(item.addedAt()))
                    .collect(toImmutableList());
            return new GoalView(goal.id(), goal.workspaceId(), goal.title(), goal.description(), goal.pinned(), goal.source(), items, goal.createdAt(), goal.updatedAt());
        }

        Optional<GoalItemView> find(GoalItemKind kind, String key)
        {
            return switch (kind) {
                case PULL_REQUEST -> pullRequest(key);
                case ISSUE -> issue(key);
                case JIRA -> jiraTicket(key);
                case TASK -> task(key);
            };
        }

        /** An item no local store holds now; an issue gone from this repository's synced open issues was closed. */
        private GoalItemView missing(GoalItem item)
        {
            boolean closed = item.kind() == GoalItemKind.ISSUE && ownRepository(NumberedKey.parse(item.key()).repo()) && issuesSynced();
            String status = closed ? "Closed" : item.kind() == GoalItemKind.TASK ? "Deleted" : "Not stored";
            return new GoalItemView(item.kind(), item.key(), item.label(), item.title(), closed ? GoalItemState.FINISHED : GoalItemState.IDLE, status, false,
                    Optional.empty(), Optional.empty(), Optional.empty());
        }

        private Optional<GoalItemView> pullRequest(String key)
        {
            NumberedKey ref = NumberedKey.parse(key);
            return pullRequests.findIdByRepoAndNumber(ref.repo(), ref.number()).flatMap(pullRequests::findById).map(this::pullRequestView);
        }

        GoalItemView pullRequestView(PullRequest pull)
        {
            boolean merged = "merged".equals(pull.state()) || pull.mergedAt() != null;
            boolean closed = !merged && "closed".equals(pull.state());
            String status = merged ? "Merged" : closed ? "Closed" : pull.draft() ? "Draft" : "Open";
            Instant at = merged && pull.mergedAt() != null ? pull.mergedAt()
                    : closed && pull.closedAt() != null ? pull.closedAt() : Optional.ofNullable(pull.activityAt()).orElse(pull.updatedAt());
            String label = "#" + pull.number();
            return new GoalItemView(GoalItemKind.PULL_REQUEST, GoalItem.numberedKey(pull.repo(), pull.number()), label, pull.title(),
                    merged || closed ? GoalItemState.FINISHED : GoalItemState.WORKING, status, false, Optional.of(pull.htmlUrl()),
                    Optional.of(new GoalActivity(label + (merged ? " merged" : closed ? " closed" : " updated"), at)), Optional.empty());
        }

        private Optional<GoalItemView> issue(String key)
        {
            NumberedKey ref = NumberedKey.parse(key);
            if (!ownRepository(ref.repo())) {
                return Optional.empty();
            }
            return issues.find(workspace.monitoredRepositoryId(), ref.number()).map(this::issueView);
        }

        GoalItemView issueView(RepositoryIssue issue)
        {
            boolean developing = development.findTask(HOST, issue.id()).isPresent();
            String label = "#" + issue.number();
            return new GoalItemView(GoalItemKind.ISSUE, GoalItem.numberedKey(workspace.monitoredRepository().fullName(), issue.number()), label, issue.title(),
                    developing ? GoalItemState.WORKING : GoalItemState.IDLE, "Open", false, Optional.of(issue.htmlUrl()),
                    Optional.of(new GoalActivity(label + " updated", issue.updatedAt())), Optional.empty());
        }

        private Optional<GoalItemView> jiraTicket(String key)
        {
            String[] parts = key.split(":", 3);
            if (parts.length != 3) {
                return Optional.empty();
            }
            try {
                return Optional.of(jiraView(jira.getObject().issue(parts[0], parts[1], parts[2])));
            }
            catch (ResponseStatusException e) {
                // Not cached: the ticket reads as saved until Jira is refreshed.
                return Optional.empty();
            }
        }

        GoalItemView jiraView(JiraIssue issue)
        {
            String category = issue.status().category();
            GoalItemState state = "done".equals(category) ? GoalItemState.FINISHED : "indeterminate".equals(category) ? GoalItemState.WORKING : GoalItemState.IDLE;
            String status = issue.status().name();
            return new GoalItemView(GoalItemKind.JIRA, GoalItem.jiraKey(issue.connectionId(), issue.cloudId(), issue.id()), issue.key(), issue.title(), state, status,
                    status.toLowerCase(Locale.ROOT).contains("blocked"), Optional.ofNullable(issue.url()),
                    Optional.of(new GoalActivity(issue.key() + " is " + status, issue.updatedAt())), Optional.empty());
        }

        private Optional<GoalItemView> task(String key)
        {
            try {
                return Optional.of(taskView(tasks.getInteractive(workspace.id(), TaskId.valueOf(key))));
            }
            catch (AgentTaskNotFoundException | IllegalArgumentException e) {
                return Optional.empty();
            }
        }

        GoalItemView taskView(AgentTask task)
        {
            boolean running = task.developmentContinuationUnfinished()
                    || task.worktreeMutationClaim().filter(claim -> claim.kind() != WorktreeMutationClaim.Kind.RECOVERY).isPresent();
            String status = switch (task.state()) {
                case COMPLETED -> "Completed";
                case FAILED -> "Failed";
                case OPEN -> running ? "Running" : "Idle";
            };
            GoalItemState state = task.state() != TaskState.OPEN ? GoalItemState.FINISHED : running ? GoalItemState.WORKING : GoalItemState.IDLE;
            return new GoalItemView(GoalItemKind.TASK, task.id().toString(), "Task", task.title(), state, status, false, Optional.empty(),
                    Optional.of(new GoalActivity(task.title() + " · " + status, task.updatedAt())), Optional.empty());
        }

        private boolean ownRepository(String repo)
        {
            return repo.equalsIgnoreCase(workspace.monitoredRepository().fullName());
        }

        private boolean issuesSynced()
        {
            if (issuesSynced.isEmpty()) {
                issuesSynced = Optional.of(issues.findPage(workspace.monitoredRepositoryId(), workspace.monitoredRepository(), 0, 1).syncedAt().isPresent());
            }
            return issuesSynced.orElseThrow();
        }
    }
}
