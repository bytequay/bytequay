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

import com.bytequay.app.service.harness.HarnessBootstrapper.BootstrapResult;
import com.bytequay.app.service.harness.HarnessModels.BootstrapProfile;
import com.bytequay.app.service.harness.HarnessModels.Cycle;
import com.bytequay.app.service.harness.HarnessModels.CycleStatus;
import com.bytequay.app.service.harness.HarnessModels.Diagnosis;
import com.bytequay.app.service.harness.HarnessModels.Failure;
import com.bytequay.app.service.harness.HarnessModels.FailureStatus;
import com.bytequay.app.service.harness.HarnessModels.HarnessDashboard;
import com.bytequay.app.service.harness.HarnessModels.Phase;
import com.bytequay.app.service.harness.HarnessModels.Watch;
import com.bytequay.app.service.harness.HarnessModels.WatchStatus;
import com.bytequay.app.service.threads.NotificationService;
import com.bytequay.app.service.workspaces.WorkspaceRepositoryResolver;
import com.bytequay.app.service.workspaces.WorkspaceRepositoryResolver.RepositoryIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestHarnessService
{
    private final HarnessStore store = mock(HarnessStore.class);
    private final HarnessBootstrapper bootstrapper = mock(HarnessBootstrapper.class);
    private final HarnessDiagnosisService diagnosis = mock(HarnessDiagnosisService.class);
    private final WorkspaceRepositoryResolver repositories = mock(WorkspaceRepositoryResolver.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final List<Runnable> queued = new ArrayList<>();
    private final HarnessService service = new HarnessService(
            store, bootstrapper, diagnosis, repositories, notifications,
            new ObjectMapper(), queued::add);

    @BeforeEach
    void setUp()
    {
        when(repositories.resolve("ws"))
                .thenReturn(new RepositoryIdentity("acme", "widget", "acme/widget", "main"));
        when(store.findLiveWatch("ws", "acme", "widget", 7)).thenReturn(Optional.empty());
        when(store.listCycles(anyString(), anyInt())).thenReturn(List.of());
        when(store.listFailuresForWatch(anyString(), anyInt())).thenReturn(List.of());
        when(store.listEventsForWatch(anyString(), anyInt())).thenReturn(List.of());
    }

    @Test
    void createReturnsPendingBeforeBootstrapRunsAndDoesNotStartACycle()
    {
        HarnessDashboard pending = service.create("ws", new HarnessService.CreateWatchCommand(
                "acme", "widget", 7, null, "/repo/widget", "feature", "PR", null));

        assertThat(pending.status()).isEqualTo("bootstrap");
        assertThat(pending.bootstrapStatus()).isEqualTo("pending");
        assertThat(pending.bootstrapProfile()).isNull();
        assertThat(pending.cycles()).isEmpty();
        assertThat(queued).hasSize(1);
        verifyNoInteractions(bootstrapper);

        ArgumentCaptor<Watch> inserted = ArgumentCaptor.forClass(Watch.class);
        verify(store).insertWatch(inserted.capture());
        assertThat(inserted.getValue().localPath()).isEqualTo("/repo/widget");

        when(store.findWatch(inserted.getValue().id()))
                .thenReturn(Optional.of(inserted.getValue()));
        BootstrapProfile profile = BootstrapProfile.empty();
        when(bootstrapper.bootstrap(
                "acme", "widget", "/repo/widget", inserted.getValue().id(), "feature"))
                .thenReturn(new BootstrapResult(Path.of("/isolated/widget"), profile));
        when(store.completeWatchBootstrap(
                eq(inserted.getValue().id()), anyString(), eq("/isolated/widget"),
                eq("feature"), anyLong())).thenReturn(true);

        queued.getFirst().run();

        verify(store).completeWatchBootstrap(
                eq(inserted.getValue().id()), anyString(), eq("/isolated/widget"),
                eq("feature"), anyLong());
        verify(store, never()).startCycle(any(), any(), any(), any(), anyLong());
    }

    @Test
    void applicationReadyDeduplicatesAndReschedulesPersistedBootstrap()
    {
        Watch watch = pendingWatch();
        when(store.watchesInStatus(WatchStatus.BOOTSTRAP)).thenReturn(List.of(watch));
        when(store.findWatch(watch.id())).thenReturn(Optional.of(watch));
        when(bootstrapper.bootstrap(
                watch.owner(), watch.repo(), watch.localPath(), watch.id(), watch.branch()))
                .thenReturn(new BootstrapResult(Path.of("/isolated/widget"), BootstrapProfile.empty()));

        service.recoverInterruptedBootstraps();
        service.recoverInterruptedBootstraps();

        assertThat(queued).hasSize(1);
        queued.getFirst().run();
        verify(bootstrapper).bootstrap(
                watch.owner(), watch.repo(), watch.localPath(), watch.id(), watch.branch());
    }

    @Test
    void resolvingTheLastEscalationReturnsTheWatchToWatching()
    {
        Watch watch = liveWatch();
        Failure failure = escalatedFailure();
        when(store.findWatch("watch")).thenReturn(Optional.of(watch));
        when(store.findFailure("failure")).thenReturn(Optional.of(failure));
        when(store.findCycle("cycle")).thenReturn(Optional.of(cycle()));
        when(store.listFailuresForWatch("watch", 200)).thenReturn(List.of(
                new Failure("other", "cycle", "run", 7L, "build", "root", null, null,
                        "other failure", "excerpt", "test", null,
                        FailureStatus.RESOLVED, null, null, null, null, 1, 1)));

        assertThat(service.resolveFailure("ws", "watch", "failure", "  known upstream flake  "))
                .isEqualTo("known upstream flake");

        verify(store).updateFailureStatus(eq("failure"), eq(FailureStatus.RESOLVED), anyLong());
        verify(store).updateWatchStatusIfNotStopped(
                eq("watch"), eq(WatchStatus.WATCHING), any(), anyLong());
    }

    @Test
    void resolvingKeepsNeedsAttentionWhileAnotherEscalationIsOpen()
    {
        Watch watch = liveWatch();
        when(store.findWatch("watch")).thenReturn(Optional.of(watch));
        when(store.findFailure("failure")).thenReturn(Optional.of(escalatedFailure()));
        when(store.findCycle("cycle")).thenReturn(Optional.of(cycle()));
        when(store.listFailuresForWatch("watch", 200)).thenReturn(List.of(escalatedFailure()));

        service.resolveFailure("ws", "watch", "failure", null);

        verify(store).updateFailureStatus(eq("failure"), eq(FailureStatus.RESOLVED), anyLong());
        verify(store, never()).updateWatchStatusIfNotStopped(any(), any(), any(), anyLong());
    }

    @Test
    void onlyAnEscalatedOrFailedFailureCanBeResolved()
    {
        when(store.findWatch("watch")).thenReturn(Optional.of(liveWatch()));
        when(store.findCycle("cycle")).thenReturn(Optional.of(cycle()));
        when(store.findFailure("failure")).thenReturn(Optional.of(
                new Failure("failure", "cycle", "run", 7L, "build", "root", null, null,
                        "verified failure", "excerpt", "test", null,
                        FailureStatus.VERIFIED, null, null, null, null, 1, 1)));

        assertThatThrownBy(() -> service.resolveFailure("ws", "watch", "failure", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
        verify(store, never()).updateFailureStatus(any(), any(), anyLong());
    }

    @Test
    void askRecordsTheQuestionTheAnswerAndItsCost()
    {
        when(store.findWatch("watch")).thenReturn(Optional.of(liveWatch()));
        when(store.listFailuresForWatch("watch", 40)).thenReturn(List.of(escalatedFailure()));
        when(diagnosis.ask(any(), eq("ws"), eq("why is it red?"), anyString(), eq(10_000L)))
                .thenReturn(new HarnessDiagnosisService.AskOutcome("Two seams are failing.", 42));

        service.ask("ws", "watch", "  why is it red?  ");

        ArgumentCaptor<String> context = ArgumentCaptor.forClass(String.class);
        verify(diagnosis).ask(any(), eq("ws"), eq("why is it red?"), context.capture(), eq(10_000L));
        assertThat(context.getValue()).contains("plan mismatch").contains("escalated");
        verify(store).addWatchCost(eq("watch"), eq(42L), anyLong());
        verify(store).appendEvent(
                eq("watch"), any(), any(), eq("question"), eq("why is it red?"), anyString(), anyLong());
        verify(store).appendEvent(
                eq("watch"), any(), any(), eq("answer"), eq("Two seams are failing."),
                anyString(), anyLong());
    }

    @Test
    void aFailedAnswerIsRecordedWithoutChargingTheWatch()
    {
        when(store.findWatch("watch")).thenReturn(Optional.of(liveWatch()));
        when(store.listFailuresForWatch("watch", 40)).thenReturn(List.of());
        when(diagnosis.ask(any(), any(), any(), any(), anyLong()))
                .thenThrow(new IllegalStateException("provider is unreachable"));

        service.ask("ws", "watch", "why is it red?");

        verify(store).appendEvent(
                eq("watch"), any(), any(), eq("answer_failed"), anyString(), anyString(), anyLong());
        verify(store, never()).addWatchCost(any(), anyLong(), anyLong());
    }

    @Test
    void theFailureProjectionKeepsSubTaggedBucketsAndParsedDiagnosis()
            throws Exception
    {
        String diagnosisJson = new ObjectMapper().writeValueAsString(new Diagnosis(
                "stale plan", null, "Add hive statistics", List.of(), "plan mismatch",
                "resource:plan_mismatch", "agent", List.of("regen"), 0.9, false,
                "regenerated fixture"));
        Failure failure = new Failure(
                "failure", "cycle", "run", 7L, "build", "root", "TestPlan", "checks",
                "plan mismatch", "excerpt", "resource:plan_mismatch", null,
                FailureStatus.ESCALATED, "Add hive statistics",
                diagnosisJson, null, null, 1, 2);

        var dto = service.toFailure(failure);

        assertThat(dto.bucket()).isEqualTo("resource:plan_mismatch");
        assertThat(dto.testClass()).isEqualTo("TestPlan");
        assertThat(dto.diagnosis().rootCause()).isEqualTo("stale plan");
        assertThat(dto.verification()).isNull();
    }

    private static Watch liveWatch()
    {
        return new Watch("watch", "ws", "acme", "widget", 7, null,
                "/repo/widget", "feature", "PR", WatchStatus.NEEDS_ATTENTION, "sha",
                "ready", "{}", 10_000, 0, null, 1, 1, null, null);
    }

    private static Cycle cycle()
    {
        return new Cycle("cycle", "watch", 1, "manual", null, CycleStatus.HANDOFF,
                Phase.DONE, "sha", null, 0, null, null, null, null, 1, 1, 1L, null);
    }

    private static Failure escalatedFailure()
    {
        return new Failure("failure", "cycle", "run", 7L, "build", "root", null, null,
                "plan mismatch", "excerpt", "resource:plan_mismatch", null,
                FailureStatus.ESCALATED, null, null, null, null, 1, 1);
    }

    private static Watch pendingWatch()
    {
        return new Watch("watch", "ws", "acme", "widget", 7, null,
                "/repo/widget", "feature", "PR", WatchStatus.BOOTSTRAP, null,
                "pending", "{}", 10_000, 0, null, 1, 1, null, null);
    }
}
