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

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.app.agent.IssueDevelopmentService;
import com.bytequay.app.jira.JiraService;
import com.bytequay.app.workspace.TaskPullRequestOwnership;
import com.bytequay.core.domain.AgendaDay;
import com.bytequay.core.domain.AgendaItem;
import com.bytequay.core.domain.Epic;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.RecentReason;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.store.EpicStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.RecentStore;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkItemStore;
import com.bytequay.core.store.WorkspaceStore;
import com.bytequay.integration.jira.JiraIssue;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

/**
 * Whether each item of a day is done, from facts the app already stores; nothing here reaches GitHub, Jira or a provider. The user's
 * own mark wins, and an item no fact can close stays open until the user marks it.
 */
@Component
class AgendaDone
{
    private final PullRequestStore pullRequests;
    private final AgentTaskStore tasks;
    private final AgentTurnStore turns;
    private final AgentReviewStore reviews;
    private final WorkItemStore workItems;
    private final EpicStore epics;
    private final RecentStore recent;
    private final TaskPullRequestOwnership ownership;
    private final ObjectProvider<JiraService> jira;
    private final WorkspaceStore workspaces;
    private final RepositoryIssueStore issues;
    private final IssueDevelopmentService issueDevelopment;
    private final Clock clock;

    AgendaDone(PullRequestStore pullRequests, AgentTaskStore tasks, AgentTurnStore turns, AgentReviewStore reviews, WorkItemStore workItems, EpicStore epics,
            RecentStore recent, TaskPullRequestOwnership ownership, ObjectProvider<JiraService> jira, WorkspaceStore workspaces, RepositoryIssueStore issues,
            IssueDevelopmentService issueDevelopment, Clock clock)
    {
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.workItems = requireNonNull(workItems, "workItems is null");
        this.epics = requireNonNull(epics, "epics is null");
        this.recent = requireNonNull(recent, "recent is null");
        this.ownership = requireNonNull(ownership, "ownership is null");
        this.jira = requireNonNull(jira, "jira is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.issues = requireNonNull(issues, "issues is null");
        this.issueDevelopment = requireNonNull(issueDevelopment, "issueDevelopment is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /**
     * @param text what closed it, such as "Merged"; the page adds the time
     */
    record Status(State state, boolean byYou, Optional<String> text, Optional<Instant> at)
    {
        enum State
        {
            OPEN, DONE, DISMISSED
        }

        static final Status OPEN = new Status(State.OPEN, false, Optional.empty(), Optional.empty());

        static Status done(String text, Optional<Instant> at)
        {
            return new Status(State.DONE, false, Optional.of(text), at);
        }
    }

    Map<String, Status> statuses(AgendaDay day)
    {
        return day.items().stream().collect(toImmutableMap(AgendaItem::ref, item -> status(day, item), (first, second) -> first));
    }

    Status status(AgendaDay day, AgendaItem item)
    {
        AgendaDay.Mark mark = day.marks().get(item.ref());
        if (mark == AgendaDay.Mark.DISMISSED) {
            return new Status(Status.State.DISMISSED, true, Optional.empty(), Optional.empty());
        }
        if (mark == AgendaDay.Mark.DONE) {
            return new Status(Status.State.DONE, true, Optional.of("Done"), Optional.empty());
        }
        try {
            return automatic(item, day.generatedAt()).orElse(Status.OPEN);
        }
        catch (RuntimeException unreadable) {
            // A row whose facts cannot be read stays open; the user can still mark it.
            return Status.OPEN;
        }
    }

    private Optional<Status> automatic(AgendaItem item, Instant after)
    {
        String id = AgendaRefs.id(item.ref());
        return switch (AgendaRefs.kind(item.ref())) {
            case "pr" -> AgendaRefs.numbered(item.ref()).flatMap(ref -> pullRequest(ref.repo(), ref.number())).flatMap(pr -> pullRequest(item, pr, after));
            case "task" -> tasks.find(TaskId.valueOf(id)).flatMap(task -> task(item, task, after));
            case "review" -> reviews.findReview(id).flatMap(review -> review(review, after));
            case "issue" -> AgendaRefs.numbered(item.ref()).flatMap(this::issue);
            case "work" -> workItems.find(id).flatMap(AgendaDone::work);
            case "epic" -> epics.find(id).flatMap(epic -> epic(item, epic));
            case "jira" -> jira(id);
            default -> Optional.empty();
        };
    }

    private Optional<Status> pullRequest(AgendaItem item, PullRequest pr, Instant after)
    {
        if (pr.mergedAt() != null) {
            return Optional.of(Status.done("Merged", Optional.of(pr.mergedAt())));
        }
        if (pr.closedAt() != null) {
            return Optional.of(Status.done("Closed", Optional.of(pr.closedAt())));
        }
        boolean mine = pr.origin() == PullRequest.Origin.AUTHORED;
        return switch (item.action()) {
            case REVIEW -> after(pr.reviewedAt(), after).map(at -> Status.done("Review submitted", Optional.of(at)));
            case REPLY -> mine
                    ? after(pr.headPushedAt(), after).map(at -> Status.done("Pushed", Optional.of(at)))
                    : after(pr.reviewedAt(), after).map(at -> Status.done("Review submitted", Optional.of(at))).or(() -> commented(pr, after));
            case FIX -> after(pr.headPushedAt(), after).filter(ignored -> pr.ciStatus() == PullRequestDetail.CiStatus.PASSING)
                    .map(ignored -> Status.done("Checks passed", Optional.empty()));
            default -> Optional.empty();
        };
    }

    /** A comment the user wrote on someone else's pull request, as Recent recorded it. */
    private Optional<Status> commented(PullRequest pr, Instant after)
    {
        return recent.findTouches(LocalDate.ofInstant(after, clock.getZone()), LocalDate.now(clock)).stream()
                .filter(touch -> touch.prId() != null && touch.prId() == pr.id() && touch.reason() == RecentReason.COMMENTED && touch.touchedAt().isAfter(after))
                .map(touch -> touch.touchedAt())
                .min(Comparator.naturalOrder())
                .map(at -> Status.done("Replied", Optional.of(at)));
    }

    private Optional<Status> task(AgendaItem item, AgentTask task, Instant after)
    {
        if (task.archivedAt().isPresent() || task.state() != TaskState.OPEN) {
            return Optional.of(Status.done(task.state() == TaskState.COMPLETED ? "Task completed" : "Task ended", task.terminalAt().or(task::archivedAt)));
        }
        Optional<Instant> resumed = turns.list(task.id()).stream().map(AgentTurn::createdAt).filter(at -> at.isAfter(after)).min(Comparator.naturalOrder());
        Optional<PullRequest> pr = ownership.linkedPullRequest(task);
        return switch (item.action()) {
            case ANSWER -> resumed.map(at -> Status.done("Answered", Optional.of(at)));
            case REPLY -> pr.flatMap(linked -> after(linked.headPushedAt(), after)).map(at -> Status.done("Pushed", Optional.of(at)))
                    .or(() -> resumed.map(at -> Status.done("Continued", Optional.of(at))));
            case FIX -> pr.filter(linked -> linked.ciStatus() == PullRequestDetail.CiStatus.PASSING)
                    .flatMap(linked -> after(linked.headPushedAt(), after)).map(ignored -> Status.done("Checks passed", Optional.empty()));
            case MERGE -> pr.map(PullRequest::mergedAt).flatMap(Optional::ofNullable).map(at -> Status.done("Merged", Optional.of(at)));
            case START -> resumed.map(at -> Status.done("Started", Optional.of(at)));
            default -> Optional.empty();
        };
    }

    private Optional<Status> review(AgentReview review, Instant after)
    {
        if (review.state() == AgentReview.State.CLOSED) {
            return Optional.of(Status.done("Review ended", Optional.empty()));
        }
        return turns.list(TaskId.valueOf(review.taskId())).stream()
                .flatMap(turn -> turn.endedAt().stream())
                .filter(at -> at.isAfter(after))
                .max(Comparator.naturalOrder())
                .map(at -> Status.done("Reviewed", Optional.of(at)));
    }

    private static Optional<Status> work(WorkItem item)
    {
        return switch (item.state()) {
            case PLANNED -> Optional.empty();
            case WORKING -> Optional.of(Status.done("Started", Optional.of(item.updatedAt())));
            case DONE -> Optional.of(Status.done("Done", Optional.of(item.updatedAt())));
            case DROPPED -> Optional.of(Status.done("Dropped", Optional.of(item.updatedAt())));
        };
    }

    private static Optional<Status> epic(AgendaItem item, Epic epic)
    {
        if (epic.concludedAt().isPresent()) {
            return Optional.of(Status.done("Concluded", epic.concludedAt()));
        }
        if (item.action() == AgendaItem.Action.DECIDE && epic.proposals().isEmpty() && !epic.offered()) {
            return Optional.of(Status.done("Decided", Optional.empty()));
        }
        return Optional.empty();
    }

    /** An issue whose development task has started, from its row's Start or its own. */
    private Optional<Status> issue(AgendaRefs.Numbered ref)
    {
        return workspaces.findAll().stream().filter(workspace -> workspace.monitoredRepository().fullName().equalsIgnoreCase(ref.repo())).findFirst()
                .flatMap(workspace -> issues.find(workspace.monitoredRepositoryId(), ref.number()))
                .flatMap(issue -> issueDevelopment.metadata(issue.id()).developmentTask())
                .map(task -> Status.done("Started", Optional.empty()));
    }

    private Optional<Status> jira(String id)
    {
        int colon = id.lastIndexOf(':');
        if (colon <= 0) {
            return Optional.empty();
        }
        Optional<JiraIssue> issue = jira.getObject().cachedIssue(id.substring(0, colon), id.substring(colon + 1));
        return issue.flatMap(ticket -> "done".equals(ticket.status().category())
                ? Optional.of(Status.done("Done in Jira", ticket.completedAt()))
                : ticket.task().map(ignored -> Status.done("Started", Optional.empty())));
    }

    private Optional<PullRequest> pullRequest(String repo, int number)
    {
        return pullRequests.findIdByRepoAndNumber(repo, number).flatMap(pullRequests::findById);
    }

    private static Optional<Instant> after(Instant value, Instant after)
    {
        return Optional.ofNullable(value).filter(at -> at.isAfter(after));
    }
}
