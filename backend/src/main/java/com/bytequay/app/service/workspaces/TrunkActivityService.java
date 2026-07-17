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
package com.bytequay.app.service.workspaces;

import com.bytequay.app.beans.workspace.DistillRunDto;
import com.bytequay.app.beans.workspace.TrunkActivityDto;
import com.bytequay.app.domain.AgentQuestion;
import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.BacklogItem;
import com.bytequay.app.domain.Notification;
import com.bytequay.app.domain.NotificationKind;
import com.bytequay.app.domain.NotificationStatus;
import com.bytequay.app.domain.ReviewRound;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.repository.ReviewRoundStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.backlog.BacklogService;
import com.bytequay.app.service.question.AgentQuestionService;
import com.bytequay.app.service.runs.AgentRunService;
import com.bytequay.app.service.threads.NotificationService;
import com.bytequay.app.service.threads.ThreadService;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Folds the trunk's separate task, backlog, notification, session, review,
 * and memory feeds into the one chronological rail exposed by the workspace
 * UI. Open questions and publish gates are returned separately so the
 * renderer can keep them pinned above history.
 */
@Service
public class TrunkActivityService
{
    private final ThreadService threads;
    private final AgentQuestionService questions;
    private final NotificationService notifications;
    private final AgentRunService runs;
    private final TaskStore tasks;
    private final BacklogService backlog;
    private final ReviewRoundStore reviewRounds;
    private final WorkspaceKnowledgeService knowledge;

