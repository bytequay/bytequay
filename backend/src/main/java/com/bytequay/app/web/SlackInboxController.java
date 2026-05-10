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

import com.bytequay.app.domain.SlackInboxItemState;
import com.bytequay.app.domain.SlackInboxStateRow;
import com.bytequay.app.domain.SlackMessage;
import com.bytequay.app.service.slack.SlackInboxService;
import com.bytequay.app.service.slack.SlackInboxService.InboxFilter;
import com.bytequay.app.service.slack.SlackInboxService.InboxItem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * REST endpoints for the Slack inbox view ({@code inbox.png}). Driven
 * by {@link SlackInboxService}; the renderer wires this in Slice 5
 * Phase B.
 *
 * <p>Mounted under {@code /api/slack/inbox} so the existing
 * {@link SlackController} stays focused on OAuth + channel selection.
 */
@RestController
@RequestMapping("/api/slack/inbox")
public class SlackInboxController
{
    private final SlackInboxService inboxService;

    public SlackInboxController(SlackInboxService inboxService)
    {
        this.inboxService = requireNonNull(inboxService, "inboxService is null");
    }

    public record InboxItemDto(
            String channelId,
            String ts,
            String state,
            String archivedAt,
            String bumpedAt,
            String respondedAt,
            String expandedAt,
            String userId,
            String text,
            String threadTs,
            boolean hasAtYou,
            String inboxKind,
            int newReplyCount) {}

    public record InboxThreadDto(
            String channelId,
            String threadTs,
            List<InboxThreadMessageDto> messages) {}

    public record InboxThreadMessageDto(
            String ts,
            String userId,
            String text,
            boolean hasAtYou) {}

    public record ReplyRequest(String text) {}

    /**
     * GET /api/slack/inbox?filter=all|mentions|dms — returns all
     * non-archived inbox items, recency-desc.
     */
    @GetMapping
    public List<InboxItemDto> list(@RequestParam(name = "filter", required = false) String filter)
    {
        ImmutableList.Builder<InboxItemDto> out = ImmutableList.builder();
        for (InboxItem item : inboxService.listInbox(InboxFilter.fromQuery(filter))) {
            out.add(toDto(item));
        }
        return out.build();
    }

    /**
     * GET /api/slack/inbox/{channel}/{ts}/thread — full thread for a
     * MENTION inbox item (parent + every reply).
     */
    @GetMapping("/{channelId}/{ts}/thread")
    public InboxThreadDto thread(@PathVariable String channelId, @PathVariable String ts)
    {
        ImmutableList.Builder<InboxThreadMessageDto> messages = ImmutableList.builder();
        for (SlackMessage m : inboxService.getThreadView(channelId, ts)) {
            messages.add(new InboxThreadMessageDto(m.ts(), m.userId(), m.text(), m.hasAtYou()));
        }
        return new InboxThreadDto(channelId, ts, messages.build());
    }

    /**
     * POST /api/slack/inbox/{channel}/{ts}/expand — flips the local
     * state to EXPANDED. No-op if the row is already past UNREAD.
     */
    @PostMapping("/{channelId}/{ts}/expand")
    public Map<String, String> expand(@PathVariable String channelId, @PathVariable String ts)
    {
        inboxService.markExpanded(channelId, ts);
        return ImmutableMap.of("result", "expanded");
    }

    /**
     * POST /api/slack/inbox/{channel}/{ts}/reply — posts a reply via
     * Slack's chat.postMessage and marks the local row RESPONDED on
     * success. GitHub-first-then-cache rule: if Slack rejects the
     * post, local state is left untouched.
     */
    @PostMapping("/{channelId}/{ts}/reply")
    public Map<String, String> reply(
            @PathVariable String channelId,
            @PathVariable String ts,
            @RequestBody ReplyRequest req)
    {
        String postedTs = inboxService.postReply(channelId, ts, req.text());
        ImmutableMap.Builder<String, String> out = ImmutableMap.builder();
        out.put("result", "responded");
        if (postedTs != null) {
            out.put("postedTs", postedTs);
        }
        return out.build();
    }

    /**
     * POST /api/slack/inbox/{channel}/{ts}/archive — manual archive-now.
     * Backs the "Archive now" link in the responded countdown bar.
     */
    @PostMapping("/{channelId}/{ts}/archive")
    public Map<String, String> archive(@PathVariable String channelId, @PathVariable String ts)
    {
        inboxService.archiveNow(channelId, ts);
        return ImmutableMap.of("result", "archived");
    }

    private static InboxItemDto toDto(InboxItem item)
    {
        SlackInboxStateRow s = item.state();
        SlackMessage m = item.rootMessage();
        return new InboxItemDto(
                m.channelId(),
                m.ts(),
                stateToWire(s.state()),
                instantOrNull(s.archivedAt()),
                instantOrNull(s.bumpedAt()),
                instantOrNull(s.respondedAt()),
                instantOrNull(s.expandedAt()),
                m.userId(),
                m.text(),
                m.threadTs(),
                m.hasAtYou(),
                m.inboxKind().toDb(),
                item.newReplyCount());
    }

    private static String stateToWire(SlackInboxItemState state)
    {
        return state.toDb();
    }

    private static String instantOrNull(Instant i)
    {
        return i == null ? null : i.toString();
    }
}
