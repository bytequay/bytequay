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
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.app.agenda.AgendaReader;
import com.bytequay.app.agenda.AgendaRefs;
import com.bytequay.app.agent.RunningTask;
import com.bytequay.app.agent.RunningTaskReader;
import com.bytequay.app.agent.TaskActivityReader;
import com.bytequay.app.agent.TaskMessage;
import com.bytequay.app.assistant.AssistantRow.Kind;
import com.bytequay.app.assistant.AssistantRow.Latest;
import com.bytequay.app.jira.JiraService;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.epic.EpicSources;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.core.domain.AgendaTarget;
import com.bytequay.core.domain.Epic;
import com.bytequay.core.domain.PrActivitySummary;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.RepositoryIssuePage;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.store.EpicStore;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkItemStore;
import com.bytequay.core.store.WorkspaceStore;
import com.bytequay.integration.jira.JiraActivity;
import com.bytequay.integration.jira.JiraActivityReader;
import com.bytequay.integration.jira.JiraIssue;
import com.google.common.base.Splitter;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

/**
 * Reads the items the assistant names from what ByteQuay stores: the rows of the lists it showed, and what a search finds among pull
 * requests, repository issues, cached Jira tickets, tasks, work items and Epics. No GitHub, Jira or provider request is made here.
 */
@Component
class AssistantRows
{
    private static final Logger log = LoggerFactory.getLogger(AssistantRows.class);
    // About a hundred words, as a pull request's excerpt; the panel folds a quote's Markdown to one line and cuts that.
    private static final int QUOTE = 800;
    // ponytail: a search reads each source and filters here; a query in the store once one of them outgrows these bounds.
    private static final int ISSUE_PAGE = 200;
    private static final int ISSUE_PAGES = 5;
    private static final int TASKS = 500;
    private static final Pattern NUMBER = Pattern.compile("#?(\\d{1,9})");
    /** What a task does while its turn or its automation is still at work; a settled turn leaves the task idle. */
    private static final Set<RunningTask.Status> WORKING = Sets.immutableEnumSet(RunningTask.Status.RUNNING, RunningTask.Status.WAITING, RunningTask.Status.BLOCKED);
    /** A pull request's timeline events in the words Home's Needs attention uses for them (EVENT_VERBS in its attention model). */
    private static final Map<String, String> EVENT_WORDS = ImmutableMap.<String, String>builder()
            .put("commented", "commented")
            .put("review_thread", "left a review comment")
            .put("committed", "pushed a commit")
            .put("review_request_removed", "removed a review request")
            .put("labeled", "added a label")
            .put("unlabeled", "removed a label")
            .put("renamed", "changed the title")
            .put("cross-referenced", "mentioned this")
            .put("head_ref_force_pushed", "force-pushed")
            .put("closed", "closed this")
            .put("merged", "merged this")
            .put("reopened", "reopened this")
            .put("convert_to_draft", "converted this to a draft")
            .put("ready_for_review", "marked this ready for review")
            .put("milestoned", "set the milestone")
            .put("demilestoned", "removed the milestone")
            .put("assigned", "changed the assignees")
            .put("unassigned", "changed the assignees")
            .put("added_to_merge_queue", "queued this for merge")
            .put("removed_from_merge_queue", "removed this from the merge queue")
            .buildOrThrow();

    private final PullRequestStore pullRequests;
    private final PrDetailStore details;
    private final RepositoryIssueStore issues;
    private final JiraService jira;
    private final JiraActivityReader jiraActivity;
    private final AgentTaskStore tasks;
    private final AgentReviewStore reviews;
    private final RunningTaskReader running;
    private final TaskActivityReader activities;
    private final WorkItemStore workItems;
    private final EpicStore epics;
    private final WorkspaceStore workspaces;
    private final AgendaReader agenda;
    private final ModelSettingsService models;
    private final CredentialService credentials;

