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

import com.bytequay.app.domain.SlackMessage;
import com.bytequay.app.service.slack.SlackInboxService;
import com.google.common.collect.ImmutableList;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Slice 6 — channel-feed and DM-view endpoints. Reads from the same
 * {@code slack_messages} cache the inbox surface uses; the only thing
 * that distinguishes a "feed" from an "inbox item" at the data layer
 * is whether the caller wants the whole channel (this controller) or
 * one thread root (the inbox controller).
 */
@RestController
@RequestMapping("/api/slack/feed")
public class SlackFeedController
{
    private final SlackInboxService inboxService;

    public SlackFeedController(SlackInboxService inboxService)
    {
        this.inboxService = requireNonNull(inboxService, "inboxService is null");
    }

    public record FeedMessageDto(
            String ts,
            String userId,
            String text,
            String threadTs,
            boolean hasAtYou) {}

    public record ChannelFeedDto(String channelId, List<FeedMessageDto> messages) {}

    public record FeedReplyRequest(String text, String threadTs) {}

    /**
     * GET /api/slack/feed/{channelId} — oldest-first stream of cached
     * messages for the channel. Frontend groups by {@code threadTs}
     * for the inline thread expansion in channel-feed.png.
     */
    @GetMapping("/{channelId}")
    public ChannelFeedDto channelFeed(@PathVariable String channelId)
    {
        ImmutableList.Builder<FeedMessageDto> messages = ImmutableList.builder();
        for (SlackMessage m : inboxService.getChannelFeed(channelId)) {
            messages.add(new FeedMessageDto(m.ts(), m.userId(), m.text(), m.threadTs(), m.hasAtYou()));
        }
        return new ChannelFeedDto(channelId, messages.build());
    }

    /**
     * POST /api/slack/feed/{channelId}/reply — posts a thread reply
     * (or top-level DM message) via Slack chat.postMessage. Differs
     * from the inbox reply path in that there's no inbox row to mark
     * RESPONDED — feed/DM replies are independent of the inbox state
     * machine.
     */
    @PostMapping("/{channelId}/reply")
    public Map<String, String> reply(@PathVariable String channelId, @RequestBody FeedReplyRequest req)
    {
        String postedTs = inboxService.postFeedMessage(channelId, req.text(), req.threadTs());
        return postedTs != null
                ? Map.of("result", "posted", "postedTs", postedTs)
                : Map.of("result", "posted");
    }
}
