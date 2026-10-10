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

import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** The bounded in-memory selection the user loaded, not a complete mailbox or calendar mirror. */
public record GoogleSnapshot(
        String accountId, String email, String name, boolean agentAccess,
        List<Mail> mail, String query, String nextPageToken, String mailSavedAt,
        List<Calendar> calendars, List<Event> events, String calendarId, String timeMin, String timeMax, String calendarSavedAt)
{
    public GoogleSnapshot
    {
        requireNonNull(accountId, "accountId is null");
        requireNonNull(email, "email is null");
        requireNonNull(name, "name is null");
        mail = ImmutableList.copyOf(mail);
        requireNonNull(query, "query is null");
        requireNonNull(nextPageToken, "nextPageToken is null");
        requireNonNull(mailSavedAt, "mailSavedAt is null");
        calendars = ImmutableList.copyOf(calendars);
        events = ImmutableList.copyOf(events);
        requireNonNull(calendarId, "calendarId is null");
        requireNonNull(timeMin, "timeMin is null");
        requireNonNull(timeMax, "timeMax is null");
        requireNonNull(calendarSavedAt, "calendarSavedAt is null");
    }

    public static GoogleSnapshot empty(String accountId, String email, String name)
    {
        return new GoogleSnapshot(accountId, email, name, false, ImmutableList.of(), "in:inbox", "", "",
                ImmutableList.of(), ImmutableList.of(), "primary", "", "", "");
    }

    public GoogleSnapshot withAgentAccess(boolean allowed)
    {
        return new GoogleSnapshot(accountId, email, name, allowed, mail, query, nextPageToken, mailSavedAt,
                calendars, events, calendarId, timeMin, timeMax, calendarSavedAt);
    }

    public record Mail(String id, String threadId, String subject, String from, String to, String date, String snippet, String body, boolean unread) {}

    public record Calendar(String id, String summary) {}

    public record Event(String id, String summary, String description, String start, String end, boolean allDay, String url) {}

    public record Account(String profile, String accountId, String email, String name) {}

    public record Status(boolean installed, String operation, Optional<String> error, Optional<GoogleSnapshot> snapshot, List<Account> accounts, String profile)
    {
        public Status
        {
            accounts = ImmutableList.copyOf(accounts);
            requireNonNull(profile, "profile is null");
            requireNonNull(operation, "operation is null");
            requireNonNull(error, "error is null");
            requireNonNull(snapshot, "snapshot is null");
        }
    }
}
