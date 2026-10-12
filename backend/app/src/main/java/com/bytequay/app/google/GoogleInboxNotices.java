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

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

/** A bounded inbox watch, separate from the user's search page and agent access. */
@Service
public class GoogleInboxNotices
{
    private final GoogleWorkspaceService google;
    private final WorkspaceConnector connector;
    private final Map<String, List<GoogleMailNotice>> notices = new LinkedHashMap<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("google-inbox-notices").factory());

    public GoogleInboxNotices(GoogleWorkspaceService google, WorkspaceConnector connector)
    {
        this.google = requireNonNull(google, "google is null");
        this.connector = requireNonNull(connector, "connector is null");
    }

    @PostConstruct
    public void start()
    {
        executor.scheduleWithFixedDelay(this::refresh, 10, 60, TimeUnit.SECONDS);
    }

    public synchronized List<GoogleMailNotice> current()
    {
        List<String> profiles = google.status().accounts().stream().map(GoogleSnapshot.Account::profile).collect(ImmutableList.toImmutableList());
        notices.keySet().retainAll(profiles);
        return notices.values().stream().flatMap(List::stream).collect(ImmutableList.toImmutableList());
    }

    void refresh()
    {
        GoogleSnapshot.Status status = google.status();
        if (!status.installed() || !status.operation().isEmpty()) {
            return;
        }
        for (GoogleSnapshot.Account account : status.accounts()) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            try (var session = connector.open(account.profile(), false)) {
                checkState(account.accountId().equals(session.call("people_getMe", ImmutableMap.of()).path("resourceName").asText()), "Google account changed.");
                JsonNode messages = session.call("gmail_search", ImmutableMap.of("query", "in:inbox is:unread newer_than:1d", "maxResults", 25, "pageToken", "")).path("messages");
                checkState(messages.isArray() && messages.size() <= 25, "Unexpected Gmail search result.");
                ImmutableList.Builder<GoogleMailNotice> found = ImmutableList.builder();
                for (JsonNode item : messages) {
                    String messageId = text(item, "id");
                    checkState(!messageId.isBlank() && messageId.length() <= 200, "Invalid Gmail message identity.");
                    JsonNode mail = session.call("gmail_get", ImmutableMap.of("messageId", messageId, "format", "full"));
                    checkState(messageId.equals(text(mail, "id")), "Gmail returned a different message.");
                    boolean inbox = false;
                    boolean unread = false;
                    for (JsonNode label : mail.path("labelIds")) {
                        inbox |= label.asText().equals("INBOX");
                        unread |= label.asText().equals("UNREAD");
                    }
                    if (!inbox || !unread) {
                        continue;
                    }
                    String id = "mail:" + account.profile() + ":" + messageId;
                    Instant occurredAt;
                    synchronized (this) {
                        occurredAt = notices.getOrDefault(account.profile(), ImmutableList.of()).stream().filter(notice -> notice.id().equals(id))
                                .map(GoogleMailNotice::occurredAt).findFirst().orElseGet(Instant::now);
                    }
                    String subject = text(mail, "subject");
                    String sender = text(mail, "from");
                    String snippet = text(mail, "snippet");
                    found.add(new GoogleMailNotice(id, account.profile(), messageId, text(mail, "threadId"), account.email(),
                            clip(sender.isBlank() ? "Unknown sender" : sender, 160), clip(subject.isBlank() ? "(No subject)" : subject, 160),
                            clip(snippet.isBlank() ? "An unread email is waiting in your inbox." : snippet, 280), occurredAt));
                }
                synchronized (this) {
                    if (google.status().accounts().contains(account)) {
                        notices.put(account.profile(), found.build());
                    }
                }
            }
            catch (RuntimeException ignored) {
                // A failed read must not clear existing cards or stop checks for other accounts.
            }
        }
    }

    private static String text(JsonNode node, String field)
    {
        JsonNode value = node.path(field);
        checkState(value.isMissingNode() || value.isNull() || value.isTextual(), "Invalid Gmail field.");
        return value.asText("");
    }

    private static String clip(String text, int limit)
    {
        String flat = text.strip().replaceAll("\\s+", " ");
        if (flat.length() <= limit) {
            return flat;
        }
        return flat.substring(0, Character.isHighSurrogate(flat.charAt(limit - 1)) ? limit - 1 : limit) + "…";
    }

    @PreDestroy
    public void close()
    {
        executor.shutdownNow();
    }
}
