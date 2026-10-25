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
package com.bytequay.app.agenda;

import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.app.agenda.AgendaSnapshot.Ask;
import com.bytequay.app.agenda.AgendaSnapshot.Change;
import com.bytequay.app.agenda.AgendaSnapshot.EpicEntry;
import com.bytequay.app.agenda.AgendaSnapshot.Findings;
import com.bytequay.app.agenda.AgendaSnapshot.HistoryDay;
import com.bytequay.app.agenda.AgendaSnapshot.HistoryItem;
import com.bytequay.app.agenda.AgendaSnapshot.Last;
import com.bytequay.app.agenda.AgendaSnapshot.Mine;
import com.bytequay.app.agenda.AgendaSnapshot.Ready;
import com.bytequay.app.agenda.AgendaSnapshot.RefInfo;
import com.bytequay.app.agenda.AgendaSnapshot.Review;
import com.bytequay.app.agenda.AgendaSnapshot.Task;
import com.bytequay.app.agenda.AgendaSnapshot.Week;
import com.bytequay.app.agenda.AgendaSnapshot.Working;
import com.bytequay.app.agent.IssueDevelopmentService;
import com.bytequay.app.jira.JiraService;
import com.bytequay.app.localreview.LocalReviewService;
import com.bytequay.app.notices.AppNotice;
import com.bytequay.app.notices.AppNoticeService;
import com.bytequay.app.service.attention.AttentionMarkService;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.epic.EpicSources;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.recent.PrMovement;
import com.bytequay.app.team.TeamCircle;
import com.bytequay.app.team.TeamService;
import com.bytequay.app.workitem.GoalItemView;
import com.bytequay.app.workitem.WorkItemService;
import com.bytequay.app.workitem.WorkItemView;
import com.bytequay.app.workspace.TaskPullRequestOwnership;
import com.bytequay.core.domain.AgendaCalendar;
import com.bytequay.core.domain.AgendaDay;
import com.bytequay.core.domain.AgendaItem;
import com.bytequay.core.domain.AgendaTarget;
import com.bytequay.core.domain.Epic;
import com.bytequay.core.domain.EpicChild;
import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.IssueEpic;
import com.bytequay.core.domain.PrActivitySummary;
import com.bytequay.core.domain.PrCommitLine;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.WorkItemLink;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.store.EpicStore;
import com.bytequay.core.store.IssueDevelopmentStore;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkItemStore;
import com.bytequay.core.store.WorkspaceStore;
import com.bytequay.integration.jira.JiraActivity;
import com.bytequay.integration.jira.JiraActivityReader;
import com.bytequay.integration.jira.JiraIssue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

/**
 * Gathers one agenda run's pages from what ByteQuay already stores: the pull requests Needs attention ranks, the tasks app notices
 * describe, Epic work, assigned issues and tickets, and earlier agendas. No GitHub, Jira or provider request is made here.
 */
@Component
public class AgendaReader
{
    private static final Logger log = LoggerFactory.getLogger(AgendaReader.class);

    private static final int TITLE = 80;
    private static final int LINE = 160;
    private static final int CHECKS = 3;
    private static final int READY = 3;
    private static final int HISTORY_DAYS = 5;
    private static final int NEWLY_WATCHED = 5;
    private static final int WEEK_REFS = 10;
    private static final Set<String> REPLY_EVENTS = ImmutableSet.of("commented", "reviewed", "review_commented");
    private static final List<String> SEVERITIES = ImmutableList.of("critical", "high", "medium", "low", "nit");
    private static final String GITHUB = "github.com";

    private final PullRequestService pullRequests;
    private final PrDetailStore details;
    private final CredentialService credentials;
    private final WorkspaceStore workspaces;
    private final AppNoticeService notices;
    private final AgentTaskStore tasks;
    private final TaskPullRequestOwnership ownership;
    private final LocalReviewService localReviews;
    private final WorkItemStore workItemStore;
    private final WorkItemService workItems;
    private final EpicStore epics;
    private final IssueDevelopmentStore markers;
    private final RepositoryIssueStore issues;
    private final IssueDevelopmentService issueDevelopment;
    private final ObjectProvider<JiraService> jira;
    private final JiraActivityReader jiraActivity;
    private final TeamService teams;
    private final AttentionMarkService marks;
    private final AgendaDone done;

