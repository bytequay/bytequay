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
package com.bytequay.app.web;

import com.bytequay.app.service.slack.SlackChannelService;
import com.bytequay.app.service.slack.SlackChannelService.ChannelRow;
import com.bytequay.app.service.slack.SlackOAuthService;
import com.bytequay.app.service.slack.SlackOAuthService.ConnectionInfo;
import com.google.common.collect.ImmutableMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * REST endpoints driving the Slack OAuth flow. Slice 2a only — the
 * Electron main process and the renderer are wired in Slice 2b.
 *
 * <p>All endpoints short-circuit with 503 when the integration is not
 * configured (env vars {@code SLACK_CLIENT_ID} / {@code SLACK_CLIENT_SECRET}
 * absent). The renderer reads {@code GET /api/slack/connection} on
 * page load to pick between the pre-connect surface and the connected
 * inbox.
 */
@RestController
@RequestMapping("/api/slack")
public class SlackController
{
    private final SlackOAuthService oauth;
    private final SlackChannelService channelService;

    public SlackController(SlackOAuthService oauth, SlackChannelService channelService)
    {
        this.oauth = requireNonNull(oauth, "oauth is null");
        this.channelService = requireNonNull(channelService, "channelService is null");
    }

    public record CallbackRequest(String code, String state) {}

    public record FollowedChannelsRequest(List<String> channelIds) {}

    /**
     * GET /api/slack/oauth/authorize-url — returns the Slack authorize URL
     * the renderer should open in the system browser. Mints a fresh
     * CSRF state token as a side effect.
     *
     * <p>Response shape: {@code {"configured": bool, "url": string?}}.
     * When {@code configured} is false, {@code url} is null and the
     * renderer shows a "set SLACK_CLIENT_ID / SLACK_CLIENT_SECRET" hint.
     */
    @GetMapping("/oauth/authorize-url")
    public Map<String, Object> authorizeUrl()
    {
        if (!oauth.isConfigured()) {
            return ImmutableMap.of("configured", false);
        }
        return ImmutableMap.of(
                "configured", true,
                "url", oauth.issueAuthorizeUrl());
    }

    /**
     * POST /api/slack/oauth/callback — completes the handshake. Body:
     * {@code {"code": "...", "state": "..."}}. Returns the connected
     * workspace info on success; 400 on bad state / missing fields;
     * 502 on Slack-side failure; 503 when not configured.
     */
    @PostMapping("/oauth/callback")
    public ConnectionInfo callback(@RequestBody CallbackRequest req)
    {
        return oauth.exchangeCode(req.code(), req.state());
    }

    /**
     * GET /api/slack/connection — returns {@code {connected: bool,
     * teamId?, teamName?, authedUserId?}}. Cheap; backed by a single
     * credentials-store lookup.
     */
    @GetMapping("/connection")
    public Map<String, Object> connection()
    {
        Optional<ConnectionInfo> info = oauth.getConnection();
        if (info.isEmpty()) {
            return ImmutableMap.of("connected", false);
        }
        ConnectionInfo c = info.get();
        ImmutableMap.Builder<String, Object> out = ImmutableMap.builder();
        out.put("connected", true);
        if (c.teamId() != null) {
            out.put("teamId", c.teamId());
        }
        if (c.teamName() != null) {
            out.put("teamName", c.teamName());
        }
        if (c.authedUserId() != null) {
            out.put("authedUserId", c.authedUserId());
        }
        return out.build();
    }

    /**
     * POST /api/slack/disconnect — clears the stored Slack user token.
     * Idempotent.
     */
    @PostMapping("/disconnect")
    public Map<String, String> disconnect()
    {
        oauth.disconnect();
        return ImmutableMap.of("result", "disconnected");
    }

    /**
     * GET /api/slack/channels — list the user's joined Slack channels with
     * isFollowed + isSmartDefault flags. Powers the channel-selection
     * screen (slice 3). 503 when no workspace is connected, 502 when
     * Slack itself errors.
     */
    @GetMapping("/channels")
    public List<ChannelRow> channels()
    {
        return channelService.listChannels();
    }

    /**
     * PUT /api/slack/channels/followed — replaces the user's followed-
     * channel set for the connected workspace. Body:
     * {@code {"channelIds": ["Cxxxxx", "Gxxxxx", ...]}}. The response
     * is the same shape as GET /channels with the new isFollowed flags
     * applied (smart-default flags are dropped after the first save).
     */
    @PutMapping("/channels/followed")
    public List<ChannelRow> replaceFollowed(@RequestBody FollowedChannelsRequest req)
    {
        List<String> ids = req.channelIds() != null ? req.channelIds() : List.of();
        return channelService.replaceFollowed(ids);
    }
}
