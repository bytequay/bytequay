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
import com.bytequay.app.domain.EmailMessageMeta;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * Inbox-list orchestrator. Resolves an access token for the requested
 * account, asks Gmail for the inbox message IDs, fetches metadata for
 * each in parallel, and returns the result newest-first.
 *
 * <p>One round trip costs {@code 1 + N} HTTP calls to Gmail, but the
 * N happen in parallel so wall-clock is dominated by the slowest one
 * (~200–600ms total for 50 messages). Gmail's per-user quota
 * (250 units/sec) leaves plenty of room here; we don't bother
 * batching for slice 1.
 *
 * <p>This is the OAuth-only path. The IMAP adapter (for
 * {@code (ACCOUNT, "gmail-imap", *)} accounts) plugs in alongside
 * once the UI shape is settled.
 */
@Service
public class EmailService
{
    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final GoogleAccessTokenService tokens;
    private final GmailApiClient gmail;

    public EmailService(GoogleAccessTokenService tokens, GmailApiClient gmail)
    {
        this.tokens = requireNonNull(tokens, "tokens is null");
        this.gmail = requireNonNull(gmail, "gmail is null");
    }

    /**
     * Returns the inbox for {@code email}, newest first, capped at
     * {@code pageSize} entries (Gmail's hard ceiling is 500). Throws
     * 401 if the refresh token is missing or has been revoked, 502
     * for upstream Gmail trouble.
     */
    public List<EmailMessageMeta> listInbox(String email, int pageSize)
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
            ids = gmail.listInboxIds(accessToken, pageSize);
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
        log.debug("Inbox for {} has {} messages; fetching metadata in parallel", email, ids.size());
        List<CompletableFuture<EmailMessageMeta>> futures = ids.stream()
                .map(id -> CompletableFuture.supplyAsync(
                        () -> gmail.getMessageMetadata(accessToken, id)))
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
                .sorted(Comparator.comparing(EmailMessageMeta::receivedAt).reversed())
                .collect(Collectors.toUnmodifiableList());
    }

    /** Full message detail including parsed body. */
    public EmailMessageDetail getMessage(String email, String messageId)
    {
        requireNonBlank(email, "email");
        requireNonBlank(messageId, "messageId");
        return runWithToken(email, accessToken -> gmail.getMessageFull(accessToken, messageId));
    }

    /** Removes the INBOX label — Gmail's archive semantics. */
    public void archive(String email, String messageId)
    {
        requireNonBlank(email, "email");
        requireNonBlank(messageId, "messageId");
        runWithToken(email, accessToken -> {
            gmail.modifyMessage(accessToken, messageId, ImmutableList.of(), ImmutableList.of("INBOX"));
            return null;
        });
    }

    /** Removes the UNREAD label — Gmail flips the bold-vs-not. */
    public void markRead(String email, String messageId)
    {
        requireNonBlank(email, "email");
        requireNonBlank(messageId, "messageId");
        runWithToken(email, accessToken -> {
            gmail.modifyMessage(accessToken, messageId, ImmutableList.of(), ImmutableList.of("UNREAD"));
            return null;
        });
    }

    /** Adds the UNREAD label back. */
    public void markUnread(String email, String messageId)
    {
        requireNonBlank(email, "email");
        requireNonBlank(messageId, "messageId");
        runWithToken(email, accessToken -> {
            gmail.modifyMessage(accessToken, messageId, ImmutableList.of("UNREAD"), ImmutableList.of());
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
