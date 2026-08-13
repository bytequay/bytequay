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
import com.bytequay.app.flow.github.GitHubEffectRecords.ExternalEffectAttempt;
import com.bytequay.app.flow.github.GitHubEffectRecords.ProbeOutcome;
import com.bytequay.app.flow.github.GitHubEffectRecords.ProviderFailure;
import com.bytequay.app.flow.github.GitHubEffectRecords.ProviderFailureKind;
import com.bytequay.app.flow.github.GitHubEffectRecords.ProviderObservation;
import com.bytequay.app.flow.github.GitHubEffects.ActivatedAttempt;
import com.bytequay.app.flow.github.GitHubEffects.InitialProbeTarget;
import com.bytequay.app.flow.github.InitialPublishRecords.Outcome;
import com.bytequay.app.flow.github.InitialPublishRecords.PrIdentity;
import com.bytequay.app.flow.github.InitialPublishRecords.ProviderProof;
import com.bytequay.app.flow.runtime.FlowRuntime;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Objects.requireNonNull;

/** Exact GitHub-only transport. It retains no child output or credential. */
final class GitHubProvider
{
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);
    private static final int OUTPUT_LIMIT = 64 * 1024;
    private static final int INITIAL_JSON_LIMIT = 1024 * 1024;
    private static final int INITIAL_PAGE_SIZE = 100;
    private static final int INITIAL_MAX_PAGES = 20;
    private static final int INITIAL_MAX_REQUESTS = 44;
    private static final Duration INITIAL_DEADLINE = Duration.ofMinutes(2);

    interface SecretSource
    {
        RepositoryCredential credential(
                String repositoryExternalId,
                String owner,
                String repository);
    }

    record RepositoryCredential(
            String repositoryExternalId, char[] token)
    {
        RepositoryCredential
        {
            requireNonNull(repositoryExternalId,
                    "repositoryExternalId is null");
            requireNonNull(token, "token is null");
        }
    }

    interface RepositoryLookup
    {
        RepositoryIdentity lookup(
                String owner, String repository, char[] token);
    }

    record RepositoryIdentity(
            boolean complete,
            boolean found,
            String externalId,
            String owner,
            String repository) {}

    interface RepositoryHttp
    {
        RepositoryHttpResponse get(
                URI uri, char[] token);
    }

    interface InitialHttp
    {
        InitialHttpResponse request(
                String method,
                URI uri,
                char[] token,
                byte[] body,
                int responseLimit);
    }

    record InitialHttpResponse(
            boolean complete, int statusCode, byte[] body)
    {
        InitialHttpResponse
        {
            body = body.clone();
        }

        @Override
        public byte[] body()
        {
            return body.clone();
        }
    }

    record RepositoryHttpResponse(
            boolean complete, int statusCode, byte[] body)
    {
        RepositoryHttpResponse
        {
            requireNonNull(body, "body is null");
        }
    }

    interface GitProcess
    {
        ProcessResult run(
                Path repositoryRoot,
                List<String> arguments,
                Map<String, String> environment,
                boolean captureOutput);
    }

    record ProcessResult(boolean complete, int exitCode, String output)
    {
        ProcessResult
        {
            requireNonNull(output, "output is null");
        }
    }

    static final class PreparedPush
    {
        private final Claim claim;
        private final Path root;
        private final String url;
        private final char[] token;
        private final CiUpdateEffectActivation activation;

        private PreparedPush(
                Claim claim,
                Path root,
                String url,
                char[] token,
                CiUpdateEffectActivation activation)
        {
            this.claim = requireNonNull(claim, "claim is null");
            this.root = requireNonNull(root, "root is null");
            this.url = requireNonNull(url, "url is null");
            this.token = requireNonNull(token, "token is null");
            this.activation = requireNonNull(
                    activation, "activation is null");
        }

        Path root() { return root; }
        String url() { return url; }
        char[] token() { return token; }
        String operationId() { return activation.operationId(); }
        String planId() { return activation.planId(); }
        String headRepositoryExternalId() {
            return activation.headRepositoryExternalId();
        }
        String headRepositoryOwner() {
            return activation.headRepositoryOwner();
        }
        String headRepositoryName() {
            return activation.headRepositoryName();
        }
        String branchRef() { return activation.branchRef(); }
        String expectedRemoteHead() {
            return activation.expectedRemoteHead();
        }
        String proposedHead() { return activation.proposedHead(); }
        boolean forcePush() { return activation.forcePush(); }
        boolean matchesClaim(Claim candidate) { return claim.equals(candidate); }
    }

    record ProbeResult(
            ProviderObservation observation,
            ProviderFailure failure)
    {
        ProbeResult
        {
            if ((observation == null) == (failure == null)) {
                throw new IllegalArgumentException(
                        "probe must contain exactly one result");
            }
        }
    }

    record Preparation(
            PreparedPush push,
            ProviderFailure failure)
    {
        Preparation
        {
            if ((push == null) == (failure == null)) {
                throw new IllegalArgumentException(
                        "preparation must contain exactly one result");
            }
        }
    }

    private enum InitialFailureKind
    {
        INVALID,
        UNAVAILABLE,
        BASE_DRIFT
    }

    record InitialProbeResult(
            ExactInitialPublishProof proof,
            ExactInitialFailure failure)
    {
        InitialProbeResult
        {
            if ((proof == null) == (failure == null)) {
                throw new IllegalArgumentException(
                        "initial probe must contain exactly one result");
            }
        }
    }

    record InitialPreparation(
            PreparedInitialMutation mutation,
            ExactInitialFailure failure)
    {
        InitialPreparation
        {
            if ((mutation == null) == (failure == null)) {
                throw new IllegalArgumentException(
                        "initial preparation must contain exactly one result");
            }
        }
    }

    static final class PreparedInitialMutation
    {
        private final Claim claim;
        private final InitialProbeTarget target;
        private final Path root;
        private final String url;
        private final char[] token;
        private final String title;
        private final String body;

        private PreparedInitialMutation(
                Claim claim,
                InitialProbeTarget target,
                Path root,
                String url,
                char[] token,
                String title,
                String body)
        {
            this.claim = requireNonNull(claim, "claim is null");
            this.target = requireNonNull(target, "target is null");
            this.root = root;
            this.url = url;
            this.token = requireNonNull(token, "token is null");
            this.title = title;
            this.body = body;
        }

        char[] token() { return token; }

        boolean matches(Claim candidate, InitialProbeTarget candidateTarget)
        {
            return claim.equals(candidate)
                    && target.operationId().equals(
                            candidateTarget.operationId())
                    && target.planId().equals(candidateTarget.planId())
                    && target.stepId().equals(candidateTarget.stepId())
                    && target.stepKind() == candidateTarget.stepKind();
        }
    }

    private record InitialContext(
            InitialPublishRecords.Plan plan,
            Path root,
            String branchName,
            String title,
            String body) {}

    private record RawPr(
            String state,
            boolean draft,
            boolean maintainerCanModify,
            String baseRepositoryExternalId,
            String baseRepositoryOwner,
            String baseRepositoryName,
            String headRepositoryExternalId,
            String headRepositoryOwner,
            String headRepositoryName,
            String headBranchRef,
            String targetBaseRef,
            long prNumber,
            String prNodeId,
            String htmlUrl,
            String observedBaseSha,
            String observedHead,
            String titleDigest,
            String bodyDigest) {}

    private record PrPass(boolean complete, List<RawPr> values) {}

    static final class ExactProviderObservation
            implements ProviderObservation
    {
        private final CiUpdateEffectActivation activation;
        private final Claim claim;
        private final String attemptId;
        private final ProbeOutcome outcome;
        private final String observedHead;

        private ExactProviderObservation(
                Claim claim,
                CiUpdateEffectActivation activation,
                ExternalEffectAttempt attempt,
                ProbeOutcome outcome,
                String observedHead)
        {
            this.claim = requireNonNull(claim, "claim is null");
            this.activation = requireNonNull(
                    activation, "activation is null");
            this.attemptId = attempt == null ? null : attempt.attemptId();
            this.outcome = requireNonNull(outcome, "outcome is null");
            this.observedHead = observedHead;
            if (outcome == ProbeOutcome.UNKNOWN && observedHead != null
                    || (outcome == ProbeOutcome.APPLIED
                            || outcome == ProbeOutcome.ABSENT)
                            && observedHead == null) {
                throw new IllegalArgumentException(
                        "provider observation is inconsistent");
            }
        }

        @Override
        public String operationId() { return activation.operationId(); }
        @Override
        public String planId() { return activation.planId(); }
        @Override
        public String attemptId() { return attemptId; }
        @Override
        public String headRepositoryExternalId() {
            return activation.headRepositoryExternalId();
        }
        @Override
        public String headRepositoryOwner() {
            return activation.headRepositoryOwner();
        }
        @Override
        public String headRepositoryName() {
            return activation.headRepositoryName();
        }
        @Override
        public String branchRef() { return activation.branchRef(); }
        @Override
        public String expectedRemoteHead() {
            return activation.expectedRemoteHead();
        }
        @Override
        public String proposedHead() { return activation.proposedHead(); }
        @Override
        public ProbeOutcome outcome() { return outcome; }
        @Override
        public String observedHead() { return observedHead; }
        @Override
        public boolean matchesClaim(Claim candidate) {
            return claim.equals(candidate);
        }
    }

    /** Sealed pure evidence composed only by the exact provider executor. */
    static final class ExactInitialPublishProof
            implements ProviderProof
    {
        private final Claim claim;
        private final InitialProbeTarget target;
        private final Outcome outcome;
        private final String observedHead;
        private final PrIdentity prIdentity;

        private ExactInitialPublishProof(
                Claim claim, InitialProbeTarget target, Outcome outcome,
                String observedHead, PrIdentity prIdentity)
        {
            this.claim = requireNonNull(claim, "claim is null");
            this.target = requireNonNull(target, "target is null");
            this.outcome = requireNonNull(outcome, "outcome is null");
            this.observedHead = observedHead;
            this.prIdentity = prIdentity;
        }

        @Override
        public String operationId() { return target.operationId(); }
        @Override
        public String planId() { return target.planId(); }
        @Override
        public String stepId() { return target.stepId(); }
        @Override
        public String attemptId() { return target.attemptId(); }
        @Override
        public int stepOrdinal() { return target.stepOrdinal(); }
        @Override
        public InitialPublishRecords.StepKind stepKind() {
            return target.stepKind();
        }
        @Override
        public Outcome outcome() { return outcome; }
        @Override
        public String observedHead() { return observedHead; }
        @Override
        public PrIdentity prIdentity() { return prIdentity; }
        @Override
        public boolean matchesClaim(Claim candidate) {
            return claim.equals(candidate) && target.matchesClaim(candidate);
        }

        boolean matchesTarget(InitialProbeTarget candidate)
        {
            return operationId().equals(candidate.operationId())
                    && planId().equals(candidate.planId())
                    && stepId().equals(candidate.stepId())
                    && Objects.equals(attemptId(), candidate.attemptId())
                    && stepOrdinal() == candidate.stepOrdinal()
                    && stepKind() == candidate.stepKind();
        }
    }

    /** Provider-minted authority for one exact INITIAL failure disposition. */
    static final class ExactInitialFailure
            implements InitialPublishRecords.ProviderFailure
    {
        private final Claim claim;
        private final InitialProbeTarget target;
        private final InitialFailureKind kind;
        private final String observedBaseSha;

        private ExactInitialFailure(Claim claim, InitialProbeTarget target,
                InitialFailureKind kind)
        {
            this(claim, target, kind, null);
        }

        private ExactInitialFailure(Claim claim, InitialProbeTarget target,
                InitialFailureKind kind, String observedBaseSha)
        {
            this.claim = requireNonNull(claim, "claim is null");
            this.target = requireNonNull(target, "target is null");
            this.kind = requireNonNull(kind, "kind is null");
            this.observedBaseSha = observedBaseSha;
            if ((kind == InitialFailureKind.BASE_DRIFT)
                    != (observedBaseSha != null)) {
                throw new IllegalArgumentException(
                        "base-drift failure evidence is inconsistent");
            }
        }

        @Override
        public boolean matches(Claim candidate, String planId, String stepId,
                String attemptId)
        {
            return claim.equals(candidate) && target.matchesClaim(candidate)
                    && target.planId().equals(planId)
                    && target.stepId().equals(stepId)
                    && Objects.equals(target.attemptId(), attemptId);
        }

        @Override
        public boolean invalid()
        {
            return kind == InitialFailureKind.INVALID
                    || kind == InitialFailureKind.BASE_DRIFT;
        }

        @Override
        public boolean baseDrift() { return kind == InitialFailureKind.BASE_DRIFT; }

        @Override
        public String observedBaseSha() { return observedBaseSha; }
    }

    static final class ExactInitialRepositoryObservation
            implements InitialPublishRecords.RepositoryObservation
    {
        private final String runId;
        private final String taskId;
        private final String repositoryId;
        private final String launchDigest;
        private final RepositoryIdentity identity;
        private final long expiresAtNanos;
        private final AtomicBoolean consumed = new AtomicBoolean();

        private ExactInitialRepositoryObservation(String runId, String taskId,
                String repositoryId, String launchDigest,
                RepositoryIdentity identity)
        {
            this.runId = requireNonNull(runId, "runId is null");
            this.taskId = requireNonNull(taskId, "taskId is null");
            this.repositoryId = requireNonNull(
                    repositoryId, "repositoryId is null");
            this.launchDigest = requireNonNull(
                    launchDigest, "launchDigest is null");
            this.identity = requireNonNull(identity, "identity is null");
            this.expiresAtNanos = System.nanoTime()
                    + INITIAL_DEADLINE.toNanos();
        }

        @Override
        public String repositoryExternalId() { return identity.externalId(); }
        @Override
        public String owner() { return identity.owner(); }
        @Override
        public String name() { return identity.repository(); }
        @Override
        public boolean consumeMatches(
                String candidateRunId, String candidateTaskId,
                String candidateRepositoryId, String candidateLaunchDigest,
                String candidateOwner, String candidateName)
        {
            return System.nanoTime() <= expiresAtNanos
                    && consumed.compareAndSet(false, true)
                    && runId.equals(candidateRunId)
                    && taskId.equals(candidateTaskId)
                    && repositoryId.equals(candidateRepositoryId)
                    && launchDigest.equals(candidateLaunchDigest)
                    && identity.owner().equals(candidateOwner)
                    && identity.repository().equals(candidateName);
        }
    }

    static final class ExactProviderFailure
            implements ProviderFailure
    {
        private final CiUpdateEffectActivation activation;
        private final Claim claim;
        private final String attemptId;
        private final ProviderFailureKind kind;

        private ExactProviderFailure(
                Claim claim,
                CiUpdateEffectActivation activation,
                ExternalEffectAttempt attempt,
                ProviderFailureKind kind)
        {
            this.claim = requireNonNull(claim, "claim is null");
            this.activation = requireNonNull(
                    activation, "activation is null");
            this.attemptId = attempt == null ? null : attempt.attemptId();
            this.kind = requireNonNull(kind, "kind is null");
        }

        @Override
        public String operationId() { return activation.operationId(); }
        @Override
        public String planId() { return activation.planId(); }
        @Override
        public String attemptId() { return attemptId; }
        @Override
        public String headRepositoryExternalId() {
            return activation.headRepositoryExternalId();
        }
        @Override
        public String headRepositoryOwner() {
            return activation.headRepositoryOwner();
        }
        @Override
        public String headRepositoryName() {
            return activation.headRepositoryName();
        }
        @Override
        public String branchRef() { return activation.branchRef(); }
        @Override
        public String expectedRemoteHead() {
            return activation.expectedRemoteHead();
        }
        @Override
        public String proposedHead() { return activation.proposedHead(); }
        @Override
        public ProviderFailureKind kind() { return kind; }
        @Override
        public boolean matchesClaim(Claim candidate) {
            return claim.equals(candidate);
        }
    }

    private final FlowRuntime runtime;
    private final SecretSource secrets;
    private final GitProcess git;
    private final RepositoryLookup repositories;
    private final InitialHttp initialHttp;
    private final ObjectMapper json = new ObjectMapper();

    GitHubProvider(FlowRuntime runtime, SecretSource secrets)
    {
        this(runtime, secrets, new DirectGitProcess(),
                new DirectRepositoryLookup(), new DirectInitialHttp());
    }

    GitHubProvider(
            FlowRuntime runtime,
            SecretSource secrets,
            GitProcess git,
            RepositoryLookup repositories)
    {
        this(runtime, secrets, git, repositories, new DirectInitialHttp());
    }

    GitHubProvider(
            FlowRuntime runtime,
            SecretSource secrets,
            GitProcess git,
            RepositoryLookup repositories,
            InitialHttp initialHttp)
    {
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.secrets = requireNonNull(secrets, "secrets is null");
        this.git = requireNonNull(git, "git is null");
        this.repositories = requireNonNull(
                repositories, "repositories is null");
        this.initialHttp = requireNonNull(initialHttp, "initialHttp is null");
    }

    InitialPublishRecords.RepositoryObservation observeInitialRepository(
            String runId)
    {
        requireNonNull(runId, "runId is null");
        GitHubCiUpdateExecutor.requireNoAmbientTransaction();
        var run = runtime.run(runId).orElseThrow(() ->
                new IllegalStateException("initial run is missing"));
        var operation = runtime.operation(run.operationId()).orElseThrow(() ->
                new IllegalStateException("initial operation is missing"));
        var task = runtime.task(operation.taskId()).orElseThrow(() ->
                new IllegalStateException("initial task is missing"));
        RepositoryCredential credential = requireNonNull(
                secrets.credential(
                        task.repositoryId(), task.repositoryOwner(),
                        task.repositoryName()),
                "repository credential is missing");
        char[] token = credential.token();
        try {
            RepositoryIdentity identity = requireNonNull(
                    repositories.lookup(
                            task.repositoryOwner(), task.repositoryName(),
                            token),
                    "repository identity is missing");
            if (!identity.complete() || !identity.found()
                    || !identity.externalId().matches("[1-9][0-9]*")
                    || !identity.owner().equals(task.repositoryOwner())
                    || !identity.repository().equals(task.repositoryName())) {
                throw new IllegalStateException(
                        "initial repository identity is unavailable or invalid");
            }
            return new ExactInitialRepositoryObservation(
                    runId, task.taskId(), task.repositoryId(),
                    task.launchDigest(), identity);
        }
        finally {
            Arrays.fill(token, '\0');
        }
    }

    InitialProbeResult probeInitial(
            Claim claim, InitialProbeTarget target)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(target, "target is null");
        assertInitialTarget(claim, target);
        try {
            InitialContext context = initialContext(target);
            return target.stepKind()
                    == InitialPublishRecords.StepKind.CREATE_REF_EXACT
                    ? probeInitialRef(claim, target, context)
                    : probeInitialPr(claim, target, context);
        }
        catch (InitialTargetException failure) {
            return initialFailureProbe(claim, target, failure.kind);
        }
    }

    InitialPreparation prepareInitialMutation(
            Claim claim, InitialProbeTarget target)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(target, "target is null");
        assertInitialTarget(claim, target);
        InitialContext context;
        try {
            context = initialContext(target);
            InitialPublishRecords.Plan plan = context.plan();
            if (target.stepKind()
                    == InitialPublishRecords.StepKind.CREATE_REF_EXACT) {
                InitialFailureKind local = initialLocalFailure(
                        context.root(), plan.proposedHead());
                if (local != null) {
                    return initialFailurePreparation(claim, target, local);
                }
                String url = exactPushUrl(
                        context.root(), plan.headRepositoryOwner(),
                        plan.headRepositoryName());
                char[] token = exactCredential(
                        plan.headRepositoryExternalId(),
                        plan.headRepositoryOwner(),
                        plan.headRepositoryName());
                boolean transferred = false;
                try {
                    InitialPreparation result = new InitialPreparation(
                            new PreparedInitialMutation(
                                    claim, target, context.root(), url, token,
                                    null, null), null);
                    transferred = true;
                    return result;
                }
                finally {
                    if (!transferred) {
                        Arrays.fill(token, '\0');
                    }
                }
            }
            String headUrl = exactPushUrl(
                    context.root(), plan.headRepositoryOwner(),
                    plan.headRepositoryName());
            char[] headToken = exactCredential(
                    plan.headRepositoryExternalId(),
                    plan.headRepositoryOwner(), plan.headRepositoryName());
            try {
                if (!exactRemoteHead(
                        context.root(), headUrl, headToken,
                        plan.branchRef(), plan.proposedHead())) {
                    return initialFailurePreparation(
                            claim, target, InitialFailureKind.INVALID);
                }
            }
            finally {
                Arrays.fill(headToken, '\0');
            }
            char[] token = exactCredential(
                    plan.baseRepositoryExternalId(),
                    plan.baseRepositoryOwner(), plan.baseRepositoryName());
            boolean transferred = false;
            try {
                String observedBase = exactBaseHead(plan, token);
                if (!observedBase.equals(plan.expectedBaseSha())) {
                    return initialBaseDrift(
                            claim, target, observedBase);
                }
                InitialPreparation result = new InitialPreparation(
                        new PreparedInitialMutation(
                                claim, target, null, null, token,
                                context.title(), context.body()), null);
                transferred = true;
                return result;
            }
            finally {
                if (!transferred) {
                    Arrays.fill(token, '\0');
                }
            }
        }
        catch (InitialTargetException failure) {
            return initialFailurePreparation(claim, target, failure.kind);
        }
        catch (StableTargetException invalid) {
            return initialFailurePreparation(
                    claim, target, InitialFailureKind.INVALID);
        }
        catch (TransientTargetException unavailable) {
            return initialFailurePreparation(
                    claim, target, InitialFailureKind.UNAVAILABLE);
        }
        catch (RuntimeException unavailable) {
            return initialFailurePreparation(
                    claim, target, InitialFailureKind.UNAVAILABLE);
        }
    }

    void mutateInitial(
            Claim claim,
            GitHubEffects.ActivatedInitialAttempt activated,
            PreparedInitialMutation prepared)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(activated, "activated is null");
        requireNonNull(prepared, "prepared is null");
        try {
            GitHubCiUpdateExecutor.requireNoAmbientTransaction();
            InitialPublishRecords.Attempt attempt = activated.attempt();
            InitialProbeTarget target = activated.target();
            if (!prepared.matches(claim, target)
                    || !attempt.operationId().equals(target.operationId())
                    || !attempt.planId().equals(target.planId())
                    || !attempt.stepId().equals(target.stepId())
                    || attempt.stepKind() != target.stepKind()
                    || !attempt.attemptId().equals(target.attemptId())) {
                throw new IllegalStateException(
                        "prepared initial mutation does not match its attempt");
            }
            runtime.consumePublishExecutionHandle(
                    activated.executionHandle(), claim,
                    attempt.attemptId(), attempt.executionTokenDigest());
            InitialPublishRecords.Plan plan = target.plan();
            if (target.stepKind()
                    == InitialPublishRecords.StepKind.CREATE_REF_EXACT) {
                // The command result is never proof. The empty old value is
                // the atomic absence lease; there is deliberately no fallback.
                try {
                    git.run(
                            prepared.root,
                            initialCreateRefArguments(
                                    plan.branchRef(), plan.proposedHead(),
                                    prepared.url),
                            environment(prepared.token, prepared.url), false);
                }
                catch (RuntimeException ignored) {
                    // A transport failure still requires the exact post-probe.
                }
                return;
            }
            byte[] request;
            try {
                var body = json.createObjectNode()
                        .put("title", prepared.title)
                        .put("body", prepared.body)
                        .put("head", plan.headRepositoryOwner() + ":"
                                + initialBranchName(plan.branchRef()))
                        .put("base", initialBaseName(plan.targetBaseRef()))
                        .put("draft", true)
                        .put("maintainer_can_modify", false);
                if (!plan.baseRepositoryExternalId().equals(
                        plan.headRepositoryExternalId())) {
                    body.put("head_repo", plan.headRepositoryName());
                }
                request = body.toString().getBytes(StandardCharsets.UTF_8);
            }
            catch (RuntimeException invalid) {
                throw new IllegalStateException(
                        "frozen initial PR request is invalid", invalid);
            }
            if (request.length > INITIAL_JSON_LIMIT) {
                throw new IllegalStateException(
                        "frozen initial PR request is too large");
            }
            // The response is ignored. Only a subsequent exhaustive probe can
            // prove whether GitHub created the exact draft pull request.
            try {
                initialHttp.request(
                        "POST",
                        api("/repos/" + encode(plan.baseRepositoryOwner()) + "/"
                                + encode(plan.baseRepositoryName()) + "/pulls"),
                        prepared.token, request, INITIAL_JSON_LIMIT);
            }
            catch (RuntimeException ignored) {
                // A response-loss exception still requires the exact post-probe.
            }
        }
        finally {
            Arrays.fill(prepared.token, '\0');
        }
    }

    static List<String> initialCreateRefArguments(
            String branchRef, String proposedHead, String url)
    {
        return List.of(
                "push",
                "--no-follow-tags",
                "--force-with-lease=" + branchRef + ":",
                url,
                proposedHead + ":" + branchRef);
    }

    private InitialProbeResult probeInitialRef(
            Claim claim,
            InitialProbeTarget target,
            InitialContext context)
    {
        InitialPublishRecords.Plan plan = context.plan();
        String url;
        try {
            url = exactPushUrl(context.root(), plan.headRepositoryOwner(),
                    plan.headRepositoryName());
        }
        catch (StableTargetException invalid) {
            return initialFailureProbe(
                    claim, target, InitialFailureKind.INVALID);
        }
        catch (TransientTargetException unavailable) {
            return initialFailureProbe(
                    claim, target, InitialFailureKind.UNAVAILABLE);
        }
        catch (RuntimeException unavailable) {
            return initialFailureProbe(
                    claim, target, InitialFailureKind.UNAVAILABLE);
        }
        char[] token;
        try {
            token = exactCredential(
                    plan.headRepositoryExternalId(),
                    plan.headRepositoryOwner(), plan.headRepositoryName());
        }
        catch (InitialTargetException failure) {
            return initialFailureProbe(claim, target, failure.kind);
        }
        try {
            ProcessResult result;
            try {
                result = git.run(
                        context.root(),
                        List.of("ls-remote", "--heads", url,
                                plan.branchRef()),
                        environment(token, url), true);
            }
            catch (RuntimeException unavailable) {
                return initialProof(
                        claim, target, Outcome.UNKNOWN, null, null);
            }
            if (!result.complete() || result.exitCode() != 0) {
                return initialProof(claim, target, Outcome.UNKNOWN, null, null);
            }
            List<String> lines = result.output().lines()
                    .filter(line -> !line.isBlank()).toList();
            if (lines.isEmpty()) {
                return initialProof(claim, target, Outcome.ABSENT, null, null);
            }
            if (lines.size() != 1) {
                return initialProof(claim, target, Outcome.UNKNOWN, null, null);
            }
            String[] fields = lines.getFirst().split("\\s+", 2);
            if (fields.length != 2 || !fields[1].equals(plan.branchRef())) {
                return initialProof(claim, target, Outcome.UNKNOWN, null, null);
            }
            String observed = fields[0];
            Outcome outcome = target.attemptId() != null
                    && observed.equals(plan.proposedHead())
                    ? Outcome.APPLIED : Outcome.DIVERGED;
            return initialProof(claim, target, outcome, observed, null);
        }
        finally {
            Arrays.fill(token, '\0');
        }
    }

    private InitialProbeResult probeInitialPr(
            Claim claim,
            InitialProbeTarget target,
            InitialContext context)
    {
        InitialPublishRecords.Plan plan = context.plan();
        char[] token;
        try {
            token = exactCredential(
                    plan.baseRepositoryExternalId(),
                    plan.baseRepositoryOwner(), plan.baseRepositoryName());
        }
        catch (InitialTargetException failure) {
            return initialFailureProbe(claim, target, failure.kind);
        }
        try {
            InitialBudget budget = new InitialBudget();
            PrPass first = readPrPass(plan, token, budget);
            PrPass second = readPrPass(plan, token, budget);
            if (!first.complete() || !second.complete()
                    || !first.values().equals(second.values())
                    || first.values().size() > 1) {
                return initialProof(claim, target, Outcome.UNKNOWN, null, null);
            }
            if (first.values().isEmpty()) {
                return initialProof(claim, target, Outcome.ABSENT, null, null);
            }
            RawPr raw = first.values().getFirst();
            boolean exact = exactInitialPr(plan, raw, context);
            PrIdentity identity = prIdentity(
                    raw, exact ? plan.targetBaseRef()
                            : raw.targetBaseRef());
            Outcome outcome = target.attemptId() != null && exact
                    ? Outcome.APPLIED : Outcome.DIVERGED;
            return initialProof(
                    claim, target, outcome, raw.observedHead(), identity);
        }
        catch (RuntimeException unavailable) {
            return initialProof(claim, target, Outcome.UNKNOWN, null, null);
        }
        finally {
            Arrays.fill(token, '\0');
        }
    }

    private InitialContext initialContext(InitialProbeTarget target)
    {
        InitialPublishRecords.Plan plan = target.plan();
        var pr = runtime.pullRequest(plan.prId()).orElseThrow(() ->
                new InitialTargetException(InitialFailureKind.INVALID));
        var task = runtime.task(pr.taskId()).orElseThrow(() ->
                new InitialTargetException(InitialFailureKind.INVALID));
        var draft = runtime.requirePrDraftRevision(plan.draftRevisionId());
        if (!draft.prId().equals(plan.prId())
                || !draft.changeSetRevisionId().equals(
                        plan.changeSetRevisionId())
                || !draft.headSha().equals(plan.proposedHead())
                || !draft.draftDigest().equals(plan.draftDigest())
                || !pr.taskId().equals(task.taskId())
                || !Objects.equals(task.prId(), pr.prId())) {
            throw new InitialTargetException(InitialFailureKind.INVALID);
        }
        return new InitialContext(
                plan,
                Path.of(task.worktreePath()).toAbsolutePath().normalize(),
                initialBranchName(plan.branchRef()),
                draft.title(), draft.body());
    }

    private char[] exactCredential(
            String externalId, String owner, String repository)
    {
        RepositoryCredential credential;
        try {
            credential = secrets.credential(externalId, owner, repository);
        }
        catch (RuntimeException unavailable) {
            throw new InitialTargetException(InitialFailureKind.UNAVAILABLE);
        }
        if (credential == null || credential.token().length == 0) {
            throw new InitialTargetException(InitialFailureKind.UNAVAILABLE);
        }
        char[] token = credential.token();
        if (!credential.repositoryExternalId().equals(externalId)) {
            Arrays.fill(token, '\0');
            throw new InitialTargetException(InitialFailureKind.INVALID);
        }
        RepositoryIdentity identity;
        try {
            identity = repositories.lookup(owner, repository, token);
        }
        catch (RuntimeException unavailable) {
            Arrays.fill(token, '\0');
            throw new InitialTargetException(InitialFailureKind.UNAVAILABLE);
        }
        if (identity == null || !identity.complete()) {
            Arrays.fill(token, '\0');
            throw new InitialTargetException(InitialFailureKind.UNAVAILABLE);
        }
        if (!identity.found()
                || !externalId.equals(identity.externalId())
                || !owner.equals(identity.owner())
                || !repository.equals(identity.repository())) {
            Arrays.fill(token, '\0');
            throw new InitialTargetException(InitialFailureKind.INVALID);
        }
        return token;
    }

    private InitialFailureKind initialLocalFailure(Path root, String head)
    {
        ProviderFailureKind graft = graftFailure(root);
        if (graft != null) {
            return graft == ProviderFailureKind.INVALID
                    ? InitialFailureKind.INVALID
                    : InitialFailureKind.UNAVAILABLE;
        }
        ProcessResult promisor = git.run(
                root,
                List.of("config", "--includes", "--local", "--get-regexp",
                        "^(extensions\\.partialClone|remote\\..*\\."
                                + "(promisor|partialclonefilter))$"),
                safeEnvironment(), true);
        if (!promisor.complete()
                || promisor.exitCode() != 0 && promisor.exitCode() != 1) {
            return InitialFailureKind.UNAVAILABLE;
        }
        if (promisor.exitCode() == 0 || !promisor.output().isBlank()) {
            return InitialFailureKind.INVALID;
        }
        ProcessResult object = git.run(
                root, List.of("cat-file", "-t", head),
                safeEnvironment(), true);
        if (!object.complete()) {
            return InitialFailureKind.UNAVAILABLE;
        }
        if (object.exitCode() != 0
                || !object.output().strip().equals("commit")) {
            return InitialFailureKind.INVALID;
        }
        return null;
    }

    private String exactBaseHead(
            InitialPublishRecords.Plan plan, char[] token)
    {
        InitialHttpResponse response;
        try {
            response = initialHttp.request(
                    "GET",
                    api("/repos/" + encode(plan.baseRepositoryOwner()) + "/"
                            + encode(plan.baseRepositoryName())
                            + "/git/ref/heads/"
                            + encode(initialBaseName(plan.targetBaseRef()))),
                    token, new byte[0], INITIAL_JSON_LIMIT);
        }
        catch (RuntimeException unavailable) {
            throw new InitialTargetException(InitialFailureKind.UNAVAILABLE);
        }
        if (response.complete() && response.statusCode() == 404) {
            throw new InitialTargetException(InitialFailureKind.INVALID);
        }
        if (!response.complete() || response.statusCode() != 200
                || response.body().length == 0
                || response.body().length > INITIAL_JSON_LIMIT) {
            throw new InitialTargetException(InitialFailureKind.UNAVAILABLE);
        }
        try {
            JsonNode root = json.readTree(response.body());
            if (root == null || !root.isObject()
                    || !root.path("object").path("sha").isTextual()) {
                throw new InitialTargetException(
                        InitialFailureKind.UNAVAILABLE);
            }
            return root.path("object").path("sha").textValue();
        }
        catch (IOException malformed) {
            throw new InitialTargetException(InitialFailureKind.UNAVAILABLE);
        }
    }

    private boolean exactRemoteHead(
            Path root,
            String url,
            char[] token,
            String branchRef,
            String expectedHead)
    {
        ProcessResult result;
        try {
            result = git.run(
                    root,
                    List.of("ls-remote", "--heads", url, branchRef),
                    environment(token, url), true);
        }
        catch (RuntimeException unavailable) {
            throw new InitialTargetException(InitialFailureKind.UNAVAILABLE);
        }
        if (!result.complete() || result.exitCode() != 0) {
            throw new InitialTargetException(InitialFailureKind.UNAVAILABLE);
        }
        List<String> lines = result.output().lines()
                .filter(line -> !line.isBlank()).toList();
        if (lines.size() != 1) {
            return false;
        }
        String[] fields = lines.getFirst().split("\\s+", 2);
        return fields.length == 2 && fields[1].equals(branchRef)
                && fields[0].equals(expectedHead);
    }

    private PrPass readPrPass(
            InitialPublishRecords.Plan plan,
            char[] token,
            InitialBudget budget)
    {
        List<RawPr> values = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        String head = plan.headRepositoryOwner() + ":"
                + initialBranchName(plan.branchRef());
        for (int page = 1; page <= INITIAL_MAX_PAGES; page++) {
            budget.request();
            InitialHttpResponse response = initialHttp.request(
                    "GET",
                    api("/repos/" + encode(plan.baseRepositoryOwner()) + "/"
                            + encode(plan.baseRepositoryName())
                            + "/pulls?state=all&head=" + encode(head)
                            + "&per_page=" + INITIAL_PAGE_SIZE
                            + "&page=" + page),
                    token, new byte[0], INITIAL_JSON_LIMIT);
            if (!response.complete() || response.statusCode() != 200
                    || response.body().length == 0
                    || response.body().length > INITIAL_JSON_LIMIT) {
                return new PrPass(false, List.of());
            }
            JsonNode root;
            try {
                root = json.readTree(response.body());
            }
            catch (IOException malformed) {
                return new PrPass(false, List.of());
            }
            if (root == null || !root.isArray()
                    || root.size() > INITIAL_PAGE_SIZE) {
                return new PrPass(false, List.of());
            }
            for (JsonNode value : root) {
                RawPr parsed = readRawPr(value);
                if (parsed == null
                        || !identities.add(parsed.prNodeId())) {
                    return new PrPass(false, List.of());
                }
                values.add(parsed);
                if (values.size()
                        > INITIAL_PAGE_SIZE * INITIAL_MAX_PAGES) {
                    return new PrPass(false, List.of());
                }
            }
            if (root.size() < INITIAL_PAGE_SIZE) {
                values.sort(Comparator
                        .comparingLong(RawPr::prNumber)
                        .thenComparing(RawPr::prNodeId));
                return new PrPass(true, List.copyOf(values));
            }
            if (page == INITIAL_MAX_PAGES) {
                return new PrPass(false, List.of());
            }
        }
        return new PrPass(false, List.of());
    }

    private RawPr readRawPr(JsonNode root)
    {
        JsonNode base = root.path("base");
        JsonNode head = root.path("head");
        JsonNode baseRepo = base.path("repo");
        JsonNode headRepo = head.path("repo");
        if (!root.path("state").isTextual()
                || !root.path("draft").isBoolean()
                || !root.path("maintainer_can_modify").isBoolean()
                || !root.path("number").canConvertToLong()
                || !root.path("node_id").isTextual()
                || !root.path("html_url").isTextual()
                || !root.path("title").isTextual()
                || !(root.path("body").isTextual()
                        || root.path("body").isNull())
                || !baseRepo.path("id").isIntegralNumber()
                || !baseRepo.path("owner").path("login").isTextual()
                || !baseRepo.path("name").isTextual()
                || !base.path("ref").isTextual()
                || !base.path("sha").isTextual()
                || !headRepo.path("id").isIntegralNumber()
                || !headRepo.path("owner").path("login").isTextual()
                || !headRepo.path("name").isTextual()
                || !head.path("ref").isTextual()
                || !head.path("sha").isTextual()) {
            return null;
        }
        long number = root.path("number").longValue();
        if (number < 1) {
            return null;
        }
        String body = root.path("body").isNull()
                ? "" : root.path("body").textValue();
        return new RawPr(
                root.path("state").textValue().toUpperCase(Locale.ROOT),
                root.path("draft").booleanValue(),
                root.path("maintainer_can_modify").booleanValue(),
                baseRepo.path("id").asText(),
                baseRepo.path("owner").path("login").textValue(),
                baseRepo.path("name").textValue(),
                headRepo.path("id").asText(),
                headRepo.path("owner").path("login").textValue(),
                headRepo.path("name").textValue(),
                "refs/heads/" + head.path("ref").textValue(),
                base.path("ref").textValue(), number,
                root.path("node_id").textValue(),
                root.path("html_url").textValue(),
                base.path("sha").textValue(), head.path("sha").textValue(),
                GitHubEffects.initialTitleDigest(
                        root.path("title").textValue()),
                GitHubEffects.initialBodyDigest(body));
    }

    private static boolean exactInitialPr(
            InitialPublishRecords.Plan plan,
            RawPr value,
            InitialContext context)
    {
        return value.state().equals("OPEN") && value.draft()
                && !value.maintainerCanModify()
                && value.baseRepositoryExternalId().equals(
                        plan.baseRepositoryExternalId())
                && value.baseRepositoryOwner().equals(
                        plan.baseRepositoryOwner())
                && value.baseRepositoryName().equals(
                        plan.baseRepositoryName())
                && value.headRepositoryExternalId().equals(
                        plan.headRepositoryExternalId())
                && value.headRepositoryOwner().equals(
                        plan.headRepositoryOwner())
                && value.headRepositoryName().equals(
                        plan.headRepositoryName())
                && value.headBranchRef().equals(plan.branchRef())
                && value.targetBaseRef().equals(
                        initialBaseName(plan.targetBaseRef()))
                && value.observedHead().equals(plan.proposedHead())
                && value.titleDigest().equals(
                        GitHubEffects.initialTitleDigest(context.title()))
                && value.bodyDigest().equals(
                        GitHubEffects.initialBodyDigest(context.body()));
    }

    private static PrIdentity prIdentity(
            RawPr value, String recordedBaseRef)
    {
        PrIdentity preliminary = new PrIdentity(
                value.state(), value.draft(),
                value.baseRepositoryExternalId(),
                value.baseRepositoryOwner(), value.baseRepositoryName(),
                value.headRepositoryExternalId(),
                value.headRepositoryOwner(), value.headRepositoryName(),
                value.headBranchRef(), recordedBaseRef,
                value.prNumber(), value.prNodeId(), value.htmlUrl(),
                value.observedBaseSha(), value.titleDigest(),
                value.bodyDigest(), "pending", "pending");
        String digest = GitHubEffects.initialPrPassDigest(
                value.observedHead(), preliminary);
        return new PrIdentity(
                value.state(), value.draft(),
                value.baseRepositoryExternalId(),
                value.baseRepositoryOwner(), value.baseRepositoryName(),
                value.headRepositoryExternalId(),
                value.headRepositoryOwner(), value.headRepositoryName(),
                value.headBranchRef(), recordedBaseRef,
                value.prNumber(), value.prNodeId(), value.htmlUrl(),
                value.observedBaseSha(), value.titleDigest(),
                value.bodyDigest(), digest, digest);
    }

    private static InitialProbeResult initialProof(
            Claim claim,
            InitialProbeTarget target,
            Outcome outcome,
            String observedHead,
            PrIdentity identity)
    {
        return new InitialProbeResult(
                new ExactInitialPublishProof(
                        claim, target, outcome, observedHead, identity), null);
    }

    private static void assertInitialTarget(
            Claim claim, InitialProbeTarget target)
    {
        if (claim.kind() != OperationKind.PUBLISH
                || !claim.operationId().equals(target.operationId())
                || !target.matchesClaim(claim)) {
            throw new IllegalArgumentException(
                    "initial provider target does not match its claim");
        }
    }

    private static String initialBranchName(String branchRef)
    {
        if (branchRef == null || !branchRef.startsWith("refs/heads/")
                || branchRef.length() == "refs/heads/".length()) {
            throw new InitialTargetException(InitialFailureKind.INVALID);
        }
        return branchRef.substring("refs/heads/".length());
    }

    private static String initialBaseName(String baseRef)
    {
        if (baseRef == null || baseRef.isBlank()) {
            throw new InitialTargetException(InitialFailureKind.INVALID);
        }
        return baseRef.startsWith("refs/heads/")
                ? initialBranchName(baseRef) : baseRef;
    }

    private static String encode(String value)
    {
        return URLEncoder.encode(
                requireNonNull(value, "value is null"), StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private static URI api(String pathAndQuery)
    {
        return URI.create("https://api.github.com" + pathAndQuery);
    }

    private static final class InitialBudget
    {
        private final long deadline = System.nanoTime()
                + INITIAL_DEADLINE.toNanos();
        private int requests;

        private void request()
        {
            requests++;
            if (requests > INITIAL_MAX_REQUESTS
                    || System.nanoTime() >= deadline) {
                throw new InitialUnavailableException();
            }
        }
    }

    private static final class InitialUnavailableException
            extends RuntimeException {}

    private static final class InitialTargetException
            extends RuntimeException
    {
        private final InitialFailureKind kind;

        private InitialTargetException(InitialFailureKind kind)
        {
            this.kind = requireNonNull(kind, "kind is null");
        }
    }

    ProbeResult probe(Claim claim, CiUpdateEffectActivation activation)
    {
        return probe(claim, activation, null);
    }

    ProbeResult probe(
            Claim claim,
            CiUpdateEffectActivation activation,
            ExternalEffectAttempt attempt)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(activation, "activation is null");
        assertClaimActivation(claim, activation);
        if (attempt != null
                && (!attempt.operationId().equals(activation.operationId())
                || !attempt.planId().equals(activation.planId()))) {
            throw new IllegalArgumentException(
                    "probe attempt does not match activation");
        }
        Path root = Path.of(activation.repositoryRoot()).toAbsolutePath()
                .normalize();
        String url;
        try {
            url = exactPushUrl(root, activation);
        }
        catch (StableTargetException invalid) {
            return failure(
                    claim, activation, attempt, ProviderFailureKind.INVALID);
        }
        catch (TransientTargetException unavailable) {
            return failure(
                    claim, activation, attempt,
                    ProviderFailureKind.UNAVAILABLE);
        }
        RepositoryCredential credential = secrets.credential(
                activation.headRepositoryExternalId(),
                activation.headRepositoryOwner(),
                activation.headRepositoryName());
        if (credential == null || credential.token().length == 0) {
            return failure(
                    claim, activation, attempt,
                    ProviderFailureKind.UNAVAILABLE);
        }
        if (!credential.repositoryExternalId().equals(
                activation.headRepositoryExternalId())) {
            Arrays.fill(credential.token(), '\0');
            return failure(
                    claim, activation, attempt,
                    ProviderFailureKind.UNAVAILABLE);
        }
        char[] token = credential.token();
        try {
            ProviderFailureKind identityFailure = identityFailure(
                    activation, token);
            if (identityFailure != null) {
                return failure(
                        claim, activation, attempt, identityFailure);
            }
            ProcessResult result = git.run(
                    root,
                    List.of(
                            "ls-remote", "--heads", url,
                            activation.branchRef()),
                    environment(token, url),
                    true);
            if (!result.complete() || result.exitCode() != 0) {
                return observation(
                        claim, activation, attempt,
                        ProbeOutcome.UNKNOWN, null);
            }
            List<String> lines = result.output().lines()
                    .filter(line -> !line.isBlank())
                    .toList();
            if (lines.isEmpty()) {
                return observation(
                        claim, activation, attempt,
                        ProbeOutcome.DIVERGED, null);
            }
            if (lines.size() != 1) {
                return observation(
                        claim, activation, attempt,
                        ProbeOutcome.UNKNOWN, null);
            }
            String[] fields = lines.getFirst().split("\\s+", 2);
            if (fields.length != 2
                    || !fields[1].equals(activation.branchRef())) {
                return observation(
                        claim, activation, attempt,
                        ProbeOutcome.UNKNOWN, null);
            }
            String observed = fields[0];
            if (observed.equals(activation.proposedHead())) {
                return observation(
                        claim, activation, attempt,
                        ProbeOutcome.APPLIED, observed);
            }
            if (observed.equals(activation.expectedRemoteHead())) {
                return observation(
                        claim, activation, attempt,
                        ProbeOutcome.ABSENT, observed);
            }
            return observation(
                    claim, activation, attempt,
                    ProbeOutcome.DIVERGED, observed);
        }
        finally {
            Arrays.fill(token, '\0');
        }
    }

    Preparation prepareMutation(
            Claim claim, CiUpdateEffectActivation activation)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(activation, "activation is null");
        assertClaimActivation(claim, activation);
        Path root = Path.of(activation.repositoryRoot()).toAbsolutePath()
                .normalize();
        ProviderFailureKind graftFailure = graftFailure(root);
        if (graftFailure != null) {
            return failedPreparation(claim, activation, graftFailure);
        }
        ProcessResult expected = runProof(root, List.of(
                "cat-file", "-e",
                activation.expectedRemoteHead() + "^{commit}"));
        ProcessResult proposed = runProof(root, List.of(
                "cat-file", "-e", activation.proposedHead() + "^{commit}"));
        ProcessResult ancestor = runProof(root, List.of(
                "merge-base", "--is-ancestor",
                activation.expectedRemoteHead(), activation.proposedHead()));
        if (!expected.complete() || !proposed.complete() || !ancestor.complete()) {
            return failedPreparation(
                    claim, activation,
                    ProviderFailureKind.UNAVAILABLE);
        }
        if (expected.exitCode() != 0 || proposed.exitCode() != 0
                || (!activation.forcePush() && ancestor.exitCode() != 0)
                || (activation.forcePush() && ancestor.exitCode() > 1)) {
            return failedPreparation(
                    claim, activation,
                    ProviderFailureKind.INVALID);
        }
        String url;
        try {
            url = exactPushUrl(root, activation);
        }
        catch (StableTargetException invalid) {
            return failedPreparation(
                    claim, activation,
                    ProviderFailureKind.INVALID);
        }
        catch (TransientTargetException unavailable) {
            return failedPreparation(
                    claim, activation,
                    ProviderFailureKind.UNAVAILABLE);
        }
        RepositoryCredential credential = secrets.credential(
                activation.headRepositoryExternalId(),
                activation.headRepositoryOwner(),
                activation.headRepositoryName());
        if (credential == null || credential.token().length == 0) {
            return failedPreparation(
                    claim, activation,
                    ProviderFailureKind.UNAVAILABLE);
        }
        if (!credential.repositoryExternalId().equals(
                activation.headRepositoryExternalId())) {
            Arrays.fill(credential.token(), '\0');
            return failedPreparation(
                    claim, activation,
                    ProviderFailureKind.UNAVAILABLE);
        }
        char[] token = credential.token();
        boolean transferred = false;
        try {
            ProviderFailureKind identityFailure = identityFailure(
                    activation, token);
            if (identityFailure != null) {
                return failedPreparation(
                        claim, activation, identityFailure);
            }
            Preparation prepared = new Preparation(
                    new PreparedPush(
                            claim,
                            root,
                            url,
                            token,
                            activation),
                    null);
            transferred = true;
            return prepared;
        }
        finally {
            if (!transferred) {
                Arrays.fill(token, '\0');
            }
        }
    }

    private ProviderFailureKind graftFailure(Path root)
    {
        ProcessResult commonDirectory = git.run(
                root,
                List.of(
                        "rev-parse", "--path-format=absolute",
                        "--git-common-dir"),
                safeEnvironment(),
                true);
        if (!commonDirectory.complete()
                || commonDirectory.exitCode() != 0) {
            return ProviderFailureKind.UNAVAILABLE;
        }
        List<String> lines = commonDirectory.output().lines()
                .filter(line -> !line.isBlank())
                .toList();
        if (lines.size() != 1) {
            return ProviderFailureKind.UNAVAILABLE;
        }
        Path common;
        try {
            common = Path.of(lines.getFirst()).normalize();
        }
        catch (RuntimeException invalid) {
            return ProviderFailureKind.UNAVAILABLE;
        }
        if (!common.isAbsolute()) {
            return ProviderFailureKind.UNAVAILABLE;
        }
        try {
            Files.readAttributes(
                    common.resolve("info/grafts"),
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            return ProviderFailureKind.INVALID;
        }
        catch (NoSuchFileException absent) {
            return null;
        }
        catch (IOException unavailable) {
            return ProviderFailureKind.UNAVAILABLE;
        }
    }

    private ProviderFailureKind identityFailure(
            CiUpdateEffectActivation activation, char[] token)
    {
        RepositoryIdentity observed = repositories.lookup(
                activation.headRepositoryOwner(),
                activation.headRepositoryName(),
                token);
        if (observed == null || !observed.complete()) {
            return ProviderFailureKind.UNAVAILABLE;
        }
        if (!observed.found()
                || !activation.headRepositoryExternalId().equals(
                        observed.externalId())
                || !activation.headRepositoryOwner().equals(
                        observed.owner())
                || !activation.headRepositoryName().equals(
                        observed.repository())) {
            return ProviderFailureKind.INVALID;
        }
        return null;
    }

    private static ProbeResult observation(
            Claim claim,
            CiUpdateEffectActivation activation,
            ExternalEffectAttempt attempt,
            ProbeOutcome outcome,
            String observedHead)
    {
        return new ProbeResult(
                new ExactProviderObservation(
                        claim, activation, attempt, outcome, observedHead),
                null);
    }

    private static InitialProbeResult initialFailureProbe(
            Claim claim, InitialProbeTarget target, InitialFailureKind kind)
    {
        return new InitialProbeResult(
                null, new ExactInitialFailure(claim, target, kind));
    }

    private static InitialPreparation initialFailurePreparation(
            Claim claim, InitialProbeTarget target, InitialFailureKind kind)
    {
        return new InitialPreparation(
                null, new ExactInitialFailure(claim, target, kind));
    }

    private static InitialPreparation initialBaseDrift(
            Claim claim, InitialProbeTarget target, String observedBaseSha)
    {
        return new InitialPreparation(null, new ExactInitialFailure(
                claim, target, InitialFailureKind.BASE_DRIFT,
                requireNonNull(observedBaseSha, "observedBaseSha is null")));
    }

    private static ProbeResult failure(
            Claim claim,
            CiUpdateEffectActivation activation,
            ExternalEffectAttempt attempt,
            ProviderFailureKind kind)
    {
        return new ProbeResult(
                null, new ExactProviderFailure(
                        claim, activation, attempt, kind));
    }

    private static Preparation failedPreparation(
            Claim claim,
            CiUpdateEffectActivation activation,
            ProviderFailureKind kind)
    {
        return new Preparation(
                null,
                new ExactProviderFailure(claim, activation, null, kind));
    }

    private static void assertClaimActivation(
            Claim claim, CiUpdateEffectActivation activation)
    {
        if (claim.kind() != OperationKind.PUBLISH
                || !claim.operationId().equals(activation.operationId())) {
            throw new IllegalArgumentException(
                    "provider activation does not match its claim");
        }
    }

    void pushExactLease(
            Claim claim,
            CiUpdateEffectActivation activation,
            ActivatedAttempt activated,
            PreparedPush prepared)
    {
        requireNonNull(prepared, "prepared is null");
        try {
            GitHubCiUpdateExecutor.requireNoAmbientTransaction();
            requireNonNull(claim, "claim is null");
            requireNonNull(activation, "activation is null");
            requireNonNull(activated, "activated is null");
            var attempt = activated.attempt();
            Path expectedRoot = Path.of(activation.repositoryRoot())
                    .toAbsolutePath().normalize();
            if (!attempt.operationId().equals(activation.operationId())
                    || !attempt.planId().equals(activation.planId())
                    || !attempt.headRepositoryExternalId().equals(
                            activation.headRepositoryExternalId())
                    || !attempt.headRepositoryOwner().equals(
                            activation.headRepositoryOwner())
                    || !attempt.headRepositoryName().equals(
                            activation.headRepositoryName())
                    || !attempt.branchRef().equals(activation.branchRef())
                    || !attempt.expectedRemoteHead().equals(
                            activation.expectedRemoteHead())
                    || !attempt.proposedHead().equals(
                            activation.proposedHead())
                    || !prepared.matchesClaim(claim)
                    || !prepared.root().equals(expectedRoot)
                    || !prepared.operationId().equals(attempt.operationId())
                    || !prepared.planId().equals(attempt.planId())
                    || !prepared.headRepositoryExternalId().equals(
                            attempt.headRepositoryExternalId())
                    || !prepared.headRepositoryOwner().equals(
                            attempt.headRepositoryOwner())
                    || !prepared.headRepositoryName().equals(
                            attempt.headRepositoryName())
                    || !prepared.branchRef().equals(attempt.branchRef())
                    || !prepared.expectedRemoteHead().equals(
                            attempt.expectedRemoteHead())
                    || !prepared.proposedHead().equals(
                            attempt.proposedHead())
                    || prepared.forcePush() != activation.forcePush()) {
                throw new IllegalStateException(
                        "prepared push does not match its durable attempt");
            }
            runtime.consumePublishExecutionHandle(
                    activated.executionHandle(),
                    claim,
                    attempt.attemptId(),
                    attempt.executionTokenDigest());
            // The result is deliberately ignored. Only the following probe
            // can prove whether GitHub applied the exact lease.
            git.run(
                    prepared.root(),
                    exactPushArguments(
                            activation.branchRef(),
                            activation.expectedRemoteHead(),
                            activation.proposedHead(),
                            prepared.url()),
                    environment(prepared.token(), prepared.url()),
                    false);
        }
        finally {
            Arrays.fill(prepared.token(), '\0');
        }
    }

    static List<String> exactPushArguments(
            String branchRef,
            String expectedRemoteHead,
            String proposedHead,
            String url)
    {
        return List.of(
                "push",
                "--no-follow-tags",
                "--force-with-lease=" + branchRef + ":" + expectedRemoteHead,
                url,
                proposedHead + ":" + branchRef);
    }

    private ProcessResult runProof(Path root, List<String> arguments)
    {
        return git.run(root, arguments, safeEnvironment(), false);
    }

    private String exactPushUrl(
            Path root, CiUpdateEffectActivation activation)
    {
        return exactPushUrl(root, activation.headRepositoryOwner(),
                activation.headRepositoryName());
    }

    private String exactPushUrl(
            Path root, String repositoryOwner, String repositoryName)
    {
        ProcessResult rewrites = git.run(
                root,
                List.of(
                        "config", "--includes", "--local", "--get-regexp",
                        "^url\\..*\\.(insteadOf|pushInsteadOf)$"),
                safeEnvironment(),
                true);
        if (!rewrites.complete()
                || rewrites.exitCode() != 0 && rewrites.exitCode() != 1) {
            throw new TransientTargetException(
                    "repository URL config is unavailable");
        }
        if (rewrites.exitCode() == 0 || !rewrites.output().isBlank()) {
            throw new StableTargetException(
                    "repository URL rewrites are not permitted");
        }
        ProcessResult httpOverrides = git.run(
                root,
                List.of(
                        "config", "--includes", "--local",
                        "--get-regexp", "^http\\."),
                safeEnvironment(),
                true);
        if (!httpOverrides.complete()
                || httpOverrides.exitCode() != 0
                        && httpOverrides.exitCode() != 1) {
            throw new TransientTargetException(
                    "Git HTTP config is unavailable");
        }
        if (httpOverrides.exitCode() == 0
                || !httpOverrides.output().isBlank()) {
            throw new StableTargetException(
                    "custom Git HTTP settings are not permitted");
        }
        ProcessResult pushOverrides = git.run(
                root,
                List.of(
                        "config", "--includes", "--local",
                        "--get-regexp", "^push\\."),
                safeEnvironment(),
                true);
        if (!pushOverrides.complete()
                || pushOverrides.exitCode() != 0
                        && pushOverrides.exitCode() != 1) {
            throw new TransientTargetException(
                    "Git push config is unavailable");
        }
        if (pushOverrides.exitCode() == 0
                || !pushOverrides.output().isBlank()) {
            throw new StableTargetException(
                    "custom Git push settings are not permitted");
        }
        ProcessResult remotes = git.run(
                root, List.of("remote", "-v"), safeEnvironment(), true);
        if (!remotes.complete() || remotes.exitCode() != 0) {
            throw new TransientTargetException(
                    "Git push remotes are unavailable");
        }
        List<String> matches = new ArrayList<>();
        for (String line : remotes.output().lines().toList()) {
            String[] fields = line.strip().split("\\s+");
            if (fields.length != 3 || !fields[2].equals("(push)")) {
                continue;
            }
            String url = fields[1];
            URI parsed;
            try {
                parsed = URI.create(url);
            }
            catch (IllegalArgumentException invalid) {
                continue;
            }
            if (!Objects.equals(parsed.getScheme(), "https")
                    || parsed.getUserInfo() != null
                    || !Objects.equals(parsed.getHost(), "github.com")
                    || parsed.getPort() != -1
                    || parsed.getQuery() != null
                    || parsed.getFragment() != null) {
                continue;
            }
            String expectedPath = "/" + repositoryOwner
                    + "/" + repositoryName;
            String actualPath = parsed.getPath();
            if (actualPath.endsWith(".git")) {
                actualPath = actualPath.substring(
                        0, actualPath.length() - 4);
            }
            if (actualPath.equalsIgnoreCase(expectedPath)) {
                matches.add("https://github.com" + parsed.getPath());
            }
        }
        if (matches.size() != 1) {
            throw new StableTargetException(
                    "exactly one credential-free GitHub push remote is required");
        }
        return matches.getFirst();
    }

    private static final class StableTargetException
            extends RuntimeException
    {
        private StableTargetException(String message)
        {
            super(message);
        }
    }

    private static final class TransientTargetException
            extends RuntimeException
    {
        private TransientTargetException(String message)
        {
            super(message);
        }
    }

    private static Map<String, String> environment(
            char[] token, String exactUrl)
    {
        requireNonNull(token, "token is null");
        if (token.length == 0) {
            throw new IllegalStateException("GitHub credential is unavailable");
        }
        String credential = "x-access-token:" + new String(token);
        String basic = Base64.getEncoder().encodeToString(
                credential.getBytes(StandardCharsets.UTF_8));
        Map<String, String> environment = safeEnvironment();
        environment.put("GIT_CONFIG_COUNT", "9");
        environment.put("GIT_CONFIG_KEY_0", "credential.helper");
        environment.put("GIT_CONFIG_VALUE_0", "");
        environment.put("GIT_CONFIG_KEY_1", "core.hooksPath");
        environment.put("GIT_CONFIG_VALUE_1", "/dev/null");
        environment.put("GIT_CONFIG_KEY_2", "protocol.file.allow");
        environment.put("GIT_CONFIG_VALUE_2", "never");
        environment.put("GIT_CONFIG_KEY_3",
                "http." + exactUrl + ".extraHeader");
        environment.put("GIT_CONFIG_VALUE_3", "");
        environment.put("GIT_CONFIG_KEY_4",
                "http." + exactUrl + ".sslVerify");
        environment.put("GIT_CONFIG_VALUE_4", "true");
        environment.put("GIT_CONFIG_KEY_5",
                "http." + exactUrl + ".followRedirects");
        environment.put("GIT_CONFIG_VALUE_5", "false");
        environment.put("GIT_CONFIG_KEY_6",
                "http." + exactUrl + ".proxy");
        environment.put("GIT_CONFIG_VALUE_6", "");
        environment.put("GIT_CONFIG_KEY_7",
                "http." + exactUrl + ".sslCAInfo");
        environment.put("GIT_CONFIG_VALUE_7", "");
        environment.put("GIT_CONFIG_KEY_8",
                "http." + exactUrl + ".extraHeader");
        environment.put("GIT_CONFIG_VALUE_8", "Authorization: Basic " + basic);
        return environment;
    }

    private static Map<String, String> safeEnvironment()
    {
        Map<String, String> environment = new HashMap<>();
        environment.put("PATH", "/usr/bin:/bin");
        environment.put("LANG", "C");
        environment.put("LC_ALL", "C");
        environment.put("GIT_CONFIG_NOSYSTEM", "1");
        environment.put("GIT_CONFIG_SYSTEM", "/dev/null");
        environment.put("GIT_CONFIG_GLOBAL", "/dev/null");
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("GIT_ASKPASS", "/usr/bin/false");
        environment.put("GIT_OPTIONAL_LOCKS", "0");
        environment.put("GIT_NO_REPLACE_OBJECTS", "1");
        environment.put("GIT_NO_LAZY_FETCH", "1");
        return environment;
    }

    static final class DirectRepositoryLookup
            implements RepositoryLookup
    {
        private static final int RESPONSE_LIMIT = 64 * 1024;

        private final RepositoryHttp http;
        private final ObjectMapper json = new ObjectMapper();

        private DirectRepositoryLookup()
        {
            this(new DirectRepositoryHttp());
        }

        DirectRepositoryLookup(RepositoryHttp http)
        {
            this.http = requireNonNull(http, "http is null");
        }

        @Override
        @SuppressWarnings("StringConcatToTextBlock")
        public RepositoryIdentity lookup(
                String owner, String repository, char[] token)
        {
            requireNonNull(token, "token is null");
            try {
                RepositoryHttpResponse response = http.get(
                        URI.create("https://api.github.com/repos/"
                                + encode(owner) + "/" + encode(repository)),
                        token);
                if (!response.complete()
                        || response.body().length > RESPONSE_LIMIT) {
                    return unavailable();
                }
                if (response.statusCode() != 200) {
                    return unavailable();
                }
                JsonNode value = json.readTree(response.body());
                if (value == null || !value.isObject()) {
                    return unavailable();
                }
                JsonNode id = value.path("id");
                JsonNode observedOwner = value.path("owner").path("login");
                JsonNode observedRepository = value.path("name");
                if (!id.isIntegralNumber()
                        || !observedOwner.isTextual()
                        || !observedRepository.isTextual()) {
                    return unavailable();
                }
                return new RepositoryIdentity(
                        true,
                        true,
                        id.asText(),
                        observedOwner.asText(),
                        observedRepository.asText());
            }
            catch (IOException failure) {
                return unavailable();
            }
        }

        private static RepositoryIdentity unavailable()
        {
            return new RepositoryIdentity(
                    false, false, null, null, null);
        }
    }

    static final class DirectRepositoryHttp
            implements RepositoryHttp
    {
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(new NoProxySelector())
                .build();

        @Override
        public RepositoryHttpResponse get(URI uri, char[] token)
        {
            requireNonNull(uri, "uri is null");
            requireNonNull(token, "token is null");
            try {
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(15))
                        .header("Accept", "application/vnd.github+json")
                        .header("Authorization",
                                "Bearer " + new String(token))
                        .header("X-GitHub-Api-Version", "2022-11-28")
                        .GET()
                        .build();
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                AtomicBoolean overflow = new AtomicBoolean();
                HttpResponse<Void> response = client.send(
                        request,
                        HttpResponse.BodyHandlers.ofByteArrayConsumer(
                                bytes -> bytes.ifPresent(chunk -> {
                                    if (body.size() + chunk.length
                                            > DirectRepositoryLookup
                                                    .RESPONSE_LIMIT) {
                                        overflow.set(true);
                                        throw new ResponseTooLargeException();
                                    }
                                    body.writeBytes(chunk);
                                })));
                if (overflow.get()) {
                    return new RepositoryHttpResponse(
                            false, response.statusCode(), new byte[0]);
                }
                return new RepositoryHttpResponse(
                        true, response.statusCode(), body.toByteArray());
            }
            catch (IOException failure) {
                return new RepositoryHttpResponse(false, -1, new byte[0]);
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return new RepositoryHttpResponse(false, -1, new byte[0]);
            }
            catch (ResponseTooLargeException oversized) {
                return new RepositoryHttpResponse(false, -1, new byte[0]);
            }
        }

        List<Proxy> proxies(URI uri)
        {
            return client.proxy().orElseThrow().select(uri);
        }

        private static final class ResponseTooLargeException
                extends RuntimeException {}
    }

    static final class DirectInitialHttp
            implements InitialHttp
    {
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(new NoProxySelector())
                .build();

        @Override
        public InitialHttpResponse request(
                String method,
                URI uri,
                char[] token,
                byte[] requestBody,
                int responseLimit)
        {
            requireNonNull(method, "method is null");
            requireNonNull(uri, "uri is null");
            requireNonNull(token, "token is null");
            requireNonNull(requestBody, "requestBody is null");
            if (!(method.equals("GET") || method.equals("POST"))
                    || !"https".equals(uri.getScheme())
                    || !"api.github.com".equals(uri.getHost())
                    || uri.getPort() != -1 || uri.getUserInfo() != null
                    || uri.getFragment() != null || responseLimit < 1
                    || requestBody.length > INITIAL_JSON_LIMIT) {
                return new InitialHttpResponse(false, -1, new byte[0]);
            }
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(15))
                        .header("Accept", "application/vnd.github+json")
                        .header("Authorization", "Bearer " + new String(token))
                        .header("X-GitHub-Api-Version", "2022-11-28");
                if (method.equals("POST")) {
                    builder.header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(
                                    requestBody));
                }
                else {
                    builder.GET();
                }
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                AtomicBoolean overflow = new AtomicBoolean();
                HttpResponse<Void> response = client.send(
                        builder.build(),
                        HttpResponse.BodyHandlers.ofByteArrayConsumer(
                                bytes -> bytes.ifPresent(chunk -> {
                                    if (body.size() + chunk.length
                                            > responseLimit) {
                                        overflow.set(true);
                                        throw new ResponseTooLargeException();
                                    }
                                    body.writeBytes(chunk);
                                })));
                return overflow.get()
                        ? new InitialHttpResponse(
                                false, response.statusCode(), new byte[0])
                        : new InitialHttpResponse(
                                true, response.statusCode(),
                                body.toByteArray());
            }
            catch (IOException failure) {
                return new InitialHttpResponse(false, -1, new byte[0]);
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return new InitialHttpResponse(false, -1, new byte[0]);
            }
            catch (ResponseTooLargeException oversized) {
                return new InitialHttpResponse(false, -1, new byte[0]);
            }
        }

        List<Proxy> proxies(URI uri)
        {
            return client.proxy().orElseThrow().select(uri);
        }

        private static final class ResponseTooLargeException
                extends RuntimeException {}
    }

    private static final class NoProxySelector
            extends ProxySelector
    {
        @Override
        public List<Proxy> select(URI uri)
        {
            requireNonNull(uri, "uri is null");
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(
                URI uri, SocketAddress address, IOException failure)
        {
            requireNonNull(uri, "uri is null");
            requireNonNull(address, "address is null");
            requireNonNull(failure, "failure is null");
        }
    }

    static final class DirectGitProcess
            implements GitProcess
    {
        private static final AtomicInteger LIVE_DRAINS = new AtomicInteger();

        static int liveDrainCount()
        {
            return LIVE_DRAINS.get();
        }

        @Override
        public ProcessResult run(
                Path repositoryRoot,
                List<String> arguments,
                Map<String, String> environment,
                boolean captureOutput)
        {
            List<String> command = new ArrayList<>();
            command.add("/usr/bin/git");
            command.add("-c");
            command.add("core.hooksPath=/dev/null");
            command.addAll(arguments);
            Process process;
            try {
                ProcessBuilder builder = new ProcessBuilder(command)
                        .directory(repositoryRoot.toFile());
                builder.environment().clear();
                builder.environment().putAll(environment);
                process = builder.start();
            }
            catch (IOException failure) {
                return new ProcessResult(false, -1, "");
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            AtomicBoolean stdoutEof = new AtomicBoolean();
            AtomicBoolean stderrEof = new AtomicBoolean();
            AtomicBoolean overflow = new AtomicBoolean();
            Thread stdoutDrain = Thread.ofPlatform().daemon().start(() -> {
                LIVE_DRAINS.incrementAndGet();
                try (InputStream input = process.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        if (captureOutput) {
                            int accepted = Math.min(
                                    read, Math.max(
                                            0, OUTPUT_LIMIT - output.size()));
                            output.write(buffer, 0, accepted);
                            if (accepted < read) {
                                overflow.set(true);
                            }
                        }
                    }
                    stdoutEof.set(true);
                }
                catch (IOException ignored) {
                    // Failure to prove EOF is reported as incomplete.
                }
                finally {
                    LIVE_DRAINS.decrementAndGet();
                }
            });
            Thread stderrDrain = Thread.ofPlatform().daemon().start(() -> {
                LIVE_DRAINS.incrementAndGet();
                try (InputStream input = process.getErrorStream()) {
                    input.transferTo(OutputStream.nullOutputStream());
                    stderrEof.set(true);
                }
                catch (IOException ignored) {
                    // Failure to prove EOF is reported as incomplete.
                }
                finally {
                    LIVE_DRAINS.decrementAndGet();
                }
            });
            boolean exited = false;
            boolean interrupted = Thread.interrupted();
            try {
                if (!interrupted) {
                    exited = process.waitFor(
                            COMMAND_TIMEOUT.toMillis(),
                            TimeUnit.MILLISECONDS);
                }
                if (!exited) {
                    interrupted |= stopDirectProcess(process);
                }
                joinDrains(stdoutDrain, stderrDrain);
            }
            catch (InterruptedException canceled) {
                interrupted = true;
                interrupted |= stopDirectProcess(process);
            }
            if (stdoutDrain.isAlive() || stderrDrain.isAlive()) {
                close(process.getOutputStream());
                close(process.getInputStream());
                close(process.getErrorStream());
                try {
                    joinDrains(stdoutDrain, stderrDrain);
                }
                catch (InterruptedException canceled) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            boolean complete = exited
                    && !stdoutDrain.isAlive() && !stderrDrain.isAlive()
                    && stdoutEof.get() && stderrEof.get() && !overflow.get();
            return new ProcessResult(
                    complete,
                    complete ? process.exitValue() : -1,
                    complete && captureOutput
                            ? output.toString(StandardCharsets.UTF_8) : "");
        }

        private static void close(AutoCloseable stream)
        {
            try {
                stream.close();
            }
            catch (Exception ignored) {
                // Failure to close is represented by an incomplete result.
            }
        }

        private static void joinDrains(Thread first, Thread second)
                throws InterruptedException
        {
            long deadline = System.nanoTime()
                    + Duration.ofSeconds(2).toNanos();
            joinUntil(first, deadline);
            joinUntil(second, deadline);
        }

        private static void joinUntil(Thread thread, long deadline)
                throws InterruptedException
        {
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) {
                thread.join(Duration.ofNanos(remaining));
            }
        }

        private static boolean stopDirectProcess(Process process)
        {
            boolean interrupted = false;
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                }
            }
            catch (InterruptedException canceled) {
                interrupted = true;
                process.destroyForcibly();
                try {
                    process.waitFor(2, TimeUnit.SECONDS);
                }
                catch (InterruptedException canceledAgain) {
                    interrupted = true;
                }
            }
            return interrupted;
        }
    }
}
