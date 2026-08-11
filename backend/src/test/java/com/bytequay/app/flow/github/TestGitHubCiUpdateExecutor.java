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
import com.bytequay.app.flow.github.GitHubEffectRecords.EffectKind;
import com.bytequay.app.flow.github.GitHubEffectRecords.ExternalEffectAttempt;
import com.bytequay.app.flow.github.GitHubEffectRecords.ExternalEffectPlan;
import com.bytequay.app.flow.github.GitHubEffectRecords.ExternalEffectReceipt;
import com.bytequay.app.flow.github.GitHubEffectRecords.ExternalEffectStep;
import com.bytequay.app.flow.github.GitHubEffectRecords.StepKind;
import com.bytequay.app.flow.github.GitHubEffects.ActivatedAttempt;
import com.bytequay.app.flow.runtime.FlowRuntime;
import com.bytequay.app.flow.runtime.FlowRuntime.PublishExecutionHandle;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class TestGitHubCiUpdateExecutor
{
    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");
    private static final String EXPECTED =
            "1111111111111111111111111111111111111111";
    private static final String PROPOSED =
            "2222222222222222222222222222222222222222";

    @TempDir
    private Path temporaryDirectory;

    private UserGates gates;
    private GitHubEffects effects;
    private FlowRuntime runtime;
    private Claim claim;
    private CiUpdateEffectActivation activation;
    private ExternalEffectPlan plan;
    private ExternalEffectStep step;
    private ExternalEffectAttempt attempt;
    private ActivatedAttempt activated;
    private PublishExecutionHandle executionHandle;

    @BeforeEach
    void setUp()
    {
        gates = mock(UserGates.class);
        effects = mock(GitHubEffects.class);
        runtime = mock(FlowRuntime.class);
        claim = claim("claim-1", 1);
        activation = activation(true, "plan-1", "operation-1");
        plan = plan(activation);
        step = step(activation);
        attempt = attempt(activation, claim, 1);
        activated = mock(ActivatedAttempt.class);
        executionHandle = mock(PublishExecutionHandle.class);
        when(activated.attempt()).thenReturn(attempt);
        when(activated.executionHandle()).thenReturn(executionHandle);
        when(gates.terminalCiUpdateReceipt(claim))
                .thenReturn(Optional.empty());
        when(gates.beginCiUpdateEffect(claim)).thenReturn(activation);
        when(effects.plan(plan.planId())).thenReturn(Optional.of(plan));
        when(effects.steps(plan.planId())).thenReturn(List.of(step));
    }

    @Test
    void durableAttemptPrecedesExactLeaseAndTimeoutAfterApplySettlesOnce()
    {
        AtomicBoolean durable = new AtomicBoolean();
        FakeGit git = new FakeGit(EXPECTED, PROPOSED);
        git.onPush = () -> assertThat(durable.get()).isTrue();
        GitHubProvider provider = provider(git);
        when(effects.attempts(plan.planId())).thenReturn(List.of());
        when(effects.activateAttempt(eq(claim), eq(plan.planId()), any()))
                .thenAnswer(ignored -> {
                    durable.set(true);
                    return activated;
                });
        ExternalEffectReceipt receipt = receipt(activation, attempt);
        when(gates.applyCiUpdateObservation(
                eq(claim), eq(executionHandle), eq(attempt),
                any())).thenReturn(Optional.of(receipt));

        assertThat(executor(provider).execute(claim)).contains(receipt);

        assertThat(git.pushes).isEqualTo(1);
        assertThat(git.pushArguments)
                .contains(
                        "--force-with-lease=refs/heads/task/one:" + EXPECTED,
                        PROPOSED + ":refs/heads/task/one")
                .doesNotContain("--force");
        verify(effects).activateAttempt(eq(claim), eq(plan.planId()), any());
    }

    @Test
    void probeOnlyActivationCannotMutateAfterAbsentObservation()
    {
        activation = activation(false, "plan-1", "operation-1");
        plan = plan(activation);
        step = step(activation);
        when(gates.beginCiUpdateEffect(claim)).thenReturn(activation);
        when(effects.plan(plan.planId())).thenReturn(Optional.of(plan));
        when(effects.steps(plan.planId())).thenReturn(List.of(step));
        when(effects.attempts(plan.planId())).thenReturn(List.of());
        FakeGit git = new FakeGit(EXPECTED);

        assertThat(executor(provider(git)).execute(claim)).isEmpty();

        assertThat(git.pushes).isZero();
        verify(effects, never()).activateAttempt(any(), any(), any());
        verify(gates).applyCiUpdateObservation(
                eq(claim), eq(null), eq(null), any());
    }

    @Test
    void timeoutBeforeApplyAllowsOnlyTheSecondExactAttempt()
    {
        Claim secondClaim = claim("claim-2", 2);
        Claim thirdClaim = claim("claim-3", 3);
        ExternalEffectAttempt secondAttempt = attempt(
                activation, secondClaim, 2);
        ActivatedAttempt secondActivated = mock(ActivatedAttempt.class);
        PublishExecutionHandle secondHandle = mock(
                PublishExecutionHandle.class);
        when(secondActivated.attempt()).thenReturn(secondAttempt);
        when(secondActivated.executionHandle()).thenReturn(secondHandle);
        when(gates.beginCiUpdateEffect(secondClaim)).thenReturn(activation);
        when(gates.beginCiUpdateEffect(thirdClaim)).thenReturn(activation);
        when(gates.terminalCiUpdateReceipt(secondClaim))
                .thenReturn(Optional.empty());
        when(gates.terminalCiUpdateReceipt(thirdClaim))
                .thenReturn(Optional.empty());
        when(effects.attempts(plan.planId())).thenReturn(
                List.of(),
                List.of(attempt),
                List.of(attempt, secondAttempt));
        when(effects.activateAttempt(any(), eq(plan.planId()), any()))
                .thenReturn(activated, secondActivated);
        ExternalEffectReceipt receipt = receipt(activation, secondAttempt);
        when(gates.applyCiUpdateObservation(any(), any(), any(), any()))
                .thenReturn(
                        Optional.empty(),
                        Optional.of(receipt),
                        Optional.empty());
        FakeGit git = new FakeGit(
                EXPECTED, EXPECTED,
                EXPECTED, PROPOSED,
                EXPECTED);
        GitHubCiUpdateExecutor executor = executor(provider(git));

        assertThat(executor.execute(claim)).isEmpty();
        assertThat(executor.execute(secondClaim)).contains(receipt);
        assertThat(executor.execute(thirdClaim)).isEmpty();

        assertThat(git.pushes).isEqualTo(2);
        verify(effects, times(2))
                .activateAttempt(any(), eq(plan.planId()), any());
    }

    @Test
    void deterministicPreparationFailureCreatesNoAttemptOrPush()
    {
        FakeGit git = new FakeGit(EXPECTED);
        git.proofExitCode = 1;
        GitHubProvider provider = provider(git);
        when(effects.attempts(plan.planId())).thenReturn(List.of());

        assertThat(executor(provider).execute(claim)).isEmpty();

        assertThat(git.pushes).isZero();
        verify(effects, never()).activateAttempt(any(), any(), any());
        verify(gates).applyCiUpdateProviderFailure(
                eq(claim), eq(null), eq(null), any());
    }

    @Test
    void attemptPersistenceFailureWipesCredentialBeforeAnyPush()
    {
        FakeGit git = new FakeGit(EXPECTED);
        List<char[]> tokens = new ArrayList<>();
        GitHubProvider provider = new GitHubProvider(
                runtime,
                (id, owner, name) -> {
                    char[] token = "secret-token".toCharArray();
                    tokens.add(token);
                    return new GitHubProvider.RepositoryCredential(id, token);
                },
                git,
                matchingLookup());
        when(effects.attempts(plan.planId())).thenReturn(List.of());
        when(effects.activateAttempt(eq(claim), eq(plan.planId()), any()))
                .thenThrow(new IllegalStateException("attempt insert failed"));

        assertThatThrownBy(() -> executor(provider).execute(claim))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("attempt insert failed");

        assertThat(git.pushes).isZero();
        assertThat(tokens).isNotEmpty()
                .allSatisfy(token -> assertThat(token).containsOnly('\0'));
    }

    @Test
    void preparedPushFromAnotherPlanIsRejectedAndCredentialWiped()
    {
        FakeGit git = new FakeGit(EXPECTED, EXPECTED);
        GitHubProvider provider = new GitHubProvider(
                runtime,
                (id, owner, name) -> credential(id, "secret-token"),
                git,
                matchingLookup());
        CiUpdateEffectActivation other = activation(
                true, "plan-2", "operation-1");
        GitHubProvider.Preparation prepared = provider.prepareMutation(
                claim, other);

        assertThatThrownBy(() -> provider.pushExactFastForward(
                claim, activation, activated, prepared.push()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("durable attempt");
        assertThat(git.pushes).isZero();
        verify(runtime, never()).consumePublishExecutionHandle(
                any(), any(), any(), any());
        assertThat(prepared.push().token()).containsOnly('\0');
    }

    private GitHubCiUpdateExecutor executor(GitHubProvider provider)
    {
        return new GitHubCiUpdateExecutor(
                gates,
                effects,
                provider,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private GitHubProvider provider(FakeGit git)
    {
        return new GitHubProvider(
                runtime,
                (id, owner, name) -> credential(id, "token"),
                git,
                matchingLookup());
    }

    private static GitHubProvider.RepositoryLookup matchingLookup()
    {
        return (owner, repository, token) ->
                new GitHubProvider.RepositoryIdentity(
                        true,
                        true,
                        "head-external-1",
                        owner,
                        repository);
    }

    private static GitHubProvider.RepositoryCredential credential(
            String externalId, String token)
    {
        return new GitHubProvider.RepositoryCredential(
                externalId, token.toCharArray());
    }

    private CiUpdateEffectActivation activation(
            boolean mutationAllowed, String planId, String operationId)
    {
        return new CiUpdateEffectActivation(
                "authorization-1",
                planId,
                operationId,
                "pr-1",
                1,
                temporaryDirectory.toString(),
                "head-external-1",
                "head",
                "repo",
                "refs/heads/task/one",
                EXPECTED,
                PROPOSED,
                mutationAllowed,
                "plan-digest-1");
    }

    private static Claim claim(String token, long generation)
    {
        return new Claim(
                "operation-1",
                "task-1",
                OperationKind.PUBLISH,
                generation,
                token,
                "publisher",
                NOW.plus(Duration.ofMinutes(5)));
    }

    private static ExternalEffectPlan plan(CiUpdateEffectActivation activation)
    {
        return new ExternalEffectPlan(
                activation.planId(),
                activation.operationId(),
                activation.authorizationId(),
                activation.prId(),
                activation.prSequence(),
                EffectKind.CI_UPDATE,
                activation.headRepositoryExternalId(),
                activation.headRepositoryOwner(),
                activation.headRepositoryName(),
                activation.expectedRemoteHead(),
                "action-1",
                "action-digest-1",
                "ci-policy-1",
                activation.planDigest(),
                NOW);
    }

    private static ExternalEffectStep step(
            CiUpdateEffectActivation activation)
    {
        return new ExternalEffectStep(
                "step-1",
                activation.planId(),
                1,
                StepKind.PUSH_EXACT,
                activation.headRepositoryExternalId(),
                activation.headRepositoryOwner(),
                activation.headRepositoryName(),
                activation.branchRef(),
                activation.expectedRemoteHead(),
                activation.proposedHead(),
                false,
                "action-1",
                "action-digest-1",
                "precondition-1");
    }

    private static ExternalEffectAttempt attempt(
            CiUpdateEffectActivation activation,
            Claim claim,
            int number)
    {
        return new ExternalEffectAttempt(
                "attempt-" + number,
                activation.operationId(),
                activation.planId(),
                "step-1",
                number,
                claim.generation(),
                "claim-token-digest",
                activation.headRepositoryExternalId(),
                activation.headRepositoryOwner(),
                activation.headRepositoryName(),
                activation.branchRef(),
                activation.expectedRemoteHead(),
                activation.proposedHead(),
                "request-digest",
                "execution-token-digest",
                NOW);
    }

    private static ExternalEffectReceipt receipt(
            CiUpdateEffectActivation activation,
            ExternalEffectAttempt attempt)
    {
        return new ExternalEffectReceipt(
                "receipt-1",
                activation.operationId(),
                activation.planId(),
                attempt.stepId(),
                attempt.attemptId(),
                "probe-1",
                activation.headRepositoryExternalId(),
                activation.headRepositoryOwner(),
                activation.headRepositoryName(),
                activation.branchRef(),
                activation.expectedRemoteHead(),
                activation.proposedHead(),
                "receipt-digest",
                NOW);
    }

    private static final class FakeGit
            implements GitHubProvider.GitProcess
    {
        private final List<String> observedHeads;
        private int proofExitCode;
        private int pushes;
        private Runnable onPush = () -> {};
        private List<String> pushArguments = List.of();

        private FakeGit(String... observedHeads)
        {
            this.observedHeads = new ArrayList<>(List.of(observedHeads));
        }

        @Override
        public GitHubProvider.ProcessResult run(
                Path repositoryRoot,
                List<String> arguments,
                Map<String, String> environment,
                boolean captureOutput)
        {
            if (arguments.contains("--git-common-dir")) {
                return new GitHubProvider.ProcessResult(
                        true,
                        0,
                        repositoryRoot.resolve(".git") + "\n");
            }
            if (arguments.contains("--get-regexp")) {
                return new GitHubProvider.ProcessResult(true, 1, "");
            }
            if (arguments.equals(List.of("remote", "-v"))) {
                return new GitHubProvider.ProcessResult(
                        true,
                        0,
                        "fork https://github.com/head/repo.git (push)\n");
            }
            if (arguments.getFirst().equals("ls-remote")) {
                String observed = observedHeads.removeFirst();
                return new GitHubProvider.ProcessResult(
                        true,
                        0,
                        observed + "\trefs/heads/task/one\n");
            }
            if (arguments.getFirst().equals("push")) {
                pushes++;
                pushArguments = List.copyOf(arguments);
                onPush.run();
                return new GitHubProvider.ProcessResult(false, -1, "");
            }
            return new GitHubProvider.ProcessResult(
                    true, proofExitCode, "");
        }
    }
}
