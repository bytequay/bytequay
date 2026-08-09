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
package com.bytequay.app.service.workspaces;

import com.bytequay.app.domain.MemoryItemConfidence;
import com.bytequay.app.domain.MemoryItemKind;
import com.bytequay.app.domain.MemoryItemOrigin;
import com.bytequay.app.domain.MemoryItemScopeKind;
import com.bytequay.app.domain.MemoryItemSource;
import com.bytequay.app.repository.sqlite.SqliteMemoryItemStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the existing distiller's {@code proposedMd} blob into a
 * sequence of typed {@link SqliteMemoryItemStore.NewItem} rows so Phase A's
 * memory_item table is populated whenever a blob proposal lands.
 * v1 lives next to the blob path; once the UI and tools have
 * migrated, the blob output becomes the human-readable digest and
 * the typed rows become the source of truth.
 *
 * <h3>Section → kind map</h3>
 *
 * The summariser's system prompt already groups bullets under H2
 * sections like {@code ## Decisions}. We honour that exact heading
 * set (case-insensitive, common synonyms accepted) and skip bullets
 * under any heading we don't recognise.
 *
 * <h3>Provenance</h3>
 *
 * Phase E (provenance everywhere) makes this strict: the
 * summariser is instructed to append a {@code [thread:id]} back-link
 * to each bullet it promotes from a thread Overall, and any bullet
 * without one is <em>dropped</em> here rather than persisted with a
 * synthetic source. Recall and citations are only useful if the
 * jump target is real; a placeholder source defeats the axis.
 */
@Component
public class WorkspaceMemoryProposalParser
{
    private static final Logger log = LoggerFactory.getLogger(WorkspaceMemoryProposalParser.class);

    /** Heading → kind. Lookup is case-insensitive on the trimmed
     *  heading text. Synonyms appear here so a slightly different
     *  prompt output ("Decisions made" vs "Decisions") still maps. */
    private static final Map<String, MemoryItemKind> HEADING_TO_KIND = Map.ofEntries(
            Map.entry("decisions", MemoryItemKind.DECISION),
            Map.entry("decisions made", MemoryItemKind.DECISION),
            Map.entry("blockers", MemoryItemKind.BLOCKER),
            Map.entry("open blockers", MemoryItemKind.BLOCKER),
            Map.entry("conventions", MemoryItemKind.CONVENTION),
            Map.entry("code conventions", MemoryItemKind.CONVENTION),
            Map.entry("active focus", MemoryItemKind.FOCUS_SHIFT),
            Map.entry("current focus", MemoryItemKind.FOCUS_SHIFT),
            Map.entry("focus", MemoryItemKind.FOCUS_SHIFT),
            Map.entry("open questions", MemoryItemKind.OPEN_QUESTION),
            Map.entry("recurring patterns", MemoryItemKind.RECURRING_PATTERN));

    /** Matches a {@code [thread:id]} back-link marker the summariser
     *  appends. Captures the id so we can build a real source. */
    private static final Pattern BACKLINK = Pattern.compile("\\[thread:([A-Za-z0-9._-]+)\\]");

    /**
     * Parse {@code proposedMd} into a sequence of typed item drafts
     * scoped to {@code workspaceId}. Each H2 section we recognise
     * contributes one row per bullet; bullets under unrecognised
     * sections are dropped silently so a new heading the summariser
     * starts emitting doesn't crash the pipeline.
     */
    public List<SqliteMemoryItemStore.NewItem> parse(String workspaceId, String proposedMd)
    {
        if (proposedMd == null || proposedMd.isBlank() || workspaceId == null) {
            return List.of();
        }
        List<SqliteMemoryItemStore.NewItem> out = new ArrayList<>();
        MemoryItemKind currentKind = null;
        for (String raw : proposedMd.split("\\R", -1)) {
            String line = raw.stripTrailing();
            if (line.startsWith("## ")) {
                currentKind = HEADING_TO_KIND.get(
                        line.substring(3).trim().toLowerCase(Locale.ROOT));
                continue;
            }
            if (currentKind == null) {
                continue;
            }
            if (!line.startsWith("- ") && !line.startsWith("* ")) {
                continue;
            }
            String bullet = line.substring(2).trim();
            if (bullet.isEmpty()) {
                continue;
            }
            List<MemoryItemSource> sources = extractSources(bullet);
            if (sources.isEmpty()) {
                // Phase E: drop bullets without real provenance. The
                // upstream summariser is expected to annotate every
                // promoted bullet; an un-annotated row would persist
                // an unverifiable claim, which defeats recall.
                log.debug("Dropping bullet without back-link from workspace {} distill: {}",
                        workspaceId, bullet);
                continue;
            }
            // Confidence: lacking better signal, default MEDIUM —
            // the distiller produces educated guesses, not facts.
            // FOCUS_SHIFT skews HIGH because it's the most volatile
            // and "what are we doing right now" is usually crisp.
            MemoryItemConfidence confidence = currentKind == MemoryItemKind.FOCUS_SHIFT
                    ? MemoryItemConfidence.HIGH
                    : MemoryItemConfidence.MEDIUM;
            out.add(new SqliteMemoryItemStore.NewItem(
                    MemoryItemScopeKind.WORKSPACE,
                    workspaceId,
                    currentKind,
                    stripBacklink(bullet),
                    sources,
                    confidence,
                    List.of(),
                    MemoryItemOrigin.DISTILL));
        }
        return out;
    }

    private static List<MemoryItemSource> extractSources(String bullet)
    {
        List<MemoryItemSource> sources = new ArrayList<>();
        Matcher m = BACKLINK.matcher(bullet);
        while (m.find()) {
            sources.add(MemoryItemSource.thread(m.group(1)));
        }
        return sources;
    }

    /** Drop the trailing {@code [thread:id]} markers from the
     *  bullet's user-visible text — the marker's meaning is carried
     *  by the {@code sources} list, not the text. */
    private static String stripBacklink(String bullet)
    {
        return BACKLINK.matcher(bullet).replaceAll("").trim();
    }
}
