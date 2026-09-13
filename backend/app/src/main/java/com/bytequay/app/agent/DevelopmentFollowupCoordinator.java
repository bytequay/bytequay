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
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.spi.DevelopmentResultReport;
import com.bytequay.agent.spi.DevelopmentStopAfter;
import com.bytequay.app.ci.CiAutofixService;
import com.bytequay.app.ci.CiHeadSnapshot;
import com.bytequay.app.ci.GitHubActionsClient;
import com.bytequay.app.ci.InspectCiHeadQuery;
import com.bytequay.app.ci.StartCiAutofixCommand;
import com.bytequay.app.repository.github.GitHubDeliveryChecks;
import com.bytequay.app.repository.github.GitHubPullRequestIdentity;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.repository.github.GitHubPullRequestWriteClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.github.GhCliService;
import com.bytequay.core.ci.CiAutofixRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

/** One live continuation per development task; neither history reads nor restart replay it. */
public final class DevelopmentFollowupCoordinator
{
    private static final Logger log = LoggerFactory.getLogger(DevelopmentFollowupCoordinator.class);

    private final AgentTurnService turns;
    private final AgentTurnStore turnStore;
    private final DevelopmentGitInspector git;
    private final GhCliService gh;
    private final Executor executor;
    private final CiAutofixService ci;
    private final GitHubActionsClient actions;
    private final CredentialService credentials;
    private final GitHubPullRequestReadClient pullRequests;
    private final GitHubPullRequestWriteClient writes;
    private final Clock clock;
    private final Runnable waitForCi;
    private final ConcurrentMap<TaskId, Accepted> accepted = new ConcurrentHashMap<>();

