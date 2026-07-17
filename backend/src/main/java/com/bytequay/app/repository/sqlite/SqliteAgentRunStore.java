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
package com.bytequay.app.repository.sqlite;

import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.repository.AgentRunStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Component
class SqliteAgentRunStore
        implements AgentRunStore
{
    private final AgentRunJpaRepository runs;

    SqliteAgentRunStore(AgentRunJpaRepository runs)
    {
        this.runs = runs;
    }

    @Override
    @Transactional
    public AgentRun save(AgentRun run)
    {
        AgentRunEntity e = new AgentRunEntity();
        e.setId(run.id());
        e.setTaskId(run.taskId());
        e.setKind(run.kind());
        e.setSource(run.source());
        e.setParentStageId(run.parentStageId());
        e.setReviewRoundId(run.reviewRoundId());
        e.setStageId(run.stageId());
        e.setStatus(run.status());
        e.setIterations(run.iterations());
        e.setBudget(run.budget());
        e.setHeadline(run.headline());
        e.setMetricsJson(run.metricsJson());
        e.setStartedAtMs(run.startedAt().toEpochMilli());
        e.setFinishedAtMs(epochOrNull(run.finishedAt()));
        e.setWorkspaceId(run.workspaceId());
        e.setThreadId(run.threadId());
        e.setProvider(run.provider());
        e.setModel(run.model());
        e.setCostUsdMilli(run.costUsdMilli());
        e.setTokensIn(run.tokensIn());
        e.setTokensOut(run.tokensOut());
        e.setStepCursor(run.stepCursor());
        e.setLaunchInput(run.launchInput());
        e.setPauseReason(run.pauseReason());
        e.setOutcome(run.outcome());
        return toDomain(runs.save(e));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentRun> findById(String id)
    {
        return runs.findById(id).map(SqliteAgentRunStore::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentRun> findByWorkspace(String workspaceId)
    {
        return runs.findByWorkspaceIdOrderByStartedAtMsDesc(workspaceId).stream()
                .map(SqliteAgentRunStore::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentRun> findByThread(String threadId)
    {
        return runs.findByThreadIdOrderByStartedAtMsDesc(threadId).stream()
                .map(SqliteAgentRunStore::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentRun> findByReviewRound(String reviewRoundId)
    {
        return runs.findByReviewRoundIdOrderByStartedAtMsAsc(reviewRoundId).stream()
                .map(SqliteAgentRunStore::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentRun> findByTask(String taskId, String kind, String parentStageId)
    {
        return runs.findByTaskIdOrderByStartedAtMsDesc(taskId).stream()
                .map(SqliteAgentRunStore::toDomain)
                .filter(r -> kind == null || kind.equals(r.kind()))
                .filter(r -> parentStageId == null || parentStageId.equals(r.parentStageId()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentRun> findLiveByTask(String taskId)
    {
        return runs.findByTaskIdOrderByStartedAtMsDesc(taskId).stream()
                .map(SqliteAgentRunStore::toDomain)
                .filter(AgentRun::isLive)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentRun> findLiveByTaskAndKind(String taskId, String kind)
    {
        return runs.findByTaskIdOrderByStartedAtMsDesc(taskId).stream()
                .map(SqliteAgentRunStore::toDomain)
                .filter(AgentRun::isLive)
                .filter(r -> Objects.equals(kind, r.kind()))
                .findFirst();
    }

    private static AgentRun toDomain(AgentRunEntity e)
    {
        return new AgentRun(
                e.getId(),
                e.getTaskId(),
                e.getKind(),
                e.getSource(),
                e.getParentStageId(),
                e.getReviewRoundId(),
                e.getStageId(),
                e.getStatus(),
                e.getIterations(),
                e.getBudget(),
                e.getHeadline(),
                e.getMetricsJson(),
                Instant.ofEpochMilli(e.getStartedAtMs()),
                instantOrNull(e.getFinishedAtMs()),
                e.getWorkspaceId(),
                e.getThreadId(),
                e.getProvider(),
                e.getModel(),
                e.getCostUsdMilli(),
                e.getTokensIn(),
                e.getTokensOut(),
                e.getStepCursor(),
                e.getLaunchInput(),
                e.getPauseReason(),
                e.getOutcome());
    }

    private static Long epochOrNull(Instant instant)
    {
        return instant == null ? null : instant.toEpochMilli();
    }

    private static Instant instantOrNull(Long epochMs)
    {
        return epochMs == null ? null : Instant.ofEpochMilli(epochMs);
    }
}