    AgendaReader(
            PullRequestService pullRequests,
            PrDetailStore details,
            CredentialService credentials,
            WorkspaceStore workspaces,
            AppNoticeService notices,
            AgentTaskStore tasks,
            TaskPullRequestOwnership ownership,
            LocalReviewService localReviews,
            WorkItemStore workItemStore,
            WorkItemService workItems,
            EpicStore epics,
            IssueDevelopmentStore markers,
            RepositoryIssueStore issues,
            IssueDevelopmentService issueDevelopment,
            ObjectProvider<JiraService> jira,
            JiraActivityReader jiraActivity,
            TeamService teams,
            AttentionMarkService marks,
            AgendaDone done)
    {
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.details = requireNonNull(details, "details is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.notices = requireNonNull(notices, "notices is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.ownership = requireNonNull(ownership, "ownership is null");
        this.localReviews = requireNonNull(localReviews, "localReviews is null");
        this.workItemStore = requireNonNull(workItemStore, "workItemStore is null");
        this.workItems = requireNonNull(workItems, "workItems is null");
        this.epics = requireNonNull(epics, "epics is null");
        this.markers = requireNonNull(markers, "markers is null");
        this.issues = requireNonNull(issues, "issues is null");
        this.issueDevelopment = requireNonNull(issueDevelopment, "issueDevelopment is null");
        this.jira = requireNonNull(jira, "jira is null");
        this.jiraActivity = requireNonNull(jiraActivity, "jiraActivity is null");
        this.teams = requireNonNull(teams, "teams is null");
        this.marks = requireNonNull(marks, "marks is null");
        this.done = requireNonNull(done, "done is null");
    }

    /**
     * @param since where "new" starts: the last ready agenda's making
     * @param earlier the agendas of earlier days, newest first
     * @param calendar today's busy time, already read
     */
    public AgendaSnapshot read(LocalDate today, Instant since, boolean recapDay, List<AgendaDay> earlier, AgendaCalendar calendar)
    {
        Map<String, RefInfo> refs = new LinkedHashMap<>();
        Optional<String> viewer = credentials.cachedLogin();
        List<Workspace> watched = workspaces.findAll();
        Map<String, String> workspaceByRepo = new HashMap<>();
        watched.forEach(workspace -> workspaceByRepo.putIfAbsent(lower(workspace.monitoredRepository().fullName()), workspace.id()));
        List<TeamCircle> circles = read("circles", teams::cachedCircles, ImmutableList.of());

        // A snoozed item stays out of the agenda and the assistant's answers until its snooze ends, as it stays out of Needs attention.
        Set<String> snoozed = read("snoozes", marks::snoozedKeys, ImmutableSet.of());
        List<PullRequest> candidates = read("pull requests", () -> pullRequests.listAttentionCandidates().stream()
                .filter(pr -> !snoozed.contains(markKey(pr.repo(), pr.number()))).collect(toImmutableList()), ImmutableList.of());
        Set<Long> mentioned = read("mentions", () -> pullRequests.findMentioned(candidates), ImmutableSet.of());
        Map<Long, List<PrCommitLine>> afterReview = read("commits", () -> pullRequests.findCachedCommitsSinceReview(candidates), ImmutableMap.of());
        Map<Long, List<String>> failing = read("checks", () -> pullRequests.findCachedFailingChecks(candidates), ImmutableMap.of());
        Map<Long, PrActivitySummary> latest = read("activity", () -> pullRequests.findCachedLatestActivities(candidates), ImmutableMap.of());
        List<PullRequest> involved = candidates.stream()
                .filter(pr -> pr.mine(viewer) || requested(pr, viewer) || pr.reviewedAt() != null || pr.monitoredAt() != null || mentioned.contains(pr.id()))
                .collect(toImmutableList());
        for (PullRequest pr : involved) {
            refs.putIfAbsent(AgendaRefs.pull(pr.repo(), pr.number()),
                    new RefInfo(Optional.ofNullable(pr.activityAt()).or(() -> Optional.ofNullable(pr.updatedAt())), workspace(workspaceByRepo, pr.repo()),
                            AgendaTarget.pull(pr.repo(), pr.number(), workspace(workspaceByRepo, pr.repo()))));
        }

        List<Task> taskEntries = read("tasks", () -> tasks(refs), ImmutableList.of());
        Map<String, String> taskByPull = new HashMap<>();
        taskEntries.forEach(task -> task.pr().ifPresent(pr -> taskByPull.putIfAbsent(pr, task.ref())));

        Map<String, List<WorkItemView>> workByWorkspace = new LinkedHashMap<>();
        for (Workspace workspace : watched) {
            workByWorkspace.put(workspace.id(), read("work of " + workspace.id(), () -> workItems.list(workspace.id()), ImmutableList.of()));
        }
        List<JiraIssue> tickets = read("Jira tickets", () -> jira.getObject().attention().stream()
                .filter(ticket -> !snoozed.contains(("jira:" + ticket.cloudId() + ":" + ticket.id()).toLowerCase(Locale.ROOT))).collect(toImmutableList()), ImmutableList.of());

        List<Change> changes = new ArrayList<>();
        changes.addAll(pullRequestChanges(involved, latest, since, viewer));
        changes.addAll(workChanges(workByWorkspace, since, refs));
        changes.addAll(read("Jira changes", () -> ticketChanges(tickets, since, refs), ImmutableList.of()));
        changes.sort(Comparator.comparing(Change::at).reversed());

        List<Mine> mine = mine(involved, viewer, latest, failing, taskByPull);
        List<Ask> asks = new ArrayList<>();
        asks.addAll(pullRequestAsks(involved, viewer, mentioned, afterReview, circles));
        for (Workspace workspace : watched) {
            asks.addAll(read("issues of " + workspace.id(), () -> issueAsks(workspace, viewer, circles, refs, snoozed), ImmutableList.of()));
        }
        asks.addAll(ticketAsks(tickets, refs));
        asks.sort(Comparator.comparing((Ask ask) -> !ask.circle()).thenComparing(ask -> ask.since().orElse(Instant.MAX)));

        List<EpicEntry> epicEntries = new ArrayList<>();
        for (Workspace workspace : watched) {
            epicEntries.addAll(read("Epics of " + workspace.id(), () -> epics(workspace, workByWorkspace.get(workspace.id()), refs), ImmutableList.of()));
        }

        List<AgendaDay> days = earlier.stream().filter(day -> day.day().isBefore(today)).limit(HISTORY_DAYS).collect(toImmutableList());
        List<HistoryDay> history = history(days, refs);
        Optional<Week> week = recapDay ? Optional.of(week(today, involved, viewer, workByWorkspace, history)) : Optional.empty();

        List<String> newlyWatched = watched.stream().filter(workspace -> workspace.createdAt().isAfter(since))
                .map(workspace -> workspace.monitoredRepository().fullName()).limit(NEWLY_WATCHED).collect(toImmutableList());
        AgendaSnapshot.Overview overview = new AgendaSnapshot.Overview(today.toString(), today.getDayOfWeek().name(), recapDay, since,
                new AgendaSnapshot.Counts(changes.size(), mine.size(), taskEntries.size(), asks.size(), epicEntries.size()), newlyWatched,
                Optional.of(AgendaSnapshot.Calendar.of(calendar)));
        return new AgendaSnapshot(overview, changes, mine, taskEntries, asks, epicEntries, history, week, refs);
    }

    private List<Change> pullRequestChanges(List<PullRequest> involved, Map<Long, PrActivitySummary> latest, Instant since, Optional<String> viewer)
    {
        ImmutableList.Builder<Change> changes = ImmutableList.builder();
        for (PullRequest pr : involved) {
            List<String> facts = new ArrayList<>();
            Optional<PrMovement> movement = read("detail of " + pr.id(), () -> details.find(pr.id()), Optional.<StoredPrDetail>empty())
                    .map(detail -> PrMovement.since(detail, since, viewer));
            movement.ifPresent(moved -> {
                if (moved.replies() > 0) {
                    facts.add(moved.replies() + (moved.replies() == 1 ? " new reply" : " new replies"));
                }
                if (moved.commits() > 0) {
                    facts.add(moved.commits() + (moved.commits() == 1 ? " new commit" : " new commits"));
                }
                if (moved.ciRed()) {
                    facts.add("CI went red");
                }
            });
            if (movement.isEmpty() && after(pr.headPushedAt(), since)) {
                facts.add("new commits");
            }
            Optional<PrActivitySummary> activity = Optional.ofNullable(latest.get(pr.id())).filter(value -> value.timestamp().isAfter(since));
            activity.filter(value -> value.event().equals("reviewed")).filter(value -> value.actor().isPresent() && !self(value.actor().orElseThrow(), viewer))
                    .flatMap(value -> verdict(value).map(verdict -> value.actor().orElseThrow() + " " + verdict))
                    .ifPresent(facts::add);
            if (after(pr.mergedAt(), since)) {
                facts.add("merged");
            }
            else if (after(pr.closedAt(), since)) {
                facts.add("closed");
            }
            if (facts.isEmpty()) {
                continue;
            }
            Optional<Last> last = activity.filter(value -> REPLY_EVENTS.contains(value.event()))
                    .filter(value -> value.actor().filter(actor -> !PrMovement.bot(actor) && !self(actor, viewer)).isPresent())
                    .flatMap(value -> value.excerpt().filter(text -> !text.isBlank()).map(text -> new Last(value.actor().orElseThrow(), clip(text, LINE))));
            Instant at = Stream.of(pr.activityAt(), pr.mergedAt(), pr.closedAt(), pr.headPushedAt(), pr.updatedAt())
                    .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(since);
            changes.add(new Change(AgendaRefs.pull(pr.repo(), pr.number()), clip(pr.title(), TITLE), facts, last, at));
        }
        return changes.build();
    }

    private static Optional<String> verdict(PrActivitySummary review)
    {
        String state = review.state().orElse("").toLowerCase(Locale.ROOT);
        return switch (state) {
            case "approved" -> Optional.of("approved");
            case "changes_requested" -> Optional.of("requested changes");
            default -> Optional.empty();
        };
    }

    private static List<Change> workChanges(Map<String, List<WorkItemView>> workByWorkspace, Instant since, Map<String, RefInfo> refs)
    {
        ImmutableList.Builder<Change> changes = ImmutableList.builder();
        workByWorkspace.forEach((workspaceId, items) -> {
            for (WorkItemView item : items) {
                item.history().stream()
                        .filter(event -> event.action().equals("RECEIPT") && event.operationId().startsWith("merged:") && event.at().isAfter(since))
                        .max(Comparator.comparing(event -> event.at()))
                        .ifPresent(event -> {
                            String ref = AgendaRefs.work(item.id());
                            refs.putIfAbsent(ref, new RefInfo(Optional.of(event.at()), Optional.of(workspaceId), AgendaTarget.of(AgendaTarget.Kind.WORK, workspaceId, item.id())));
                            changes.add(new Change(ref, clip(item.title(), TITLE), ImmutableList.of("work item merged"), Optional.empty(), event.at()));
                        });
            }
        });
        return changes.build();
    }

    private List<Change> ticketChanges(List<JiraIssue> tickets, Instant since, Map<String, RefInfo> refs)
    {
        ImmutableList.Builder<Change> changes = ImmutableList.builder();
        for (JiraIssue ticket : tickets) {
            Optional<JiraActivity> activity = jiraActivity.latest(ticket).filter(value -> value.at().isAfter(since));
            if (activity.isEmpty()) {
                continue;
            }
            JiraActivity moved = activity.orElseThrow();
            String fact = switch (moved.kind()) {
                case STATUS_CHANGED -> "Jira status: " + moved.from().map(status -> status.name()).orElse("?") + " → " + moved.to().map(status -> status.name()).orElse("?");
                case COMMENTED -> "new Jira comment";
                case FIELDS_CHANGED -> "Jira fields changed";
            };
            Optional<Last> last = moved.kind() == JiraActivity.Kind.COMMENTED
                    ? moved.note().filter(text -> !text.isBlank()).map(text -> new Last(moved.actor().map(person -> person.displayName()).orElse("someone"), clip(text, LINE)))
                    : Optional.empty();
            String ref = ticketRef(ticket, refs);
            changes.add(new Change(ref, clip(ticket.key() + " " + ticket.title(), TITLE), ImmutableList.of(fact), last, moved.at()));
        }
        return changes.build();
    }

    private static List<Mine> mine(List<PullRequest> involved, Optional<String> viewer, Map<Long, PrActivitySummary> latest, Map<Long, List<String>> failing,
            Map<String, String> taskByPull)
    {
        return involved.stream()
                .filter(pr -> pr.mine(viewer) && open(pr))
                .map(pr -> {
                    String ref = AgendaRefs.pull(pr.repo(), pr.number());
                    List<String> by = Optional.ofNullable(latest.get(pr.id())).filter(activity -> activity.event().equals("reviewed"))
                            .flatMap(PrActivitySummary::actor).map(ImmutableList::of).orElse(ImmutableList.of());
                    Review review = new Review(Optional.ofNullable(pr.reviewDecision()).map(AgendaReader::lower), by, pr.requestedReviewers());
                    return new Mine(ref, clip(pr.title(), TITLE), pr.draft(), review,
                            Optional.ofNullable(pr.ciStatus()).map(status -> lower(status.name())),
                            failing.getOrDefault(pr.id(), ImmutableList.of()).stream().limit(CHECKS).collect(toImmutableList()),
                            Optional.ofNullable(pr.mergeable()), pr.mergeBlocked(), Optional.ofNullable(pr.activityAt()), Optional.ofNullable(taskByPull.get(ref)));
                })
                .sorted(Comparator.comparingInt(AgendaReader::mineOrder))
                .collect(toImmutableList());
    }

    /** Changes requested first, then red, then ready to merge, then waiting for review or for what blocks the merge. */
    private static int mineOrder(Mine pr)
    {
        if (pr.review().decision().filter("changes_requested"::equals).isPresent()) {
            return 0;
        }
        if (pr.ci().filter("failing"::equals).isPresent()) {
            return 1;
        }
        if (pr.review().decision().filter("approved"::equals).isPresent() && !pr.mergeBlocked()) {
            return 2;
        }
        return 3;
    }

    private List<Task> tasks(Map<String, RefInfo> refs)
    {
        ImmutableList.Builder<Task> entries = ImmutableList.builder();
        for (AppNotice notice : notices.current()) {
            boolean review = notice.reviewId().isPresent();
            String ref = review ? AgendaRefs.review(notice.reviewId().orElseThrow()) : AgendaRefs.task(notice.taskId());
            Optional<AgendaRefs.Numbered> linked = review
                    ? notice.pullRequest().flatMap(AgendaReader::numbered)
                    : tasks.find(TaskId.valueOf(notice.taskId())).flatMap(ownership::linkedPullRequest).map(pull -> new AgendaRefs.Numbered(pull.repo(), pull.number()));
            Optional<String> pr = linked.map(value -> AgendaRefs.pull(value.repo(), value.number()));
            refs.putIfAbsent(ref, new RefInfo(Optional.of(notice.occurredAt()), Optional.of(notice.workspaceId()),
                    review ? AgendaTarget.of(AgendaTarget.Kind.REVIEW, notice.workspaceId(), notice.reviewId().orElseThrow())
                            : AgendaTarget.of(AgendaTarget.Kind.TASK, notice.workspaceId(), notice.taskId()),
                    linked.map(value -> value.repo() + " #" + value.number())));
            Optional<String> work = review ? Optional.empty()
                    : workItemStore.findByIdentity(WorkItemLink.taskIdentity(notice.taskId())).map(item -> AgendaRefs.work(item.id()));
            work.ifPresent(workRef -> workItemStore.findByIdentity(WorkItemLink.taskIdentity(notice.taskId()))
                    .ifPresent(item -> refs.putIfAbsent(workRef, new RefInfo(Optional.of(item.updatedAt()), Optional.of(item.workspaceId()),
                            AgendaTarget.of(AgendaTarget.Kind.WORK, item.workspaceId(), item.id())))));
            Optional<Findings> findings = review ? Optional.empty() : findings(notice.taskId());
            String state = switch (notice.kind()) {
                case QUESTION, APPROVAL, RECOVERY, QUEUE_PAUSED, CI_ATTENTION -> "waiting_on_you";
                case FAILURE, TURN_FINISHED, NEXT_WORK -> "idle";
            };
            Optional<String> waitingFor = waitingFor(notice);
            Optional<String> lastTurn = switch (notice.kind()) {
                case FAILURE -> Optional.of("failed");
                case TURN_FINISHED -> Optional.of("completed");
                default -> Optional.empty();
            };
            Optional<String> question = notice.kind() == AppNotice.Kind.QUESTION ? Optional.of(clip(notice.message(), LINE)) : Optional.empty();
            entries.add(new Task(ref, review ? "review" : "development", clip(notice.title(), TITLE), state, waitingFor, question, lastTurn,
                    Optional.of(notice.occurredAt()), pr, work, findings));
        }
        return entries.build().stream().sorted(Comparator.comparing((Task task) -> !task.state().equals("waiting_on_you"))
                .thenComparing(task -> task.lastTurn().filter("failed"::equals).isEmpty())).collect(toImmutableList());
    }

    /** What a task's notice says it waits on the user for; nothing once its turn has settled. */
    public static Optional<String> waitingFor(AppNotice notice)
    {
        return switch (notice.kind()) {
            case QUESTION -> Optional.of(notice.id().startsWith("question:") ? "result" : "question");
            case APPROVAL -> Optional.of("approval");
            case RECOVERY -> Optional.of("recovery");
            case QUEUE_PAUSED -> Optional.of("queue");
            case CI_ATTENTION -> Optional.of("ci");
            case FAILURE, TURN_FINISHED, NEXT_WORK -> Optional.empty();
        };
    }

    private Optional<Findings> findings(String taskId)
    {
        // A finished task keeps its notice, but its agent can no longer read or answer findings.
        List<LocalReviewService.AgentFinding> found = read("findings of " + taskId,
                () -> localReviews.availableForAgent(taskId) ? localReviews.findingsForAgent(taskId) : ImmutableList.of(), ImmutableList.of());
        List<ReviewFinding> open = found.stream().map(LocalReviewService.AgentFinding::finding).filter(finding -> finding.state() == ReviewFinding.State.OPEN)
                .collect(toImmutableList());
        if (open.isEmpty()) {
            return Optional.empty();
        }
        long unanswered = found.stream()
                .filter(finding -> finding.finding().state() == ReviewFinding.State.OPEN)
                .filter(finding -> finding.messages().stream().noneMatch(message -> message.developerTaskId().filter(taskId::equals).isPresent()))
                .count();
        Optional<String> highest = open.stream().flatMap(finding -> finding.severity().stream()).map(AgendaReader::lower)
                .filter(SEVERITIES::contains).min(Comparator.comparingInt(SEVERITIES::indexOf));
        return Optional.of(new Findings(open.size(), (int) unanswered, highest));
    }

    private static List<Ask> pullRequestAsks(List<PullRequest> involved, Optional<String> viewer, Set<Long> mentioned, Map<Long, List<PrCommitLine>> afterReview,
            List<TeamCircle> circles)
    {
        ImmutableList.Builder<Ask> asks = ImmutableList.builder();
        for (PullRequest pr : involved) {
            if (pr.mine(viewer) || !open(pr)) {
                continue;
            }
            Optional<String> why = Optional.empty();
            Optional<Instant> since = Optional.empty();
            Optional<Integer> commits = Optional.empty();
            if (!pr.draft() && requested(pr, viewer) && (pr.reviewedAt() == null || pr.reviewRequestedAt() != null && pr.reviewedAt().isBefore(pr.reviewRequestedAt()))) {
                why = Optional.of("review_requested");
                since = Optional.ofNullable(pr.reviewRequestedAt()).or(() -> Optional.ofNullable(pr.updatedAt()));
            }
            else if (pr.reviewedAt() != null && afterReview.containsKey(pr.id())) {
                why = Optional.of("re_review");
                since = Optional.ofNullable(pr.headPushedAt());
                commits = Optional.of(afterReview.get(pr.id()).size());
            }
            else if (mentioned.contains(pr.id())) {
                why = Optional.of("mentioned");
                since = Optional.ofNullable(pr.activityAt()).or(() -> Optional.ofNullable(pr.updatedAt()));
            }
            if (why.isEmpty()) {
                continue;
            }
            asks.add(new Ask(AgendaRefs.pull(pr.repo(), pr.number()), why.orElseThrow(), clip(pr.title(), TITLE), Optional.ofNullable(pr.author()),
                    circle(circles, pr.repo(), pr.author()), since, Optional.of(pr.additions() + pr.deletions()),
                    Optional.ofNullable(pr.ciStatus()).map(status -> lower(status.name())), commits, Optional.empty()));
        }
        return asks.build();
    }

    private List<Ask> issueAsks(Workspace workspace, Optional<String> viewer, List<TeamCircle> circles, Map<String, RefInfo> refs, Set<String> snoozed)
    {
        if (viewer.isEmpty()) {
            return ImmutableList.of();
        }
        String repo = workspace.monitoredRepository().fullName();
        ImmutableList.Builder<Ask> asks = ImmutableList.builder();
        for (RepositoryIssue issue : issueDevelopment.attention(workspace.id()).issues()) {
            if (issue.assignees().stream().noneMatch(viewer.orElseThrow()::equalsIgnoreCase) || snoozed.contains(markKey(repo, issue.number()))) {
                continue;
            }
            String ref = AgendaRefs.issue(repo, issue.number());
            refs.putIfAbsent(ref, new RefInfo(Optional.of(issue.updatedAt()), Optional.of(workspace.id()), AgendaTarget.issue(repo, issue.number(), workspace.id())));
            asks.add(new Ask(ref, "assigned_issue", clip(issue.title(), TITLE), Optional.of(issue.author()), circle(circles, repo, issue.author()),
                    Optional.of(issue.updatedAt()), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
        }
        return asks.build();
    }

    private static List<Ask> ticketAsks(List<JiraIssue> tickets, Map<String, RefInfo> refs)
    {
        return tickets.stream()
                .filter(ticket -> !"done".equals(ticket.status().category()))
                .map(ticket -> new Ask(ticketRef(ticket, refs), "assigned_jira", clip(ticket.key() + " " + ticket.title(), TITLE), Optional.empty(), false,
                        Optional.of(ticket.updatedAt()), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(ticket.status().name())))
                .collect(toImmutableList());
    }

    private static String ticketRef(JiraIssue ticket, Map<String, RefInfo> refs)
    {
        String ref = AgendaRefs.jira(ticket.cloudId(), ticket.id());
        refs.putIfAbsent(ref, new RefInfo(Optional.of(ticket.updatedAt()), ticket.workspace().map(workspace -> workspace.id()),
                AgendaTarget.jira(ticket.connectionId(), ticket.cloudId(), ticket.id(), ticket.workspace().map(workspace -> workspace.id())), Optional.of(ticket.key())));
        return ref;
    }

    private List<EpicEntry> epics(Workspace workspace, List<WorkItemView> work, Map<String, RefInfo> refs)
    {
        Map<String, List<WorkItemView>> byEpic = new LinkedHashMap<>();
        for (WorkItemView item : work) {
            item.epicId().ifPresent(epicId -> byEpic.computeIfAbsent(epicId, ignored -> new ArrayList<>()).add(item));
        }
        ImmutableList.Builder<EpicEntry> entries = ImmutableList.builder();
        byEpic.forEach((epicId, items) -> {
            Optional<Epic> epic = epics.find(epicId);
            if (epic.flatMap(Epic::concludedAt).isPresent()) {
                return;
            }
            Optional<EpicSource> source = epicSource(workspace, epicId);
            if (source.isEmpty()) {
                return;
            }
            int proposals = epic.map(value -> value.proposals().size()).orElse(0);
            int newChildren = epic.map(AgendaReader::newChildren).orElse(0);
            if (items.stream().noneMatch(ReadyWork::open) && proposals == 0 && newChildren == 0) {
                return;
            }
            String ref = AgendaRefs.epic(epicId);
            int doneCount = (int) items.stream().filter(item -> item.state() == WorkItem.State.DONE).count();
            int total = (int) items.stream().filter(item -> item.state() != WorkItem.State.DROPPED).count();
            String title = clip(source.orElseThrow().title(), TITLE);
            Optional<Instant> touched = items.stream().map(WorkItemView::updatedAt).max(Comparator.naturalOrder());
            refs.putIfAbsent(ref, new RefInfo(touched, Optional.of(workspace.id()), source.orElseThrow().target(), Optional.of("Epic · " + doneCount + "/" + total)));
            for (WorkItemView item : items) {
                // Work a task or receipt named first is still best named by its Epic.
                refs.put(AgendaRefs.work(item.id()), new RefInfo(Optional.of(item.updatedAt()), Optional.of(workspace.id()),
                        AgendaTarget.of(AgendaTarget.Kind.WORK, workspace.id(), item.id()), Optional.of(title + " · " + doneCount + "/" + total)));
            }
            List<Working> working = items.stream().filter(item -> item.state() == WorkItem.State.WORKING)
                    .map(item -> new Working(AgendaRefs.work(item.id()), clip(item.title(), TITLE), pullLink(item), ReadyWork.unblocks(item, items)))
                    .collect(toImmutableList());
            List<Ready> ready = ReadyWork.ready(items).stream().limit(READY)
                    .map(item -> new Ready(AgendaRefs.work(item.id()), clip(item.title(), TITLE), item.priority().name(), ReadyWork.unblocks(item, items)))
                    .collect(toImmutableList());
            int blocked = (int) items.stream().filter(item -> ReadyWork.open(item) && ReadyWork.waiting(item, items)).count();
            entries.add(new EpicEntry(ref, title, doneCount, total, blocked, proposals, newChildren, working, ready));
        });
        return entries.build();
    }

    /** What an Epic is called and where it opens: the work item, ticket or issue marked as the Epic. */
    public record EpicSource(String title, AgendaTarget target) {}

    /** An Epic of the workspace by its source, from the stores only; empty when the source is gone. */
    public Optional<EpicSource> epicSource(Workspace workspace, String epicId)
    {
        Optional<String> local = EpicSources.localId(epicId);
        if (local.isPresent()) {
            return workItemStore.find(local.orElseThrow()).filter(item -> item.workspaceId().equals(workspace.id()))
                    .map(item -> new EpicSource(item.title(), AgendaTarget.of(AgendaTarget.Kind.WORK, workspace.id(), item.id())));
        }
        Optional<EpicSources.Ticket> ticket = EpicSources.ticket(epicId);
        if (ticket.isPresent()) {
            return jira.getObject().cachedIssue(ticket.orElseThrow().cloudId(), ticket.orElseThrow().issueId())
                    .map(issue -> new EpicSource(issue.key() + " " + issue.title(), AgendaTarget.jira(issue.connectionId(), issue.cloudId(), issue.id(),
                            Optional.of(workspace.id()))));
        }
        Optional<IssueEpic> marker = markers.findEpic(GITHUB, EpicSources.issueId(epicId));
        return marker.map(found -> new EpicSource(issues.find(workspace.monitoredRepositoryId(), found.number()).map(RepositoryIssue::title).orElse("#" + found.number()),
                AgendaTarget.issue(found.repo(), found.number(), workspace.id())));
    }

    private static int newChildren(Epic epic)
    {
        Set<String> synced = epic.synced().stream().map(EpicChild::identity).collect(toImmutableSet());
        return (int) epic.children().stream().filter(child -> !synced.contains(child.identity()) && !epic.dismissed().contains(child.identity())).count();
    }

    private static Optional<String> pullLink(WorkItemView item)
    {
        return item.links().stream().filter(link -> link.kind() == GoalItemKind.PULL_REQUEST).map(GoalItemView::key).findFirst().map(key -> "pr:" + key);
    }

    private List<HistoryDay> history(List<AgendaDay> days, Map<String, RefInfo> refs)
    {
        ImmutableList.Builder<HistoryDay> history = ImmutableList.builder();
        for (AgendaDay day : days) {
            Map<String, AgendaDone.Status> statuses = done.statuses(day);
            ImmutableList.Builder<HistoryItem> items = ImmutableList.builder();
            for (AgendaItem item : day.items()) {
                refs.putIfAbsent(item.ref(), new RefInfo(item.at(), item.workspaceId(), item.target(), item.where()));
                AgendaDone.Status status = statuses.getOrDefault(item.ref(), AgendaDone.Status.OPEN);
                items.add(new HistoryItem(item.ref(), lower(item.action().name()), lower(status.state().name())));
            }
            history.add(new HistoryDay(day.day().toString(), items.build()));
        }
        return history.build();
    }

    private Week week(LocalDate today, List<PullRequest> involved, Optional<String> viewer, Map<String, List<WorkItemView>> workByWorkspace, List<HistoryDay> history)
    {
        LocalDate monday = today.with(DayOfWeek.MONDAY);
        Instant weekStart = monday.atStartOfDay(ZoneId.systemDefault()).toInstant();
        List<String> merged = involved.stream().filter(pr -> pr.mine(viewer) && after(pr.mergedAt(), weekStart))
                .map(pr -> AgendaRefs.pull(pr.repo(), pr.number())).limit(WEEK_REFS).collect(toImmutableList());
        List<String> finished = workByWorkspace.values().stream().flatMap(List::stream)
                .filter(item -> item.state() == WorkItem.State.DONE && item.updatedAt().isAfter(weekStart))
                .map(item -> AgendaRefs.work(item.id())).limit(WEEK_REFS).collect(toImmutableList());
        List<String> stillOpen = history.stream().filter(day -> !LocalDate.parse(day.day()).isBefore(monday))
                .flatMap(day -> day.items().stream()).filter(item -> item.outcome().equals("open"))
                .map(HistoryItem::ref).distinct().limit(WEEK_REFS).collect(toImmutableList());
        return new Week(merged, finished, stillOpen);
    }

    private static boolean circle(List<TeamCircle> circles, String repo, String login)
    {
        return login != null && circles.stream()
                .filter(circle -> circle.repositories().isEmpty() || circle.repositories().contains(lower(repo)))
                .anyMatch(circle -> circle.members().stream().anyMatch(member -> member.login().equalsIgnoreCase(login)));
    }

    private static Optional<AgendaRefs.Numbered> numbered(String key)
    {
        return AgendaRefs.numbered("pr:" + key);
    }

    private static Optional<String> workspace(Map<String, String> workspaceByRepo, String repo)
    {
        return Optional.ofNullable(workspaceByRepo.get(lower(repo)));
    }

    private static boolean open(PullRequest pr)
    {
        return pr.mergedAt() == null && pr.closedAt() == null && (pr.state() == null || pr.state().equalsIgnoreCase("open"));
    }

    private static boolean requested(PullRequest pr, Optional<String> viewer)
    {
        return viewer.filter(login -> pr.requestedReviewers().stream().anyMatch(login::equalsIgnoreCase)).isPresent();
    }

    private static boolean self(String login, Optional<String> viewer)
    {
        return viewer.filter(login::equalsIgnoreCase).isPresent();
    }

    private static boolean after(Instant value, Instant since)
    {
        return value != null && value.isAfter(since);
    }

    private static String lower(String value)
    {
        return value.toLowerCase(Locale.ROOT);
    }

    public static String clip(String text, int length)
    {
        String flat = text.strip().replaceAll("\\s+", " ");
        return flat.length() <= length ? flat : flat.substring(0, length - 1).stripTrailing() + "…";
    }

    /** One source's facts, or nothing when they cannot be read: a missing source narrows the agenda instead of failing it. */
    /** A pull request's or an issue's key in Needs attention marks, lowercased as snoozedKeys gives them. */
    private static String markKey(String repo, int number)
    {
        return (repo + "#" + number).toLowerCase(Locale.ROOT);
    }

    private static <T> T read(String what, Supplier<T> source, T fallback)
    {
        try {
            return source.get();
        }
        catch (RuntimeException failure) {
            log.warn("Agenda could not read {}: {}", what, failure.getMessage());
            return fallback;
        }
    }
}
