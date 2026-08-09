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
package com.bytequay.app.service.harness;

import com.bytequay.app.repository.sqlite.SqlitePRStore;
import com.bytequay.app.service.harness.GitHubActionsProbe.FailedJob;
import com.bytequay.app.service.harness.GitHubActionsProbe.ProbeResult;
import com.bytequay.app.service.harness.HarnessLogParser.ParsedFailure;
import com.bytequay.app.service.harness.HarnessModels.BootstrapProfile;
import com.bytequay.app.service.harness.HarnessModels.Cycle;
import com.bytequay.app.service.harness.HarnessModels.CycleStatus;
import com.bytequay.app.service.harness.HarnessModels.Diagnosis;
import com.bytequay.app.service.harness.HarnessModels.Edit;
import com.bytequay.app.service.harness.HarnessModels.Failure;
import com.bytequay.app.service.harness.HarnessModels.FailureStatus;
import com.bytequay.app.service.harness.HarnessModels.GitSafetyProof;
import com.bytequay.app.service.harness.HarnessModels.Phase;
import com.bytequay.app.service.harness.HarnessModels.Watch;
import com.bytequay.app.service.harness.HarnessModels.WatchStatus;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.workspaces.SyncRunStream;
import com.bytequay.app.service.workspaces.WorkspaceKnowledgeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestHarnessOrchestrator
{
    private static final String PICK_SHA = "281482c3c20aad84cf2ad3991f0af5ff59679c5d";
    private static final String FIXUP_SHA = "345068df1033c9178d1bf7df8116c54372d2c362";
    private static final String OTHER_SHA = "550db638b3f388d4f3f1db8e0498b44ff32853dd";

    private final HarnessStore store = mock(HarnessStore.class);
    private final HarnessService service = mock(HarnessService.class);
    private final GitHubActionsProbe probe = mock(GitHubActionsProbe.class);
    private final HarnessLogParser parser = mock(HarnessLogParser.class);
    private final HarnessRepairAgent agent = mock(HarnessRepairAgent.class);
    private final WorkspaceKnowledgeService knowledge = mock(WorkspaceKnowledgeService.class);
    private final HarnessGitSafety gitSafety = mock(HarnessGitSafety.class);
    private final GitRunner git = mock(GitRunner.class);
    private final SqlitePRStore prs = mock(SqlitePRStore.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final HarnessOrchestrator orchestrator = new HarnessOrchestrator(
            store, service, probe, parser, agent, knowledge, new SyncRunStream(),
            gitSafety, git, prs, Runnable::run);

    private Cycle cycle;

    @BeforeEach
    void setUp()
    {
        cycle = new Cycle("cycle", "watch", 1, "manual", null, CycleStatus.QUEUED,
                Phase.PROBE, null, null, 0, null, null, null, null,
                1, 1, null, null);
        when(store.findCycle(cycle.id())).thenReturn(Optional.of(cycle));
        when(store.updateCycleProgress(
                eq(cycle.id()), any(), any(), any(), any(), any(), anyLong())).thenReturn(true);
        when(service.profile("{}")).thenReturn(BootstrapProfile.empty());
    }

    @Test
    void manualRunIsRejectedUntilBootstrapCompletes()
    {
        Watch watch = watch(WatchStatus.BOOTSTRAP, null, null);
        when(store.findWatch(watch.id())).thenReturn(Optional.of(watch));

        assertThatThrownBy(() -> orchestrator.requestRun(watch.id(), "manual", "guidance"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("still bootstrapping");
        verify(store, never()).startCycle(any(), any(), any(), any(), anyLong());
    }

    @Test
    void sameHeadWhileAwaitingPushDoesNotReprocessTheFailure()
    {
        Watch watch = watch(WatchStatus.HANDOFF, "same", "{\"reason\":\"verified\"}");
        when(store.findWatch(watch.id())).thenReturn(Optional.of(watch));
        when(store.isCycleActive(watch.id(), cycle.id())).thenReturn(true);
        when(store.updateWatchStatusIfNotStopped(
                eq(watch.id()), eq(WatchStatus.RUNNING), eq(watch.handoffJson()), anyLong()))
                .thenReturn(true);
        when(store.updateWatchStatusIfNotStopped(
                eq(watch.id()), eq(WatchStatus.HANDOFF), eq(watch.handoffJson()), anyLong()))
                .thenReturn(true);
        when(store.updateWatchHeadAndPoll(
                eq(watch.id()), eq("same"), eq("feature"), anyLong())).thenReturn(true);
        when(store.finishCycleIfLive(
                eq(cycle.id()), eq(CycleStatus.NO_CHANGE), eq(Phase.PROBE),
                eq(0L), isNull(), isNull(), eq("green"), isNull(), anyLong()))
                .thenReturn(true);
        when(probe.probe(eq(watch), eq(BootstrapProfile.empty()), any())).thenReturn(new ProbeResult(
                "same", "base", "feature", true, false, List.of(), "green"));

        orchestrator.runCycle(cycle.id());

        verify(parser, never()).parse(any(), anyLong(), any(), any(), any());
        verify(agent, never()).fix(any(), any(), any(), anyLong(), any(), any(), any());
        verify(store).updateWatchStatusIfNotStopped(
                eq(watch.id()), eq(WatchStatus.HANDOFF), eq(watch.handoffJson()), anyLong());
    }

    @Test
    void unchangedGreenHeadDoesNotDuplicateGreenEventsOrTimeline()
    {
        Watch watch = watch(WatchStatus.GREEN, "same", "{\"stale\":true}");
        when(store.findWatch(watch.id())).thenReturn(Optional.of(watch));
        when(store.isCycleActive(watch.id(), cycle.id())).thenReturn(true);
        when(store.updateWatchStatusIfNotStopped(
                eq(watch.id()), eq(WatchStatus.RUNNING), eq(watch.handoffJson()), anyLong()))
                .thenReturn(true);
        when(store.updateWatchStatusIfNotStopped(
                eq(watch.id()), eq(WatchStatus.GREEN), isNull(), anyLong())).thenReturn(true);
        when(store.updateWatchHeadAndPoll(
                eq(watch.id()), eq("same"), eq("feature"), anyLong())).thenReturn(true);
        when(store.finishCycleIfLive(
                eq(cycle.id()), eq(CycleStatus.NO_CHANGE), eq(Phase.PROBE),
                eq(0L), isNull(), isNull(), eq("green"), isNull(), anyLong()))
                .thenReturn(true);
        when(probe.probe(eq(watch), eq(BootstrapProfile.empty()), any())).thenReturn(new ProbeResult(
                "same", "base", "feature", true, false, List.of(), "green"));

        orchestrator.runCycle(cycle.id());

        verify(parser, never()).parse(any(), anyLong(), any(), any(), any());
        verify(store, never()).appendEvent(
                anyString(), anyString(), any(), eq("all_green"), anyString(), anyString(), anyLong());
        verify(prs, never()).findById(any());
        verify(store).updateWatchStatusIfNotStopped(
                eq(watch.id()), eq(WatchStatus.GREEN), isNull(), anyLong());
    }

    @Test
    void restartClearsStaleHandoffFromGreenWatchMismatch()
    {
        Watch watch = watch(WatchStatus.RUNNING, "same", "{\"stale\":true}");
        Cycle terminal = new Cycle(
                "terminal", watch.id(), 1, "manual", null, CycleStatus.GREEN,
                Phase.DONE, "same", "run", 0, null, null, null, "green",
                1, 2, 2L, null);
        when(store.watchesInStatus(WatchStatus.RUNNING)).thenReturn(List.of(watch));
        when(store.findLiveCycle(watch.id())).thenReturn(Optional.empty());
        when(store.listCycles(watch.id(), 1)).thenReturn(List.of(terminal));
        when(store.resumableCycles()).thenReturn(List.of());

        orchestrator.recoverInterruptedCycles();

        verify(store).updateWatchStatus(
                eq(watch.id()), eq(WatchStatus.GREEN), isNull(), anyLong());
    }

    @Test
    void handoffCommandPinsTheExplicitWorktreeAndRemoteBranch()
    {
        assertThat(HarnessOrchestrator.handoffCommand(
                Path.of("/tmp/o'hare/widget"), "feature/fix"))
                .isEqualTo("git -C '/tmp/o'\"'\"'hare/widget' push --force-with-lease "
                        + "origin 'HEAD:feature/fix'");
        assertThatThrownBy(() -> HarnessOrchestrator.handoffCommand(
                Path.of("/tmp/widget"), "-danger"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Failure failure(String id, String module, String signature, String ruleId)
    {
        return new Failure(
                id, cycle.id(), "run", 7L, "build", module, null, null,
                signature, signature, ruleId == null ? "unknown" : "resource:repeat", ruleId,
                FailureStatus.OBSERVED, null, null, null, null, 1, 1);
    }

    private static Diagnosis diagnosis(
            String signature, String bucket, String binding,
            String targetSubject, double confidence)
    {
        return new Diagnosis(
                "cause", null, targetSubject,
                List.of(new Edit("pom.xml", "old", "new")),
                signature, bucket, binding, List.of("build"),
                confidence, false, "evidence");
    }

    private static GitSafetyProof proof()
    {
        return new GitSafetyProof(
                "before", "after", "tree", "tree",
                true, true, true, "equivalent");
    }

    private Failure stubAgentDiagnosis(Watch watch)
            throws Exception
    {
        when(store.findWatch(watch.id())).thenReturn(Optional.of(watch));
        when(store.isCycleActive(watch.id(), cycle.id())).thenReturn(true);
        when(store.updateWatchStatusIfNotStopped(
                eq(watch.id()), eq(WatchStatus.RUNNING), isNull(), anyLong())).thenReturn(true);
        when(store.updateWatchStatusIfNotStopped(
                eq(watch.id()), eq(WatchStatus.NEEDS_ATTENTION), anyString(), anyLong()))
                .thenReturn(true);
        when(store.updateWatchHeadAndPoll(
                eq(watch.id()), eq("new"), eq("feature"), anyLong())).thenReturn(true);
        when(store.finishCycleIfLive(
                eq(cycle.id()), eq(CycleStatus.HANDOFF), eq(Phase.PROBE), eq(0L),
                isNull(), isNull(), isNull(), isNull(), anyLong())).thenReturn(true);
        FailedJob job = new FailedJob(
                "run", 7, "build", "failure", false, "plan mismatch");
        when(probe.probe(eq(watch), eq(BootstrapProfile.empty()), any())).thenReturn(new ProbeResult(
                "new", "base", "feature", false, false, List.of(job), "build: failure"));
        ParsedFailure parsed = new ParsedFailure(
                "run", 7, "build", "root", null, null, "plan mismatch", "plan mismatch");
        when(parser.parse("run", 7, "build", "plan mismatch", BootstrapProfile.empty()))
                .thenReturn(List.of(parsed));
        Failure failure = new Failure(
                "failure", cycle.id(), "run", 7L, "build", "root", null, null,
                "plan mismatch", "plan mismatch", "unknown", null,
                FailureStatus.OBSERVED, null, null, null, null, 1, 1);
        when(store.insertFailure(any())).thenReturn(failure);
        when(git.hasUncommittedChanges(any())).thenReturn(false);
        when(service.json(any())).thenAnswer(invocation ->
                mapper.writeValueAsString(invocation.getArgument(0)));
        return failure;
    }

    private static Failure persistedFailure(
            Failure failure, String bucketLabel, FailureStatus status,
            String targetSubject, String diagnosisJson, String fixJson,
            String verificationJson)
    {
        return new Failure(
                failure.id(), failure.cycleId(), failure.runId(), failure.checkRunId(),
                failure.jobName(), failure.module(), failure.testClass(), failure.testMethod(),
                failure.signature(), failure.logExcerpt(), bucketLabel, failure.ruleId(), status,
                targetSubject, diagnosisJson, fixJson, verificationJson,
                failure.createdAtMs(), failure.updatedAtMs());
    }

    @Test
    void theRoundHandsFailuresToTheAgentAndPushesWhatItCommitted()
            throws Exception
    {
        Watch watch = watch(WatchStatus.RUNNING, "old", null);
        Failure failure = stubAgentDiagnosis(watch);
        when(store.listFailuresForCycle(cycle.id())).thenReturn(List.of(failure));
        when(agent.fix(any(), eq("ws"), anyList(), anyLong(), any(), any(), any()))
                .thenReturn(new HarnessRepairAgent.Outcome(
                        true, false, "fixed the compile break", 300, "session-2"));
        when(git.pushRewrittenBranch(any(), eq("feature"), eq("new")))
                .thenReturn(new GitRunner.GitResult(0, "", "", List.of()));
        when(store.finishHandoff(
                eq(cycle.id()), eq(watch.id()), anyLong(), isNull(), isNull(),
                anyString(), anyString(), anyLong())).thenReturn(true);

        orchestrator.runCycle(cycle.id());

        // Every failure of the round goes over in one turn — the agent decides
        // what to take on, not the program.
        verify(agent).fix(any(), eq("ws"), eq(List.of(failure)), anyLong(), any(), any(), any());
        // The push is the program's, under an explicit lease on the head CI ran on.
        verify(git).pushRewrittenBranch(any(), eq("feature"), eq("new"));
        // One session for the run: what this round opened, the next resumes.
        verify(store).updateWatchAgentSession(eq(watch.id()), eq("session-2"), anyLong());
    }

    @Test
    void anAgentThatLeavesTheWorktreeDirtyIsNeverPushed()
            throws Exception
    {
        Watch watch = watch(WatchStatus.RUNNING, "old", null);
        Failure failure = stubAgentDiagnosis(watch);
        when(store.listFailuresForCycle(cycle.id())).thenReturn(List.of(failure));
        when(agent.fix(any(), any(), anyList(), anyLong(), any(), any(), any()))
                .thenReturn(new HarnessRepairAgent.Outcome(
                        true, false, "fixed it", 300, "session-2"));
        // Clean when the round starts, dirty when the agent hands it back.
        when(git.hasUncommittedChanges(any())).thenReturn(false, true);

        orchestrator.runCycle(cycle.id());

        verify(git, never()).pushRewrittenBranch(any(), any(), any());
        verify(service).notifyNeedsAttention(any(), anyString(), contains("uncommitted"));
    }

    @Test
    void aBoardStillRunningHoldsTheRoundBack()
            throws Exception
    {
        Watch watch = watch(WatchStatus.RUNNING, "old", null);
        stubAgentDiagnosis(watch);
        stubStillRunningBoard(watch);

        orchestrator.runCycle(cycle.id());

        // A check still running may yet fail; fixing around it wastes a push.
        verify(agent, never()).fix(any(), any(), anyList(), anyLong(), any(), any(), any());
        verify(store).updateWatchStatusIfNotStopped(
                eq(watch.id()), eq(WatchStatus.WATCHING), isNull(), anyLong());
    }

    @Test
    void aBoardFailingTooWidelyIsFixedWithoutWaitingForTheRest()
            throws Exception
    {
        // Eleven checks red with more still running. The rest of the board is not
        // going to redeem this one, so there is nothing to wait for.
        Watch watch = watch(WatchStatus.RUNNING, "old", null);
        Failure failure = stubAgentDiagnosis(watch);
        when(store.listFailuresForCycle(cycle.id())).thenReturn(List.of(failure));
        stubPendingBoard(watch, 11);
        stubSuccessfulRound(watch);

        orchestrator.runCycle(cycle.id());

        verify(agent).fix(any(), eq("ws"), eq(List.of(failure)), anyLong(), any(), any(), any());
        verify(git).pushRewrittenBranch(any(), eq("feature"), eq("new"));
    }

    @Test
    void aBoardPendingForHoursIsFixedRatherThanProbedForever()
            throws Exception
    {
        // One red check and a board that has sat on this head for three hours.
        // Waiting has stopped telling us anything.
        Watch watch = watch(WatchStatus.RUNNING, "old", null);
        Failure failure = stubAgentDiagnosis(watch);
        when(store.listFailuresForCycle(cycle.id())).thenReturn(List.of(failure));
        stubPendingBoard(watch, 1);
        when(store.headFirstSeenAtMs(eq(watch.id()), eq("new"), anyLong()))
                .thenReturn(System.currentTimeMillis() - 3 * 3_600_000L);
        stubSuccessfulRound(watch);

        orchestrator.runCycle(cycle.id());

        verify(agent).fix(any(), eq("ws"), eq(List.of(failure)), anyLong(), any(), any(), any());
    }

    @Test
    void aBoardPendingWithOneFailureForMinutesStillWaits()
            throws Exception
    {
        Watch watch = watch(WatchStatus.RUNNING, "old", null);
        stubAgentDiagnosis(watch);
        stubPendingBoard(watch, 1);
        when(store.headFirstSeenAtMs(eq(watch.id()), eq("new"), anyLong()))
                .thenReturn(System.currentTimeMillis() - 600_000L);

        orchestrator.runCycle(cycle.id());

        verify(agent, never()).fix(any(), any(), anyList(), anyLong(), any(), any(), any());
    }

    @Test
    void aPickSupersededByAPassingFixupIsNotOfferedToTheProbeAsBroken()
            throws Exception
    {
        // The per-commit job judges commits as they stand. A pick that only
        // builds once its fixup lands is red on its own and green with it, and
        // the two are one commit after autosquash — so the probe is told which
        // commit each fixup covers and drops the artefact itself.
        Watch watch = watch(WatchStatus.RUNNING, "old", null);
        stubAgentDiagnosis(watch);
        when(git.listCommits(Path.of(watch.localPath()), "feature", 2_000)).thenReturn(List.of(
                commit(FIXUP_SHA, "fixup! Migrate UI to bun"),
                commit(PICK_SHA, "Migrate UI to bun"),
                commit(OTHER_SHA, "Update airlift")));

        orchestrator.runCycle(cycle.id());

        // Full shas both sides: the probe matches these against the sha inside
        // "check-commit (<sha>)", so an abbreviation here would match nothing.
        verify(probe).probe(eq(watch), eq(BootstrapProfile.empty()),
                eq(Map.of(PICK_SHA, FIXUP_SHA)));
    }

    @Test
    void aCommitWithNoFixupAfterItCoversNothing()
            throws Exception
    {
        Watch watch = watch(WatchStatus.RUNNING, "old", null);
        stubAgentDiagnosis(watch);
        // A fixup naming a different subject is not this commit's fixup, and the
        // oldest commit has nothing before it to cover.
        when(git.listCommits(Path.of(watch.localPath()), "feature", 2_000)).thenReturn(List.of(
                commit(FIXUP_SHA, "fixup! Something else"),
                commit(PICK_SHA, "Migrate UI to bun")));

        orchestrator.runCycle(cycle.id());

        verify(probe).probe(eq(watch), eq(BootstrapProfile.empty()), eq(Map.of()));
    }

    private static GitRunner.CommitEntry commit(String sha, String subject)
    {
        return new GitRunner.CommitEntry(sha, sha, "a", "a@b", "t", "t", subject);
    }

    /** A half-finished board with {@code failures} checks already red. */
    private void stubPendingBoard(Watch watch, int failures)
    {
        List<FailedJob> red = IntStream.range(0, failures)
                .mapToObj(index -> new FailedJob(
                        "run", 7, "build", "failure", false, "plan mismatch"))
                .toList();
        when(probe.probe(eq(watch), eq(BootstrapProfile.empty()), any())).thenReturn(new ProbeResult(
                "new", "base", "feature", false, true, red, "build: failure"));
        when(store.finishCycleIfLive(
                eq(cycle.id()), eq(CycleStatus.NO_CHANGE), eq(Phase.PROBE), anyLong(),
                isNull(), isNull(), any(), isNull(), anyLong())).thenReturn(true);
        when(store.updateWatchStatusIfNotStopped(
                eq(watch.id()), eq(WatchStatus.WATCHING), isNull(), anyLong())).thenReturn(true);
    }

    private void stubSuccessfulRound(Watch watch)
            throws Exception
    {
        when(agent.fix(any(), eq("ws"), anyList(), anyLong(), any(), any(), any()))
                .thenReturn(new HarnessRepairAgent.Outcome(
                        true, false, "fixed the compile break", 300, "session-2"));
        when(git.pushRewrittenBranch(any(), eq("feature"), eq("new")))
                .thenReturn(new GitRunner.GitResult(0, "", "", List.of()));
        when(store.finishHandoff(
                eq(cycle.id()), eq(watch.id()), anyLong(), isNull(), isNull(),
                anyString(), anyString(), anyLong())).thenReturn(true);
    }

    @Test
    void aRoundTheUserAsksForWorksFromWhatHasAlreadyFailed()
            throws Exception
    {
        // Same half-finished board, but this round was asked for by name: a
        // suite that runs for an hour must not hold back the checks that are
        // already red.
        cycle = new Cycle(cycle.id(), "watch", 1, HarnessOrchestrator.TRIGGER_FIX_NOW,
                null, CycleStatus.QUEUED, Phase.PROBE, null, null, 0, null, null,
                null, null, 1, 1, null, null);
        when(store.findCycle(cycle.id())).thenReturn(Optional.of(cycle));
        Watch watch = watch(WatchStatus.RUNNING, "old", null);
        Failure failure = stubAgentDiagnosis(watch);
        when(store.listFailuresForCycle(cycle.id())).thenReturn(List.of(failure));
        stubStillRunningBoard(watch);
        when(agent.fix(any(), eq("ws"), anyList(), anyLong(), any(), any(), any()))
                .thenReturn(new HarnessRepairAgent.Outcome(
                        true, false, "fixed the compile break", 300, "session-2"));
        when(git.pushRewrittenBranch(any(), eq("feature"), eq("new")))
                .thenReturn(new GitRunner.GitResult(0, "", "", List.of()));
        when(store.finishHandoff(
                eq(cycle.id()), eq(watch.id()), anyLong(), isNull(), isNull(),
                anyString(), anyString(), anyLong())).thenReturn(true);

        orchestrator.runCycle(cycle.id());

        verify(agent).fix(any(), eq("ws"), eq(List.of(failure)), anyLong(), any(), any(), any());
        verify(git).pushRewrittenBranch(any(), eq("feature"), eq("new"));
    }

    /** Six checks red, one suite still going — the shape that used to stall. */
    private void stubStillRunningBoard(Watch watch)
    {
        when(probe.probe(eq(watch), eq(BootstrapProfile.empty()), any())).thenReturn(new ProbeResult(
                "new", "base", "feature", false, true,
                List.of(new FailedJob("run", 7, "build", "failure", false, "plan mismatch")),
                "build: failure"));
        when(store.finishCycleIfLive(
                eq(cycle.id()), eq(CycleStatus.NO_CHANGE), eq(Phase.PROBE), anyLong(),
                isNull(), isNull(), any(), isNull(), anyLong())).thenReturn(true);
        when(store.updateWatchStatusIfNotStopped(
                eq(watch.id()), eq(WatchStatus.WATCHING), isNull(), anyLong())).thenReturn(true);
    }

    @Test
    void aRoundWithoutCommitsParksAndPushesNothing()
            throws Exception
    {
        Watch watch = watch(WatchStatus.RUNNING, "old", null);
        Failure failure = stubAgentDiagnosis(watch);
        when(store.listFailuresForCycle(cycle.id())).thenReturn(List.of(failure));
        when(agent.fix(any(), any(), anyList(), anyLong(), any(), any(), any()))
                .thenReturn(new HarnessRepairAgent.Outcome(
                        false, false, "the fork's own API diverged too far here",
                        300, "session-2"));

        orchestrator.runCycle(cycle.id());

        verify(git, never()).pushRewrittenBranch(any(), any(), any());
        // The agent's own words reach the user rather than a generic reason.
        verify(service).notifyNeedsAttention(
                any(), anyString(), contains("the fork's own API diverged"));
    }

    @Test
    void aRefusedLeaseParksRatherThanForcingPast()
            throws Exception
    {
        Watch watch = watch(WatchStatus.RUNNING, "old", null);
        Failure failure = stubAgentDiagnosis(watch);
        when(store.listFailuresForCycle(cycle.id())).thenReturn(List.of(failure));
        when(agent.fix(any(), any(), anyList(), anyLong(), any(), any(), any()))
                .thenReturn(new HarnessRepairAgent.Outcome(
                        true, false, "fixed it", 300, "session-2"));
        when(git.pushRewrittenBranch(any(), eq("feature"), eq("new")))
                .thenReturn(new GitRunner.GitResult(
                        1, "", "stale info: refs/heads/feature", List.of()));

        orchestrator.runCycle(cycle.id());

        // A refused lease means the branch moved under us. Never retry past it —
        // that is the whole point of the lease.
        verify(git).pushRewrittenBranch(any(), eq("feature"), eq("new"));
        verify(service).notifyNeedsAttention(any(), anyString(), contains("stale info"));
    }

    @Test
    void anExhaustedBudgetParksWithoutCallingTheAgent()
            throws Exception
    {
        Watch watch = new Watch("watch", "ws", "acme", "widget", 7, null,
                "/tmp/widget.bytequay-worktrees/cherry-pick/one", "feature", "PR",
                WatchStatus.RUNNING, "old", "ready", "{}", 10_000, 10_000, null,
                1, 1, null, null, null);
        Failure failure = stubAgentDiagnosis(watch);
        when(store.listFailuresForCycle(cycle.id())).thenReturn(List.of(failure));

        orchestrator.runCycle(cycle.id());

        verify(agent, never()).fix(any(), any(), any(), anyLong(), any(), any(), any());
        // The budget is the one hard stop, and the park says it can be lifted.
        verify(service).notifyNeedsAttention(any(), anyString(), contains("Raise it"));
    }

    /**
     * The full logs land where the agent can read them, and — the part that
     * would break a run if it were wrong — git does not see them. Untracked
     * files count as changes to {@code git status --porcelain}, so an unexcluded
     * {@code logs/} would fail the next round's clean check and read as the
     * agent having left the worktree dirty.
     */
    @Test
    void jobLogsLandInTheWorktreeWithoutDirtyingIt(@TempDir Path root)
            throws Exception
    {
        Path clone = root.resolve("clone");
        Path worktree = root.resolve("wt");
        run(root, "init", "-b", "main", clone.toString());
        Files.writeString(clone.resolve("README.md"), "hello\n");
        run(clone, "add", "README.md");
        run(clone, "-c", "user.email=a@b.c", "-c", "user.name=a", "commit", "-m", "base");
        run(clone, "worktree", "add", "--detach", worktree.toString(), "HEAD");

        HarnessOrchestrator real = new HarnessOrchestrator(
                store, service, probe, parser, agent, knowledge, new SyncRunStream(),
                gitSafety, new GitRunner(), prs, Runnable::run);
        real.writeJobLogs(worktree, new ProbeResult(
                "head", "base", "feature", false, false,
                List.of(
                        new FailedJob("5", 1, "test (plugin/trino-postgresql)", "failure", false, "the whole log"),
                        new FailedJob("5", 2, "maven-checks 25.0.2", "failure", false, "")),
                ""));

        Path logs = worktree.resolve("logs");
        assertThat(logs.resolve("test-plugin-trino-postgresql.log")).hasContent("the whole log");
        // A job with no log at all leaves no file to mislead the agent.
        assertThat(logs.resolve("maven-checks-25.0.2.log")).doesNotExist();
        assertThat(run(worktree, "status", "--porcelain")).isEmpty();

        // Running it again must not stack a second exclude entry per round.
        real.writeJobLogs(worktree, new ProbeResult(
                "head", "base", "feature", false, false, List.of(), ""));
        assertThat(Files.readAllLines(new GitRunner().gitInfoExcludePath(worktree)))
                .containsOnlyOnce("/logs/");
    }

    /**
     * The pairing against real git rather than a stubbed list. Everything else
     * about this rule rests on {@code git log} being newest-first: read the
     * other way round every pick would be paired with the commit before it, and
     * a mock returning whatever order the test author assumed proves nothing.
     */
    @Test
    void fixupPairsAreReadFromRealGitHistoryInTheRightDirection(@TempDir Path root)
            throws Exception
    {
        Path repo = root.resolve("repo");
        run(root, "init", "-b", "feature", repo.toString());
        commitFile(repo, "a.txt", "Migrate UI to bun");
        commitFile(repo, "b.txt", "fixup! Migrate UI to bun");
        commitFile(repo, "c.txt", "Update airlift");
        String pick = run(repo, "rev-parse", "HEAD~2");
        String fixup = run(repo, "rev-parse", "HEAD~1");

        HarnessOrchestrator real = new HarnessOrchestrator(
                store, service, probe, parser, agent, knowledge, new SyncRunStream(),
                gitSafety, new GitRunner(), prs, Runnable::run);

        assertThat(real.fixupsBySupersededSha(watchAt(repo)))
                .containsExactly(entry(pick, fixup));
    }

    @Test
    void anUnreadableCheckoutPairsNothingRatherThanFailingTheCycle(@TempDir Path root)
    {
        // No repository there at all. The strict reading — every red check is
        // real — has to be what a missing checkout falls back to.
        HarnessOrchestrator real = new HarnessOrchestrator(
                store, service, probe, parser, agent, knowledge, new SyncRunStream(),
                gitSafety, new GitRunner(), prs, Runnable::run);

        assertThat(real.fixupsBySupersededSha(watchAt(root.resolve("nowhere")))).isEmpty();
    }

    private static Watch watchAt(Path localPath)
    {
        return new Watch("watch", "ws", "acme", "widget", 7, null,
                localPath.toString(), "feature", "PR",
                WatchStatus.RUNNING, "head", "ready", "{}", 10_000, 0, null,
                1, 1, null, null, null);
    }

    private static void commitFile(Path repo, String name, String subject)
            throws Exception
    {
        Files.writeString(repo.resolve(name), subject + "\n");
        run(repo, "add", name);
        run(repo, "-c", "user.email=a@b.c", "-c", "user.name=a", "commit", "-m", subject);
    }

    private static String run(Path workingDir, String... args)
            throws Exception
    {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command)
                .directory(workingDir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), UTF_8);
        assertThat(process.waitFor()).describedAs(output).isZero();
        return output.strip();
    }

    private static Watch watch(WatchStatus status, String head, String handoff)
    {
        return new Watch("watch", "ws", "acme", "widget", 7, null,
                "/tmp/widget.bytequay-worktrees/cherry-pick/one", "feature", "PR",
                status, head, "ready", "{}", 10_000, 0, handoff,
                1, 1, null, null, null);
    }
}
