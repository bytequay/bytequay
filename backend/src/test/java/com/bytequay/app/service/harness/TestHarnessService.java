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
import com.bytequay.app.service.harness.HarnessModels.HarnessDashboard;
import com.bytequay.app.service.harness.HarnessModels.Watch;
import com.bytequay.app.service.harness.HarnessModels.WatchStatus;
import com.bytequay.app.service.threads.NotificationService;
import com.bytequay.app.service.workspaces.WorkspaceRepositoryResolver;
import com.bytequay.app.service.workspaces.WorkspaceRepositoryResolver.RepositoryIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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
    private final WorkspaceRepositoryResolver repositories = mock(WorkspaceRepositoryResolver.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final List<Runnable> queued = new ArrayList<>();
    private final HarnessService service = new HarnessService(
            store, bootstrapper, repositories, notifications,
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

    private static Watch pendingWatch()
    {
        return new Watch("watch", "ws", "acme", "widget", 7, null,
                "/repo/widget", "feature", "PR", WatchStatus.BOOTSTRAP, null,
                "pending", "{}", 10_000, 0, null, 1, 1, null, null);
    }
}
