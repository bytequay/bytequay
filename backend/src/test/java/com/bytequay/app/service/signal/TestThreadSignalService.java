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
package com.bytequay.app.service.signal;

import com.bytequay.app.domain.ThreadSignal;
import com.bytequay.app.repository.sqlite.ThreadSignalStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestThreadSignalService
{
    private static final Instant NOW = Instant.parse("2026-06-24T09:00:00Z");

    private ThreadSignalStore store;
    private ThreadSignalServiceImpl service;

    @BeforeEach
    void setUp()
    {
        store = mock(ThreadSignalStore.class);
        service = new ThreadSignalServiceImpl(store);
        when(store.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void recordPersistsAValidSignalAndCoalescesBlanks()
    {
        ThreadSignal saved = service.record("thread-1", "  ", "system", "info", "Pushed", "  ", null);

        ArgumentCaptor<ThreadSignal> captor = ArgumentCaptor.forClass(ThreadSignal.class);
        verify(store).save(captor.capture());
        ThreadSignal s = captor.getValue();
        assertThat(s.threadId()).isEqualTo("thread-1");
        assertThat(s.taskId()).isNull();
        assertThat(s.body()).isNull();
        assertThat(s.title()).isEqualTo("Pushed");
        assertThat(s.readAt()).isNull();
        assertThat(s.id()).isNotBlank();
        assertThat(saved).isSameAs(s);
    }

    @Test
    void recordRejectsAnUnknownSourceOrIconKind()
    {
        assertThatThrownBy(() -> service.record("t", null, "bogus", "info", "x", null, null))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.record("t", null, "system", "bogus", "x", null, null))
                .isInstanceOf(ResponseStatusException.class);
        verify(store, never()).save(any());
    }

    @Test
    void recordRejectsABlankTitle()
    {
        assertThatThrownBy(() -> service.record("t", null, "agent", "warn", "  ", null, null))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void markReadFlipsAnUnreadSignal()
    {
        when(store.findById("s1")).thenReturn(Optional.of(signal("s1", null)));

        service.markRead("s1");

        ArgumentCaptor<ThreadSignal> captor = ArgumentCaptor.forClass(ThreadSignal.class);
        verify(store).save(captor.capture());
        assertThat(captor.getValue().readAt()).isNotNull();
    }

    @Test
    void markReadIsANoOpWhenUnknownOrAlreadyRead()
    {
        when(store.findById("missing")).thenReturn(Optional.empty());
        service.markRead("missing");
        when(store.findById("read")).thenReturn(Optional.of(signal("read", NOW)));
        service.markRead("read");
        verify(store, never()).save(any());
    }

    @Test
    void listDelegatesToTheStore()
    {
        ThreadSignal s = signal("s1", null);
        when(store.findByThread("thread-1")).thenReturn(List.of(s));
        assertThat(service.list("thread-1")).containsExactly(s);
    }

    private static ThreadSignal signal(String id, Instant readAt)
    {
        return new ThreadSignal(id, "thread-1", null, "system", "info", "Title", null, null, NOW, readAt);
    }
}
