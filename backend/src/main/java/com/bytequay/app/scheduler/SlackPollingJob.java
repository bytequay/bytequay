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
import com.bytequay.app.service.slack.SlackOAuthService;
import com.bytequay.app.service.slack.SlackOAuthService.ConnectionInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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

    private final SlackOAuthService oauthService;
    private final SlackApiClient apiClient;
    private final FollowedChannelStore followedChannelStore;
    private final SlackMessageStore messageStore;
    private final SlackChannelWatermarkStore watermarkStore;
    private final SlackDmConversationStore dmConversationStore;
    private final Clock clock;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Autowired
    public SlackPollingJob(
            SlackOAuthService oauthService,
            SlackApiClient apiClient,
            FollowedChannelStore followedChannelStore,
            SlackMessageStore messageStore,
            SlackChannelWatermarkStore watermarkStore,
            SlackDmConversationStore dmConversationStore)
    {
        this(oauthService, apiClient, followedChannelStore, messageStore, watermarkStore, dmConversationStore, Clock.systemUTC());
    }

    SlackPollingJob(
            SlackOAuthService oauthService,
            SlackApiClient apiClient,
            FollowedChannelStore followedChannelStore,
            SlackMessageStore messageStore,
            SlackChannelWatermarkStore watermarkStore,
            SlackDmConversationStore dmConversationStore,
            Clock clock)
    {
        this.oauthService = requireNonNull(oauthService, "oauthService is null");
        this.apiClient = requireNonNull(apiClient, "apiClient is null");
        this.followedChannelStore = requireNonNull(followedChannelStore, "followedChannelStore is null");
        this.messageStore = requireNonNull(messageStore, "messageStore is null");
        this.watermarkStore = requireNonNull(watermarkStore, "watermarkStore is null");
        this.dmConversationStore = requireNonNull(dmConversationStore, "dmConversationStore is null");
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
        for (FollowedChannel followed : followedChannelStore.findByWorkspace(workspaceId)) {
            pollConversation(token, workspaceId, followed.channelId(), authedUserId, /* isDm */ false);
        }

        // 3. DM/MPIM conversations.
        for (SlackDmConversation dm : dms) {
            pollConversation(token, workspaceId, dm.conversationId(), authedUserId, /* isDm */ true);
        }
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
                if (!ts.isEmpty() && compareTs(ts, maxTs) > 0) {
                    maxTs = ts;
                }
                SlackMessage parsed = parseMessage(node, workspaceId, channelId, authedUserId, isDm, now);
                if (parsed == null) {
                    continue;
                }
                messages.add(parsed);
            }
            messageStore.insertIfAbsent(messages.build());
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

    /** Slack ts strings are "<seconds>.<microseconds>". Lexicographic
     *  compare works iff both sides have the same integer width — they
     *  do for the foreseeable future (10-digit unix seconds), and on
     *  parse failure we fall back to numeric. */
    static int compareTs(String a, String b)
    {
        if (a == null || a.isEmpty()) {
            return b == null || b.isEmpty() ? 0 : -1;
        }
        if (b == null || b.isEmpty()) {
            return 1;
        }
        try {
            double da = Double.parseDouble(a);
            double db = Double.parseDouble(b);
            return Double.compare(da, db);
        }
        catch (NumberFormatException e) {
            return a.compareTo(b);
        }
    }
}
