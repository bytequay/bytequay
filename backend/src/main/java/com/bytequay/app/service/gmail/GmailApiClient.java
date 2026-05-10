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
package com.bytequay.app.service.gmail;

import com.bytequay.app.domain.EmailMessageMeta;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;

/**
 * Thin REST wrapper around Gmail's HTTP API. Stateless — every method
 * takes the access token explicitly so the caller can hand it off
 * however it wants (per-request, per-account, etc.).
 *
 * <p>API docs: https://developers.google.com/gmail/api/reference/rest
 */
@Component
public class GmailApiClient
{
    private static final String API_BASE = "https://gmail.googleapis.com/gmail/v1/users/me";
    private static final Logger log = LoggerFactory.getLogger(GmailApiClient.class);

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    /**
     * Returns just the message IDs for the inbox, newest first.
     * Body is two requests away — list returns IDs, then each ID
     * needs a separate {@link #getMessageMetadata(String, String)}
     * call. This split is by design on Google's side: it lets
     * pagination be cheap.
     */
    public List<String> listInboxIds(String accessToken, int pageSize)
    {
        if (pageSize <= 0 || pageSize > 500) {
            throw new IllegalArgumentException("pageSize must be in [1, 500], got " + pageSize);
        }
        URI uri = URI.create(API_BASE + "/messages?labelIds=INBOX&maxResults=" + pageSize);
        JsonNode body = doGet(accessToken, uri);
        List<String> ids = new ArrayList<>();
        for (JsonNode m : body.path("messages")) {
            String id = m.path("id").asText(null);
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    /**
     * Fetches the metadata for one message — From / Subject / Date /
     * snippet / labels — without body. Cheap; one quota unit.
     */
    public EmailMessageMeta getMessageMetadata(String accessToken, String messageId)
    {
        URI uri = URI.create(API_BASE + "/messages/" + url(messageId)
                + "?format=metadata"
                + "&metadataHeaders=From"
                + "&metadataHeaders=Subject"
                + "&metadataHeaders=Date");
        JsonNode body = doGet(accessToken, uri);
        return toMeta(body);
    }

    private EmailMessageMeta toMeta(JsonNode body)
    {
        String id = body.path("id").asText(null);
        String threadId = body.path("threadId").asText(null);
        String snippet = body.path("snippet").asText("");
        String from = "";
        String subject = "";
        for (JsonNode header : body.path("payload").path("headers")) {
            String name = header.path("name").asText("");
            if ("From".equalsIgnoreCase(name)) {
                from = header.path("value").asText("");
            }
            else if ("Subject".equalsIgnoreCase(name)) {
                subject = header.path("value").asText("");
            }
        }
        long internalDateMs = body.path("internalDate").asLong(0L);
        Instant receivedAt = internalDateMs > 0 ? Instant.ofEpochMilli(internalDateMs) : Instant.EPOCH;
        boolean unread = false;
        for (JsonNode label : body.path("labelIds")) {
            if ("UNREAD".equals(label.asText(""))) {
                unread = true;
                break;
            }
        }
        return new EmailMessageMeta(id, threadId, from, subject, snippet, receivedAt, unread);
    }

    private JsonNode doGet(String accessToken, URI uri)
    {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(uri)
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> resp;
        try {
            resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        }
        catch (IOException e) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(502),
                    "Gmail API call failed: " + e.getMessage(), e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatusCode.valueOf(502),
                    "Gmail API call interrupted", e);
        }
        if (resp.statusCode() == 401) {
            // Bubble up so the caller can invalidate the cached access
            // token and retry once. We don't auto-retry here to avoid
            // infinite loops on a genuinely-revoked refresh token.
            throw new ResponseStatusException(HttpStatusCode.valueOf(401),
                    "Gmail API returned 401: " + resp.body());
        }
        if (resp.statusCode() / 100 != 2) {
            log.warn("Gmail API {} returned {}: {}", uri.getPath(), resp.statusCode(), resp.body());
            throw new ResponseStatusException(HttpStatusCode.valueOf(502),
                    "Gmail API returned " + resp.statusCode() + " on " + uri.getPath()
                            + ": " + resp.body());
        }
        try {
            return json.readTree(resp.body());
        }
        catch (IOException e) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(502),
                    "Gmail API returned non-JSON: " + resp.body(), e);
        }
    }

    private static String url(String s)
    {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
