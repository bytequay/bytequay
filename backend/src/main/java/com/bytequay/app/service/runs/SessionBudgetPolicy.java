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
package com.bytequay.app.service.runs;

import com.bytequay.app.beans.workspace.WorkspaceSettingsDto;
import com.bytequay.app.domain.AgentMetrics;
import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.NotificationKind;
import com.bytequay.app.service.threads.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

import static java.util.Objects.requireNonNull;

/** Accounts one scheduler turn and enforces the owning workspace's caps. */
@Service
public class SessionBudgetPolicy
{
    private final AgentRunServiceImpl runs;
    private final NotificationService notifications;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SessionBudgetPolicy(
            AgentRunServiceImpl runs,
            NotificationService notifications,
            JdbcTemplate jdbc,
            ObjectMapper mapper)
    {
        this.runs = requireNonNull(runs, "runs is null");
        this.notifications = requireNonNull(notifications, "notifications is null");
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    /**
     * Adds the turn-local metric delta to the Session. Returns true when a
     * configured cap paused it, allowing the scheduler to leave the
     * completed turn durable without changing the Session back to done.
     */
    public synchronized boolean account(
            String runId, AgentMetrics before, AgentMetrics after)
    {
        if (runId == null || runId.isBlank() || before == null || after == null) {
            return false;
        }
        AgentRun prior = runs.findById(runId).orElse(null);
        if (prior == null) {
            return false;
        }
        long costDelta = Math.max(0L, after.costUsdMilli() - before.costUsdMilli());
        long tokensInDelta = Math.max(0L, after.tokensIn() - before.tokensIn());
        long tokensOutDelta = Math.max(0L, after.tokensOut() - before.tokensOut());
        AgentRun updated = runs.updateAccounting(
                runId,
                prior.costUsdMilli() + costDelta,
                prior.tokensIn() + tokensInDelta,
                prior.tokensOut() + tokensOutDelta,
                prior.stepCursor() + 1);
        // Accounting still belongs on a turn that just finished, but a run
        // already sealed (or deliberately paused/cancelled) must not be
        // reopened as PAUSED by a post-completion budget check.
        if (!AgentRun.STATUS_RUNNING.equals(updated.status())) {
            return false;
        }
        if (updated.workspaceId() == null || updated.workspaceId().isBlank()) {
            return false;
        }
        WorkspaceSettingsDto settings = settings(updated.workspaceId());
        if (!settings.pauseAtCap() || costDelta == 0L) {
            return false;
        }

        long sessionCap = usdMilli(settings.sessionCapUsd());
        long dailyCap = usdMilli(settings.dailyCapUsd());
        String reason = null;
        if (updated.costUsdMilli() >= sessionCap) {
            reason = String.format(
                    Locale.ROOT,
                    "per-session budget cap reached ($%.2f)",
                    settings.sessionCapUsd());
        }
        else if (dailySpend(updated.workspaceId()) >= dailyCap) {
            reason = String.format(
                    Locale.ROOT,
                    "daily workspace budget cap reached ($%.2f)",
                    settings.dailyCapUsd());
        }
        if (reason == null) {
            return false;
        }

        AgentRun paused = runs.pause(updated.id(), reason);
        boolean taskTurn = updated.taskId() != null && !updated.taskId().isBlank();
        String path = taskTurn
                ? "#/workspace/" + updated.workspaceId() + "/settings/agents"
                : "#/workspace/" + updated.workspaceId() + "/sessions/" + updated.id();
        notifications.createCanonical(
                NotificationKind.NEEDS_ATTENTION,
                updated.workspaceId(),
                updated.threadId(),
                updated.taskId(),
                "budget",
                taskTurn ? "Task paused at budget cap" : "Session paused at budget cap",
                taskTurn
                        ? reason + ". Increase the workspace budget in Settings → Agents, then resume this task."
                        : reason + ". Review the usage, then resume or restart.",
                path,
                "session-budget:" + updated.id(),
                "{\"sessionId\":\"" + updated.id() + "\"}");
        return AgentRun.STATUS_PAUSED.equals(paused.status());
    }

    private WorkspaceSettingsDto settings(String workspaceId)
    {
        List<String> rows = jdbc.queryForList("""
                SELECT settings_json
                FROM workspace_settings
                WHERE workspace_id = ?
                """, String.class, workspaceId);
        if (rows.isEmpty()) {
            return WorkspaceSettingsDto.defaults();
        }
        try {
            return mapper.readValue(rows.getFirst(), WorkspaceSettingsDto.class);
        }
        catch (Exception ignored) {
            return WorkspaceSettingsDto.defaults();
        }
    }

    private long dailySpend(String workspaceId)
    {
        LocalDate today = LocalDate.now();
        ZoneId zone = ZoneId.systemDefault();
        Instant start = today.atStartOfDay(zone).toInstant();
        Instant end = today.plusDays(1).atStartOfDay(zone).toInstant();
        return runs.findByWorkspace(workspaceId).stream()
                .filter(run -> !run.startedAt().isBefore(start) && run.startedAt().isBefore(end))
                .mapToLong(AgentRun::costUsdMilli)
                .sum();
    }

    private static long usdMilli(double usd)
    {
        return Math.max(0L, Math.round(usd * 1_000.0));
    }
}
