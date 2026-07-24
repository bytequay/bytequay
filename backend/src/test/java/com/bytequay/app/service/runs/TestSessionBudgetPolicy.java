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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestSessionBudgetPolicy
{
    private final AgentRunService runs = mock(AgentRunService.class);
    private final NotificationService notifications =
            mock(NotificationService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SessionBudgetPolicy policy = new SessionBudgetPolicy(
            runs, notifications, jdbc, new ObjectMapper());

    @Test
    void workspaceDailyBudgetDefaultsToFiveHundredDollars()
    {
        assertThat(WorkspaceSettingsDto.defaults().dailyCapUsd()).isEqualTo(500.0);
    }

    @Test
    void accountsOnlyTheTurnDeltaAndPausesAtTheSessionCap()
    {
        AgentRun prior = run(900L, 10L, 20L);
        AgentRun accounted = run(1_100L, 60L, 40L);
        AgentRun paused = accounted.paused(
                "per-session budget cap reached ($1.00)");
        when(runs.findById(prior.id()))
                .thenReturn(Optional.of(prior));
        when(runs.updateAccounting(
                prior.id(), 1_100L, 60L, 40L, 1))
                .thenReturn(accounted);
        when(jdbc.queryForList(
                anyString(), eq(String.class), eq("ws-1")))
                .thenReturn(List.of("""
                        {
                          "sessionCapUsd": 1.0,
                          "dailyCapUsd": 10.0,
                          "pauseAtCap": true,
                          "syncSeconds": 60,
                          "brainBudgetChars": 8000,
                          "distillMinutes": 30,
                          "kbAudiences": ["plan", "dev", "review", "ci-fix"],
                          "providers": {},
                          "notifyCi": true,
                          "notifyCompletions": false
                        }
                        """));
        when(runs.pause(
                prior.id(),
                "per-session budget cap reached ($1.00)"))
                .thenReturn(paused);

        boolean capped = policy.account(
                prior.id(),
                new AgentMetrics(100L, 1_000L, 500L, 200L, 2, 1),
                new AgentMetrics(200L, 1_200L, 550L, 220L, 4, 2));

        assertThat(capped).isTrue();
        verify(runs).updateAccounting(
                prior.id(), 1_100L, 60L, 40L, 1);
        verify(runs).pause(
                prior.id(),
                "per-session budget cap reached ($1.00)");
        ArgumentCaptor<String> summary =
                ArgumentCaptor.forClass(String.class);
        verify(notifications).createCanonical(
                eq(NotificationKind.NEEDS_ATTENTION),
                eq("ws-1"),
                eq("trunk-1"),
                eq("task-1"),
                eq("budget"),
                eq("Task paused at budget cap"),
                summary.capture(),
                eq("#/workspace/ws-1/settings/agents"),
                eq("session-budget:run-1"),
                anyString());
        assertThat(summary.getValue())
                .contains("per-session budget cap reached")
                .contains("Settings → Agents")
                .contains("resume this task");
    }

    @Test
    void missingMetricsDoNotMutateTheSession()
    {
        assertThat(policy.account("run-1", null, AgentMetrics.empty()))
                .isFalse();

        verify(runs, never()).findById(anyString());
    }

    @Test
    void terminalRunIsAccountedWithoutBeingReopenedAtTheBudgetCap()
    {
        AgentRun prior = run(900L, 10L, 20L);
        AgentRun completed = run(1_100L, 60L, 40L)
                .withStatus(AgentRun.STATUS_SUCCEEDED, Instant.parse("2026-07-17T00:01:00Z"));
        when(runs.findById(prior.id())).thenReturn(Optional.of(prior));
        when(runs.updateAccounting(prior.id(), 1_100L, 60L, 40L, 1))
                .thenReturn(completed);

        assertThat(policy.account(
                prior.id(),
                new AgentMetrics(100L, 1_000L, 500L, 200L, 2, 1),
                new AgentMetrics(200L, 1_200L, 550L, 220L, 4, 2)))
                .isFalse();

        verify(runs, never()).pause(anyString(), anyString());
        verify(notifications, never()).createCanonical(
                eq(NotificationKind.NEEDS_ATTENTION),
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString());
    }

    private static AgentRun run(
            long costUsdMilli,
            long tokensIn,
            long tokensOut)
    {
        return new AgentRun(
                "run-1",
                "task-1",
                AgentRun.KIND_DEV,
                AgentRun.SOURCE_SCHEDULED,
                "stage-1",
                null,
                "stage-1",
                AgentRun.STATUS_RUNNING,
                0,
                null,
                null,
                null,
                Instant.parse("2026-07-17T00:00:00Z"),
                null,
                "ws-1",
                "trunk-1",
                "claude-code",
                "sonnet",
                costUsdMilli,
                tokensIn,
                tokensOut,
                0,
                "Implement",
                null,
                null);
    }
}
