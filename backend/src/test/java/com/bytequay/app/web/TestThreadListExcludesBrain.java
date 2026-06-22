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
package com.bytequay.app.web;

import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.ThreadStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The workspace thread list ({@code GET /api/threads}) must omit
 * {@link ThreadKind#BRAIN_AGENT} threads — those are per-task internal
 * children reached through the task brain view, not top-level threads.
 */
@SpringBootTest
class TestThreadListExcludesBrain
{
    @Autowired
    private ThreadController controller;
    @Autowired
    private ThreadStore threads;

    @Test
    void listOmitsBrainAgentThreads()
    {
        // ws-default is the seeded workspace (threads.workspace_id is an FK).
        String ws = "ws-default";
        String cliId = save(ThreadKind.CLI_AGENT, ws);
        String brainId = save(ThreadKind.BRAIN_AGENT, ws);

        List<Thread> result = controller.list(null, null, ws, 50);

        // Scope assertions to our own ids — the shared workspace may hold
        // threads from other tests, but a brain thread must never appear.
        assertThat(result).extracting(Thread::id).contains(cliId).doesNotContain(brainId);
    }

    private String save(ThreadKind kind, String workspaceId)
    {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        threads.saveThread(new Thread(
                id, kind, /* provider */ "claude-code", /* agentSessionId */ null,
                kind + " fixture", ThreadStatus.IDLE, /* model */ "test",
                0L, 0L, 0L, now, now, /* endedAt */ null, /* errorMessage */ null,
                ThreadFlow.BUILD, workspaceId, /* workModel */ null, /* activeTask */ null));
        return id;
    }
}