    public DevelopmentFollowupCoordinator(AgentTurnService turns, AgentTurnStore turnStore, DevelopmentGitInspector git, GhCliService gh,
            Executor executor, CiAutofixService ci, GitHubActionsClient actions, CredentialService credentials, GitHubPullRequestReadClient pullRequests,
            GitHubPullRequestWriteClient writes, Clock clock, Runnable waitForCi)
    {
        this.turns = requireNonNull(turns, "turns is null");
        this.turnStore = requireNonNull(turnStore, "turnStore is null");
        this.git = requireNonNull(git, "git is null");
        this.gh = requireNonNull(gh, "gh is null");
        this.executor = requireNonNull(executor, "executor is null");
        this.ci = requireNonNull(ci, "ci is null");
        this.actions = requireNonNull(actions, "actions is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.writes = requireNonNull(writes, "writes is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.waitForCi = requireNonNull(waitForCi, "waitForCi is null");
    }

    /** Called after turn admission and before provider launch, outside the task lock. */
    public void accepted(AgentTask task, AgentTurn turn)
    {
        DevelopmentBaseline baseline = git.capture(task);
        Optional<Accepted> previous = Optional.ofNullable(accepted.get(task.id()));
        boolean pending = priorQuestion(task.id(), turn.id()) || previous.filter(prior -> prior.pendingDelivery()
                || changed(prior.baseline().local(), baseline.local())).isPresent();
        Optional<DevelopmentPublicationFacts> publication = Optional.empty();
        Optional<String> remoteFailure = Optional.empty();
        DevelopmentAutomation automation = turn.settings().orElseThrow().developmentAutomation().orElseThrow();
        if (automation.autoDraftPr() || automation.autoCiFix()) {
            try {
                publication = Optional.of(git.inspectPublication(baseline.worktree(), baseline.route()));
            }
            catch (RuntimeException failure) {
                // Remote prerequisites must not prevent the initial local development work.
                remoteFailure = Optional.of(message(failure));
            }
        }
        Accepted captured = new Accepted(turn.id(), baseline, pending, publication, remoteFailure);
        accepted.put(task.id(), captured);
        // Stop can settle the turn while the read above is still in flight.
        if (turnStore.findActive(task.id()).filter(active -> active.id().equals(turn.id())).isEmpty()) {
            accepted.remove(task.id(), captured);
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

    private void advance(DevelopmentTurnCompletion completion, Instant activatedAt)
    {
        Optional<Accepted> captured = Optional.ofNullable(accepted.get(completion.task().id()))
                .filter(value -> value.turnId().equals(completion.turn().id()));
        if (!owns(completion)) {
            captured.ifPresent(value -> accepted.remove(completion.task().id(), value));
            return;
        }
        String action = "Check development result";
        try {
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
            if (!changed(baseline.local(), local) && !source.pendingDelivery()) {
                finish(completion);
                return;
            }
            DevelopmentAutomation automation = completion.turn().settings().orElseThrow().developmentAutomation().orElseThrow();
            if (local.uncommittedFileCount() > 0) {
                if (!automation.autoCommit() || !allowed(completion, DevelopmentTurnOrigin.Kind.AUTO_COMMIT)) {
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
            boolean publicationAllowed = automation.autoDraftPr() && allowed(completion, DevelopmentTurnOrigin.Kind.CREATE_DRAFT_PR);
            boolean ciAllowed = automation.autoCiFix() && ciAllowed(completion);
            if (local.commitCount() == 0 || !local.diffFromBase() || (!publicationAllowed && !ciAllowed)) {
                finish(completion);
                return;
            }
            action = "Check publication";
            checkState(source.remoteFailure().isEmpty(), "Remote baseline unavailable: %s", source.remoteFailure().orElse(""));
            DevelopmentPublicationFacts original = source.publication().orElseThrow(() -> new IllegalStateException("The accepted remote baseline is unavailable"));
            DevelopmentPublicationFacts publication = git.inspectPublication(baseline.worktree(), baseline.route());
            Optional<GitHubPullRequestIdentity> originalPr = matchingPr(baseline.route(), original);
            Optional<GitHubPullRequestIdentity> currentPr = matchingPr(baseline.route(), publication);
            if (originalPr.isPresent()) {
                checkState(currentPr.map(GitHubPullRequestIdentity::pullRequest).equals(originalPr.map(GitHubPullRequestIdentity::pullRequest)),
                        "The task's pull request changed during development");
            }
            boolean alreadyPublished = publication.remoteHeadSha().filter(local.headSha()::equals).isPresent()
                    && currentPr.map(GitHubPullRequestIdentity::headSha).filter(local.headSha()::equals).isPresent();
            if (!alreadyPublished) {
                if (!publicationAllowed) {
                    finish(completion);
                    return;
                }
                checkState(publication.remoteHeadSha().equals(original.remoteHeadSha()), "The remote branch changed during development");
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
                String binary = gh.findBinary().orElseThrow(() -> new IllegalStateException("GitHub CLI is unavailable; install and authenticate gh"));
                gh.readCredential();
                DevelopmentTurnCompletion published = followup(completion, DevelopmentTurnOrigin.Kind.CREATE_DRAFT_PR,
                        publicationPrompt(baseline, local, publication, currentPr, binary));
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
            if (ciAllowed && ciAllowed(completion)) {
                action = "CI Autofix";
                GitHubPullRequestIdentity publishedPr = currentPr.orElseThrow();
                Optional<CiAutofixRun> green = runCi(completion, publishedPr, automation, activatedAt);
                if (green.isPresent()) {
                    action = "Verify CI and finish delivery";
                    String sourceHead = local.headSha();
                    turns.executeDevelopmentAction(completion.task().id(), completion.turn().id(),
                            () -> finishDelivery(completion, baseline, sourceHead, publishedPr, green.orElseThrow(), activatedAt));
                }
            }
            finish(completion);
        }
        catch (RuntimeException failure) {
            fail(completion, action + ": " + message(failure)
                    + (action.equals("Create draft PR") ? ". Remote result may have changed; inspect the branch and PR before another publication attempt." : ""));
        }
        finally {
            captured.ifPresent(value -> accepted.remove(completion.task().id(), value));
        }
    }

    private Optional<CiAutofixRun> runCi(DevelopmentTurnCompletion source, GitHubPullRequestIdentity pullRequest, DevelopmentAutomation automation, Instant activatedAt)
    {
        checkState(owns(source), "Development automation was stopped");
        var lane = automation.ciLaneId().orElse(source.task().laneId());
        checkState(Instant.now(clock).isBefore(activatedAt.plus(Duration.ofHours(8))), "CI Autofix authorization expired before attachment");
        CiAutofixRun run = ci.startDevelopment(new StartCiAutofixCommand(source.task().workspaceId().orElseThrow(), pullRequest.pullRequest().number(),
                lane, 3, true, true, true, "development-ci:" + source.task().id() + ":" + source.turn().id()),
                source.task().id(), source.turn().id(), pullRequest, activatedAt);
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
                throw new IllegalStateException("CI Autofix cannot continue: "
                        + run.summary().orElseGet(() -> latest.pauseReason().map(Enum::name).orElse("Unknown result")));
            }
            // The coordinator owns polling; a renderer reload or an independent monitor cannot drive this run.
            run = ci.advance(run.id());
            if (run.status() != CiAutofixRun.Status.FINISHED) {
                waitForCi.run();
            }
        }
        return Optional.empty();
    }

    private void finishDelivery(DevelopmentTurnCompletion source, DevelopmentBaseline baseline, String sourceHead,
            GitHubPullRequestIdentity publishedPr, CiAutofixRun green, Instant activatedAt)
    {
        // CI persists GREEN only after completing its child task and proving its writers stopped.
        Runnable requireAuthority = () -> {
            checkState(owns(source), "Development automation was stopped");
            checkState(Instant.now(clock).isBefore(activatedAt.plus(Duration.ofHours(8)))
                    && Instant.now(clock).isBefore(green.authorization().expiresAt()), "Development CI authorization expired");
        };
        progress(source, "Verifying all pull request checks");
        String token = credentials.resolveGitHubToken(baseline.worktree().workspaceId());
        awaitDeliveryChecks(source, baseline, publishedPr, green, token, requireAuthority);
        progress(source, "Synchronizing source worktree");
        git.synchronizeSource(baseline, sourceHead, green.expectedRemoteHead(), requireAuthority);
        progress(source, "Rechecking pull request before finishing delivery");
        GitHubDeliveryChecks verified = awaitDeliveryChecks(source, baseline, publishedPr, green, token, requireAuthority);
        requireSynchronizedSource(baseline, green.expectedRemoteHead());
        if (!readyAllowed(source) || !verified.pullRequest().draft()) {
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
            try {
                requireAuthority.run();
            }
            catch (IllegalStateException expired) {
                throw new IllegalStateException(expired.getMessage() + ". " + waiting, expired);
            }
            GitHubDeliveryChecks checks = pullRequests.fetchDeliveryChecks(token, publishedPr.pullRequest());
            verifyDeliveryPr(baseline.route(), publishedPr, green, checks.pullRequest());
            CiHeadSnapshot snapshot = inspectDeliveryActions(publishedPr, green);
            verifyDeliveryPublication(baseline, publishedPr, green);
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

    private CiHeadSnapshot inspectDeliveryActions(GitHubPullRequestIdentity publishedPr, CiAutofixRun green)
    {
        CiHeadSnapshot snapshot = actions.inspect(new InspectCiHeadQuery(publishedPr.pullRequest()));
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

    private static void verifyDeliveryPr(DevelopmentPublicationRoute route, GitHubPullRequestIdentity publishedPr, CiAutofixRun green,
            GitHubPullRequestIdentity current)
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

    private boolean ciAllowed(DevelopmentTurnCompletion source)
    {
        return deliveryLimits(source).stream().allMatch(limit -> limit == DevelopmentStopAfter.CI);
    }

    private boolean readyAllowed(DevelopmentTurnCompletion source)
    {
        return deliveryLimits(source).isEmpty();
    }

    private List<DevelopmentStopAfter> deliveryLimits(DevelopmentTurnCompletion source)
    {
        return turnStore.list(source.task().id()).stream()
                .filter(turn -> turn.id().equals(source.turn().id()) || turn.developmentOrigin()
                        .filter(origin -> origin.parentUserTurnId().equals(source.turn().id())).isPresent())
                .flatMap(turn -> turn.developmentResult().flatMap(DevelopmentResultReport::stopAfter).stream()).toList();
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

    private boolean allowed(DevelopmentTurnCompletion source, DevelopmentTurnOrigin.Kind kind)
    {
        return deliveryLimits(source).stream()
                .allMatch(limit -> kind == DevelopmentTurnOrigin.Kind.AUTO_COMMIT ? limit != DevelopmentStopAfter.LOCAL_CHANGES
                        : limit == DevelopmentStopAfter.DRAFT || limit == DevelopmentStopAfter.CI);
    }

    static Optional<GitHubPullRequestIdentity> matchingPr(DevelopmentPublicationRoute route, DevelopmentPublicationFacts facts)
    {
        checkState(facts.matchingPullRequests().size() <= 1, "Several pull requests match the task branch");
        Optional<GitHubPullRequestIdentity> found = facts.matchingPullRequests().stream().findFirst();
        found.ifPresent(pr -> {
            checkState(pr.state() == GitHubPullRequestIdentity.State.OPEN, "The task's pull request is closed or merged");
            checkState(pr.headRepository().equals(route.headRepository()) && pr.headBranch().equals(route.headBranch())
                    && pr.baseRepository().equals(route.baseRepository()) && pr.baseBranch().equals(route.baseBranch()),
                    "Pull request base or head does not match the accepted destination");
            checkState(facts.remoteHeadSha().filter(pr.headSha()::equals).isPresent(), "Pull request and remote branch revisions do not agree");
        });
        return found;
    }

    private boolean priorQuestion(TaskId taskId, TurnId acceptedTurn)
    {
        List<AgentTurn> history = turnStore.list(taskId).stream().filter(turn -> !turn.id().equals(acceptedTurn)).toList();
        return !history.isEmpty() && history.getLast().state() == TurnState.COMPLETED
                && history.getLast().developmentResult().filter(report -> !report.readyForDelivery()).isPresent();
    }

    private static boolean changed(DevelopmentLocalFacts before, DevelopmentLocalFacts after)
    {
        return !before.headSha().equals(after.headSha()) || !before.fingerprint().equals(after.fingerprint());
    }

    private static DevelopmentResultReport requireResult(DevelopmentTurnCompletion completion)
    {
        checkState(completion.turn().state() == TurnState.COMPLETED, "%s",
                completion.turn().terminalReason().orElse("Development turn did not complete successfully"));
        checkState(completion.turn().cancellationRequestedAt().isEmpty(), "Development was stopped before delivery");
        checkState(completion.providerQuiescent(), "Provider shutdown could not be verified");
        checkState(completion.failure().isEmpty(), "Development turn cleanup failed: %s", completion.failure().orElse(""));
        return completion.turn().developmentResult().orElseThrow(() -> new IllegalStateException("The root agent did not report a valid development result"));
    }

    private void finish(DevelopmentTurnCompletion completion)
    {
        if (owns(completion)) {
            turns.finishDevelopmentContinuation(completion.task().id(), completion.turn().id());
        }
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
                The user's accepted Auto commit setting authorizes this bounded follow-up for the completed task work.
                Work only in %s on branch %s. Expected starting HEAD: %s.
                Check that ownership and HEAD still match before making changes. If they do not, stop and report the problem.
                Commit the completed task changes in focused commits, choosing subjects and bodies from the actual changes
                and the repository's commit conventions. Preserve existing commits; do not amend, rebase, reset, or change branches.
                Do not discard changes merely to make the checkout clean. Exclude ignored build output and credentials.
                Do not push or create a pull request in this turn. Preserve the previously reported local validation limitations.
                If a user decision remains, report_development_result with readyForDelivery=false and ask in the final response.
                Otherwise report readiness before ending. Earlier stopAfter limits remain in force.
                """.formatted(quote(baseline.worktree().canonicalWorktree().toString()), quote(baseline.route().headBranch()), local.headSha());
    }

    static String publicationPrompt(DevelopmentBaseline baseline, DevelopmentLocalFacts local, DevelopmentPublicationFacts publication,
            Optional<GitHubPullRequestIdentity> pullRequest, String ghBinary)
    {
        DevelopmentPublicationRoute route = baseline.route();
        String remoteRef = "refs/heads/" + route.headBranch();
        String push = "git -c credential.helper= -c " + quote("credential.https://github.com.helper=!" + quote(ghBinary) + " auth git-credential")
                + " push --porcelain " + quote("--force-with-lease=" + remoteRef + ":" + publication.remoteHeadSha().orElse(""))
                + " -- " + quote(route.pushUrl()) + " " + quote(local.headSha() + ":" + remoteRef);
        // gh rejects organization:branch heads; a same-repository head needs only the branch.
        String head = route.headRepository().equals(route.baseRepository()) ? route.headBranch() : route.headRepository().owner() + ":" + route.headBranch();
        String create = quote(ghBinary) + " pr create --draft --repo " + quote(route.baseRepository().fullName())
                + " --base " + quote(route.baseBranch()) + " --head " + quote(head)
                + " --title <chosen-title> --body-file <prepared-body-file>";
        String prInstruction = pullRequest.map(pr -> "Reuse existing PR " + pr.url() + "; do not create another or change its draft/readiness status.")
                .orElse("No matching PR existed at verification. Check again before creating; reuse only one open PR with this exact base/head. "
                        + "Stop on closed, merged, wrong-base, or ambiguous matches. If still absent, run once: " + create);
        return """
                ByteQuay · Create draft PR
                The user's accepted Auto draft PR setting authorizes this single publication of the verified revision.
                Worktree: %s. Branch: %s. Exact clean HEAD to publish: %s.
                Base repository/branch: %s / %s. Push repository: %s. Push URL: %s.
                Do not commit, edit code, amend, rebase, reset, switch branches, or run an independent CI repair loop.
                Recheck the branch, exact HEAD, clean staged/unstaged/untracked status (excluding ignored build output), and no open Git operation.
                If any check differs, stop. Verify gh authentication; keep normal tool approvals. Never print credentials.
                If the remote already equals the exact HEAD, no push is necessary. Otherwise use this exact push command once:
                %s
                Its explicit lease must stay intact: an empty expected SHA means the remote branch must still be absent.
                Never replace it with plain --force, an implicit lease, another destination, another ref, or a different object.
                %s
                Choose PR title/body from the actual change, follow the repository PR template, and preserve local-check limitations.
                The title/body placeholders mean prepare those values; write the body file with real newlines outside this worktree,
                such as in the system temporary directory, so publication leaves the checkout clean. Never mark the PR ready.
                If any push/create result is uncertain or fails, stop without retrying; report the error and any known URL.
                If a user decision is needed, report readyForDelivery=false and ask it. Otherwise report readiness before ending.
                """.formatted(quote(baseline.worktree().canonicalWorktree().toString()), quote(route.headBranch()), local.headSha(),
                        route.baseRepository().fullName(), quote(route.baseBranch()), route.headRepository().fullName(), quote(route.pushUrl()), push, prInstruction);
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

    private record Accepted(TurnId turnId, DevelopmentBaseline baseline, boolean pendingDelivery,
            Optional<DevelopmentPublicationFacts> publication, Optional<String> remoteFailure) {}
}
