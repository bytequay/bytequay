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
package com.bytequay.agent.core;

import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentSession;
import com.bytequay.agent.spi.AgentSessionContext;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.agent.spi.RunningTurn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class TestOneShotSession
{
    private final AgentResourceService resources = mock(AgentResourceService.class);
    private final AgentDriver driver = mock(AgentDriver.class);
    private final AgentSessionContext context = mock(AgentSessionContext.class);
    private final AgentSession session = mock(AgentSession.class);

    @AfterEach
    void clearInterruption()
    {
        Thread.interrupted();
    }

    @Test
    void doesNotAllocateOrLaunchWhenAlreadyInterrupted()
    {
        Thread.currentThread().interrupt();

        assertThatThrownBy(this::run).isInstanceOf(AgentUnavailableException.class).hasMessageContaining("Interrupted");

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verifyNoInteractions(resources, driver);
    }

    @Test
    void doesNotLaunchWhenInterruptedDuringAllocation()
    {
        when(resources.allocate(any())).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return null;
        });

        assertThatThrownBy(this::run).isInstanceOf(AgentUnavailableException.class).hasMessageContaining("Interrupted");

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verifyNoInteractions(driver);
        verify(resources).delete(any());
    }

    @Test
    void closesWithoutStartingATurnWhenInterruptedDuringOpen()
    {
        when(driver.open(any(), any())).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return session;
        });
        doAnswer(invocation -> {
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
            return null;
        }).when(session).close();

        assertThatThrownBy(this::run).isInstanceOf(AgentUnavailableException.class).hasMessageContaining("Interrupted");

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verify(session).close();
        verify(resources).delete(any());
        verifyNoMoreInteractions(session);
    }

    @Test
    void verifiesProviderShutdownAfterInterruptingTheAnswerWait()
    {
        RunningTurn turn = mock(RunningTurn.class);
        when(driver.open(any(), any())).thenReturn(session);
        when(session.start(any())).thenReturn(turn);
        when(turn.completion()).thenReturn(new CompletableFuture<>());
        doAnswer(invocation -> {
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
            return null;
        }).when(session).close();

        assertThatThrownBy(() -> OneShotSession.run(resources, driver, (taskId, scratch) -> context, "prompt", Duration.ofSeconds(5),
                started -> Thread.currentThread().interrupt()))
                .isInstanceOf(AgentUnavailableException.class).hasMessageContaining("Interrupted");

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verify(session).close();
        verify(resources).delete(any());
    }

    private void run()
    {
        OneShotSession.run(resources, driver, (taskId, scratch) -> context, "prompt", Duration.ofSeconds(5), turn -> {});
    }
}
