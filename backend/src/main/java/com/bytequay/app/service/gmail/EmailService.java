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
import com.bytequay.app.domain.LinkedRef;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.web.PatResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static java.util.Objects.requireNonNull;

/**
 * Inbox-list orchestrator for Gmail accounts connected via IMAP +
 * app password. Reads come from {@link GmailImapClient}, sends from
 * {@link GmailSmtpClient}. The OAuth/Gmail-API path was removed — see
 * the 2026-05-14 commit — because for a local-only desktop app the
 * Cloud Console / verification dance was a worse experience than just
 * pasting an app password.
 *
 * <p>Operates on Gmail's <strong>thread</strong> abstraction — a row
 * in the inbox is a conversation, archive applies to the whole thread,
 * and unread = "any message in the thread is unread". This matches
 * what the Gmail web UI shows.
 */
@Service
public class EmailService
{
    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    /** Dedup window for the email-triggered PR detail refresh — if the
     *  user opens the same email thread twice in this many minutes, we
     *  don't kick off a redundant refresh. */
    private static final Duration PR_REFRESH_DEDUP_TTL = Duration.ofMinutes(5);

    private final GmailImapAuthService imapAuth;
    private final GmailImapClient imapClient;
    private final GmailSmtpClient smtpClient;
    private final LinkDetector linkDetector;
    private final EmailHtmlEnricher htmlEnricher;
    private final EmailMuteService muteService;
    private final EmailTagService tagService;
    private final PullRequestService pullRequestService;
    private final PatResolver patResolver;
    /** "owner/repo#number" → last-refresh-time, dedup for the email
     *  -triggered PR refresh. */
    private final ConcurrentMap<String, Instant> recentPrRefreshes = new ConcurrentHashMap<>();

    public EmailService(
            GmailImapAuthService imapAuth,
            GmailImapClient imapClient,
            GmailSmtpClient smtpClient,
            LinkDetector linkDetector,
            EmailHtmlEnricher htmlEnricher,
            EmailMuteService muteService,
            EmailTagService tagService,
            PullRequestService pullRequestService,
            PatResolver patResolver)
    {
        this.imapAuth = requireNonNull(imapAuth, "imapAuth is null");
        this.imapClient = requireNonNull(imapClient, "imapClient is null");
        this.smtpClient = requireNonNull(smtpClient, "smtpClient is null");
        this.linkDetector = requireNonNull(linkDetector, "linkDetector is null");
        this.htmlEnricher = requireNonNull(htmlEnricher, "htmlEnricher is null");
        this.muteService = requireNonNull(muteService, "muteService is null");
        this.tagService = requireNonNull(tagService, "tagService is null");
        this.pullRequestService = requireNonNull(pullRequestService, "pullRequestService is null");
        this.patResolver = requireNonNull(patResolver, "patResolver is null");
    }

