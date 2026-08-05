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

import com.bytequay.app.beans.workspace.TrunkDto;
import com.bytequay.app.beans.workspace.WorkspaceOnboardingDto;
import com.bytequay.app.beans.workspace.WorkspaceOverviewDto;
import com.bytequay.app.beans.workspace.WorkspaceSummaryDto;
import com.bytequay.app.developmentflow.compatibility.V2DevelopmentFlowProjection;
import com.bytequay.app.developmentflow.compatibility.V2TrunkRuntimeProjection;
import com.bytequay.app.domain.BacklogItem;
import com.bytequay.app.domain.NotificationStatus;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.domain.WorkspaceCardDto;
import com.bytequay.app.domain.WorkspaceRepo;
import com.bytequay.app.repository.BacklogStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.runs.SessionProjectionService;
import com.bytequay.app.service.threads.NotificationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;

import static java.util.Objects.requireNonNull;

/** Builds the two public workspace read models from existing stores. */
@Service
public class WorkspaceOverviewService
{
    private final WorkspaceService workspaces;
    private final WorkspaceCreationService creations;
    private final WorkspaceConfigurationService configuration;
    private final ThreadStore threads;
    private final V2TrunkRuntimeProjection trunkRuntime;
    private final TaskStore tasks;
    private final V2DevelopmentFlowProjection taskRuntime;
    private final SessionProjectionService sessions;
    private final BacklogStore backlog;
    private final NotificationService notifications;
    private final WatchedRepoStore watchedRepos;
    private final JdbcTemplate jdbc;

