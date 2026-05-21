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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.ConvIndexEntry;
import com.bytequay.app.domain.ConvIndexPage;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.repository.ThreadStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Conversation-index reader. Derives a clickable per-prompt index
 * from {@code thread_messages} on demand — the doc explicitly calls
 * out that the index is a <em>view</em>, not a stored table, so it
 * can never drift from the canonical message log.
 *
 * <p>Two read modes:
 * <ul>
 *   <li>{@link #initial} — most-recent window. Picks the last
 *       {@code limit} messages, returns them oldest-first along
 *       with derived index entries plus the thread-wide user-prompt
 *       count for the panel's "N of M" header.</li>
 *   <li>{@link #backfill} — older window prepended on "↑ load
 *       earlier". Same window applies to both the messages and the
 *       index entries so the two views never desync.</li>
 * </ul>
 *
 * <p>An index entry is a {@code user}-role, {@code text}-type row.
 * {@code tool_result} blocks are also persisted under role=user by
 * the CLI parser but they're not prompts the human typed, so the
 * filter is exact on both fields.
 */
@Service
public class ConvIndexService
{
    private static final Logger log = LoggerFactory.getLogger(ConvIndexService.class);

    /** Hard cap so a misconfigured client can't ask for the whole
     *  100k-row history in one go. Matches the panel's intent: it
     *  shows a working window, not the full log. */
    public static final int MAX_LIMIT = 200;

    /** Fallback when the caller passes 0 or a negative limit — picks
     *  the same window size as the controller's documented default
     *  so a bare {@code GET /index} still returns something useful. */
    private static final int FALLBACK_LIMIT = 50;

    /** Preview width that fits the floating panel's ~188 px column
     *  without measurement. Matches the dev doc's 80-char target. */
    private static final int PREVIEW_CHARS = 80;

    private final ThreadStore store;
    private final ObjectMapper mapper;

    public ConvIndexService(ThreadStore store, ObjectMapper mapper)
    {
        this.store = requireNonNull(store, "store is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    /** Initial load: most-recent {@code limit} messages plus the
     *  user-prompt entries derived from them. Total user-prompt
     *  count is thread-wide so the header can show progress beyond
     *  the current window. */
    public ConvIndexPage initial(String threadId, int limit)
    {
        requireNonNull(threadId, "threadId is null");
        int capped = capLimit(limit);
        List<ThreadMessage> window = store.listRecentMessages(threadId, capped);
        long total = store.countUserMessages(threadId);
        return buildPage(threadId, total, window);
    }

    /** Backfill load triggered by "↑ load earlier". Returns the
     *  {@code limit} messages whose seq is strictly less than
     *  {@code beforeSeq}, oldest-first, plus the matching index
     *  entries. {@code totalUserMessages} mirrors {@link #initial}
     *  so the header stays consistent across paging. */
    public ConvIndexPage backfill(String threadId, long beforeSeq, int limit)
    {
        requireNonNull(threadId, "threadId is null");
        int capped = capLimit(limit);
        List<ThreadMessage> window = store.listMessagesBefore(threadId, beforeSeq, capped);
        long total = store.countUserMessages(threadId);
        return buildPage(threadId, total, window);
    }

    private ConvIndexPage buildPage(String threadId, long total, List<ThreadMessage> window)
    {
        ImmutableList.Builder<ConvIndexEntry> entries = ImmutableList.builder();
        for (ThreadMessage m : window) {
            if (isUserPrompt(m)) {
                entries.add(toEntry(m));
            }
        }
        Long loadedFromSeq = window.isEmpty() ? null : window.get(0).seq();
        // nextCursor mirrors loadedFromSeq when there's older data
        // to fetch — i.e. the loaded window does not start at the
        // thread's seq=1 row. A brand-new thread that returns 0 rows
        // hides the "load earlier" affordance entirely.
        Long nextCursor = loadedFromSeq != null && loadedFromSeq > 1L ? loadedFromSeq : null;
        return new ConvIndexPage(
                threadId,
                total,
                entries.build(),
                List.copyOf(window),
                loadedFromSeq,
                nextCursor);
    }

    private static boolean isUserPrompt(ThreadMessage m)
    {
        return "user".equals(m.role()) && "text".equals(m.type());
    }

    private ConvIndexEntry toEntry(ThreadMessage m)
    {
        String text = extractText(m.contentJson());
        return new ConvIndexEntry(m.seq(), summarise(text), m.ts().toEpochMilli());
    }

    /** Parse {@code {"text":"…"}} (the shape ClaudeCodeCliThreadAgent
     *  uses for user messages) and return the contained string. We
     *  fall back to the raw JSON on a parse failure so the preview
     *  still shows something — the doc's bias is "always show a
     *  preview, never silently drop a row". */
    private String extractText(String contentJson)
    {
        if (contentJson == null || contentJson.isBlank()) {
            return "";
        }
        try {
            JsonNode node = mapper.readTree(contentJson);
            JsonNode text = node.path("text");
            if (text.isTextual()) {
                return text.asText();
            }
        }
        catch (Exception e) {
            log.debug("ConvIndex: failed to parse contentJson for preview, "
                    + "falling back to raw form: {}", e.getMessage());
        }
        return contentJson;
    }

    /** Whitespace-collapse + first-line + ellipsis. Lifted from the
     *  doc spec verbatim. */
    static String summarise(String raw)
    {
        if (raw == null) {
            return "";
        }
        // First non-empty line — multi-line prompts get reduced so
        // the panel column stays single-row.
        String firstLine = "";
        for (String line : raw.split("\\r?\\n", -1)) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                firstLine = trimmed;
                break;
            }
        }
        if (firstLine.isEmpty()) {
            return "";
        }
        // Collapse runs of internal whitespace to single spaces so a
        // tab-padded prompt doesn't read as a sparse preview.
        String collapsed = firstLine.replaceAll("\\s+", " ");
        if (collapsed.length() <= PREVIEW_CHARS) {
            return collapsed;
        }
        return collapsed.substring(0, PREVIEW_CHARS - 1) + "…";
    }

    private static int capLimit(int limit)
    {
        if (limit <= 0) {
            return FALLBACK_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
