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
import com.bytequay.app.repository.EmailMessageStore;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.web.PatResolver;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

/**
 * Inbox-list orchestrator. Resolves an access token for the requested
 * account, asks Gmail for the inbox thread IDs, fetches metadata for
 * each in parallel, and returns the threads newest-first.
 *
 * <p>One round trip costs {@code 1 + N} HTTP calls to Gmail, capped at
 * {@link #MAX_PARALLEL_FETCHES} concurrency to stay under Gmail's
 * per-user concurrent-request limit.
 *
 * <p>Operates on Gmail's <strong>thread</strong> abstraction, not
 * individual messages — matches Gmail's UI semantics where a row in
 * the inbox is a conversation, archive applies to the whole thread,
 * and the unread state is "any message in the thread is unread".
 *
 * <p>OAuth-only path. The IMAP adapter for
 * {@code (ACCOUNT, "gmail-imap", *)} accounts plugs in alongside once
 * the UI shape is settled.
 */
@Service
public class EmailService
{
    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    /** Dedup window for the email-triggered PR detail refresh — if the
     *  user opens the same email thread twice in this many minutes, we
     *  don't kick off a redundant refresh. */
    private static final Duration PR_REFRESH_DEDUP_TTL = Duration.ofMinutes(5);

    private final GoogleAccessTokenService tokens;
    private final GmailApiClient gmail;
    private final GmailImapAuthService imapAuth;
    private final GmailImapClient imapClient;
    private final LinkDetector linkDetector;
    private final EmailHtmlEnricher htmlEnricher;
    private final EmailMessageStore store;
    private final EmailSyncService sync;
    private final EmailMuteService muteService;
    private final PullRequestService pullRequestService;
    private final PatResolver patResolver;
    /** "owner/repo#number" → last-refresh-time, dedup for the email
     *  -triggered PR refresh. */
    private final ConcurrentMap<String, Instant> recentPrRefreshes = new ConcurrentHashMap<>();

    public EmailService(
            GoogleAccessTokenService tokens,
            GmailApiClient gmail,
            GmailImapAuthService imapAuth,
            GmailImapClient imapClient,
            LinkDetector linkDetector,
            EmailHtmlEnricher htmlEnricher,
            EmailMessageStore store,
            EmailSyncService sync,
            EmailMuteService muteService,
            PullRequestService pullRequestService,
            PatResolver patResolver)
    {
        this.tokens = requireNonNull(tokens, "tokens is null");
        this.gmail = requireNonNull(gmail, "gmail is null");
        this.imapAuth = requireNonNull(imapAuth, "imapAuth is null");
        this.imapClient = requireNonNull(imapClient, "imapClient is null");
        this.linkDetector = requireNonNull(linkDetector, "linkDetector is null");
        this.htmlEnricher = requireNonNull(htmlEnricher, "htmlEnricher is null");
        this.store = requireNonNull(store, "store is null");
        this.sync = requireNonNull(sync, "sync is null");
        this.muteService = requireNonNull(muteService, "muteService is null");
        this.pullRequestService = requireNonNull(pullRequestService, "pullRequestService is null");
        this.patResolver = requireNonNull(patResolver, "patResolver is null");
    }