    AssistantRows(
            PullRequestStore pullRequests,
            PrDetailStore details,
            RepositoryIssueStore issues,
            JiraService jira,
            JiraActivityReader jiraActivity,
            AgentTaskStore tasks,
            AgentReviewStore reviews,
            RunningTaskReader running,
            TaskActivityReader activities,
            WorkItemStore workItems,
            EpicStore epics,
            WorkspaceStore workspaces,
            AgendaReader agenda,
            ModelSettingsService models,
            CredentialService credentials)
    {
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.details = requireNonNull(details, "details is null");
        this.issues = requireNonNull(issues, "issues is null");
        this.jira = requireNonNull(jira, "jira is null");
        this.jiraActivity = requireNonNull(jiraActivity, "jiraActivity is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.running = requireNonNull(running, "running is null");
        this.activities = requireNonNull(activities, "activities is null");
        this.workItems = requireNonNull(workItems, "workItems is null");
        this.epics = requireNonNull(epics, "epics is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.agenda = requireNonNull(agenda, "agenda is null");
        this.models = requireNonNull(models, "models is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
    }

    /**
     * Each ref that still resolves, as the panel draws it. A ref missing from the answer is gone; one whose store cannot be read now is
     * left out the same way, and the next read tries again.
     */
    Map<String, AssistantRow> rows(Collection<String> refs)
    {
        Shared shared = new Shared();
        Map<String, AssistantRow> rows = new LinkedHashMap<>();
        for (String ref : ImmutableSet.copyOf(refs)) {
            try {
                row(ref, shared).ifPresent(row -> rows.put(ref, row));
            }
            catch (RuntimeException failure) {
                log.warn("The assistant could not read {}: {}", ref, failure.getMessage());
            }
        }
        return ImmutableMap.copyOf(rows);
    }

    /** What several rows need, read once for all of them and only when one does. */
    private final class Shared
    {
        final Supplier<List<Workspace>> watched = Suppliers.memoize(workspaces::findAll);
        final Supplier<Map<String, RunningTask>> running = Suppliers.memoize(() -> AssistantRows.this.running.current().stream()
                .collect(toImmutableMap(RunningTask::id, task -> task, (first, second) -> first)));
        final Supplier<Map<String, String>> agents = Suppliers.memoize(() -> models.get().providers().stream()
                .collect(toImmutableMap(ModelSettingsService.Provider::id, ModelSettingsService.Provider::label, (first, second) -> first)));
    }

    private Optional<AssistantRow> row(String ref, Shared shared)
    {
        return switch (AgendaRefs.kind(ref)) {
            case "pr" -> AgendaRefs.numbered(ref).flatMap(number -> pullRequests.findIdByRepoAndNumber(number.repo(), number.number())).flatMap(pullRequests::findById)
                    .map(pr -> pullRequest(pr, shared));
            case "issue" -> AgendaRefs.numbered(ref).flatMap(number -> workspace(shared.watched.get(), number.repo())
                    .flatMap(workspace -> issues.find(workspace.monitoredRepositoryId(), number.number()).map(issue -> issue(workspace, issue))));
            case "jira" -> Optional.of(Splitter.on(':').limit(2).splitToList(AgendaRefs.id(ref))).filter(parts -> parts.size() == 2)
                    .flatMap(parts -> jira.cachedIssue(parts.get(0), parts.get(1))).map(this::ticket);
            case "task" -> taskId(AgendaRefs.id(ref)).flatMap(tasks::find).flatMap(task -> task(task, shared));
            case "review" -> reviews.findReview(AgendaRefs.id(ref)).flatMap(review -> taskId(review.taskId()).flatMap(tasks::find).map(task -> review(review, task, shared)));
            case "work" -> workItems.find(AgendaRefs.id(ref)).map(this::work);
            case "epic" -> epic(AgendaRefs.id(ref), shared);
            default -> Optional.empty();
        };
    }

    private AssistantRow pullRequest(PullRequest pr, Shared shared)
    {
        Optional<String> viewer = credentials.cachedLogin();
        Optional<StoredPrDetail> detail = details.find(pr.id());
        // Each reviewer's latest verdict, as the pull request's page counts approvals; none until its reviews have been read once.
        List<PrReviewState> approving = detail.map(stored -> PrReviewState.latestVerdicts(stored.reviews()).stream().filter(review -> review.state().equals("APPROVED"))
                .collect(toImmutableList())).orElse(ImmutableList.of());
        Optional<Integer> approvals = detail.map(stored -> approving.size());
        boolean youApproved = viewer.filter(login -> approving.stream().anyMatch(review -> login.equalsIgnoreCase(review.login()))).isPresent();
        Optional<Latest> latest = Optional.ofNullable(details.findLatestActivities(ImmutableSet.of(pr.id())).get(pr.id())).map(activity -> latest(activity, viewer));
        return new AssistantRow(AgendaRefs.pull(pr.repo(), pr.number()), Kind.PULL, pr.title(), Optional.of("#" + pr.number()), Optional.of(state(pr)),
                Optional.ofNullable(pr.ciStatus()).filter(ci -> ci != PullRequestDetail.CiStatus.NONE).map(ci -> lower(ci.name())),
                Optional.ofNullable(pr.reviewDecision()).map(AssistantRows::lower), approvals, pr.mine(viewer), pr.mergeBlocked(), youApproved,
                detail.map(stored -> stored.raw().headSha()), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), at(pr), latest, AgendaTarget.pull(pr.repo(), pr.number(), workspace(shared.watched.get(), pr.repo()).map(Workspace::id)));
    }

    private static Latest latest(PrActivitySummary activity, Optional<String> viewer)
    {
        String did = eventWords(activity, viewer);
        String text = activity.excerpt().filter(excerpt -> !excerpt.isBlank()).map(excerpt -> did + ": " + excerpt).orElse(did);
        // The user's own events read as theirs, the way a task's own messages do.
        Optional<String> by = activity.actor().map(actor -> viewer.filter(actor::equalsIgnoreCase).isPresent() ? "You" : actor);
        return new Latest(by, quote(text), activity.timestamp());
    }

    /** What a card quotes, its lines kept so the panel can fold its Markdown and HTML into one plain line. */
    private static String quote(String text)
    {
        String kept = text.strip();
        return kept.length() <= QUOTE ? kept : kept.substring(0, QUOTE - 1).stripTrailing() + "…";
    }

    /** What a pull request's latest event did, in the words Home's Needs attention uses; a review's verdict says more than that someone reviewed. */
    private static String eventWords(PrActivitySummary activity, Optional<String> viewer)
    {
        return switch (activity.event()) {
            case "reviewed" -> switch (activity.state().map(AssistantRows::lower).orElse("")) {
                case "approved" -> "approved these changes";
                case "changes_requested" -> "requested changes";
                default -> "reviewed";
            };
            case "review_requested" -> activity.requestedReviewer()
                    .map(reviewer -> viewer.filter(reviewer::equalsIgnoreCase).isPresent() ? "requested your review" : "requested review from " + reviewer)
                    .orElse("requested a review");
            default -> EVENT_WORDS.getOrDefault(activity.event(), "updated this pull request");
        };
    }

    private static AssistantRow issue(Workspace workspace, RepositoryIssue issue)
    {
        String repo = workspace.monitoredRepository().fullName();
        // The store keeps a repository's open issues only.
        return new AssistantRow(AgendaRefs.issue(repo, issue.number()), Kind.ISSUE, issue.title(), Optional.of("#" + issue.number()), Optional.of("open"), Optional.empty(),
                Optional.empty(), Optional.empty(), false, false, false, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(issue.updatedAt()), Optional.empty(), AgendaTarget.issue(repo, issue.number(), workspace.id()));
    }

    private AssistantRow ticket(JiraIssue ticket)
    {
        Optional<Latest> latest = jiraActivity.latest(ticket)
                .map(activity -> new Latest(activity.actor().map(person -> person.displayName()), quote(said(activity)), activity.at()));
        return new AssistantRow(AgendaRefs.jira(ticket.cloudId(), ticket.id()), Kind.JIRA, ticket.title(), Optional.of(ticket.key()), Optional.of(ticket.status().name()),
                Optional.empty(), Optional.empty(), Optional.empty(), false, false, false, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(ticket.updatedAt()), latest, AgendaTarget.jira(ticket.connectionId(), ticket.cloudId(), ticket.id(), ticket.workspace().map(link -> link.id())));
    }

    /** What a ticket's latest change did. */
    private static String said(JiraActivity activity)
    {
        return switch (activity.kind()) {
            case STATUS_CHANGED -> "moved to " + activity.to().map(status -> status.name()).orElse("another status");
            case COMMENTED -> activity.note().filter(note -> !note.isBlank()).map(note -> "commented: " + note).orElse("commented");
            case FIELDS_CHANGED -> "changed " + (activity.fields().isEmpty() ? "fields" : String.join(", ", activity.fields()));
        };
    }

    private Optional<AssistantRow> task(AgentTask task, Shared shared)
    {
        return task.workspaceId().map(workspace -> {
            Phase phase = phase(task, shared);
            return new AssistantRow(AgendaRefs.task(task.id().toString()), Kind.TASK, task.title(), Optional.empty(), Optional.of(phase.name()), Optional.empty(),
                    Optional.empty(), Optional.empty(), false, false, false, Optional.empty(), Optional.of(phase.agent()), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.of(phase.since()), phase.latest(), AgendaTarget.of(AgendaTarget.Kind.TASK, workspace, task.id().toString()));
        });
    }

    private AssistantRow review(AgentReview review, AgentTask task, Shared shared)
    {
        Phase phase = phase(task, shared);
        return new AssistantRow(AgendaRefs.review(review.id()), Kind.REVIEW, task.title(), review.number().stream().mapToObj(number -> "#" + number).findFirst(),
                Optional.of(phase.name()), Optional.empty(), Optional.empty(), Optional.empty(), false, false, false, Optional.empty(), Optional.of(phase.agent()),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(phase.since()), phase.latest(),
                AgendaTarget.of(AgendaTarget.Kind.REVIEW, review.workspaceId(), review.id()));
    }

    /**
     * Where a task's work stands, with what was said last: running, waiting or blocked while it works, idle while it waits for the user's
     * next message, and completed or failed once the task itself ended. Only an idle task can be completed.
     */
    private record Phase(String name, Instant since, String agent, Optional<Latest> latest) {}

    private Phase phase(AgentTask task, Shared shared)
    {
        Optional<RunningTask> now = Optional.ofNullable(shared.running.get().get(task.id().toString())).filter(value -> WORKING.contains(value.status()));
        // The sidebar keeps a turn that just completed or failed for a while; the task itself is open and idle until the user ends it.
        String name = task.state() == TaskState.OPEN ? now.map(value -> lower(value.status().name())).orElse("idle") : lower(task.state().name());
        String agent = shared.agents.get().getOrDefault(task.laneId().value(), task.laneId().value());
        Optional<Latest> latest = Optional.ofNullable(activities.read(ImmutableList.of(task)).get(task.id())).flatMap(activity -> activity.lastMessage())
                .map(message -> new Latest(Optional.of(message.author() == TaskMessage.Author.AGENT ? agent : "You"), quote(message.text()), message.at()));
        return new Phase(name, now.map(RunningTask::startedAt).orElse(task.updatedAt()), agent, latest);
    }

    private AssistantRow work(WorkItem item)
    {
        Optional<String> epic = item.epicId().flatMap(epicId -> workspaces.findById(item.workspaceId()).flatMap(workspace -> agenda.epicSource(workspace, epicId)))
                .map(AgendaReader.EpicSource::title);
        return new AssistantRow(AgendaRefs.work(item.id()), Kind.WORK, item.title(), Optional.empty(), Optional.of(lower(item.state().name())), Optional.empty(),
                Optional.empty(), Optional.empty(), false, false, false, Optional.empty(), Optional.empty(), epic, Optional.empty(), Optional.empty(),
                Optional.of(item.updatedAt()), Optional.empty(), AgendaTarget.of(AgendaTarget.Kind.WORK, item.workspaceId(), item.id()));
    }

    /**
     * The key Needs attention keeps an item's mark under: a pull request or an issue by its repository as the list spells it, which a
     * ref's lowercase may not match, and a ticket or a task by its ref. Empty for anything else, or a pull request ByteQuay does not have.
     */
    Optional<String> attentionKey(String ref)
    {
        return switch (AgendaRefs.kind(ref)) {
            case "pr" -> AgendaRefs.numbered(ref).flatMap(number -> pullRequests.findIdByRepoAndNumber(number.repo(), number.number())).flatMap(pullRequests::findById)
                    .map(pr -> pr.repo() + "#" + pr.number());
            case "issue" -> AgendaRefs.numbered(ref).flatMap(number -> workspace(workspaces.findAll(), number.repo())
                    .map(workspace -> workspace.monitoredRepository().fullName() + "#" + number.number()));
            case "jira", "task" -> Optional.of(ref);
            default -> Optional.empty();
        };
    }

    /** The workspace an Epic belongs to: the one its record, its local work item, or any work in it names. */
    Optional<Workspace> epicWorkspace(String epicId)
    {
        return epicWorkspace(epicId, workspaces::findAll);
    }

    private Optional<Workspace> epicWorkspace(String epicId, Supplier<List<Workspace>> watched)
    {
        return epics.find(epicId).map(Epic::workspaceId).or(() -> EpicSources.localId(epicId).flatMap(workItems::find).map(WorkItem::workspaceId))
                .flatMap(workspaces::findById)
                .or(() -> watched.get().stream().filter(workspace -> workItems.list(workspace.id()).stream().anyMatch(item -> inEpic(item, epicId))).findFirst());
    }

    private Optional<AssistantRow> epic(String epicId, Shared shared)
    {
        return epicWorkspace(epicId, shared.watched).flatMap(found -> agenda.epicSource(found, epicId).map(source -> {
            List<WorkItem> work = workItems.list(found.id()).stream().filter(item -> inEpic(item, epicId)).collect(toImmutableList());
            int done = (int) work.stream().filter(item -> item.state() == WorkItem.State.DONE).count();
            int total = (int) work.stream().filter(item -> item.state() != WorkItem.State.DROPPED).count();
            Optional<String> state = epics.find(epicId).flatMap(Epic::concludedAt).map(ignored -> "concluded");
            return new AssistantRow(AgendaRefs.epic(epicId), Kind.EPIC, source.title(), Optional.empty(), state, Optional.empty(), Optional.empty(), Optional.empty(),
                    false, false, false, Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(done), Optional.of(total),
                    work.stream().map(WorkItem::updatedAt).max(Comparator.naturalOrder()), Optional.empty(), source.target());
        }));
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
        number.flatMap(value -> issues.find(workspace.monitoredRepositoryId(), value)).ifPresent(issue -> add(found, found(workspace, repo, issue, true)));
        for (int page = 0; page < ISSUE_PAGES; page++) {
            RepositoryIssuePage read = issues.findPage(workspace.monitoredRepositoryId(), workspace.monitoredRepository(), page, ISSUE_PAGE);
            read.issues().stream().filter(issue -> matches(issue.title(), words)).forEach(issue -> add(found, found(workspace, repo, issue, false)));
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
                Optional<Instant> touched = items.stream().filter(item -> inEpic(item, epicId)).map(WorkItem::updatedAt).max(Comparator.naturalOrder());
                Optional<String> state = epics.find(epicId).flatMap(Epic::concludedAt).map(ignored -> "concluded");
                add(found, new Found(AgendaRefs.epic(epicId), source.title(), state, touched, source.target(), false));
            });
        }
    }

    private static Found found(Workspace workspace, String repo, RepositoryIssue issue, boolean exact)
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

    private static boolean inEpic(WorkItem item, String epicId)
    {
        return item.epicId().filter(epicId::equals).isPresent();
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

    static Optional<TaskId> taskId(String id)
    {
        try {
            return Optional.of(TaskId.valueOf(id));
        }
        catch (IllegalArgumentException notATask) {
            return Optional.empty();
        }
    }

    private static String lower(String value)
    {
        return value.toLowerCase(Locale.ROOT);
    }
}
