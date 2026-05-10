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
import com.bytequay.app.service.slack.SlackInboxService.InboxFilter;
import com.bytequay.app.service.slack.SlackInboxService.InboxItem;
import com.bytequay.app.service.slack.SlackOAuthService.ConnectionInfo;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestSlackInboxService
{
    private static final String WS = "T1";
    private static final String ME = "U123";
    private static final String CH_GENERAL = "C100";
    private static final String CH_DM = "D200";
    private static final Instant NOW = Instant.parse("2026-05-10T10:00:00Z");

    @Test
    void testRecordCreatesUnreadOnFirstMention()
    {
        Fixture f = new Fixture();
        SlackMessage m = msg(CH_GENERAL, "1700000010.000100", SlackInboxKind.MENTION, true, null);
        f.messages.insertIfAbsent(List.of(m));

        f.service().recordNewMessages(WS, List.of(m));

        Optional<SlackInboxStateRow> row = f.states.find(WS, CH_GENERAL, "1700000010.000100");
        assertThat(row).isPresent();
        assertThat(row.get().state()).isEqualTo(SlackInboxItemState.UNREAD);
        assertThat(row.get().archivedAt()).isNull();
    }

    @Test
    void testRecordCreatesRowOnFirstDm()
    {
        Fixture f = new Fixture();
        SlackMessage m = msg(CH_DM, "1700000010.000100", SlackInboxKind.DM, false, null);
        f.messages.insertIfAbsent(List.of(m));

        f.service().recordNewMessages(WS, List.of(m));

        assertThat(f.states.find(WS, CH_DM, "1700000010.000100")).isPresent();
    }

    @Test
    void testRecordIgnoresPlainChannelMessage()
    {
        // CHANNEL kind with no @you and not a thread reply on an inbox
        // item — pure channel chatter that shouldn't appear in the inbox.
        Fixture f = new Fixture();
        SlackMessage m = msg(CH_GENERAL, "1700000010.000100", SlackInboxKind.CHANNEL, false, null);
        f.messages.insertIfAbsent(List.of(m));

        f.service().recordNewMessages(WS, List.of(m));

        assertThat(f.states.find(WS, CH_GENERAL, "1700000010.000100")).isEmpty();
    }

    @Test
    void testActiveItemIsNotResetByNewActivity()
    {
        Fixture f = new Fixture();
        // Existing UNREAD row from an earlier mention.
        SlackMessage parent = msg(CH_GENERAL, "1700000000.000000", SlackInboxKind.MENTION, true, null);
        f.messages.insertIfAbsent(List.of(parent));
        f.states.createIfAbsent(WS, CH_GENERAL, "1700000000.000000");
        // A new thread reply lands.
        SlackMessage reply = msg(CH_GENERAL, "1700000050.000100", SlackInboxKind.CHANNEL, false, "1700000000.000000");

        f.service().recordNewMessages(WS, List.of(reply));

        // State unchanged — recency sort handles position bumping.
        assertThat(f.states.find(WS, CH_GENERAL, "1700000000.000000"))
                .map(SlackInboxStateRow::state)
                .contains(SlackInboxItemState.UNREAD);
    }

    @Test
    void testArchivedMentionResurrectsOnFreshAtYou()
    {
        Fixture f = new Fixture();
        SlackMessage parent = msg(CH_GENERAL, "1700000000.000000", SlackInboxKind.MENTION, true, null);
        f.messages.insertIfAbsent(List.of(parent));
        f.states.createIfAbsent(WS, CH_GENERAL, "1700000000.000000");
        f.states.markResponded(WS, CH_GENERAL, "1700000000.000000", NOW.minusSeconds(60 * 60 * 5));
        f.states.markArchived(WS, CH_GENERAL, "1700000000.000000", NOW.minusSeconds(60 * 60));
        // Fresh @you in the same thread.
        SlackMessage atYou = msg(CH_GENERAL, "1700000900.000100", SlackInboxKind.MENTION, true, "1700000000.000000");

        f.service().recordNewMessages(WS, List.of(atYou));

        SlackInboxStateRow row = f.states.find(WS, CH_GENERAL, "1700000000.000000").orElseThrow();
        assertThat(row.state()).isEqualTo(SlackInboxItemState.BUMPED);
        assertThat(row.archivedAt()).isNull();
        assertThat(row.bumpedAt()).isEqualTo(NOW);
    }

    @Test
    void testArchivedMentionDoesNotResurrectOnNonMentionReply()
    {
        // Asymmetric resurface rule from the design doc — generic
        // non-mention replies in archived threads stay archived.
        Fixture f = new Fixture();
        SlackMessage parent = msg(CH_GENERAL, "1700000000.000000", SlackInboxKind.MENTION, true, null);
        f.messages.insertIfAbsent(List.of(parent));
        f.states.createIfAbsent(WS, CH_GENERAL, "1700000000.000000");
        f.states.markResponded(WS, CH_GENERAL, "1700000000.000000", NOW.minusSeconds(60 * 60 * 5));
        f.states.markArchived(WS, CH_GENERAL, "1700000000.000000", NOW.minusSeconds(60 * 60));
        // Generic reply — no @you mention.
        SlackMessage reply = msg(CH_GENERAL, "1700000900.000100", SlackInboxKind.CHANNEL, false, "1700000000.000000");

        f.service().recordNewMessages(WS, List.of(reply));

        SlackInboxStateRow row = f.states.find(WS, CH_GENERAL, "1700000000.000000").orElseThrow();
        assertThat(row.archivedAt()).isNotNull();
        assertThat(row.bumpedAt()).isNull();
    }

    @Test
    void testArchivedDmResurrectsOnAnyReply()
    {
        // DMs are inherently directed — any new message resurrects.
        Fixture f = new Fixture();
        SlackMessage parent = msg(CH_DM, "1700000000.000000", SlackInboxKind.DM, false, null);
        f.messages.insertIfAbsent(List.of(parent));
        f.states.createIfAbsent(WS, CH_DM, "1700000000.000000");
        f.states.markArchived(WS, CH_DM, "1700000000.000000", NOW.minusSeconds(60 * 60));
        SlackMessage reply = msg(CH_DM, "1700000900.000100", SlackInboxKind.DM, false, "1700000000.000000");

        f.service().recordNewMessages(WS, List.of(reply));

        assertThat(f.states.find(WS, CH_DM, "1700000000.000000"))
                .map(SlackInboxStateRow::state)
                .contains(SlackInboxItemState.BUMPED);
    }

    @Test
    void testMarkExpandedFlipsUnreadToExpanded()
    {
        Fixture f = new Fixture();
        SlackMessage parent = msg(CH_GENERAL, "1700000000.000000", SlackInboxKind.MENTION, true, null);
        f.messages.insertIfAbsent(List.of(parent));
        f.states.createIfAbsent(WS, CH_GENERAL, "1700000000.000000");

        f.service().markExpanded(CH_GENERAL, "1700000000.000000");

        SlackInboxStateRow row = f.states.find(WS, CH_GENERAL, "1700000000.000000").orElseThrow();
        assertThat(row.state()).isEqualTo(SlackInboxItemState.EXPANDED);
        assertThat(row.expandedAt()).isEqualTo(NOW);
    }

    @Test
    void testMarkExpandedDoesNotResetRespondedState()
    {
        Fixture f = new Fixture();
        SlackMessage parent = msg(CH_GENERAL, "1700000000.000000", SlackInboxKind.MENTION, true, null);
        f.messages.insertIfAbsent(List.of(parent));
        f.states.createIfAbsent(WS, CH_GENERAL, "1700000000.000000");
        f.states.markResponded(WS, CH_GENERAL, "1700000000.000000", NOW.minusSeconds(60));

        f.service().markExpanded(CH_GENERAL, "1700000000.000000");

        // RESPONDED is the load-bearing state for the auto-archive
        // countdown — opening the thread again must not flip it back.
        assertThat(f.states.find(WS, CH_GENERAL, "1700000000.000000"))
                .map(SlackInboxStateRow::state)
                .contains(SlackInboxItemState.RESPONDED);
    }

    @Test
    void testPostReplyCallsApiThenMarksResponded()
    {
        Fixture f = new Fixture();
        SlackMessage parent = msg(CH_GENERAL, "1700000000.000000", SlackInboxKind.MENTION, true, null);
        f.messages.insertIfAbsent(List.of(parent));
        f.states.createIfAbsent(WS, CH_GENERAL, "1700000000.000000");
        when(f.api.postMessage("xoxp", CH_GENERAL, "ack", "1700000000.000000")).thenReturn("1700000900.000100");

        String posted = f.service().postReply(CH_GENERAL, "1700000000.000000", "ack");

        assertThat(posted).isEqualTo("1700000900.000100");
        verify(f.api).postMessage("xoxp", CH_GENERAL, "ack", "1700000000.000000");
        SlackInboxStateRow row = f.states.find(WS, CH_GENERAL, "1700000000.000000").orElseThrow();
        assertThat(row.state()).isEqualTo(SlackInboxItemState.RESPONDED);
        assertThat(row.respondedAt()).isEqualTo(NOW);
    }

    @Test
    void testPostReplyDoesNotMarkRespondedWhenSlackFails()
    {
        // GitHub-first-then-cache: if Slack rejects the call, leave
        // local state alone so the user can retry.
        Fixture f = new Fixture();
        SlackMessage parent = msg(CH_GENERAL, "1700000000.000000", SlackInboxKind.MENTION, true, null);
        f.messages.insertIfAbsent(List.of(parent));
        f.states.createIfAbsent(WS, CH_GENERAL, "1700000000.000000");
        when(f.api.postMessage("xoxp", CH_GENERAL, "ack", "1700000000.000000"))
                .thenThrow(new RuntimeException("rate_limited"));

        try {
            f.service().postReply(CH_GENERAL, "1700000000.000000", "ack");
        }
        catch (RuntimeException ignored) {
            // expected
        }

        // State unchanged.
        SlackInboxStateRow row = f.states.find(WS, CH_GENERAL, "1700000000.000000").orElseThrow();
        assertThat(row.state()).isEqualTo(SlackInboxItemState.UNREAD);
        assertThat(row.respondedAt()).isNull();
    }

    @Test
    void testAutoArchiveSweepArchivesItemsOlderThanFourHours()
    {
        Fixture f = new Fixture();
        SlackMessage parent = msg(CH_GENERAL, "1700000000.000000", SlackInboxKind.MENTION, true, null);
        f.messages.insertIfAbsent(List.of(parent));
        f.states.createIfAbsent(WS, CH_GENERAL, "1700000000.000000");
        f.states.markResponded(WS, CH_GENERAL, "1700000000.000000", NOW.minusSeconds(60 * 60 * 5));

        int swept = f.service().autoArchiveExpired();

        assertThat(swept).isEqualTo(1);
        assertThat(f.states.find(WS, CH_GENERAL, "1700000000.000000"))
                .map(SlackInboxStateRow::archivedAt)
                .contains(NOW);
    }

    @Test
    void testAutoArchiveSweepLeavesFreshRespondedAlone()
    {
        Fixture f = new Fixture();
        SlackMessage parent = msg(CH_GENERAL, "1700000000.000000", SlackInboxKind.MENTION, true, null);
        f.messages.insertIfAbsent(List.of(parent));
        f.states.createIfAbsent(WS, CH_GENERAL, "1700000000.000000");
        f.states.markResponded(WS, CH_GENERAL, "1700000000.000000", NOW.minusSeconds(60 * 60));

        int swept = f.service().autoArchiveExpired();

        assertThat(swept).isZero();
        SlackInboxStateRow row = f.states.find(WS, CH_GENERAL, "1700000000.000000").orElseThrow();
        assertThat(row.archivedAt()).isNull();
    }

    @Test
    void testListInboxOmitsArchived()
    {
        Fixture f = new Fixture();
        SlackMessage active = msg(CH_GENERAL, "1700000010.000100", SlackInboxKind.MENTION, true, null);
        SlackMessage archived = msg(CH_GENERAL, "1700000020.000200", SlackInboxKind.MENTION, true, null);
        f.messages.insertIfAbsent(List.of(active, archived));
        f.states.createIfAbsent(WS, CH_GENERAL, "1700000010.000100");
        f.states.createIfAbsent(WS, CH_GENERAL, "1700000020.000200");
        f.states.markArchived(WS, CH_GENERAL, "1700000020.000200", NOW.minusSeconds(60));

        List<InboxItem> items = f.service().listInbox(InboxFilter.ALL);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).rootMessage().ts()).isEqualTo("1700000010.000100");
    }

    @Test
    void testListInboxFiltersByMentions()
    {
        Fixture f = new Fixture();
        SlackMessage mention = msg(CH_GENERAL, "1700000010.000100", SlackInboxKind.MENTION, true, null);
        SlackMessage dm = msg(CH_DM, "1700000020.000200", SlackInboxKind.DM, false, null);
        f.messages.insertIfAbsent(List.of(mention, dm));
        f.states.createIfAbsent(WS, CH_GENERAL, "1700000010.000100");
        f.states.createIfAbsent(WS, CH_DM, "1700000020.000200");

        List<InboxItem> mentions = f.service().listInbox(InboxFilter.MENTIONS);
        List<InboxItem> dms = f.service().listInbox(InboxFilter.DMS);

        assertThat(mentions).hasSize(1);
        assertThat(mentions.get(0).rootMessage().channelId()).isEqualTo(CH_GENERAL);
        assertThat(dms).hasSize(1);
        assertThat(dms.get(0).rootMessage().channelId()).isEqualTo(CH_DM);
    }

    @Test
    void testRecordOnDisconnectedWorkspaceIsHandledGracefully()
    {
        // Defensive — recordNewMessages is called with a workspace id;
        // it doesn't depend on the connection accessor for routing.
        Fixture f = new Fixture();
        when(f.oauth.getConnection()).thenReturn(Optional.empty());

        // Should not throw.
        f.service().recordNewMessages(WS, List.of());
        verify(f.oauth, never()).getValidAccessToken();
    }

    @Test
    void testListInboxReturnsEmptyWhenNotConnected()
    {
        Fixture f = new Fixture();
        when(f.oauth.getConnection()).thenReturn(Optional.empty());

        assertThat(f.service().listInbox(InboxFilter.ALL)).isEmpty();
    }

    @Test
    void testInboxFilterFromQueryHandlesAliases()
    {
        assertThat(InboxFilter.fromQuery(null)).isEqualTo(InboxFilter.ALL);
        assertThat(InboxFilter.fromQuery("")).isEqualTo(InboxFilter.ALL);
        assertThat(InboxFilter.fromQuery("Mentions")).isEqualTo(InboxFilter.MENTIONS);
        assertThat(InboxFilter.fromQuery("DM")).isEqualTo(InboxFilter.DMS);
        assertThat(InboxFilter.fromQuery("dms")).isEqualTo(InboxFilter.DMS);
        assertThat(InboxFilter.fromQuery("garbage")).isEqualTo(InboxFilter.ALL);
    }

    @Test
    void testPostReplyVerifiesApiCalledOnce()
    {
        Fixture f = new Fixture();
        SlackMessage parent = msg(CH_GENERAL, "1700000000.000000", SlackInboxKind.MENTION, true, null);
        f.messages.insertIfAbsent(List.of(parent));
        f.states.createIfAbsent(WS, CH_GENERAL, "1700000000.000000");
        when(f.api.postMessage("xoxp", CH_GENERAL, "ack", "1700000000.000000")).thenReturn("1700000900.000100");

        f.service().postReply(CH_GENERAL, "1700000000.000000", "ack");

        verify(f.api, times(1)).postMessage("xoxp", CH_GENERAL, "ack", "1700000000.000000");
    }

    private static SlackMessage msg(String channel, String ts, SlackInboxKind kind, boolean hasAtYou, String threadTs)
    {
        return new SlackMessage(WS, channel, ts, "U999", "hello", threadTs, hasAtYou, kind, "{}", NOW);
    }

    private static final class Fixture
    {
        final SlackOAuthService oauth = mock(SlackOAuthService.class);
        final SlackApiClient api = mock(SlackApiClient.class);
        final InMemoryMessageStore messages = new InMemoryMessageStore();
        final InMemoryStateStore states = new InMemoryStateStore();
        final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        Fixture()
        {
            when(oauth.getConnection()).thenReturn(Optional.of(new ConnectionInfo(WS, "Acme", ME)));
            when(oauth.getValidAccessToken()).thenReturn(Optional.of("xoxp"));
        }

        SlackInboxService service()
        {
            return new SlackInboxService(oauth, api, messages, states, clock);
        }
    }

    private static final class InMemoryMessageStore
            implements SlackMessageStore
    {
        final List<SlackMessage> rows = new ArrayList<>();

        @Override
        public void insertIfAbsent(List<SlackMessage> messages)
        {
            for (SlackMessage m : messages) {
                boolean exists = rows.stream().anyMatch(x -> x.workspaceId().equals(m.workspaceId())
                        && x.channelId().equals(m.channelId())
                        && x.ts().equals(m.ts()));
                if (!exists) {
                    rows.add(m);
                }
            }
        }

        @Override
        public Optional<SlackMessage> find(String workspaceId, String channelId, String ts)
        {
            return rows.stream()
                    .filter(m -> m.workspaceId().equals(workspaceId)
                            && m.channelId().equals(channelId)
                            && m.ts().equals(ts))
                    .findFirst();
        }

        @Override
        public List<SlackMessage> findByChannel(String workspaceId, String channelId)
        {
            return rows.stream()
                    .filter(m -> m.workspaceId().equals(workspaceId) && m.channelId().equals(channelId))
                    .sorted(Comparator.comparing(SlackMessage::ts).reversed())
                    .toList();
        }

        @Override
        public List<SlackMessage> findByThread(String workspaceId, String channelId, String threadTs)
        {
            return rows.stream()
                    .filter(m -> m.workspaceId().equals(workspaceId)
                            && m.channelId().equals(channelId)
                            && (threadTs.equals(m.ts()) || threadTs.equals(m.threadTs())))
                    .sorted(Comparator.comparing(SlackMessage::ts))
                    .toList();
        }

        @Override
        public List<SlackMessage> findByInboxKind(String workspaceId, SlackInboxKind kind)
        {
            return rows.stream()
                    .filter(m -> m.workspaceId().equals(workspaceId) && m.inboxKind() == kind)
                    .sorted(Comparator.comparing(SlackMessage::ts).reversed())
                    .toList();
        }
    }

    private static final class InMemoryStateStore
            implements SlackInboxStateStore
    {
        final Map<String, SlackInboxStateRow> rows = new HashMap<>();

        @Override
        public Optional<SlackInboxStateRow> find(String workspaceId, String channelId, String ts)
        {
            return Optional.ofNullable(rows.get(key(workspaceId, channelId, ts)));
        }

        @Override
        public List<SlackInboxStateRow> findActive(String workspaceId)
        {
            return rows.values().stream()
                    .filter(r -> r.workspaceId().equals(workspaceId) && !r.isArchived())
                    .sorted(Comparator.comparing(SlackInboxStateRow::ts).reversed())
                    .toList();
        }

        @Override
        public void createIfAbsent(String workspaceId, String channelId, String ts)
        {
            String k = key(workspaceId, channelId, ts);
            rows.putIfAbsent(k, new SlackInboxStateRow(
                    workspaceId, channelId, ts, SlackInboxItemState.UNREAD,
                    null, null, null, null));
        }

        @Override
        public void markExpanded(String workspaceId, String channelId, String ts, Instant when)
        {
            update(workspaceId, channelId, ts, r -> {
                SlackInboxItemState newState = r.state() == SlackInboxItemState.UNREAD
                        ? SlackInboxItemState.EXPANDED : r.state();
                Instant expandedAt = r.expandedAt() != null ? r.expandedAt() : when;
                return new SlackInboxStateRow(r.workspaceId(), r.channelId(), r.ts(),
                        newState, r.archivedAt(), r.bumpedAt(), r.respondedAt(), expandedAt);
            });
        }

        @Override
        public void markResponded(String workspaceId, String channelId, String ts, Instant when)
        {
            update(workspaceId, channelId, ts, r -> new SlackInboxStateRow(
                    r.workspaceId(), r.channelId(), r.ts(),
                    SlackInboxItemState.RESPONDED, r.archivedAt(), null, when, r.expandedAt()));
        }

        @Override
        public void markArchived(String workspaceId, String channelId, String ts, Instant when)
        {
            update(workspaceId, channelId, ts, r -> new SlackInboxStateRow(
                    r.workspaceId(), r.channelId(), r.ts(),
                    r.state(), when, r.bumpedAt(), r.respondedAt(), r.expandedAt()));
        }

        @Override
        public void resurrect(String workspaceId, String channelId, String ts, Instant when)
        {
            update(workspaceId, channelId, ts, r -> new SlackInboxStateRow(
                    r.workspaceId(), r.channelId(), r.ts(),
                    SlackInboxItemState.BUMPED, null, when, r.respondedAt(), r.expandedAt()));
        }

        @Override
        public List<SlackInboxStateRow> findRespondedBefore(String workspaceId, Instant threshold)
        {
            return rows.values().stream()
                    .filter(r -> r.workspaceId().equals(workspaceId)
                            && r.state() == SlackInboxItemState.RESPONDED
                            && !r.isArchived()
                            && r.respondedAt() != null
                            && r.respondedAt().isBefore(threshold))
                    .toList();
        }

        @Override
        public void updateState(String workspaceId, String channelId, String ts, SlackInboxItemState state)
        {
            update(workspaceId, channelId, ts, r -> new SlackInboxStateRow(
                    r.workspaceId(), r.channelId(), r.ts(),
                    state, r.archivedAt(), r.bumpedAt(), r.respondedAt(), r.expandedAt()));
        }

        private void update(String workspaceId, String channelId, String ts,
                Function<SlackInboxStateRow, SlackInboxStateRow> fn)
        {
            String k = key(workspaceId, channelId, ts);
            SlackInboxStateRow existing = rows.get(k);
            if (existing != null) {
                rows.put(k, fn.apply(existing));
            }
        }

        private static String key(String workspaceId, String channelId, String ts)
        {
            return workspaceId + "|" + channelId + "|" + ts;
        }
    }
}
