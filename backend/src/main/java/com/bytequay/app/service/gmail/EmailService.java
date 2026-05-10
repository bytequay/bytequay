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
import com.google.common.collect.ImmutableList;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    /** Caps parallel metadata fetches. Gmail enforces a per-user
     *  concurrent-request limit (~10) that's separate from the
     *  quota-per-second one — bursts of 50 unbounded calls hit it
     *  fast and the requests come back as 429s. Five gives us
     *  headroom under that ceiling and is empirically still fast. */
    private static final int MAX_PARALLEL_FETCHES = 5;

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final GoogleAccessTokenService tokens;
    private final GmailApiClient gmail;
    private final LinkDetector linkDetector;
    private final ExecutorService executor;

    public EmailService(GoogleAccessTokenService tokens, GmailApiClient gmail, LinkDetector linkDetector)
    {
        this.tokens = requireNonNull(tokens, "tokens is null");
        this.gmail = requireNonNull(gmail, "gmail is null");
        this.linkDetector = requireNonNull(linkDetector, "linkDetector is null");
        this.executor = Executors.newFixedThreadPool(MAX_PARALLEL_FETCHES, r -> {
            Thread t = new Thread(r, "gmail-fetch");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    public void shutdown()
    {
        executor.shutdown();
    }

    /**
     * Returns the inbox for {@code email} grouped by thread, newest
     * first, capped at {@code pageSize} threads (Gmail's hard ceiling
     * is 500). Throws 401 if the refresh token is missing or has been
     * revoked, 502 for upstream Gmail trouble.
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
        String accessToken;
        List<String> ids;
        try {
            accessToken = tokens.getAccessToken(email);
            ids = gmail.listInboxThreadIds(accessToken, pageSize);
        }
        catch (ResponseStatusException e) {
            // 401 from Gmail means the cached access token is stale or
            // the refresh token was revoked. Drop the cache and let
            // the caller retry; we don't auto-retry here because a
            // genuinely-revoked token would loop forever.
            if (e.getStatusCode().value() == 401) {
                tokens.invalidate(email);
            }
            throw e;
        }
        log.debug("Inbox for {} has {} threads; fetching metadata in parallel (cap={})",
                email, ids.size(), MAX_PARALLEL_FETCHES);
        List<CompletableFuture<EmailThreadMeta>> futures = ids.stream()
                .map(id -> CompletableFuture.supplyAsync(
                        () -> gmail.getThreadMetadata(accessToken, id), executor))
                .collect(Collectors.toUnmodifiableList());
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }
        catch (Exception e) {
            // Unwrap CompletionException → underlying ResponseStatusException
            // so the controller surfaces the original status code.
            Throwable cause = e.getCause();
            if (cause instanceof ResponseStatusException rse) {
                if (rse.getStatusCode().value() == 401) {
                    tokens.invalidate(email);
                }
                throw rse;
            }
            throw new ResponseStatusException(HttpStatusCode.valueOf(502),
                    "Gmail metadata fetch failed: " + e.getMessage(), e);
        }
        return futures.stream()
                .map(CompletableFuture::join)
                .sorted(Comparator.comparing(EmailThreadMeta::receivedAt).reversed())
                .collect(Collectors.toUnmodifiableList());
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
     *  Gmail's archive semantics. */
    public void archiveThread(String email, String threadId)
    {
        requireNonBlank(email, "email");
        requireNonBlank(threadId, "threadId");
        runWithToken(email, accessToken -> {
            gmail.modifyThread(accessToken, threadId, ImmutableList.of(), ImmutableList.of("INBOX"));
            return null;
        });
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
