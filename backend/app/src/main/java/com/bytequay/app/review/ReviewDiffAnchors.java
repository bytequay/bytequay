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
package com.bytequay.app.review;

import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.review.ReviewAnchor;
import com.bytequay.core.review.ReviewInvalidRequestException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * The deterministic anchor map of one frozen publication diff.
 *
 * <p>Every commentable line gets one identifier, numbered in diff order, so the same patches always
 * yield the same identifiers. A deleted line is anchored on the left side at its old number; a
 * context or added line on the right side at its new number, which is where GitHub places a
 * comment on it. Binary and unavailable patches contribute no anchors at all.
 */
public final class ReviewDiffAnchors
{
    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");

    private final Map<String, Line> byId;
    private final List<Hunk> hunks;

    private ReviewDiffAnchors(Map<String, Line> byId, List<Hunk> hunks)
    {
        this.byId = ImmutableMap.copyOf(byId);
        this.hunks = ImmutableList.copyOf(hunks);
    }

    public static ReviewDiffAnchors of(List<DiffFile> files)
    {
        requireNonNull(files, "files is null");
        ImmutableMap.Builder<String, Line> byId = ImmutableMap.builder();
        List<Hunk> hunks = new ArrayList<>();
        int next = 1;
        for (DiffFile file : files) {
            if (file.patch() == null || file.patch().isBlank()) {
                continue;
            }
            int oldLine = 0;
            int newLine = 0;
            List<Line> current = null;
            String header = null;
            for (String raw : file.patch().split("\n", -1)) {
                Matcher hunk = HUNK_HEADER.matcher(raw);
                if (hunk.matches()) {
                    if (current != null) {
                        hunks.add(new Hunk(file.filename(), header, current));
                    }
                    oldLine = Integer.parseInt(hunk.group(1));
                    newLine = Integer.parseInt(hunk.group(3));
                    header = raw;
                    current = new ArrayList<>();
                    continue;
                }
                if (current == null || raw.isEmpty() || raw.startsWith("\\")) {
                    continue;
                }
                char kind = raw.charAt(0);
                String text = raw.substring(1);
                Line line;
                switch (kind) {
                    case '+' -> line = new Line("A" + next++, file.filename(), "RIGHT", newLine++, text, kind, hunks.size());
                    case '-' -> line = new Line("A" + next++, file.filename(), "LEFT", oldLine++, text, kind, hunks.size());
                    case ' ' -> {
                        line = new Line("A" + next++, file.filename(), "RIGHT", newLine++, text, kind, hunks.size());
                        oldLine++;
                    }
                    default -> {
                        continue;
                    }
                }
                byId.put(line.id(), line);
                current.add(line);
            }
            if (current != null) {
                hunks.add(new Hunk(file.filename(), header, current));
            }
        }
        return new ReviewDiffAnchors(byId.buildOrThrow(), hunks);
    }

    public Optional<Line> line(String id)
    {
        return Optional.ofNullable(byId.get(id));
    }

    public List<Hunk> hunks()
    {
        return hunks;
    }

    public List<Hunk> hunks(String path)
    {
        return hunks.stream().filter(hunk -> hunk.path().equals(path)).collect(toImmutableList());
    }

    public int size()
    {
        return byId.size();
    }

    /**
     * Resolves a start and optional end identifier to one publishable range: both anchors must exist
     * in this diff, sit in the same file, hunk and side, and be ordered. The excerpt is the exact
     * frozen text between them.
     */
    public ReviewAnchor range(String startId, Optional<String> endId)
    {
        Line start = line(startId).orElseThrow(() -> new ReviewInvalidRequestException("startAnchor: unknown anchor " + startId + " for this run"));
        Line end = endId.map(id -> line(id).orElseThrow(() -> new ReviewInvalidRequestException("endAnchor: unknown anchor " + id + " for this run"))).orElse(start);
        if (!end.path().equals(start.path())) {
            throw new ReviewInvalidRequestException("endAnchor: range crosses files");
        }
        if (!end.side().equals(start.side())) {
            throw new ReviewInvalidRequestException("endAnchor: range crosses diff sides; a deleted and an added line cannot share one comment");
        }
        if (end.hunk() != start.hunk()) {
            throw new ReviewInvalidRequestException("endAnchor: range crosses hunks; use one finding per hunk");
        }
        if (end.line() < start.line()) {
            throw new ReviewInvalidRequestException("endAnchor: range is reversed");
        }
        List<String> excerpt = hunks.get(start.hunk()).lines().stream()
                .filter(line -> line.side().equals(start.side()) && line.line() >= start.line() && line.line() <= end.line())
                .map(Line::text)
                .collect(toImmutableList());
        return new ReviewAnchor(start.path(), start.side(), start.line(), end.line(), excerpt);
    }

    /**
     * Maps an anchor recorded against another head onto this diff, only when the mapping is
     * deterministic: the same lines with the same text, or exactly one place in the same file and
     * side that still holds the exact excerpt. Anything else leaves the finding outdated.
     */
    public Optional<ReviewAnchor> remap(ReviewAnchor anchor)
    {
        requireNonNull(anchor, "anchor is null");
        List<ReviewAnchor> matches = new ArrayList<>();
        for (Hunk hunk : hunks(anchor.path())) {
            List<Line> side = hunk.lines().stream().filter(line -> line.side().equals(anchor.side())).collect(toImmutableList());
            for (int index = 0; index + anchor.excerpt().size() <= side.size(); index++) {
                boolean same = true;
                for (int offset = 0; offset < anchor.excerpt().size(); offset++) {
                    Line candidate = side.get(index + offset);
                    if (!candidate.text().equals(anchor.excerpt().get(offset)) || candidate.line() != side.get(index).line() + offset) {
                        same = false;
                        break;
                    }
                }
                if (same) {
                    int start = side.get(index).line();
                    matches.add(new ReviewAnchor(anchor.path(), anchor.side(), start, start + anchor.excerpt().size() - 1, anchor.excerpt()));
                }
            }
        }
        if (matches.stream().anyMatch(match -> match.startLine() == anchor.startLine())) {
            return Optional.of(anchor);
        }
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    /** One commentable diff line and its anchor identifier. */
    public record Line(String id, String path, String side, int line, String text, char kind, int hunk) {}

    public record Hunk(String path, String header, List<Line> lines)
    {
        public Hunk
        {
            requireNonNull(path, "path is null");
            requireNonNull(header, "header is null");
            lines = ImmutableList.copyOf(lines);
        }
    }
}
