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
import com.bytequay.app.flow.github.GitHubEffectRecords.ExternalEffectReceipt;
import com.bytequay.app.flow.github.GitHubEffectRecords.ExternalEffectStep;
import com.bytequay.app.flow.github.GitHubEffectRecords.ProbeOutcome;
import com.bytequay.app.flow.github.GitHubEffectRecords.ProviderObservation;
import com.bytequay.app.flow.runtime.FlowRuntime;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only route through the concrete provider proof boundary. */
@SuppressWarnings("StringConcatToTextBlock")
public final class GitHubProviderFixtures
{
    private GitHubProviderFixtures() {}

    public static ProviderObservation observation(
            FlowRuntime runtime,
            Claim claim,
            CiUpdateEffectActivation activation,
            ExternalEffectAttempt attempt,
            ProbeOutcome outcome)
    {
        GitHubProvider provider = new GitHubProvider(
                runtime,
                (id, owner, name) -> credential(id),
                new ObservationGit(activation, outcome),
                matchingLookup(activation.headRepositoryExternalId()));
        GitHubProvider.ProbeResult result = provider.probe(
                claim, activation, attempt);
        if (result.failure() != null) {
            throw new AssertionError("fixture provider did not observe remote");
        }
        return result.observation();
    }

    public static Optional<ExternalEffectReceipt> executeApplied(
            FlowRuntime runtime,
            UserGates gates,
            GitHubEffects effects,
            Claim claim,
            ExternalEffectStep step,
            Clock clock,
            Runnable onPush,
            AtomicInteger pushes)
    {
        GitHubProvider provider = new GitHubProvider(
                runtime,
                (id, owner, name) -> credential(id),
                new AppliedGit(step, onPush, pushes),
                matchingLookup(step.headRepositoryExternalId()));
        return new GitHubCiUpdateExecutor(
                gates, effects, provider, clock).execute(claim);
    }

    public static Optional<ExternalEffectReceipt> executeTerminalProbe(
            FlowRuntime runtime,
            UserGates gates,
            GitHubEffects effects,
            Claim claim,
            Clock clock,
            boolean invalidTarget,
            AtomicInteger providerCommands)
    {
        GitHubProvider provider = new GitHubProvider(
                runtime,
                (id, owner, name) -> credential(id),
                new TerminalGit(invalidTarget, providerCommands),
                matchingLookup("head-repo-external"));
        return new GitHubCiUpdateExecutor(
                gates, effects, provider, clock).execute(claim);
    }

    public static Optional<ExternalEffectReceipt> executeUnavailableProbe(
            FlowRuntime runtime,
            UserGates gates,
            GitHubEffects effects,
            Claim claim,
            ExternalEffectStep step,
            Clock clock,
            boolean afterAttempt,
            AtomicInteger pushes)
    {
        GitHubProvider provider = new GitHubProvider(
                runtime,
                (id, owner, name) -> credential(id),
                new UnavailableGit(step, afterAttempt, pushes),
                matchingLookup(step.headRepositoryExternalId()));
        return new GitHubCiUpdateExecutor(
                gates, effects, provider, clock).execute(claim);
    }

    private static GitHubProvider.RepositoryLookup matchingLookup(
            String externalId)
    {
        return (owner, repository, token) ->
                new GitHubProvider.RepositoryIdentity(
                        true, true, externalId, owner, repository);
    }

    private static GitHubProvider.RepositoryCredential credential(
            String externalId)
    {
        return new GitHubProvider.RepositoryCredential(
                externalId, "fixture-token".toCharArray());
    }

    private record ObservationGit(
            CiUpdateEffectActivation activation,
            ProbeOutcome outcome)
            implements GitHubProvider.GitProcess
    {
        @Override
        public GitHubProvider.ProcessResult run(
                Path repositoryRoot,
                List<String> arguments,
                Map<String, String> environment,
                boolean captureOutput)
        {
            if (arguments.contains("--git-common-dir")) {
                return commonDirectory(repositoryRoot);
            }
            if (arguments.contains("--get-regexp")) {
                return new GitHubProvider.ProcessResult(true, 1, "");
            }
            if (arguments.equals(List.of("remote", "-v"))) {
                return new GitHubProvider.ProcessResult(
                        true,
                        0,
                        "push https://github.com/"
                                + activation.headRepositoryOwner() + "/"
                                + activation.headRepositoryName()
                                + ".git (push)\n");
            }
            if (arguments.getFirst().equals("ls-remote")) {
                if (outcome == ProbeOutcome.UNKNOWN) {
                    return new GitHubProvider.ProcessResult(false, -1, "");
                }
                String observed = switch (outcome) {
                    case APPLIED -> activation.proposedHead();
                    case ABSENT -> activation.expectedRemoteHead();
                    case DIVERGED -> "3333333333333333333333333333333333333333";
                    case UNKNOWN -> throw new AssertionError();
                };
                return new GitHubProvider.ProcessResult(
                        true,
                        0,
                        observed + "\t" + activation.branchRef() + "\n");
            }
            throw new AssertionError("unexpected fixture Git command");
        }
    }

