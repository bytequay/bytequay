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
import com.bytequay.app.repository.ThreadStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for the trunk-altitude session routing in
 * {@link ThreadService}. A 0-task (planning) thread has no task to
 * build a task-mode CLI agent from, so {@code sessionOrThrow} must
 * route to the trunk-scope agent — otherwise every session-scoped op
 * at the trunk (subscribe, interrupt, the permission-prompt / budget
 * path behind gated MCP tools like list_skills) throws
 * "thread … has no task; cannot spawn CLI agent".
 */
@SpringBootTest
class TestThreadServiceTrunkSession
{
    @Autowired
    private ThreadService threads;
    @Autowired
    private ThreadStore threadStore;
    @Autowired
    private ThreadRegistry registry;

    @Test
    void subscribeAtTrunkBuildsTheTrunkAgentNotTheTaskAgent()
    {
        String threadId = newTrunkThread();

        // Before the fix this threw "has no task; cannot spawn CLI
        // agent" — sessionOrThrow always tried the task-mode builder.
        Runnable unsubscribe = threads.subscribe(threadId, event -> {});

        assertThat(unsubscribe).isNotNull();
        // The trunk-scope session is the one that got built; the
        // task-scope map stays empty for a 0-task thread.
        assertThat(registry.findTrunk(threadId)).isPresent();
        assertThat(registry.find(threadId)).isEmpty();

        unsubscribe.run();
        registry.evictTrunk(threadId);
    }

    private String newTrunkThread()
    {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Thread thread = new Thread(
                id,
                ThreadKind.CLI_AGENT,
                /* provider */ "claude-code",
                /* agentSessionId */ null,
                "Trunk session fixture",
                ThreadStatus.IDLE,
                /* model */ "test",
                0L, 0L, 0L,
                now, now, null, null,
                ThreadFlow.BUILD,
                /* workspaceId */ "ws-default",
                /* workModel */ null,
                /* activeTask */ null);
        threadStore.saveThread(thread);
        return id;
    }
}
