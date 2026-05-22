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
package com.bytequay.app.domain;

import java.util.List;

/**
 * Read-only projection of a workspace shaped for the top-level
 * Workspaces landing grid — one card per workspace, with the
 * at-a-glance aggregates the user picks between on. Threads, tasks,
 * and the memory markdown are summarised here so the landing renders
 * without a follow-up fetch.
 *
 * <p>Sourced from {@link Workspace} (id / name / scratch flag), the
 * attached {@link WorkspaceRepo} rows (repos), and per-workspace
 * counts/sums computed from the {@code threads} and {@code tasks}
 * tables. The memory aggregates parse the {@code ## Decisions} and
 * {@code ## Blockers} sections of {@code memoryMd}.
 *
 * @param color hex string used to colour the card's gradient avatar.
 *              Derived from the workspace name so a hand-typed name
 *              keeps the same colour across restarts.
 * @param spendTodayMilliUsd milli-USD spent on tasks created since
 *                           local midnight. Approximation — we don't
 *                           keep a per-day cost ledger, so a long
 *                           multi-day task counts on its create date.
 * @param needsAttentionCount tasks currently parked at
 *                            {@link TaskStatus#AWAITING_REVIEW} or
 *                            {@link TaskStatus#NEEDS_ATTENTION}.
 * @param lastActivityMs max {@code updated_at_ms} across the
 *                       workspace's threads; null when the workspace
 *                       has no threads yet.
 */
public record WorkspaceCardDto(
        String id,
        String name,
        String color,
        boolean isScratch,
        List<String> repos,
        int activeThreadCount,
        int tasksInFlight,
        long spendTodayMilliUsd,
        int needsAttentionCount,
        MemorySummary memory,
        Long lastActivityMs)
{
    /** Bullet counts under {@code ## Decisions} / {@code ## Blockers}
     *  plus a coarse token-usage estimate so the card can render the
     *  budget bar without a second fetch.
     *
     * @param tokensUsed rough token estimate of {@code memoryMd}
     *                   (char count / 4).
     * @param tokensCap design target — ~4k tokens. The
     *                  {@code memoryMd} hard cap on the service is
     *                  higher to allow paste-and-distill workflows;
     *                  this is the displayed budget. */
    public record MemorySummary(
            int decisionCount,
            int blockerCount,
            int tokensUsed,
            int tokensCap)
    {
    }
}
