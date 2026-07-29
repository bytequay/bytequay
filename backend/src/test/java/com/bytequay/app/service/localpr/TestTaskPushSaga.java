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
package com.bytequay.app.service.localpr;

import com.bytequay.app.developmentflow.execution.RetiredSagaGate;
import com.bytequay.app.repository.LocalReviewSubmissionStore;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.ReviewRoundStore;
import com.bytequay.app.repository.TaskPushStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.checks.CodeFingerprints;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.threads.NotificationService;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.bytequay.app.service.threads.TaskPhaseMachine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class TestTaskPushSaga
{
    private PRService prs;
    private TaskStore tasks;
    private TaskPushStore pushes;
    private TaskPushSaga saga;

    @BeforeEach
    void setUp()
    {
        prs = mock(PRService.class);
        tasks = mock(TaskStore.class);
        pushes = mock(TaskPushStore.class);
        saga = new TaskPushSaga(
                prs,
                tasks,
                mock(WatchedRepoStore.class),
                mock(GitRunner.class),
                mock(PullRequestRepository.class),
                mock(PatResolver.class),
                mock(ReviewRoundStore.class),
                mock(CodeFingerprints.class),
                pushes,
                mock(TaskCommandExecutor.class),
                mock(TaskPhaseMachine.class),
                mock(NotificationService.class),
                mock(LocalReviewSubmissionStore.class),
                mock(RetiredSagaGate.class),
                mock(ObjectMapper.class));
    }

    @Test
    void everyLegacyMutationEntryPointFailsClosedBeforeStorageOrIo()
    {
        assertRetired(() -> saga.push("pr-1", false));
        assertRetired(() -> saga.drive("token-1"));
        assertRetired(() -> saga.adoptRemotePullRequest(
                "task-1", "octo/repo", 7, "https://example.test/pr/7"));
        assertRetired(() -> saga.prepareRecovery("task-1", 1));
        assertRetired(() -> saga.verifyRecoveryRequest("task-1"));
        assertRetired(() -> saga.resumeExternalSagaInCommand(
                new TaskPushSaga.RecoveryPlan(
                        "token-1", "push_branch", "failed", 1, "head", "fingerprint")));
        assertRetired(saga::reconcileActive);
        assertRetired(() -> saga.revokeUnclaimedInCommand("task-1", "replan"));

        verifyNoInteractions(prs, tasks, pushes);
    }

    private static void assertRetired(Runnable action)
    {
        assertThatThrownBy(action::run)
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("retired")
                .hasMessageContaining("typed remote runtime");
    }
}