    /**
     * Returns the inbox grouped by thread, newest first. Live fetch
     * from {@code imap.gmail.com} on every call — there's no SQLite
     * mirror layer, so the cost is one IMAP login per call. Pagination
     * caps at {@code pageSize} threads, ordered by the head message's
     * arrival time.
     */
    public List<EmailThreadMeta> listInboxThreads(String email, int pageSize)
    {
        requireNonBlank(email, "email");
        if (pageSize <= 0 || pageSize > 500) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "pageSize must be in [1, 500]");
        }
        String appPassword = appPasswordFor(email);
        List<EmailThreadMeta> raw = imapClient.listInboxThreads(email, appPassword, pageSize);
        List<EmailThreadMeta> muted = applyMuteFilter(email, raw);
        List<EmailThreadMeta> classified = tagService.classify(email, muted);
        return archiveSweep(email, appPassword, classified);
    }

    /** Force-refresh handler. Same as {@link #listInboxThreads} —
     *  there's no cached data to bust, every call hits IMAP live.
     *  Kept as a separate endpoint so the UI can wire a Refresh button
     *  to "give me the absolute latest" without opening the question
     *  of whether listInboxThreads might be returning stale rows. */
    public List<EmailThreadMeta> refresh(String email, int pageSize)
    {
        return listInboxThreads(email, pageSize);
    }

    /** Drops any thread whose latest-message sender is in the mute set
     *  for this account. Done in-memory after the listing call — the
     *  mute set is typically tiny so a SQL JOIN isn't worth the
     *  query complexity. */
    private List<EmailThreadMeta> applyMuteFilter(String accountEmail, List<EmailThreadMeta> threads)
    {
        Set<String> muted = muteService.mutedSet(accountEmail);
        if (muted.isEmpty()) {
            return threads;
        }
        return threads.stream()
                .filter(t -> !muted.contains(EmailMuteService.normaliseSender(t.from())))
                .toList();
    }

    /**
     * For every thread classified as {@link EmailThreadMeta.View#ARCHIVE}:
     * issues the Gmail-side archive, persists an audit row in the tag
     * archive log, and drops the thread from the returned list. Other
     * views (INBOX, FOCUS, IGNORE) pass through unchanged so the
     * frontend can render the left-nav counts.
     *
     * <p>If the IMAP archive call throws for an individual thread we log
     * and keep going — the next refresh will retry, and a single sick
     * thread mustn't take down the whole listing.
     */
    private List<EmailThreadMeta> archiveSweep(String accountEmail, String appPassword, List<EmailThreadMeta> threads)
    {
        List<EmailThreadMeta> out = new ArrayList<>(threads.size());
        Instant archivedAt = Instant.now();
        for (EmailThreadMeta t : threads) {
            if (t.view() != EmailThreadMeta.View.ARCHIVE) {
                out.add(t);
                continue;
            }
            try {
                imapClient.archiveThread(accountEmail, appPassword, t.id());
                tagService.logArchive(accountEmail, t, t.matchedTagId(), archivedAt);
            }
            catch (Exception e) {
                log.warn("Tag-driven archive failed for thread {} on {}: {}", t.id(), accountEmail, e.getMessage());
                // Pass it through so the user still sees it; next refresh will retry.
                out.add(t);
            }
        }
        return List.copyOf(out);
    }

    /** Full thread including every message, parsed body, and any
     *  PR/issue/commit refs the LinkDetector found inside the bodies.
     *
     *  <p>As a side effect, fires a background PullRequest detail
     *  refresh for any detected PR refs — so when the user clicks
     *  "Open PR →" from the panel, the diff already reflects the
     *  commit/comments the email is notifying them about. Deduped on
     *  a {@link #PR_REFRESH_DEDUP_TTL} window so reopening the same
     *  thread twice doesn't fire twice. */
    public EmailThreadDetail getThread(String email, String threadId)
    {
        requireNonBlank(email, "email");
        requireNonBlank(threadId, "threadId");
        EmailThreadDetail raw = imapClient.getThreadFull(email, appPasswordFor(email), threadId);
        List<LinkedRef> refs = linkDetector.detect(raw);
        for (LinkedRef ref : refs) {
            if (ref.kind() == LinkedRef.Kind.PR) {
                triggerPrRefresh(ref);
            }
        }
        List<EmailMessageDetail> enriched = new ArrayList<>(raw.messages().size());
        for (EmailMessageDetail msg : raw.messages()) {
            enriched.add(new EmailMessageDetail(
                    msg.id(), msg.threadId(), msg.from(), msg.to(), msg.cc(),
                    msg.subject(), msg.receivedAt(), msg.unread(), msg.labels(),
                    msg.bodyText(), htmlEnricher.enrich(msg.bodyHtml(), refs)));
        }
        return new EmailThreadDetail(raw.id(), raw.subject(), List.copyOf(enriched), refs);
    }

    /** Fire-and-forget. Resolves the PAT for the repo (which may not
     *  even be in watched_repos — refreshPullRequestDetail still
     *  works for arbitrary owner/repo via the user's PAT) and runs
     *  the existing detail refresh on a background thread. Errors
     *  are swallowed since they're not user-actionable. */
    @SuppressWarnings("FutureReturnValueIgnored")
    private void triggerPrRefresh(LinkedRef ref)
    {
        int number;
        try {
            number = Integer.parseInt(ref.slug());
        }
        catch (NumberFormatException e) {
            return;
        }
        String key = ref.owner() + "/" + ref.repo() + "#" + number;
        Instant now = Instant.now();
        Instant prev = recentPrRefreshes.get(key);
        if (prev != null && prev.plus(PR_REFRESH_DEDUP_TTL).isAfter(now)) {
            return;
        }
        recentPrRefreshes.put(key, now);
        // Cleanup stale entries opportunistically so the map doesn't
        // grow without bound.
        if (recentPrRefreshes.size() > 256) {
            recentPrRefreshes.entrySet().removeIf(
                    e -> e.getValue().plus(PR_REFRESH_DEDUP_TTL).isBefore(now));
        }
        String repoFull = ref.owner() + "/" + ref.repo();
        CompletableFuture.runAsync(() -> {
            try {
                String pat = patResolver.resolve(repoFull);
                pullRequestService.refreshPullRequestDetail(pat, repoFull, number);
                log.debug("Email-triggered PR refresh ok: {}", key);
            }
            catch (Exception e) {
                log.debug("Email-triggered PR refresh failed for {}: {}", key, e.getMessage());
            }
        });
    }

    /** Removes the Gmail INBOX label from every message in the thread —
     *  Gmail's archive semantics. Also records a row in the tag archive
     *  log (with {@code tagId = null}) so the Archived left-nav view
     *  surfaces manual archives, not just tag-driven ones. */
    public void archiveThread(String email, String threadId)
    {
        requireNonBlank(email, "email");
        requireNonBlank(threadId, "threadId");
        String appPassword = appPasswordFor(email);
        imapClient.archiveThread(email, appPassword, threadId);
        logManualArchive(email, appPassword, threadId);
    }

    /** Sets {@code \Seen} on every message in the thread. */
    public void markThreadRead(String email, String threadId)
    {
        requireNonBlank(email, "email");
        requireNonBlank(threadId, "threadId");
        imapClient.markThreadRead(email, appPasswordFor(email), threadId);
    }

    /** Clears {@code \Seen} on every message in the thread. */
    public void markThreadUnread(String email, String threadId)
    {
        requireNonBlank(email, "email");
        requireNonBlank(threadId, "threadId");
        imapClient.markThreadUnread(email, appPasswordFor(email), threadId);
    }

    /** Combined "open and dismiss" — one IMAP STORE batch sets {@code \Seen}
     *  and removes the INBOX label. Wired to the auto-action that fires
     *  when the user opens an unread thread, so reading is the same
     *  gesture as archiving. Also records the archive in the log (with
     *  {@code tagId = null}). */
    public void readAndArchiveThread(String email, String threadId)
    {
        requireNonBlank(email, "email");
        requireNonBlank(threadId, "threadId");
        String appPassword = appPasswordFor(email);
        imapClient.readAndArchiveThread(email, appPassword, threadId);
        logManualArchive(email, appPassword, threadId);
    }

    /**
     * Best-effort archive-log write for manually-archived threads. Runs
     * after the IMAP archive succeeded, so failure here only loses the
     * audit row — the thread is still archived on Gmail. We swallow
     * exceptions and log a warning since the user's primary action
     * (archive) has already succeeded; surfacing a 500 here would be
     * confusing.
     */
    private void logManualArchive(String email, String appPassword, String threadId)
    {
        try {
            EmailThreadDetail detail = imapClient.getThreadFull(email, appPassword, threadId);
            EmailMessageDetail head = detail.messages().isEmpty() ? null : detail.messages().get(0);
            String fromAddr = head == null ? "" : (head.from() == null ? "" : head.from());
            Instant receivedAt = head == null || head.receivedAt() == null
                    ? Instant.EPOCH
                    : head.receivedAt();
            String snippet = head == null ? "" : snippetOf(head.bodyText());
            // Reuse EmailThreadMeta to feed logArchive without duplicating the
            // record's shape — receivedAt/messageCount/view fields are inert here.
            EmailThreadMeta meta = new EmailThreadMeta(
                    threadId,
                    head == null ? null : head.id(),
                    fromAddr,
                    detail.subject() == null ? "" : detail.subject(),
                    snippet,
                    receivedAt,
                    false,
                    detail.messages().size(),
                    null,
                    EmailThreadMeta.View.ARCHIVE);
            tagService.logArchive(email, meta, null, Instant.now());
        }
        catch (Exception e) {
            log.warn("Manual archive log failed for thread {} on {}: {}", threadId, email, e.getMessage());
        }
    }

    private static String snippetOf(String bodyText)
    {
        if (bodyText == null) {
            return "";
        }
        String collapsed = bodyText.replaceAll("\\s+", " ").trim();
        return collapsed.length() > 240 ? collapsed.substring(0, 240) : collapsed;
    }

    /** Reverses an archive: re-adds INBOX and clears UNREAD. Drives the
     *  "Keep in inbox" button on the detail pane, including for threads
     *  that landed in the Archived view because a tag's ARCHIVE action
     *  fired. Also drops any matching audit row from the tag archive
     *  log so the Archived view doesn't keep showing a thread the user
     *  pulled back to Inbox. */
    public void keepThreadInInbox(String email, String threadId)
    {
        requireNonBlank(email, "email");
        requireNonBlank(threadId, "threadId");
        imapClient.keepThreadInInbox(email, appPasswordFor(email), threadId);
        tagService.forgetArchived(email, threadId);
    }

    /**
     * Sends a plain-text reply to the latest message in a thread via
     * SMTP. Pulls the original {@code Message-ID} + {@code References}
     * from IMAP first so Gmail (and every other RFC-compliant client)
     * keeps the reply threaded with the conversation. Subject gets a
     * {@code Re:} prefix only when not already present.
     *
     * <p>v1: no Reply-All, no rich text, no attachments. The original
     * message body is appended {@code >}-quoted so the recipient has
     * context without us needing a real rich-text composer.
     */
    public void sendReply(String email, String threadId, String body)
    {
        requireNonBlank(email, "email");
        requireNonBlank(threadId, "threadId");
        if (body == null || body.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "body must not be blank");
        }
        String appPassword = appPasswordFor(email);
        EmailThreadDetail thread = imapClient.getThreadFull(email, appPassword, threadId);
        if (thread.messages().isEmpty()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(404),
                    "thread is empty");
        }
        EmailMessageDetail last = thread.messages().get(thread.messages().size() - 1);
        Map<String, String> headers = imapClient.getMessageHeaders(email, appPassword, last.id(),
                List.of("Message-ID", "References", "Subject", "From"));
        String origMessageId = headers.get("Message-ID");
        String origReferences = headers.get("References");
        String origSubject = headers.getOrDefault("Subject",
                thread.subject() == null ? "" : thread.subject());
        String origFrom = headers.getOrDefault("From", last.from());

        String replySubject = buildReplySubject(origSubject);
        String references = mergeReferences(origReferences, origMessageId);
        String quoted = quoteForReply(last);
        String fullBody = quoted.isEmpty() ? body : body + "\n\n" + quoted;
        smtpClient.sendReply(email, appPassword, origFrom, replySubject,
                origMessageId, references, fullBody);
    }

    /** Resolves the app password for {@code email} or throws 401 if no
     *  IMAP credential is stored. Centralised so every operation gets
     *  the same error shape and so the blank-email guard is the only
     *  thing the call sites need to remember. */
    private String appPasswordFor(String email)
    {
        if (!imapAuth.isConnected(email)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(401),
                    "No IMAP credential stored for " + email + " — connect under Settings → Integrations");
        }
        return imapAuth.getAppPassword(email);
    }

    private static String buildReplySubject(String origSubject)
    {
        if (origSubject == null) {
            return "Re:";
        }
        return origSubject.toLowerCase(Locale.ROOT).startsWith("re:")
                ? origSubject
                : "Re: " + origSubject;
    }

    private static String mergeReferences(String origReferences, String origMessageId)
    {
        if (origReferences != null && !origReferences.isBlank()) {
            return origReferences + " " + (origMessageId != null ? origMessageId : "");
        }
        return origMessageId != null ? origMessageId : "";
    }

    private static String quoteForReply(EmailMessageDetail msg)
    {
        String src = msg.bodyText();
        if (src == null || src.isBlank()) {
            return "";
        }
        String when = msg.receivedAt() == null ? "earlier"
                : DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm")
                        .format(msg.receivedAt().atZone(ZoneId.systemDefault()));
        String header = "On " + when + ", " + msg.from() + " wrote:";
        StringBuilder out = new StringBuilder(header).append("\n");
        for (String line : src.split("\\R", -1)) {
            out.append("> ").append(line).append("\n");
        }
        return out.toString();
    }

    private static void requireNonBlank(String value, String fieldName)
    {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    fieldName + " must not be blank");
        }
    }
}
