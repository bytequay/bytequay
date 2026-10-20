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
package com.bytequay.app.agent;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.DevelopmentTurnCompletion;
import com.bytequay.agent.core.DevelopmentTurnOrigin;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.spi.DevelopmentResultBridge;
import com.bytequay.agent.spi.DevelopmentResultReport;
import com.bytequay.app.ci.CiAutofixService;
import com.bytequay.app.ci.CiHeadSnapshot;
import com.bytequay.app.ci.GitHubActionsClient;
import com.bytequay.app.ci.StartCiAutofixCommand;
import com.bytequay.app.repository.github.CreatedPullRequest;
import com.bytequay.app.repository.github.GitHubDeliveryChecks;
import com.bytequay.app.repository.github.GitHubPullRequestIdentity;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.repository.github.GitHubPullRequestWriteClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.core.ci.CiAutofixRun;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

/** One live continuation per development task; neither history reads nor restart replay it. */
public class DevelopmentFollowupCoordinator
{
    private static final Logger log = LoggerFactory.getLogger(DevelopmentFollowupCoordinator.class);
    private static final String CI_CONNECTION_WAIT = "Waiting for GitHub; CI monitoring will retry automatically";
    private static final Pattern ATTRIBUTION = Pattern.compile("(?im)^.*(?:generated with[^\\n]{0,40}claude code|co-authored-by:\\s*claude\\b).*$\\R?");

    private final AgentTurnService turns;
    private final AgentTurnStore turnStore;
    private final DevelopmentGitInspector git;
    private final Executor executor;
    private final CiAutofixService ci;
    private final GitHubActionsClient actions;
    private final CredentialService credentials;
    private final GitHubPullRequestReadClient pullRequests;
    private final GitHubPullRequestWriteClient writes;
    private final Clock clock;
    private final Runnable waitForCi;
    private final DevelopmentBranchService branches;
    private final ConcurrentMap<TaskId, Accepted> accepted = new ConcurrentHashMap<>();
    private final ConcurrentMap<TaskId, PendingPublication> publications = new ConcurrentHashMap<>();

