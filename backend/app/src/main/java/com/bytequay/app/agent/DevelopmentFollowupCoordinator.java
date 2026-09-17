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
import com.bytequay.agent.core.StartAgentTurnCommand;
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
import com.bytequay.app.ci.InspectCiHeadQuery;
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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
    private static final Pattern ATTRIBUTION = Pattern.compile(
            "(?im)^.*(?:generated with[^\\n]{0,40}claude code|co-authored-by:\\s*claude\\b).*$\\R?");

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
    private final ConcurrentMap<TaskId, Accepted> accepted = new ConcurrentHashMap<>();
    private final ConcurrentMap<TaskId, PendingPublication> publications = new ConcurrentHashMap<>();

    public DevelopmentFollowupCoordinator(AgentTurnService turns, AgentTurnStore turnStore, DevelopmentGitInspector git,
            Executor executor, CiAutofixService ci, GitHubActionsClient actions, CredentialService credentials, GitHubPullRequestReadClient pullRequests,
            GitHubPullRequestWriteClient writes, Clock clock, Runnable waitForCi)
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
        DevelopmentAutomation automation = turn.settings().orElseThrow().developmentAutomation().orElse(DevelopmentAutomation.LOCAL_ONLY);
        if (automation.autoDraftPr() || automation.autoCiFix()) {
            try {
                DevelopmentPublicationFacts current = git.inspectPublication(baseline.worktree(), baseline.route());
                publication = Optional.of(current);
                if (!pending) {
                    // A queued turn captures its baseline after the previous turn's CLI publication.
                    pending = previous.filter(prior -> prior.remoteFailure().isEmpty() && prior.baseline().route().equals(baseline.route()))
                            .flatMap(Accepted::publication).filter(original -> newlyPublished(baseline, original, current)).isPresent();
                }
            }
            catch (RuntimeException failure) {
                // Remote prerequisites must not prevent the initial local development work.
                remoteFailure = Optional.of(message(failure));
            }
        }
        Accepted captured = new Accepted(turn.id(), baseline, previous.map(Accepted::summaryBaseline).orElse(baseline.local()), pending, publication, remoteFailure);
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
            requireSuccessful(completion);
            DevelopmentAutomation automation = completion.turn().settings().orElseThrow().developmentAutomation().orElse(DevelopmentAutomation.LOCAL_ONLY);
            if (!automation.enabled()) {
                finish(completion);
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
            checkState(source.remoteFailure().isEmpty(), "Remote baseline unavailable: %s", source.remoteFailure().orElse(""));
            DevelopmentPublicationFacts original = source.publication().orElseThrow(() -> new IllegalStateException("The accepted remote baseline is unavailable"));
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
                PendingPublication window = new PendingPublication(
                        completion.turn().id(), baseline, local.headSha(), publication.remoteHeadSha(), currentPr);
                publications.put(completion.task().id(), window);
                DevelopmentTurnCompletion published;
                try {
                    published = followup(completion, DevelopmentTurnOrigin.Kind.CREATE_DRAFT_PR,
                            publicationPrompt(baseline, local, currentPr));
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
                            () -> finishDelivery(completion, baseline, sourceHead, publishedPr, green.orElseThrow(), activatedAt));
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
        checkState(current.remoteHeadSha().filter(baseline.local().headSha()::equals).isPresent(),
                "The published revision does not match the clean task revision");
        return true;
    }

    private static void requireSuccessful(DevelopmentTurnCompletion completion)
    {
        checkState(completion.turn().state() == TurnState.COMPLETED, "%s",
                completion.turn().terminalReason().orElse("Development turn did not complete successfully"));
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
        if (!owns(completion)) {
            return;
        }
        boolean waitingForAnswer = turnStore.list(completion.task().id()).reversed().stream()
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
                        new Accepted(source.turnId(), source.baseline(), current, source.pendingDelivery(), source.publication(), source.remoteFailure()));
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
                Refresh this development task's private Review & Learning cards from the final code and actual commits.
                Worktree: %s. Branch: %s. Expected HEAD: %s. Frozen comparison base: %s.
                Read the current diff and commit history, including any uncommitted changes. Explain the task's intention
                and each commit's design and implementation. Ground explanations in the final code; do not invent rationale
                or verification. Distinguish inferred rationale from the stated intent.
                This is a reporting-only follow-up. Do not edit files, create or rewrite commits, push, publish, run
                repairs, release held work, or resolve a pending user decision. Preserve the task's existing question.
                Call report_development_result with readyForDelivery=true and learningSummary containing purpose,
                commits (one object per actual commit with full sha, title, and summary). Include only these two fields.
                Explain how uncommitted work differs from the commits in the intention. Do not write the summary into a
                worktree file. All text stays private to this task.
                Follow the shared card writing targets and applicable writing skills. Use short, complete sentences for
                the intention and each commit; explain why the design and implementation solve the problem. Keep bugs and
                concrete code improvements in anchored Findings when the review tools are available. This reporting-only
                follow-up does not start a new review. Replace stale prose; do not append another recap.
                readyForDelivery here only confirms this summary was recorded; it does not authorize delivery of held work.
                """.formatted(quote(baseline.worktree().canonicalWorktree().toString()), quote(baseline.route().headBranch()), local.headSha(), baseline.route().baseSha());
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
                and the repository's commit conventions. Never add an attribution, co-authorship, or generated-with trailer.
                Preserve existing commits; do not amend, rebase, reset, or change branches.
                Do not discard changes merely to make the checkout clean. Exclude ignored build output and credentials.
                Do not push or create a pull request in this turn. Preserve the previously reported local validation limitations.
                If a user decision remains, report_development_result with readyForDelivery=false and ask in the final response.
                Otherwise report readiness before ending. ByteQuay controls later stages using the user's accepted automation settings.
                """.formatted(quote(baseline.worktree().canonicalWorktree().toString()), quote(baseline.route().headBranch()), local.headSha());
    }

    /**
     * Starts the draft-pull-request turn the user asked for from the task's pull-request panel.
     *
     * <p>This is the manual twin of the accepted automation: the same verification, the same
     * backend-built prompt, and the same publication window, driven by a click instead of a
     * previously accepted setting. It owns no continuation, so nothing follows the turn it starts.
     * The task's work must already be committed — the publication request never commits, exactly as
     * it does not in the automation chain.
     *
     * @param task the interactive task to publish
     * @param expectedVersion the task version the caller saw
     * @param idempotencyKey replay key for the started turn
     * @return the accepted turn
     */
    public AgentTurn requestDraftPullRequest(AgentTask task, long expectedVersion, String idempotencyKey)
    {
        requireNonNull(task, "task is null");
        AgentTurn source = turnStore.list(task.id()).reversed().stream()
                .filter(turn -> turn.developmentOrigin().isEmpty())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("This task has no work to publish yet"));
        DevelopmentBaseline baseline = git.capture(task);
        DevelopmentLocalFacts local = inspectLocal(baseline);
        checkState(local.uncommittedFileCount() == 0, "Commit this task's changes before opening a draft pull request");
        checkState(local.commitCount() > 0 && local.diffFromBase(), "This task's branch has nothing to publish");
        DevelopmentPublicationFacts publication = git.inspectPublication(baseline.worktree(), baseline.route());
        Optional<GitHubPullRequestIdentity> currentPr = matchingPr(baseline.route(), publication);
        if (publication.remoteHeadSha().isPresent()) {
            checkState(git.isAncestor(baseline.worktree(), publication.remoteHeadSha().orElseThrow(), local.headSha()),
                    "Publication would replace unrelated remote history");
        }
        PendingPublication window = new PendingPublication(
                source.id(), baseline, local.headSha(), publication.remoteHeadSha(), currentPr);
        publications.put(task.id(), window);
        try {
            return turns.startPublicationTurn(new StartAgentTurnCommand(task.id(), expectedVersion,
                    publicationPrompt(baseline, local, currentPr), ImmutableList.of(), idempotencyKey), source.id());
        }
        catch (RuntimeException failure) {
            publications.remove(task.id(), window);
            throw failure;
        }
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
        checkState(local.headSha().equals(window.localHead()) && local.uncommittedFileCount() == 0,
                "The verified local revision changed; publication was not attempted");
        DevelopmentPublicationFacts facts = git.inspectPublication(worktree, route);
        if (facts.remoteHeadSha().filter(window.localHead()::equals).isEmpty()) {
            checkState(facts.remoteHeadSha().equals(window.expectedRemoteSha()), "The remote branch changed; publication was not attempted");
            git.pushDevelopmentHead(worktree, route, window.localHead(), window.expectedRemoteSha());
            facts = git.inspectPublication(worktree, route);
        }
        checkState(facts.remoteHeadSha().filter(window.localHead()::equals).isPresent(),
                "The pushed revision was not verified on the remote branch");
        Optional<GitHubPullRequestIdentity> existing = matchingPr(route, facts);
        if (existing.isPresent()) {
            GitHubPullRequestIdentity pullRequest = existing.orElseThrow();
            window.existingPr().ifPresent(original -> checkState(pullRequest.pullRequest().equals(original.pullRequest()),
                    "Publication replaced the task's pull request"));
            return "Pushed " + window.localHead() + "; pull request " + pullRequest.url() + " already tracks this branch.";
        }
        String head = route.headRepository().equals(route.baseRepository())
                ? route.headBranch()
                : route.headRepository().owner() + ":" + route.headBranch();
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

    static String publicationPrompt(DevelopmentBaseline baseline, DevelopmentLocalFacts local,
            Optional<GitHubPullRequestIdentity> pullRequest)
    {
        DevelopmentPublicationRoute route = baseline.route();
        String prInstruction = pullRequest
                .map(pr -> "Pull request " + pr.url() + " already tracks this branch; publishing updates it and creates no second one.")
                .orElse("No pull request tracked this branch at verification.");
        return """
                ByteQuay · Create draft PR
                The user's accepted Auto draft PR setting authorizes this single publication of the verified revision.
                Worktree: %s. Branch: %s. Exact clean HEAD to publish: %s.
                Base repository/branch: %s / %s. Push repository: %s.
                Do not commit, edit code, amend, rebase, reset, switch branches, or run an independent CI repair loop.
                Recheck the branch, exact HEAD, clean staged/unstaged/untracked status (excluding ignored build output),
                and no open Git operation. If any check differs, stop and report the difference.
                Publish by calling %s exactly once with a title and body.
                ByteQuay owns the push and the GitHub write: it pushes that exact HEAD under its own lease and opens the
                pull request as a draft. Never run git push, a GitHub CLI command, or any other remote write for this,
                and never read or ask for credentials. %s
                Choose title and body from the actual change and the repository PR template. Fill only the
                template sections this change actually has something to say for, and leave the rest exactly as
                the template leaves them: an empty section is better than one filled to look complete. Do not
                include local execution details: commands run, test classes executed, test counts, pass/fail
                results, build or validation outcomes, logs, tooling, sandbox, or environment limitations.
                Keep all local execution reporting in the ByteQuay task conversation, never the PR description,
                including its Testing, Validation, or Test plan sections. Describe tests added or changed by
                the behavior they cover, without reporting that they ran or passed. Never add an
                attribution, co-authorship, or generated-with trailer.
                Pass the body as tool input; do not write a body file into the worktree.
                The tool reports the resulting pull request. If it refuses or its result is uncertain, stop without
                retrying and report what it said.
                If a user decision is needed, report readyForDelivery=false and ask it. Otherwise report readiness before ending.
                """.formatted(quote(baseline.worktree().canonicalWorktree().toString()), quote(route.headBranch()), local.headSha(),
                        route.baseRepository().fullName(), quote(route.baseBranch()), route.headRepository().fullName(),
                        DevelopmentResultBridge.PUBLISH_TOOL_NAME, prInstruction);
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

    private record Accepted(TurnId turnId, DevelopmentBaseline baseline, DevelopmentLocalFacts summaryBaseline, boolean pendingDelivery,
            Optional<DevelopmentPublicationFacts> publication, Optional<String> remoteFailure) {}
}
