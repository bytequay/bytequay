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
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestAgentRunService
{
    private static final Instant NOW = Instant.parse("2026-07-29T00:00:00Z");

    private final AgentRunStore store = mock(AgentRunStore.class);
    private final AgentRunServiceImpl service = new AgentRunServiceImpl(
            store, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void legacyCreationAndMutationPortsFailClosedWithoutStorageWrites()
    {
        List<ThrowingCallable> retiredCalls = List.of(
                () -> service.openInCommand("task", "dev", null, null,
                        StageType.DEVELOPMENT_STAGE, null),
                () -> service.openInStageInCommand("task", "dev", null, "stage", null),
                () -> service.openSchedulerSessionInCommand(
                        mock(Thread.class), "task", "stage", "dev", "prompt"),
                () -> service.pauseInCommand("task", "run", "reason"),
                () -> service.restartInCommand("task", "run"),
                () -> service.transitionInCommand(
                        "task", "run", AgentRun.STATUS_SUCCEEDED, "done"));

        for (ThrowingCallable call : retiredCalls) {
            assertThatThrownBy(call)
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("AgentRun execution is retired");
        }
        verify(store, never()).insert(any());
        verify(store, never()).save(any());
    }

    @Test
    void createsOneAlreadyTerminalDetachedReviewCompatibilityHeader()
    {
        when(store.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AgentRun header = service.createReviewCompatibilityHeader(
                "round-1", 25);

        assertThat(header.taskId()).isNull();
        assertThat(header.kind()).isEqualTo(AgentRun.KIND_REVIEW_COMPATIBILITY_HEADER);
        assertThat(header.source()).isEqualTo(AgentRun.SOURCE_V2_REVIEW_FOREIGN_KEY);
        assertThat(header.reviewRoundId()).isEqualTo("round-1");
        assertThat(header.status()).isEqualTo(AgentRun.STATUS_SUCCEEDED);
        assertThat(header.startedAt()).isEqualTo(NOW);
        assertThat(header.finishedAt()).isEqualTo(NOW);
        assertThat(header.workspaceId()).isNull();
        assertThat(header.threadId()).isNull();
        assertThat(header.provider()).isNull();
        assertThat(header.model()).isNull();
        assertThat(header.launchInput()).isNull();
        assertThat(header.isLive()).isFalse();
        assertThat(header.isReviewCompatibilityHeader()).isTrue();
        assertThat(header.outcome()).isEqualTo("completed");
    }

    @Test
    void compatibilityHeadersStayOutOfLegacySessionAndTaskFeeds()
    {
        AgentRun historical = historical("historical");
        AgentRun header = header("header");
        when(store.findByWorkspace("workspace")).thenReturn(List.of(header, historical));
        when(store.findByThread("thread")).thenReturn(List.of(header, historical));
        when(store.findByTask("task", null, null)).thenReturn(List.of(header, historical));
        when(store.findLiveByTask("task")).thenReturn(List.of(header, historical));

        assertThat(service.findByWorkspace("workspace")).containsExactly(historical);
        assertThat(service.findByThread("thread")).containsExactly(historical);
        assertThat(service.findByTask("task", null, null)).containsExactly(historical);
        assertThat(service.liveRunsByTask("task")).containsExactly(historical);
    }

    @Test
    void reviewInternalsCanResolveTheCompatibilityForeignKeyHeader()
    {
        AgentRun header = header("header");
        when(store.findById("header")).thenReturn(Optional.of(header));
        when(store.findByReviewRound("round-1")).thenReturn(List.of(header));

        assertThat(service.findById("header")).isEmpty();
        assertThat(service.findReviewCompatibilityHeaderById("header")).contains(header);
        assertThat(service.findByReviewRound("round-1")).containsExactly(header);
    }

    private static AgentRun historical(String id)
    {
        return new AgentRun(
                id, "task", AgentRun.KIND_DEV, null, null, null, null,
                AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
    }

    private static AgentRun header(String id)
    {
        return new AgentRun(
                id, null, AgentRun.KIND_REVIEW_COMPATIBILITY_HEADER,
                AgentRun.SOURCE_V2_REVIEW_FOREIGN_KEY, null, "round-1", null,
                AgentRun.STATUS_SUCCEEDED, 0, null, null, null, NOW, NOW);
    }
}
