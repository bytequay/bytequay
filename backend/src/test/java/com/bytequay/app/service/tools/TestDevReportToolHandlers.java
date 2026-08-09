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
package com.bytequay.app.service.tools;

import com.bytequay.app.domain.DevReport;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.sqlite.SqliteStageStore;
import com.bytequay.app.service.review.DevReportServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestDevReportToolHandlers
{
    private static final String TASK_ID = "t1.k1";
    private static final UUID DEV_STAGE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final Instant NOW = Instant.parse("2026-07-05T00:00:00Z");

    private final DevReportServiceImpl devReports = mock(DevReportServiceImpl.class);
    private final SqliteStageStore stageStore = mock(SqliteStageStore.class);
    private final ThreadStore threadStore = mock(ThreadStore.class);
    private final DevReportToolHandlers tools =
            new DevReportToolHandlers(devReports, stageStore, threadStore, new ObjectMapper().findAndRegisterModules());

    @Test
    void readDevConversationSearchesTheDevStageOnlyByQuery()
    {
        when(stageStore.findStagesByTask(TASK_ID)).thenReturn(List.of(
                stage(DEV_STAGE_ID, StageType.DEVELOPMENT_STAGE),
                stage(UUID.randomUUID(), StageType.REVIEW_MONITOR_STAGE)));
        when(threadStore.listStageMessagesByTask(TASK_ID)).thenReturn(List.of(
                message("m1", 1, DEV_STAGE_ID.toString(), "{\"text\":\"chose retries over a queue\"}"),
                message("m2", 2, DEV_STAGE_ID.toString(), "{\"text\":\"unrelated message\"}"),
                message("m3", 3, UUID.randomUUID().toString(), "{\"text\":\"retries mentioned in review\"}")));

        ToolOutcome.Completed out = completed(tools.readDevConversation(
                new DevReportToolHandlers.ReadDevConversationArgs("retries", null), call()));

        assertThat(out.isError()).isFalse();
        assertThat(out.text()).contains("chose retries over a queue")
                .doesNotContain("unrelated message")
                .doesNotContain("retries mentioned in review");
    }

    @Test
    void readDevConversationWithNoQueryReturnsMostRecentMessages()
    {
        when(stageStore.findStagesByTask(TASK_ID)).thenReturn(List.of(stage(DEV_STAGE_ID, StageType.DEVELOPMENT_STAGE)));
        when(threadStore.listStageMessagesByTask(TASK_ID)).thenReturn(List.of(
                message("m1", 1, DEV_STAGE_ID.toString(), "{\"text\":\"oldest\"}"),
                message("m2", 2, DEV_STAGE_ID.toString(), "{\"text\":\"newest\"}")));

        ToolOutcome.Completed out = completed(tools.readDevConversation(
                new DevReportToolHandlers.ReadDevConversationArgs(null, 1), call()));

        assertThat(out.text()).contains("newest").doesNotContain("oldest");
    }

    @Test
    void readDevReportReturnsTheRecordedReportJson()
    {
        DevReport report = new DevReport(
                "report1", TASK_ID, "summary here", List.of(), List.of(), List.of(), List.of(), List.of(), NOW);
        when(devReports.find(TASK_ID)).thenReturn(Optional.of(report));

        ToolOutcome.Completed out = completed(tools.readDevReport(
                new DevReportToolHandlers.ReadDevReportArgs(), call()));

        assertThat(out.text()).contains("summary here");
    }

    @Test
    void readDevReportReportsWhenNoneRecorded()
    {
        when(devReports.find(TASK_ID)).thenReturn(Optional.empty());

        ToolOutcome.Completed out = completed(tools.readDevReport(
                new DevReportToolHandlers.ReadDevReportArgs(), call()));

        assertThat(out.text()).contains("no dev report recorded");
    }

    private static ToolCall call()
    {
        return new ToolCall(ThreadScope.TASK, "thread-1", NullNode.getInstance(), AgentRole.TASK, TASK_ID, null);
    }

    private static ToolOutcome.Completed completed(ToolOutcome outcome)
    {
        return (ToolOutcome.Completed) outcome;
    }

    private static StageInstance stage(UUID id, StageType type)
    {
        return new StageInstance(id, TASK_ID, type, StageState.CLOSED, NOW, NOW, null);
    }

    private static ThreadMessage message(String id, long seq, String stageId, String contentJson)
    {
        return new ThreadMessage(
                id, "thread-1", TASK_ID, seq, "assistant", "text", contentJson,
                null, null, null, null, NOW, stageId, ThreadScope.STAGE);
    }
}
