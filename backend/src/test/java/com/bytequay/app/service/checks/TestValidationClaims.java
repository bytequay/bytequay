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
import com.bytequay.app.repository.sqlite.SqliteValidationPassStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The claim/lease contract on {@code validation_pass}: idempotent claim
 * insert, single live owner via CAS, completion only by the owner of the
 * exact fingerprint, and cancel/supersede blocking further acquisition.
 */
@SpringBootTest
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestValidationClaims
{
    private static final Instant NOW = Instant.parse("2026-07-25T09:00:00Z");

    @Autowired
    private SqliteValidationPassStore store;
    @Autowired
    private TaskStore taskStore;
    @Autowired
    private ThreadStore threadStore;

    @Test
    void claimInsertIsIdempotentPerKey()
    {
        String key = claimKey();
        String taskId = seedTask();

        assertThat(store.insertClaim(key, taskId, "dev-round", null, "fp-1", null, null, NOW))
                .isPresent();
        assertThat(store.insertClaim(key, taskId, "dev-round", null, "fp-1", null, null, NOW))
                .isEmpty();
        assertThat(store.findByClaimKey(key)).isPresent();
    }

    @Test
    void onlyOneLiveOwnerAndOnlyTheOwnerOfTheFingerprintCompletes()
    {
        String key = claimKey();
        String taskId = seedTask();
        store.insertClaim(key, taskId, "dev-round", null, "fp-1", null, null, NOW);

        assertThat(store.acquireOwner(key, "owner-a", "pid-1", NOW.plusSeconds(120), NOW)).isTrue();
        assertThat(store.acquireOwner(key, "owner-b", "pid-2", NOW.plusSeconds(120), NOW)).isFalse();

        // Expired lease may be reclaimed by a new owner.
        Instant afterLease = NOW.plusSeconds(300);
        assertThat(store.acquireOwner(key, "owner-b", "pid-2", afterLease.plusSeconds(120), afterLease))
                .isTrue();

        // The evicted owner's completion is discarded; the current owner
        // with the exact fingerprint wins exactly once.
        assertThat(store.completeOwned(key, "owner-a", "fp-1", afterLease, true, "[]")).isFalse();
        assertThat(store.completeOwned(key, "owner-b", "fp-other", afterLease, true, "[]")).isFalse();
        assertThat(store.completeOwned(key, "owner-b", "fp-1", afterLease, true, "[]")).isTrue();
        assertThat(store.completeOwned(key, "owner-b", "fp-1", afterLease, true, "[]")).isFalse();

        ValidationClaim done = store.findByClaimKey(key).orElseThrow();
        assertThat(done.isTerminalGreen()).isTrue();
    }

    @Test
    void cancelAndSupersedeBlockAcquisitionAndLeaveResumableSet()
    {
        String cancelled = claimKey();
        String superseded = claimKey();
        String resumable = claimKey();
        String taskId = seedTask();
        store.insertClaim(cancelled, taskId, "dev-round", null, "fp-1", null, null, NOW);
        store.insertClaim(superseded, taskId, "dev-round", null, "fp-2", null, null, NOW);
        store.insertClaim(resumable, taskId, "dev-round", null, "fp-3", null, null, NOW);

        assertThat(store.requestCancel(cancelled, NOW, NOW.plusSeconds(30))).isTrue();
        assertThat(store.markSuperseded(superseded, NOW)).isTrue();

        assertThat(store.acquireOwner(cancelled, "o", "p", NOW.plusSeconds(120), NOW)).isFalse();
        assertThat(store.acquireOwner(superseded, "o", "p", NOW.plusSeconds(120), NOW)).isFalse();

        assertThat(store.findResumableStarted(NOW))
                .extracting(ValidationClaim::claimKey)
                .contains(resumable)
                .doesNotContain(cancelled, superseded);
    }

    @Test
    void cancellationStopsLeaseRenewal()
    {
        String key = claimKey();
        String taskId = seedTask();
        store.insertClaim(key, taskId, "dev-round", null, "fp-1", null, null, NOW);
        assertThat(store.acquireOwner(
                key, "owner-a", "pid-1", NOW.plusSeconds(120), NOW)).isTrue();

        store.requestCancel(key, NOW.plusSeconds(1), NOW.plusSeconds(30));

        assertThat(store.renewLease(
                key, "owner-a", NOW.plusSeconds(240), NOW.plusSeconds(2))).isFalse();
    }

    private static String claimKey()
    {
        return "test-claim:" + UUID.randomUUID();
    }

    private String seedTask()
    {
        Thread thread = new Thread(
                UUID.randomUUID().toString(), ThreadKind.CLI_AGENT, "claude-code",
                null, "Validation claim test", ThreadStatus.RUNNING, "claude-sonnet-4.6",
                0L, 0L, 0L, NOW, NOW, null, null, ThreadFlow.BUILD, "ws-default", null, null);
        threadStore.saveThread(thread);
        String taskId = UUID.randomUUID().toString();
        taskStore.saveTask(new Task(
                taskId, thread.id(), 1L, TaskStatus.RUNNING, "feature", null, "main", "/tmp",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, NOW, null, null, null, null, null));
        return taskId;
    }
}
