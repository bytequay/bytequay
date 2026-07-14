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
package com.bytequay.app.service;

import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.github.GitHubRateLimitMonitor;
import com.bytequay.app.repository.sqlite.InvestigationReviewStore;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Rolls thread state up into the per-window numbers the Workspace
 * Insights surface renders. Active threads + tasks-in-flight come
 * straight from the existing status query; spend over the window
 * sums {@code costUsdMilli} from threads whose {@code updatedAt}
 * lands inside the window plus task-owned AgentReview round spend;
 * the per-day breakdown buckets those charges by local calendar day
 * so the chart matches what the user sees in their timezone.
 *
 * <p>Tasks-shipped-per-repo is intentionally absent from this commit
 * because {@code Task} doesn't carry an owner/repo column today —
 * the workingDir path is the only repo signal and parsing it is
 * fragile. A follow-up adds the column (or joins via PR lookup) and
 * extends this service.
 */
@Service
public class WorkspaceInsightsService
{
    /** Statuses that count as "in-flight" for the headline counters.
     *  Lines up with {@code ThreadDto.activeTask} on the frontend so
     *  the Home page and Insights page report the same numbers. */
    private static final Set<ThreadStatus> ACTIVE = Set.of(
            ThreadStatus.PENDING,
            ThreadStatus.RUNNING,
            ThreadStatus.AWAITING,
            ThreadStatus.IDLE);

    private final ThreadStore threadStore;
    private final TaskStore taskStore;
    private final InvestigationReviewStore reviewStore;
    private final GitHubRateLimitMonitor rateLimitMonitor;

    public WorkspaceInsightsService(
            ThreadStore threadStore, TaskStore taskStore,
            InvestigationReviewStore reviewStore, GitHubRateLimitMonitor rateLimitMonitor)
    {
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.reviewStore = requireNonNull(reviewStore, "reviewStore is null");
        this.rateLimitMonitor = requireNonNull(rateLimitMonitor, "rateLimitMonitor is null");
    }

    /** Page size when scanning shipped tasks. Single-user local app,
     *  so 1000 covers the typical window comfortably; if the user
     *  hits the cap the count just rolls over (rare). */
    private static final int SHIPPED_TASKS_PAGE = 1_000;

    public Insights get(String window)
    {
        Duration windowDuration = parseWindow(window);
        Instant now = Instant.now();
        Instant windowStart = now.minus(windowDuration);
        Instant today = LocalDate.now(ZoneId.systemDefault())
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant();

        List<Thread> recent = threadStore.listThreadsUpdatedSince(windowStart);

        // Bucket by local-day for the spend chart. Days with no
        // recorded spend still surface as 0 so the chart's x-axis
        // length matches the window.
        ZoneId zone = ZoneId.systemDefault();
        Map<LocalDate, Long> spendByDay = new LinkedHashMap<>();
        LocalDate fromDay = LocalDate.ofInstant(windowStart, zone);
        LocalDate toDay = LocalDate.ofInstant(now, zone);
        for (LocalDate d = fromDay; !d.isAfter(toDay); d = d.plusDays(1)) {
            spendByDay.put(d, 0L);
        }

        long spendInWindowMilli = 0L;
        long spendTodayMilli = 0L;
        int activeThreads = 0;
        int tasksInFlight = 0;
        for (Thread t : recent) {
            spendInWindowMilli += t.costUsdMilli();
            LocalDate day = LocalDate.ofInstant(t.updatedAt(), zone);
            spendByDay.merge(day, t.costUsdMilli(), Long::sum);
            if (!t.updatedAt().isBefore(today)) {
                spendTodayMilli += t.costUsdMilli();
            }
            if (ACTIVE.contains(t.status())) {
                activeThreads++;
                if (taskStore.hasActiveTask(t.id())) {
                    tasksInFlight++;
                }
            }
        }
        for (InvestigationReviewStore.TaskReviewSpend spend
                : reviewStore.taskReviewSpendSince(windowStart)) {
            spendInWindowMilli += spend.costMilli();
            LocalDate day = LocalDate.ofInstant(spend.occurredAt(), zone);
            spendByDay.merge(day, spend.costMilli(), Long::sum);
            if (!spend.occurredAt().isBefore(today)) {
                spendTodayMilli += spend.costMilli();
            }
        }

        // The Insights page caps the chart at the most recent N
        // points to match the existing placeholder shape (7d -> 7
        // bars, 24h -> 8 buckets of 3h, 30d -> ~10 bars of 3d). Keep
        // it simple here: emit one entry per day in the window;
        // the frontend already handles arbitrary length.
        List<DayPoint> series = new ArrayList<>();
        for (Map.Entry<LocalDate, Long> e : spendByDay.entrySet()) {
            series.add(new DayPoint(e.getKey().toString(),
                    formatDayLabel(e.getKey(), now, zone),
                    e.getValue()));
        }

        int tasksShipped = countTasksShippedSince(windowStart);

        return new Insights(
                window,
                activeThreads,
                tasksInFlight,
                /* reposInWorkspace */ 0,
                spendTodayMilli,
                spendInWindowMilli,
                tasksShipped,
                series,
                tasksByRepo(windowStart),
                rateLimitMonitor.latest()
                        .map(s -> new GitHubRateLimit(s.remaining(), s.limit(), s.resetAt().toString()))
                        .orElse(null));
    }

