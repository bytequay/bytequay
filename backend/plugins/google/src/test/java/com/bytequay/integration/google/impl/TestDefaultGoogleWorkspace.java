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
package com.bytequay.integration.google.impl;

import com.bytequay.core.store.AppSettingsStore;
import com.bytequay.integration.google.GoogleBusyTime;
import com.bytequay.integration.google.GoogleSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestDefaultGoogleWorkspace
{
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final WorkspaceConnector connector = mock(WorkspaceConnector.class);
    private final WorkspaceConnector.Session session = mock(WorkspaceConnector.Session.class);
    private final AppSettingsStore settings = mock(AppSettingsStore.class);
    private final Map<String, String> saved = new ConcurrentHashMap<>();
    private final DefaultGoogleWorkspace google = new DefaultGoogleWorkspace(connector, settings, mapper);

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
        when(connector.open(anyString(), anyBoolean())).thenReturn(session);
        when(connector.open(anyString(), anyBoolean(), any(Runnable.class))).thenReturn(session);
        when(session.call(eq("people_getMe"), anyMap())).thenReturn(json("{\"resourceName\":\"people/one\",\"emailAddresses\":[{\"value\":\"one@example.com\"}]}"));
        when(session.call(eq("gmail_search"), anyMap())).thenReturn(json("{\"messages\":[{\"id\":\"m1\"}],\"nextPageToken\":\"next\"}"));
        when(session.call(eq("gmail_get"), anyMap())).thenReturn(json("{\"id\":\"m1\",\"subject\":\"Project\",\"body\":\"saved text\",\"labelIds\":[\"UNREAD\"]}"));
        google.loadAccounts();
        google.connect(false);
        awaitDone();
    }

    @AfterEach
    void cleanup()
    {
        google.close();
    }

    @Test
    void waitsForSignInOnlyWhenTheConnectorRequestsAuthentication()
            throws Exception
    {
        String selected = google.status().profile();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Runnable> authenticationRequired = new AtomicReference<>();
        when(connector.open(eq(selected), eq(true), any(Runnable.class))).thenAnswer(call -> {
            authenticationRequired.set(call.getArgument(2));
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return session;
        });
        assertThat(google.connect(false).operation()).isEqualTo("Checking Google connection");
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            assertThat(google.status().operation()).isEqualTo("Checking Google connection");
            authenticationRequired.get().run();
            assertThat(google.status().operation()).isEqualTo("Waiting for Google sign-in");
        }
        finally {
            release.countDown();
        }
        awaitDone();
        assertThat(google.status().profile()).isEqualTo(selected);
        assertThat(google.status().error()).isEmpty();
        google.connect(false);
        awaitDone();
        assertThat(google.status().error()).isEmpty();
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
        when(session.call(eq("people_getMe"), anyMap())).thenReturn(json("{\"resourceName\":\"people/two\",\"emailAddresses\":[{\"value\":\"two@example.com\"}]}"));
        google.refreshMail("in:inbox", "");
        awaitDone();
        assertThat(google.status().error()).get().asString().contains("another Google account");
        assertThat(google.snapshot()).contains(initial);
        verify(session, never()).call(eq("gmail_search"), anyMap());
        google.agentAccess(true);
        google.connect(false);
        awaitDone();
        assertThat(google.snapshot().orElseThrow().accountId()).isEqualTo("people/one");
        assertThat(google.status().error()).get().asString().contains("Use Add account");
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
            assertThatThrownBy(() -> google.connect(false)).isInstanceOf(IllegalStateException.class);
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

    @Test
    void readsTodaysBusyTimesWithoutTouchingTheCalendarView()
            throws Exception
    {
        when(session.call(eq("calendar_listEvents"), anyMap())).thenReturn(json("""
                [{"id":"e1","summary":"Design review","start":{"dateTime":"2026-10-16T10:00:00+08:00"},"end":{"dateTime":"2026-10-16T11:00:00+08:00"}},
                 {"id":"e2","summary":"Focus","transparency":"transparent","start":{"dateTime":"2026-10-16T13:00:00+08:00"},"end":{"dateTime":"2026-10-16T15:00:00+08:00"}},
                 {"id":"e3","summary":"Optional sync","attendees":[{"email":"one@example.com","self":true,"responseStatus":"declined"}],
                  "start":{"dateTime":"2026-10-16T16:00:00+08:00"},"end":{"dateTime":"2026-10-16T17:00:00+08:00"}},
                 {"id":"e4","summary":"Day off","start":{"date":"2026-10-16"},"end":{"date":"2026-10-17"}}]
                """));
        GoogleSnapshot before = google.snapshot().orElseThrow();

        List<GoogleBusyTime> busy = google.busyTimes(before.accountId(), Instant.parse("2026-10-15T16:00:00Z"), Instant.parse("2026-10-16T16:00:00Z"));

        assertThat(busy).containsExactly(new GoogleBusyTime("2026-10-16T10:00:00+08:00", "2026-10-16T11:00:00+08:00", false),
                new GoogleBusyTime("2026-10-16", "2026-10-17", true));
        assertThat(google.snapshot()).contains(before);
        assertThatThrownBy(() -> google.busyTimes("people/someone-else", Instant.parse("2026-10-15T16:00:00Z"), Instant.parse("2026-10-16T16:00:00Z")))
                .hasMessageContaining("Another Google account");
    }

    @Test
    void keepsMailOnlyInMemoryAcrossRestarts()
            throws Exception
    {
        google.refreshMail("in:inbox", "");
        awaitDone();
        assertThat(saved.values()).allSatisfy(value -> assertThat(value).doesNotContain("saved text", "Project", "mailSavedAt"));
        verify(settings).delete("google.workspace.snapshot");
        var restarted = new DefaultGoogleWorkspace(connector, settings, mapper);
        try {
            restarted.loadAccounts();
            assertThat(restarted.snapshot().orElseThrow().mail()).isEmpty();
            assertThat(restarted.status().accounts()).hasSize(1);
        }
        finally {
            restarted.close();
        }
    }

    @Test
    void switchesAccountsWithoutMixingCachedMailOrConsent()
            throws Exception
    {
        google.refreshMail("in:inbox", "");
        awaitDone();
        String first = google.status().profile();
        google.agentAccess(true);
        when(session.call(eq("people_getMe"), anyMap())).thenReturn(json("{\"resourceName\":\"people/two\",\"emailAddresses\":[{\"value\":\"two@example.com\"}]}"));
        google.connect(true);
        awaitDone();
        assertThat(google.status().accounts()).hasSize(2);
        assertThat(google.snapshot().orElseThrow().mail()).isEmpty();
        assertThatThrownBy(() -> google.send(first, "to@example.com", "Subject", "Body", "")).hasMessageContaining("Select the account");
        google.select(first);
        assertThat(google.snapshot().orElseThrow().mail()).hasSize(1);
        assertThat(google.snapshot().orElseThrow().agentAccess()).isFalse();
    }

    @Test
    void verifiesAccountBeforeWritesAndPreservesMailAfterFailure()
            throws Exception
    {
        google.refreshMail("in:inbox", "");
        awaitDone();
        String selected = google.status().profile();
        when(session.call(eq("people_getMe"), anyMap())).thenReturn(json("{\"resourceName\":\"people/wrong\"}"));
        google.archive(selected, "m1");
        awaitDone();
        verify(session, never()).call(eq("gmail_modify"), anyMap());
        assertThat(google.snapshot().orElseThrow().mail()).hasSize(1);
        assertThatThrownBy(() -> google.send(selected, "a@example.com\r\nBcc: b@example.com", "Hi", "Body", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sendsToValidatedRecipientsAndArchivesOnlyTheSelectedMessage()
            throws Exception
    {
        google.refreshMail("in:inbox", "");
        awaitDone();
        when(session.call(eq("gmail_send"), anyMap())).thenReturn(json("{\"id\":\"sent-1\"}"));
        google.send(google.status().profile(), "one@example.com, two@example.com", "Hello", "Message", "");
        awaitDone();
        assertThat(google.status().error()).isEmpty();
        verify(session).call("gmail_send", ImmutableMap.of("to", ImmutableList.of("one@example.com", "two@example.com"), "subject", "Hello", "body", "Message", "isHtml", false));
        google.archive(google.status().profile(), "m1");
        awaitDone();
        assertThat(google.status().error()).isEmpty();
        assertThat(google.snapshot().orElseThrow().mail()).isEmpty();
    }

    @Test
    void sendsRepliesAsThreadedDraftsWithoutAutomaticRetries()
            throws Exception
    {
        google.refreshMail("in:inbox", "");
        awaitDone();
        when(session.call(eq("gmail_createDraft"), anyMap())).thenReturn(json("{\"id\":\"draft-1\"}"));
        when(session.call(eq("gmail_sendDraft"), anyMap())).thenThrow(new IllegalStateException("timeout"));
        when(session.call("gmail_get", ImmutableMap.of("messageId", "m1", "format", "raw"))).thenReturn(rawMail("From: to@example.com"));
        google.send(google.status().profile(), "to@example.com", "Re: Project", "Reply body", "m1");
        awaitDone();
        verify(session).call("gmail_sendDraft", ImmutableMap.of("draftId", "draft-1"));
        assertThat(google.status().error()).get().asString().contains("Check Sent mail and Drafts");
    }

    @Test
    void resolvesReplyRecipientsAndVerifiesTheConfirmedAddressesBeforeSending()
            throws Exception
    {
        google.refreshMail("in:inbox", "");
        awaitDone();
        String selected = google.status().profile();
        when(session.call("gmail_get", ImmutableMap.of("messageId", "m1", "format", "raw")))
                .thenReturn(rawMail("From: notifications@example.com\r\nReply-To: Support <support@example.com>, Team <team@example.com>"));
        assertThat(google.replyRecipients(selected, "m1")).containsExactly("support@example.com", "team@example.com");
        assertThatThrownBy(() -> google.replyRecipients("other-account", "m1")).hasMessageContaining("Select the account");
        assertThatThrownBy(() -> google.replyRecipients(selected, "missing")).hasMessageContaining("select a message");
        when(session.call(eq("gmail_createDraft"), anyMap())).thenReturn(json("{\"id\":\"reply-draft\"}"));
        when(session.call(eq("gmail_sendDraft"), anyMap())).thenReturn(json("{\"id\":\"sent\"}"));
        google.send(selected, "notifications@example.com", "Re: Project", "Body", "m1");
        awaitDone();
        assertThat(google.status().error()).isPresent();
        verify(session, never()).call(eq("gmail_createDraft"), anyMap());
        google.send(selected, "support@example.com, team@example.com", "Re: Project", "Body", "m1");
        awaitDone();
        assertThat(google.status().error()).isEmpty();
        verify(session).call("gmail_createDraft", ImmutableMap.of("to", ImmutableList.of("support@example.com", "team@example.com"),
                "subject", "Re: Project", "body", "Body", "isHtml", false, "threadId", ""));
    }

    private JsonNode rawMail(String headers)
    {
        // MIME bodies may exceed the text-field limit even when the reply headers are short.
        return mapper.valueToTree(ImmutableMap.of("id", "m1", "raw", Base64.getUrlEncoder().encodeToString((headers + "\r\n\r\n" + "x".repeat(1024 * 1024)).getBytes(UTF_8))));
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