    private static final class AppliedGit
            implements GitHubProvider.GitProcess
    {
        private final Runnable onPush;
        private final AtomicInteger pushes;
        private final ExternalEffectStep step;
        private int probes;

        private AppliedGit(
                ExternalEffectStep step,
                Runnable onPush,
                AtomicInteger pushes)
        {
            this.step = step;
            this.onPush = onPush;
            this.pushes = pushes;
        }

        @Override
        public GitHubProvider.ProcessResult run(
                Path repositoryRoot,
                List<String> arguments,
                Map<String, String> environment,
                boolean captureOutput)
        {
            if (arguments.contains("--git-common-dir")) {
                return commonDirectory(repositoryRoot);
            }
            if (arguments.contains("--get-regexp")) {
                return new GitHubProvider.ProcessResult(true, 1, "");
            }
            if (arguments.equals(List.of("remote", "-v"))) {
                return new GitHubProvider.ProcessResult(
                        true,
                        0,
                        "push https://github.com/"
                                + step.headRepositoryOwner() + "/"
                                + step.headRepositoryName()
                                + ".git (push)\n");
            }
            if (arguments.getFirst().equals("ls-remote")) {
                String head = probes++ == 0
                        ? step.expectedRemoteHead()
                        : step.proposedHead();
                return new GitHubProvider.ProcessResult(
                        true,
                        0,
                        head + "\t" + step.branchRef() + "\n");
            }
            if (arguments.getFirst().equals("push")) {
                pushes.incrementAndGet();
                onPush.run();
                return new GitHubProvider.ProcessResult(false, -1, "");
            }
            return new GitHubProvider.ProcessResult(true, 0, "");
        }
    }

    private record TerminalGit(
            boolean invalidTarget,
            AtomicInteger providerCommands)
            implements GitHubProvider.GitProcess
    {
        @Override
        public GitHubProvider.ProcessResult run(
                Path repositoryRoot,
                List<String> arguments,
                Map<String, String> environment,
                boolean captureOutput)
        {
            if (arguments.contains("--git-common-dir")) {
                return commonDirectory(repositoryRoot);
            }
            providerCommands.incrementAndGet();
            if (arguments.contains("--get-regexp")) {
                return new GitHubProvider.ProcessResult(true, 1, "");
            }
            if (arguments.equals(List.of("remote", "-v"))) {
                return new GitHubProvider.ProcessResult(
                        true,
                        0,
                        invalidTarget
                                ? "push git@github.com:head/repo.git (push)\n"
                                : "push https://github.com/head/repo.git (push)\n");
            }
            if (arguments.getFirst().equals("ls-remote")) {
                return new GitHubProvider.ProcessResult(true, 0, "");
            }
            throw new AssertionError("unexpected terminal fixture Git command");
        }
    }

    private static final class UnavailableGit
            implements GitHubProvider.GitProcess
    {
        private final ExternalEffectStep step;
        private final boolean afterAttempt;
        private final AtomicInteger pushes;
        private boolean pushed;

        private UnavailableGit(
                ExternalEffectStep step,
                boolean afterAttempt,
                AtomicInteger pushes)
        {
            this.step = step;
            this.afterAttempt = afterAttempt;
            this.pushes = pushes;
        }

        @Override
        public GitHubProvider.ProcessResult run(
                Path repositoryRoot,
                List<String> arguments,
                Map<String, String> environment,
                boolean captureOutput)
        {
            if (arguments.contains("--git-common-dir")) {
                return commonDirectory(repositoryRoot);
            }
            if (arguments.contains("--get-regexp")) {
                if (afterAttempt && pushed) {
                    return new GitHubProvider.ProcessResult(
                            true, 0, "push.gpgsign true\n");
                }
                return new GitHubProvider.ProcessResult(true, 1, "");
            }
            if (arguments.equals(List.of("remote", "-v"))) {
                return new GitHubProvider.ProcessResult(
                        true,
                        0,
                        "push https://github.com/"
                                + step.headRepositoryOwner() + "/"
                                + step.headRepositoryName()
                                + ".git (push)\n");
            }
            if (arguments.getFirst().equals("ls-remote")) {
                return new GitHubProvider.ProcessResult(
                        true,
                        0,
                        step.expectedRemoteHead() + "\t"
                                + step.branchRef() + "\n");
            }
            if (arguments.getFirst().equals("push")) {
                pushed = true;
                pushes.incrementAndGet();
                return new GitHubProvider.ProcessResult(false, -1, "");
            }
            return new GitHubProvider.ProcessResult(
                    afterAttempt, afterAttempt ? 0 : -1, "");
        }
    }

    private static GitHubProvider.ProcessResult commonDirectory(
            Path repositoryRoot)
    {
        return new GitHubProvider.ProcessResult(
                true, 0, repositoryRoot.resolve(".git") + "\n");
    }
}
