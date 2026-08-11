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

import com.bytequay.app.flow.gate.UserGates;
import com.bytequay.app.flow.gate.UserGates.InitialPublishDispositionKind;
import com.bytequay.app.flow.gate.UserGates.InitialPublishEffectActivation;
import com.bytequay.app.flow.github.GitHubEffects.ActivatedInitialAttempt;
import com.bytequay.app.flow.github.GitHubEffects.InitialProbeTarget;
import com.bytequay.app.flow.github.InitialPublishRecords.Attempt;
import com.bytequay.app.flow.github.InitialPublishRecords.Outcome;
import com.bytequay.app.flow.github.InitialPublishRecords.Probe;
import com.bytequay.app.flow.github.InitialPublishRecords.Settlement;
import com.bytequay.app.flow.github.InitialPublishRecords.StepReceipt;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;

import static java.util.Objects.requireNonNull;

/** Executes exactly one owner-derived INITIAL_PUBLISH step per claim. */
public final class GitHubInitialPublishExecutor
{
    public enum ResultKind
    {
        BRANCH_APPLIED,
        SETTLEMENT_REQUIRED,
        SETTLED,
        DEFERRED,
        CANCELED
    }

    public sealed interface Result
            permits BranchApplied, SettlementRequired, SettlementApplied,
                    Deferred, Canceled
    {
        ResultKind kind();
    }

    public static final class BranchApplied
            implements Result
    {
        private final StepReceipt receipt;

        private BranchApplied(StepReceipt receipt)
        {
            this.receipt = requireNonNull(receipt, "receipt is null");
        }

        @Override
        public ResultKind kind() { return ResultKind.BRANCH_APPLIED; }
        public StepReceipt receipt() { return receipt; }
    }

    /** The sole success handoff for the terminal settlement owner. */
    public static final class SettlementRequired
            implements Result
    {
        private final StepReceipt receipt;

        private SettlementRequired(StepReceipt receipt)
        {
            this.receipt = requireNonNull(receipt, "receipt is null");
        }

        @Override
        public ResultKind kind() { return ResultKind.SETTLEMENT_REQUIRED; }
        public StepReceipt receipt() { return receipt; }
    }

    public static final class SettlementApplied
            implements Result
    {
        private final Settlement settlement;

        private SettlementApplied(Settlement settlement)
        {
            this.settlement = requireNonNull(
                    settlement, "settlement is null");
        }

        @Override public ResultKind kind() { return ResultKind.SETTLED; }
        public Settlement settlement() { return settlement; }
    }

    public static final class Deferred
            implements Result
    {
        private final String evidenceRef;

        private Deferred(String evidenceRef)
        {
            this.evidenceRef = requireNonNull(
                    evidenceRef, "evidenceRef is null");
        }

        @Override
        public ResultKind kind() { return ResultKind.DEFERRED; }
        public String evidenceRef() { return evidenceRef; }
    }

    public static final class Canceled
            implements Result
    {
        private final String reason;

        private Canceled(String reason)
        {
            this.reason = requireNonNull(reason, "reason is null");
        }

        @Override
        public ResultKind kind() { return ResultKind.CANCELED; }
        public String reason() { return reason; }
    }

    private final UserGates gates;
    private final GitHubEffects effects;
    private final GitHubProvider provider;
    private final Clock clock;

