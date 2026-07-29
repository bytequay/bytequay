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

import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.repository.AgentRunStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Read boundary for historical AgentRuns.
 *
 * <p>AgentRun stopped being an execution or lifecycle aggregate at the V2
 * cutover.  Every historical mutation entry point fails closed.  The sole
 * write is a terminal compatibility header required by foreign keys in the
 * investigation-review schema; typed ReviewAssignmentTurns own all work and
 * state around that header.</p>
 */
@Service
class AgentRunServiceImpl
        implements AgentRunService
{
    private static final String RETIRED =
            "AgentRun execution is retired; use typed V2 Turns and Operations";

    private final AgentRunStore store;
    private final Clock clock;

    @Autowired
    AgentRunServiceImpl(AgentRunStore store)
    {
        this(store, Clock.systemUTC());
    }

    AgentRunServiceImpl(AgentRunStore store, Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public Optional<AgentRun> findById(String runId)
    {
        return store.findById(runId)
                .filter(run -> !run.isReviewCompatibilityHeader());
    }

    @Override
    public Optional<AgentRun> findReviewCompatibilityHeaderById(String runId)
    {
        return store.findById(runId)
                .filter(AgentRun::isReviewCompatibilityHeader);
    }

    @Override
    public List<AgentRun> findByWorkspace(String workspaceId)
    {
        return visible(store.findByWorkspace(workspaceId));
    }

    @Override
    public List<AgentRun> findByThread(String threadId)
    {
        return visible(store.findByThread(threadId));
    }

    @Override
    public List<AgentRun> findByReviewRound(String reviewRoundId)
    {
        return store.findByReviewRound(reviewRoundId);
    }

    @Override
    public List<AgentRun> findByTask(String taskId, String kind, String parentStageId)
    {
        return visible(store.findByTask(taskId, kind, parentStageId));
    }

    @Override
    public List<AgentRun> liveRunsByTask(String taskId)
    {
        return visible(store.findLiveByTask(taskId));
    }

    @Override
    public AgentRun createReviewCompatibilityHeader(
            String reviewRoundId,
            Integer budget)
    {
        requireText(reviewRoundId, "reviewRoundId");
        if (budget != null && budget < 0) {
            throw new IllegalArgumentException("budget must be non-negative");
        }
        Instant createdAt = Instant.now(clock);
        AgentRun header = new AgentRun(
                UUID.randomUUID().toString(),
                null,
                AgentRun.KIND_REVIEW_COMPATIBILITY_HEADER,
                AgentRun.SOURCE_V2_REVIEW_FOREIGN_KEY,
                null,
                reviewRoundId,
                null,
                AgentRun.STATUS_SUCCEEDED,
                0,
                budget,
                null,
                null,
                createdAt,
                createdAt,
                null,
                null,
                null,
                null,
                0L,
                0L,
                0L,
                0,
                null,
                null,
                "completed");
        return store.insert(header);
    }

    @Override
    public AgentRun open(
            String taskId, String kind, String source, String parentStageId,
            StageType backingStageType, Integer budget)
    {
        throw retired();
    }

    @Override
    public AgentRun openInCommand(
            String taskId, String kind, String source, String parentStageId,
            StageType backingStageType, Integer budget)
    {
        throw retired();
    }

    @Override
    public AgentRun openInStage(
            String taskId, String kind, String source, String stageId, Integer budget)
    {
        throw retired();
    }

    @Override
    public AgentRun openInStageInCommand(
            String taskId, String kind, String source, String stageId, Integer budget)
    {
        throw retired();
    }

    @Override
    public AgentRun openDetached(
            String kind, String source, String reviewRoundId, Integer budget)
    {
        throw retired();
    }

    @Override
    public AgentRun openTaskArtifact(
            String taskId, String kind, String source, String reviewRoundId, Integer budget)
    {
        throw retired();
    }

    @Override
    public AgentRun openSchedulerSession(
            Thread thread, String taskId, String stageId, String kind, String launchInput)
    {
        throw retired();
    }

    @Override
    public AgentRun openSchedulerSessionInCommand(
            Thread thread, String taskId, String stageId, String kind, String launchInput)
    {
        throw retired();
    }

    @Override
    public AgentRun attachOwnership(
            String runId, String workspaceId, String threadId,
            String provider, String model, String launchInput)
    {
        throw retired();
    }

    @Override
    public AgentRun recordIteration(String runId, String headlineOrNull)
    {
        throw retired();
    }

    @Override
    public AgentRun spendBudget(String runId)
    {
        throw retired();
    }

    @Override
    public AgentRun updateHeadline(String runId, String headline)
    {
        throw retired();
    }

    @Override
    public AgentRun updateMetrics(String runId, String metricsJson)
    {
        throw retired();
    }

    @Override
    public AgentRun updateAccounting(
            String runId, long costUsdMilli, long tokensIn, long tokensOut, int stepCursor)
    {
        throw retired();
    }

    @Override
    public AgentRun pause(String runId, String reason)
    {
        throw retired();
    }

    @Override
    public AgentRun pauseInCommand(String taskId, String runId, String reason)
    {
        throw retired();
    }

    @Override
    public AgentRun resume(String runId)
    {
        throw retired();
    }

    @Override
    public AgentRun restart(String runId)
    {
        throw retired();
    }

    @Override
    public AgentRun restartInCommand(String taskId, String runId)
    {
        throw retired();
    }

    @Override
    public AgentRun transition(String runId, String status, String reason)
    {
        throw retired();
    }

    @Override
    public AgentRun transitionInCommand(
            String taskId, String runId, String status, String reason)
    {
        throw retired();
    }

    private static List<AgentRun> visible(List<AgentRun> runs)
    {
        return runs.stream()
                .filter(run -> !run.isReviewCompatibilityHeader())
                .toList();
    }

    private static UnsupportedOperationException retired()
    {
        return new UnsupportedOperationException(RETIRED);
    }

    private static void requireText(String value, String field)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
