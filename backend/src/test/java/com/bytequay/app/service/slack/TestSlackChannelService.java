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

import com.bytequay.app.domain.FollowedChannel;
import com.bytequay.app.domain.SlackChannel;
import com.bytequay.app.repository.FollowedChannelStore;
import com.bytequay.app.service.slack.SlackChannelService.ChannelRow;
import com.bytequay.app.service.slack.SlackOAuthService.ConnectionInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestSlackChannelService
{
    private static final String WORKSPACE_ID = "T1";

    @Test
    void testSmartDefaultsPickTopThreeByLatestActivity()
    {
        SlackChannel a = new SlackChannel("C1", "alpha", false, 10, Instant.parse("2026-05-01T00:00:00Z"));
        SlackChannel b = new SlackChannel("C2", "bravo", false, 20, Instant.parse("2026-05-09T12:00:00Z"));
        SlackChannel c = new SlackChannel("C3", "charlie", false, 30, Instant.parse("2026-05-09T13:00:00Z"));
        SlackChannel d = new SlackChannel("C4", "delta", false, 40, Instant.parse("2026-05-09T14:00:00Z"));
        SlackChannel e = new SlackChannel("C5", "echo", true, 5, null); // null timestamp sorts last

        Set<String> picks = SlackChannelService.pickSmartDefaults(List.of(a, b, c, d, e));

        // Top three by recency are delta (14:00), charlie (13:00), bravo (12:00).
        assertThat(picks).containsExactlyInAnyOrder("C4", "C3", "C2");
    }

    @Test
    void testListChannelsAttachesSmartDefaultOnFirstRun()
    {
        SlackOAuthService oauth = Mockito.mock(SlackOAuthService.class);
        SlackApiClient apiClient = Mockito.mock(SlackApiClient.class);
        InMemoryFollowedChannelStore store = new InMemoryFollowedChannelStore();
        when(oauth.getConnection()).thenReturn(Optional.of(new ConnectionInfo(WORKSPACE_ID, "Acme", "U1")));
        when(oauth.getValidAccessToken()).thenReturn(Optional.of("xoxp-stub"));
        when(apiClient.listConversations("xoxp-stub")).thenReturn(List.of(
                new SlackChannel("C1", "alpha", false, 10, Instant.parse("2026-05-01T00:00:00Z")),
                new SlackChannel("C2", "bravo", false, 20, Instant.parse("2026-05-09T12:00:00Z")),
                new SlackChannel("C3", "charlie", false, 30, Instant.parse("2026-05-09T14:00:00Z"))));

        SlackChannelService service = new SlackChannelService(oauth, apiClient, store);
        List<ChannelRow> rows = service.listChannels();

        assertThat(rows).hasSize(3);
        // No prior selections → smart-default flag set on the most-recent rows.
        assertThat(rows.stream().filter(ChannelRow::isSmartDefault).map(r -> r.channel().id()))
                .containsExactlyInAnyOrder("C1", "C2", "C3");
        assertThat(rows).allMatch(r -> !r.isFollowed());
    }

    @Test
    void testListChannelsDropsSmartDefaultOnceUserHasSelections()
    {
        SlackOAuthService oauth = Mockito.mock(SlackOAuthService.class);
        SlackApiClient apiClient = Mockito.mock(SlackApiClient.class);
        InMemoryFollowedChannelStore store = new InMemoryFollowedChannelStore();
        store.replace(WORKSPACE_ID, List.of(new FollowedChannel(WORKSPACE_ID, "C2", "bravo", false, Instant.now())));
        when(oauth.getConnection()).thenReturn(Optional.of(new ConnectionInfo(WORKSPACE_ID, "Acme", "U1")));
        when(oauth.getValidAccessToken()).thenReturn(Optional.of("xoxp-stub"));
        when(apiClient.listConversations("xoxp-stub")).thenReturn(List.of(
                new SlackChannel("C1", "alpha", false, 10, Instant.parse("2026-05-01T00:00:00Z")),
                new SlackChannel("C2", "bravo", false, 20, Instant.parse("2026-05-09T12:00:00Z"))));

        SlackChannelService service = new SlackChannelService(oauth, apiClient, store);
        List<ChannelRow> rows = service.listChannels();

        assertThat(rows).hasSize(2);
        // No row should carry the smart-default badge once the user has picked anything.
        assertThat(rows).noneMatch(ChannelRow::isSmartDefault);
        // C2 is followed; C1 isn't.
        assertThat(rows.stream().filter(ChannelRow::isFollowed).map(r -> r.channel().id()))
                .containsExactly("C2");
    }

    @Test
    void testReplaceFollowedPersistsResolvedRowsAndDropsSmartDefault()
    {
        SlackOAuthService oauth = Mockito.mock(SlackOAuthService.class);
        SlackApiClient apiClient = Mockito.mock(SlackApiClient.class);
        InMemoryFollowedChannelStore store = new InMemoryFollowedChannelStore();
        when(oauth.getConnection()).thenReturn(Optional.of(new ConnectionInfo(WORKSPACE_ID, "Acme", "U1")));
        when(oauth.getValidAccessToken()).thenReturn(Optional.of("xoxp-stub"));
        when(apiClient.listConversations("xoxp-stub")).thenReturn(List.of(
                new SlackChannel("C1", "alpha", false, 10, Instant.parse("2026-05-01T00:00:00Z")),
                new SlackChannel("C2", "bravo", true, 20, Instant.parse("2026-05-09T12:00:00Z"))));

        SlackChannelService service = new SlackChannelService(oauth, apiClient, store);
        List<ChannelRow> rows = service.replaceFollowed(List.of("C2"));

        // Smart-default goes away after a save, isFollowed reflects the new set.
        assertThat(rows).noneMatch(ChannelRow::isSmartDefault);
        assertThat(rows.stream().filter(ChannelRow::isFollowed).map(r -> r.channel().id())).containsExactly("C2");
        // Persistence captures the looked-up name + privacy flag from the live list.
        List<FollowedChannel> persisted = store.findByWorkspace(WORKSPACE_ID);
        assertThat(persisted).hasSize(1);
        assertThat(persisted.get(0).channelName()).isEqualTo("bravo");
        assertThat(persisted.get(0).isPrivate()).isTrue();
    }

    @Test
    void testReplaceFollowedRemovesUntoggledChannels()
    {
        SlackOAuthService oauth = Mockito.mock(SlackOAuthService.class);
        SlackApiClient apiClient = Mockito.mock(SlackApiClient.class);
        InMemoryFollowedChannelStore store = new InMemoryFollowedChannelStore();
        Instant earlier = Instant.parse("2026-05-08T00:00:00Z");
        store.replace(WORKSPACE_ID, List.of(
                new FollowedChannel(WORKSPACE_ID, "C1", "alpha", false, earlier),
                new FollowedChannel(WORKSPACE_ID, "C2", "bravo", false, earlier)));
        when(oauth.getConnection()).thenReturn(Optional.of(new ConnectionInfo(WORKSPACE_ID, "Acme", "U1")));
        when(oauth.getValidAccessToken()).thenReturn(Optional.of("xoxp-stub"));
        when(apiClient.listConversations("xoxp-stub")).thenReturn(List.of(
                new SlackChannel("C1", "alpha", false, 10, Instant.parse("2026-05-01T00:00:00Z")),
                new SlackChannel("C2", "bravo", false, 20, Instant.parse("2026-05-09T12:00:00Z"))));

        SlackChannelService service = new SlackChannelService(oauth, apiClient, store);
        // Drop C2; only C1 should remain followed.
        service.replaceFollowed(List.of("C1"));

        List<FollowedChannel> persisted = store.findByWorkspace(WORKSPACE_ID);
        assertThat(persisted.stream().map(FollowedChannel::channelId)).containsExactly("C1");
    }

    @Test
    void testParseChannelsAcceptsLatestTsAndUpdatedFallback()
            throws Exception
    {
        ObjectMapper mapper = new ObjectMapper();
        // First channel has a Slack-shaped latest.ts; second only has the
        // updated millis fallback. Both should produce a populated Instant.
        String json = "[{\"id\":\"C1\",\"name\":\"alpha\",\"is_private\":false,\"num_members\":12,"
                + "\"latest\":{\"ts\":\"1715000000.123456\"}},"
                + "{\"id\":\"G1\",\"name\":\"bravo\",\"is_private\":true,\"num_members\":4,"
                + "\"updated\":1716000000000}]";
        JsonNode arr = mapper.readTree(json);
        List<SlackChannel> channels = SlackApiClient.parseChannelsForTest(arr);

        assertThat(channels).hasSize(2);
        assertThat(channels.get(0).id()).isEqualTo("C1");
        assertThat(channels.get(0).isPrivate()).isFalse();
        assertThat(channels.get(0).memberCount()).isEqualTo(12);
        assertThat(channels.get(0).latestActivityAt()).isEqualTo(Instant.ofEpochSecond(1715000000L));
        assertThat(channels.get(1).id()).isEqualTo("G1");
        assertThat(channels.get(1).isPrivate()).isTrue();
        assertThat(channels.get(1).latestActivityAt()).isEqualTo(Instant.ofEpochMilli(1716000000000L));
    }

    @Test
    void testReplaceFollowedTreatsTokenSourceAsCalledOnce()
    {
        SlackOAuthService oauth = Mockito.mock(SlackOAuthService.class);
        SlackApiClient apiClient = Mockito.mock(SlackApiClient.class);
        InMemoryFollowedChannelStore store = new InMemoryFollowedChannelStore();
        when(oauth.getConnection()).thenReturn(Optional.of(new ConnectionInfo(WORKSPACE_ID, "Acme", "U1")));
        when(oauth.getValidAccessToken()).thenReturn(Optional.of("xoxp-stub"));
        when(apiClient.listConversations("xoxp-stub")).thenReturn(List.of(
                new SlackChannel("C1", "alpha", false, 1, Instant.parse("2026-05-01T00:00:00Z"))));

        SlackChannelService service = new SlackChannelService(oauth, apiClient, store);
        service.replaceFollowed(List.of("C1"));

        // One refresh-aware token fetch + one Slack list call per save.
        verify(oauth, times(1)).getValidAccessToken();
        verify(apiClient, times(1)).listConversations("xoxp-stub");
    }

    /** Minimal in-memory store — enough for the picker's replace+find round trip. */
    private static final class InMemoryFollowedChannelStore
            implements FollowedChannelStore
    {
        private final Map<String, List<FollowedChannel>> byWorkspace = new HashMap<>();

        @Override
        public List<FollowedChannel> findByWorkspace(String workspaceId)
        {
            return List.copyOf(byWorkspace.getOrDefault(workspaceId, List.of()));
        }

        @Override
        public void replace(String workspaceId, List<FollowedChannel> channels)
        {
            byWorkspace.put(workspaceId, new ArrayList<>(channels));
        }
    }
}