    /** Per-repo split of PR-linked tasks: shipped (reached COMPLETED, cut
     *  inside the window) vs still-open (not yet terminal). Attribution is
     *  by the {@code owner/repo#n} link ref — the only repo signal a Task
     *  carries today. Tasks with no linked PR (no repo signal) are omitted.
     *  Sorted by shipped count, then open. */
    private List<RepoTaskBreakdown> tasksByRepo(Instant windowStart)
    {
        Map<String, int[]> byRepo = new LinkedHashMap<>(); // repo -> [shipped, open]
        for (Task t : taskStore.listWithLinkedPr(SHIPPED_TASKS_PAGE)) {
            String repo = repoFromLinkedPrRef(t.linkedPrRef());
            if (repo == null) {
                continue;
            }
            int[] counts = byRepo.computeIfAbsent(repo, k -> new int[2]);
            if (t.phase() == TaskPhase.COMPLETED) {
                if (!t.createdAt().isBefore(windowStart)) {
                    counts[0]++;
                }
            }
            else if (!isTerminal(t.status())) {
                counts[1]++;
            }
        }
        return byRepo.entrySet().stream()
                .map(e -> new RepoTaskBreakdown(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .filter(r -> r.tasksShipped() > 0 || r.tasksOpen() > 0)
                .sorted(Comparator.comparingInt(RepoTaskBreakdown::tasksShipped)
                        .thenComparingInt(RepoTaskBreakdown::tasksOpen).reversed())
                .toList();
    }

    /** Parse {@code owner/repo} out of an {@code owner/repo#n} link ref. */
    private static String repoFromLinkedPrRef(String linkedPrRef)
    {
        if (linkedPrRef == null || linkedPrRef.isBlank()) {
            return null;
        }
        int hash = linkedPrRef.indexOf('#');
        return hash < 0 ? linkedPrRef : linkedPrRef.substring(0, hash);
    }

    private static boolean isTerminal(TaskStatus status)
    {
        return status == TaskStatus.COMPLETED
                || status == TaskStatus.REMOTE_CLOSED
                || status == TaskStatus.CANCELED
                || status == TaskStatus.ERRORED;
    }

    /** Tasks linked to a PR that updated inside the window. The
     *  linked_pr_number set on a Task is the "shipped" signal — the
     *  task ran through ship-and-continue, opened (or finalized) a
     *  PR, and the agent moved on to the next sequence step. A
     *  per-repo breakdown wants an owner/repo column on Task that
     *  doesn't exist today; this rolled-up count is the honest
     *  number until that column lands. */
    private int countTasksShippedSince(Instant since)
    {
        // Task has no updatedAt today, so we filter on createdAt
        // (when the task was cut) — close enough for "shipped this
        // window" since a task with a linked PR is almost always
        // created → PR-opened in the same session. Tasks that linger
        // for follow-up commits past the window cap still count if
        // they originated inside it.
        List<Task> withPr = taskStore.listWithLinkedPr(SHIPPED_TASKS_PAGE);
        int count = 0;
        for (Task t : withPr) {
            if (!t.createdAt().isBefore(since)) {
                count++;
            }
        }
        return count;
    }

    private static Duration parseWindow(String window)
    {
        if (window == null) {
            return Duration.ofDays(7);
        }
        return switch (window.toLowerCase(Locale.ROOT)) {
            case "24h" -> Duration.ofHours(24);
            case "30d" -> Duration.ofDays(30);
            default -> Duration.ofDays(7);
        };
    }

    private static String formatDayLabel(LocalDate day, Instant now, ZoneId zone)
    {
        LocalDate today = LocalDate.ofInstant(now, zone);
        if (day.equals(today)) {
            return "Today";
        }
        // Short day-of-week for the 7d window matches the mockup.
        return day.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.getDefault());
    }

    public record Insights(
            String window,
            int activeThreads,
            int tasksInFlight,
            int reposInWorkspace,
            long spendTodayMilli,
            long spendInWindowMilli,
            int tasksShippedInWindow,
            List<DayPoint> spendByDay,
            List<RepoTaskBreakdown> tasksByRepo,
            GitHubRateLimit githubRateLimit) {}

    public record DayPoint(String date, String label, long costUsdMilli) {}

    public record RepoTaskBreakdown(String repoFullName, int tasksShipped, int tasksOpen) {}

    /** Latest GitHub REST quota, or null if no call has landed since boot. */
    public record GitHubRateLimit(int remaining, int limit, String resetAt) {}
}