    public TrunkActivityService(
            ThreadService threads,
            AgentQuestionService questions,
            NotificationService notifications,
            AgentRunService runs,
            TaskStore tasks,
            BacklogService backlog,
            ReviewRoundStore reviewRounds,
            WorkspaceKnowledgeService knowledge)
    {
        this.threads = requireNonNull(threads, "threads is null");
        this.questions = requireNonNull(questions, "questions is null");
        this.notifications = requireNonNull(notifications, "notifications is null");
        this.runs = requireNonNull(runs, "runs is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.backlog = requireNonNull(backlog, "backlog is null");
        this.reviewRounds = requireNonNull(reviewRounds, "reviewRounds is null");
        this.knowledge = requireNonNull(knowledge, "knowledge is null");
    }

    public TrunkActivityDto get(String trunkId)
    {
        Thread trunk = threads.find(trunkId)
                .orElseThrow(() -> status(404, "no trunk: " + trunkId));
        String workspaceId = trunk.workspaceId();
        String trunkPath = "#/workspace/" + workspaceId + "/trunks/" + trunkId;
        List<TrunkActivityDto.Item> pinned = new ArrayList<>();
        List<TrunkActivityDto.Item> timeline = new ArrayList<>();

        for (AgentQuestion question : questions.listOpen(trunkId)) {
            pinned.add(item(
                    "question:" + question.id(), "question", question.question(),
                    question.context(), "needs-you", trunkPath, question.taskId(),
                    null, question.createdAt(), true));
        }

        List<Notification> notificationRows = notifications.listForThread(trunkId);
        for (Notification notification : notificationRows) {
            boolean gate = isOpenGate(notification);
            TrunkActivityDto.Item item = item(
                    "notification:" + notification.id(),
                    notificationKind(notification),
                    notification.title(),
                    notification.summary(),
                    notification.status().name().toLowerCase(Locale.ROOT),
                    notification.itemPath() == null ? trunkPath : notification.itemPath(),
                    notification.taskId(),
                    null,
                    notification.createdAt(),
                    gate);
            if (gate) {
                pinned.add(item);
            }
            else {
                timeline.add(item);
            }
        }

        List<AgentRun> runRows = runs.findByThread(trunkId);
        for (AgentRun run : runRows) {
            String publicKind = publicRunKind(run.kind());
            if (publicKind == null) {
                continue;
            }
            String title = run.headline() == null || run.headline().isBlank()
                    ? sessionTitle(publicKind)
                    : run.headline();
            String summary = sessionSummary(run);
            timeline.add(item(
                    "session:" + run.id(), "session", title, summary,
                    publicRunStatus(run.status()),
                    "#/workspace/" + workspaceId + "/sessions/" + run.id(),
                    run.taskId(), run.id(),
                    run.finishedAt() == null ? run.startedAt() : run.finishedAt(),
                    AgentRun.STATUS_PAUSED.equals(run.status())));
        }

        List<Task> taskRows = tasks.listTasksByThread(trunkId);
        for (Task task : taskRows) {
            String title = task.name() == null || task.name().isBlank()
                    ? humanize(task.branchName())
                    : task.name();
            String summary = taskSummary(task);
            timeline.add(item(
                    "task:" + task.id(),
                    task.prNumber() == null && task.linkedPrNumber() == null ? "task" : "pull-request",
                    title, summary, task.status().name().toLowerCase(Locale.ROOT),
                    trunkPath, task.id(), null,
                    task.endedAt() == null ? task.createdAt() : task.endedAt(),
                    false));

            for (ReviewRound round : reviewRounds.findByTask(task.id())) {
                String reviewers = round.reviewers().isEmpty()
                        ? "Agent review"
                        : String.join(", ", round.reviewers());
                timeline.add(item(
                        "review:" + round.id(), "review",
                        "Review round " + round.idx(),
                        reviewers + reviewSummary(round),
                        round.status(), trunkPath, task.id(), round.runId(),
                        round.postedAt() != null ? round.postedAt()
                                : round.gatedAt() != null ? round.gatedAt() : round.openedAt(),
                        ReviewRound.STATUS_AWAITING_GATE.equals(round.status())));
            }
        }

        for (BacklogItem item : backlog.list(trunkId)) {
            Instant occurredAt = item.resolvedAt() != null ? item.resolvedAt()
                    : item.rejectedAt() != null ? item.rejectedAt()
                    : item.inProgressAt() != null ? item.inProgressAt()
                    : item.createdAt();
            String key = item.itemKey() == null ? item.id() : item.itemKey();
            timeline.add(item(
                    "backlog:" + item.id(), "backlog",
                    item.title(), item.summary(), item.status(),
                    "#/workspace/" + workspaceId + "/backlog/" + key,
                    item.linkedTaskId(), null, occurredAt, false));
        }

        for (DistillRunDto run : knowledge.listRuns(workspaceId)) {
            if (!belongsToTrunk(run.sources(), trunkId)) {
                continue;
            }
            timeline.add(new TrunkActivityDto.Item(
                    "distill:" + run.id(), "memory", distillTitle(run),
                    run.operations().size() + (run.operations().size() == 1
                            ? " proposed memory change" : " proposed memory changes"),
                    run.status(),
                    "#/workspace/" + workspaceId + "/memory",
                    null, null,
                    run.revertedAt() != null ? run.revertedAt()
                            : run.appliedAt() != null ? run.appliedAt() : run.createdAt(),
                    "pending".equals(run.status())));
        }

        pinned.sort(Comparator.comparingLong(TrunkActivityDto.Item::occurredAt));
        timeline.sort(Comparator.comparingLong(TrunkActivityDto.Item::occurredAt).reversed());
        int pullRequestCount = (int) taskRows.stream()
                .filter(task -> task.prNumber() != null || task.linkedPrNumber() != null)
                .count();
        long costUsdMilli = runRows.stream()
                .filter(run -> publicRunKind(run.kind()) != null)
                .mapToLong(AgentRun::costUsdMilli)
                .sum();
        return new TrunkActivityDto(
                trunkId, List.copyOf(pinned), List.copyOf(timeline),
                taskRows.size(), pullRequestCount, costUsdMilli,
                Instant.now().toEpochMilli());
    }

    private static boolean isOpenGate(Notification notification)
    {
        boolean open = notification.status() == NotificationStatus.UNREAD
                || notification.status() == NotificationStatus.RESOLVING;
        return open && (notification.kind() == NotificationKind.AWAITING_REVIEW
                || notification.kind() == NotificationKind.NEEDS_ATTENTION);
    }

    private static String notificationKind(Notification notification)
    {
        return switch (notification.kind()) {
            case AWAITING_REVIEW -> "approval";
            case NEEDS_ATTENTION -> "question";
            case AUTO_FIX_DONE -> "ci";
            case READY_TO_MERGE -> "pull-request";
            case PASSIVE -> notification.publicType() == null ? "activity" : notification.publicType();
        };
    }

    private static String publicRunKind(String kind)
    {
        return switch (kind) {
            case AgentRun.KIND_PLAN -> "plan";
            case AgentRun.KIND_DEV -> "dev";
            case AgentRun.KIND_REVIEW, AgentRun.KIND_REVIEW_ROUND,
                    AgentRun.KIND_PANEL_REVIEW -> "review";
            case AgentRun.KIND_CI_FIX -> "ci-fix";
            case AgentRun.KIND_BRANCH_GUARD -> null;
            default -> null;
        };
    }

    private static String publicRunStatus(String status)
    {
        return switch (status) {
            case AgentRun.STATUS_QUEUED -> "queued";
            case AgentRun.STATUS_RUNNING -> "running";
            case AgentRun.STATUS_PAUSED, AgentRun.STATUS_AWAITING_GATE -> "paused";
            case AgentRun.STATUS_FAILED -> "errored";
            case AgentRun.STATUS_SUCCEEDED, AgentRun.STATUS_CANCELLED -> "done";
            default -> status;
        };
    }

    private static String sessionTitle(String kind)
    {
        return switch (kind) {
            case "ci-fix" -> "CI fix session";
            case "review" -> "Review session";
            case "dev" -> "Development session";
            default -> "Plan session";
        };
    }

    private static String sessionSummary(AgentRun run)
    {
        List<String> parts = new ArrayList<>();
        if (run.provider() != null && !run.provider().isBlank()) {
            parts.add(run.model() == null || run.model().isBlank()
                    ? run.provider()
                    : run.provider() + " · " + run.model());
        }
        long tokens = run.tokensIn() + run.tokensOut();
        if (tokens > 0) {
            parts.add(tokens + " tokens");
        }
        if (run.costUsdMilli() > 0) {
            parts.add(String.format(Locale.ROOT, "$%.3f", run.costUsdMilli() / 1000.0));
        }
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    private static String taskSummary(Task task)
    {
        List<String> parts = new ArrayList<>();
        Integer pr = task.linkedPrNumber() == null ? task.prNumber() : task.linkedPrNumber();
        if (pr != null) {
            parts.add("PR #" + pr);
        }
        if (task.linkedIssueNumber() != null) {
            parts.add("Issue #" + task.linkedIssueNumber());
        }
        if (task.ciState() != null && !task.ciState().isBlank()) {
            parts.add("CI " + task.ciState().toLowerCase(Locale.ROOT).replace('_', ' '));
        }
        parts.add(task.status().name().toLowerCase(Locale.ROOT).replace('_', ' '));
        return String.join(" · ", parts);
    }

    private static String reviewSummary(ReviewRound round)
    {
        int open = round.stats().open();
        return open == 0 ? "" : " · " + open + (open == 1 ? " open comment" : " open comments");
    }

    private static boolean belongsToTrunk(List<Map<String, Object>> sources, String trunkId)
    {
        return sources.stream().anyMatch(source -> trunkId.equals(source.get("threadId")));
    }

    private static String distillTitle(DistillRunDto run)
    {
        return switch (run.status()) {
            case "applied" -> "Memory changes applied";
            case "reverted" -> "Memory changes reverted";
            case "no-changes" -> "Memory scan found no changes";
            default -> "Memory changes need a decision";
        };
    }

    private static String humanize(String value)
    {
        if (value == null || value.isBlank()) {
            return "Development task";
        }
        String result = value.replace('-', ' ').replace('_', ' ').strip();
        return result.substring(0, 1).toUpperCase(Locale.ROOT) + result.substring(1);
    }

    private static TrunkActivityDto.Item item(
            String id,
            String kind,
            String title,
            String summary,
            String status,
            String itemPath,
            String taskId,
            String sessionId,
            Instant occurredAt,
            boolean actionable)
    {
        return new TrunkActivityDto.Item(
                id, kind, title, summary, status, itemPath, taskId, sessionId,
                occurredAt == null ? 0L : occurredAt.toEpochMilli(), actionable);
    }

    private static ResponseStatusException status(int code, String message)
    {
        return new ResponseStatusException(HttpStatusCode.valueOf(code), message);
    }
}
