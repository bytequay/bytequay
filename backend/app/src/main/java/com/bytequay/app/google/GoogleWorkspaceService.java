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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

@Service
public class GoogleWorkspaceService
{
    private static final String KEY = "google.workspace.accounts";
    private final Map<String, GoogleSnapshot.Account> accounts = new LinkedHashMap<>();
    private final Map<String, GoogleSnapshot> cache = new LinkedHashMap<>();
    private String profile = "";
    private final WorkspaceConnector connector;
    private final AppSettingsStore settings;
    private final ObjectMapper mapper;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private String operation = "";
    private Optional<String> error = Optional.empty();
    private Optional<Thread> worker = Optional.empty();
    private boolean cancelled;

    public GoogleWorkspaceService(WorkspaceConnector connector, AppSettingsStore settings, ObjectMapper mapper)
    {
        this.connector = requireNonNull(connector, "connector is null");
        this.settings = requireNonNull(settings, "settings is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    public synchronized GoogleSnapshot.Status status()
    {
        return new GoogleSnapshot.Status(connector.installed(), operation, error, snapshot(), ImmutableList.copyOf(accounts.values()), profile);
    }

    @PostConstruct
    public synchronized void loadAccounts()
    {
        settings.delete("google.workspace.snapshot");
        settings.get(KEY).ifPresent(raw -> {
            try {
                for (GoogleSnapshot.Account account : mapper.readValue(raw, GoogleSnapshot.Account[].class)) {
                    accounts.put(account.profile(), account);
                }
                profile = accounts.keySet().stream().findFirst().orElse("");
            }
            catch (JsonProcessingException failure) {
                throw new IllegalStateException("Google account settings could not be read.");
            }
        });
    }

    public synchronized Optional<GoogleSnapshot> snapshot()
    {
        return Optional.ofNullable(accounts.get(profile)).map(account -> cache.computeIfAbsent(profile,
                ignored -> GoogleSnapshot.empty(account.accountId(), account.email(), account.name())));
    }

    public synchronized GoogleSnapshot.Status select(String selected)
    {
        checkState(operation.isEmpty(), "Wait for the current Google operation to finish.");
        checkArgument(accounts.containsKey(selected), "Unknown Google account.");
        cache.replaceAll((key, value) -> value.withAgentAccess(false));
        profile = selected;
        error = Optional.empty();
        return status();
    }

    public synchronized List<String> chatCommand(String selected)
    {
        checkArgument(accounts.containsKey(selected), "Unknown Google account.");
        return connector.profileCommand(selected);
    }

    public synchronized GoogleSnapshot.Status install()
    {
        return start("Installing connector", connector::install);
    }

    public synchronized GoogleSnapshot.Status connect()
    {
        return connect(false);
    }

    public synchronized GoogleSnapshot.Status connect(boolean add)
    {
        checkState(connector.installed(), "Install the Google connector first.");
        String target = add || profile.isEmpty() ? UUID.randomUUID().toString() : profile;
        return start("Waiting for Google sign-in", () -> {
            try (var session = connector.open(target, true)) {
                JsonNode person = session.call("people_getMe", ImmutableMap.of());
                String id = required(person, "resourceName");
                String email = person.path("emailAddresses").path(0).path("value").asText();
                checkState(!email.isBlank(), "Google did not return an email address for this account.");
                synchronized (this) {
                    checkState(!cancelled, "Google operation cancelled.");
                    checkState(!accounts.containsKey(target) || accounts.get(target).accountId().equals(id),
                            "This profile belongs to another Google account. Use Add account to connect a different email.");
                    checkState(accounts.values().stream().noneMatch(account -> !account.profile().equals(target) && account.accountId().equals(id)),
                            "This Google account is already connected. Select its email address.");
                    GoogleSnapshot.Account account = new GoogleSnapshot.Account(target, id, email, person.path("names").path(0).path("displayName").asText());
                    cache.replaceAll((key, value) -> value.withAgentAccess(false));
                    accounts.put(target, account);
                    profile = target;
                    save(GoogleSnapshot.empty(id, email, account.name()));
                    saveAccounts();
                }
            }
        });
    }

    public synchronized GoogleSnapshot.Status refreshMail(String query, String pageToken)
    {
        checkArgument(query != null && query.length() <= 1000, "Gmail search must be at most 1000 characters.");
        checkArgument(pageToken != null && pageToken.length() <= 4096, "Invalid Gmail page token.");
        GoogleSnapshot before = connected();
        checkArgument(pageToken.isEmpty() || before.query().equals(query) && before.nextPageToken().equals(pageToken), "Refresh this search before loading its next page.");
        return start("Loading Gmail", () -> {
            try (var session = connector.open(profile, false)) {
                verifyAccount(session, before);
                JsonNode results = session.call("gmail_search", ImmutableMap.of("query", query, "maxResults", 25, "pageToken", pageToken));
                JsonNode messages = results.path("messages");
                checkState(messages.isArray() && messages.size() <= 25, "Unexpected Gmail search result.");
                ImmutableList.Builder<GoogleSnapshot.Mail> mail = ImmutableList.builder();
                for (JsonNode message : messages) {
                    String id = required(message, "id");
                    JsonNode detail = session.call("gmail_get", ImmutableMap.of("messageId", id, "format", "full"));
                    checkState(id.equals(required(detail, "id")), "Gmail returned a different message.");
                    boolean unread = false;
                    for (JsonNode label : detail.path("labelIds")) {
                        unread |= label.asText().equals("UNREAD");
                    }
                    mail.add(new GoogleSnapshot.Mail(id, text(detail, "threadId"), text(detail, "subject"), text(detail, "from"), text(detail, "to"),
                            text(detail, "date"), text(detail, "snippet"), text(detail, "body"), unread));
                }
                synchronized (this) {
                    GoogleSnapshot current = connected();
                    save(new GoogleSnapshot(current.accountId(), current.email(), current.name(), current.agentAccess(), mail.build(), query,
                            text(results, "nextPageToken"), Instant.now().toString(), current.calendars(), current.events(), current.calendarId(),
                            current.timeMin(), current.timeMax(), current.calendarSavedAt()));
                }
            }
        });
    }

    public synchronized GoogleSnapshot.Status refreshCalendar(String calendarId, String timeMin, String timeMax)
    {
        checkArgument(calendarId != null && !calendarId.isBlank() && calendarId.length() <= 1000, "Choose a calendar.");
        checkArgument(timeMin != null && timeMax != null && timeMin.length() <= 50 && timeMax.length() <= 50, "Choose a valid calendar range.");
        Instant start = OffsetDateTime.parse(timeMin).toInstant();
        Instant end = OffsetDateTime.parse(timeMax).toInstant();
        checkArgument(end.isAfter(start) && !end.isAfter(start.plus(31, ChronoUnit.DAYS)), "Choose a calendar range of up to 31 days.");
        GoogleSnapshot before = connected();
        return start("Loading Calendar", () -> {
            try (var session = connector.open(profile, false)) {
                verifyAccount(session, before);
                JsonNode calendars = session.call("calendar_list", ImmutableMap.of());
                checkState(calendars.isArray(), "Unexpected calendar list.");
                ImmutableList.Builder<GoogleSnapshot.Calendar> choices = ImmutableList.builder();
                for (JsonNode calendar : calendars) {
                    choices.add(new GoogleSnapshot.Calendar(required(calendar, "id"), text(calendar, "summary")));
                }
                JsonNode result = session.call("calendar_listEvents", ImmutableMap.of("calendarId", calendarId, "timeMin", timeMin, "timeMax", timeMax));
                checkState(result.isArray(), "Unexpected calendar response.");
                ImmutableList.Builder<GoogleSnapshot.Event> events = ImmutableList.builder();
                for (JsonNode event : result) {
                    JsonNode from = event.path("start");
                    JsonNode to = event.path("end");
                    boolean allDay = from.has("date");
                    events.add(new GoogleSnapshot.Event(required(event, "id"), text(event, "summary"), text(event, "description"),
                            required(from, allDay ? "date" : "dateTime"), required(to, allDay ? "date" : "dateTime"), allDay, text(event, "htmlLink")));
                }
                List<GoogleSnapshot.Event> sorted = events.build().stream().sorted(Comparator.comparing(event -> event.allDay()
                        ? LocalDate.parse(event.start()).atStartOfDay(ZoneId.systemDefault()).toInstant()
                        : OffsetDateTime.parse(event.start()).toInstant())).collect(ImmutableList.toImmutableList());
                synchronized (this) {
                    GoogleSnapshot current = connected();
                    save(new GoogleSnapshot(current.accountId(), current.email(), current.name(), current.agentAccess(), current.mail(), current.query(),
                            current.nextPageToken(), current.mailSavedAt(), choices.build(), sorted, calendarId, timeMin, timeMax, Instant.now().toString()));
                }
            }
        });
    }

    public synchronized GoogleSnapshot.Status agentAccess(boolean enabled)
    {
        save(connected().withAgentAccess(enabled));
        return status();
    }

    public synchronized GoogleSnapshot.Status disconnect()
    {
        checkState(operation.isEmpty(), "Wait for the current Google operation to finish.");
        cache.remove(profile);
        accounts.remove(profile);
        profile = accounts.keySet().stream().findFirst().orElse("");
        saveAccounts();
        error = Optional.empty();
        // Removing account metadata never revokes the Google credential or changes a terminal login.
        return status();
    }

    public synchronized GoogleSnapshot.Status cancel()
    {
        if (!operation.isEmpty()) {
            cancelled = true;
            operation = "Cancelling Google operation";
            worker.ifPresent(Thread::interrupt);
        }
        return status();
    }

    private void verifyAccount(WorkspaceConnector.Session session, GoogleSnapshot expected)
    {
        JsonNode person = session.call("people_getMe", ImmutableMap.of());
        checkState(expected.accountId().equals(required(person, "resourceName")), "The connector is signed into another Google account. Reconnect before refreshing.");
    }

    private GoogleSnapshot connected()
    {
        return snapshot().orElseThrow(() -> new IllegalStateException("Connect Google in Settings first."));
    }

    private GoogleSnapshot.Status start(String label, Runnable action)
    {
        checkState(operation.isEmpty(), "A Google operation is already running.");
        operation = label;
        error = Optional.empty();
        cancelled = false;
        executor.submit(() -> {
            try {
                synchronized (this) {
                    worker = Optional.of(Thread.currentThread());
                    checkState(!cancelled, "Google operation cancelled.");
                }
                action.run();
            }
            catch (RuntimeException failure) {
                synchronized (this) {
                    error = Optional.of(Optional.ofNullable(failure.getMessage()).orElse("Google operation failed. Please retry."));
                }
            }
            finally {
                synchronized (this) {
                    worker = Optional.empty();
                    operation = "";
                    cancelled = false;
                }
            }
        });
        return status();
    }

    private void save(GoogleSnapshot snapshot)
    {
        checkState(!cancelled, "Google operation cancelled.");
        cache.put(profile, snapshot);
    }

    private void saveAccounts()
    {
        try {
            settings.put(KEY, mapper.writeValueAsString(accounts.values()));
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("Unable to save Google accounts.");
        }
    }

    public synchronized GoogleSnapshot.Status archive(String selected, String messageId)
    {
        checkArgument(profile.equals(selected), "Select the account again before archiving.");
        GoogleSnapshot before = connected();
        checkArgument(before.mail().stream().anyMatch(mail -> mail.id().equals(messageId)), "Refresh and select a message first.");
        return start("Archiving message", () -> {
            try (var session = connector.open(selected, false)) {
                verifyAccount(session, before);
                session.call("gmail_modify", ImmutableMap.of("messageId", messageId, "removeLabelIds", ImmutableList.of("INBOX")));
                synchronized (this) {
                    GoogleSnapshot current = connected();
                    save(new GoogleSnapshot(current.accountId(), current.email(), current.name(), current.agentAccess(),
                            current.mail().stream().filter(mail -> !mail.id().equals(messageId)).collect(ImmutableList.toImmutableList()), current.query(),
                            current.nextPageToken(), current.mailSavedAt(), current.calendars(), current.events(), current.calendarId(),
                            current.timeMin(), current.timeMax(), current.calendarSavedAt()));
                }
            }
        });
    }

    public synchronized GoogleSnapshot.Status send(String selected, String to, String subject, String body, String replyId)
    {
        checkArgument(profile.equals(selected), "Select the account again before sending.");
        checkArgument(to != null && !to.isBlank() && to.length() <= 2000 && !to.contains("\r") && !to.contains("\n"), "Enter valid recipients.");
        List<String> recipients = Arrays.stream(to.split(",", -1)).map(String::trim).collect(ImmutableList.toImmutableList());
        checkArgument(recipients.stream().allMatch(value -> value.matches("[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+")), "Enter comma-separated email addresses.");
        checkArgument(subject != null && subject.length() <= 1000 && !subject.contains("\r") && !subject.contains("\n"), "Enter a valid subject.");
        checkArgument(body != null && !body.isBlank() && body.length() <= 100000, "Enter a message of up to 100,000 characters.");
        checkArgument(replyId != null, "Invalid reply.");
        GoogleSnapshot before = connected();
        Optional<GoogleSnapshot.Mail> reply = before.mail().stream().filter(mail -> mail.id().equals(replyId)).findFirst();
        checkArgument(replyId.isEmpty() || reply.isPresent(), "Refresh and select the message to reply to.");
        return start("Sending message", () -> {
            try (var session = connector.open(selected, false)) {
                verifyAccount(session, before);
                var args = ImmutableMap.<String, Object>builder().put("to", recipients).put("subject", subject).put("body", body).put("isHtml", false);
                if (reply.isPresent()) {
                    args.put("threadId", reply.orElseThrow().threadId());
                    JsonNode draft = session.call("gmail_createDraft", args.buildOrThrow());
                    required(session.call("gmail_sendDraft", ImmutableMap.of("draftId", required(draft, "id"))), "id");
                }
                else {
                    required(session.call("gmail_send", args.buildOrThrow()), "id");
                }
            }
            catch (RuntimeException failure) {
                throw new IllegalStateException("Sending could not be confirmed. Check Sent mail and Drafts before retrying.");
            }
        });
    }

    private static String required(JsonNode node, String field)
    {
        String value = text(node, field);
        checkState(!value.isBlank(), "Google returned incomplete data (%s).", field);
        return value;
    }

    private static String text(JsonNode node, String field)
    {
        JsonNode value = node.path(field);
        checkState(value.isMissingNode() || value.isNull() || value.isTextual(), "Google returned an invalid %s value.", field);
        String text = value.asText("");
        checkState(text.length() <= 1024 * 1024, "Google returned too much %s data.", field);
        return text;
    }

    @PreDestroy
    public void close()
    {
        executor.shutdownNow();
    }
}
