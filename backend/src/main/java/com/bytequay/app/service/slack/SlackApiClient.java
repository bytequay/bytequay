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
            throw new ResponseStatusException(HttpStatusCode.valueOf(502), "Slack channels endpoint I/O failure", e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatusCode.valueOf(502), "Slack channels endpoint interrupted", e);
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
            log.warn("Slack users.conversations error: {}", slackError);
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(502),
                    "Slack rejected channels list: " + slackError);
        }
        return root;
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
