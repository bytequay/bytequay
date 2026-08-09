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

import com.bytequay.app.repository.sqlite.EmailMutedSenderStore;
import com.google.common.collect.ImmutableSet;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Per-account list of sender addresses whose threads get filtered out
 * of the inbox view. Local-mode only — the mute list lives in our
 * SQLite, never syncs to Gmail's filters. Sender match is by lower-
 * cased {@code addr@host}; display names and angle brackets are
 * stripped before storage and lookup so {@code "Display <a@b>"} and
 * {@code "a@b"} mute equivalently.
 */
@Service
public class EmailMuteService
{
    private final EmailMutedSenderStore store;

    public EmailMuteService(EmailMutedSenderStore store)
    {
        this.store = requireNonNull(store, "store is null");
    }

    /** Adds a mute. Idempotent — re-muting the same sender just refreshes
     *  the timestamp. */
    public void mute(String accountEmail, String rawFrom)
    {
        String normalised = normaliseSender(rawFrom);
        if (normalised.isEmpty()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "could not extract a sender address from: " + rawFrom);
        }
        store.mute(accountEmail, normalised, Instant.now());
    }

    public void unmute(String accountEmail, String rawFrom)
    {
        String normalised = normaliseSender(rawFrom);
        if (normalised.isEmpty()) {
            return;
        }
        store.unmute(accountEmail, normalised);
    }

    /** All sender addresses currently muted for this account, sorted by
     *  the address for stable rendering in the (future) Settings page. */
    public List<String> listMuted(String accountEmail)
    {
        return store.listMuted(accountEmail).stream()
                .sorted()
                .toList();
    }

    /** Lookup for the inbox filter — returns the full muted set in one
     *  query so the per-thread check is an in-memory hash lookup. */
    public Set<String> mutedSet(String accountEmail)
    {
        return ImmutableSet.copyOf(store.listMuted(accountEmail));
    }

    /**
     * Extracts the address part of an RFC 5322 {@code From:} header
     * and lowercases it for comparison. Examples:
     * <pre>
     *   "Display Name &lt;addr@host&gt;"  → "addr@host"
     *   "addr@host"                     → "addr@host"
     *   "ADDR@HOST"                     → "addr@host"
     *   null or blank                   → ""
     * </pre>
     * Visible to the test so we can pin the parser edge cases.
     */
    static String normaliseSender(String raw)
    {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        int open = trimmed.lastIndexOf('<');
        int close = trimmed.lastIndexOf('>');
        if (open >= 0 && close > open) {
            return trimmed.substring(open + 1, close).trim().toLowerCase(Locale.ROOT);
        }
        // No angle brackets — assume the whole thing is the address.
        // Strip a leading display name if there's a leftover quote pair
        // ahead of an @, since some clients emit `"Name" addr@host`.
        int at = trimmed.indexOf('@');
        if (at < 0) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
        int start = 0;
        for (int i = at; i >= 0; i--) {
            char c = trimmed.charAt(i);
            if (Character.isWhitespace(c) || c == '"' || c == '\'') {
                start = i + 1;
                break;
            }
        }
        int end = trimmed.length();
        for (int i = at; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isWhitespace(c) || c == '"' || c == '\'') {
                end = i;
                break;
            }
        }
        return trimmed.substring(start, end).toLowerCase(Locale.ROOT);
    }
}