    public DevelopmentFollowupCoordinator(AgentTurnService turns, AgentTurnStore turnStore, DevelopmentGitInspector git,
            Executor executor, CiAutofixService ci, GitHubActionsClient actions, CredentialService credentials, GitHubPullRequestReadClient pullRequests,
            GitHubPullRequestWriteClient writes, Clock clock, Runnable waitForCi, DevelopmentBranchService branches)
    {
        this.turns = requireNonNull(turns, "turns is null");
        this.turnStore = requireNonNull(turnStore, "turnStore is null");
        this.git = requireNonNull(git, "git is null");
        this.executor = requireNonNull(executor, "executor is null");
        this.ci = requireNonNull(ci, "ci is null");
        this.actions = requireNonNull(actions, "actions is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.writes = requireNonNull(writes, "writes is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.waitForCi = requireNonNull(waitForCi, "waitForCi is null");
        this.branches = requireNonNull(branches, "branches is null");
    }

    /** Called after turn admission and before provider launch, outside the task lock. */
    public void accepted(AgentTask task, AgentTurn turn)
    {
        DevelopmentBaseline baseline = git.capture(task);
        Optional<Accepted> previous = Optional.ofNullable(accepted.get(task.id()));
        boolean pending = priorQuestion(task.id(), turn.id()) || previous.filter(prior -> prior.localPending()
                || changed(prior.baseline().local(), baseline.local())).isPresent();
        DevelopmentAutomation automation = turn.settings().orElseThrow().developmentAutomation().orElse(DevelopmentAutomation.LOCAL_ONLY);
        CompletableFuture<RemoteBaseline> priorRemote = previous.map(Accepted::remote).orElseGet(() -> CompletableFuture.completedFuture(RemoteBaseline.NONE));
        // The remote read reaches the network, so it runs beside the turn instead of inside Send; only the turn's end uses it.
        CompletableFuture<RemoteBaseline> remote = automation.autoDraftPr() || automation.autoCiFix()
                ? priorRemote.thenApplyAsync(prior -> readRemote(baseline, previous.map(Accepted::baseline), prior), executor)
                : priorRemote.thenApply(RemoteBaseline::carried);
        Accepted captured = new Accepted(turn.id(), baseline, previous.map(Accepted::summaryBaseline).orElse(baseline.local()), pending, remote);
        accepted.put(task.id(), captured);
        // Stop can settle the turn while the capture above is still in flight.
        if (turnStore.findActive(task.id()).filter(active -> active.id().equals(turn.id())).isEmpty()) {
            accepted.remove(task.id(), captured);
        }
    }

    private RemoteBaseline readRemote(DevelopmentBaseline baseline, Optional<DevelopmentBaseline> priorBaseline, RemoteBaseline prior)
    {
        try {
            DevelopmentPublicationFacts current = git.inspectPublication(baseline.worktree(), baseline.route());
            // A queued turn captures its baseline after the previous turn's CLI publication.
            boolean published = prior.failure().isEmpty() && priorBaseline.filter(value -> value.route().equals(baseline.route())).isPresent()
                    && prior.publication().filter(original -> newlyPublished(baseline, original, current)).isPresent();
            return new RemoteBaseline(Optional.of(current), Optional.empty(), prior.pending() || published);
        }
        catch (RuntimeException failure) {
            // Remote prerequisites must not prevent the initial local development work.
            return new RemoteBaseline(Optional.empty(), Optional.of(message(failure)), prior.pending());
        }
    }

    public void completed(DevelopmentTurnCompletion completion)
    {
        requireNonNull(completion, "completion is null");
        try {
            Instant activatedAt = Instant.now(clock);
            executor.execute(() -> advance(completion, activatedAt));
        }
        catch (RuntimeException failure) {
            fail(completion, "Could not start the development follow-up: " + message(failure));
            accepted.computeIfPresent(completion.task().id(), (id, current) -> current.turnId().equals(completion.turn().id()) ? null : current);
        }
    }

    /** Keeps the accepted destination and starting revision while adopting a verified unpublished name. */
    public void branchRenamed(TaskId taskId, TaskWorktree previous, TaskWorktree renamed)
    {
        accepted.computeIfPresent(taskId, (id, source) -> {
            if (source.baseline().worktree().equals(renamed)) {
                return source;
            }
            checkState(source.baseline().worktree().equals(previous), "The accepted development branch changed");
            DevelopmentBaseline baseline = source.baseline();
            return new Accepted(source.turnId(), new DevelopmentBaseline(renamed, baseline.route().withHeadBranch(renamed.generatedBranch()), baseline.local()),
                    source.summaryBaseline(), source.localPending(), source.remote().thenApply(remote ->
                            new RemoteBaseline(Optional.of(new DevelopmentPublicationFacts(Optional.empty(), ImmutableList.of())), Optional.empty(), remote.pending())));
        });
    }

    private void advance(DevelopmentTurnCompletion completion, Instant activatedAt)
    {
        if (!owns(completion)) {
            accepted.computeIfPresent(completion.task().id(), (id, value) -> value.turnId().equals(completion.turn().id()) ? null : value);
            return;
        }
        String action = "Check development result";
        try {
            requireSuccessful(completion);
            if (completion.turn().developmentResult().filter(DevelopmentResultReport::readyForDelivery).isPresent()) {
                turns.executeDevelopmentAction(completion.task().id(), completion.turn().id(), () -> branches.prepare(completion.task()));
            }
            Optional<Accepted> captured = Optional.ofNullable(accepted.get(completion.task().id()))
                    .filter(value -> value.turnId().equals(completion.turn().id()));
            DevelopmentAutomation automation = completion.turn().settings().orElseThrow().developmentAutomation().orElse(DevelopmentAutomation.LOCAL_ONLY);
            if (!automation.enabled()) {
                finish(completion);
                return;
            }
            // A turn that only answered a question leaves the checkout exactly as it was accepted, so a
            // missing report withholds nothing and means the same as readyForDelivery=false: wait for the
            // user. A turn that did change something must still report, or finished work would be dropped.
            if (completion.turn().developmentResult().isEmpty() && captured.isPresent() && !undelivered(captured.get())) {
                finish(completion, true);
                return;
            }
            DevelopmentResultReport report = requireResult(completion);
            if (!report.readyForDelivery()) {
                finish(completion);
                return;
            }
            Accepted source = captured.orElseThrow(() -> new IllegalStateException("The accepted development baseline is unavailable"));
            if (turns.handoffDevelopmentContinuationToQueue(completion.task().id(), completion.turn().id())) {
                return;
            }
            DevelopmentBaseline baseline = source.baseline();
            DevelopmentLocalFacts local = inspectLocal(baseline);
            boolean pendingWork = changed(baseline.local(), local) || source.pendingDelivery();
            if (!pendingWork && local.uncommittedFileCount() > 0) {
                finish(completion);
                return;
            }
            if (local.uncommittedFileCount() > 0) {
                if (!automation.autoCommit()) {
                    finish(completion);
                    return;
                }
                action = "Auto commit";
                DevelopmentLocalFacts beforeCommit = local;
                DevelopmentTurnCompletion committed = followup(completion, DevelopmentTurnOrigin.Kind.AUTO_COMMIT, commitPrompt(baseline, local));
                if (!requireResult(committed).readyForDelivery()) {
                    finish(completion);
                    return;
                }
                local = inspectLocal(baseline);
                checkState(local.uncommittedFileCount() == 0, "Auto commit left uncommitted task changes");
                checkState(!local.headSha().equals(beforeCommit.headSha()), "Auto commit did not create a commit");
                checkState(git.isAncestor(baseline.worktree(), beforeCommit.headSha(), local.headSha()), "Auto commit rewrote existing task history");
            }
            if (local.commitCount() == 0 || !local.diffFromBase() || (!automation.autoDraftPr() && !automation.autoCiFix())) {
                finish(completion);
                return;
            }
            action = "Check publication";
            RemoteBaseline remote = source.remote().join();
            checkState(remote.failure().isEmpty(), "Remote baseline unavailable: %s", remote.failure().orElse(""));
            DevelopmentPublicationFacts original = remote.publication().orElseThrow(() -> new IllegalStateException("The accepted remote baseline is unavailable"));
            DevelopmentPublicationFacts publication = git.inspectPublication(baseline.worktree(), baseline.route());
            Optional<GitHubPullRequestIdentity> originalPr = matchingPr(baseline.route(), original);
            Optional<GitHubPullRequestIdentity> currentPr = matchingPr(baseline.route(), publication);
            if (originalPr.isPresent()) {
                checkState(currentPr.map(GitHubPullRequestIdentity::pullRequest).equals(originalPr.map(GitHubPullRequestIdentity::pullRequest)),
                        "The task's pull request changed during development");
            }
            boolean alreadyPushed = publication.remoteHeadSha().filter(local.headSha()::equals).isPresent();
            if (!pendingWork && !newlyPublished(baseline, original, publication)) {
                finish(completion);
                return;
            }
            boolean alreadyPublished = alreadyPushed
                    && currentPr.map(GitHubPullRequestIdentity::headSha).filter(local.headSha()::equals).isPresent();
            if (!alreadyPublished) {
                if (!automation.autoDraftPr()) {
                    finish(completion);
                    return;
                }
                // An explicitly requested CLI push can finish before the generated draft-creation step.
                checkState(alreadyPushed || publication.remoteHeadSha().equals(original.remoteHeadSha()), "The remote branch changed during development");
                checkState(currentPr.map(GitHubPullRequestIdentity::pullRequest).equals(originalPr.map(GitHubPullRequestIdentity::pullRequest)),
                        "The pull request changed during development");
                if (publication.remoteHeadSha().isPresent()) {
                    String remoteHead = publication.remoteHeadSha().orElseThrow();
                    // A user turn may rewrite published commits that belonged to its starting history.
                    checkState(git.isAncestor(baseline.worktree(), remoteHead, local.headSha())
                            || git.isAncestor(baseline.worktree(), remoteHead, baseline.local().headSha()),
                            "Remote history belongs to neither the current nor the starting task revision");
                }
                action = "Create draft PR";
                PendingPublication window = new PendingPublication(completion.turn().id(), baseline, local.headSha(), publication.remoteHeadSha(), currentPr);
                publications.put(completion.task().id(), window);
                DevelopmentTurnCompletion published;
                try {
                    published = followup(completion, DevelopmentTurnOrigin.Kind.CREATE_DRAFT_PR, publicationPrompt(baseline, local, currentPr));
                }
                finally {
                    // The window authorizes exactly this follow-up; a later turn must not publish.
                    publications.remove(completion.task().id(), window);
                }
                if (!requireResult(published).readyForDelivery()) {
                    finish(completion);
                    return;
                }
                DevelopmentLocalFacts after = inspectLocal(baseline);
                checkState(after.uncommittedFileCount() == 0 && after.headSha().equals(local.headSha()), "Publication changed the verified local revision");
                publication = git.inspectPublication(baseline.worktree(), baseline.route());
                Optional<GitHubPullRequestIdentity> verifiedPr = matchingPr(baseline.route(), publication);
                checkState(publication.remoteHeadSha().filter(local.headSha()::equals).isPresent(), "Remote result unknown: pushed revision was not verified");
                checkState(verifiedPr.isPresent() && verifiedPr.orElseThrow().headSha().equals(local.headSha()), "Remote result unknown: matching pull request was not verified");
                if (currentPr.isPresent()) {
                    checkState(verifiedPr.orElseThrow().pullRequest().equals(currentPr.orElseThrow().pullRequest()), "Publication replaced the task's pull request");
                }
                else {
                    checkState(verifiedPr.orElseThrow().draft(), "The created pull request is not a draft");
                }
                currentPr = verifiedPr;
            }
            if (automation.autoCiFix()) {
                action = "CI Autofix";
                GitHubPullRequestIdentity publishedPr = currentPr.orElseThrow();
                Optional<CiAutofixRun> green = runCi(completion, publishedPr, automation, activatedAt);
                if (green.isPresent()) {
                    action = "Verify CI and finish delivery";
                    String sourceHead = local.headSha();
                    turns.executeDevelopmentAction(completion.task().id(), completion.turn().id(),
                            () -> finishDelivery(completion, baseline, sourceHead, publishedPr, green.orElseThrow()));
                }
            }
            finish(completion);
        }
        catch (RuntimeException failure) {
            String detail = message(failure);
            fail(completion, detail.startsWith("Refine summary:") ? detail : action + ": " + detail
                    + (action.equals("Create draft PR") ? ". Remote result may have changed; inspect the branch and PR before another publication attempt." : ""));
        }
        finally {
            accepted.computeIfPresent(completion.task().id(), (id, value) -> value.turnId().equals(completion.turn().id()) ? null : value);
        }
    }

    private Optional<CiAutofixRun> runCi(DevelopmentTurnCompletion source, GitHubPullRequestIdentity pullRequest, DevelopmentAutomation automation, Instant activatedAt)
    {
        checkState(owns(source), "Development automation was stopped");
        var lane = automation.ciLaneId().orElse(source.task().laneId());
        CiAutofixRun run = ci.startDevelopment(new StartCiAutofixCommand(
                source.task().workspaceId().orElseThrow(),
                pullRequest.pullRequest().number(),
                lane,
                "development-ci:" + source.task().id() + ":" + source.turn().id()),
                source.task().id(), source.turn().id(), pullRequest, activatedAt);
        boolean waitingForGitHub = false;
        while (owns(source)) {
            if (run.status() == CiAutofixRun.Status.FINISHED) {
                checkState(run.finishReason().filter(reason -> reason == CiAutofixRun.FinishReason.GREEN).isPresent(),
                        "CI Autofix ended: %s", run.summary().orElse(run.finishReason().map(Enum::name).orElse("Unknown result")));
                return Optional.of(run);
            }
            if (run.status() == CiAutofixRun.Status.PAUSED && !ci.canMessage(run)) {
                CiAutofixRun latest = ci.get(run.id());
                if (!latest.equals(run)) {
                    // A reply can start a repair between polling and checking the paused run.
                    run = latest;
                    continue;
                }
                throw new IllegalStateException("CI Autofix cannot continue: " + run.summary().orElseGet(() -> latest.pauseReason().map(Enum::name).orElse("Unknown result")));
            }
            // The coordinator owns polling; a renderer reload or an independent monitor cannot drive this run.
            try {
                // Each advance reloads durable state and reconciles any uncertain writes before progressing.
                run = ci.advance(run.id());
            }
            catch (RuntimeException failure) {
                if (!retryableCiFailure(failure)) {
                    throw failure;
                }
                if (!waitingForGitHub) {
                    progress(source, CI_CONNECTION_WAIT);
                    log.warn("Cannot poll CI for development task {}; waiting for GitHub", source.task().id(), failure);
                }
                waitingForGitHub = true;
                waitForCi.run();
                continue;
            }
            if (waitingForGitHub) {
                progress(source, "GitHub connection restored; continuing CI Autofix");
                waitingForGitHub = false;
            }
            if (run.status() != CiAutofixRun.Status.FINISHED) {
                waitForCi.run();
            }
        }
        return Optional.empty();
    }

    private void finishDelivery(DevelopmentTurnCompletion source, DevelopmentBaseline baseline, String sourceHead,
            GitHubPullRequestIdentity publishedPr, CiAutofixRun green)
    {
        // CI persists GREEN only after completing its child task and proving its writers stopped.
        Runnable requireAuthority = () -> checkState(owns(source), "Development automation was stopped");
        progress(source, "Verifying all pull request checks");
        String token = credentials.resolveGitHubToken(baseline.worktree().workspaceId());
        awaitDeliveryChecks(source, baseline, publishedPr, green, token, requireAuthority);
        progress(source, "Synchronizing source worktree");
        git.synchronizeSource(baseline, sourceHead, green.expectedRemoteHead(), requireAuthority);
        progress(source, "Rechecking pull request before finishing delivery");
        GitHubDeliveryChecks verified = awaitDeliveryChecks(source, baseline, publishedPr, green, token, requireAuthority);
        requireSynchronizedSource(baseline, green.expectedRemoteHead());
        if (!verified.pullRequest().draft()) {
            return;
        }
        progress(source, "Marking PR ready for review");
        requireAuthority.run();
        try {
            // GitHub has no head-conditioned ready mutation. Never retry an uncertain response.
            writes.markReadyForReview(token, verified.nodeId());
            GitHubPullRequestIdentity after = pullRequests.fetchPullRequestIdentity(token, publishedPr.pullRequest());
            verifyDeliveryPr(baseline.route(), publishedPr, green, after);
            checkState(!after.draft(), "Pull request still appears as a draft");
            GitHubDeliveryChecks afterChecks = pullRequests.fetchDeliveryChecks(token, publishedPr.pullRequest());
            verifyDeliveryPr(baseline.route(), publishedPr, green, afterChecks.pullRequest());
            checkState(!afterChecks.pullRequest().draft() && afterChecks.green(), "Pull request checks changed during promotion");
            checkState(inspectDeliveryActions(publishedPr, green).green(), "Actions checks changed during promotion");
            verifyDeliveryPublication(baseline, publishedPr, green);
            requireSynchronizedSource(baseline, green.expectedRemoteHead());
        }
        catch (RuntimeException failure) {
            throw new IllegalStateException("Ready-for-review result may have changed; inspect the PR before starting over. " + message(failure), failure);
        }
    }

    private GitHubDeliveryChecks awaitDeliveryChecks(DevelopmentTurnCompletion source, DevelopmentBaseline baseline,
            GitHubPullRequestIdentity publishedPr, CiAutofixRun green, String token, Runnable requireAuthority)
    {
        String waiting = "Full pull request check evidence is unavailable";
        while (owns(source)) {
            requireAuthority.run();
            GitHubDeliveryChecks checks;
            CiHeadSnapshot snapshot;
            try {
                checks = pullRequests.fetchDeliveryChecks(token, publishedPr.pullRequest());
                verifyDeliveryPr(baseline.route(), publishedPr, green, checks.pullRequest());
                snapshot = inspectDeliveryActions(publishedPr, green);
                verifyDeliveryPublication(baseline, publishedPr, green);
            }
            catch (RuntimeException failure) {
                if (!retryableCiFailure(failure)) {
                    throw failure;
                }
                if (!waiting.equals(CI_CONNECTION_WAIT)) {
                    progress(source, CI_CONNECTION_WAIT);
                    log.warn("Cannot read PR checks for development task {}; waiting for GitHub", source.task().id(), failure);
                    waiting = CI_CONNECTION_WAIT;
                }
                waitForCi.run();
                continue;
            }
            checkState(checks.failures().isEmpty(), "Pull request checks need attention: %s", String.join("; ", checks.failures()));
            requireAuthority.run();
            checkState(snapshot.actionableFailures().isEmpty(), "Actions checks failed after the CI repair finished");
            if (checks.green() && snapshot.green()) {
                return checks;
            }
            String observed = String.join("; ", checks.waiting())
                    + (snapshot.green() ? "" : "; Actions checks are incomplete or not passing; required workflows may exclude drafts");
            if (!observed.equals(waiting)) {
                progress(source, "Waiting for PR checks: " + observed);
                waiting = observed;
            }
            waitForCi.run();
        }
        throw new IllegalStateException("Development automation was stopped");
    }

    private static boolean retryableCiFailure(RuntimeException failure)
    {
        if (Thread.currentThread().isInterrupted()) {
            return false;
        }
        if (failure instanceof ResourceAccessException) {
            return true;
        }
        return failure instanceof ResponseStatusException response
                && (response.getStatusCode().is5xxServerError() || response.getStatusCode().value() == 408 || response.getStatusCode().value() == 429);
    }

    private CiHeadSnapshot inspectDeliveryActions(GitHubPullRequestIdentity publishedPr, CiAutofixRun green)
    {
        CiHeadSnapshot snapshot = actions.inspect(publishedPr.pullRequest());
        checkState(snapshot.pullRequest().equals(publishedPr.pullRequest()) && snapshot.open()
                && snapshot.headRepository().equals(publishedPr.headRepository())
                && snapshot.headRef().equals(publishedPr.headBranch()) && snapshot.baseRef().equals(publishedPr.baseBranch())
                && snapshot.headSha().equals(green.expectedRemoteHead()) && snapshot.baseSha().equals(green.latestObservedBaseSha()),
                "Actions evidence no longer matches the verified pull request");
        return snapshot;
    }

    private void progress(DevelopmentTurnCompletion source, String message)
    {
        turns.reportDevelopmentProgress(source.task().id(), source.turn().id(), message.substring(0, Math.min(message.length(), 8 * 1024)));
    }

    private void verifyDeliveryPublication(DevelopmentBaseline baseline, GitHubPullRequestIdentity publishedPr, CiAutofixRun green)
    {
        GitHubPullRequestIdentity remote = matchingPr(baseline.route(), git.inspectPublication(baseline.worktree(), baseline.route()))
                .orElseThrow(() -> new IllegalStateException("The published pull request is no longer available"));
        verifyDeliveryPr(baseline.route(), publishedPr, green, remote);
    }

    private static void verifyDeliveryPr(DevelopmentPublicationRoute route, GitHubPullRequestIdentity publishedPr, CiAutofixRun green, GitHubPullRequestIdentity current)
    {
        checkState(current.pullRequest().equals(publishedPr.pullRequest()) && current.state() == GitHubPullRequestIdentity.State.OPEN
                && current.headRepository().equals(route.headRepository()) && current.headBranch().equals(route.headBranch())
                && current.baseRepository().equals(route.baseRepository()) && current.baseBranch().equals(route.baseBranch()),
                "Pull request identity or destination changed after CI");
        checkState(current.headSha().equals(green.expectedRemoteHead()) && current.baseSha().equals(green.latestObservedBaseSha()),
                "Pull request head or base changed after verified CI");
    }

    private void requireSynchronizedSource(DevelopmentBaseline baseline, String publishedHead)
    {
        DevelopmentLocalFacts local = inspectLocal(baseline);
        checkState(local.headSha().equals(publishedHead) && local.uncommittedFileCount() == 0, "Source worktree changed after synchronization");
    }

    private DevelopmentLocalFacts inspectLocal(DevelopmentBaseline baseline)
    {
        DevelopmentLocalFacts local = git.inspectLocal(baseline.worktree(), baseline.route());
        checkState(!local.gitOperationInProgress(), "A Git operation still needs attention");
        return local;
    }

    private DevelopmentTurnCompletion followup(DevelopmentTurnCompletion source, DevelopmentTurnOrigin.Kind kind, String prompt)
    {
        checkState(owns(source), "Development automation was stopped");
        return turns.startGeneratedTurn(source.task().id(), new DevelopmentTurnOrigin(kind, source.turn().id()), prompt).toCompletableFuture().join();
    }

    static Optional<GitHubPullRequestIdentity> matchingPr(DevelopmentPublicationRoute route, DevelopmentPublicationFacts facts)
    {
        checkState(facts.matchingPullRequests().size() <= 1, "Several pull requests match the task branch");
        Optional<GitHubPullRequestIdentity> found = facts.matchingPullRequests().stream().findFirst();
        route.assignedPullRequest().ifPresent(assigned -> checkState(found.map(GitHubPullRequestIdentity::pullRequest).filter(assigned::equals).isPresent(),
                "The assigned pull request is unavailable; no replacement will be created"));
        found.ifPresent(pr -> {
            checkState(pr.state() == GitHubPullRequestIdentity.State.OPEN, "The task's pull request is closed or merged");
            checkState(pr.headRepository().equals(route.headRepository()) && pr.headBranch().equals(route.headBranch())
                    && pr.baseRepository().equals(route.baseRepository()) && pr.baseBranch().equals(route.baseBranch()),
                    "Pull request base or head does not match the accepted destination");
            checkState(facts.remoteHeadSha().filter(pr.headSha()::equals).isPresent(), "Pull request and remote branch revisions do not agree");
        });
        return found;
    }

    /** Whether the turn left code or commits the accepted automation would still have to deliver. */
    private boolean undelivered(Accepted source)
    {
        return source.pendingDelivery() || changed(source.baseline().local(), inspectLocal(source.baseline()));
    }

    private boolean priorQuestion(TaskId taskId, TurnId acceptedTurn)
    {
        List<AgentTurn> history = turnStore.list(taskId).stream().filter(turn -> !turn.id().equals(acceptedTurn))
                .filter(turn -> turn.developmentOrigin().filter(origin -> origin.kind() == DevelopmentTurnOrigin.Kind.REFINE_SUMMARY).isEmpty())
                .filter(turn -> turn.developmentResult().isPresent()).toList();
        return !history.isEmpty() && history.getLast().state() == TurnState.COMPLETED
                && history.getLast().developmentResult().filter(report -> !report.readyForDelivery()).isPresent();
    }

    private static boolean changed(DevelopmentLocalFacts before, DevelopmentLocalFacts after)
    {
        return !before.headSha().equals(after.headSha()) || !before.fingerprint().equals(after.fingerprint());
    }

    private static boolean newlyPublished(DevelopmentBaseline baseline, DevelopmentPublicationFacts original, DevelopmentPublicationFacts current)
    {
        if (baseline.local().uncommittedFileCount() > 0) {
            return false;
        }
        Optional<GitHubPullRequestIdentity> originalPr = matchingPr(baseline.route(), original);
        Optional<GitHubPullRequestIdentity> currentPr = matchingPr(baseline.route(), current);
        boolean samePr = currentPr.map(GitHubPullRequestIdentity::pullRequest).equals(originalPr.map(GitHubPullRequestIdentity::pullRequest));
        checkState(originalPr.isEmpty() || samePr, "The task's pull request changed during development");
        if (current.remoteHeadSha().equals(original.remoteHeadSha()) && samePr) {
            return false;
        }
        // A push or PR creation can publish finished work without changing the local checkout.
        checkState(current.remoteHeadSha().filter(baseline.local().headSha()::equals).isPresent(), "The published revision does not match the clean task revision");
        return true;
    }

    private static void requireSuccessful(DevelopmentTurnCompletion completion)
    {
        checkState(completion.turn().state() == TurnState.COMPLETED, "%s", completion.turn().terminalReason().orElse("Development turn did not complete successfully"));
        checkState(completion.turn().cancellationRequestedAt().isEmpty(), "Development was stopped before delivery");
        checkState(completion.providerQuiescent(), "Provider shutdown could not be verified");
        checkState(completion.failure().isEmpty(), "Development turn cleanup failed: %s", completion.failure().orElse(""));
    }

    private static DevelopmentResultReport requireResult(DevelopmentTurnCompletion completion)
    {
        requireSuccessful(completion);
        return completion.turn().developmentResult().orElseThrow(() -> new IllegalStateException("The root agent did not report a valid development result"));
    }

    private void finish(DevelopmentTurnCompletion completion)
    {
        finish(completion, false);
    }

    /**
     * @param awaitingUser forces the waiting branch for a turn that reported nothing and delivered nothing,
     *         whose own absent report the queue handoff would otherwise read as work not yet ready
     */
    private void finish(DevelopmentTurnCompletion completion, boolean awaitingUser)
    {
        if (!owns(completion)) {
            return;
        }
        boolean waitingForAnswer = awaitingUser || turnStore.list(completion.task().id()).reversed().stream()
                .filter(turn -> turn.developmentOrigin().filter(origin -> origin.kind() == DevelopmentTurnOrigin.Kind.REFINE_SUMMARY).isEmpty())
                .filter(turn -> turn.developmentResult().isPresent())
                .findFirst().flatMap(AgentTurn::developmentResult).filter(report -> !report.readyForDelivery()).isPresent();
        if (!waitingForAnswer && turns.handoffDevelopmentContinuationToQueue(completion.task().id(), completion.turn().id())) {
            return;
        }
        Accepted source = Optional.ofNullable(accepted.get(completion.task().id())).filter(value -> value.turnId().equals(completion.turn().id()))
                .orElseThrow(() -> new IllegalStateException("The accepted development baseline is unavailable"));
        DevelopmentLocalFacts current = inspectLocal(source.baseline());
        if (changed(source.summaryBaseline(), current)) {
            progress(completion, "Refining summary…");
            try {
                DevelopmentTurnCompletion refined = followup(completion, DevelopmentTurnOrigin.Kind.REFINE_SUMMARY, summaryPrompt(source.baseline(), current));
                DevelopmentResultReport report = requireResult(refined);
                checkState(report.readyForDelivery() && report.learningSummary().isPresent(), "The agent did not record the refined learning summary");
                checkState(!changed(current, inspectLocal(source.baseline())), "Summary refinement changed the task's code or commits");
                checkState(owns(completion), "Summary refinement was stopped");
                accepted.replace(completion.task().id(), source,
                        new Accepted(source.turnId(), source.baseline(), current, source.localPending(), source.remote()));
            }
            catch (RuntimeException failure) {
                turnStore.list(completion.task().id()).stream()
                            .filter(turn -> turn.state().terminal())
                            .filter(turn -> turn.developmentOrigin().filter(origin -> origin.kind() == DevelopmentTurnOrigin.Kind.REFINE_SUMMARY
                                    && origin.parentUserTurnId().equals(completion.turn().id())).isPresent())
                            .filter(turn -> turn.developmentResult().flatMap(DevelopmentResultReport::learningSummary).isPresent())
                            .forEach(turn -> turnStore.update(turn.withoutLearningSummary()));
                throw new IllegalStateException("Refine summary: " + message(failure), failure);
            }
        }
        if (!waitingForAnswer && owns(completion)
                && turns.handoffDevelopmentContinuationToQueue(completion.task().id(), completion.turn().id())) {
            return;
        }
        if (owns(completion)) {
            turns.finishDevelopmentContinuation(completion.task().id(), completion.turn().id());
        }
    }

    static String summaryPrompt(DevelopmentBaseline baseline, DevelopmentLocalFacts local)
    {
        return """
                ByteQuay · Refining summary
                Refresh this task's private Review & Learning cards from the final code and actual commits.
                Worktree: %s. Branch: %s. Expected HEAD: %s. Frozen comparison base: %s.

                List only commits in frozen comparison base..HEAD, excluding the base and earlier history; use an empty
                commits list when that range is empty. Read the current diff, including any uncommitted changes. Explain the intention and
                why each commit's design and implementation solve it. Explain uncommitted work in the intention; distinguish
                stated intent from inferred rationale.
                Ground explanations in code; never invent rationale or verification. Follow shared card writing targets and
                applicable writing skills: short, complete sentences. Put bugs and code improvements in anchored Findings
                when review tools are available. Replace stale prose rather than appending another recap.

                Reporting only: do not edit files, create or rewrite commits, push, publish, run repairs, or start a review.
                Keep all text private; never write a summary file. Preserve the existing question and pending user decision;
                do not release held work.

                Call report_development_result with readyForDelivery=true and learningSummary containing only purpose and
                commits (one per actual commit, with full sha, title and summary); only these two fields.
                Readiness here records the summary only; it does not authorize delivery of held work.
                """.formatted(quote(baseline.worktree().canonicalWorktree().toString()), quote(baseline.worktree().generatedBranch()), local.headSha(), baseline.route().baseSha());
    }

    private boolean owns(DevelopmentTurnCompletion completion)
    {
        return turns.ownsDevelopmentContinuation(completion.task().id(), completion.turn().id());
    }

    private void fail(DevelopmentTurnCompletion completion, String reason)
    {
        if (!owns(completion)) {
            return;
        }
        try {
            turns.failDevelopmentContinuation(completion.task().id(), completion.turn().id(), "Automation stopped. " + reason);
        }
        catch (RuntimeException failure) {
            log.error("Could not settle development follow-up failure for task {}", completion.task().id(), failure);
        }
    }

    static String commitPrompt(DevelopmentBaseline baseline, DevelopmentLocalFacts local)
    {
        return """
                ByteQuay · Auto commit
                The accepted Auto commit setting authorizes committing only the completed task changes.
                Worktree: %s. Branch: %s. Expected starting HEAD: %s.

                Work only in this worktree and branch. Verify ownership, branch and HEAD before changes; stop and report any mismatch.
                Make focused commits from the actual changes using repository commit conventions.
                Preserve existing commits: never amend, rebase, reset, or change branches. Do not discard changes to clean
                the checkout. Exclude ignored build output and credentials. Never add an attribution, co-authorship, or
                generated-with trailer. Preserve previously reported local validation limitations.
                Do not push or create a pull request in this turn.

                Call report_development_result before ending: readyForDelivery=false if a user decision remains, and ask
                in the final response; otherwise report readiness. ByteQuay controls later stages using accepted automation settings.
                """.formatted(quote(baseline.worktree().canonicalWorktree().toString()), quote(baseline.worktree().generatedBranch()), local.headSha());
    }

    /**
     * Publishes the pending revision for one task; the provider tool calls this instead of a shell.
     *
     * <p>Only an open publication window authorizes the write, and the window carries the verified
     * revision, the accepted destination, and the remote lease. The caller chooses the title and
     * body and nothing else, so a session cannot retarget the push, publish a different commit, or
     * reach a credential. Repeating the call is safe: an already-published revision reports the
     * existing pull request rather than opening a second one.
     *
     * @param taskId task whose publication window must be open
     * @param title pull request title
     * @param body pull request body
     * @return a short result naming the created or reused pull request
     */
    public String publishDraftPullRequest(String taskId, String title, String body)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(title, "title is null");
        requireNonNull(body, "body is null");
        checkArgument(!title.isBlank(), "The pull request title is blank");
        TaskId id = TaskId.valueOf(taskId);
        PendingPublication window = publications.get(id);
        checkState(window != null, "ByteQuay is not requesting a draft pull request for this task right now");
        // A window outlives the manual turn it was opened for, so identity is checked here rather
        // than by removing it: only a publication turn may spend one.
        checkState(turnStore.findActive(id)
                        .flatMap(AgentTurn::developmentOrigin)
                        .filter(origin -> origin.kind() == DevelopmentTurnOrigin.Kind.CREATE_DRAFT_PR)
                        .isPresent(),
                "This turn is not ByteQuay's draft pull request request");
        DevelopmentPublicationRoute route = window.baseline().route();
        TaskWorktree worktree = window.baseline().worktree();
        DevelopmentLocalFacts local = git.inspectLocal(worktree, route);
        checkState(local.headSha().equals(window.localHead()) && local.uncommittedFileCount() == 0, "The verified local revision changed; publication was not attempted");
        DevelopmentPublicationFacts facts = git.inspectPublication(worktree, route);
        if (facts.remoteHeadSha().filter(window.localHead()::equals).isEmpty()) {
            checkState(facts.remoteHeadSha().equals(window.expectedRemoteSha()), "The remote branch changed; publication was not attempted");
            git.pushDevelopmentHead(worktree, route, window.localHead(), window.expectedRemoteSha());
            facts = git.inspectPublication(worktree, route);
        }
        checkState(facts.remoteHeadSha().filter(window.localHead()::equals).isPresent(), "The pushed revision was not verified on the remote branch");
        Optional<GitHubPullRequestIdentity> existing = matchingPr(route, facts);
        if (existing.isPresent()) {
            GitHubPullRequestIdentity pullRequest = existing.orElseThrow();
            window.existingPr().ifPresent(original -> checkState(pullRequest.pullRequest().equals(original.pullRequest()), "Publication replaced the task's pull request"));
            return "Pushed " + window.localHead() + "; pull request " + pullRequest.url() + " already tracks this branch.";
        }
        String head = route.headRepository().equals(route.baseRepository()) ? route.headBranch() : route.headRepository().owner() + ":" + route.headBranch();
        CreatedPullRequest created = writes.createPullRequest(
                credentials.resolveGitHubToken(worktree.workspaceId()),
                route.baseRepository(), head, route.baseBranch(), withoutAttribution(title), withoutAttribution(body), true);
        return "Pushed " + window.localHead() + "; created draft pull request " + created.url();
    }

