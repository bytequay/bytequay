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
import com.bytequay.app.service.slack.SlackOAuthService.ConnectionInfo;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toUnmodifiableSet;

/**
 * Drives the channel-selection screen (slice 3). Combines the live
 * Slack list (via {@link SlackApiClient#listConversations}) with the
 * locally-persisted follow set ({@link FollowedChannelStore}) and
 * decorates each row with two flags the picker UI needs:
 *
 * <ul>
 *   <li>{@code isFollowed} — already in the user's saved set.</li>
 *   <li>{@code isSmartDefault} — only on first-run (no rows in
 *       {@code followed_channels} for this workspace yet); the top
 *       three by recent activity get the badge so the picker can
 *       pre-toggle them.</li>
 * </ul>
 */
@Service
public class SlackChannelService
{
    /** Smart-default cap. Matches the design doc copy ("2-3 channels you fully follow"). */
    public static final int SMART_DEFAULT_LIMIT = 3;

    private final SlackOAuthService oauthService;
    private final SlackApiClient apiClient;
    private final FollowedChannelStore followedChannelStore;

    public SlackChannelService(
            SlackOAuthService oauthService,
            SlackApiClient apiClient,
            FollowedChannelStore followedChannelStore)
    {
        this.oauthService = requireNonNull(oauthService, "oauthService is null");
        this.apiClient = requireNonNull(apiClient, "apiClient is null");
        this.followedChannelStore = requireNonNull(followedChannelStore, "followedChannelStore is null");
    }

    /**
     * Lists the user's joined channels enriched with isFollowed + isSmartDefault
     * flags. {@link ResponseStatusException} 503 when no Slack workspace is
     * connected; 502 when Slack itself errors (propagated from the API client).
     */
    public List<ChannelRow> listChannels()
    {
        ConnectionInfo connection = requireConnection();
        String token = oauthService.getValidAccessToken()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(503),
                        "Slack workspace connected but access token unavailable"));

        List<SlackChannel> channels = apiClient.listConversations(token);
        Set<String> followedIds = followedChannelStore.findByWorkspace(connection.teamId()).stream()
                .map(FollowedChannel::channelId)
                .collect(toUnmodifiableSet());
        Set<String> smartDefaultIds = followedIds.isEmpty()
                ? pickSmartDefaults(channels)
                : Set.of();

        return channels.stream()
                .map(c -> new ChannelRow(c, followedIds.contains(c.id()), smartDefaultIds.contains(c.id())))
                .collect(toImmutableList());
    }

    /** Top-{@link #SMART_DEFAULT_LIMIT} by latestActivityAt desc. Channels
     *  without a timestamp sort to the bottom. */
    static Set<String> pickSmartDefaults(List<SlackChannel> channels)
    {
        return channels.stream()
                .sorted(Comparator.comparing(
                        SlackChannel::latestActivityAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(SMART_DEFAULT_LIMIT)
                .map(SlackChannel::id)
                .collect(toUnmodifiableSet());
    }

    /**
     * Replaces the user's followed-channel set for the connected
     * workspace. {@code channelIds} is the full target set; anything
     * not in it is unfollowed. The names + privacy flags are looked up
     * against the live Slack list so the sidebar can render without a
     * second round-trip.
     */
    public List<ChannelRow> replaceFollowed(List<String> channelIds)
    {
        ConnectionInfo connection = requireConnection();
        String token = oauthService.getValidAccessToken()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(503),
                        "Slack workspace connected but access token unavailable"));

        List<SlackChannel> channels = apiClient.listConversations(token);
        Set<String> targetIds = new HashSet<>(channelIds);
        Instant now = Instant.now();
        List<FollowedChannel> resolved = channels.stream()
                .filter(c -> targetIds.contains(c.id()))
                .map(c -> new FollowedChannel(connection.teamId(), c.id(), c.name(), c.isPrivate(), now))
                .collect(toImmutableList());
        followedChannelStore.replace(connection.teamId(), resolved);

        Set<String> followedIds = resolved.stream()
                .map(FollowedChannel::channelId)
                .collect(toUnmodifiableSet());
        // After a save, smart-default badges go away — the user has made
        // explicit choices, the picker is now in management mode.
        return channels.stream()
                .map(c -> new ChannelRow(c, followedIds.contains(c.id()), false))
                .collect(toImmutableList());
    }

    private ConnectionInfo requireConnection()
    {
        Optional<ConnectionInfo> info = oauthService.getConnection();
        if (info.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(503),
                    "No Slack workspace connected");
        }
        ConnectionInfo connection = info.get();
        if (connection.teamId() == null || connection.teamId().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(503),
                    "Slack workspace connection is missing team_id");
        }
        return connection;
    }

    /** One row of the picker payload — channel + UI flags. */
    public record ChannelRow(SlackChannel channel, boolean isFollowed, boolean isSmartDefault) {}
}
