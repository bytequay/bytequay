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
import com.bytequay.app.service.slack.SlackInboxService;
import com.bytequay.app.service.slack.SlackOAuthService;
import com.bytequay.app.service.slack.SlackOAuthService.ConnectionInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestSlackPollingJob
{
    private static final String WS = "T1";
    private static final String ME = "U123";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-05-10T10:00:00Z");

    @Test
    void testTickIsNoOpWhenNoWorkspaceConnected()
            throws Exception
    {
        Fixture f = new Fixture();
        when(f.oauth.getConnection()).thenReturn(Optional.empty());
        when(f.oauth.getValidAccessToken()).thenReturn(Optional.empty());

        f.job().tick();

        // No API calls and no store mutations.
        verify(f.api, never()).listImAndMpimConversations(any(), any());
        assertThat(f.messageStore.inserted).isEmpty();
    }

    @Test
    void testFirstSyncBootstrapsLast24Hours()
            throws Exception
    {
        Fixture f = new Fixture();
        f.connected();
        f.followed.add(new FollowedChannel(WS, "C1", "general", false, NOW));
        ArgumentCaptor<String> oldestCap = ArgumentCaptor.forClass(String.class);
        when(f.api.listImAndMpimConversations("xoxp", WS)).thenReturn(List.of());
        // ts must be > (NOW - 24h) so the watermark-advance check accepts it.
        String recentTs = (NOW.getEpochSecond() - 60) + ".000100";
        when(f.api.getConversationsHistory(eq("xoxp"), eq("C1"), oldestCap.capture()))
                .thenReturn(List.of(messageJson(recentTs, "U999", "hey there")));

        f.job().tick();

        // Bootstrap window: oldest = now - 24h, in epoch seconds.
        long expectedOldest = NOW.minusSeconds(24 * 60 * 60).getEpochSecond();
        assertThat(oldestCap.getValue()).isEqualTo(Long.toString(expectedOldest));
        assertThat(f.messageStore.inserted).hasSize(1);
        assertThat(f.messageStore.inserted.get(0).ts()).isEqualTo(recentTs);
        // Watermark advanced to the latest seen ts.
        assertThat(f.watermarks.find(WS, "C1"))
                .map(SlackChannelWatermark::lastTs)
                .contains(recentTs);
    }

    @Test
    void testIncrementalPollUsesExistingWatermark()
            throws Exception
    {
        Fixture f = new Fixture();
        f.connected();
        f.followed.add(new FollowedChannel(WS, "C1", "general", false, NOW));
        f.watermarks.upsert(new SlackChannelWatermark(WS, "C1", "1700000000.000000", NOW.minusSeconds(60)));
        when(f.api.listImAndMpimConversations("xoxp", WS)).thenReturn(List.of());
        ArgumentCaptor<String> oldestCap = ArgumentCaptor.forClass(String.class);
        when(f.api.getConversationsHistory(eq("xoxp"), eq("C1"), oldestCap.capture()))
                .thenReturn(List.of(
                        messageJson("1700000010.000100", "U999", "first new"),
                        messageJson("1700000020.000200", "U999", "second new")));

        f.job().tick();

        assertThat(oldestCap.getValue()).isEqualTo("1700000000.000000");
        assertThat(f.messageStore.inserted).hasSize(2);
        // Watermark advances to the highest ts in the batch.
        assertThat(f.watermarks.find(WS, "C1"))
                .map(SlackChannelWatermark::lastTs)
                .contains("1700000020.000200");
    }

    @Test
    void testCategorizesMentionInFollowedChannel()
            throws Exception
    {
        Fixture f = new Fixture();
        f.connected();
        f.followed.add(new FollowedChannel(WS, "C1", "general", false, NOW));
        when(f.api.listImAndMpimConversations("xoxp", WS)).thenReturn(List.of());
        when(f.api.getConversationsHistory(eq("xoxp"), eq("C1"), any()))
                .thenReturn(List.of(
                        messageJson("1700000010.000100", "U999", "Hey <@U123> please review"),
                        messageJson("1700000020.000200", "U999", "deploy is green")));

        f.job().tick();

        Map<String, SlackInboxKind> byTs = new HashMap<>();
        for (SlackMessage m : f.messageStore.inserted) {
            byTs.put(m.ts(), m.inboxKind());
        }
        assertThat(byTs.get("1700000010.000100")).isEqualTo(SlackInboxKind.MENTION);
        assertThat(byTs.get("1700000020.000200")).isEqualTo(SlackInboxKind.CHANNEL);
        // hasAtYou mirrors the mention detection so the inbox view can
        // bold the row without re-scanning the text.
        SlackMessage mention = f.messageStore.inserted.stream()
                .filter(m -> m.ts().equals("1700000010.000100")).findFirst().orElseThrow();
        assertThat(mention.hasAtYou()).isTrue();
    }

    @Test
    void testDmConversationsClassifyAsDm()
            throws Exception
    {
        Fixture f = new Fixture();
        f.connected();
        SlackDmConversation dm = new SlackDmConversation(
                WS, "D55", false, List.of("U999"), "1700000010.000100", NOW);
        when(f.api.listImAndMpimConversations("xoxp", WS)).thenReturn(List.of(dm));
        when(f.api.getConversationsHistory(eq("xoxp"), eq("D55"), any()))
                .thenReturn(List.of(messageJson("1700000050.000100", "U999", "lunch?")));

        f.job().tick();

        assertThat(f.dms.findByWorkspace(WS)).extracting(SlackDmConversation::conversationId).containsExactly("D55");
        assertThat(f.messageStore.inserted).hasSize(1);
        assertThat(f.messageStore.inserted.get(0).inboxKind()).isEqualTo(SlackInboxKind.DM);
    }

    @Test
    void testSubtypeNoiseIsSkipped()
            throws Exception
    {
        Fixture f = new Fixture();
        f.connected();
        f.followed.add(new FollowedChannel(WS, "C1", "general", false, NOW));
        when(f.api.listImAndMpimConversations("xoxp", WS)).thenReturn(List.of());
        // channel_join is a subtype Slack uses for "U999 has joined".
        // Filter it out so the inbox doesn't show every join event.
        when(f.api.getConversationsHistory(eq("xoxp"), eq("C1"), any()))
                .thenReturn(List.of(
                        messageJsonWithSubtype("1700000005.000000", "channel_join"),
                        messageJson("1700000010.000100", "U999", "real message")));

        f.job().tick();

        assertThat(f.messageStore.inserted).hasSize(1);
        assertThat(f.messageStore.inserted.get(0).text()).isEqualTo("real message");
    }

    @Test
    void testWatermarkAdvancesEvenWhenAllMessagesFiltered()
            throws Exception
    {
        Fixture f = new Fixture();
        f.connected();
        f.followed.add(new FollowedChannel(WS, "C1", "general", false, NOW));
        f.watermarks.upsert(new SlackChannelWatermark(WS, "C1", "1700000000.000000", NOW.minusSeconds(60)));
        when(f.api.listImAndMpimConversations("xoxp", WS)).thenReturn(List.of());
        when(f.api.getConversationsHistory(eq("xoxp"), eq("C1"), any()))
                .thenReturn(List.of(
                        messageJsonWithSubtype("1700000010.000100", "channel_topic"),
                        messageJsonWithSubtype("1700000020.000200", "channel_join")));

        f.job().tick();

        // Nothing inserted, but the watermark still advances to the
        // highest ts — otherwise a chatty bot channel would refetch the
        // same window forever.
        assertThat(f.messageStore.inserted).isEmpty();
        assertThat(f.watermarks.find(WS, "C1"))
                .map(SlackChannelWatermark::lastTs)
                .contains("1700000020.000200");
    }

    @Test
    void testCompareTsIsNumeric()
    {
        // Sanity check the helper used to advance the watermark.
        assertThat(SlackPollingJob.compareTs("1700000010.000100", "1700000010.000099")).isPositive();
        assertThat(SlackPollingJob.compareTs("1700000010", "1700000020")).isNegative();
        assertThat(SlackPollingJob.compareTs("", "")).isZero();
        assertThat(SlackPollingJob.compareTs(null, "1700000000")).isNegative();
    }

    private static JsonNode messageJson(String ts, String userId, String text)
            throws Exception
    {
        ObjectNodeLike o = new ObjectNodeLike();
        o.put("ts", ts);
        o.put("user", userId);
        o.put("text", text);
        return MAPPER.readTree(o.toJson());
    }

    private static JsonNode messageJsonWithSubtype(String ts, String subtype)
            throws Exception
    {
        ObjectNodeLike o = new ObjectNodeLike();
        o.put("ts", ts);
        o.put("subtype", subtype);
        return MAPPER.readTree(o.toJson());
    }

    /** Tiny JSON builder — avoids pulling jackson-databind's mutable
     *  ObjectNode just for a fixture. */
    private static final class ObjectNodeLike
    {
        private final Map<String, String> fields = new LinkedHashMap<>();

        void put(String k, String v) { fields.put(k, v); }

        String toJson()
        {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, String> e : fields.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(e.getKey()).append("\":\"").append(e.getValue()).append('"');
            }
            return sb.append('}').toString();
        }
    }

    /** Bundles the polling job + its dependencies for one test scenario. */
    private static final class Fixture
    {
        final SlackOAuthService oauth = mock(SlackOAuthService.class);
        final SlackApiClient api = mock(SlackApiClient.class);
        final InMemoryFollowedChannelStore followedStore = new InMemoryFollowedChannelStore();
        final InMemoryMessageStore messageStore = new InMemoryMessageStore();
        final InMemoryWatermarkStore watermarks = new InMemoryWatermarkStore();
        final InMemoryDmStore dms = new InMemoryDmStore();
        final SlackInboxService inboxService = mock(SlackInboxService.class);
        final List<FollowedChannel> followed = followedStore.workspaceRows;
        final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        void connected()
        {
            when(oauth.getConnection()).thenReturn(Optional.of(new ConnectionInfo(WS, "Acme", ME)));
            when(oauth.getValidAccessToken()).thenReturn(Optional.of("xoxp"));
        }

        SlackPollingJob job()
        {
            return new SlackPollingJob(oauth, api, followedStore, messageStore, watermarks, dms, inboxService, clock);
        }
    }

    private static final class InMemoryFollowedChannelStore
            implements FollowedChannelStore
    {
        final List<FollowedChannel> workspaceRows = new ArrayList<>();

        @Override
        public List<FollowedChannel> findByWorkspace(String workspaceId)
        {
            List<FollowedChannel> out = new ArrayList<>();
            for (FollowedChannel c : workspaceRows) {
                if (c.workspaceId().equals(workspaceId)) {
                    out.add(c);
                }
            }
            return out;
        }

        @Override
        public void replace(String workspaceId, List<FollowedChannel> channels)
        {
            workspaceRows.removeIf(c -> c.workspaceId().equals(workspaceId));
            workspaceRows.addAll(channels);
        }
    }

    private static final class InMemoryMessageStore
            implements SlackMessageStore
    {
        final List<SlackMessage> inserted = new ArrayList<>();

        @Override
        public void insertIfAbsent(List<SlackMessage> messages)
        {
            for (SlackMessage m : messages) {
                boolean exists = inserted.stream()
                        .anyMatch(x -> x.workspaceId().equals(m.workspaceId())
                                && x.channelId().equals(m.channelId())
                                && x.ts().equals(m.ts()));
                if (!exists) {
                    inserted.add(m);
                }
            }
        }

        @Override
        public Optional<SlackMessage> find(String workspaceId, String channelId, String ts)
        {
            return inserted.stream()
                    .filter(m -> m.workspaceId().equals(workspaceId)
                            && m.channelId().equals(channelId)
                            && m.ts().equals(ts))
                    .findFirst();
        }

        @Override
        public List<SlackMessage> findByChannel(String workspaceId, String channelId)
        {
            return List.of();
        }

        @Override
        public List<SlackMessage> findByThread(String workspaceId, String channelId, String threadTs)
        {
            return List.of();
        }

        @Override
        public List<SlackMessage> findByInboxKind(String workspaceId, SlackInboxKind kind)
        {
            return List.of();
        }
    }

    private static final class InMemoryWatermarkStore
            implements SlackChannelWatermarkStore
    {
        final Map<String, SlackChannelWatermark> rows = new HashMap<>();

        @Override
        public Optional<SlackChannelWatermark> find(String workspaceId, String channelId)
        {
            return Optional.ofNullable(rows.get(workspaceId + "|" + channelId));
        }

        @Override
        public void upsert(SlackChannelWatermark watermark)
        {
            rows.put(watermark.workspaceId() + "|" + watermark.channelId(), watermark);
        }
    }

    private static final class InMemoryDmStore
            implements SlackDmConversationStore
    {
        final AtomicReference<List<SlackDmConversation>> rows = new AtomicReference<>(new ArrayList<>());

        @Override
        public List<SlackDmConversation> findByWorkspace(String workspaceId)
        {
            return new ArrayList<>(rows.get());
        }

        @Override
        public void replace(String workspaceId, List<SlackDmConversation> conversations)
        {
            rows.set(new ArrayList<>(conversations));
        }
    }
}
