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
package com.bytequay.app.service.slack;

import com.bytequay.app.domain.SlackInboxItemState;
import com.bytequay.app.domain.SlackInboxKind;
import com.bytequay.app.domain.SlackInboxStateRow;
import com.bytequay.app.domain.SlackMessage;
import com.bytequay.app.repository.SlackInboxStateStore;
import com.bytequay.app.repository.SlackMessageStore;
import com.bytequay.app.service.slack.SlackOAuthService.ConnectionInfo;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Owns the four-state inbox machine (Unread → Expanded → Responded →
 * Bumped) layered on top of the {@code slack_messages} cache. The
 * polling loop calls {@link #recordNewMessages} after each ingest;
 * the renderer calls {@link #listInbox} to paint the inbox.png
 * surface.
 */
@Service
public class SlackInboxService
{
    private static final Logger log = LoggerFactory.getLogger(SlackInboxService.class);

    /**
     * Auto-archive threshold for RESPONDED items. Per the 2026-05-10
     * decision (logged in the Slack design doc): 4h. Settable per-user
     * later — Slice 7's Settings → Slack subpage.
     */
    public static final Duration AUTO_ARCHIVE_THRESHOLD = Duration.ofHours(4);

    private final SlackOAuthService oauthService;
    private final SlackApiClient apiClient;
    private final SlackMessageStore messageStore;
    private final SlackInboxStateStore stateStore;
    private final Clock clock;

    @Autowired
    public SlackInboxService(
            SlackOAuthService oauthService,
            SlackApiClient apiClient,
            SlackMessageStore messageStore,
            SlackInboxStateStore stateStore)
    {
        this(oauthService, apiClient, messageStore, stateStore, Clock.systemUTC());
    }

    SlackInboxService(
            SlackOAuthService oauthService,
            SlackApiClient apiClient,
            SlackMessageStore messageStore,
            SlackInboxStateStore stateStore,
            Clock clock)
    {
        this.oauthService = requireNonNull(oauthService, "oauthService is null");
        this.apiClient = requireNonNull(apiClient, "apiClient is null");
        this.messageStore = requireNonNull(messageStore, "messageStore is null");
        this.stateStore = requireNonNull(stateStore, "stateStore is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public List<InboxItem> listInbox(InboxFilter filter)
    {
        Optional<ConnectionInfo> conn = oauthService.getConnection();
        if (conn.isEmpty()) {
            return List.of();
        }
        String workspaceId = conn.get().teamId();
        ImmutableList.Builder<InboxItem> out = ImmutableList.builder();
        for (SlackInboxStateRow state : stateStore.findActive(workspaceId)) {
            Optional<SlackMessage> rootOpt = messageStore.find(workspaceId, state.channelId(), state.ts());
            if (rootOpt.isEmpty()) {
                // Defensive — the state row outlived its message (e.g.
                // clean-rebuild scenario). Skip rather than render an
                // empty card.
                continue;
            }
            SlackMessage root = rootOpt.get();
            if (!matches(filter, root.inboxKind())) {
                continue;
            }
            // newReplyCount drives the BUMPED visual ("3 NEW" pill) —
            // count messages newer than the user's reply (or the inbox
            // row creation time, before the user replied).
            int newReplyCount = countNewActivity(workspaceId, state, root);
            out.add(new InboxItem(state, root, newReplyCount));
        }
        return out.build();
    }

    /**
     * All messages for one channel (or DM) oldest-first. Backs the
     * channel-feed view and the DM expanded view in Slice 6 — both
     * read from the same {@code slack_messages} cache, just keyed by
     * a Cxxxx vs Dxxxx prefix.
     */
    public List<SlackMessage> getChannelFeed(String channelId)
    {
        Optional<ConnectionInfo> conn = oauthService.getConnection();
        if (conn.isEmpty()) {
            return List.of();
        }
        // findByChannel returns newest-first; the feed renders oldest-first
        // so the natural reading order matches Slack's web app.
        List<SlackMessage> newestFirst = messageStore.findByChannel(conn.get().teamId(), channelId);
        return newestFirst.reversed();
    }

    /** Full thread (parent + replies) for the inbox MENTION expanded
     *  view. Reads from the local cache; the polling loop is what keeps
     *  it fresh. Falls back to {@code conversations.replies} on demand
     *  if we haven't ingested all of the thread yet. */
    public List<SlackMessage> getThreadView(String channelId, String threadTs)
    {
        ConnectionInfo conn = requireConnection();
        String workspaceId = conn.teamId();
        List<SlackMessage> cached = messageStore.findByThread(workspaceId, channelId, threadTs);
        if (cached.size() >= 2) {
            return cached;
        }
        // Cache miss / partial — pull straight from Slack so the user
        // doesn't see an empty thread on first open.
        Optional<String> tokenOpt = oauthService.getValidAccessToken();
        if (tokenOpt.isEmpty()) {
            return cached;
        }
        try {
            apiClient.getConversationsReplies(tokenOpt.get(), channelId, threadTs);
            // The polling-loop's normal path doesn't ingest replies-only
            // payloads (they don't pass through the watermark loop), so
            // the on-demand fetch is informational for now —
            // re-reading the cache after the round-trip would still
            // miss them. Surfacing the live cached set is fine for v1;
            // a richer "merge into cache" path is a follow-up if we see
            // user-visible gaps.
            return messageStore.findByThread(workspaceId, channelId, threadTs);
        }
        catch (Exception e) {
            log.warn("Slack conversations.replies fetch failed: {}", e.getMessage());
            return cached;
        }
    }

    public void markExpanded(String channelId, String ts)
    {
        ConnectionInfo conn = requireConnection();
        stateStore.markExpanded(conn.teamId(), channelId, ts, clock.instant());
    }

    /**
     * Posts a reply through Slack and then marks the local inbox row
     * RESPONDED. GitHub-first-then-cache rule applies: if the Slack
     * call fails we don't touch local state.
     */
    public String postReply(String channelId, String threadTs, String text)
    {
        ConnectionInfo conn = requireConnection();
        String token = oauthService.getValidAccessToken()
                .orElseThrow(() -> new ResponseStatusException(HttpStatusCode.valueOf(401), "Slack token missing"));
        String postedTs = apiClient.postMessage(token, channelId, text, threadTs);
        stateStore.markResponded(conn.teamId(), channelId, threadTs, clock.instant());
        return postedTs;
    }

    public void archiveNow(String channelId, String ts)
    {
        ConnectionInfo conn = requireConnection();
        stateStore.markArchived(conn.teamId(), channelId, ts, clock.instant());
    }

    /**
     * Posts a message to a channel/DM without touching the inbox state
     * machine. Backs the channel-feed thread reply box and the DM
     * compose box in Slice 6 (the inbox reply path is the one that
     * marks RESPONDED — different surface, different semantics).
     */
    public String postFeedMessage(String channelId, String text, String threadTs)
    {
        requireConnection();
        String token = oauthService.getValidAccessToken()
                .orElseThrow(() -> new ResponseStatusException(HttpStatusCode.valueOf(401), "Slack token missing"));
        return apiClient.postMessage(token, channelId, text, threadTs);
    }

    /**
     * Hook called by the polling loop after it inserts a batch of new
     * messages into the local store. Drives both inbox row creation
     * (UNREAD on first MENTION/DM in a thread) and the asymmetric
     * resurface rule:
     *
     * <ul>
     *   <li>DM thread, archived → any new message resurrects.</li>
     *   <li>MENTION thread, archived → only a message with @you resurrects.</li>
     *   <li>Active threads → no state change (recency sort handles position).</li>
     * </ul>
     */
    public void recordNewMessages(String workspaceId, List<SlackMessage> batch)
    {
        Instant now = clock.instant();
        for (SlackMessage msg : batch) {
            String threadRoot = threadRoot(msg);
            Optional<SlackInboxStateRow> existing = stateStore.find(workspaceId, msg.channelId(), threadRoot);
            if (existing.isEmpty()) {
                if (msg.inboxKind() == SlackInboxKind.MENTION || msg.inboxKind() == SlackInboxKind.DM) {
                    stateStore.createIfAbsent(workspaceId, msg.channelId(), threadRoot);
                }
                continue;
            }
            SlackInboxStateRow row = existing.get();
            if (!row.isArchived()) {
                continue;
            }
            // Archived — apply the asymmetric resurface rule.
            SlackInboxKind parentKind = messageStore.find(workspaceId, msg.channelId(), threadRoot)
                    .map(SlackMessage::inboxKind)
                    // Defensive: a row whose parent is gone falls back to
                    // the new message's own kind to make the rule decidable.
                    .orElse(msg.inboxKind());
            boolean shouldResurrect = parentKind == SlackInboxKind.DM || msg.hasAtYou();
            if (shouldResurrect) {
                stateStore.resurrect(workspaceId, msg.channelId(), threadRoot, now);
            }
        }
    }

    /**
     * Sweep RESPONDED rows whose responded_at is older than {@link
     * #AUTO_ARCHIVE_THRESHOLD}. Called by {@code SlackInboxArchiveJob}
     * on a 60-second cadence.
     */
    public int autoArchiveExpired()
    {
        Optional<ConnectionInfo> conn = oauthService.getConnection();
        if (conn.isEmpty()) {
            return 0;
        }
        Instant now = clock.instant();
        Instant threshold = now.minus(AUTO_ARCHIVE_THRESHOLD);
        int archived = 0;
        for (SlackInboxStateRow row : stateStore.findRespondedBefore(conn.get().teamId(), threshold)) {
            stateStore.markArchived(row.workspaceId(), row.channelId(), row.ts(), now);
            archived++;
        }
        return archived;
    }

    private int countNewActivity(String workspaceId, SlackInboxStateRow state, SlackMessage root)
    {
        // Reference point: when we should start counting "new" activity.
        // - Bumped: count from bumped_at → the user already saw activity
        //   before, so the pill should reflect the post-bump deltas.
        // - Responded: count from responded_at → "did anyone reply
        //   after my reply?". Slice 5's BUMPED state is reachable from
        //   either path.
        // - Otherwise: zero — UNREAD / EXPANDED items don't carry the
        //   "N NEW" pill.
        Instant since;
        if (state.state() == SlackInboxItemState.BUMPED && state.bumpedAt() != null) {
            since = state.bumpedAt();
        }
        else if (state.respondedAt() != null) {
            since = state.respondedAt();
        }
        else {
            return 0;
        }
        String sinceTs = Long.toString(since.getEpochSecond());
        int count = 0;
        for (SlackMessage m : messageStore.findByThread(workspaceId, root.channelId(), root.ts())) {
            if (SlackTs.compare(m.ts(), sinceTs) > 0) {
                count++;
            }
        }
        return count;
    }

    private static String threadRoot(SlackMessage msg)
    {
        return msg.threadTs() != null && !msg.threadTs().isEmpty() ? msg.threadTs() : msg.ts();
    }

    private static boolean matches(InboxFilter filter, SlackInboxKind kind)
    {
        return switch (filter) {
            case ALL -> kind == SlackInboxKind.MENTION || kind == SlackInboxKind.DM;
            case MENTIONS -> kind == SlackInboxKind.MENTION;
            case DMS -> kind == SlackInboxKind.DM;
        };
    }

    private ConnectionInfo requireConnection()
    {
        return oauthService.getConnection()
                .orElseThrow(() -> new ResponseStatusException(HttpStatusCode.valueOf(409), "Slack workspace not connected"));
    }

    /** Filter for {@link #listInbox}. Maps to the inbox.png top-tab pills. */
    public enum InboxFilter
    {
        ALL, MENTIONS, DMS;

        public static InboxFilter fromQuery(String s)
        {
            if (s == null || s.isBlank()) {
                return ALL;
            }
            return switch (s.toLowerCase(Locale.ROOT)) {
                case "mentions" -> MENTIONS;
                case "dms", "dm" -> DMS;
                default -> ALL;
            };
        }
    }

    /** One row of the inbox view — state + the underlying thread-root message + new-reply count. */
    public record InboxItem(SlackInboxStateRow state, SlackMessage rootMessage, int newReplyCount) {}
}
