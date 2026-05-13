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

import com.bytequay.app.domain.EmailMessageMeta;
import com.bytequay.app.repository.EmailMessageStore;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * Maintains the local mirror in {@link EmailMessageStore}. Two modes:
 *
 * <ul>
 *   <li><b>Full sync</b> — {@link #fullSync(String)} crawls the whole
 *       inbox via {@code users.threads.list} + parallel
 *       {@code users.threads.get?format=metadata}, replaces the local
 *       cache, then snapshots the current historyId as the watermark.
 *       Runs the first time an account is connected and again when
 *       the incremental watermark expires (Gmail prunes history after
 *       ~7 days).</li>
 *   <li><b>Incremental sync</b> — {@link #incrementalSync(String)}
 *       calls {@code users.history.list?startHistoryId=last} and
 *       patches the rows the events touch (added / deleted /
 *       labels-changed). Cheap; runs every minute via
 *       {@code GmailPollingJob}.</li>
 * </ul>
 *
 * <p>Mutations from the UI (archive, mark-read) update the local row
 * directly via {@link EmailMessageStore#updateLabels} so the optimistic
 * UI doesn't have to wait for the next poll tick to see the change
 * settle.
 */
@Service
public class EmailSyncService
{
    /** Same parallelism cap as the live-fetch path — Gmail's per-user
     *  concurrent-request limit doesn't care which endpoint we're
     *  hitting. */
    private static final int MAX_PARALLEL_FETCHES = 5;

    private static final Logger log = LoggerFactory.getLogger(EmailSyncService.class);

    private final GoogleAccessTokenService tokens;
    private final GmailApiClient gmail;
    private final EmailMessageStore store;
    private final ExecutorService executor;

    public EmailSyncService(
            GoogleAccessTokenService tokens,
            GmailApiClient gmail,
            EmailMessageStore store)
    {
        this.tokens = requireNonNull(tokens, "tokens is null");
        this.gmail = requireNonNull(gmail, "gmail is null");
        this.store = requireNonNull(store, "store is null");
        this.executor = Executors.newFixedThreadPool(MAX_PARALLEL_FETCHES, r -> {
            Thread t = new Thread(r, "gmail-sync");
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
     * Pulls the inbox fresh. Wipes the local cache for the account,
     * rewrites it from {@code users.threads.list}, and seeds the
     * sync watermark. Cheap-ish (1 + ~50 calls); runs on first
     * connect and as a recovery path when the incremental watermark
     * expires.
     */
    public void fullSync(String accountEmail)
    {
        requireNonBlank(accountEmail);
        String accessToken = tokens.getAccessToken(accountEmail);
        // Snapshot historyId BEFORE the crawl so any messages that
        // arrive mid-crawl get picked up by the next incremental.
        String startHistoryId = gmail.getCurrentHistoryId(accessToken);
        List<String> messageIds = gmail.listInboxIds(accessToken, 100);
        log.info("Gmail full sync for {}: {} messages", accountEmail, messageIds.size());
        // Full-sync 404s are silently dropped: we're rewriting the whole
        // cache below anyway, so a missing message just doesn't make it in.
        List<EmailMessageMeta> all = fetchMessageMetas(accessToken, messageIds).ok();
        store.deleteAllForAccount(accountEmail);
        store.upsertAll(accountEmail, all);
        if (startHistoryId != null) {
            store.setSyncState(accountEmail, startHistoryId, System.currentTimeMillis());
        }
    }

    /**
     * Patches the local cache with whatever Gmail's history says
     * happened since the last watermark. No-ops when the watermark
     * is missing — the caller should run {@link #fullSync(String)}
     * first in that case.
     */
    public void incrementalSync(String accountEmail)
    {
        requireNonBlank(accountEmail);
        Optional<EmailMessageStore.SyncState> state = store.getSyncState(accountEmail);
        if (state.isEmpty()) {
            // No watermark yet — caller hasn't done a full sync, do
            // it now so subsequent incrementals have something to
            // chain off of.
            fullSync(accountEmail);
            return;
        }
        String accessToken = tokens.getAccessToken(accountEmail);
        GmailApiClient.HistoryListResult result;
        try {
            result = gmail.listHistorySince(accessToken, state.get().lastHistoryId());
        }
        catch (ResponseStatusException e) {
            if (e.getStatusCode().value() == 410) {
                log.info("Gmail history watermark expired for {} — running full sync", accountEmail);
                fullSync(accountEmail);
                return;
            }
            throw e;
        }
        if (result.events().isEmpty()) {
            store.setSyncState(accountEmail, result.latestHistoryId(), System.currentTimeMillis());
            return;
        }
        // Dedupe message IDs to refetch — a single delta can touch the
        // same message multiple times (added then label-changed, etc.).
        Set<String> toFetch = new HashSet<>();
        Set<String> toDelete = new HashSet<>();
        for (JsonNode ev : result.events()) {
            for (JsonNode m : ev.path("messagesAdded")) {
                String id = m.path("message").path("id").asText(null);
                if (id != null) {
                    toFetch.add(id);
                }
            }
            for (JsonNode m : ev.path("messagesDeleted")) {
                String id = m.path("message").path("id").asText(null);
                if (id != null) {
                    toDelete.add(id);
                }
            }
            for (JsonNode m : ev.path("labelsAdded")) {
                String id = m.path("message").path("id").asText(null);
                if (id != null) {
                    toFetch.add(id);
                }
            }
            for (JsonNode m : ev.path("labelsRemoved")) {
                String id = m.path("message").path("id").asText(null);
                if (id != null) {
                    toFetch.add(id);
                }
            }
        }
        // Process deletes first so a delete-then-re-add (rare) ends
        // up with the right state.
        for (String id : toDelete) {
            store.deleteMessage(accountEmail, id);
            toFetch.remove(id);
        }
        int notFoundCount = 0;
        if (!toFetch.isEmpty()) {
            FetchMetasResult fresh = fetchMessageMetas(accessToken, new ArrayList<>(toFetch));
            // upsertAll always sets in_inbox=true. INBOX-label-removed
            // events for messages we already had won't flip the row
            // off-inbox here; the UI mutation write-through and the
            // next full sync handle that. Acceptable drift for v1.
            store.upsertAll(accountEmail, fresh.ok());
            // Race window: Gmail emitted a messages-added event, then the
            // user permanently deleted the message before our metadata
            // fetch landed. Purge locally so the row doesn't ghost-survive
            // and so subsequent polls can advance the watermark cleanly.
            for (String id : fresh.notFoundIds()) {
                store.deleteMessage(accountEmail, id);
            }
            notFoundCount = fresh.notFoundIds().size();
        }
        store.setSyncState(accountEmail, result.latestHistoryId(), System.currentTimeMillis());
        log.debug("Gmail incremental sync for {}: +{} -{} (404 {}) → {}",
                accountEmail, toFetch.size() - notFoundCount, toDelete.size(),
                notFoundCount, result.latestHistoryId());
    }

    /** Mutation write-through. The UI calls Gmail directly to apply
     *  archive / mark-read on a thread; this updates the local rows
     *  for that thread so the cache reflects the change without
     *  waiting for the next poll tick.
     *
     *  <p>{@code newInInbox} = false for archive, true for everything
     *  else. {@code newUnread} = false for mark-read, true for
     *  mark-unread, unchanged on archive (we still pass the current
     *  value).
     */
    public void onThreadModified(
            String accountEmail,
            String threadId,
            boolean newInInbox,
            boolean newUnread)
    {
        try {
            for (String msgId : messageIdsInLocalThread(accountEmail, threadId)) {
                store.updateLabels(accountEmail, msgId, newInInbox, newUnread);
            }
        }
        catch (Exception e) {
            log.warn("Local cache update for thread {} failed: {}", threadId, e.getMessage());
        }
    }

    /** Find every cached message in a thread without hitting Gmail —
     *  cheaper than refetching the thread, and the next incremental
     *  sync reconciles any drift. */
    private List<String> messageIdsInLocalThread(String accountEmail, String threadId)
    {
        return store.listMessageIdsInThread(accountEmail, threadId);
    }

    /**
     * Fans out per-message metadata fetches in parallel. Per-message
     * 404s are swallowed and reported via {@link FetchMetasResult#notFoundIds}
     * — Gmail emits messages-added history events even for items the
     * user permanently deletes seconds later, and a single missing
     * message shouldn't take down a 50-message batch (and with it the
     * sync watermark, leading to an infinite re-poll loop on the same
     * dead id). Other failures still bubble up.
     */
    private FetchMetasResult fetchMessageMetas(String accessToken, List<String> messageIds)
    {
        if (messageIds.isEmpty()) {
            return new FetchMetasResult(List.of(), List.of());
        }
        List<CompletableFuture<MetaOrMissing>> futures = messageIds.stream()
                .map(id -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return new MetaOrMissing(gmail.getMessageMetadata(accessToken, id), null);
                    }
                    catch (ResponseStatusException e) {
                        if (e.getStatusCode().value() == 404) {
                            log.debug("Gmail metadata 404 for {} — message gone, skipping", id);
                            return new MetaOrMissing(null, id);
                        }
                        throw e;
                    }
                }, executor))
                .collect(Collectors.toUnmodifiableList());
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }
        catch (Exception e) {
            Throwable cause = e.getCause();
            if (cause instanceof ResponseStatusException rse) {
                throw rse;
            }
            throw new ResponseStatusException(HttpStatusCode.valueOf(502),
                    "Gmail metadata fetch failed: " + e.getMessage(), e);
        }
        List<EmailMessageMeta> ok = new ArrayList<>(futures.size());
        List<String> missing = new ArrayList<>();
        for (CompletableFuture<MetaOrMissing> f : futures) {
            MetaOrMissing r = f.join();
            if (r.meta() != null) {
                ok.add(r.meta());
            }
            else if (r.missingId() != null) {
                missing.add(r.missingId());
            }
        }
        return new FetchMetasResult(List.copyOf(ok), List.copyOf(missing));
    }

    /** Result of {@link #fetchMessageMetas}: successfully fetched metas
     *  plus the IDs Gmail returned 404 for (so the caller can purge them
     *  from the local cache rather than treating their absence as a bug). */
    private record FetchMetasResult(List<EmailMessageMeta> ok, List<String> notFoundIds) {}

    private record MetaOrMissing(EmailMessageMeta meta, String missingId) {}

    /** Cheap snippet fallback — first ~200 chars of plain text or
     *  stripped HTML. The full-detail view replaces this when the
     *  user opens the thread. */
    private static String snippetFromBody(String text, String html)
    {
        String src = text != null ? text : html;
        if (src == null) {
            return "";
        }
        String stripped = src
                .replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return stripped.length() > 200 ? stripped.substring(0, 200) : stripped;
    }

    private static void requireNonBlank(String email)
    {
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "email must not be blank");
        }
    }
}
