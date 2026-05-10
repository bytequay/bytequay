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

import com.bytequay.app.domain.EmailMessageDetail;
import com.bytequay.app.domain.EmailMessageMeta;
import com.bytequay.app.domain.EmailThreadDetail;
import com.bytequay.app.domain.EmailThreadMeta;
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
import java.util.Base64;
import java.util.Comparator;
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

    /**
     * Fetches the full message including body. The renderer uses this
     * when the user opens a message in the preview pane. Walks the
     * MIME tree to extract the first {@code text/plain} and
     * {@code text/html} parts (multipart/alternative emails carry
     * both; either may be present alone).
     */
    public EmailMessageDetail getMessageFull(String accessToken, String messageId)
    {
        URI uri = URI.create(API_BASE + "/messages/" + url(messageId) + "?format=full");
        JsonNode body = doGet(accessToken, uri);
        return toDetail(body);
    }

    /**
     * Modifies a message's labels — used for archive (remove INBOX),
     * mark-read (remove UNREAD), mark-unread (add UNREAD). Either
     * argument may be empty; both empty is a no-op on Google's side
     * but we still spend a quota unit.
     */
    public void modifyMessage(
            String accessToken,
            String messageId,
            List<String> addLabelIds,
            List<String> removeLabelIds)
    {
        String json = "{"
                + "\"addLabelIds\":" + jsonStringArray(addLabelIds)
                + ","
                + "\"removeLabelIds\":" + jsonStringArray(removeLabelIds)
                + "}";
        URI uri = URI.create(API_BASE + "/messages/" + url(messageId) + "/modify");
        doPostJson(accessToken, uri, json);
    }

    /* ── Threads ─────────────────────────────────────────────────── */

    /**
     * Returns thread IDs for the inbox, newest first. One round trip;
     * each thread carries multiple messages that we lazy-fetch.
     */
    public List<String> listInboxThreadIds(String accessToken, int pageSize)
    {
        if (pageSize <= 0 || pageSize > 500) {
            throw new IllegalArgumentException("pageSize must be in [1, 500], got " + pageSize);
        }
        URI uri = URI.create(API_BASE + "/threads?labelIds=INBOX&maxResults=" + pageSize);
        JsonNode body = doGet(accessToken, uri);
        List<String> ids = new ArrayList<>();
        for (JsonNode t : body.path("threads")) {
            String id = t.path("id").asText(null);
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    /**
     * Fetches metadata for one thread — enough for the inbox list
     * card. Picks the latest message in the thread to represent the
     * row, counts total messages, and reports unread if any message
     * carries the UNREAD label (matches Gmail's bold-or-not row
     * semantics).
     */
    public EmailThreadMeta getThreadMetadata(String accessToken, String threadId)
    {
        URI uri = URI.create(API_BASE + "/threads/" + url(threadId)
                + "?format=metadata"
                + "&metadataHeaders=From"
                + "&metadataHeaders=Subject"
                + "&metadataHeaders=Date");
        JsonNode body = doGet(accessToken, uri);
        return toThreadMeta(body);
    }

    /** Fetches the full thread (every message, with body) for the
     *  detail pane. */
    public EmailThreadDetail getThreadFull(String accessToken, String threadId)
    {
        URI uri = URI.create(API_BASE + "/threads/" + url(threadId) + "?format=full");
        JsonNode body = doGet(accessToken, uri);
        return toThreadDetail(body);
    }

    /**
     * Modifies labels on every message in a thread atomically. Gmail
     * itself uses this when you archive or mark-read from its UI —
     * applying to the thread keeps the per-message and per-thread
     * states consistent.
     */
    public void modifyThread(
            String accessToken,
            String threadId,
            List<String> addLabelIds,
            List<String> removeLabelIds)
    {
        String json = "{"
                + "\"addLabelIds\":" + jsonStringArray(addLabelIds)
                + ","
                + "\"removeLabelIds\":" + jsonStringArray(removeLabelIds)
                + "}";
        URI uri = URI.create(API_BASE + "/threads/" + url(threadId) + "/modify");
        doPostJson(accessToken, uri, json);
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

    private EmailMessageDetail toDetail(JsonNode body)
    {
        String id = body.path("id").asText(null);
        String threadId = body.path("threadId").asText(null);
        String from = "";
        String to = "";
        String cc = "";
        String subject = "";
        for (JsonNode header : body.path("payload").path("headers")) {
            String name = header.path("name").asText("");
            String value = header.path("value").asText("");
            if ("From".equalsIgnoreCase(name)) {
                from = value;
            }
            else if ("To".equalsIgnoreCase(name)) {
                to = value;
            }
            else if ("Cc".equalsIgnoreCase(name)) {
                cc = value;
            }
            else if ("Subject".equalsIgnoreCase(name)) {
                subject = value;
            }
        }
        long internalDateMs = body.path("internalDate").asLong(0L);
        Instant receivedAt = internalDateMs > 0 ? Instant.ofEpochMilli(internalDateMs) : Instant.EPOCH;
        List<String> labels = new ArrayList<>();
        boolean unread = false;
        for (JsonNode label : body.path("labelIds")) {
            String l = label.asText("");
            labels.add(l);
            if ("UNREAD".equals(l)) {
                unread = true;
            }
        }
        // Walk the MIME tree depth-first, collecting the first text/plain
        // and the first text/html we find. Multipart/alternative emails
        // carry both; older plain-text emails carry just text/plain.
        BodyAccumulator acc = new BodyAccumulator();
        collectBody(body.path("payload"), acc);
        return new EmailMessageDetail(
                id, threadId, from, to, cc, subject, receivedAt, unread,
                List.copyOf(labels), acc.text, acc.html);
    }

    private EmailThreadMeta toThreadMeta(JsonNode body)
    {
        String threadId = body.path("id").asText(null);
        JsonNode messages = body.path("messages");
        if (!messages.isArray() || messages.size() == 0) {
            // Empty thread shouldn't happen but guard anyway — return a
            // skeleton row the renderer can show without crashing.
            return new EmailThreadMeta(threadId, null, "", "", "", Instant.EPOCH, false, 0);
        }
        // Latest = highest internalDate. Gmail's API returns messages
        // in insertion order which is *usually* chronological but
        // not contractually so — sort defensively.
        EmailMessageMeta latest = null;
        boolean anyUnread = false;
        for (JsonNode msg : messages) {
            EmailMessageMeta m = toMeta(msg);
            if (m.unread()) {
                anyUnread = true;
            }
            if (latest == null || m.receivedAt().isAfter(latest.receivedAt())) {
                latest = m;
            }
        }
        return new EmailThreadMeta(
                threadId,
                latest.id(),
                latest.from(),
                latest.subject(),
                latest.snippet(),
                latest.receivedAt(),
                anyUnread,
                messages.size());
    }

    private EmailThreadDetail toThreadDetail(JsonNode body)
    {
        String threadId = body.path("id").asText(null);
        JsonNode messages = body.path("messages");
        List<EmailMessageDetail> out = new ArrayList<>();
        String subject = "";
        if (messages.isArray()) {
            for (JsonNode msg : messages) {
                EmailMessageDetail detail = toDetail(msg);
                out.add(detail);
                // Keep the first non-blank subject as the thread subject.
                // Gmail repeats "Re:" prefixes per message; the original
                // is the most useful header for a thread title.
                if (subject.isEmpty() && !detail.subject().isEmpty()) {
                    subject = detail.subject();
                }
            }
        }
        // Oldest first so the renderer can scan a thread top-down like
        // a conversation transcript.
        out.sort(Comparator.comparing(EmailMessageDetail::receivedAt));
        // linkedRefs left empty here — EmailService runs the detector
        // and replaces this thread with an enriched copy.
        return new EmailThreadDetail(threadId, subject, List.copyOf(out), List.of());
    }

    private static final class BodyAccumulator
    {
        String text;
        String html;
    }

    private void collectBody(JsonNode part, BodyAccumulator acc)
    {
        if (part == null || part.isMissingNode()) {
            return;
        }
        String mimeType = part.path("mimeType").asText("");
        JsonNode parts = part.path("parts");
        if (parts.isArray() && parts.size() > 0) {
            for (JsonNode child : parts) {
                collectBody(child, acc);
            }
            return;
        }
        // Leaf part — record the body if we don't already have one of
        // this type and it's a body type we care about.
        String data = part.path("body").path("data").asText("");
        if (data.isEmpty()) {
            return;
        }
        String decoded = decodeBase64Url(data);
        if ("text/plain".equalsIgnoreCase(mimeType) && acc.text == null) {
            acc.text = decoded;
        }
        else if ("text/html".equalsIgnoreCase(mimeType) && acc.html == null) {
            acc.html = decoded;
        }
    }

    private static String decodeBase64Url(String input)
    {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(input);
            return new String(bytes, StandardCharsets.UTF_8);
        }
        catch (IllegalArgumentException e) {
            log.warn("Couldn't decode Gmail body part as base64url: {}", e.getMessage());
            return "";
        }
    }

    private static String jsonStringArray(List<String> items)
    {
        if (items == null || items.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String s : items) {
            if (!first) {
                sb.append(',');
            }
            sb.append('"').append(s.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            first = false;
        }
        sb.append(']');
        return sb.toString();
    }

    private JsonNode doGet(String accessToken, URI uri)
    {
        return send(accessToken, HttpRequest.newBuilder()
                .uri(uri)
                .header("Accept", "application/json")
                .GET());
    }

    private JsonNode doPostJson(String accessToken, URI uri, String jsonBody)
    {
        return send(accessToken, HttpRequest.newBuilder()
                .uri(uri)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody)));
    }

    /** Max retry attempts for transient failures (429 / 503). 1 + 2 retries
     *  is enough for the brief concurrency-burst spikes Gmail throws when
     *  we open the inbox; beyond that the user gets a real error. */
    private static final int MAX_RETRIES = 2;
    /** Base backoff in millis. Doubled per attempt (200, 400). */
    private static final long BACKOFF_BASE_MS = 200L;

    private JsonNode send(String accessToken, HttpRequest.Builder builder)
    {
        HttpRequest req = builder
                .header("Authorization", "Bearer " + accessToken)
                .build();
        HttpResponse<String> resp = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
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
            int sc = resp.statusCode();
            // Retry on rate-limit / transient-server-error responses; the
            // per-user concurrency cap on Gmail throws a lot of 429s when
            // we burst metadata fetches at the start of an inbox load.
            if ((sc == 429 || sc == 503) && attempt < MAX_RETRIES) {
                long delayMs = backoffMs(resp, attempt);
                log.debug("Gmail {} returned {} (attempt {}/{}); retrying in {}ms",
                        req.uri().getPath(), sc, attempt + 1, MAX_RETRIES + 1, delayMs);
                try {
                    Thread.sleep(delayMs);
                }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new ResponseStatusException(HttpStatusCode.valueOf(502),
                            "Gmail API retry interrupted", ie);
                }
                continue;
            }
            break;
        }
        if (resp.statusCode() == 401) {
            // Bubble up so the caller can invalidate the cached access
            // token and retry once. We don't auto-retry here to avoid
            // infinite loops on a genuinely-revoked refresh token.
            throw new ResponseStatusException(HttpStatusCode.valueOf(401),
                    "Gmail API returned 401: " + resp.body());
        }
        if (resp.statusCode() / 100 != 2) {
            log.warn("Gmail API {} returned {}: {}", req.uri().getPath(), resp.statusCode(), resp.body());
            throw new ResponseStatusException(HttpStatusCode.valueOf(502),
                    "Gmail API returned " + resp.statusCode() + " on " + req.uri().getPath()
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

    /** Backoff selection: prefer Retry-After header when Google sends one,
     *  otherwise exponential from {@link #BACKOFF_BASE_MS}. */
    private static long backoffMs(HttpResponse<?> resp, int attempt)
    {
        return resp.headers().firstValue("Retry-After")
                .map(GmailApiClient::parseRetryAfterMs)
                .orElse(BACKOFF_BASE_MS << attempt);
    }

    private static long parseRetryAfterMs(String header)
    {
        try {
            // Retry-After can be seconds (integer) or an HTTP-date. We
            // only handle the seconds form — the date form is rare here.
            return Long.parseLong(header.trim()) * 1000L;
        }
        catch (NumberFormatException e) {
            return BACKOFF_BASE_MS;
        }
    }

    private static String url(String s)
    {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
