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
package com.bytequay.app.service.checks;

import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.ValidationClaim;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ValidationPassStore;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The claimed dev-round flow end to end against the real store: claim
 * inserted and committed first, checks executed by the single admitted
 * owner outside any transaction, terminal CAS once, and the finished
 * event published with the pass outcome. Terminal claims replay their
 * event instead of re-running.
 */
@SpringBootTest
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestValidationClaimService
{
    private static final Instant NOW = Instant.parse("2026-07-25T09:00:00Z");

    @Autowired
    private ValidationPassStore store;
    @Autowired
    private TaskStore taskStore;
    @Autowired
    private ThreadStore threadStore;
    @Autowired
    private TaskCommandExecutor commands;
    @Autowired
    private ObjectMapper mapper;

    @Test
    void claimsRunsAndPublishesExactlyOnce()
            throws Exception
    {
        String taskId = seedTask("/tmp/claimed-worktree");
        ValidationPassService checks = mock(ValidationPassService.class);
        when(checks.runChecks(taskId)).thenReturn(List.of());
        CodeFingerprints fingerprints = mock(CodeFingerprints.class);
        when(fingerprints.fingerprint(any(Path.class))).thenReturn("fp-" + taskId);
        List<Object> published = new CopyOnWriteArrayList<>();
        ApplicationEventPublisher events = published::add;

        ValidationClaimService service = new ValidationClaimService(
                store, taskStore, checks, fingerprints,
                new ValidationExecutorRegistry(), commands, events, mapper);

        service.claimAndRunDevRound(taskId);

        String claimKey = "dev-round:" + taskId + ":fp-" + taskId;
        ValidationClaim claim = awaitTerminal(claimKey);
        assertThat(claim.isTerminalGreen()).isTrue();
        assertThat(published)
                .filteredOn(ValidationPassFinishedEvent.class::isInstance)
                .hasSize(1);

        // A second call for the same fingerprint replays the finished
        // event from the terminal claim without another run.
        service.claimAndRunDevRound(taskId);
        assertThat(published)
                .filteredOn(ValidationPassFinishedEvent.class::isInstance)
                .hasSize(2);
        assertThat(((ValidationPassFinishedEvent) published.get(1)).passed()).isTrue();
    }

    private ValidationClaim awaitTerminal(String claimKey)
            throws InterruptedException
    {
        for (int i = 0; i < 100; i++) {
            ValidationClaim claim = store.findByClaimKey(claimKey).orElse(null);
            if (claim != null && claim.endedAt() != null) {
                return claim;
            }
            java.lang.Thread.sleep(50);
        }
        throw new AssertionError("claim " + claimKey + " never reached a terminal state");
    }

    private String seedTask(String worktree)
    {
        Thread thread = new Thread(
                UUID.randomUUID().toString(), ThreadKind.CLI_AGENT, "claude-code",
                null, "Claim service test", ThreadStatus.RUNNING, "claude-sonnet-4.6",
                0L, 0L, 0L, NOW, NOW, null, null, ThreadFlow.BUILD, "ws-default", null, null);
        threadStore.saveThread(thread);
        String taskId = UUID.randomUUID().toString();
        taskStore.saveTask(new Task(
                taskId, thread.id(), 1L, TaskStatus.RUNNING, "feature", worktree, "main", "/tmp",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, NOW, null, null, null, null, null));
        return taskId;
    }
}
