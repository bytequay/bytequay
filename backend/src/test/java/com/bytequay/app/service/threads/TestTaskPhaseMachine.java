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
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.TaskStore;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestTaskPhaseMachine
{
    private final TaskStore taskStore = mock(TaskStore.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final TaskPhaseMachine machine =
            new TaskPhaseMachine(taskStore, notifications, events);

    @Test
    void legalForwardTransitionPersistsAuditsAndPublishes()
    {
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(taskAt("t1", TaskPhase.IMPLEMENTING)));

        machine.transition("t1", TaskPhase.VALIDATING, "ready_for_checks", Actor.AGENT);

        verify(taskStore).updatePhase("t1", TaskPhase.VALIDATING);
        verify(taskStore).appendPhaseEvent(eq("t1"), eq(TaskPhase.IMPLEMENTING),
                eq(TaskPhase.VALIDATING), any(), eq("ready_for_checks"), eq(Actor.AGENT));
        verify(events).publishEvent(any(TaskPhaseTransitionedEvent.class));
    }

    @Test
    void illegalForwardTransitionThrowsAndPersistsNothing()
    {
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(taskAt("t1", TaskPhase.IMPLEMENTING)));

        assertThatThrownBy(() ->
                machine.transition("t1", TaskPhase.AWAITING_PUSH, "skip", Actor.AGENT))
                .isInstanceOf(ResponseStatusException.class);

        verify(taskStore, never()).updatePhase(any(), any());
    }

    @Test
    void needsAttentionAndCompletedAreReachableFromAnyPhase()
    {
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(taskAt("t1", TaskPhase.PUSHED_AWAITING_CI)));
        machine.transition("t1", TaskPhase.NEEDS_ATTENTION, "stuck", Actor.HUMAN);
        verify(taskStore).updatePhase("t1", TaskPhase.NEEDS_ATTENTION);

        when(taskStore.findTaskById("t2")).thenReturn(Optional.of(taskAt("t2", TaskPhase.IMPLEMENTING)));
        machine.transition("t2", TaskPhase.COMPLETED, "closed", Actor.WEBHOOK);
        verify(taskStore).updatePhase("t2", TaskPhase.COMPLETED);
    }

    @Test
    void completedIsTerminal()
    {
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(taskAt("t1", TaskPhase.COMPLETED)));
        assertThatThrownBy(() ->
                machine.transition("t1", TaskPhase.IMPLEMENTING, "reopen", Actor.HUMAN))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void autoPushUnderCapProceedsAndBumpsTheStreak()
    {
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(taskAt("t1", TaskPhase.AWAITING_PUSH)));
        when(taskStore.consecutiveAutoPushes("t1")).thenReturn(2);

        machine.transition("t1", TaskPhase.PUSHED_AWAITING_CI, "auto_push", Actor.AGENT);

        verify(taskStore).updatePhase("t1", TaskPhase.PUSHED_AWAITING_CI);
        verify(taskStore).setConsecutiveAutoPushes("t1", 3);
    }

    @Test
    void humanPushResetsTheStreak()
    {
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(taskAt("t1", TaskPhase.AWAITING_PUSH)));
        when(taskStore.consecutiveAutoPushes("t1")).thenReturn(4);

        machine.transition("t1", TaskPhase.PUSHED_AWAITING_CI, "human_approved", Actor.HUMAN);

        verify(taskStore).updatePhase("t1", TaskPhase.PUSHED_AWAITING_CI);
        verify(taskStore).setConsecutiveAutoPushes("t1", 0);
    }

    @Test
    void autoPushAtCapParksAtNeedsAttentionInsteadOfPushing()
    {
        when(taskStore.findTaskById("t1")).thenReturn(Optional.of(taskAt("t1", TaskPhase.AWAITING_PUSH)));
        when(taskStore.consecutiveAutoPushes("t1")).thenReturn(TaskPhaseMachine.DEFAULT_AUTO_PUSH_CAP);

        machine.transition("t1", TaskPhase.PUSHED_AWAITING_CI, "auto_push", Actor.SCHEDULER);

        verify(taskStore).updatePhase("t1", TaskPhase.NEEDS_ATTENTION);
        verify(taskStore, never()).updatePhase("t1", TaskPhase.PUSHED_AWAITING_CI);
        verify(notifications).notifyNeedsAttention(eq("thread-1"), eq("t1"), any());
    }

    private static Task taskAt(String id, TaskPhase phase)
    {
        Instant now = Instant.parse("2026-06-15T12:00:00Z");
        return new Task(
                id, "thread-1", 1L, TaskStatus.RUNNING,
                "dev/x", "/tmp/wt", "main", "/tmp/repo",
                null, null, null, null, null, "DEVELOP",
                null, null, 0L, 0L, 0L, null,
                now, null, null, null, null, null, null, phase, null, 0, null);
    }
}
