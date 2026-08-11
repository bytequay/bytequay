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
package com.bytequay.app.flow.github;

import com.bytequay.app.flow.ci.CiAutofixCoordinator;
import com.bytequay.app.flow.gate.UserGates;
import com.bytequay.app.flow.github.InitialPublishRecords.Plan;
import com.bytequay.app.flow.runtime.FlowRuntime;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ExpiredClaim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Operation;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationKind;
import com.bytequay.app.repository.CredentialStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestGitHubOwnerDispatchers
{
    private static final Instant NOW = Instant.parse("2026-08-12T00:00:00Z");

    @Test
    void initialPollFailureDoesNotKillTheLane()
            throws Exception
    {
        FlowRuntime runtime = mock(FlowRuntime.class);
        when(runtime.expiredClaims()).thenReturn(List.of());
        AtomicInteger polls = new AtomicInteger();
        CountDownLatch retried = new CountDownLatch(1);
        when(runtime.claimNextInitialPublish(
                anyString(), any(Duration.class), anyInt()))
                .thenAnswer(invocation -> {
                    if (polls.incrementAndGet() == 1) {
                        throw new IllegalStateException("transient poll");
                    }
                    retried.countDown();
                    return Optional.empty();
                });
        GitHubInitialPublishDispatcher dispatcher = initial(runtime);

        dispatcher.start();
        assertThat(retried.await(1, TimeUnit.SECONDS)).isTrue();
        dispatcher.close();

        assertThat(polls).hasValueGreaterThanOrEqualTo(2);
    }

    @Test
    void initialCloseInterruptsABoundedHandler()
            throws Exception
    {
        FlowRuntime runtime = mock(FlowRuntime.class);
        UserGates gates = mock(UserGates.class);
        when(runtime.expiredClaims()).thenReturn(List.of());
        Claim claim = new Claim(
                "operation", "task", OperationKind.PUBLISH, 1, "token",
                "initial-worker", NOW.plusSeconds(60));
        when(runtime.claimNextInitialPublish(
                anyString(), any(Duration.class), anyInt()))
                .thenReturn(Optional.of(claim));
        CountDownLatch entered = new CountDownLatch(1);
        when(gates.terminalInitialPublishSettlement(claim)).thenAnswer(call -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
                return Optional.empty();
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", interrupted);
            }
        });
        GitHubInitialPublishDispatcher dispatcher = initial(
                runtime, gates, mock(GitHubEffects.class));

        dispatcher.start();
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        dispatcher.close();
    }

    @Test
    void oneBadExpiredObservationDoesNotStarveTheNextOwner()
    {
        FlowRuntime runtime = mock(FlowRuntime.class);
        ExpiredClaim bad = expired("bad");
        ExpiredClaim good = expired("good");
        when(runtime.expiredClaims()).thenReturn(List.of(bad, good));
        doThrow(new IllegalStateException("corrupt owner"))
                .when(runtime).recoverExpiredCiObservation("bad", 1);
        when(runtime.claimNextCiObservation(
                anyString(), any(Duration.class), anyInt()))
                .thenReturn(Optional.empty());
        GitHubCiObservationDispatcher dispatcher =
                new GitHubCiObservationDispatcher(
                        runtime, mock(CiAutofixCoordinator.class),
                        mock(CredentialStore.class), fixedClock(),
                        "ci-observer", Duration.ofSeconds(1),
                        Duration.ofMillis(10), 1);

        assertThat(dispatcher.dispatchOnce()).isTrue();

        verify(runtime).recoverExpiredCiObservation("bad", 1);
        verify(runtime).recoverExpiredCiObservation("good", 1);
    }

    @Test
    void oneBadExpiredInitialOwnerDoesNotStarveTheNextOwner()
    {
        FlowRuntime runtime = mock(FlowRuntime.class);
        UserGates gates = mock(UserGates.class);
        GitHubEffects effects = mock(GitHubEffects.class);
        ExpiredClaim bad = expired("bad", OperationKind.PUBLISH);
        ExpiredClaim good = expired("good", OperationKind.PUBLISH);
        when(runtime.expiredClaims()).thenReturn(List.of(bad, good));
        when(runtime.operation("bad"))
                .thenThrow(new IllegalStateException("corrupt owner"));
        Operation operation = mock(Operation.class);
        when(operation.inputRef()).thenReturn("good-plan");
        when(runtime.operation("good")).thenReturn(Optional.of(operation));
        when(effects.initialPublishPlan("good-plan"))
                .thenReturn(Optional.of(mock(Plan.class)));
        when(runtime.claimNextInitialPublish(
                anyString(), any(Duration.class), anyInt()))
                .thenReturn(Optional.empty());
        GitHubInitialPublishDispatcher dispatcher = initial(
                runtime, gates, effects);

        assertThat(dispatcher.dispatchOnce()).isTrue();

        verify(gates).recoverExpiredInitialPublish("good", 1);
    }

    private static GitHubInitialPublishDispatcher initial(FlowRuntime runtime)
    {
        return initial(runtime, mock(UserGates.class),
                mock(GitHubEffects.class));
    }

    private static GitHubInitialPublishDispatcher initial(
            FlowRuntime runtime, UserGates gates, GitHubEffects effects)
    {
        return new GitHubInitialPublishDispatcher(
                runtime, gates, effects, mock(CredentialStore.class),
                fixedClock(), new GitHubInitialPublishDispatcher.Config(
                        "initial-worker", Duration.ofSeconds(1),
                        Duration.ofMillis(10), 1));
    }

    private static ExpiredClaim expired(String operationId)
    {
        return expired(operationId, OperationKind.OBSERVE_CI);
    }

    private static ExpiredClaim expired(
            String operationId, OperationKind kind)
    {
        return new ExpiredClaim(
                operationId, "task", kind, 1,
                NOW.minusSeconds(1), null, null, null);
    }

    private static Clock fixedClock()
    {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
