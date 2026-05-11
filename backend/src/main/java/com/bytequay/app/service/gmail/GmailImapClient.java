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
package com.bytequay.app.service.gmail;

import com.bytequay.app.domain.EmailThreadMeta;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.NoSuchProviderException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import org.eclipse.angus.mail.gimap.GmailFolder;
import org.eclipse.angus.mail.gimap.GmailMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Reads a Gmail mailbox over IMAP using the Gmail-specific protocol
 * extensions ({@code X-GM-THRID}, {@code X-GM-MSGID}, {@code X-GM-LABELS})
 * exposed by Angus Mail's {@code gimaps} provider. The Gmail-aware
 * provider lets us:
 * <ul>
 *   <li>address messages by stable Gmail IDs (matches the OAuth API
 *       semantics) instead of fragile per-folder UIDs;</li>
 *   <li>group messages by thread ID without parsing
 *       {@code References:} / {@code In-Reply-To:} ourselves;</li>
 *   <li>see Gmail labels (INBOX, UNREAD, etc.) as first-class data.</li>
 * </ul>
 *
 * <p>Per-request connect for now — IMAP TLS handshake adds ~300ms but
 * the alternative (a pooled {@code Store} per account) needs careful
 * lifecycle handling. We can add pooling later if the latency hurts.
 */
@Component
public class GmailImapClient
{
    /** Gmail-aware IMAP store. Plain {@code "imaps"} works too, but
     *  loses access to the {@code GmailFolder} / {@code GmailMessage}
     *  API and the X-GM-* extensions. */
    private static final String STORE_PROTOCOL = "gimaps";
    private static final String HOST = "imap.gmail.com";
    private static final int PORT = 993;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private static final Logger log = LoggerFactory.getLogger(GmailImapClient.class);