    GitHubInitialPublishExecutor(
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

    public Result execute(Claim claim)
    {
        requireNonNull(claim, "claim is null");
        GitHubCiUpdateExecutor.requireNoAmbientTransaction();
        var terminal = gates.terminalInitialPublishSettlement(claim);
        if (terminal.isPresent()) {
            return new SettlementApplied(terminal.orElseThrow());
        }
        InitialPublishEffectActivation admission;
        try {
            admission = gates.beginInitialPublishEffect(claim);
        }
        catch (UserGates.DurableStaleEffectException stale) {
            var settled = gates.terminalInitialPublishSettlement(claim);
            if (settled.isPresent()) {
                return new SettlementApplied(settled.orElseThrow());
            }
            return new Canceled(stale.getMessage());
        }
        catch (UserGates.DurableProbeRequiredException attention) {
            return new Deferred(attention.getMessage());
        }
        String planId = admission.planId();
        List<StepReceipt> receipts =
                effects.initialPublishStepReceipts(planId);
        if (receipts.size() == 2) {
            SettlementRequired required = new SettlementRequired(
                    effects.requireCompleteInitialPublishReceipt(
                            claim, planId));
            return new SettlementApplied(
                    gates.settleInitialPublish(claim, required));
        }
        InitialProbeTarget target = effects.prepareInitialPublishProbe(
                claim, planId);
        List<Attempt> attempts = currentAttempts(planId, target);
        GitHubProvider.InitialProbeResult before =
                provider.probeInitial(claim, target);
        if (before.failure() != null) {
            return deferFailure(claim, null, before.failure(), planId);
        }
        Probe beforeProbe = effects.recordInitialPublishProbe(
                claim, before.proof(), clock.instant());
        Result settled = applyProbe(
                claim, null, beforeProbe, attempts, false,
                admission.mutationAllowed());
        if (settled != null) {
            return settled;
        }
        GitHubProvider.InitialPreparation preparation =
                provider.prepareInitialMutation(claim, target);
        if (preparation.failure() != null) {
            return deferFailure(
                    claim, null, preparation.failure(), planId);
        }
        ActivatedInitialAttempt activated = null;
        try {
            activated = effects.activateInitialPublishAttempt(
                    claim, planId, clock.instant());
            provider.mutateInitial(claim, activated, preparation.mutation());
            GitHubProvider.InitialProbeResult after = provider.probeInitial(
                    claim, activated.target());
            if (after.failure() != null) {
                return deferFailure(
                        claim, activated, after.failure(), planId);
            }
            Probe afterProbe = effects.recordInitialPublishAttemptProbe(
                    claim, activated, after.proof(), clock.instant());
            Result result = applyProbe(
                    claim, activated, afterProbe,
                    currentAttempts(planId, activated.target()), true, false);
            return requireNonNull(result,
                    "post-mutation probe did not produce a result");
        }
        finally {
            Arrays.fill(preparation.mutation().token(), '\0');
        }
    }

    private Result applyProbe(
            Claim claim,
            ActivatedInitialAttempt activated,
            Probe probe,
            List<Attempt> attempts,
            boolean afterMutation,
            boolean mutationAllowed)
    {
        boolean divergencePoison = effects.initialPublishProbes(
                probe.planId()).stream()
                .anyMatch(value -> !value.probeId().equals(probe.probeId())
                        && value.stepId().equals(probe.stepId())
                        && value.outcome() == Outcome.DIVERGED);
        if (probe.outcome() == Outcome.APPLIED) {
            StepReceipt receipt = effects.insertInitialPublishStepReceipt(
                    claim, probe, clock.instant());
            if (receipt.stepKind()
                    == InitialPublishRecords.StepKind.CREATE_REF_EXACT) {
                return new BranchApplied(receipt);
            }
            SettlementRequired required = new SettlementRequired(
                    effects.requireCompleteInitialPublishReceipt(
                            claim, receipt.planId()));
            return new SettlementApplied(
                    gates.settleInitialPublish(claim, required));
        }
        if (probe.outcome() == Outcome.DIVERGED) {
            gates.applyInitialPublishDisposition(
                    claim, activated, probe,
                    InitialPublishDispositionKind.DIVERGED);
            return new Deferred(probe.probeId());
        }
        if (probe.outcome() == Outcome.UNKNOWN) {
            gates.applyInitialPublishDisposition(
                    claim, activated, probe,
                    InitialPublishDispositionKind.UNKNOWN);
            return new Deferred(probe.probeId());
        }
        if (divergencePoison) {
            gates.applyInitialPublishDisposition(
                    claim, activated, probe,
                    InitialPublishDispositionKind.DIVERGENCE_LOCKED);
            return attempts.isEmpty()
                    ? new Canceled(probe.probeId())
                    : new Deferred(probe.probeId());
        }
        if (attempts.size() >= GitHubEffects.MAX_MUTATION_ATTEMPTS) {
            gates.applyInitialPublishDisposition(
                    claim, activated, probe,
                    InitialPublishDispositionKind.ATTEMPT_LIMIT);
            return new Deferred(probe.probeId());
        }
        if (afterMutation) {
            gates.applyInitialPublishDisposition(
                    claim, activated, probe,
                    InitialPublishDispositionKind.ABSENT_RETRY);
            return new Deferred(probe.probeId());
        }
        if (!mutationAllowed) {
            gates.applyInitialPublishDisposition(
                    claim, activated, probe,
                    InitialPublishDispositionKind.ABSENT_RETRY);
            return new Deferred(probe.probeId());
        }
        return null;
    }

    private Result deferFailure(
            Claim claim,
            ActivatedInitialAttempt activated,
            InitialPublishRecords.ProviderFailure failure,
            String planId)
    {
        List<InitialPublishRecords.StepReceipt> receipts =
                effects.initialPublishStepReceipts(planId);
        InitialPublishRecords.Step step = effects
                .initialPublishSteps(planId).get(receipts.size());
        boolean preAttempt = effects.initialPublishAttempts(planId).stream()
                .noneMatch(value -> value.stepId().equals(step.stepId()));
        var settlement = gates.applyInitialPublishFailure(
                claim, activated, failure);
        if (settlement.isPresent()) {
            return new SettlementApplied(settlement.orElseThrow());
        }
        return failure.invalid()
                        && preAttempt
                ? new Canceled(planId) : new Deferred(planId);
    }

    private List<Attempt> currentAttempts(
            String planId, InitialProbeTarget target)
    {
        return effects.initialPublishAttempts(planId).stream()
                .filter(value -> value.stepId().equals(target.stepId()))
                .toList();
    }
}
