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

import com.bytequay.app.repository.ReviewRoundStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ValidationPassStore;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class TestValidationClaimRetirement
{
    private final ValidationPassStore claims = mock(ValidationPassStore.class);
    private final TaskStore tasks = mock(TaskStore.class);
    private final ThreadStore threads = mock(ThreadStore.class);
    private final ReviewRoundStore rounds = mock(ReviewRoundStore.class);
    private final ValidationPassService validation = mock(ValidationPassService.class);
    private final CodeFingerprints fingerprints = mock(CodeFingerprints.class);
    private final ValidationExecutorRegistry executors = mock(ValidationExecutorRegistry.class);
    private final TaskCommandExecutor commands = mock(TaskCommandExecutor.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ValidationClaimService service = new ValidationClaimService(
            claims, tasks, threads, rounds, validation, fingerprints,
            executors, commands, events, new ObjectMapper());

    @Test
    void everyLegacyEntryPointRejectsBeforeClaimOrExecution()
    {
        assertRetired(() -> service.claimAndRunDevRound("task-1"));
        assertRetired(() -> service.onValidationRecheckRequested(null));
        assertRetired(() -> service.claimAndRunLocalReview("task-1", 1, "roots"));
        assertRetired(() -> service.claimAndRunReviewRound("task-1", "round-1", "attempt-1"));
        assertRetired(() -> service.claimAndRunGateRevalidation("round-1"));
        assertRetired(service::reconcileClaims);

        verifyNoInteractions(
                claims, tasks, threads, rounds, validation, fingerprints,
                executors, commands, events);
    }

    private static void assertRetired(Runnable action)
    {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(409));
    }
}
