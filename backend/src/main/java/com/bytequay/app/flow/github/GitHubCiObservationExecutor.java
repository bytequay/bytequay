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
import com.bytequay.app.flow.ci.CiAutofixCoordinator.CiObservationActivation;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiRound;
import com.bytequay.app.flow.runtime.FlowRuntime;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Runs one bounded read-only GitHub CI poll and rearms the same watch. */
public final class GitHubCiObservationExecutor
{
    private static final Duration CLAIM_TTL = Duration.ofMinutes(3);
    private static final Duration RETRY_DELAY = Duration.ofMinutes(1);
    private static final Duration UNSUPPORTED_DELAY = Duration.ofMinutes(5);

    private final FlowRuntime runtime;
    private final CiAutofixCoordinator coordinator;
    private final GitHubCiProvider provider;
    private final Clock clock;

    GitHubCiObservationExecutor(
            FlowRuntime runtime,
            CiAutofixCoordinator coordinator,
            GitHubCiProvider provider,
            Clock clock)
    {
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.coordinator = requireNonNull(
                coordinator, "coordinator is null");
        this.provider = requireNonNull(provider, "provider is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public Optional<CiRound> execute(Claim suppliedClaim)
    {
        requireNonNull(suppliedClaim, "suppliedClaim is null");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "CI observation executor must run outside owner transactions");
        }
        if (runtime.canceledCiObservationResult(suppliedClaim).isPresent()) {
            return Optional.empty();
        }
        Claim claim = runtime.renewClaim(suppliedClaim, CLAIM_TTL);
        Optional<CiObservationActivation> begun =
                coordinator.beginCiObservation(claim);
        if (begun.isEmpty()) {
            return Optional.empty();
        }
        CiObservationActivation activation = begun.orElseThrow();
        GitHubCiProvider.PollResult result = provider.poll(activation);
        if (result.failure() == GitHubCiProvider.Failure.INVALID) {
            runtime.cancelCiObservation(
                    claim, "CI_OBSERVATION_PROVIDER_INVALID");
            return Optional.empty();
        }
        if (result.failure() == GitHubCiProvider.Failure.UNSUPPORTED) {
            runtime.rearmCiObservation(
                    claim, "CI_OBSERVATION_PROVIDER_UNSUPPORTED",
                    clock.instant().plus(UNSUPPORTED_DELAY));
            return Optional.empty();
        }
        if (result.failure() == GitHubCiProvider.Failure.UNAVAILABLE) {
            runtime.rearmCiObservation(
                    claim, "CI_OBSERVATION_PROVIDER_UNAVAILABLE",
                    later(
                            clock.instant().plus(RETRY_DELAY),
                            result.retryNotBefore()));
            return Optional.empty();
        }
        return coordinator.acceptCiObservation(
                activation, result.proof());
    }

    private static Instant later(Instant fallback, Instant provider)
    {
        return provider != null && provider.isAfter(fallback)
                ? provider : fallback;
    }
}
