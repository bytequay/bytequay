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
package com.bytequay.app.service.distillation;

import com.bytequay.app.domain.DistillationSignal;
import com.bytequay.app.repository.sqlite.DistillationSignalStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TestDistillationSignalService
{
    private final DistillationSignalStore store = mock(DistillationSignalStore.class);
    private final DistillationSignalServiceImpl service =
            new DistillationSignalServiceImpl(store, new ObjectMapper());

    @Test
    void recordPersistsASignalWithTheSerialisedContext()
    {
        service.record("backlog-skip", "b1", "skipped", "nope", Map.of("title", "X"), "t1", "ws1");

        ArgumentCaptor<DistillationSignal> captor = ArgumentCaptor.forClass(DistillationSignal.class);
        verify(store).save(captor.capture());
        DistillationSignal signal = captor.getValue();
        assertThat(signal.eventType()).isEqualTo("backlog-skip");
        assertThat(signal.sourceId()).isEqualTo("b1");
        assertThat(signal.userDecision()).isEqualTo("skipped");
        assertThat(signal.reason()).isEqualTo("nope");
        assertThat(signal.contextSnapshotJson()).contains("\"title\":\"X\"");
        assertThat(signal.threadId()).isEqualTo("t1");
        assertThat(signal.workspaceId()).isEqualTo("ws1");
        assertThat(signal.id()).isNotBlank();
        assertThat(signal.createdAt()).isNotNull();
    }

    @Test
    void recordDefaultsAnAbsentContextToEmptyJson()
    {
        service.record("plan-approve", "p1", "approved", null, null, null, null);

        ArgumentCaptor<DistillationSignal> captor = ArgumentCaptor.forClass(DistillationSignal.class);
        verify(store).save(captor.capture());
        assertThat(captor.getValue().contextSnapshotJson()).isEqualTo("{}");
    }

    @Test
    void recordSwallowsStoreFailuresSoItNeverBreaksTheUserAction()
    {
        doThrow(new RuntimeException("db down")).when(store).save(any());
        assertThatNoException().isThrownBy(
                () -> service.record("backlog-revive", "b1", "revived", null, null, "t1", "ws1"));
    }
}