    /**
     * Returns the inbox grouped by thread, newest first.
     *
     * <p>OAuth path: served from the local SQLite mirror. Cold accounts
     * trigger a synchronous initial sync; subsequent calls hit cached
     * data, with fresh rows flowing in via the background poll tick or
     * an explicit {@link #refresh refresh}.
     *
     * <p>IMAP path: live fetch from {@code imap.gmail.com} on every
     * call — no SQLite mirror yet. Tolerable while the page poll only
     * fires every 30s; we'll add an IMAP cache layer once the read/
     * archive mutations land and the poll cost actually matters.
     */
    public List<EmailThreadMeta> listInboxThreads(String email, int pageSize)
    {
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "email must not be blank");
        }
        if (pageSize <= 0 || pageSize > 500) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "pageSize must be in [1, 500]");
        }
        if (imapAuth.isConnected(email)) {
            return applyMuteFilter(email,
                    imapClient.listInboxThreads(email, imapAuth.getAppPassword(email), pageSize));
        }
        // Cold-start the cache for accounts we've never synced.
        if (store.getSyncState(email).isEmpty()) {
            log.info("Cold cache for {} — running initial Gmail sync", email);
            sync.fullSync(email);
        }
        return applyMuteFilter(email, store.listInboxThreads(email, pageSize));
    }

    /** Force-refresh handler — for OAuth runs an incremental sync and
     *  returns the resulting cached inbox; for IMAP this is the same
     *  as {@link #listInboxThreads} since there's no cache to bust yet. */
    public List<EmailThreadMeta> refresh(String email, int pageSize)
    {
        requireNonBlank(email, "email");
        if (imapAuth.isConnected(email)) {
            return applyMuteFilter(email,
                    imapClient.listInboxThreads(email, imapAuth.getAppPassword(email), pageSize));
        }
        sync.incrementalSync(email);
        return applyMuteFilter(email, store.listInboxThreads(email, pageSize));
    }

    /** Drops any thread whose latest-message sender is in the mute set
     *  for this account. Done in-memory after the SQL list query — the
     *  mute set is typically tiny so the extra round trip vs a JOIN is
     *  not worth the query complexity. */
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
        EmailThreadDetail raw = imapAuth.isConnected(email)
                ? imapClient.getThreadFull(email, imapAuth.getAppPassword(email), threadId)
                : runWithToken(email, accessToken -> gmail.getThreadFull(accessToken, threadId));
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

    /** Removes the INBOX label from every message in the thread —
     *  Gmail's archive semantics. Cache write-through flips the local
     *  rows off-inbox so the list view drops the row immediately. */
    public void archiveThread(String email, String threadId)
    {
        requireNonBlank(email, "email");
        requireNonBlank(threadId, "threadId");
        if (imapAuth.isConnected(email)) {
            imapClient.archiveThread(email, imapAuth.getAppPassword(email), threadId);
            return;
        }
        runWithToken(email, accessToken -> {
            gmail.modifyThread(accessToken, threadId, ImmutableList.of(), ImmutableList.of("INBOX"));
            return null;
        });
        sync.onThreadModified(email, threadId, false, false);
    }

    /** Removes the UNREAD label from every message in the thread.
     *  Operating on the thread (not individual messages) keeps
     *  ByteQuay's read-state in lockstep with what Gmail's UI shows. */
    public void markThreadRead(String email, String threadId)
    {
        requireNonBlank(email, "email");
        requireNonBlank(threadId, "threadId");
        if (imapAuth.isConnected(email)) {
            imapClient.markThreadRead(email, imapAuth.getAppPassword(email), threadId);
            return;
        }
        runWithToken(email, accessToken -> {
            gmail.modifyThread(accessToken, threadId, ImmutableList.of(), ImmutableList.of("UNREAD"));
            return null;
        });
        sync.onThreadModified(email, threadId, true, false);
    }

    /** Adds the UNREAD label back to every message in the thread. */
    public void markThreadUnread(String email, String threadId)
    {
        requireNonBlank(email, "email");
        requireNonBlank(threadId, "threadId");
        if (imapAuth.isConnected(email)) {
            imapClient.markThreadUnread(email, imapAuth.getAppPassword(email), threadId);
            return;
        }
        runWithToken(email, accessToken -> {
            gmail.modifyThread(accessToken, threadId, ImmutableList.of("UNREAD"), ImmutableList.of());
            return null;
        });
        sync.onThreadModified(email, threadId, true, true);
    }

    /** Combined "open and dismiss" — removes both INBOX and UNREAD in
     *  a single Gmail call. Wired to the auto-action that fires when
     *  the user opens an unread thread, so reading is the same gesture
     *  as archiving. Saves a round trip versus calling
     *  {@link #archiveThread} + {@link #markThreadRead} separately. */
    public void readAndArchiveThread(String email, String threadId)
    {
        requireNonBlank(email, "email");
        requireNonBlank(threadId, "threadId");
        if (imapAuth.isConnected(email)) {
            imapClient.readAndArchiveThread(email, imapAuth.getAppPassword(email), threadId);
            return;
        }
        runWithToken(email, accessToken -> {
            gmail.modifyThread(accessToken, threadId,
                    ImmutableList.of(), ImmutableList.of("INBOX", "UNREAD"));
            return null;
        });
        sync.onThreadModified(email, threadId, false, false);
    }

    /**
     * Sends a plain-text reply to the latest message in a thread.
     * Pulls the original {@code Message-ID} + {@code References} so
     * Gmail (and every other RFC-compliant client) keeps the reply
     * threaded with the conversation. Subject gets a {@code Re:}
     * prefix only when not already present.
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
        rejectIfImap(email, "reply to thread");
        runWithToken(email, accessToken -> {
            EmailThreadDetail thread = gmail.getThreadFull(accessToken, threadId);
            if (thread.messages().isEmpty()) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(404),
                        "thread is empty");
            }
            EmailMessageDetail last = thread.messages().get(thread.messages().size() - 1);
            Map<String, String> headers = gmail.getMessageHeaders(accessToken, last.id(),
                    List.of("Message-ID", "References", "Subject", "From"));
            String origMessageId = headers.get("Message-ID");
            String origReferences = headers.get("References");
            String origSubject = headers.getOrDefault("Subject",
                    thread.subject() == null ? "" : thread.subject());
            String origFrom = headers.getOrDefault("From", last.from());

            String replySubject = origSubject.toLowerCase(Locale.ROOT).startsWith("re:")
                    ? origSubject
                    : "Re: " + origSubject;
            String references = origReferences != null && !origReferences.isBlank()
                    ? origReferences + " " + (origMessageId != null ? origMessageId : "")
                    : (origMessageId != null ? origMessageId : "");

            String quoted = quoteForReply(last);
            String fullBody = quoted.isEmpty() ? body : body + "\n\n" + quoted;
            String mime = buildPlainTextMime(origFrom, replySubject, origMessageId, references, fullBody);
            String b64 = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mime.getBytes(StandardCharsets.UTF_8));
            gmail.sendMessage(accessToken, threadId, b64);
            return null;
        });
        // Pull the sent message into the local mirror so the new
        // bottom message shows up the next time the user opens the
        // thread (or via the inbox poll).
        try {
            sync.incrementalSync(email);
        }
        catch (Exception e) {
            log.debug("Post-reply sync skipped: {}", e.getMessage());
        }
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

    /** Builds an RFC5322 text/plain MIME message. CRLF throughout per
     *  spec — anything else gets normalized at the boundary. */
    private static String buildPlainTextMime(
            String to, String subject, String inReplyTo, String references, String body)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("To: ").append(to).append("\r\n");
        sb.append("Subject: ").append(subject).append("\r\n");
        if (inReplyTo != null && !inReplyTo.isBlank()) {
            sb.append("In-Reply-To: ").append(inReplyTo).append("\r\n");
        }
        if (references != null && !references.isBlank()) {
            sb.append("References: ").append(references).append("\r\n");
        }
        sb.append("MIME-Version: 1.0\r\n");
        sb.append("Content-Type: text/plain; charset=UTF-8\r\n");
        sb.append("Content-Transfer-Encoding: 8bit\r\n");
        sb.append("\r\n");
        // Normalize bare LFs to CRLF so the on-the-wire message is
        // strictly RFC-compliant. Bare CRs (rare) get CRLF too.
        sb.append(body.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\r\n"));
        return sb.toString();
    }

    /** Reverses the auto-archive: re-adds INBOX (and clears UNREAD if
     *  set, since the typical entry point is "I just opened this and
     *  want to keep it visible"). The "Keep in inbox" button on the
     *  detail pane drives this. */
    public void keepThreadInInbox(String email, String threadId)
    {
        requireNonBlank(email, "email");
        requireNonBlank(threadId, "threadId");
        if (imapAuth.isConnected(email)) {
            imapClient.keepThreadInInbox(email, imapAuth.getAppPassword(email), threadId);
            return;
        }
        runWithToken(email, accessToken -> {
            gmail.modifyThread(accessToken, threadId,
                    ImmutableList.of("INBOX"), ImmutableList.of("UNREAD"));
            return null;
        });
        sync.onThreadModified(email, threadId, true, false);
    }

    /**
     * Resolves an access token, runs {@code call}, and on a 401 from
     * Gmail invalidates the cached token so the next attempt refreshes.
     * We don't auto-retry — a genuinely-revoked refresh token would
     * loop forever and the user needs to reconnect anyway.
     */
    private <T> T runWithToken(String email, Function<String, T> call)
    {
        String accessToken = tokens.getAccessToken(email);
        try {
            return call.apply(accessToken);
        }
        catch (ResponseStatusException e) {
            if (e.getStatusCode().value() == 401) {
                tokens.invalidate(email);
            }
            throw e;
        }
    }

    private static void requireNonBlank(String value, String fieldName)
    {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    fieldName + " must not be blank");
        }
    }

    /** IMAP backend currently only supports listing the inbox. Every
     *  other operation (open thread, archive, mark read/unread, reply,
     *  keep-in-inbox) lands here until the matching IMAP slice ships,
     *  so the UI gets a clear 501 instead of a confusing OAuth error. */
    private void rejectIfImap(String email, String operation)
    {
        if (imapAuth.isConnected(email)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(501),
                    "IMAP backend doesn't support " + operation + " yet — coming in a later slice");
        }
    }
}
