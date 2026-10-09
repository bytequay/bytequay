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
package com.bytequay.app.google;

import com.bytequay.core.store.AppSettingsStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestGoogleWorkspaceService
{
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final WorkspaceConnector connector = mock(WorkspaceConnector.class);
    private final WorkspaceConnector.Session session = mock(WorkspaceConnector.Session.class);
    private final AppSettingsStore settings = mock(AppSettingsStore.class);
    private final Map<String, String> saved = new ConcurrentHashMap<>();
    private final GoogleWorkspaceService google = new GoogleWorkspaceService(connector, settings, mapper);

    @BeforeEach
    void setup()
            throws Exception
    {
        when(settings.get(anyString())).thenAnswer(call -> Optional.ofNullable(saved.get(call.getArgument(0))));
        doAnswer(call -> {
            saved.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(settings).put(anyString(), anyString());
        doAnswer(call -> {
            saved.remove(call.getArgument(0));
            return null;
        }).when(settings).delete(anyString());
        when(connector.installed()).thenReturn(true);
        when(connector.open(anyBoolean())).thenReturn(session);
        when(session.call(eq("people_getMe"), anyMap())).thenReturn(json("{\"resourceName\":\"people/one\",\"emailAddresses\":[{\"value\":\"one@example.com\"}]}"));
        when(session.call(eq("gmail_search"), anyMap())).thenReturn(json("{\"messages\":[{\"id\":\"m1\"}],\"nextPageToken\":\"next\"}"));
        when(session.call(eq("gmail_get"), anyMap())).thenReturn(json("{\"id\":\"m1\",\"subject\":\"Project\",\"body\":\"saved text\",\"labelIds\":[\"UNREAD\"]}"));
        google.connect();
        awaitDone();
    }

    @AfterEach
    void cleanup()
    {
        google.close();
    }

    @Test
    void savesAReadOnlyPageAndPreservesItAfterFailure()
            throws Exception
    {
        assertThat(google.snapshot().orElseThrow().agentAccess()).isFalse();
        google.refreshMail("in:inbox", "");
        awaitDone();
        GoogleSnapshot initial = google.snapshot().orElseThrow();
        assertThat(initial.mail()).hasSize(1);
        assertThat(initial.mail().getFirst().body()).isEqualTo("saved text");
        assertThat(initial.mail().getFirst().unread()).isTrue();
        verify(session).call("gmail_search", ImmutableMap.of("query", "in:inbox", "maxResults", 25, "pageToken", ""));
        when(session.call(eq("gmail_get"), anyMap())).thenThrow(new IllegalStateException("Unable to read message"));
        google.refreshMail("newer_than:1d", "");
        awaitDone();
        assertThat(google.status().error()).contains("Unable to read message");
        assertThat(google.snapshot()).contains(initial);
        assertThatThrownBy(() -> google.refreshMail("different", "next")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void changedSharedAccountCannotOverwriteSavedMail()
            throws Exception
    {
        GoogleSnapshot initial = google.snapshot().orElseThrow();
        when(session.call(eq("people_getMe"), anyMap())).thenReturn(json("{\"resourceName\":\"people/two\"}"));
        google.refreshMail("in:inbox", "");
        awaitDone();
        assertThat(google.status().error()).get().asString().contains("another Google account");
        assertThat(google.snapshot()).contains(initial);
        verify(session, never()).call(eq("gmail_search"), anyMap());
        google.agentAccess(true);
        google.connect();
        awaitDone();
        assertThat(google.snapshot().orElseThrow().accountId()).isEqualTo("people/two");
        assertThat(google.snapshot().orElseThrow().agentAccess()).isFalse();
    }

    @Test
    void serializesRefreshAndPreservesRevokedAgentAccess()
            throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(session.call(eq("gmail_search"), anyMap())).thenAnswer(call -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return json("{\"messages\":[]}");
        });
        google.agentAccess(true);
        google.refreshMail("in:inbox", "");
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            assertThatThrownBy(google::disconnect).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(google::connect).isInstanceOf(IllegalStateException.class);
            google.agentAccess(false);
        }
        finally {
            release.countDown();
        }
        awaitDone();
        assertThat(google.snapshot().orElseThrow().agentAccess()).isFalse();
        google.disconnect();
        assertThat(google.snapshot()).isEmpty();
        verify(session, never()).call(eq("auth_clear"), anyMap());
    }

    @Test
    void cancellingRefreshKeepsTheSavedSnapshot()
            throws Exception
    {
        google.refreshMail("in:inbox", "");
        awaitDone();
        GoogleSnapshot before = google.snapshot().orElseThrow();
        CountDownLatch entered = new CountDownLatch(1);
        when(session.call(eq("gmail_search"), anyMap())).thenAnswer(call -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await(5, TimeUnit.SECONDS);
            }
            catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            // A connector can finish just as cancellation arrives; its late result must still be discarded.
            return json("{\"messages\":[]}");
        });
        google.refreshMail("newer_than:1d", "");
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        google.cancel();
        awaitDone();
        assertThat(google.snapshot()).contains(before);
        assertThat(google.status().error()).contains("Google operation cancelled.");
    }

    @Test
    void savesAllDayAndTimedEventsWithExplicitCoverage()
            throws Exception
    {
        when(session.call(eq("calendar_list"), anyMap())).thenReturn(json("[{\"id\":\"primary\",\"summary\":\"Personal\"}]"));
        when(session.call(eq("calendar_listEvents"), anyMap())).thenReturn(json("""
                [{"id":"e1","summary":"Day off","start":{"date":"2026-10-12"},"end":{"date":"2026-10-13"}},
                 {"id":"e2","summary":"Meeting","start":{"dateTime":"2026-10-11T10:00:00+08:00"},"end":{"dateTime":"2026-10-11T11:00:00+08:00"}}]
                """));
        google.refreshCalendar("primary", "2026-10-09T00:00:00+08:00", "2026-10-16T00:00:00+08:00");
        awaitDone();
        GoogleSnapshot data = google.snapshot().orElseThrow();
        assertThat(data.events()).extracting(GoogleSnapshot.Event::id).containsExactly("e2", "e1");
        assertThat(data.events().getLast().allDay()).isTrue();
        assertThat(data.timeMin()).isEqualTo("2026-10-09T00:00:00+08:00");
        assertThatThrownBy(() -> google.refreshCalendar("primary", "2026-10-09T00:00:00Z", "2027-10-09T00:00:00Z"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void awaitDone()
            throws InterruptedException
    {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!google.status().operation().isEmpty() && System.nanoTime() < until) {
            Thread.sleep(10);
        }
        assertThat(google.status().operation()).isEmpty();
    }

    private JsonNode json(String raw)
            throws Exception
    {
        return mapper.readTree(raw);
    }
}
