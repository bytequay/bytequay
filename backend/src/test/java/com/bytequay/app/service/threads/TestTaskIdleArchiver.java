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
package com.bytequay.app.service.threads;

import com.bytequay.app.developmentflow.task.V2TaskControlService;
import com.bytequay.app.service.WorkspaceBehaviorService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class TestTaskIdleArchiver
{
    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");

    private final WorkspaceBehaviorService behavior = mock(
            WorkspaceBehaviorService.class, Mockito.RETURNS_DEEP_STUBS);
    private final V2TaskControlService v2Controls = mock(V2TaskControlService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<V2TaskControlService> v2Provider =
            mock(ObjectProvider.class);
    private final TaskIdleArchiver archiver = new TaskIdleArchiver(
            behavior, v2Provider);

    @Test
    void routesV2IdleCandidatesThroughTypedTaskControls()
    {
        when(behavior.get().archiveIdleAfter()).thenReturn("1h");
        when(v2Provider.getIfAvailable()).thenReturn(v2Controls);
        when(v2Controls.idleArchiveCandidates(
                NOW.minusSeconds(3600), NOW, 200)).thenReturn(List.of("v2"));

        archiver.maintain(NOW);

        verify(v2Controls).idleArchiveCandidates(
                NOW.minusSeconds(3600), NOW, 200);
        verify(v2Controls).archiveIfIdle(
                "v2", NOW.minusSeconds(3600), NOW);

        archiver.maintain(NOW.plusSeconds(3599));
        verifyNoMoreInteractions(v2Controls);

        when(v2Controls.idleArchiveCandidates(
                NOW, NOW.plusSeconds(3600), 200)).thenReturn(List.of());
        archiver.maintain(NOW.plusSeconds(3600));
        verify(v2Controls).idleArchiveCandidates(
                NOW, NOW.plusSeconds(3600), 200);
    }

    @Test
    void neverCadenceSkipsTheSweep()
    {
        when(behavior.get().archiveIdleAfter()).thenReturn("never");

        archiver.maintain(NOW);

        verifyNoInteractions(v2Provider);
    }
}
