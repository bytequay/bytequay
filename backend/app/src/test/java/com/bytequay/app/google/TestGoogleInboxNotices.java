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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestGoogleInboxNotices
{
    @Test
    void watchesAccountsWithoutChangingSearchesAndPreservesCardsOnFailure()
            throws Exception
    {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var google = mock(GoogleWorkspaceService.class);
        var connector = mock(WorkspaceConnector.class);
        var first = mock(WorkspaceConnector.Session.class);
        var second = mock(WorkspaceConnector.Session.class);
        var one = new GoogleSnapshot.Account("one", "people/one", "one@example.com", "One");
        var two = new GoogleSnapshot.Account("two", "people/two", "two@example.com", "Two");
        when(google.status()).thenReturn(new GoogleSnapshot.Status(true, "", Optional.empty(), Optional.empty(), ImmutableList.of(one, two), "one"));
        when(connector.open("one", false)).thenReturn(first);
        when(connector.open("two", false)).thenReturn(second);
        when(first.call(eq("people_getMe"), anyMap())).thenReturn(mapper.readTree("{\"resourceName\":\"people/one\"}"));
        when(second.call(eq("people_getMe"), anyMap())).thenReturn(mapper.readTree("{\"resourceName\":\"people/two\"}"));
        for (var session : ImmutableList.of(first, second)) {
            when(session.call(eq("gmail_search"), anyMap())).thenReturn(mapper.readTree("{\"messages\":[{\"id\":\"m1\"}]}"));
            when(session.call(eq("gmail_get"), anyMap())).thenReturn(mapper.readTree("""
                    {"id":"m1","threadId":"thread","subject":"Hello","from":"Alex","snippet":"Please read",
                     "labelIds":["INBOX","UNREAD"]}
                    """));
        }
        var notices = new GoogleInboxNotices(google, connector);
        try {
            notices.refresh();
            var original = notices.current();
            assertThat(original).extracting(GoogleMailNotice::id).containsExactly("mail:one:m1", "mail:two:m1");
            assertThat(original).extracting(GoogleMailNotice::email).containsExactly("one@example.com", "two@example.com");
            assertThat(mapper.valueToTree(original.getFirst()).path("kind").asText()).isEqualTo("MAIL");
            verify(first).call("gmail_search", ImmutableMap.of("query", "in:inbox is:unread newer_than:1d", "maxResults", 25, "pageToken", ""));
            notices.refresh();
            assertThat(notices.current()).isEqualTo(original);
            when(first.call(eq("people_getMe"), anyMap())).thenReturn(mapper.readTree("{\"resourceName\":\"people/wrong\"}"));
            when(second.call(eq("gmail_search"), anyMap())).thenReturn(mapper.readTree("{\"messages\":[]}"));
            notices.refresh();
            assertThat(notices.current()).containsExactly(original.getFirst());
            when(google.status()).thenReturn(new GoogleSnapshot.Status(true, "", Optional.empty(), Optional.empty(), ImmutableList.of(two), "two"));
            assertThat(notices.current()).isEmpty();
        }
        finally {
            notices.close();
        }
    }

    @Test
    void dropsReadOrArchivedMessagesAndRejectsMismatchedResults()
            throws Exception
    {
        var mapper = new ObjectMapper();
        var google = mock(GoogleWorkspaceService.class);
        var connector = mock(WorkspaceConnector.class);
        var session = mock(WorkspaceConnector.Session.class);
        var account = new GoogleSnapshot.Account("one", "people/one", "one@example.com", "One");
        when(google.status()).thenReturn(new GoogleSnapshot.Status(true, "", Optional.empty(), Optional.empty(), ImmutableList.of(account), "one"));
        when(connector.open("one", false)).thenReturn(session);
        when(session.call(eq("people_getMe"), anyMap())).thenReturn(mapper.readTree("{\"resourceName\":\"people/one\"}"));
        when(session.call(eq("gmail_search"), anyMap())).thenReturn(mapper.readTree("{\"messages\":[{\"id\":\"m1\"}]}"));
        var notices = new GoogleInboxNotices(google, connector);
        try {
            for (String labels : ImmutableList.of("\"INBOX\"", "\"UNREAD\"")) {
                when(session.call(eq("gmail_get"), anyMap())).thenReturn(mapper.readTree("{\"id\":\"m1\",\"labelIds\":[" + labels + "]}"));
                notices.refresh();
                assertThat(notices.current()).isEmpty();
            }
            when(session.call(eq("gmail_get"), anyMap())).thenReturn(mapper.readTree("{\"id\":\"different\",\"labelIds\":[\"INBOX\",\"UNREAD\"]}"));
            notices.refresh();
            assertThat(notices.current()).isEmpty();
        }
        finally {
            notices.close();
        }
    }
}
