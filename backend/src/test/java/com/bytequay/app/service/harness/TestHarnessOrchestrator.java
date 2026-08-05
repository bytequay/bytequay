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

import com.bytequay.app.repository.PRStore;
import com.bytequay.app.service.harness.GitHubActionsProbe.FailedJob;
import com.bytequay.app.service.harness.GitHubActionsProbe.ProbeResult;
import com.bytequay.app.service.harness.HarnessClassifier.Classification;
import com.bytequay.app.service.harness.HarnessDiagnosisService.DiagnosisOutcome;
import com.bytequay.app.service.harness.HarnessGitSafety.FixupBatch;
import com.bytequay.app.service.harness.HarnessGitSafety.SafetyResult;
import com.bytequay.app.service.harness.HarnessLogParser.ParsedFailure;
import com.bytequay.app.service.harness.HarnessModels.BootstrapProfile;
import com.bytequay.app.service.harness.HarnessModels.Bucket;
import com.bytequay.app.service.harness.HarnessModels.Cycle;
import com.bytequay.app.service.harness.HarnessModels.CycleStatus;
import com.bytequay.app.service.harness.HarnessModels.Diagnosis;
import com.bytequay.app.service.harness.HarnessModels.Edit;
import com.bytequay.app.service.harness.HarnessModels.Failure;
import com.bytequay.app.service.harness.HarnessModels.FailureStatus;
import com.bytequay.app.service.harness.HarnessModels.FixResult;
import com.bytequay.app.service.harness.HarnessModels.GitSafetyProof;
import com.bytequay.app.service.harness.HarnessModels.Phase;
import com.bytequay.app.service.harness.HarnessModels.Rule;
import com.bytequay.app.service.harness.HarnessModels.RuleStatus;
import com.bytequay.app.service.harness.HarnessModels.VerificationResult;
import com.bytequay.app.service.harness.HarnessModels.VerifiedFix;
import com.bytequay.app.service.harness.HarnessModels.Watch;
import com.bytequay.app.service.harness.HarnessModels.WatchStatus;
import com.bytequay.app.service.local.GitRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestHarnessOrchestrator
{
    private final HarnessStore store = mock(HarnessStore.class);
    private final HarnessService service = mock(HarnessService.class);
    private final GitHubActionsProbe probe = mock(GitHubActionsProbe.class);
    private final HarnessLogParser parser = mock(HarnessLogParser.class);
    private final HarnessClassifier classifier = mock(HarnessClassifier.class);
    private final HarnessDiagnosisService diagnosis = mock(HarnessDiagnosisService.class);
    private final HarnessFixApplier applier = mock(HarnessFixApplier.class);
    private final HarnessVerifier verifier = mock(HarnessVerifier.class);
    private final HarnessGitSafety gitSafety = mock(HarnessGitSafety.class);
    private final GitRunner git = mock(GitRunner.class);
    private final PRStore prs = mock(PRStore.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final HarnessOrchestrator orchestrator = new HarnessOrchestrator(
            store, service, probe, parser, classifier, diagnosis, applier, verifier,
            gitSafety, git, prs, mock(ApplicationEventPublisher.class),
            mapper, Runnable::run);

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
    void cancellationImmediatelyBeforeApplyNeverMutatesTheWorktree()
            throws Exception
    {
        Watch watch = watch(WatchStatus.WATCHING, "old", null);
        when(store.findWatch(watch.id())).thenReturn(Optional.of(watch));
        when(store.isCycleActive(watch.id(), cycle.id())).thenReturn(true, false);
        when(store.updateWatchStatusIfNotStopped(
                eq(watch.id()), eq(WatchStatus.RUNNING), isNull(), anyLong())).thenReturn(true);
        when(store.updateWatchHeadAndPoll(
                eq(watch.id()), eq("new"), eq("feature"), anyLong())).thenReturn(true);
        FailedJob job = new FailedJob("run", 7, "build", "failure", false, "compile failed");
        when(probe.probe(watch, BootstrapProfile.empty())).thenReturn(new ProbeResult(
                "new", "base", "feature", false, false, List.of(job), "build: failure"));
        ParsedFailure parsed = new ParsedFailure(
                "run", 7, "build", "root", null, null, "compile failed", "compile failed");
        when(parser.parse("run", 7, "build", "compile failed", BootstrapProfile.empty()))
                .thenReturn(List.of(parsed));
        Failure failure = new Failure(
                "failure", cycle.id(), "run", 7L, "build", "root", null, null,
                "compile failed", "compile failed", "build", "rule",
                FailureStatus.OBSERVED, null, null, null, null, 1, 1);
        when(store.insertFailure(any())).thenReturn(failure);
        Diagnosis recipe = new Diagnosis(
                "cause", null, "Update plan", List.of(new Edit("pom.xml", "old", "new")),
                "compile failed", "resource:plan_mismatch", "recipe:rule", List.of("build"),
                0.9, false, "evidence");
        Rule rule = new Rule(
                "rule", "ws", "acme", "widget", "compile failed", null,
                "resource:plan_mismatch",
                "recipe:rule", mapper.writeValueAsString(recipe), RuleStatus.ACTIVE,
                "human", 100, "[]", 1, 1, 1, 1L);
        when(classifier.classify(
                eq("ws"), eq("acme"), eq("widget"), eq("root"),
                eq("compile failed"), eq("compile failed"), anyLong()))
                .thenReturn(new Classification(Bucket.RESOURCE, rule));
        when(store.listFailuresForCycle(cycle.id())).thenReturn(List.of(failure));
        when(store.findRule(rule.id())).thenReturn(Optional.of(rule));
        when(git.hasUncommittedChanges(any())).thenReturn(false);

        orchestrator.runCycle(cycle.id());

        verify(applier, never()).apply(any(), any());
        verify(applier, never()).applyRecipe(any(), any());
        verify(verifier, never()).verify(any(), any(), any(), any());
        verify(gitSafety, never()).commitFixupAndAutosquash(
                any(), any(), any(), any(), any(), any(), any(), any());
        verify(store).updateFailure(
                eq(failure.id()), eq("resource:plan_mismatch"), eq(rule.id()),
                eq(FailureStatus.OBSERVED), isNull(), isNull(), isNull(), isNull(), anyLong());
        verify(store, never()).updateWatchStatusIfNotStopped(
                eq(watch.id()), eq(WatchStatus.NEEDS_ATTENTION), any(), anyLong());
        verify(store, never()).updateWatchStatusIfNotStopped(
                eq(watch.id()), eq(WatchStatus.HANDOFF), any(), anyLong());
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
        when(probe.probe(watch, BootstrapProfile.empty())).thenReturn(new ProbeResult(
                "same", "base", "feature", true, false, List.of(), "green"));

        orchestrator.runCycle(cycle.id());

        verify(parser, never()).parse(any(), anyLong(), any(), any(), any());
        verify(classifier, never()).classify(any(), any(), any(), any(), any(), any(), anyLong());
        verify(applier, never()).apply(any(), any());
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
        when(probe.probe(watch, BootstrapProfile.empty())).thenReturn(new ProbeResult(
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
    void restartRecoversVerifiedTerminalCycleToHandoff()
            throws Exception
    {
        Watch watch = watch(WatchStatus.RUNNING, "same", null);
        Cycle terminal = new Cycle(
                "terminal", watch.id(), 1, "manual", null, CycleStatus.HANDOFF,
                Phase.DONE, "same", "run", 12, "backup", "original",
                "{\"netNeutral\":true}", "green", 1, 2, 2L, null);
        when(store.watchesInStatus(WatchStatus.RUNNING)).thenReturn(List.of(watch));
        when(store.findLiveCycle(watch.id())).thenReturn(Optional.empty());
        when(store.listCycles(watch.id(), 1)).thenReturn(List.of(terminal));
        when(store.resumableCycles()).thenReturn(List.of());
        when(service.json(any())).thenAnswer(invocation ->
                mapper.writeValueAsString(invocation.getArgument(0)));

        orchestrator.recoverInterruptedCycles();

        ArgumentCaptor<String> handoff = ArgumentCaptor.forClass(String.class);
        verify(store).updateWatchStatus(
                eq(watch.id()), eq(WatchStatus.HANDOFF), handoff.capture(), anyLong());
        assertThat(mapper.readTree(handoff.getValue()).path("command").asText())
                .isEqualTo(HarnessOrchestrator.handoffCommand(
                        Path.of(watch.localPath()), watch.branch()));
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

    @Test
    void excludesMatrixDuplicatesFromDiagnosisNegatives()
    {
        Failure current = failure("first", "one", " Plan mismatch ", null);
        Failure duplicate = failure("second", "two", "plan mismatch", null);
        Failure unrelated = failure("third", "three", "compile failed", null);
        Failure repeatedUnrelated = failure("fourth", "four", "compile failed", null);

        assertThat(HarnessOrchestrator.unrelatedSignatures(
                List.of(current, duplicate, unrelated, repeatedUnrelated), current))
                .containsExactly("compile failed");
    }

    @Test
    void diagnosesAMatrixSignatureOnceAndDefersItsDuplicate()
            throws Exception
    {
        Watch watch = watch(WatchStatus.WATCHING, "old", null);
        Failure first = stubAgentDiagnosis(watch);
        FailedJob firstJob = new FailedJob(
                "run", 7, "jdk-17", "failure", false, "plan mismatch");
        FailedJob duplicateJob = new FailedJob(
                "run", 8, "jdk-21", "failure", false, "plan mismatch");
        when(probe.probe(watch, BootstrapProfile.empty())).thenReturn(new ProbeResult(
                "new", "base", "feature", false, false,
                List.of(firstJob, duplicateJob), "matrix failed"));
        ParsedFailure firstParsed = new ParsedFailure(
                "run", 7, "jdk-17", "root", null, null,
                "plan mismatch", "first excerpt");
        ParsedFailure duplicateParsed = new ParsedFailure(
                "run", 8, "jdk-21", "root", null, null,
                "plan mismatch", "duplicate excerpt");
        when(parser.parse("run", 7, "jdk-17", "plan mismatch", BootstrapProfile.empty()))
                .thenReturn(List.of(firstParsed));
        when(parser.parse("run", 8, "jdk-21", "plan mismatch", BootstrapProfile.empty()))
                .thenReturn(List.of(duplicateParsed));
        Failure duplicate = new Failure(
                "duplicate", cycle.id(), "run", 8L, "jdk-21", "root", null, null,
                "plan mismatch", "duplicate excerpt", "unknown", null,
                FailureStatus.OBSERVED, null, null, null, null, 1, 1);
        Failure deferred = persistedFailure(
                duplicate, "unknown", FailureStatus.DEFERRED,
                null, null, null, null);
        when(store.insertFailure(any())).thenReturn(first, duplicate);
        when(classifier.classify(
                eq("ws"), eq("acme"), eq("widget"), eq("root"),
                eq("plan mismatch"), anyString(), anyLong()))
                .thenReturn(new Classification(Bucket.UNKNOWN, null));
        when(store.listFailuresForCycle(cycle.id()))
                .thenReturn(List.of(first, deferred), List.of(first, deferred));
        Diagnosis proposed = diagnosis(
                "plan mismatch", "resource:plan_mismatch", "agent", "Update plan", 0.6);
        when(diagnosis.diagnose(
                eq(first), any(), eq("base"), any(), anyLong(), eq("ws"), isNull()))
                .thenReturn(new DiagnosisOutcome(proposed, 10, "{}", 1, "complete"));

        orchestrator.runCycle(cycle.id());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> unrelated = ArgumentCaptor.forClass(List.class);
        verify(diagnosis).diagnose(
                eq(first), any(), eq("base"), unrelated.capture(), anyLong(), eq("ws"), isNull());
        assertThat(unrelated.getValue()).isEmpty();
        verify(diagnosis, times(1)).diagnose(
                any(), any(), any(), any(), anyLong(), any(), any());
        verify(store).updateFailure(
                eq(duplicate.id()), eq("unknown"), isNull(), eq(FailureStatus.DEFERRED),
                isNull(), isNull(), isNull(), isNull(), anyLong());
    }

    @Test
    void lowConfidenceEscalationPreservesDiagnosisSubtypeAndEvidence()
            throws Exception
    {
        Watch watch = watch(WatchStatus.WATCHING, "old", null);
        Failure failure = stubAgentDiagnosis(watch);
        Diagnosis proposed = new Diagnosis(
                "stale plan", null, "Update plan",
                List.of(new Edit("pom.xml", "old", "new")),
                "plan mismatch", "resource:plan_mismatch", "agent", List.of("build"),
                0.6, false, "insufficient ownership evidence");
        String diagnosisJson = mapper.writeValueAsString(proposed);
        Failure persisted = persistedFailure(
                failure, proposed.bucketLabel(), FailureStatus.PROPOSED,
                proposed.targetSubject(), diagnosisJson, null, null);
        when(store.listFailuresForCycle(cycle.id()))
                .thenReturn(List.of(failure), List.of(persisted));
        when(diagnosis.diagnose(
                eq(failure), any(), eq("base"), any(), anyLong(), eq("ws"), isNull()))
                .thenReturn(new DiagnosisOutcome(proposed, 17, diagnosisJson, 1, "complete"));

        orchestrator.runCycle(cycle.id());

        verify(applier, never()).apply(any(), any());
        verify(store).updateFailure(
                eq(failure.id()), eq("resource:plan_mismatch"), isNull(),
                eq(FailureStatus.ESCALATED), eq("Update plan"), eq(diagnosisJson),
                isNull(), isNull(), anyLong());
    }

    @Test
    void verificationFailureEscalationPreservesAllFailureEvidence()
            throws Exception
    {
        Watch watch = watch(WatchStatus.WATCHING, "old", null);
        Failure failure = stubAgentDiagnosis(watch);
        Diagnosis proposed = new Diagnosis(
                "stale plan", null, "Update plan",
                List.of(new Edit("pom.xml", "old", "new")),
                "plan mismatch", "resource:plan_mismatch", "agent", List.of("build"),
                0.9, false, "owned by the target commit");
        FixResult fix = new FixResult(
                List.of("pom.xml"), "Update plan", List.of("./mvnw test"), "agent");
        VerificationResult verification = new VerificationResult(
                false, true, List.of(), "targeted verification failed");
        String diagnosisJson = mapper.writeValueAsString(proposed);
        String fixJson = mapper.writeValueAsString(fix);
        String verificationJson = mapper.writeValueAsString(verification);
        Failure persisted = persistedFailure(
                failure, proposed.bucketLabel(), FailureStatus.FAILED,
                proposed.targetSubject(), diagnosisJson, fixJson, verificationJson);
        when(store.listFailuresForCycle(cycle.id()))
                .thenReturn(List.of(failure), List.of(persisted));
        when(diagnosis.diagnose(
                eq(failure), any(), eq("base"), any(), anyLong(), eq("ws"), isNull()))
                .thenReturn(new DiagnosisOutcome(proposed, 17, diagnosisJson, 1, "complete"));
        when(applier.apply(any(), eq(proposed))).thenReturn(fix);
        when(verifier.verify(any(), eq(fix), eq(BootstrapProfile.empty()), eq("root")))
                .thenReturn(new VerifiedFix(fix, verification));

        orchestrator.runCycle(cycle.id());

        verify(gitSafety).discardTrackedProposal(any(), eq(fix.filesChanged()));
        verify(store).updateFailure(
                eq(failure.id()), eq("resource:plan_mismatch"), isNull(),
                eq(FailureStatus.ESCALATED), eq("Update plan"), eq(diagnosisJson),
                eq(fixJson), eq(verificationJson), anyLong());
    }

    @Test
    void continuesAfterOneFailureEscalatesThenNormalizesSuccessfulFixupsOnce()
            throws Exception
    {
        Watch watch = watch(WatchStatus.WATCHING, "old", null);
        when(store.findWatch(watch.id())).thenReturn(Optional.of(watch));
        when(store.isCycleActive(watch.id(), cycle.id())).thenReturn(true);
        when(store.updateWatchStatusIfNotStopped(
                eq(watch.id()), eq(WatchStatus.RUNNING), isNull(), anyLong())).thenReturn(true);
        when(store.updateWatchHeadAndPoll(
                eq(watch.id()), eq("new"), eq("feature"), anyLong())).thenReturn(true);
        when(service.json(any())).thenAnswer(invocation ->
                mapper.writeValueAsString(invocation.getArgument(0)));
        FailedJob job = new FailedJob(
                "run", 7, "build", "failure", false, "three failures");
        when(probe.probe(watch, BootstrapProfile.empty())).thenReturn(new ProbeResult(
                "new", "base", "feature", false, false, List.of(job), "build: failure"));
        ParsedFailure parsedOne = new ParsedFailure(
                "run", 7, "build", "one", null, null, "first failure", "first failure");
        ParsedFailure parsedTwo = new ParsedFailure(
                "run", 7, "build", "two", null, null, "second failure", "second failure");
        ParsedFailure parsedThree = new ParsedFailure(
                "run", 7, "build", "three", null, null, "third failure", "third failure");
        when(parser.parse("run", 7, "build", "three failures", BootstrapProfile.empty()))
                .thenReturn(List.of(parsedOne, parsedTwo, parsedThree));
        Failure first = failure("first", "one", "first failure", null);
        Failure second = failure("second", "two", "second failure", null);
        Failure third = failure("third", "three", "third failure", null);
        when(store.insertFailure(any())).thenReturn(first, second, third);
        when(classifier.classify(
                eq("ws"), eq("acme"), eq("widget"), any(), any(), any(), anyLong()))
                .thenReturn(new Classification(Bucket.UNKNOWN, null));
        when(store.listFailuresForCycle(cycle.id())).thenReturn(List.of(first, second, third));
        Diagnosis firstDiagnosis = diagnosis(
                "first failure", "resource:first", "recipe:fix_first", "Update first", 0.9);
        Diagnosis secondDiagnosis = diagnosis(
                "second failure", "build", "agent", "Update second", 0.9);
        Diagnosis thirdDiagnosis = diagnosis(
                "third failure", "test:third", "agent", "Update third", 0.9);
        when(diagnosis.diagnose(eq(first), any(), eq("base"), any(), anyLong(), eq("ws"), isNull()))
                .thenReturn(new DiagnosisOutcome(firstDiagnosis, 10, "{}", 1, "complete"));
        when(diagnosis.diagnose(eq(second), any(), eq("base"), any(), anyLong(), eq("ws"), isNull()))
                .thenReturn(new DiagnosisOutcome(secondDiagnosis, 11, "{}", 1, "complete"));
        when(diagnosis.diagnose(eq(third), any(), eq("base"), any(), anyLong(), eq("ws"), isNull()))
                .thenReturn(new DiagnosisOutcome(thirdDiagnosis, 12, "{}", 1, "complete"));
        FixResult firstFix = new FixResult(
                List.of("first.txt"), "Update first", List.of("build"), "agent");
        FixResult secondFix = new FixResult(
                List.of("second.txt"), "Update second", List.of("build"), "agent");
        FixResult thirdFix = new FixResult(
                List.of("third.txt"), "Update third", List.of("test"), "agent");
        when(applier.applyRecipe(any(), eq(firstDiagnosis))).thenReturn(firstFix);
        when(applier.apply(any(), eq(secondDiagnosis))).thenReturn(secondFix);
        when(applier.apply(any(), eq(thirdDiagnosis))).thenReturn(thirdFix);
        VerificationResult passed = new VerificationResult(true, true, List.of(), "passed");
        VerificationResult failed = new VerificationResult(
                false, true, List.of(), "second verification failed");
        when(verifier.verify(any(), eq(firstFix), eq(BootstrapProfile.empty()), eq("one")))
                .thenReturn(new VerifiedFix(firstFix, passed));
        when(verifier.verify(any(), eq(secondFix), eq(BootstrapProfile.empty()), eq("two")))
                .thenReturn(new VerifiedFix(secondFix, failed));
        when(verifier.verify(any(), eq(thirdFix), eq(BootstrapProfile.empty()), eq("three")))
                .thenReturn(new VerifiedFix(thirdFix, passed));
        FixupBatch batch = mock(FixupBatch.class);
        when(gitSafety.beginFixupBatch(
                any(), eq("base"), eq("origin"), eq("feature"), any(), any()))
                .thenReturn(batch);
        GitSafetyProof proof = proof();
        when(batch.finishWithoutSquash()).thenReturn(new SafetyResult("backup", "original", proof));
        when(store.upsertCandidate(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(store.finishHandoff(
                eq(cycle.id()), eq(watch.id()), anyLong(), eq("backup"), any(),
                eq("build: failure"), any(), anyLong())).thenReturn(true);

        orchestrator.runCycle(cycle.id());

        InOrder order = inOrder(verifier, batch, store);
        order.verify(verifier).verify(any(), eq(firstFix), any(), eq("one"));
        order.verify(batch).commitFixup(firstFix.filesChanged(), firstFix.targetSubject());
        order.verify(verifier).verify(any(), eq(secondFix), any(), eq("two"));
        order.verify(verifier).verify(any(), eq(thirdFix), any(), eq("three"));
        order.verify(batch).commitFixup(thirdFix.filesChanged(), thirdFix.targetSubject());
        order.verify(batch).finishWithoutSquash();
        verify(batch, never()).finish();
        order.verify(store).finishHandoff(
                eq(cycle.id()), eq(watch.id()), anyLong(), eq("backup"), any(),
                eq("build: failure"), any(), anyLong());
        verify(diagnosis, times(3)).diagnose(
                any(), any(), eq("base"), any(), anyLong(), eq("ws"), isNull());
        verify(gitSafety).discardTrackedProposal(any(), eq(secondFix.filesChanged()));
        ArgumentCaptor<Rule> candidates = ArgumentCaptor.forClass(Rule.class);
        verify(store, times(2)).upsertCandidate(candidates.capture());
        assertThat(candidates.getAllValues().get(0))
                .satisfies(candidate -> {
                    assertThat(candidate.binding()).isEqualTo("recipe:fix_first");
                    assertThat(candidate.recipeJson()).isEqualTo(
                            mapper.writeValueAsString(firstDiagnosis));
                });
        assertThat(candidates.getAllValues().get(1))
                .satisfies(candidate -> {
                    assertThat(candidate.binding()).isEqualTo("agent");
                    assertThat(candidate.recipeJson()).isNull();
                });
        verify(batch, never()).commitFixup(secondFix.filesChanged(), secondFix.targetSubject());
    }

    @Test
    void aRejectedDiagnosisEscalatesOnlyItsOwnFailureAndKeepsVerifiedFixups()
            throws Exception
    {
        // A malformed or unusable model response is the single most likely event in
        // this loop. It must not unwind fixups that already passed verification.
        Watch watch = watch(WatchStatus.WATCHING, "old", null);
        when(store.findWatch(watch.id())).thenReturn(Optional.of(watch));
        when(store.isCycleActive(watch.id(), cycle.id())).thenReturn(true);
        when(store.updateWatchStatusIfNotStopped(
                eq(watch.id()), eq(WatchStatus.RUNNING), isNull(), anyLong())).thenReturn(true);
        when(store.updateWatchHeadAndPoll(
                eq(watch.id()), eq("new"), eq("feature"), anyLong())).thenReturn(true);
        when(service.json(any())).thenAnswer(invocation ->
                mapper.writeValueAsString(invocation.getArgument(0)));
        FailedJob job = new FailedJob("run", 7, "build", "failure", false, "two failures");
        when(probe.probe(watch, BootstrapProfile.empty())).thenReturn(new ProbeResult(
                "new", "base", "feature", false, false, List.of(job), "build: failure"));
        when(parser.parse("run", 7, "build", "two failures", BootstrapProfile.empty()))
                .thenReturn(List.of(
                        new ParsedFailure("run", 7, "build", "one", null, null,
                                "first failure", "first failure"),
                        new ParsedFailure("run", 7, "build", "two", null, null,
                                "second failure", "second failure")));
        Failure first = failure("first", "one", "first failure", null);
        Failure second = failure("second", "two", "second failure", null);
        when(store.insertFailure(any())).thenReturn(first, second);
        when(classifier.classify(
                eq("ws"), eq("acme"), eq("widget"), any(), any(), any(), anyLong()))
                .thenReturn(new Classification(Bucket.UNKNOWN, null));
        when(store.listFailuresForCycle(cycle.id())).thenReturn(List.of(first, second));
        Diagnosis firstDiagnosis = diagnosis(
                "first failure", "build", "agent", "Update first", 0.9);
        when(diagnosis.diagnose(eq(first), any(), eq("base"), any(), anyLong(), eq("ws"), isNull()))
                .thenReturn(new DiagnosisOutcome(firstDiagnosis, 10, "{}", 1, "complete"));
        when(diagnosis.diagnose(eq(second), any(), eq("base"), any(), anyLong(), eq("ws"), isNull()))
                .thenThrow(new IllegalArgumentException("diagnosis is not valid JSON"));
        FixResult firstFix = new FixResult(
                List.of("first.txt"), "Update first", List.of("build"), "agent");
        when(applier.apply(any(), eq(firstDiagnosis))).thenReturn(firstFix);
        when(verifier.verify(any(), eq(firstFix), eq(BootstrapProfile.empty()), eq("one")))
                .thenReturn(new VerifiedFix(
                        firstFix, new VerificationResult(true, true, List.of(), "passed")));
        FixupBatch batch = mock(FixupBatch.class);
        when(gitSafety.beginFixupBatch(
                any(), eq("base"), eq("origin"), eq("feature"), any(), any()))
                .thenReturn(batch);
        when(batch.finishWithoutSquash()).thenReturn(new SafetyResult("backup", "original", proof()));
        when(store.upsertCandidate(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(store.finishHandoff(
                eq(cycle.id()), eq(watch.id()), anyLong(), eq("backup"), any(),
                eq("build: failure"), any(), anyLong())).thenReturn(true);

        orchestrator.runCycle(cycle.id());

        // The verified fixup survives and the cycle still hands off cleanly.
        verify(batch).commitFixup(firstFix.filesChanged(), firstFix.targetSubject());
        verify(batch).finishWithoutSquash();
        // Nothing is squashed into a commit the human has not reviewed.
        verify(batch, never()).finish();
        verify(batch, never()).abort();
        verify(store).finishHandoff(
                eq(cycle.id()), eq(watch.id()), anyLong(), eq("backup"), any(),
                eq("build: failure"), any(), anyLong());
        // The rejected failure is escalated, not silently dropped.
        verify(store).updateFailure(
                eq(second.id()), any(), any(), eq(FailureStatus.ESCALATED),
                any(), any(), any(), any(), anyLong());
    }

    @Test
    void aKnowledgeBaseWriteFailureNeverUnwindsACommittedFixup()
            throws Exception
    {
        Watch watch = watch(WatchStatus.WATCHING, "old", null);
        when(store.findWatch(watch.id())).thenReturn(Optional.of(watch));
        when(store.isCycleActive(watch.id(), cycle.id())).thenReturn(true);
        when(store.updateWatchStatusIfNotStopped(
                eq(watch.id()), eq(WatchStatus.RUNNING), isNull(), anyLong())).thenReturn(true);
        when(store.updateWatchHeadAndPoll(
                eq(watch.id()), eq("new"), eq("feature"), anyLong())).thenReturn(true);
        when(service.json(any())).thenAnswer(invocation ->
                mapper.writeValueAsString(invocation.getArgument(0)));
        FailedJob job = new FailedJob("run", 7, "build", "failure", false, "one failure");
        when(probe.probe(watch, BootstrapProfile.empty())).thenReturn(new ProbeResult(
                "new", "base", "feature", false, false, List.of(job), "build: failure"));
        when(parser.parse("run", 7, "build", "one failure", BootstrapProfile.empty()))
                .thenReturn(List.of(new ParsedFailure(
                        "run", 7, "build", "one", null, null, "only failure", "only failure")));
        Failure only = failure("only", "one", "only failure", null);
        when(store.insertFailure(any())).thenReturn(only);
        when(classifier.classify(
                eq("ws"), eq("acme"), eq("widget"), any(), any(), any(), anyLong()))
                .thenReturn(new Classification(Bucket.UNKNOWN, null));
        when(store.listFailuresForCycle(cycle.id())).thenReturn(List.of(only));
        Diagnosis proposed = diagnosis("only failure", "build", "agent", "Update only", 0.9);
        when(diagnosis.diagnose(eq(only), any(), eq("base"), any(), anyLong(), eq("ws"), isNull()))
                .thenReturn(new DiagnosisOutcome(proposed, 10, "{}", 1, "complete"));
        FixResult fix = new FixResult(
                List.of("only.txt"), "Update only", List.of("build"), "agent");
        when(applier.apply(any(), eq(proposed))).thenReturn(fix);
        when(verifier.verify(any(), eq(fix), eq(BootstrapProfile.empty()), eq("one")))
                .thenReturn(new VerifiedFix(
                        fix, new VerificationResult(true, true, List.of(), "passed")));
        FixupBatch batch = mock(FixupBatch.class);
        when(gitSafety.beginFixupBatch(
                any(), eq("base"), eq("origin"), eq("feature"), any(), any()))
                .thenReturn(batch);
        when(batch.finishWithoutSquash()).thenReturn(new SafetyResult("backup", "original", proof()));
        // Bookkeeping is not in the commit critical path.
        when(store.upsertCandidate(any()))
                .thenThrow(new IllegalStateException("candidate identity collided"));
        when(store.finishHandoff(
                eq(cycle.id()), eq(watch.id()), anyLong(), eq("backup"), any(),
                eq("build: failure"), any(), anyLong())).thenReturn(true);

        orchestrator.runCycle(cycle.id());

        verify(batch).commitFixup(fix.filesChanged(), fix.targetSubject());
        verify(batch).finishWithoutSquash();
        // Nothing is squashed into a commit the human has not reviewed.
        verify(batch, never()).finish();
        verify(batch, never()).abort();
        verify(gitSafety, never()).discardTrackedProposal(any(), any());
        verify(store).finishHandoff(
                eq(cycle.id()), eq(watch.id()), anyLong(), eq("backup"), any(),
                eq("build: failure"), any(), anyLong());
    }

    @Test
    void approvedLearnedRecipeRoutesDeterministicallyWithoutDiagnosis()
            throws Exception
    {
        Watch watch = watch(WatchStatus.WATCHING, "old", null);
        when(store.findWatch(watch.id())).thenReturn(Optional.of(watch));
        when(store.isCycleActive(watch.id(), cycle.id())).thenReturn(true);
        when(store.updateWatchStatusIfNotStopped(
                eq(watch.id()), eq(WatchStatus.RUNNING), isNull(), anyLong())).thenReturn(true);
        when(store.updateWatchHeadAndPoll(
                eq(watch.id()), eq("new"), eq("feature"), anyLong())).thenReturn(true);
        when(service.json(any())).thenAnswer(invocation ->
                mapper.writeValueAsString(invocation.getArgument(0)));
        FailedJob job = new FailedJob(
                "run", 7, "build", "failure", false, "repeat failure");
        when(probe.probe(watch, BootstrapProfile.empty())).thenReturn(new ProbeResult(
                "new", "base", "feature", false, false, List.of(job), "build: failure"));
        when(parser.parse("run", 7, "build", "repeat failure", BootstrapProfile.empty()))
                .thenReturn(List.of(new ParsedFailure(
                        "run", 7, "build", "root", null, null,
                        "repeat failure", "repeat failure")));
        Failure observed = failure("failure", "root", "repeat failure", null);
        Failure classified = failure("failure", "root", "repeat failure", "recipe-rule");
        when(store.insertFailure(any())).thenReturn(observed);
        Diagnosis recipe = diagnosis(
                "repeat failure", "resource:repeat", "recipe:repeat", "Update plan", 0.9);
        Rule active = new Rule(
                "recipe-rule", "ws", "acme", "widget", recipe.signaturePattern(), "root",
                recipe.bucketLabel(), recipe.binding(), mapper.writeValueAsString(recipe),
                RuleStatus.ACTIVE, "human", 100, "[]", 3, 1, 3, 3L);
        when(classifier.classify(
                eq("ws"), eq("acme"), eq("widget"), eq("root"),
                eq("repeat failure"), eq("repeat failure"), anyLong()))
                .thenReturn(new Classification(Bucket.RESOURCE, active));
        when(store.listFailuresForCycle(cycle.id())).thenReturn(List.of(classified));
        when(store.findRule(active.id())).thenReturn(Optional.of(active));
        when(git.hasUncommittedChanges(any())).thenReturn(false);
        FixResult fix = new FixResult(
                List.of("pom.xml"), "Update plan", List.of("build"), active.binding());
        when(applier.applyRecipe(any(), eq(recipe))).thenReturn(fix);
        when(verifier.verify(any(), eq(fix), eq(BootstrapProfile.empty()), eq("root")))
                .thenReturn(new VerifiedFix(fix,
                        new VerificationResult(true, true, List.of(), "passed")));
        FixupBatch batch = mock(FixupBatch.class);
        when(gitSafety.beginFixupBatch(
                any(), eq("base"), eq("origin"), eq("feature"), any(), any()))
                .thenReturn(batch);
        when(batch.finishWithoutSquash()).thenReturn(new SafetyResult("backup", "original", proof()));
        when(store.finishHandoff(
                eq(cycle.id()), eq(watch.id()), anyLong(), eq("backup"), any(),
                eq("build: failure"), any(), anyLong())).thenReturn(true);

        orchestrator.runCycle(cycle.id());

        verify(diagnosis, never()).diagnose(any(), any(), any(), any(), anyLong(), any(), any());
        verify(applier, never()).apply(any(), any());
        verify(applier).applyRecipe(any(), eq(recipe));
        verify(batch).commitFixup(fix.filesChanged(), fix.targetSubject());
        verify(batch).finishWithoutSquash();
        // Nothing is squashed into a commit the human has not reviewed.
        verify(batch, never()).finish();
        verify(store, never()).upsertCandidate(any());
    }

    @Test
    void approvedAgentRuleStillUsesAdvisoryDiagnosis()
            throws Exception
    {
        Watch watch = watch(WatchStatus.WATCHING, "old", null);
        Failure observed = stubAgentDiagnosis(watch);
        Rule active = new Rule(
                "agent-rule", "ws", "acme", "widget", "plan mismatch", "root",
                "build", "agent", null, RuleStatus.ACTIVE,
                "human", 100, "[]", 3, 1, 3, 3L);
        Failure classified = new Failure(
                observed.id(), observed.cycleId(), observed.runId(), observed.checkRunId(),
                observed.jobName(), observed.module(), observed.testClass(), observed.testMethod(),
                observed.signature(), observed.logExcerpt(), active.bucketLabel(), active.id(),
                FailureStatus.OBSERVED, null, null, null, null, 1, 2);
        Diagnosis proposed = diagnosis(
                "plan mismatch", "build", "agent", "Update plan", 0.6);
        String diagnosisJson = mapper.writeValueAsString(proposed);
        Failure persisted = persistedFailure(
                classified, proposed.bucketLabel(), FailureStatus.PROPOSED,
                proposed.targetSubject(), diagnosisJson, null, null);
        when(classifier.classify(
                eq("ws"), eq("acme"), eq("widget"), eq("root"),
                eq("plan mismatch"), eq("plan mismatch"), anyLong()))
                .thenReturn(new Classification(Bucket.BUILD, active));
        when(store.listFailuresForCycle(cycle.id()))
                .thenReturn(List.of(classified), List.of(persisted));
        when(store.findRule(active.id())).thenReturn(Optional.of(active));
        when(diagnosis.diagnose(
                eq(classified), any(), eq("base"), any(), anyLong(), eq("ws"), isNull()))
                .thenReturn(new DiagnosisOutcome(proposed, 17, diagnosisJson, 1, "complete"));

        orchestrator.runCycle(cycle.id());

        verify(diagnosis).diagnose(
                eq(classified), any(), eq("base"), any(), anyLong(), eq("ws"), isNull());
        verify(applier, never()).applyRecipe(any(), any());
        verify(store, never()).upsertCandidate(any());
    }

    @Test
    void invalidApprovedRecipeFailsClosedWithoutAgentFallback()
            throws Exception
    {
        Watch watch = watch(WatchStatus.WATCHING, "old", null);
        Failure observed = stubAgentDiagnosis(watch);
        Rule active = new Rule(
                "recipe-rule", "ws", "acme", "widget", "plan mismatch", "root",
                "resource:repeat", "recipe:repeat", null, RuleStatus.ACTIVE,
                "human", 100, "[]", 3, 1, 3, 3L);
        Failure classified = new Failure(
                observed.id(), observed.cycleId(), observed.runId(), observed.checkRunId(),
                observed.jobName(), observed.module(), observed.testClass(), observed.testMethod(),
                observed.signature(), observed.logExcerpt(), active.bucketLabel(), active.id(),
                FailureStatus.OBSERVED, null, null, null, null, 1, 2);
        when(classifier.classify(
                eq("ws"), eq("acme"), eq("widget"), eq("root"),
                eq("plan mismatch"), eq("plan mismatch"), anyLong()))
                .thenReturn(new Classification(Bucket.RESOURCE, active));
        when(store.listFailuresForCycle(cycle.id())).thenReturn(List.of(classified));
        when(store.findRule(active.id())).thenReturn(Optional.of(active));

        orchestrator.runCycle(cycle.id());

        verify(diagnosis, never()).diagnose(any(), any(), any(), any(), anyLong(), any(), any());
        verify(applier, never()).apply(any(), any());
        verify(applier, never()).applyRecipe(any(), any());
        verify(gitSafety, never()).beginFixupBatch(any(), any(), any(), any(), any(), any());
        verify(store).updateFailure(
                eq(classified.id()), eq(active.bucketLabel()), eq(active.id()),
                eq(FailureStatus.ESCALATED), isNull(), isNull(), isNull(), isNull(), anyLong());
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
        when(probe.probe(watch, BootstrapProfile.empty())).thenReturn(new ProbeResult(
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
        when(classifier.classify(
                eq("ws"), eq("acme"), eq("widget"), eq("root"),
                eq("plan mismatch"), eq("plan mismatch"), anyLong()))
                .thenReturn(new Classification(Bucket.UNKNOWN, null));
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

    private static Watch watch(WatchStatus status, String head, String handoff)
    {
        return new Watch("watch", "ws", "acme", "widget", 7, null,
                "/tmp/widget.bytequay-worktrees/cherry-pick/one", "feature", "PR",
                status, head, "ready", "{}", 10_000, 0, handoff,
                1, 1, null, null);
    }
}
