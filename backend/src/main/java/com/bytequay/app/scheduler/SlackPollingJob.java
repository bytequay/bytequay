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
package com.bytequay.app.scheduler;

import com.bytequay.app.domain.FollowedChannel;
import com.bytequay.app.domain.SlackChannelWatermark;
import com.bytequay.app.domain.SlackDmConversation;
import com.bytequay.app.domain.SlackInboxKind;
import com.bytequay.app.domain.SlackMessage;
import com.bytequay.app.repository.FollowedChannelStore;
import com.bytequay.app.repository.SlackChannelWatermarkStore;
import com.bytequay.app.repository.SlackDmConversationStore;
import com.bytequay.app.repository.SlackMessageStore;
import com.bytequay.app.service.slack.SlackApiClient;
import com.bytequay.app.service.slack.SlackInboxCategorizer;
import com.bytequay.app.service.slack.SlackInboxService;
import com.bytequay.app.service.slack.SlackOAuthService;
import com.bytequay.app.service.slack.SlackOAuthService.ConnectionInfo;
import com.bytequay.app.service.slack.SlackTs;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Objects.requireNonNull;

/**
 * Slice 4 polling loop. Per the realtime-mechanism doc, Slice 4 ships
 * polling-only — Socket Mode is incompatible with the PKCE distribution
 * model (one Slack-app-level token can only carry one connected
 * instance) and is parked behind a future feature flag for BYO users.
 *
 * <p>On each 30-second tick, when a workspace is connected:
 * <ol>
 *   <li>Refresh the open-DM/MPIM list (Slack's view is the source of truth).</li>
 *   <li>For each followed channel + DM conversation: fetch
 *       {@code conversations.history?oldest=<watermark>} (or {@code now-24h}
 *       on first sync), categorise via {@link SlackInboxCategorizer},
 *       insert any new rows, advance the watermark.</li>
 * </ol>
 *
 * <p>Each per-conversation pass is independently try/catch'd — a
 * 4xx/5xx on one channel doesn't stall the rest of the tick. The
 * {@link AtomicBoolean} guard prevents overlapping ticks if a tick
 * runs longer than the 30-second cadence (e.g. first-sync bootstrap on
 * a chatty channel).
 */
@Component
public class SlackPollingJob
{
    private static final Logger log = LoggerFactory.getLogger(SlackPollingJob.class);

    private static final Duration BOOTSTRAP_WINDOW = Duration.ofHours(24);

    /** Sentinel "channel id" used to persist the mention-sweep
     *  watermark via the same {@link SlackChannelWatermarkStore}. Real
     *  Slack channel ids never start with an underscore, so this can't
     *  collide. */
    static final String MENTION_SWEEP_SENTINEL = "__mention_search__";

    private final SlackOAuthService oauthService;
    private final SlackApiClient apiClient;
    private final FollowedChannelStore followedChannelStore;
    private final SlackMessageStore messageStore;
    private final SlackChannelWatermarkStore watermarkStore;
    private final SlackDmConversationStore dmConversationStore;
    private final SlackInboxService inboxService;
    private final Clock clock;

    private final AtomicBoolean running = new AtomicBoolean(false);

    /** One-shot guard for the {@code missing_scope} warn on
     *  {@code search.messages}. PKCE tokens minted before
     *  {@code search:read} was added to {@code USER_SCOPES} hit this
     *  every tick; logging once per JVM restart is enough to nudge the
     *  user to reconnect without flooding stderr. Resets across restart
     *  — intentional, so a fresh launch surfaces the warning again. */
    private final AtomicBoolean searchMissingScopeLogged = new AtomicBoolean(false);

    @Autowired
    public SlackPollingJob(
            SlackOAuthService oauthService,
            SlackApiClient apiClient,
            FollowedChannelStore followedChannelStore,
            SlackMessageStore messageStore,
            SlackChannelWatermarkStore watermarkStore,
            SlackDmConversationStore dmConversationStore,
            SlackInboxService inboxService)
    {
        this(oauthService, apiClient, followedChannelStore, messageStore, watermarkStore, dmConversationStore, inboxService, Clock.systemUTC());
    }