    /**
     * Drops a provider's own sign-off from a body about to be published.
     *
     * <p>A session's built-in rules tell it to sign what it writes; this repository's rules do not,
     * and the request says so. Reviewers read this body on a public pull request, so the prompt is
     * not left as the only thing standing between them and a tool advertisement.
     */
    static String withoutAttribution(String body)
    {
        return ATTRIBUTION.matcher(body).replaceAll("").strip();
    }

    /** One task's authorization to publish: the accepted destination, revision, and remote lease. */
    private record PendingPublication(
            TurnId turnId,
            DevelopmentBaseline baseline,
            String localHead,
            Optional<String> expectedRemoteSha,
            Optional<GitHubPullRequestIdentity> existingPr)
    {
        private PendingPublication
        {
            requireNonNull(turnId, "turnId is null");
            requireNonNull(baseline, "baseline is null");
            requireNonNull(localHead, "localHead is null");
            requireNonNull(expectedRemoteSha, "expectedRemoteSha is null");
            requireNonNull(existingPr, "existingPr is null");
        }
    }

    static String publicationPrompt(DevelopmentBaseline baseline, DevelopmentLocalFacts local, Optional<GitHubPullRequestIdentity> pullRequest)
    {
        DevelopmentPublicationRoute route = baseline.route();
        String prInstruction = pullRequest
                .map(pr -> "Pull request " + pr.url() + " already tracks this branch; publishing updates it and creates no second one.")
                .orElse("No pull request tracked this branch at verification.");
        return """
                ByteQuay · Create draft PR
                The accepted Auto draft PR setting authorizes this single publication of the verified revision.
                Worktree: %s. Branch: %s. Exact clean HEAD to publish: %s.
                Base repository/branch: %s / %s. Push repository/branch: %s / %s.
                %s

                Recheck branch, exact HEAD, clean staged/unstaged/untracked status (excluding ignored build output),
                and no open Git operation. Stop and report any mismatch.
                Do not commit, edit code, amend, rebase, reset, switch branches, or run an independent CI repair loop.
                Call %s exactly once with title and body as tool input; never write a body file.
                ByteQuay owns the push and the GitHub write: exact HEAD, its own lease, draft PR creation or existing PR update.
                Never run git push, a GitHub CLI command, or other remote writes; never read or ask for credentials.

                Derive title and body from the actual change and repository PR template. Fill relevant sections only;
                leave other sections exactly as the template leaves them. Describe changed tests by the behavior they cover.
                Keep local execution details private to this task: commands run, test classes, counts, pass/fail results,
                build or validation outcomes, logs, tooling, sandbox and environment limitations. Never put these in the PR,
                including Testing, Validation or Test plan sections. Never add an attribution, co-authorship, or generated-with trailer.

                If the tool refuses or its result is uncertain, stop without retrying and report its response.
                Call report_development_result before ending: readyForDelivery=false if a user decision is needed, and ask it;
                otherwise report readiness.
                """.formatted(quote(baseline.worktree().canonicalWorktree().toString()), quote(baseline.worktree().generatedBranch()), local.headSha(),
                        route.baseRepository().fullName(), quote(route.baseBranch()), route.headRepository().fullName(), quote(route.headBranch()),
                        prInstruction, DevelopmentResultBridge.PUBLISH_TOOL_NAME);
    }

    private static String quote(String value)
    {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String message(RuntimeException failure)
    {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        return Optional.ofNullable(cause.getMessage()).filter(value -> !value.isBlank()).orElse(cause.getClass().getSimpleName());
    }

    private record Accepted(TurnId turnId, DevelopmentBaseline baseline, DevelopmentLocalFacts summaryBaseline, boolean localPending,
            CompletableFuture<RemoteBaseline> remote)
    {
        /** Waits for the remote read when the accepted turn needed one; only a turn's completion asks. */
        private boolean pendingDelivery()
        {
            return localPending || remote.join().pending();
        }
    }

    /** The remote branch and pull request as a turn was accepted, and whether earlier publication still awaits delivery. */
    private record RemoteBaseline(Optional<DevelopmentPublicationFacts> publication, Optional<String> failure, boolean pending)
    {
        private static final RemoteBaseline NONE = new RemoteBaseline(Optional.empty(), Optional.empty(), false);

        private RemoteBaseline carried()
        {
            return new RemoteBaseline(Optional.empty(), Optional.empty(), pending);
        }
    }
}
