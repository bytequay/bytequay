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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestClaudeCodeCliThreadAgent
{
    private static final String CWD = "/tmp/wt-claude";
    private static final Instant NOW = Instant.parse("2026-06-17T12:00:00Z");

    private final ThreadStore threadStore = mock(ThreadStore.class);
    private final TaskStore taskStore = mock(TaskStore.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void reasoningEffortOverrideIsPassedToClaude()
    {
        when(threadStore.listMessages(anyString())).thenReturn(List.of());
        Thread thread = new Thread(
                "thread-1", ThreadKind.CLI_AGENT, "claude-code", /* agentSessionId */ null,
                "Claude trunk test", ThreadStatus.IDLE, "claude-sonnet-4-6",
                0L, 0L, 0L,
                NOW, NOW, null, null,
                ThreadFlow.BUILD, "ws-default", null, null);
        ClaudeCodeCliThreadAgent agent = new ClaudeCodeCliThreadAgent(
                thread, threadStore, taskStore, new StreamJsonParser(mapper), mapper,
                mock(McpPermissionGate.class), mock(ExecutorService.class),
                mock(CheckpointTrigger.class), () -> "", null, null, CWD,
                ClaudeCodeCliThreadAgent.TrunkMode.ENABLED, "high");

        assertThat(agent.buildCommand("plan").command())
                .containsSubsequence("--effort", "high");
    }
}