    SlackPollingJob(
            SlackOAuthService oauthService,
            SlackApiClient apiClient,
            FollowedChannelStore followedChannelStore,
            SlackMessageStore messageStore,
            SlackChannelWatermarkStore watermarkStore,
            SlackDmConversationStore dmConversationStore,
            SlackInboxService inboxService,
            Clock clock)
    {
        this.oauthService = requireNonNull(oauthService, "oauthService is null");
        this.apiClient = requireNonNull(apiClient, "apiClient is null");
        this.followedChannelStore = requireNonNull(followedChannelStore, "followedChannelStore is null");
        this.messageStore = requireNonNull(messageStore, "messageStore is null");
        this.watermarkStore = requireNonNull(watermarkStore, "watermarkStore is null");
        this.dmConversationStore = requireNonNull(dmConversationStore, "dmConversationStore is null");
        this.inboxService = requireNonNull(inboxService, "inboxService is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /**
     * 30-second tick with {@code initialDelay = 5_000} so the very first
     * fire happens after the renderer has had a chance to paint the
     * Slack tab — avoids competing with the pre-connect surface's
     * {@code /connection} call on cold launch.
     */
    @Scheduled(initialDelay = 5_000, fixedDelay = 30_000)
    public void tick()
    {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            doPoll();
        }
        catch (Exception e) {
            // Belt-and-braces — every per-conversation error path inside
            // doPoll already swallows + logs. This catch keeps the
            // scheduler thread alive if something unexpected escapes.
            log.warn("Slack poll tick failed: {}", e.getMessage());
        }
        finally {
            running.set(false);
        }
    }

    private void doPoll()
    {
        Optional<ConnectionInfo> connOpt = oauthService.getConnection();
        Optional<String> tokenOpt = oauthService.getValidAccessToken();
        if (connOpt.isEmpty() || tokenOpt.isEmpty()) {
            return;
        }
        ConnectionInfo conn = connOpt.get();
        String token = tokenOpt.get();
        String workspaceId = conn.teamId();
        String authedUserId = conn.authedUserId();

        // 1. Refresh the open-DM list. Slack drops conversations the user
        //    closes; the replace() call mirrors that exactly.
        List<SlackDmConversation> dms;
        try {
            dms = apiClient.listImAndMpimConversations(token, workspaceId);
            dmConversationStore.replace(workspaceId, dms);
        }
        catch (Exception e) {
            log.warn("Slack DM-list refresh failed: {}", e.getMessage());
            // Fall back to whatever the local store already has so the
            // rest of the tick can still ingest history for known DMs.
            dms = dmConversationStore.findByWorkspace(workspaceId);
        }

        // 2. Followed channels.
        Set<String> followedIds = new HashSet<>();
        for (FollowedChannel followed : followedChannelStore.findByWorkspace(workspaceId)) {
            followedIds.add(followed.channelId());
            pollConversation(token, workspaceId, followed.channelId(), authedUserId, /* isDm */ false);
        }

        // 3. DM/MPIM conversations.
        Set<String> dmIds = new HashSet<>();
        for (SlackDmConversation dm : dms) {
            dmIds.add(dm.conversationId());
            pollConversation(token, workspaceId, dm.conversationId(), authedUserId, /* isDm */ true);
        }

        // 4. Mention-sweep across the workspace — catches `<@you>` in
        //    channels the user hasn't followed (and therefore the
        //    history poll above doesn't visit). One Tier-2 search call
        //    per tick, well inside the 20/min budget.
        sweepMentions(token, workspaceId, authedUserId, followedIds, dmIds);
    }

    /**
     * Issues one {@code search.messages?query=<@USERID>} request per
     * tick and ingests any new hits that the followed-channel / DM
     * passes didn't already catch. Watermarked separately via
     * {@link #MENTION_SWEEP_SENTINEL} so the channel-history watermarks
     * stay independent.
     *
     * <p>Hits in already-followed channels or open DMs are skipped —
     * those threads have a state row from the per-conversation pass
     * already, and re-running the inbox state machine on a duplicate
     * batch would create a second UNREAD row.
     */
    private void sweepMentions(
            String token, String workspaceId, String authedUserId, Set<String> followedIds, Set<String> dmIds)
    {
        if (authedUserId == null || authedUserId.isBlank()) {
            return;
        }
        String oldest = watermarkStore.find(workspaceId, MENTION_SWEEP_SENTINEL)
                .map(SlackChannelWatermark::lastTs)
                .orElseGet(() -> bootstrapOldestTs(clock.instant()));
        List<JsonNode> matches;
        try {
            matches = apiClient.searchMessages(token, "<@" + authedUserId + ">");
        }
        catch (ResponseStatusException e) {
            String reason = e.getReason();
            if (reason != null && reason.contains("missing_scope")) {
                if (searchMissingScopeLogged.compareAndSet(false, true)) {
                    log.warn("Slack search.messages returned missing_scope — reconnect to grant search:read; "
                            + "mention sweep for unfollowed channels is disabled until then");
                }
                return;
            }
            log.warn("Slack mention sweep failed: {}", reason);
            return;
        }
        catch (Exception e) {
            log.warn("Slack mention sweep failed: {}", e.getMessage());
            return;
        }
        if (matches.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        String maxTs = oldest;
        ImmutableList.Builder<SlackMessage> batch = ImmutableList.builder();
        for (JsonNode match : matches) {
            String ts = match.path("ts").asText("");
            if (ts.isEmpty()) {
                continue;
            }
            if (SlackTs.compare(ts, oldest) <= 0) {
                // Older than (or equal to) the watermark — already seen
                // on a prior tick, or pre-bootstrap. Skip.
                continue;
            }
            if (SlackTs.compare(ts, maxTs) > 0) {
                maxTs = ts;
            }
            String channelId = match.path("channel").path("id").asText("");
            if (channelId.isEmpty()) {
                continue;
            }
            if (followedIds.contains(channelId) || dmIds.contains(channelId)) {
                // The per-conversation poll above already ingested any
                // new history for this channel — don't double-record.
                continue;
            }
            boolean isDm = SlackInboxCategorizer.isDmConversation(channelId);
            SlackMessage parsed = parseMessage(match, workspaceId, channelId, authedUserId, isDm, now);
            if (parsed == null) {
                continue;
            }
            // The search query is the `<@USERID>` token, so every
            // surviving result is a real mention (or a DM, defensively).
            // Drop anything else — a Slack search relevance match on a
            // string-quoted user id shouldn't pop into the inbox.
            if (parsed.inboxKind() != SlackInboxKind.MENTION && parsed.inboxKind() != SlackInboxKind.DM) {
                continue;
            }
            batch.add(parsed);
        }
        List<SlackMessage> built = batch.build();
        if (!built.isEmpty()) {
            messageStore.insertIfAbsent(built);
            inboxService.recordNewMessages(workspaceId, built);
        }
        // Advance the watermark from ALL matches scanned, including
        // those we skipped for being already-covered channels — that's
        // the only way to stop re-walking the same hit list every tick.
        String newWatermark = maxTs.isEmpty() ? oldest : maxTs;
        watermarkStore.upsert(new SlackChannelWatermark(workspaceId, MENTION_SWEEP_SENTINEL, newWatermark, now));
    }

    private void pollConversation(String token, String workspaceId, String channelId, String authedUserId, boolean isDm)
    {
        try {
            String oldest = watermarkStore.find(workspaceId, channelId)
                    .map(SlackChannelWatermark::lastTs)
                    .orElseGet(() -> bootstrapOldestTs(clock.instant()));

            List<JsonNode> raw = apiClient.getConversationsHistory(token, channelId, oldest);
            if (raw.isEmpty()) {
                return;
            }
            Instant now = clock.instant();
            ImmutableList.Builder<SlackMessage> messages = ImmutableList.builder();
            String maxTs = oldest;
            for (JsonNode node : raw) {
                // Advance the watermark from the raw ts BEFORE the subtype
                // filter — otherwise a channel that only carries
                // channel_join / channel_topic events would refetch the
                // same window forever.
                String ts = node.path("ts").asText("");
                if (!ts.isEmpty() && SlackTs.compare(ts, maxTs) > 0) {
                    maxTs = ts;
                }
                SlackMessage parsed = parseMessage(node, workspaceId, channelId, authedUserId, isDm, now);
                if (parsed == null) {
                    continue;
                }
                messages.add(parsed);
            }
            List<SlackMessage> built = messages.build();
            messageStore.insertIfAbsent(built);
            // Feed the inbox state machine — this creates UNREAD rows for
            // fresh MENTION/DM threads and resurrects archived items
            // per the asymmetric rule. Runs after insertIfAbsent so the
            // parent-thread lookup in the inbox service can find the
            // root message in the cache.
            inboxService.recordNewMessages(workspaceId, built);
            // Watermark advances even when every message is filtered out
            // (e.g. all bot messages with no user_id) — otherwise a
            // followed channel that only carries bot traffic would
            // re-fetch the same window forever.
            String newWatermark = maxTs.isEmpty() ? oldest : maxTs;
            watermarkStore.upsert(new SlackChannelWatermark(workspaceId, channelId, newWatermark, now));
        }
        catch (Exception e) {
            log.warn("Slack poll for {} failed: {}", channelId, e.getMessage());
        }
    }

    /** First-sync bootstrap: fetch up to {@link #BOOTSTRAP_WINDOW} of history. */
    private static String bootstrapOldestTs(Instant now)
    {
        long seconds = now.minus(BOOTSTRAP_WINDOW).getEpochSecond();
        // Slack ts is "<seconds>.<microseconds>" — seconds-only is a
        // valid input on the oldest= parameter.
        return Long.toString(seconds);
    }

    /** Parses one Slack {@code message} JsonNode into our domain row.
     *  Returns null for system/tombstone messages that don't carry
     *  enough fields for inbox display. */
    private static SlackMessage parseMessage(
            JsonNode node, String workspaceId, String channelId, String authedUserId, boolean isDm, Instant fetchedAt)
    {
        String ts = node.path("ts").asText(null);
        if (ts == null || ts.isBlank()) {
            return null;
        }
        // Skip Slack subtype noise we don't render: channel join/leave,
        // pin/topic edits, etc. Their absence keeps the inbox clean
        // without losing real user content.
        String subtype = node.path("subtype").asText("");
        if (!subtype.isEmpty() && !subtype.equals("thread_broadcast")) {
            return null;
        }
        String userId = node.path("user").asText(null);
        String text = node.path("text").asText(null);
        String threadTs = node.path("thread_ts").asText(null);
        if (threadTs != null && threadTs.isBlank()) {
            threadTs = null;
        }
        boolean hasAtYou = SlackInboxCategorizer.containsUserMention(text, authedUserId);
        SlackInboxKind kind = SlackInboxCategorizer.categorize(channelId, text, authedUserId, isDm);
        return new SlackMessage(
                workspaceId, channelId, ts,
                userId, text, threadTs, hasAtYou, kind,
                node.toString(),
                fetchedAt);
    }

    /** Test seam — exposes the SlackTs helper through the scheduler so
     *  TestSlackPollingJob can keep its existing assertions. */
    static int compareTs(String a, String b)
    {
        return SlackTs.compare(a, b);
    }
}
