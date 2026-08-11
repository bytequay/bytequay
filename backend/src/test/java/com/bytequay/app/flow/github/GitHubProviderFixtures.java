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

import com.bytequay.app.domain.CredentialType;
import com.bytequay.app.flow.ci.CiAutofixCoordinator;
import com.bytequay.app.flow.ci.CiAutofixCoordinator.CiObservationActivation;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiRound;
import com.bytequay.app.flow.gate.UserGateRecords.CiUpdateEffectActivation;
import com.bytequay.app.flow.gate.UserGateRecords.GateRevision;
import com.bytequay.app.flow.gate.UserGates;
import com.bytequay.app.flow.github.GitHubEffectRecords.ExternalEffectAttempt;
import com.bytequay.app.flow.github.GitHubEffectRecords.ExternalEffectReceipt;
import com.bytequay.app.flow.github.GitHubEffectRecords.ExternalEffectStep;
import com.bytequay.app.flow.github.GitHubEffectRecords.ProbeOutcome;
import com.bytequay.app.flow.github.GitHubEffectRecords.ProviderObservation;
import com.bytequay.app.flow.github.GitHubEffects.ActivatedInitialAttempt;
import com.bytequay.app.flow.github.GitHubEffects.InitialProbeTarget;
import com.bytequay.app.flow.github.InitialPublishRecords.Outcome;
import com.bytequay.app.flow.github.InitialPublishRecords.Plan;
import com.bytequay.app.flow.github.InitialPublishRecords.PrIdentity;
import com.bytequay.app.flow.github.InitialPublishRecords.ProviderProof;
import com.bytequay.app.flow.runtime.FlowRuntime;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.repository.CredentialStore;

import java.lang.reflect.Constructor;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Test-only route through the concrete provider proof boundary. */
@SuppressWarnings("StringConcatToTextBlock")
public final class GitHubProviderFixtures
{
    public enum CiObservationMode
    {
        GREEN,
        FAILED_ACTIONS,
        FAILED_UNSUPPORTED,
        PENDING,
        UNSTABLE
    }

    public record CiObservationDelivery(
            CiObservationActivation activation,
            GitHubCiObservationProof proof) {}

    public record InitialExecution(
            GitHubInitialPublishExecutor.Result branch,
            GitHubInitialPublishExecutor.Result pullRequest,
            int pushes,
            int posts,
            String postBody,
            Claim prClaim,
            GitHubInitialPublishExecutor executor,
            AtomicInteger providerCommands) {}

    public record InitialProbeExecution(
            GitHubInitialPublishExecutor.Result result, int pushes) {}

    public record InterruptedInitialExecution(
            GitHubInitialPublishExecutor.Result branch,
            RuntimeException failure,
            Claim prClaim,
            GitHubInitialPublishExecutor executor,
            AtomicInteger providerCommands) {}

    public record InitialRetryExecution(
            GitHubInitialPublishExecutor.Result first,
            GitHubInitialPublishExecutor executor,
            AtomicInteger pushes) {}

    private GitHubProviderFixtures() {}

    public static GateRevision openInitialPublish(
            FlowRuntime runtime, UserGates gates, String runId)
    {
        CredentialStore credentials = mock(CredentialStore.class);
        var run = runtime.run(runId).orElseThrow();
        var operation = runtime.operation(run.operationId()).orElseThrow();
        var task = runtime.task(operation.taskId()).orElseThrow();
        when(credentials.getSecret(
                CredentialType.REPO,
                task.repositoryOwner() + "/" + task.repositoryName()))
                .thenReturn(Optional.of("initial-secret"));
        GitHubProvider provider = new GitHubProvider(
                runtime,
                GitHubInitialPublishDispatcher.repoSecrets(credentials),
                (root, arguments, environment, captureOutput) ->
                        new GitHubProvider.ProcessResult(true, 0, ""),
                matchingLookup("101"));
        return gates.openInitialPublish(
                runId, () -> provider.observeInitialRepository(runId));
    }

