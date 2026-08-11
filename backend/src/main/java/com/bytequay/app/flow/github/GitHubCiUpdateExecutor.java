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

import com.bytequay.app.flow.gate.UserGateRecords.CiUpdateEffectActivation;
import com.bytequay.app.flow.gate.UserGates;
import com.bytequay.app.flow.github.GitHubEffectRecords.ExternalEffectAttempt;
import com.bytequay.app.flow.github.GitHubEffectRecords.ExternalEffectPlan;
import com.bytequay.app.flow.github.GitHubEffectRecords.ExternalEffectReceipt;
import com.bytequay.app.flow.github.GitHubEffectRecords.ExternalEffectStep;
import com.bytequay.app.flow.github.GitHubEffectRecords.ProbeOutcome;
import com.bytequay.app.flow.github.GitHubEffects.ActivatedAttempt;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Runs only the authorized one-step CI_UPDATE publication. */
public final class GitHubCiUpdateExecutor
{
    private final UserGates gates;
    private final GitHubEffects effects;
    private final GitHubProvider provider;
    private final Clock clock;

    GitHubCiUpdateExecutor(
            UserGates gates,
            GitHubEffects effects,
            GitHubProvider provider,
            Clock clock)
    {
        this.gates = requireNonNull(gates, "gates is null");
        this.effects = requireNonNull(effects, "effects is null");
        this.provider = requireNonNull(provider, "provider is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public Optional<ExternalEffectReceipt> execute(Claim claim)
    {
        requireNonNull(claim, "claim is null");
        requireNoAmbientTransaction();
        Optional<ExternalEffectReceipt> terminal =
                gates.terminalCiUpdateReceipt(claim);
        if (terminal.isPresent()) {
            return terminal;
        }
        CiUpdateEffectActivation activation;
        try {
            activation = gates.beginCiUpdateEffect(claim);
        }
        catch (UserGates.DurableStaleEffectException
                | UserGates.DurableProbeRequiredException settled) {
            return Optional.empty();
        }
        ExternalEffectPlan plan = effects.plan(activation.planId())
                .orElseThrow();
        List<ExternalEffectStep> steps = effects.steps(plan.planId());
        effects.assertExactPlan(plan, steps);
        ExternalEffectStep step = steps.getFirst();
        assertActivation(activation, plan, step);

        List<ExternalEffectAttempt> attempts = effects.attempts(plan.planId());
        ExternalEffectAttempt latestAttempt = attempts.isEmpty()
                ? null : attempts.getLast();
        GitHubProvider.ProbeResult before = provider.probe(
                claim, activation, latestAttempt);
        if (before.failure() != null) {
            gates.applyCiUpdateProviderFailure(
                    claim, null, latestAttempt, before.failure());
            return Optional.empty();
        }
        if (before.observation().outcome() != ProbeOutcome.ABSENT
                || attempts.size() >= GitHubEffects.MAX_MUTATION_ATTEMPTS) {
            return gates.applyCiUpdateObservation(
                    claim, null, latestAttempt, before.observation());
        }
        if (!activation.mutationAllowed()) {
            return gates.applyCiUpdateObservation(
                    claim, null, latestAttempt, before.observation());
        }
        GitHubProvider.Preparation preparation =
                provider.prepareMutation(claim, activation);
        if (preparation.failure() != null) {
            gates.applyCiUpdateProviderFailure(
                    claim, null, null, preparation.failure());
            return Optional.empty();
        }
        try {
            effects.recordObservation(
                    claim, before.observation(), clock.instant());
            ActivatedAttempt activated = effects.activateAttempt(
                    claim, plan.planId(), clock.instant());
            provider.pushExactFastForward(
                    claim, activation, activated, preparation.push());
            GitHubProvider.ProbeResult after = provider.probe(
                    claim, activation, activated.attempt());
            if (after.failure() != null) {
                gates.applyCiUpdateProviderFailure(
                        claim,
                        activated.executionHandle(),
                        activated.attempt(),
                        after.failure());
                return Optional.empty();
            }
            return gates.applyCiUpdateObservation(
                    claim,
                    activated.executionHandle(),
                    activated.attempt(),
                    after.observation());
        }
        finally {
            Arrays.fill(preparation.push().token(), '\0');
        }
    }

    static void requireNoAmbientTransaction()
    {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "GitHub execution must start outside an owner transaction");
        }
    }

    private static void assertActivation(
            CiUpdateEffectActivation activation,
            ExternalEffectPlan plan,
            ExternalEffectStep step)
    {
        if (!activation.planId().equals(plan.planId())
                || !activation.operationId().equals(plan.operationId())
                || !activation.prId().equals(plan.prId())
                || activation.prSequence() != plan.prSequence()
                || !activation.planDigest().equals(plan.planDigest())
                || !activation.headRepositoryExternalId().equals(
                        step.headRepositoryExternalId())
                || !activation.headRepositoryOwner().equals(
                        step.headRepositoryOwner())
                || !activation.headRepositoryName().equals(
                        step.headRepositoryName())
                || !activation.branchRef().equals(step.branchRef())
                || !activation.expectedRemoteHead().equals(
                        step.expectedRemoteHead())
                || !activation.proposedHead().equals(step.proposedHead())) {
            throw new IllegalStateException(
                    "publication activation does not match its exact plan");
        }
    }
}
