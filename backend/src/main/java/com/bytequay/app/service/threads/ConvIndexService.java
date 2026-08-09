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

import com.bytequay.app.developmentflow.trunk.ThreadTurnProjection;
import com.bytequay.app.domain.ConvIndexEntry;
import com.bytequay.app.domain.ConvIndexPage;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.repository.ThreadStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Conversation-index reader. Derives a clickable per-prompt index from the
 * physical conversation ledgers on demand — the index is a <em>view</em>, not
 * a stored table, so it cannot drift from the canonical message log. A
 * promoted Trunk reads retained LEGACY messages followed by typed ThreadTurn
 * messages without rewriting either ledger.
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
    private static final long MAX_SAFE_JSON_INTEGER = 9_007_199_254_740_991L;

    private final ThreadStore store;
    private final ObjectMapper mapper;
    private final ThreadTurnProjection threadTurns;

    public ConvIndexService(
            ThreadStore store,
            ObjectMapper mapper,
            ThreadTurnProjection threadTurns)
    {
        this.store = requireNonNull(store, "store is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.threadTurns = requireNonNull(threadTurns, "threadTurns is null");
    }

    /** Initial load: most-recent {@code limit} messages plus the
     *  user-prompt entries derived from them. Total user-prompt
     *  count is thread-wide so the header can show progress beyond
     *  the current window. */
    public ConvIndexPage initial(String threadId, int limit)
    {
        requireNonNull(threadId, "threadId is null");
        int capped = capLimit(limit);
        if (isV2Trunk(threadId)) {
            return initialV2(threadId, capped);
        }
        // Window the last N user *prompts*, not the last N messages. A
        // single busy turn emits dozens of tool / assistant rows, so a
        // message-based window would leave only the most recent prompt
        // visible and bury every earlier one behind "load earlier".
        List<ThreadMessage> prompts = store.listRecentUserMessages(threadId, capped);
        long total = store.countUserMessages(threadId);
        // The terminal renders the *full* transcript for those prompts —
        // every assistant / tool / thinking row from the earliest prompt
        // in view through the live tail. Windowing the prompts but then
        // shipping only the prompt rows as {@code messages} would show
        // the questions and silently drop every answer.
        List<ThreadMessage> transcript = transcriptFrom(threadId, prompts, Long.MAX_VALUE);
        return buildPage(
                threadId, total, prompts, transcript,
                total > prompts.size());
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
        if (isV2Trunk(threadId)) {
            return backfillV2(threadId, beforeSeq, capped);
        }
        List<ThreadMessage> prompts = store.listUserMessagesBefore(threadId, beforeSeq, capped);
        long total = store.countUserMessages(threadId);
        // Older transcript window: from the earliest prompt now in view up
        // to (but not including) the prompt the caller paged back from, so
        // the prepended rows carry their answers, not just the questions.
        List<ThreadMessage> transcript = transcriptFrom(threadId, prompts, beforeSeq - 1);
        boolean hasOlder = !prompts.isEmpty()
                && !store.listUserMessagesBefore(
                        threadId, prompts.getFirst().seq(), 1).isEmpty();
        return buildPage(threadId, total, prompts, transcript, hasOlder);
    }

    /** A promoted Trunk has two independent physical ledgers. LEGACY rows keep
     *  their positive seq; typed rows are projected with a negative durable
     *  Trunk version. Source concatenation is therefore the canonical order:
     *  all retained LEGACY history, then typed conversation history. */
    private ConvIndexPage initialV2(String threadId, int limit)
    {
        List<ThreadMessage> history = v2History(threadId);
        List<Integer> positions = userPromptPositions(history);
        int from = Math.max(0, positions.size() - limit);
        List<ThreadMessage> prompts = messagesAt(history, positions, from, positions.size());
        List<ThreadMessage> transcript = prompts.isEmpty()
                ? List.of()
                : List.copyOf(history.subList(positions.get(from), history.size()));
        return buildPage(
                threadId, positions.size(), prompts, transcript, from > 0);
    }

    private ConvIndexPage backfillV2(
            String threadId, long beforeSeq, int limit)
    {
        List<ThreadMessage> history = v2History(threadId);
        int cursor = messagePosition(history, beforeSeq);
        List<Integer> positions = userPromptPositions(history);
        int promptsBefore = 0;
        while (promptsBefore < positions.size()
                && positions.get(promptsBefore) < cursor) {
            promptsBefore++;
        }
        int from = Math.max(0, promptsBefore - limit);
        List<ThreadMessage> prompts = messagesAt(
                history, positions, from, promptsBefore);
        List<ThreadMessage> transcript = prompts.isEmpty()
                ? List.of()
                : List.copyOf(history.subList(positions.get(from), cursor));
        return buildPage(
                threadId, positions.size(), prompts, transcript, from > 0);
    }

    private List<ThreadMessage> v2History(String threadId)
    {
        List<ThreadMessage> legacy = store.listMessages(threadId);
        List<ThreadMessage> typed = threadTurns.history(threadId);
        List<ThreadMessage> history = new ArrayList<>(legacy.size() + typed.size());
        Set<Long> seen = new HashSet<>();
        for (ThreadMessage message : legacy) {
            if (!isRetainedTrunkMessage(message)) {
                continue;
            }
            if (message.seq() <= 0
                    || message.seq() > MAX_SAFE_JSON_INTEGER
                    || !seen.add(message.seq())) {
                throw new IllegalStateException(
                        "LEGACY conversation seq is not unique, positive, and JSON-safe: %s"
                                .formatted(message.seq()));
            }
            history.add(message);
        }
        for (ThreadMessage message : typed) {
            if (message.seq() >= 0 || !seen.add(message.seq())) {
                throw new IllegalStateException(
                        "typed conversation seq is not unique and negative: %s"
                                .formatted(message.seq()));
            }
            history.add(message);
        }
        return List.copyOf(history);
    }

    private static boolean isRetainedTrunkMessage(ThreadMessage message)
    {
        boolean trunkScope = message.scope() == ThreadScope.TRUNK
                || message.scope() == null;
        return trunkScope
                && message.taskId() == null
                && message.stageId() == null;
    }

    private boolean isV2Trunk(String threadId)
    {
        return store.findTurnVersion(threadId).filter("V2"::equals).isPresent();
    }

    private static List<Integer> userPromptPositions(
            List<ThreadMessage> history)
    {
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            if (isUserPrompt(history.get(i))) {
                positions.add(i);
            }
        }
        return List.copyOf(positions);
    }

    private static List<ThreadMessage> messagesAt(
            List<ThreadMessage> history,
            List<Integer> positions,
            int from,
            int to)
    {
        List<ThreadMessage> messages = new ArrayList<>(to - from);
        for (int i = from; i < to; i++) {
            messages.add(history.get(positions.get(i)));
        }
        return List.copyOf(messages);
    }

    private static int messagePosition(
            List<ThreadMessage> history, long seq)
    {
        for (int i = 0; i < history.size(); i++) {
            if (history.get(i).seq() == seq) {
                return i;
            }
        }
        throw new IllegalArgumentException(
                "conversation cursor is not present: %s".formatted(seq));
    }

    /** The transcript rows that pair with a prompt window: every message
     *  from the earliest prompt's seq through {@code lastSeq} (inclusive).
     *  Empty when the prompt window is empty — a thread with no human
     *  prompts yet has nothing to render. */
    private List<ThreadMessage> transcriptFrom(String threadId, List<ThreadMessage> prompts, long lastSeq)
    {
        if (prompts.isEmpty()) {
            return List.of();
        }
        return store.listMessagesBetween(threadId, prompts.get(0).seq(), lastSeq);
    }

    private ConvIndexPage buildPage(
            String threadId,
            long total,
            List<ThreadMessage> prompts,
            List<ThreadMessage> transcript,
            boolean hasOlder)
    {
        ImmutableList.Builder<ConvIndexEntry> entries = ImmutableList.builder();
        for (ThreadMessage m : prompts) {
            // The prompt window is already user-prompt-only, but keep the
            // guard so a future caller passing a raw window can't slip
            // tool_result rows into the index.
            if (isUserPrompt(m)) {
                entries.add(toEntry(m));
            }
        }
        Long loadedFromSeq = prompts.isEmpty() ? null : prompts.get(0).seq();
        Long nextCursor = loadedFromSeq != null && hasOlder
                ? loadedFromSeq : null;
        return new ConvIndexPage(
                threadId,
                total,
                entries.build(),
                List.copyOf(transcript),
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

    /** Parse {@code {"text":"…"}} (the shape Claude Code
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