    public WorkspaceOverviewService(
            WorkspaceService workspaces,
            WorkspaceCreationService creations,
            WorkspaceConfigurationService configuration,
            ThreadStore threads,
            V2TrunkRuntimeProjection trunkRuntime,
            TaskStore tasks,
            V2DevelopmentFlowProjection taskRuntime,
            SessionProjectionService sessions,
            BacklogStore backlog,
            NotificationService notifications,
            WatchedRepoStore watchedRepos,
            JdbcTemplate jdbc)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.creations = requireNonNull(creations, "creations is null");
        this.configuration = requireNonNull(configuration, "configuration is null");
        this.threads = requireNonNull(threads, "threads is null");
        this.trunkRuntime = requireNonNull(
                trunkRuntime, "trunkRuntime is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.taskRuntime = requireNonNull(taskRuntime, "taskRuntime is null");
        this.sessions = requireNonNull(sessions, "sessions is null");
        this.backlog = requireNonNull(backlog, "backlog is null");
        this.notifications = requireNonNull(notifications, "notifications is null");
        this.watchedRepos = requireNonNull(watchedRepos, "watchedRepos is null");
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    public List<WorkspaceSummaryDto> listReady()
    {
        return workspaces.listWithStats().stream()
                .filter(card -> creations.visible(card.id()))
                .map(this::summary)
                .filter(WorkspaceSummaryDto::ready)
                .toList();
    }

    public WorkspaceOverviewDto overview(String workspaceId)
    {
        workspaces.require(workspaceId);
        WorkspaceCardDto card = workspaces.listWithStats().stream()
                .filter(candidate -> candidate.id().equals(workspaceId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException(
                        "no workspace summary: " + workspaceId));
        List<Thread> all = publicTrunks(workspaceId);
        WorkspaceSummaryDto summary = summary(card, all);
        List<BacklogItem> backlogRows = backlog.findByWorkspace(workspaceId);
        long midnight = localMidnight();
        List<Thread> needsYou = all.stream()
                .filter(WorkspaceOverviewService::needsYou)
                .toList();
        List<Thread> running = all.stream()
                .filter(thread -> thread.status() == ThreadStatus.RUNNING)
                .toList();
        List<Thread> landed = all.stream()
                .filter(thread -> (thread.status() == ThreadStatus.COMPLETED
                        || thread.status() == ThreadStatus.ARCHIVED)
                        && thread.updatedAt().toEpochMilli() >= midnight)
                .toList();
        int unread = (int) notifications.listForWorkspace(workspaceId).stream()
                .filter(notification -> notification.status() == NotificationStatus.UNREAD)
                .count();
        int liveSessions = sessions.countLive(workspaceId);
        int openBacklog = (int) backlogRows.stream()
                .filter(item -> !BacklogItem.STATUS_RESOLVED.equals(item.status())
                        && !BacklogItem.STATUS_DISCARDED.equals(item.status()))
                .count();
        WorkspaceOnboardingDto onboarding =
                configuration.onboarding(workspaceId);
        WorkspaceOverviewDto.SidebarCountsDto counts =
                new WorkspaceOverviewDto.SidebarCountsDto(
                        needsYou.size(),
                        (int) all.stream().filter(WorkspaceOverviewService::active).count(),
                        openPullRequests(workspaceId, summary.repository()),
                        null,
                        openBacklog,
                        null,
                        liveSessions,
                        unread);
        List<TrunkDto> pinned = all.stream()
                .filter(WorkspaceOverviewService::active)
                .limit(5)
                .map(TrunkDto::from)
                .toList();
        WorkspaceOverviewDto.TodayDto today =
                new WorkspaceOverviewDto.TodayDto(
                        needsYou.stream().map(TrunkDto::from).toList(),
                        running.stream().map(TrunkDto::from).toList(),
                        landed.stream().map(TrunkDto::from).toList(),
                        summary.spendTodayMilliUsd());
        return new WorkspaceOverviewDto(
                summary,
                summary.repository(),
                counts,
                pinned,
                today,
                onboarding,
                onboarding.syncState());
    }

    private WorkspaceSummaryDto summary(WorkspaceCardDto card)
    {
        return summary(card, publicTrunks(card.id()));
    }

    private WorkspaceSummaryDto summary(
            WorkspaceCardDto storedCard, List<Thread> trunks)
    {
        WorkspaceCardDto card = effectiveCard(storedCard, trunks);
        WorkspaceOnboardingDto onboarding =
                configuration.onboarding(card.id());
        WorkspaceSummaryDto.RepositoryDto repository =
                repository(card.id());
        List<WorkspaceSummaryDto.ActivityDto> recent =
                trunks.stream()
                        .limit(2)
                        .map(thread -> new WorkspaceSummaryDto.ActivityDto(
                                thread.id(),
                                thread.title(),
                                thread.status().name(),
                                "#/workspace/" + card.id() + "/trunks/" + thread.id(),
                                thread.updatedAt().toEpochMilli()))
                        .toList();
        boolean ready = creations.visible(card.id())
                && "ready".equals(onboarding.syncState())
                && (card.isScratch()
                        || repository != null && repository.verified());
        return WorkspaceSummaryDto.from(
                card, repository, recent, ready, onboarding.syncState());
    }

    private WorkspaceCardDto effectiveCard(
            WorkspaceCardDto stored, List<Thread> trunks)
    {
        if (stored.isScratch()) {
            return stored;
        }
        List<Task> effectiveTasks = trunks.stream()
                .flatMap(trunk -> tasks.listTasksByThread(trunk.id()).stream())
                .map(task -> taskRuntime.isV2Task(task.id())
                        ? taskRuntime.project(task)
                        : task)
                .toList();
        long midnight = localMidnight();
        Long lastActivity = trunks.stream()
                .map(Thread::updatedAt)
                .max(Comparator.naturalOrder())
                .map(Instant::toEpochMilli)
                .orElse(null);
        return new WorkspaceCardDto(
                stored.id(), stored.name(), stored.color(), false,
                stored.repos(),
                (int) trunks.stream().filter(WorkspaceOverviewService::active).count(),
                (int) effectiveTasks.stream()
                        .filter(WorkspaceOverviewService::inFlight).count(),
                effectiveTasks.stream()
                        .filter(task -> task.createdAt().toEpochMilli() >= midnight)
                        .mapToLong(Task::costUsdMilli)
                        .sum(),
                (int) effectiveTasks.stream()
                        .filter(WorkspaceOverviewService::needsYou).count(),
                stored.memory(), lastActivity);
    }

    private WorkspaceSummaryDto.RepositoryDto repository(String workspaceId)
    {
        List<WorkspaceRepo> repos = workspaces.listRepos(workspaceId);
        if (repos.size() != 1) {
            return null;
        }
        WorkspaceRepo row = repos.getFirst();
        String fullName = row.repoFullName();
        int slash = fullName.indexOf('/');
        if (slash < 1 || slash == fullName.length() - 1) {
            return null;
        }
        String owner = fullName.substring(0, slash);
        String repo = fullName.substring(slash + 1);
        WatchedRepo watched = watchedRepos.find(owner, repo).orElse(null);
        String clonePath = watched == null ? null : watched.localClonePath();
        return new WorkspaceSummaryDto.RepositoryDto(
                owner,
                repo,
                fullName,
                row.defaultBaseBranch(),
                clonePath,
                directory(clonePath),
                watched != null
                        && watched.upstreamRemoteName() != null
                        && !watched.upstreamRemoteName().isBlank());
    }

    private List<Thread> publicTrunks(String workspaceId)
    {
        List<Thread> stored = threads.listThreadsByWorkspace(workspaceId).stream()
                .filter(thread -> thread.kind() != ThreadKind.BRAIN_AGENT)
                .filter(thread -> thread.flow() != ThreadFlow.REVIEW)
                .filter(thread -> thread.parentTaskId() == null)
                .toList();
        return trunkRuntime.projectAll(stored).stream()
                .sorted(Comparator.comparing(Thread::updatedAt).reversed())
                .toList();
    }

    private int openPullRequests(
            String workspaceId,
            WorkspaceSummaryDto.RepositoryDto repository)
    {
        if (repository == null) {
            return 0;
        }
        Long count = jdbc.queryForObject("""
                SELECT count(*)
                FROM pr
                WHERE merged_at_ms IS NULL
                  AND closed_at_ms IS NULL
                  AND (
                    lower(repo) = lower(?)
                    OR task_id IN (
                      SELECT task.id
                      FROM tasks task
                      JOIN threads thread ON thread.id = task.thread_id
                      WHERE thread.workspace_id = ?
                    )
                  )
                """, Long.class, repository.fullName(), workspaceId);
        return count == null ? 0 : count.intValue();
    }

    private static boolean active(Thread thread)
    {
        return thread.status() != ThreadStatus.COMPLETED
                && thread.status() != ThreadStatus.ARCHIVED
                && thread.status() != ThreadStatus.ERRORED;
    }

    private static boolean needsYou(Thread thread)
    {
        return thread.status() == ThreadStatus.AWAITING_REVIEW
                || thread.status() == ThreadStatus.NEEDS_ATTENTION;
    }

    private static boolean inFlight(Task task)
    {
        return switch (task.status()) {
            case PENDING, RUNNING, IDLE, AWAITING_REVIEW, NEEDS_ATTENTION -> true;
            default -> false;
        };
    }

    private static boolean needsYou(Task task)
    {
        return task.status() == TaskStatus.AWAITING_REVIEW
                || task.status() == TaskStatus.NEEDS_ATTENTION;
    }

    private static long localMidnight()
    {
        ZoneId zone = ZoneId.systemDefault();
        return LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli();
    }

    private static boolean directory(String value)
    {
        try {
            return value != null && !value.isBlank()
                    && Files.isDirectory(Path.of(value));
        }
        catch (RuntimeException ignored) {
            return false;
        }
    }
}