    public static InitialPublishRecords.RepositoryObservation
            initialRepositoryObservation(
                    FlowRuntime runtime,
                    String runId,
                    String attestedExternalId,
                    String observedExternalId,
                    char[] token,
                    AtomicInteger lookups)
    {
        GitHubProvider provider = new GitHubProvider(
                runtime,
                (repositoryId, owner, name) ->
                        new GitHubProvider.RepositoryCredential(
                                attestedExternalId, token),
                (root, arguments, environment, captureOutput) ->
                        new GitHubProvider.ProcessResult(true, 0, ""),
                (owner, repository, suppliedToken) -> {
                    lookups.incrementAndGet();
                    return new GitHubProvider.RepositoryIdentity(
                            true, true, observedExternalId, owner, repository);
                });
        return provider.observeInitialRepository(runId);
    }

    public static ProviderProof initialProof(
            Claim claim, InitialProbeTarget target, Outcome outcome,
            String observedHead, PrIdentity identity)
    {
        try {
            Constructor<GitHubProvider.ExactInitialPublishProof> constructor =
                    GitHubProvider.ExactInitialPublishProof.class
                            .getDeclaredConstructor(Claim.class,
                                    InitialProbeTarget.class, Outcome.class,
                                    String.class, PrIdentity.class);
            constructor.setAccessible(true);
            return constructor.newInstance(
                    claim, target, outcome, observedHead, identity);
        }
        catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    public static ProviderProof initialProof(
            Claim claim, ActivatedInitialAttempt activated, Outcome outcome,
            String observedHead, PrIdentity identity)
    {
        return initialProof(
                claim, activated.target(), outcome, observedHead, identity);
    }

    public static void consumeInitial(
            FlowRuntime runtime, Claim claim, ActivatedInitialAttempt activated)
    {
        runtime.consumePublishExecutionHandle(
                activated.executionHandle(), claim,
                activated.attempt().attemptId(),
                activated.attempt().executionTokenDigest());
    }

    public static PrIdentity exactInitialPr(
            Plan plan, String observedBaseSha)
    {
        String title = GitHubEffects.initialTitleDigest("Initial draft");
        String body = GitHubEffects.initialBodyDigest("body");
        PrIdentity seed = new PrIdentity("OPEN", true,
                plan.baseRepositoryExternalId(), plan.baseRepositoryOwner(),
                plan.baseRepositoryName(), plan.headRepositoryExternalId(),
                plan.headRepositoryOwner(), plan.headRepositoryName(),
                plan.branchRef(), plan.targetBaseRef(), 42, "PR_node",
                "https://example.test/pr/42", observedBaseSha, title, body,
                "seed", "seed");
        String pass = GitHubEffects.initialPrPassDigest(
                plan.proposedHead(), seed);
        return new PrIdentity("OPEN", true,
                plan.baseRepositoryExternalId(), plan.baseRepositoryOwner(),
                plan.baseRepositoryName(), plan.headRepositoryExternalId(),
                plan.headRepositoryOwner(), plan.headRepositoryName(),
                plan.branchRef(), plan.targetBaseRef(), 42, "PR_node",
                "https://example.test/pr/42", observedBaseSha, title, body,
                pass, pass);
    }

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

    public static InitialExecution executeInitialApplied(
            FlowRuntime runtime,
            UserGates gates,
            GitHubEffects effects,
            Claim branchClaim,
            Plan plan,
            Clock clock)
    {
        return executeInitialApplied(
                runtime, gates, effects, branchClaim, plan, clock,
                plan.expectedBaseSha());
    }

    public static InitialExecution executeInitialApplied(
            FlowRuntime runtime,
            UserGates gates,
            GitHubEffects effects,
            Claim branchClaim,
            Plan plan,
            Clock clock,
            String observedBaseSha)
    {
        return executeInitial(
                runtime, gates, effects, branchClaim, plan, clock,
                plan.expectedBaseSha(), observedBaseSha, () -> {});
    }

    public static InitialExecution executeInitialBaseDrift(
            FlowRuntime runtime,
            UserGates gates,
            GitHubEffects effects,
            Claim branchClaim,
            Plan plan,
            Clock clock,
            String observedBaseSha)
    {
        return executeInitial(
                runtime, gates, effects, branchClaim, plan, clock,
                observedBaseSha, plan.expectedBaseSha(), () -> {});
    }

    public static InitialExecution executeInitialWithBranchHook(
            FlowRuntime runtime,
            UserGates gates,
            GitHubEffects effects,
            Claim branchClaim,
            Plan plan,
            Clock clock,
            Runnable afterBranch)
    {
        return executeInitial(
                runtime, gates, effects, branchClaim, plan, clock,
                plan.expectedBaseSha(), plan.expectedBaseSha(), afterBranch);
    }

    public static InterruptedInitialExecution executeInitialInterrupted(
            FlowRuntime runtime,
            UserGates gates,
            GitHubEffects effects,
            Claim branchClaim,
            Plan plan,
            Clock clock,
            Runnable afterBranch)
    {
        return executeInitialInterrupted(
                runtime, gates, effects, branchClaim, plan, clock,
                plan.expectedBaseSha(), afterBranch);
    }

    public static InterruptedInitialExecution executeInitialPartialInterrupted(
            FlowRuntime runtime,
            UserGates gates,
            GitHubEffects effects,
            Claim branchClaim,
            Plan plan,
            Clock clock,
            String observedBaseSha,
            Runnable afterBranch)
    {
        return executeInitialInterrupted(
                runtime, gates, effects, branchClaim, plan, clock,
                observedBaseSha, afterBranch);
    }

    private static InterruptedInitialExecution executeInitialInterrupted(
            FlowRuntime runtime,
            UserGates gates,
            GitHubEffects effects,
            Claim branchClaim,
            Plan plan,
            Clock clock,
            String observedBaseSha,
            Runnable afterBranch)
    {
        InitialAppliedTransport transport = new InitialAppliedTransport(
                plan, true, plan.expectedBaseSha(), observedBaseSha);
        GitHubProvider provider = new GitHubProvider(
                runtime,
                (id, owner, name) -> credential(id),
                transport,
                (owner, repository, token) ->
                        new GitHubProvider.RepositoryIdentity(
                                true, true,
                                owner.equals(plan.baseRepositoryOwner())
                                        ? plan.baseRepositoryExternalId()
                                        : plan.headRepositoryExternalId(),
                                owner, repository),
                transport);
        GitHubInitialPublishExecutor executor =
                new GitHubInitialPublishExecutor(
                        gates, effects, provider, clock);
        GitHubInitialPublishExecutor.Result branch =
                executor.execute(branchClaim);
        afterBranch.run();
        Claim prClaim = runtime.claimNextPublish(
                "initial-pr-interrupted", Duration.ofMinutes(5))
                .orElseThrow();
        RuntimeException failure;
        try {
            executor.execute(prClaim);
            throw new AssertionError("initial settlement did not fail");
        }
        catch (RuntimeException expected) {
            failure = expected;
        }
        return new InterruptedInitialExecution(
                branch, failure, prClaim, executor, transport.commands);
    }

    private static InitialExecution executeInitial(
            FlowRuntime runtime,
            UserGates gates,
            GitHubEffects effects,
            Claim branchClaim,
            Plan plan,
            Clock clock,
            String preflightBaseSha,
            String observedBaseSha,
            Runnable afterBranch)
    {
        InitialAppliedTransport transport = new InitialAppliedTransport(
                plan, true, preflightBaseSha, observedBaseSha);
        GitHubProvider provider = new GitHubProvider(
                runtime,
                (id, owner, name) -> credential(id),
                transport,
                (owner, repository, token) ->
                        new GitHubProvider.RepositoryIdentity(
                                true, true,
                                owner.equals(plan.baseRepositoryOwner())
                                        ? plan.baseRepositoryExternalId()
                                        : plan.headRepositoryExternalId(),
                                owner, repository),
                transport);
        GitHubInitialPublishExecutor executor =
                new GitHubInitialPublishExecutor(
                        gates, effects, provider, clock);
        GitHubInitialPublishExecutor.Result branch =
                executor.execute(branchClaim);
        afterBranch.run();
        Claim prClaim = runtime.claimNextPublish(
                "initial-pr-fixture", Duration.ofMinutes(5)).orElseThrow();
        GitHubInitialPublishExecutor.Result pullRequest =
                executor.execute(prClaim);
        return new InitialExecution(
                branch, pullRequest,
                transport.pushes.get(), transport.posts.get(),
                transport.postBody,
                prClaim, executor, transport.commands);
    }

    public static InitialProbeExecution executeInitialAbsent(
            FlowRuntime runtime,
            UserGates gates,
            GitHubEffects effects,
            Claim claim,
            Plan plan,
            Clock clock)
    {
        InitialAppliedTransport transport = new InitialAppliedTransport(plan);
        GitHubProvider provider = new GitHubProvider(
                runtime,
                (id, owner, name) -> credential(id),
                transport,
                (owner, repository, token) ->
                        new GitHubProvider.RepositoryIdentity(
                                true, true,
                                owner.equals(plan.baseRepositoryOwner())
                                        ? plan.baseRepositoryExternalId()
                                        : plan.headRepositoryExternalId(),
                                owner, repository),
                transport);
        var result = new GitHubInitialPublishExecutor(
                gates, effects, provider, clock).execute(claim);
        return new InitialProbeExecution(result, transport.pushes.get());
    }

    public static InitialRetryExecution executeInitialMissingAfterMutation(
            FlowRuntime runtime,
            UserGates gates,
            GitHubEffects effects,
            Claim claim,
            Plan plan,
            Clock clock)
    {
        InitialAppliedTransport transport =
                new InitialAppliedTransport(plan, false);
        GitHubProvider provider = new GitHubProvider(
                runtime,
                (id, owner, name) -> credential(id),
                transport,
                matchingLookup(plan.headRepositoryExternalId()),
                transport);
        var executor = new GitHubInitialPublishExecutor(
                gates, effects, provider, clock);
        return new InitialRetryExecution(
                executor.execute(claim), executor, transport.pushes);
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

    public static Optional<CiRound> executeCiObservation(
            FlowRuntime runtime,
            CiAutofixCoordinator coordinator,
            Claim claim,
            Clock clock,
            CiObservationMode mode)
    {
        FlowRuntime.CiObservationSubject subject =
                runtime.ciObservationSubject(claim);
        GitHubCiProvider provider = new GitHubCiProvider(
                (id, owner, name) -> credential(id),
                new ObservationHttp(subject, mode),
                clock);
        return new GitHubCiObservationExecutor(
                runtime, coordinator, provider, clock).execute(claim);
    }

    public static CiObservationDelivery prepareCiObservation(
            FlowRuntime runtime,
            CiAutofixCoordinator coordinator,
            Claim suppliedClaim,
            Clock clock,
            CiObservationMode mode)
    {
        Claim claim = runtime.renewClaim(
                suppliedClaim, Duration.ofMinutes(3));
        CiObservationActivation activation = coordinator
                .beginCiObservation(claim).orElseThrow();
        FlowRuntime.CiObservationSubject subject =
                runtime.ciObservationSubject(claim);
        GitHubCiProvider provider = new GitHubCiProvider(
                (id, owner, name) -> credential(id),
                new ObservationHttp(subject, mode),
                clock);
        GitHubCiProvider.PollResult result = provider.poll(activation);
        if (result.failure() != null) {
            throw new AssertionError(
                    "fixture CI provider failed: " + result.failure());
        }
        return new CiObservationDelivery(activation, result.proof());
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

    private static final class InitialAppliedTransport
            implements GitHubProvider.GitProcess, GitHubProvider.InitialHttp
    {
        private final Plan plan;
        private final boolean applyPush;
        private final AtomicInteger pushes = new AtomicInteger();
        private final AtomicInteger posts = new AtomicInteger();
        private final AtomicInteger commands = new AtomicInteger();
        private final String observedBaseSha;
        private final String preflightBaseSha;
        private boolean branchExists;
        private boolean prExists;
        private String postBody;

        private InitialAppliedTransport(Plan plan)
        {
            this(plan, true, plan.expectedBaseSha(), plan.expectedBaseSha());
        }

        private InitialAppliedTransport(Plan plan, boolean applyPush)
        {
            this(plan, applyPush, plan.expectedBaseSha(),
                    plan.expectedBaseSha());
        }

        private InitialAppliedTransport(
                Plan plan, boolean applyPush, String preflightBaseSha,
                String observedBaseSha)
        {
            this.plan = plan;
            this.applyPush = applyPush;
            this.preflightBaseSha = preflightBaseSha;
            this.observedBaseSha = observedBaseSha;
        }

        @Override
        public GitHubProvider.ProcessResult run(
                Path root,
                List<String> arguments,
                Map<String, String> environment,
                boolean captureOutput)
        {
            commands.incrementAndGet();
            if (arguments.contains("--git-common-dir")) {
                return new GitHubProvider.ProcessResult(
                        true, 0, root.resolve(".git") + "\n");
            }
            if (arguments.contains("--get-regexp")) {
                return new GitHubProvider.ProcessResult(true, 1, "");
            }
            if (arguments.equals(List.of("remote", "-v"))) {
                return new GitHubProvider.ProcessResult(
                        true, 0, "target https://github.com/"
                                + plan.headRepositoryOwner() + "/"
                                + plan.headRepositoryName() + ".git (push)\n");
            }
            if (arguments.equals(List.of(
                    "cat-file", "-t", plan.proposedHead()))) {
                return new GitHubProvider.ProcessResult(true, 0, "commit\n");
            }
            if (arguments.getFirst().equals("ls-remote")) {
                return new GitHubProvider.ProcessResult(
                        true, 0, branchExists
                                ? plan.proposedHead() + "\t"
                                        + plan.branchRef() + "\n"
                                : "");
            }
            if (arguments.getFirst().equals("push")) {
                pushes.incrementAndGet();
                branchExists = applyPush;
                return new GitHubProvider.ProcessResult(false, -1, "");
            }
            return new GitHubProvider.ProcessResult(true, 0, "");
        }

        @Override
        public GitHubProvider.InitialHttpResponse request(
                String method,
                URI uri,
                char[] token,
                byte[] body,
                int responseLimit)
        {
            commands.incrementAndGet();
            if (uri.getPath().contains("/git/ref/heads/")) {
                return initialJson("""
                        {"object":{"sha":"%s"}}
                        """.formatted(preflightBaseSha));
            }
            if (method.equals("POST")) {
                posts.incrementAndGet();
                postBody = new String(body, StandardCharsets.UTF_8);
                prExists = true;
                return new GitHubProvider.InitialHttpResponse(
                        false, -1, new byte[0]);
            }
            if (!prExists) {
                return initialJson("[]");
            }
            String prBody = """
                    [{"state":"open","draft":true,
                      "maintainer_can_modify":false,"number":42,
                      "node_id":"PR_node",
                      "html_url":"https://example.test/pr/42",
                      "title":"Initial draft","body":"body",
                      "base":{"ref":"%s","sha":"%s","repo":{
                        "id":%s,"name":"%s","owner":{"login":"%s"}}},
                      "head":{"ref":"%s","sha":"%s","repo":{
                        "id":%s,"name":"%s","owner":{"login":"%s"}}}}]
                    """.formatted(
                    plan.targetBaseRef().startsWith("refs/heads/")
                            ? plan.targetBaseRef().substring(
                                    "refs/heads/".length())
                            : plan.targetBaseRef(),
                    observedBaseSha,
                    plan.baseRepositoryExternalId(),
                    plan.baseRepositoryName(), plan.baseRepositoryOwner(),
                    plan.branchRef().substring("refs/heads/".length()),
                    plan.proposedHead(), plan.headRepositoryExternalId(),
                    plan.headRepositoryName(), plan.headRepositoryOwner());
            return initialJson(prBody);
        }

        private static GitHubProvider.InitialHttpResponse initialJson(
                String value)
        {
            return new GitHubProvider.InitialHttpResponse(
                    true, 200, value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final class ObservationHttp
            implements GitHubCiProvider.CiHttp
    {
        private final FlowRuntime.CiObservationSubject subject;
        private final CiObservationMode mode;
        private int runReads;

        private ObservationHttp(
                FlowRuntime.CiObservationSubject subject,
                CiObservationMode mode)
        {
            this.subject = subject;
            this.mode = mode;
        }

        @Override
        public GitHubCiProvider.CiHttpResponse get(
                URI uri, char[] token, int responseLimit)
        {
            String value = uri.toString();
            if (value.contains("/pulls/" + subject.prNumber())) {
                return json(pr());
            }
            if (value.contains("/check-suites?")) {
                return json("""
                        {"total_count":1,"check_suites":[
                          {"id":1,"app":{"id":7},"head_sha":"%s"}]}
                        """.formatted(subject.proposedHead()));
            }
            if (value.contains("/check-suites/1/check-runs?")) {
                String conclusion = switch (mode) {
                    case GREEN -> "success";
                    case FAILED_ACTIONS, FAILED_UNSUPPORTED -> "failure";
                    case PENDING -> null;
                    case UNSTABLE -> runReads++ == 0 ? "success" : null;
                };
                String status = conclusion == null
                        ? "in_progress" : "completed";
                String details = mode == CiObservationMode.FAILED_UNSUPPORTED
                        ? "https://ci.invalid/build/1"
                        : "https://github.com/" + subject.repositoryOwner()
                                + "/" + subject.repositoryName()
                                + "/actions/runs/1/job/1";
                return json("""
                        {"total_count":1,"check_runs":[{
                          "id":1,"check_suite":{"id":1},
                          "app":{"id":7,"slug":"github-actions"},
                          "head_sha":"%s","name":"build",
                          "status":"%s","conclusion":%s,
                          "started_at":"2026-08-11T00:00:00Z",
                          "completed_at":"2026-08-11T00:01:00Z",
                          "details_url":"%s"}]}
                        """.formatted(
                        subject.proposedHead(), status,
                        conclusion == null ? "null"
                                : "\"" + conclusion + "\"",
                        details));
            }
            if (value.contains("/actions/jobs/1/logs")) {
                return new GitHubCiProvider.CiHttpResponse(
                        true, 200,
                        "exact failure log\n".getBytes(StandardCharsets.UTF_8),
                        null, null, null);
            }
            throw new AssertionError("unexpected CI fixture request: " + uri);
        }

        private String pr()
        {
            return """
                    {"number":%d,"state":"open","node_id":"%s",
                     "base":{"ref":"%s","sha":"base-sha","repo":{
                       "id":%s,"name":"%s","owner":{"login":"%s"}}},
                     "head":{"ref":"%s","sha":"%s","repo":{
                       "id":%s,"name":"%s","owner":{"login":"%s"}}}}
                    """.formatted(
                    subject.prNumber(), subject.prNodeId(),
                    subject.targetBaseRef(), subject.repositoryExternalId(),
                    subject.repositoryName(), subject.repositoryOwner(),
                    subject.branchName(), subject.proposedHead(),
                    subject.headRepositoryExternalId(),
                    subject.headRepositoryName(),
                    subject.headRepositoryOwner());
        }

        private static GitHubCiProvider.CiHttpResponse json(String value)
        {
            return new GitHubCiProvider.CiHttpResponse(
                    true, 200, value.getBytes(StandardCharsets.UTF_8),
                    null, null, null);
        }
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
