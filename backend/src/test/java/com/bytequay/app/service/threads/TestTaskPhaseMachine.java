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

import com.bytequay.app.domain.Actor;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.service.checks.ValidationPassFinishedEvent;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class TestTaskPhaseMachine
{
    private final TaskPhaseMachine machine = new TaskPhaseMachine();

    @Test
    void everyLegacyLifecycleMutationFailsClosed()
    {
        List<ThrowingCallable> calls = List.of(
                () -> machine.transition("task", TaskPhase.VALIDATING, "reason", Actor.HUMAN),
                () -> machine.transitionInCommand(
                        "task", TaskPhase.VALIDATING, "reason", Actor.HUMAN),
                () -> machine.spendLocalShipAuthorizationInCommand("task", Actor.HUMAN),
                () -> machine.finalizeLocalShipInCommand("task", Actor.HUMAN, "reason"),
                () -> machine.invalidateLocalShipInCommand("task", Actor.HUMAN, "reason"),
                () -> machine.parkForLocalReviewInCommand("task", Actor.HUMAN, "reason"),
                () -> machine.resumeFromLocalReviewInCommand("task", Actor.HUMAN, "reason"),
                () -> machine.markRemoteInReviewInCommand("task", Actor.HUMAN, "reason"),
                () -> machine.parkOperational("task", Actor.HUMAN, "reason"),
                () -> machine.parkOperationalInCommand("task", Actor.HUMAN, "reason"),
                () -> machine.requestRecovery("task", "normal"),
                () -> machine.requestRecoveryInCommand("task", "normal"),
                () -> machine.requestRecoveryInCommand("task", "normal", "{}"),
                () -> machine.rejectRecoveryRequest("task", "request", "reason"),
                () -> machine.rejectRecoveryRequestInCommand(
                        "task", "request", "reason"),
                () -> machine.completeRecovery(
                        "task", Actor.HUMAN, "reason", TaskPhase.IMPLEMENTING),
                () -> machine.completeRecoveryInCommand(
                        "task", Actor.HUMAN, "reason", TaskPhase.IMPLEMENTING),
                () -> machine.completeExternalSagaRecoveryInCommand(
                        "task", Actor.HUMAN, "reason", TaskPhase.IMPLEMENTING),
                () -> machine.completeReplanInCommand("task", Actor.HUMAN, "reason"),
                () -> machine.retryErrored("task", "turn"),
                () -> machine.retryErroredInCommand("task", "turn"),
                () -> machine.archiveIdle("task"),
                () -> machine.archiveIdleInCommand("task"),
                () -> machine.reviveArchived("task"),
                () -> machine.reviveArchivedInCommand("task"),
                () -> machine.resumeIdleRuntimeInCommand("task"),
                () -> machine.onValidationFinished(mock(ValidationPassFinishedEvent.class)),
                () -> machine.observe("task", TaskPhase.VALIDATING, "reason"),
                () -> machine.observeRemoteOpened("task", "reason"),
                () -> machine.observeRemoteOpenedInCommand("task", "reason"),
                () -> machine.observeRemoteCiGreen("task", false, "reason"),
                () -> machine.observeRemoteCiGreenInCommand("task", false, "reason"),
                () -> machine.observeReady("task", "reason"),
                () -> machine.observeReadyInCommand("task", "reason"),
                () -> machine.observeInCommand("task", TaskPhase.VALIDATING, "reason"),
                () -> machine.finishTerminal(
                        "task", TaskStatus.COMPLETED, Actor.HUMAN, "reason"),
                () -> machine.finishTerminalInCommand(
                        "task", TaskStatus.COMPLETED, Actor.HUMAN, "reason"),
                () -> machine.pause("task", Actor.HUMAN, "reason"),
                () -> machine.pauseInCommand("task", Actor.HUMAN, "reason"),
                () -> machine.requestResume("task"),
                () -> machine.requestResumeInCommand("task"),
                () -> machine.completeResume("task", Actor.HUMAN, "reason"),
                () -> machine.completeResumeInCommand("task", Actor.HUMAN, "reason"));

        for (ThrowingCallable call : calls) {
            assertThatThrownBy(call)
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("TaskPhaseMachine is retired");
        }
    }

    @Test
    void compatibilityLockOwnsNoLifecycleState()
    {
        assertThat(TaskPhaseMachine.withTaskLock("task", () -> "value"))
                .isEqualTo("value");
    }
}
