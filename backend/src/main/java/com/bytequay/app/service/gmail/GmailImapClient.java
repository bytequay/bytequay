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

import com.bytequay.app.domain.EmailMessageDetail;
import com.bytequay.app.domain.EmailThreadDetail;
import com.bytequay.app.domain.EmailThreadMeta;
import jakarta.mail.Address;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.NoSuchProviderException;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMultipart;
import org.eclipse.angus.mail.gimap.GmailFolder;
import org.eclipse.angus.mail.gimap.GmailMessage;
import org.eclipse.angus.mail.gimap.GmailMsgIdTerm;
import org.eclipse.angus.mail.gimap.GmailThrIdTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
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

    /** Where {@link #getThreadFull} searches for messages by thread ID.
     *  All Mail contains every message regardless of label, so a thread
     *  the user has just archived (or that lives in a custom label) is
     *  still findable by X-GM-THRID. The path is fixed for English-locale
     *  Gmail accounts; localized accounts will need an LSUB-based
     *  resolver later (RFC 6154 \All flag).
     *
     *  <p>For purely-inbox lookups {@link #listInboxThreads} sticks to
     *  INBOX since it's much smaller and the search is cheaper. */
    private static final String ALL_MAIL_FOLDER = "[Gmail]/All Mail";

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

    /**
     * Loads a single conversation by Gmail thread ID, oldest-first,
     * with bodies parsed into text + HTML. Searches {@link #ALL_MAIL_FOLDER}
     * so threads remain findable after archive (no INBOX label).
     *
     * <p>The {@code threadId} parameter is the unsigned-decimal string
     * produced by {@link #listInboxThreads}; we parse it back to a long
     * for the {@link GmailThrIdTerm} search. Returns 404 if no messages
     * carry that thread ID — most likely a stale ID after the user
     * permanently deleted the thread on gmail.com.
     */
    public EmailThreadDetail getThreadFull(String email, String appPassword, String threadId)
    {
        long thrId;
        try {
            thrId = Long.parseUnsignedLong(threadId);
        }
        catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "threadId is not a Gmail thread id: " + threadId);
        }
        Session session = Session.getInstance(properties());
        try (Store store = session.getStore(STORE_PROTOCOL)) {
            connect(store, email, appPassword);
            Folder allMail = store.getFolder(ALL_MAIL_FOLDER);
            allMail.open(Folder.READ_ONLY);
            try {
                Message[] hits = allMail.search(new GmailThrIdTerm(thrId));
                if (hits.length == 0) {
                    throw new ResponseStatusException(HttpStatusCode.valueOf(404),
                            "thread " + threadId + " not found in " + ALL_MAIL_FOLDER);
                }
                FetchProfile fp = new FetchProfile();
                fp.add(FetchProfile.Item.ENVELOPE);
                fp.add(FetchProfile.Item.FLAGS);
                fp.add(FetchProfile.Item.CONTENT_INFO);
                fp.add(GmailFolder.FetchProfileItem.MSGID);
                fp.add(GmailFolder.FetchProfileItem.THRID);
                fp.add(GmailFolder.FetchProfileItem.LABELS);
                allMail.fetch(hits, fp);
                List<EmailMessageDetail> details = new ArrayList<>(hits.length);
                String subject = null;
                for (Message raw : hits) {
                    if (raw instanceof GmailMessage gm) {
                        EmailMessageDetail d = toMessageDetail(gm);
                        details.add(d);
                        if (subject == null && d.subject() != null && !d.subject().isBlank()) {
                            subject = d.subject();
                        }
                    }
                }
                details.sort(Comparator.comparing(EmailMessageDetail::receivedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())));
                // linkedRefs computed by EmailService via LinkDetector after
                // this returns — keeping that single source of truth means
                // the IMAP path doesn't need its own copy of the regex.
                return new EmailThreadDetail(threadId, subject == null ? "" : subject,
                        List.copyOf(details), List.of());
            }
            finally {
                allMail.close(false);
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
        catch (MessagingException | IOException e) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(502),
                    "Gmail IMAP error: " + e.getMessage(), e);
        }
    }

    private static EmailMessageDetail toMessageDetail(GmailMessage m)
            throws MessagingException, IOException
    {
        BodyAccumulator body = new BodyAccumulator();
        collectBody(m, body);
        Instant received = m.getReceivedDate() == null
                ? (m.getSentDate() == null ? null : m.getSentDate().toInstant())
                : m.getReceivedDate().toInstant();
        boolean unread = !m.isSet(Flags.Flag.SEEN);
        // Mirror the OAuth path: include "UNREAD" as a synthetic label
        // so downstream code (EmailHtmlEnricher etc.) doesn't have to
        // care which backend filled the row.
        List<String> labels = new ArrayList<>();
        String[] gmailLabels = m.getLabels();
        if (gmailLabels != null) {
            labels.addAll(Arrays.asList(gmailLabels));
        }
        if (unread) {
            labels.add("UNREAD");
        }
        return new EmailMessageDetail(
                Long.toUnsignedString(m.getMsgId()),
                Long.toUnsignedString(m.getThrId()),
                formatFrom(m),
                joinAddresses(m.getRecipients(Message.RecipientType.TO)),
                joinAddresses(m.getRecipients(Message.RecipientType.CC)),
                m.getSubject() == null ? "" : m.getSubject(),
                received,
                unread,
                List.copyOf(labels),
                body.text,
                body.html);
    }

    /**
     * Walks the MIME tree depth-first, collecting the first text/plain
     * and the first text/html. Stops walking once both are filled —
     * multipart/alternative emails carry both, older plain-text emails
     * carry just text/plain.
     */
    private static void collectBody(Part part, BodyAccumulator acc)
            throws MessagingException, IOException
    {
        if (acc.text != null && acc.html != null) {
            return;
        }
        Object content;
        try {
            content = part.getContent();
        }
        catch (IOException e) {
            // Malformed MIME parts surface as IOException out of getContent;
            // treat as empty rather than failing the whole thread fetch.
            return;
        }
        if (part.isMimeType("text/plain") && acc.text == null) {
            acc.text = stringify(content);
        }
        else if (part.isMimeType("text/html") && acc.html == null) {
            acc.html = stringify(content);
        }
        else if (content instanceof MimeMultipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                if (mp.getBodyPart(i) instanceof MimeBodyPart bp) {
                    collectBody(bp, acc);
                }
            }
        }
    }

    private static String stringify(Object content)
    {
        if (content instanceof String s) {
            return s;
        }
        // Some servers return InputStream for text/* despite the
        // Content-Type — Angus normally handles this, but guard anyway.
        return content == null ? "" : content.toString();
    }

    private static String joinAddresses(Address[] addrs)
    {
        if (addrs == null || addrs.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < addrs.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(addrs[i].toString());
        }
        return sb.toString();
    }

    private static final class BodyAccumulator
    {
        String text;
        String html;
    }

    /**
     * Reads the named RFC 822 headers from a single message identified
     * by its X-GM-MSGID. Used by {@code EmailService.sendReply} to grab
     * the original {@code Message-ID} + {@code References} so the
     * outgoing reply threads correctly.
     *
     * <p>Returns the first value per header (RFC 822 allows duplicates;
     * Gmail rarely emits them and the caller only cares about the first).
     * Missing headers are simply absent from the map — callers default
     * via {@link Map#getOrDefault}.
     */
    public Map<String, String> getMessageHeaders(
            String email, String appPassword, String messageId, List<String> headerNames)
    {
        if (headerNames == null || headerNames.isEmpty()) {
            return Map.of();
        }
        long msgId;
        try {
            msgId = Long.parseUnsignedLong(messageId);
        }
        catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "messageId is not a Gmail message id: " + messageId);
        }
        Session session = Session.getInstance(properties());
        try (Store store = session.getStore(STORE_PROTOCOL)) {
            connect(store, email, appPassword);
            Folder allMail = store.getFolder(ALL_MAIL_FOLDER);
            allMail.open(Folder.READ_ONLY);
            try {
                Message[] hits = allMail.search(new GmailMsgIdTerm(msgId));
                if (hits.length == 0) {
                    throw new ResponseStatusException(HttpStatusCode.valueOf(404),
                            "message " + messageId + " not found in " + ALL_MAIL_FOLDER);
                }
                Message m = hits[0];
                Map<String, String> out = new LinkedHashMap<>();
                for (String name : headerNames) {
                    String[] vals = m.getHeader(name);
                    if (vals != null && vals.length > 0 && vals[0] != null) {
                        out.put(name, vals[0]);
                    }
                }
                return out;
            }
            finally {
                allMail.close(false);
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

    /** Sets the {@code \Seen} flag on every message in the thread. */
    public void markThreadRead(String email, String appPassword, String threadId)
    {
        mutateThread(email, appPassword, threadId, (folder, msgs) ->
                folder.setFlags(msgs, new Flags(Flags.Flag.SEEN), true));
    }

    /** Clears {@code \Seen} on every message in the thread. */
    public void markThreadUnread(String email, String appPassword, String threadId)
    {
        mutateThread(email, appPassword, threadId, (folder, msgs) ->
                folder.setFlags(msgs, new Flags(Flags.Flag.SEEN), false));
    }

    /** Removes the Gmail INBOX label from every message — Gmail's
     *  "archive" semantics. The messages stay in All Mail and remain
     *  reachable by thread-id search; only the INBOX listing changes. */
    public void archiveThread(String email, String appPassword, String threadId)
    {
        mutateThread(email, appPassword, threadId, (folder, msgs) ->
                folder.setLabels(msgs, INBOX_LABEL, false));
    }

    /** Single-round-trip "open and dismiss" — sets {@code \Seen} and
     *  drops INBOX in one STORE batch per message. Mirrors the OAuth
     *  path's {@code modifyThread(addInbox=[], remove=[INBOX, UNREAD])}. */
    public void readAndArchiveThread(String email, String appPassword, String threadId)
    {
        mutateThread(email, appPassword, threadId, (folder, msgs) -> {
            folder.setFlags(msgs, new Flags(Flags.Flag.SEEN), true);
            folder.setLabels(msgs, INBOX_LABEL, false);
        });
    }

    /** Inverse of archive: re-adds the INBOX label and clears unread,
     *  matching the OAuth "Keep in inbox" semantics (re-adds INBOX,
     *  removes UNREAD — the user just opened it, so leave it read). */
    public void keepThreadInInbox(String email, String appPassword, String threadId)
    {
        mutateThread(email, appPassword, threadId, (folder, msgs) -> {
            folder.setLabels(msgs, INBOX_LABEL, true);
            folder.setFlags(msgs, new Flags(Flags.Flag.SEEN), true);
        });
    }

    /** Gmail's INBOX is a system label exposed over IMAP X-GM-LABELS as
     *  {@code \Inbox} (note the leading backslash — that's how the
     *  protocol distinguishes built-in labels from user-defined ones).
     *  Defined once so the call sites read cleanly and we don't fight
     *  Java string-escape noise inline. */
    private static final String[] INBOX_LABEL = {"\\Inbox"};

    /**
     * Connects, opens All Mail read-write, runs an X-GM-THRID search,
     * and hands the resulting GmailFolder + Message[] to the caller.
     * All Mail is the right folder regardless of the operation: it
     * contains every message in the account so already-archived threads
     * are reachable for "Keep in inbox", and adding/removing labels
     * applies whether the thread is currently in INBOX or not.
     *
     * <p>Closes the folder with {@code expunge=false} — none of these
     * mutations set {@code \Deleted}, so an expunge would only flush
     * unrelated pending deletes that other clients might have queued.
     */
    private void mutateThread(String email, String appPassword, String threadId, ThreadMutator mutator)
    {
        long thrId;
        try {
            thrId = Long.parseUnsignedLong(threadId);
        }
        catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "threadId is not a Gmail thread id: " + threadId);
        }
        Session session = Session.getInstance(properties());
        try (Store store = session.getStore(STORE_PROTOCOL)) {
            connect(store, email, appPassword);
            Folder allMail = store.getFolder(ALL_MAIL_FOLDER);
            allMail.open(Folder.READ_WRITE);
            try {
                Message[] hits = allMail.search(new GmailThrIdTerm(thrId));
                if (hits.length == 0) {
                    throw new ResponseStatusException(HttpStatusCode.valueOf(404),
                            "thread " + threadId + " not found in " + ALL_MAIL_FOLDER);
                }
                if (!(allMail instanceof GmailFolder gf)) {
                    // Defensive: gimaps always returns a GmailFolder, but cast
                    // safely so a provider misconfig surfaces as 500 not CCE.
                    throw new ResponseStatusException(HttpStatusCode.valueOf(500),
                            "Expected GmailFolder for All Mail, got " + allMail.getClass().getName());
                }
                mutator.apply(gf, hits);
            }
            finally {
                allMail.close(false);
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

    @FunctionalInterface
    private interface ThreadMutator
    {
        void apply(GmailFolder folder, Message[] messages)
                throws MessagingException;
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
