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
package com.bytequay.app.web;

import com.bytequay.app.beans.ds4.Ds4StatusDto;
import com.bytequay.app.service.local.ds4.Ds4Config;
import com.bytequay.app.service.local.ds4.Ds4InstallerService;
import com.bytequay.app.service.local.ds4.Ds4Instrumentation;
import com.bytequay.app.service.local.ds4.Ds4LifecycleService;
import com.bytequay.app.service.local.ds4.Ds4State;
import com.bytequay.app.service.local.ds4.Ds4Status;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestDs4Controller
{
    @Test
    void statusReturnsTheLifecycleSnapshotWithDerivedUptime()
    {
        Ds4LifecycleService lifecycle = mock(Ds4LifecycleService.class);
        when(lifecycle.status()).thenReturn(Ds4Status.stopped("http://127.0.0.1:8000"));
        Ds4Controller controller = new Ds4Controller(
                lifecycle, mock(Ds4InstallerService.class), new Ds4Instrumentation());

        Ds4StatusDto dto = controller.status();
        assertThat(dto.state()).isEqualTo(Ds4State.STOPPED);
        assertThat(dto.endpoint()).isEqualTo("http://127.0.0.1:8000");
        assertThat(dto.uptimeSec()).isEqualTo(0L);
    }

    @Test
    void startReturns409WhenAlreadyRunning()
    {
        Ds4LifecycleService lifecycle = mock(Ds4LifecycleService.class);
        when(lifecycle.status()).thenReturn(running());
        Ds4Controller controller = new Ds4Controller(
                lifecycle, mock(Ds4InstallerService.class), new Ds4Instrumentation());

        ResponseEntity<Ds4StatusDto> resp = controller.start();
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verify(lifecycle, never()).start();
    }

    @Test
    void start202sWhenStopped()
    {
        Ds4LifecycleService lifecycle = mock(Ds4LifecycleService.class);
        when(lifecycle.status()).thenReturn(Ds4Status.stopped("http://127.0.0.1:8000"));
        when(lifecycle.start()).thenReturn(starting());
        Ds4Controller controller = new Ds4Controller(
                lifecycle, mock(Ds4InstallerService.class), new Ds4Instrumentation());

        ResponseEntity<Ds4StatusDto> resp = controller.start();
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(lifecycle).start();
    }

    @Test
    void stopOnAnAttachedServerRequiresConfirmFirst()
    {
        Ds4LifecycleService lifecycle = mock(Ds4LifecycleService.class);
        when(lifecycle.status()).thenReturn(attached());
        Ds4Controller controller = new Ds4Controller(
                lifecycle, mock(Ds4InstallerService.class), new Ds4Instrumentation());

        ResponseEntity<Ds4Controller.StopResponse> resp = controller.stop(/* confirm */ false);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().requiresConfirm()).isTrue();
        // The lifecycle Stop must not have been triggered yet — the
        // UI gets to surface the confirm prompt before we actually
        // touch someone else's server.
        verify(lifecycle, never()).stop();
    }

    @Test
    void stopOnAnAttachedServerWithConfirmActuallyStops()
    {
        Ds4LifecycleService lifecycle = mock(Ds4LifecycleService.class);
        when(lifecycle.status()).thenReturn(attached());
        when(lifecycle.stop()).thenReturn(Ds4Status.stopped("http://127.0.0.1:8000"));
        Ds4Controller controller = new Ds4Controller(
                lifecycle, mock(Ds4InstallerService.class), new Ds4Instrumentation());

        ResponseEntity<Ds4Controller.StopResponse> resp = controller.stop(/* confirm */ true);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(resp.getBody().requiresConfirm()).isFalse();
        verify(lifecycle).stop();
    }

    @Test
    void putConfigWithRestartFalseTriggersSetButNotRestart()
    {
        Ds4LifecycleService lifecycle = mock(Ds4LifecycleService.class);
        when(lifecycle.status()).thenReturn(running());
        Ds4Controller controller = new Ds4Controller(
                lifecycle, mock(Ds4InstallerService.class), new Ds4Instrumentation());

        Ds4Controller.ConfigResponse resp = controller.setConfig(
                Ds4Config.defaults(), /* restartNow */ false);
        verify(lifecycle).setConfig(any());
        verify(lifecycle, never()).restartWithConfig(any());
        // Currently RUNNING means the UI gets restartRequired = true
        // so it can show the "applies on restart" banner.
        assertThat(resp.restartRequired()).isTrue();
    }

    @Test
    void putConfigWithRestartTrueTakesTheRelaunchPath()
    {
        Ds4LifecycleService lifecycle = mock(Ds4LifecycleService.class);
        when(lifecycle.status()).thenReturn(running());
        Ds4Controller controller = new Ds4Controller(
                lifecycle, mock(Ds4InstallerService.class), new Ds4Instrumentation());

        Ds4Controller.ConfigResponse resp = controller.setConfig(
                Ds4Config.defaults(), /* restartNow */ true);
        verify(lifecycle).restartWithConfig(any());
        verify(lifecycle, never()).setConfig(any());
        // restartRequired = false on the restart-now path because
        // the relaunch is already in flight.
        assertThat(resp.restartRequired()).isFalse();
    }

    private static Ds4Status running()
    {
        return new Ds4Status(
                Ds4State.RUNNING, "http://127.0.0.1:8000",
                /* pid */ 12345L,
                Instant.now(),
                /* spawnedByUs */ true,
                /* restartAttempts */ 0,
                /* lastError */ null);
    }

    private static Ds4Status starting()
    {
        return new Ds4Status(
                Ds4State.STARTING, "http://127.0.0.1:8000",
                12345L, null, true, 0, null);
    }

    private static Ds4Status attached()
    {
        return new Ds4Status(
                Ds4State.RUNNING, "http://127.0.0.1:8000",
                -1L,
                Instant.now(),
                /* spawnedByUs */ false,
                0, null);
    }
}
