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

import com.bytequay.app.domain.EmailThreadDetail;
import com.bytequay.app.domain.EmailThreadMeta;
import com.bytequay.app.repository.EmailMessageStore;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
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

    private final GoogleAccessTokenService tokens;
    private final GmailApiClient gmail;
    private final LinkDetector linkDetector;
    private final EmailMessageStore store;
    private final EmailSyncService sync;

    public EmailService(
            GoogleAccessTokenService tokens,
            GmailApiClient gmail,
            LinkDetector linkDetector,
            EmailMessageStore store,
            EmailSyncService sync)
    {
        this.tokens = requireNonNull(tokens, "tokens is null");
        this.gmail = requireNonNull(gmail, "gmail is null");
        this.linkDetector = requireNonNull(linkDetector, "linkDetector is null");
        this.store = requireNonNull(store, "store is null");
        this.sync = requireNonNull(sync, "sync is null");
    }

    /**
     * Returns the inbox grouped by thread from the local SQLite
     * mirror, newest first. If the cache is empty (first connect),
     * triggers a synchronous full sync first. Otherwise returns
     * whatever's cached — fresh data lands via the background poll
     * tick or an explicit {@link #refresh(String)} call.
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
        // Cold-start the cache for accounts we've never synced.
        if (store.getSyncState(email).isEmpty()) {
            log.info("Cold cache for {} — running initial Gmail sync", email);
            sync.fullSync(email);
        }
        return store.listInboxThreads(email, pageSize);
    }

    /** Force-refresh handler — runs an incremental sync (or full
     *  if the watermark is missing/stale) and returns the resulting
     *  cached inbox. */
    public List<EmailThreadMeta> refresh(String email, int pageSize)
    {
        requireNonBlank(email, "email");
        sync.incrementalSync(email);
        return store.listInboxThreads(email, pageSize);
    }

    /** Full thread including every message, parsed body, and any
     *  PR/issue refs the LinkDetector found inside the bodies. */
    public EmailThreadDetail getThread(String email, String threadId)
    {
        requireNonBlank(email, "email");
        requireNonBlank(threadId, "threadId");
        EmailThreadDetail raw = runWithToken(email,
                accessToken -> gmail.getThreadFull(accessToken, threadId));
        return new EmailThreadDetail(
                raw.id(), raw.subject(), raw.messages(), linkDetector.detect(raw));
    }

    /** Removes the INBOX label from every message in the thread —
     *  Gmail's archive semantics. Cache write-through flips the local
     *  rows off-inbox so the list view drops the row immediately. */
    public void archiveThread(String email, String threadId)
    {
        requireNonBlank(email, "email");
        requireNonBlank(threadId, "threadId");
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
        runWithToken(email, accessToken -> {
            gmail.modifyThread(accessToken, threadId, ImmutableList.of("UNREAD"), ImmutableList.of());
            return null;
        });
        sync.onThreadModified(email, threadId, true, true);
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
}
