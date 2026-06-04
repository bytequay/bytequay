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
package com.bytequay.app.service.concepts;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parser for the user-extensible vocabulary section of a brain
 * {@code .md} file (the workspace {@code memoryMd}, or the repo
 * {@code REPO.md} analog). Recognises a {@code ## Glossary} H2
 * section whose direct children are H3 entries naming a concept:
 *
 * <pre>
 *   ## Glossary
 *
 *   ### urgent
 *   PRs that need to be looked at first — CI failing, conflict,
 *   author waiting on me, or stale &gt; 7 days.
 *   *aka:* needs-attention-now
 *
 *   ### shippable
 *   A PR that's ready to merge — green CI, no unresolved threads,
 *   reviewer approval.
 * </pre>
 *
 * <p>Parses a flat sequence of {@link Entry} records — turning them
 * into {@link ConceptSpec}s with a scope and source URI is the
 * caller's job (workspace path vs repo path).
 *
 * <p>Conventions:
 * <ul>
 *   <li>The {@code ## Glossary} heading is case-insensitive.</li>
 *   <li>Lines starting with {@code *aka:} (or {@code _aka:_}) inside
 *       an entry become the entry's aliases.</li>
 *   <li>An H1 / H2 (other than another {@code ## Glossary}) ends
 *       the section.</li>
 *   <li>Empty or whitespace-only definitions skip the entry; an
 *       entry with no body isn't useful and would just register a
 *       definition-less concept.</li>
 * </ul>
 */
@Component
public class WorkspaceGlossaryParser
{
    private static final String GLOSSARY_HEADING = "glossary";

    /** Parsed glossary entry — owns just the data; the caller
     *  decides what {@link ConceptScope} and source URI to stamp. */
    public record Entry(String name, String definition, List<String> aka) {}

    /**
     * Returns the entries under the first {@code ## Glossary}
     * heading found in {@code body}, in document order. Empty if
     * the body is blank, has no glossary section, or the glossary
     * section is empty.
     */
    public List<Entry> parse(String body)
    {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        String[] lines = body.split("\\R", -1);
        List<Entry> entries = new ArrayList<>();
        boolean inGlossary = false;
        String currentName = null;
        List<String> currentAka = new ArrayList<>();
        StringBuilder currentDef = new StringBuilder();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("# ") || (line.startsWith("## ") && !isGlossaryHeading(line))) {
                if (inGlossary) {
                    flush(entries, currentName, currentDef, currentAka);
                    currentName = null;
                    currentDef.setLength(0);
                    currentAka = new ArrayList<>();
                    inGlossary = false;
                }
                continue;
            }
            if (line.startsWith("## ") && isGlossaryHeading(line)) {
                inGlossary = true;
                continue;
            }
            if (!inGlossary) {
                continue;
            }
            if (line.startsWith("### ")) {
                flush(entries, currentName, currentDef, currentAka);
                currentName = line.substring(4).trim();
                currentDef.setLength(0);
                currentAka = new ArrayList<>();
                continue;
            }
            if (currentName == null) {
                continue;
            }
            if (isAkaLine(line)) {
                for (String alias : extractAka(line)) {
                    if (!alias.isEmpty()) {
                        currentAka.add(alias);
                    }
                }
                continue;
            }
            if (currentDef.length() > 0) {
                currentDef.append(' ');
            }
            currentDef.append(line);
        }
        flush(entries, currentName, currentDef, currentAka);
        return List.copyOf(entries);
    }

    private static void flush(List<Entry> out, String name, StringBuilder def, List<String> aka)
    {
        if (name == null || name.isBlank()) {
            return;
        }
        String trimmedDef = def.toString().trim();
        if (trimmedDef.isEmpty()) {
            return;
        }
        out.add(new Entry(name.trim(), trimmedDef, List.copyOf(aka)));
    }

    private static boolean isGlossaryHeading(String line)
    {
        return line.length() > 3
                && line.substring(3).trim().toLowerCase(Locale.ROOT).equals(GLOSSARY_HEADING);
    }

    private static boolean isAkaLine(String line)
    {
        // Accept both *aka:* (asterisks) and _aka:_ (underscores) so
        // either common markdown emphasis style works.
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.startsWith("*aka:") || lower.startsWith("_aka:");
    }

    private static List<String> extractAka(String line)
    {
        int colon = line.indexOf(':');
        if (colon < 0 || colon == line.length() - 1) {
            return List.of();
        }
        String tail = line.substring(colon + 1).trim();
        // Strip a leading emphasis closer immediately after the colon
        // (the {@code *} or {@code _} that closes the {@code *aka:*}
        // span). Stop at the first non-marker character.
        while (!tail.isEmpty() && (tail.charAt(0) == '*' || tail.charAt(0) == '_')) {
            tail = tail.substring(1).trim();
        }
        // And a trailing emphasis marker (rare but valid for users
        // who italicise the whole line).
        while (!tail.isEmpty()
                && (tail.charAt(tail.length() - 1) == '*' || tail.charAt(tail.length() - 1) == '_')) {
            tail = tail.substring(0, tail.length() - 1).trim();
        }
        if (tail.isEmpty()) {
            return List.of();
        }
        String[] parts = tail.split(",");
        List<String> out = new ArrayList<>(parts.length);
        for (String p : parts) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }
}
