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
package com.bytequay.app.developmentflow.stage;

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairTurnStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRepairTurnStore.RepairContext;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.BaseRepairAuthorization;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.CiBudgets;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.CiEffectDelivery;
import com.bytequay.app.developmentflow.stage.persistence.SqliteRemoteRuntimeStore.CiEpisode;
import com.bytequay.app.developmentflow.task.TaskManager;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class TestCiRepairBrainRetirement
{
    private static final Instant NOW = Instant.ofEpochMilli(1_000);
    private static final Path MAIN_JAVA = Path.of("src/main/java");

    @Test
    void baseRewriteValidationPushesDirectlyWithoutTaskBrain()
            throws Exception
    {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite::memory:");
        TaskCommandExecutor commands = new TaskCommandExecutor(
                new DataSourceTransactionManager(dataSource));
        TaskManager tasks = mock(TaskManager.class);
        SqliteRemoteRuntimeStore remote = mock(SqliteRemoteRuntimeStore.class);
        SqliteRemoteRepairTurnStore turns =
                mock(SqliteRemoteRepairTurnStore.class);
        ObjectMapper json = new ObjectMapper();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        RemoteRepairTurnRuntime repairTurns = new RemoteRepairTurnRuntime(
                commands, tasks, remote, turns, json, clock, 8080);
        RemoteCiRepairRuntimeCoordinator repairs =
                new RemoteCiRepairRuntimeCoordinator(
                        commands, remote,
                        ignored -> RemoteCiRepairRuntimeCoordinator
                                .Classification.BASE_DETERMINISTIC,
                        new CiBudgets(0, 2, 2, 2), repairTurns, json, clock);

        CiEpisode episode = new CiEpisode(
                "episode-1", "stage-1", "task-1", 1, 1, "binding-1",
                "failed-evaluation-1", "head-1", "base-1",
                "BASE_DETERMINISTIC", "VALIDATING",
                0, 0, 1, 2, 0, 2, 0, 2,
                null, null, null, NOW, null, null);
        CiEffectDelivery delivery = new CiEffectDelivery(
                "validation-row-1", "validation-1", episode.id(),
                "VALIDATE",
                RemoteEffectOperationHandler.REWRITE_VALIDATE_BASE_CI_REPAIR,
                "authorization-1", null, episode.taskId(),
                episode.taskEpoch(), episode.stageId(),
                episode.stageGeneration(), 1, "fingerprint-2", "head-2",
                "base-1", episode.status(), episode.subjectHeadSha(),
                episode.subjectBaseSha(), 0, 0, 1, 2, 0, 2,
                null, null, true);
        RepairContext context = new RepairContext(
                "workspace-1", "trunk-1", episode.taskId(),
                episode.taskEpoch(), 1, episode.stageId(),
                episode.stageGeneration(), 1, "/tmp/task-1", "{}",
                "openai", "gpt", "ci-fix", "policy-1", true,
                "fingerprint-3", "head-3", "base-1", "head-1", "base-1");
        BaseRepairAuthorization authorization = new BaseRepairAuthorization(
                "authorization-1", episode.id(), "manifest-1", 1,
                "AUTO_APPROVE_POLICY", "policy-1", null, "command-1",
                null, "repair base-owned CI", episode.failedCiEvaluationId(),
                "snapshot-1", "head-1", "head-1", "base-1",
                "manifest-digest", "CLAIMED", NOW, null, null);
        DispatchTicket.OperationFence fence = new DispatchTicket.OperationFence(
                episode.taskEpoch(), episode.stageId(),
                episode.stageGeneration(), delivery.operationId(),
                delivery.semanticAttempt(), delivery.expectedCodeFingerprint(),
                delivery.expectedHeadSha(), delivery.expectedBaseSha());
        RemoteEffectOperationHandler.Result result =
                new RemoteEffectOperationHandler.Result(
                        1, delivery.operationId(),
                        RemoteEffectOperationHandler.Disposition.SUCCEEDED,
                        context.codeFingerprint(), context.headSha(),
                        context.baseSha(), "exact base rewrite proof", null);
        String payload = json.writeValueAsString(result);

        when(remote.requireEffectTaskId(delivery.operationId()))
                .thenReturn(episode.taskId());
        when(remote.findCiEffectReceipt(delivery.operationId()))
                .thenReturn(Optional.empty());
        when(remote.requireCiEffectDelivery(delivery.operationId()))
                .thenReturn(delivery);
        when(remote.requireCiEpisode(episode.taskId(), episode.id()))
                .thenReturn(episode);
        when(turns.requireContext(episode.taskId(), episode.stageId()))
                .thenReturn(context);
        when(remote.findClaimedBaseRepairAuthorization(episode.id()))
                .thenReturn(Optional.of(authorization));

        DispatchTicket.DeliveryReceipt receipt = repairs.deliverEffect(
                new DispatchTicket.OwnerReference(
                        DispatchTicket.OwnerKind.STAGE, episode.stageId(),
                        "REMOTE_CI_BASE_REWRITE_VALIDATION_RESULT"),
                fence, new DispatchTicket.DispatchResult(
                        fence, DispatchTicket.Outcome.SUCCEEDED,
                        payload, payload, null));

        assertThat(receipt.acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        verify(turns).requireContext(episode.taskId(), episode.stageId());
        verify(turns).insertCiPush(context, episode, authorization.id(), NOW);
        verifyNoMoreInteractions(turns);
        verifyNoInteractions(tasks);
    }

    @Test
    void productionCannotCreateFreshCiBrainTurnsButHistoricalDeliveryRemains()
            throws IOException
    {
        Path historicalStore = MAIN_JAVA.resolve(
                "com/bytequay/app/developmentflow/stage/persistence/"
                        + "SqliteRemoteRepairTurnStore.java");
        List<Path> files;
        try (var paths = Files.walk(MAIN_JAVA)) {
            files = paths.filter(Files::isRegularFile).toList();
        }
        for (Path file : files) {
            if (!file.equals(historicalStore)) {
                assertThat(Files.readString(file))
                        .as(MAIN_JAVA.relativize(file).toString())
                        .doesNotContain("insertCiBrain(");
            }
        }

        String runtime = Files.readString(MAIN_JAVA.resolve(
                "com/bytequay/app/developmentflow/stage/"
                        + "RemoteRepairTurnRuntime.java"));
        assertThat(runtime).contains(
                "CI_BRAIN_CALLBACK = \"REMOTE_CI_BRAIN_RESULT\"",
                "return deliverBrain(raw, context, rawDigest);");
    }
}
