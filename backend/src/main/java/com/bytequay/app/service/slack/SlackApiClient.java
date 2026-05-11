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

import com.bytequay.app.domain.SlackChannel;
import com.bytequay.app.domain.SlackDmConversation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Thin HTTP wrapper around Slack's Web API for the non-OAuth calls
 * ByteQuay needs. Slice 3 only needs {@code conversations.list};
 * slice 4 will add {@code conversations.history},
 * {@code users.conversations}, {@code conversations.replies}, etc.
 *
 * <p>Each method takes a {@code userToken} (xoxp-) — the caller is
 * expected to source it via {@link SlackOAuthService#getValidAccessToken}
 * so PKCE refresh-near-expiry happens transparently.
 */
@Component
public class SlackApiClient
{
    private static final Logger log = LoggerFactory.getLogger(SlackApiClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Page size cap. Slack defaults to 100, max 1000. 200 keeps a single
     *  request enough for typical workspaces (~150 joined channels) without
     *  needing pagination on the happy path. */
    private static final int PAGE_LIMIT = 200;

    /**
     * Lists every channel the authenticated user is a member of. Filters
     * to public + private channels (no DMs / mpim). Pages through Slack's
     * cursor when a workspace has more than {@link #PAGE_LIMIT} channels.
     *
     * <p>Throws {@link ResponseStatusException} with the Slack error
     * verbatim on a non-{@code ok} response so the controller surfaces
     * Slack's actual problem (e.g. {@code missing_scope}) rather than a
     * generic 500.
     */
    public List<SlackChannel> listConversations(String userToken)
    {
        ImmutableList.Builder<SlackChannel> all = ImmutableList.builder();
        String cursor = null;
        do {
            JsonNode page = fetchPage(userToken, cursor);
            JsonNode channels = page.path("channels");
            if (channels.isArray()) {
                for (JsonNode channel : channels) {
                    SlackChannel parsed = parseChannel(channel);
                    if (parsed != null) {
                        all.add(parsed);
                    }
                }
            }
            cursor = page.path("response_metadata").path("next_cursor").asText("");
        } while (cursor != null && !cursor.isEmpty());
        return all.build();
    }

    private JsonNode fetchPage(String userToken, String cursor)
    {
        StringBuilder query = new StringBuilder()
                .append("types=public_channel,private_channel")
                // Slack defaults exclude_archived to false; explicitly opt
                // in so the picker doesn't show a wall of long-dead
                // channels next to live ones.
                .append("&exclude_archived=true")
                .append("&limit=").append(PAGE_LIMIT);
        if (cursor != null && !cursor.isEmpty()) {
            query.append("&cursor=").append(URLEncoder.encode(cursor, StandardCharsets.UTF_8));
        }
        // users.conversations is the right endpoint for "channels I'm a
        // member of" — conversations.list returns every channel in the
        // workspace, which would be the wrong default for our picker.
        URI uri = URI.create("https://slack.com/api/users.conversations?" + query);
        return slackGet(uri, userToken, "users.conversations");
    }

    private static SlackChannel parseChannel(JsonNode channel)
    {
        String id = channel.path("id").asText(null);
        String name = channel.path("name").asText(null);
        if (id == null || id.isBlank() || name == null || name.isBlank()) {
            // Defensive: Slack should always populate these on a non-archived
            // channel; if it doesn't, drop the row rather than rendering an
            // unnamed toggle the user can't make sense of.
            return null;
        }
        boolean isPrivate = channel.path("is_private").asBoolean(false)
                || channel.path("is_group").asBoolean(false);
        Integer memberCount = channel.has("num_members") ? channel.path("num_members").asInt() : null;
        Instant latestActivityAt = parseTimestamp(channel.path("latest").path("ts"));
        if (latestActivityAt == null) {
            // Some workspace-wide list responses omit `latest` for channels
            // the user hasn't viewed recently. Fall back to `updated` (set
            // when the channel itself was last modified) so smart-default
            // ordering still has something to work with.
            latestActivityAt = parseTimestampMillis(channel.path("updated"));
        }
        return new SlackChannel(id, name, isPrivate, memberCount, latestActivityAt);
    }

    /**
     * Lists the user's open IM and MPIM conversations. Reuses
     * {@code users.conversations} (not {@code im.list}, which is
     * deprecated). The picker doesn't show these — they're for the
     * polling loop, which needs an up-to-date set of "DMs to fetch
     * history for" on every tick.
     *
     * <p>Returns one {@link SlackDmConversation} per Slack conversation;
     * IMs always carry exactly one peer user id, MPIMs carry the names
     * Slack assembles into {@code mpim_name} but we keep the user-id
     * list for renderer flexibility.
     */
    public List<SlackDmConversation> listImAndMpimConversations(String userToken, String workspaceId)
    {
        ImmutableList.Builder<SlackDmConversation> all = ImmutableList.builder();
        String cursor = null;
        Instant now = Instant.now();
        do {
            JsonNode page = fetchImPage(userToken, cursor);
            JsonNode channels = page.path("channels");
            if (channels.isArray()) {
                for (JsonNode ch : channels) {
                    SlackDmConversation parsed = parseDmConversation(ch, workspaceId, now);
                    if (parsed != null) {
                        all.add(parsed);
                    }
                }
            }
            cursor = page.path("response_metadata").path("next_cursor").asText("");
        } while (cursor != null && !cursor.isEmpty());
        return all.build();
    }

    /**
     * Pages of {@code conversations.history} for one channel since the
     * supplied watermark. {@code oldest} is a Slack ts string —
     * passing the empty string or null fetches whatever the page-limit
     * gives us at the head of the channel.
     *
     * <p>Returns the raw {@code message} JsonNodes in chronological
     * order (oldest first), which is the order the categoriser + store
     * upserts naturally process them.
     */
    public List<JsonNode> getConversationsHistory(String userToken, String channelId, String oldest)
    {
        List<JsonNode> chronological = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = fetchHistoryPage(userToken, channelId, oldest, cursor);
            JsonNode messages = page.path("messages");
            if (messages.isArray()) {
                // Slack returns history newest-first. Prepend each page's
                // newest-first batch so the final list is oldest-first
                // overall.
                List<JsonNode> batch = new ArrayList<>();
                for (JsonNode m : messages) {
                    batch.add(m);
                }
                for (int i = batch.size() - 1; i >= 0; i--) {
                    chronological.add(batch.get(i));
                }
            }
            cursor = page.path("response_metadata").path("next_cursor").asText("");
        } while (cursor != null && !cursor.isEmpty());
        return chronological;
    }

    /**
     * Fetches the parent + every reply for a thread via
     * {@code conversations.replies}. Used when expanding a MENTION
     * inbox item — the inbox view needs the full back-context, not
     * just the message that pinged the user.
     *
     * <p>Returns oldest-first like {@link #getConversationsHistory},
     * including the parent (Slack always echoes it as the first
     * element of the {@code messages} array).
     */
    public List<JsonNode> getConversationsReplies(String userToken, String channelId, String threadTs)
    {
        List<JsonNode> chronological = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = fetchRepliesPage(userToken, channelId, threadTs, cursor);
            JsonNode messages = page.path("messages");
            if (messages.isArray()) {
                for (JsonNode m : messages) {
                    chronological.add(m);
                }
            }
            cursor = page.path("response_metadata").path("next_cursor").asText("");
        } while (cursor != null && !cursor.isEmpty());
        return chronological;
    }

    /**
     * Posts a message via {@code chat.postMessage}. Returns the ts of
     * the new message on success — the inbox-state machine immediately
     * marks the parent thread RESPONDED, so we don't actually need the
     * ts back, but surfacing it keeps the API symmetric with future
     * "edit my reply" flows.
     *
     * <p>{@code threadTs} is optional: pass null to post a top-level
     * message (used by the channel-feed view's compose box later);
     * pass the thread root to reply inline.
     */
    public String postMessage(String userToken, String channelId, String text, String threadTs)
    {
        StringBuilder form = new StringBuilder()
                .append("channel=").append(URLEncoder.encode(channelId, StandardCharsets.UTF_8))
                .append("&text=").append(URLEncoder.encode(text, StandardCharsets.UTF_8));
        if (threadTs != null && !threadTs.isEmpty()) {
            form.append("&thread_ts=").append(URLEncoder.encode(threadTs, StandardCharsets.UTF_8));
        }
        URI uri = URI.create("https://slack.com/api/chat.postMessage");
        JsonNode root = slackPostForm(uri, userToken, form.toString(), "chat.postMessage");
        return root.path("ts").asText(null);
    }

    /**
     * Sweeps {@code search.messages} for the supplied query (typically
     * the user-mention token {@code <@USERID>}). Returns the raw match
     * nodes Slack reports — each carries {@code channel.id} +
     * {@code text} + {@code ts} alongside the usual message shape, so
     * the caller can decide whether to ingest it or skip (e.g. already
     * covered by the followed-channel poll).
     *
     * <p>Pinned to {@code sort=timestamp&sort_dir=desc} so the watermark
     * filter on the calling side sees the freshest matches first, and
     * {@code count=100} — one Tier-2 call per 30-second tick stays
     * comfortably under the 20/min search budget. We do not paginate;
     * if a user accumulates more than 100 fresh mentions in 30 s we
     * accept the head and let the next tick catch the rest.
     *
     * <p>Surfaces the verbatim Slack error on a non-{@code ok} response
     * via {@link ResponseStatusException} like the other endpoints —
     * callers (the polling job) detect {@code missing_scope} from the
     * exception message so users on pre-{@code search:read} tokens get
     * a one-shot warn instead of a per-tick spam.
     */
    public List<JsonNode> searchMessages(String userToken, String query)
    {
        // search.messages caps page size at 100 — narrower than other
        // Slack endpoints' 1000 ceiling, so we use a dedicated literal
        // rather than PAGE_LIMIT.
        String q = "query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&count=100"
                + "&sort=timestamp"
                + "&sort_dir=desc";
        URI uri = URI.create("https://slack.com/api/search.messages?" + q);
        JsonNode root = slackGet(uri, userToken, "search.messages");
        JsonNode matches = root.path("messages").path("matches");
        if (!matches.isArray()) {
            return ImmutableList.of();
        }
        ImmutableList.Builder<JsonNode> out = ImmutableList.builder();
        for (JsonNode m : matches) {
            out.add(m);
        }
        return out.build();
    }

    private JsonNode fetchRepliesPage(String userToken, String channelId, String threadTs, String cursor)
    {
        StringBuilder query = new StringBuilder()
                .append("channel=").append(URLEncoder.encode(channelId, StandardCharsets.UTF_8))
                .append("&ts=").append(URLEncoder.encode(threadTs, StandardCharsets.UTF_8))
                .append("&limit=").append(PAGE_LIMIT);
        if (cursor != null && !cursor.isEmpty()) {
            query.append("&cursor=").append(URLEncoder.encode(cursor, StandardCharsets.UTF_8));
        }
        URI uri = URI.create("https://slack.com/api/conversations.replies?" + query);
        return slackGet(uri, userToken, "conversations.replies");
    }

    private JsonNode fetchImPage(String userToken, String cursor)
    {
        StringBuilder query = new StringBuilder()
                .append("types=im,mpim")
                .append("&exclude_archived=true")
                .append("&limit=").append(PAGE_LIMIT);
        if (cursor != null && !cursor.isEmpty()) {
            query.append("&cursor=").append(URLEncoder.encode(cursor, StandardCharsets.UTF_8));
        }
        URI uri = URI.create("https://slack.com/api/users.conversations?" + query);
        return slackGet(uri, userToken, "users.conversations(im,mpim)");
    }

    private JsonNode fetchHistoryPage(String userToken, String channelId, String oldest, String cursor)
    {
        StringBuilder query = new StringBuilder()
                .append("channel=").append(URLEncoder.encode(channelId, StandardCharsets.UTF_8))
                .append("&limit=").append(PAGE_LIMIT)
                // inclusive=false (the default) is what we want — the
                // watermark IS the message we last ingested, so we only
                // want strictly newer ts values.
                .append("&inclusive=false");
        if (oldest != null && !oldest.isEmpty()) {
            query.append("&oldest=").append(URLEncoder.encode(oldest, StandardCharsets.UTF_8));
        }
        if (cursor != null && !cursor.isEmpty()) {
            query.append("&cursor=").append(URLEncoder.encode(cursor, StandardCharsets.UTF_8));
        }
        URI uri = URI.create("https://slack.com/api/conversations.history?" + query);
        return slackGet(uri, userToken, "conversations.history");
    }

    /** Shared HTTP+error path for Slack POST form-encoded endpoints
     *  (chat.postMessage and friends). Mirrors {@link #slackGet} —
     *  identical Slack-error handling, only the request shape differs. */
    private JsonNode slackPostForm(URI uri, String userToken, String body, String label)
    {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + userToken)
                .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try (HttpClient http = HttpClient.newHttpClient()) {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        catch (IOException e) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(502), "Slack " + label + " I/O failure", e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatusCode.valueOf(502), "Slack " + label + " interrupted", e);
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(response.body());
        }
        catch (IOException e) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(502), "Slack returned non-JSON response", e);
        }
        if (!root.path("ok").asBoolean(false)) {
            String slackError = root.path("error").asText("unknown_error");
            log.warn("Slack {} error: {}", label, slackError);
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(502),
                    "Slack rejected " + label + ": " + slackError);
        }
        return root;
    }

    /** Shared HTTP+error path for Slack GET endpoints; keeps the per-call
     *  methods focused on building the query string. */
    private JsonNode slackGet(URI uri, String userToken, String label)
    {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + userToken)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response;
        try (HttpClient http = HttpClient.newHttpClient()) {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        catch (IOException e) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(502), "Slack " + label + " I/O failure", e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatusCode.valueOf(502), "Slack " + label + " interrupted", e);
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(response.body());
        }
        catch (IOException e) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(502), "Slack returned non-JSON response", e);
        }
        if (!root.path("ok").asBoolean(false)) {
            String slackError = root.path("error").asText("unknown_error");
            log.warn("Slack {} error: {}", label, slackError);
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(502),
                    "Slack rejected " + label + ": " + slackError);
        }
        return root;
    }

    private static SlackDmConversation parseDmConversation(JsonNode channel, String workspaceId, Instant fetchedAt)
    {
        String id = channel.path("id").asText(null);
        if (id == null || id.isBlank()) {
            return null;
        }
        boolean isGroup = channel.path("is_mpim").asBoolean(false);
        ImmutableList.Builder<String> peers = ImmutableList.builder();
        if (isGroup) {
            // Slack populates `members` for MPIMs, but `users.conversations`
            // omits it on the listing endpoint. Parse `mpim_name`
            // (e.g. "mpdm-bob--alice--carol-1") which carries the user
            // handles separated by "--". This is best-effort —
            // SlackPollingService refreshes peer ids opportunistically
            // when it sees full message payloads.
            String mpimName = channel.path("name").asText("");
            if (mpimName.startsWith("mpdm-")) {
                String stripped = mpimName.substring("mpdm-".length());
                int trailingDash = stripped.lastIndexOf('-');
                if (trailingDash >= 0) {
                    stripped = stripped.substring(0, trailingDash);
                }
                for (String handle : stripped.split("--")) {
                    if (!handle.isEmpty()) {
                        peers.add(handle);
                    }
                }
            }
        }
        else {
            String peer = channel.path("user").asText(null);
            if (peer != null && !peer.isBlank()) {
                peers.add(peer);
            }
        }
        String latestTs = channel.path("latest").path("ts").asText(null);
        if (latestTs != null && latestTs.isBlank()) {
            latestTs = null;
        }
        return new SlackDmConversation(workspaceId, id, isGroup, peers.build(), latestTs, fetchedAt);
    }

    /** Slack timestamps are double-formatted strings: "1700000000.123456".
     *  Drop the fractional part and parse the leading seconds. */
    private static Instant parseTimestamp(JsonNode tsNode)
    {
        if (tsNode == null || tsNode.isMissingNode() || tsNode.isNull()) {
            return null;
        }
        String raw = tsNode.asText("");
        if (raw.isBlank()) {
            return null;
        }
        try {
            int dot = raw.indexOf('.');
            long seconds = Long.parseLong(dot >= 0 ? raw.substring(0, dot) : raw);
            return Instant.ofEpochSecond(seconds);
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code updated} is an integer epoch-millis on conversations.list. */
    private static Instant parseTimestampMillis(JsonNode tsNode)
    {
        if (tsNode == null || tsNode.isMissingNode() || tsNode.isNull()) {
            return null;
        }
        long millis = tsNode.asLong(0);
        if (millis <= 0) {
            return null;
        }
        return Instant.ofEpochMilli(millis);
    }

    /** Visible for tests — lets a unit test exercise parseChannel against
     *  a JSON fixture without standing up an HTTP server. Iterates over a
     *  pre-built JsonNode array of channel rows. */
    static List<SlackChannel> parseChannelsForTest(JsonNode channelsArray)
    {
        ImmutableList.Builder<SlackChannel> out = ImmutableList.builder();
        if (channelsArray.isArray()) {
            for (Iterator<JsonNode> it = channelsArray.elements(); it.hasNext(); ) {
                SlackChannel parsed = parseChannel(it.next());
                if (parsed != null) {
                    out.add(parsed);
                }
            }
        }
        return out.build();
    }
}