    /**
     * Lists the most recent {@code pageSize} INBOX threads, newest
     * first. Threads are aggregated from per-message rows by
     * {@code X-GM-THRID}; the head row of each thread (newest message)
     * supplies the from/subject/date shown in the list.
     *
     * <p>{@code unread} on the thread is true if any message in the
     * group lacks the {@code \Seen} flag — same semantics as the
     * Gmail web UI's bold-or-not row.
     */
    public List<EmailThreadMeta> listInboxThreads(String email, String appPassword, int pageSize)
    {
        if (pageSize <= 0) {
            return List.of();
        }
        Session session = Session.getInstance(properties());
        try (Store store = session.getStore(STORE_PROTOCOL)) {
            connect(store, email, appPassword);
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            try {
                int total = inbox.getMessageCount();
                if (total == 0) {
                    return List.of();
                }
                // Fetch a window slightly larger than pageSize so the
                // grouping has enough rows to merge multi-message threads
                // without the result being short. 3x is a heuristic — most
                // inboxes have ~1–2 messages per thread on average.
                int wanted = Math.min(total, Math.max(pageSize * 3, pageSize));
                int from = Math.max(1, total - wanted + 1);
                Message[] messages = inbox.getMessages(from, total);
                FetchProfile fp = new FetchProfile();
                fp.add(FetchProfile.Item.ENVELOPE);
                fp.add(FetchProfile.Item.FLAGS);
                fp.add(GmailFolder.FetchProfileItem.MSGID);
                fp.add(GmailFolder.FetchProfileItem.THRID);
                fp.add(GmailFolder.FetchProfileItem.LABELS);
                inbox.fetch(messages, fp);
                List<MessageRow> rows = new ArrayList<>(messages.length);
                for (Message raw : messages) {
                    if (raw instanceof GmailMessage gm) {
                        rows.add(toRow(gm));
                    }
                }
                return groupIntoThreads(rows, pageSize);
            }
            finally {
                inbox.close(false);
            }
        }
        catch (NoSuchProviderException e) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(500),
                    "Gmail IMAP provider missing from classpath", e);
        }
        catch (AuthenticationFailedException e) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(401),
                    "Google rejected the login (check app password)", e);
        }
        catch (MessagingException e) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(502),
                    "Gmail IMAP error: " + e.getMessage(), e);
        }
    }

    /**
     * Groups per-message rows into thread metas, newest thread first.
     * Public-package for the unit test — no live IMAP server needed
     * to exercise the grouping logic.
     */
    static List<EmailThreadMeta> groupIntoThreads(List<MessageRow> rows, int limit)
    {
        // LinkedHashMap preserves insertion order → first-seen thread
        // wins position. We sort the result anyway, so order here only
        // matters for determinism in the tie-break case.
        Map<Long, List<MessageRow>> byThread = new LinkedHashMap<>();
        for (MessageRow r : rows) {
            byThread.computeIfAbsent(r.thrId(), k -> new ArrayList<>()).add(r);
        }
        List<EmailThreadMeta> threads = new ArrayList<>(byThread.size());
        for (Map.Entry<Long, List<MessageRow>> entry : byThread.entrySet()) {
            List<MessageRow> group = entry.getValue();
            // Newest message is the "head" of the thread for display.
            group.sort(Comparator.comparing(MessageRow::receivedAt,
                    Comparator.nullsLast(Comparator.naturalOrder())));
            MessageRow head = group.get(group.size() - 1);
            boolean anyUnread = group.stream().anyMatch(MessageRow::unread);
            threads.add(new EmailThreadMeta(
                    Long.toUnsignedString(entry.getKey()),
                    Long.toUnsignedString(head.msgId()),
                    head.from(),
                    head.subject(),
                    "",  // IMAP has no native snippet — empty for v1
                    head.receivedAt(),
                    anyUnread,
                    group.size()));
        }
        threads.sort(Comparator.comparing(EmailThreadMeta::receivedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        if (threads.size() > limit) {
            return List.copyOf(threads.subList(0, limit));
        }
        return List.copyOf(threads);
    }

    private static MessageRow toRow(GmailMessage m)
            throws MessagingException
    {
        long thrId = m.getThrId();
        long msgId = m.getMsgId();
        String from = formatFrom(m);
        String subject = m.getSubject() == null ? "" : m.getSubject();
        Instant received = m.getReceivedDate() == null
                ? (m.getSentDate() == null ? null : m.getSentDate().toInstant())
                : m.getReceivedDate().toInstant();
        boolean unread = !m.isSet(Flags.Flag.SEEN);
        return new MessageRow(thrId, msgId, from, subject, received, unread);
    }

    private static String formatFrom(Message m)
            throws MessagingException
    {
        if (m.getFrom() == null || m.getFrom().length == 0) {
            return "";
        }
        // Gmail's API path returns a single "Name <addr@host>" string;
        // mirror that so the renderer's shortenFrom() logic works the
        // same regardless of which backend produced the row.
        if (m.getFrom()[0] instanceof InternetAddress addr) {
            String personal = addr.getPersonal();
            if (personal != null && !personal.isBlank()) {
                return personal + " <" + addr.getAddress() + ">";
            }
            return addr.getAddress();
        }
        return m.getFrom()[0].toString();
    }

    private static void connect(Store store, String email, String appPassword)
            throws MessagingException
    {
        store.connect(HOST, email, appPassword);
        log.debug("Gmail IMAP connected for {}", email);
    }

    private static Properties properties()
    {
        Properties props = new Properties();
        props.put("mail.store.protocol", STORE_PROTOCOL);
        props.put("mail.gimaps.host", HOST);
        props.put("mail.gimaps.port", String.valueOf(PORT));
        props.put("mail.gimaps.ssl.enable", "true");
        props.put("mail.gimaps.ssl.checkserveridentity", "true");
        props.put("mail.gimaps.connectiontimeout", String.valueOf(CONNECT_TIMEOUT_MS));
        props.put("mail.gimaps.timeout", String.valueOf(READ_TIMEOUT_MS));
        return props;
    }

    /** Flat per-message projection of the FETCH response. Visible to
     *  the test so we can drive {@link #groupIntoThreads} without a
     *  live server. */
    record MessageRow(long thrId, long msgId, String from, String subject, Instant receivedAt, boolean unread) {}
}
