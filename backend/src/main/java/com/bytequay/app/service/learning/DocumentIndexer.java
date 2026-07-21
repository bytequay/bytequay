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
package com.bytequay.app.service.learning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

/**
 * Builds the heading-level local-document index and the bounded project
 * capsule for a verified clone. Markdown / AsciiDoc documents are split by
 * ATX heading (a {@code #}- or {@code =}-prefixed line) rather than by
 * arbitrary character windows; each section records enough to locate and
 * re-read the exact local span. The bulk text is never copied into the
 * database — only the reference and a content digest.
 *
 * <p>The capsule is a bounded (&lt;= {@value #CAPSULE_CHAR_CAP}-char) derived
 * view — project identity, module map, build/test entry points, pointers —
 * regenerated when its source digest changes. It lives only in the local
 * database, never written back into the repository.
 *
 * <p>ponytail: ATX headings cover Markdown and AsciiDoc, which is every doc
 * ByteQuay indexes today; reStructuredText underline headings can be added
 * if an rST-heavy project needs them.
 */
@Component
public class DocumentIndexer
{
    private static final Logger log = LoggerFactory.getLogger(DocumentIndexer.class);

    static final int CAPSULE_CHAR_CAP = 4000;
    private static final int MAX_DEPTH = 6;
    private static final int MAX_DOCS = 400;
    private static final long MAX_FILE_BYTES = 2 * 1024 * 1024;

    private static final List<String> EXCLUDED_DIRS = List.of(
            ".git", "node_modules", "target", "build", "dist", "out",
            "vendor", ".gradle", ".idea", "__pycache__");

    private final ProjectLearningStore store;

    public DocumentIndexer(ProjectLearningStore store)
    {
        this.store = requireNonNull(store, "store is null");
    }

    /** Result of an index pass: what was written and the capsule text. */
    public record IndexResult(int sections, String capsuleMd, String sourceDigest) {}

    /**
     * Re-index {@code root} for a workspace repository, replacing any prior
     * sections, and regenerate the capsule. Returns the section count and
     * the capsule so the caller can persist the capsule and update counts.
     */
    public IndexResult index(String workspaceId, String repo, Path root, String commitSha)
    {
        requireNonNull(root, "root is null");
        long now = Instant.now().toEpochMilli();
        List<Path> docs = discover(root);

        store.deleteDocSections(workspaceId, repo);
        StringBuilder digestSeed = new StringBuilder();
        int sectionCount = 0;
        for (Path doc : docs) {
            String rel = root.relativize(doc).toString().replace('\\', '/');
            String type = knowledgeType(rel);
            List<Section> sections = splitByHeading(doc);
            for (Section s : sections) {
                store.insertDocSection(workspaceId, repo, rel, s.headingPath(),
                        s.lineStart(), s.lineEnd(), s.digest(), type, "[]", commitSha, now);
                digestSeed.append(rel).append('#').append(s.headingPath())
                        .append('=').append(s.digest()).append('\n');
                sectionCount++;
            }
        }

        String sourceDigest = MergedPrCatalog.sha256(digestSeed.toString());
        String capsule = buildCapsule(root, docs);
        return new IndexResult(sectionCount, capsule, sourceDigest);
    }

    // ── discovery ───────────────────────────────────────────────────

    private List<Path> discover(Path root)
    {
        try (Stream<Path> walk = Files.walk(root, MAX_DEPTH)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(p -> !isExcluded(root, p))
                    .filter(this::isIndexable)
                    .limit(MAX_DOCS)
                    .sorted()
                    .toList();
        }
        catch (IOException e) {
            throw new UncheckedIOException("failed to walk " + root, e);
        }
    }

    private boolean isExcluded(Path root, Path file)
    {
        for (Path part : root.relativize(file)) {
            if (EXCLUDED_DIRS.contains(part.toString())) {
                return true;
            }
        }
        try {
            return Files.size(file) > MAX_FILE_BYTES;
        }
        catch (IOException e) {
            return true;
        }
    }

    private boolean isIndexable(Path file)
    {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".md")
                || name.endsWith(".markdown")
                || name.endsWith(".rst")
                || name.endsWith(".adoc")
                || name.equals("pom.xml")
                || name.equals("build.gradle")
                || name.equals("build.gradle.kts")
                || name.equals("package.json")
                || name.equals("cargo.toml")
                || name.equals("go.mod");
    }

    private static String knowledgeType(String rel)
    {
        String lower = rel.toLowerCase(Locale.ROOT);
        String name = lower.contains("/") ? lower.substring(lower.lastIndexOf('/') + 1) : lower;
        if (name.startsWith("readme")) {
            return "readme";
        }
        if (name.startsWith("contributing")) {
            return "contributing";
        }
        if (lower.contains("adr") || lower.contains("/decisions/")) {
            return "adr";
        }
        if (lower.contains("architecture") || lower.contains("design")) {
            return "architecture";
        }
        if (name.equals("pom.xml") || name.startsWith("build.gradle")
                || name.equals("package.json") || name.equals("cargo.toml")
                || name.equals("go.mod")) {
            return "build";
        }
        return "brief";
    }

    // ── heading-level split ─────────────────────────────────────────

    private record Section(String headingPath, int lineStart, int lineEnd, String digest) {}

    private List<Section> splitByHeading(Path doc)
    {
        List<String> lines;
        try {
            lines = Files.readAllLines(doc, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            log.debug("skipping unreadable doc {}: {}", doc, e.getMessage());
            return List.of();
        }
        boolean atxDoc = doc.getFileName().toString().toLowerCase(Locale.ROOT)
                .matches(".*\\.(md|markdown|adoc)$");

        List<Section> sections = new ArrayList<>();
        ArrayList<String> trail = new ArrayList<>();
        int sectionStart = 0;
        StringBuilder body = new StringBuilder();
        String currentHeadingPath = "";
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int level = atxDoc ? headingLevel(line) : -1;
            if (level > 0) {
                if (body.length() > 0 || !currentHeadingPath.isEmpty() || i > 0) {
                    sections.add(new Section(currentHeadingPath, sectionStart + 1, i,
                            MergedPrCatalog.sha256(body.toString())));
                }
                String title = line.substring(level).trim();
                while (trail.size() >= level) {
                    trail.remove(trail.size() - 1);
                }
                trail.add(title);
                currentHeadingPath = String.join(" > ", trail);
                sectionStart = i;
                body.setLength(0);
            }
            else {
                body.append(line).append('\n');
            }
        }
        sections.add(new Section(currentHeadingPath, sectionStart + 1, lines.size(),
                MergedPrCatalog.sha256(body.toString())));
        return sections;
    }

    /** ATX heading level for a {@code #}- or {@code =}-prefixed line, else 0. */
    private static int headingLevel(String line)
    {
        char marker = line.startsWith("#") ? '#' : (line.startsWith("=") ? '=' : 0);
        if (marker == 0) {
            return 0;
        }
        int level = 0;
        while (level < line.length() && line.charAt(level) == marker) {
            level++;
        }
        // A real heading has whitespace after the marker run.
        return level < line.length() && Character.isWhitespace(line.charAt(level)) ? level : 0;
    }

    // ── capsule ─────────────────────────────────────────────────────

    private String buildCapsule(Path root, List<Path> docs)
    {
        StringBuilder capsule = new StringBuilder();
        capsule.append("# Project capsule\n\n");

        String readme = firstReadmeSummary(root, docs);
        if (!readme.isEmpty()) {
            capsule.append("## Identity\n").append(readme).append("\n\n");
        }

        capsule.append("## Module map\n");
        for (String dir : topLevelModules(root)) {
            capsule.append("- ").append(dir).append('\n');
        }
        capsule.append('\n');

        capsule.append("## Documentation\n");
        capsule.append(docs.size()).append(" indexed document(s); read a section for detail.\n");

        String text = capsule.toString();
        return text.length() > CAPSULE_CHAR_CAP
                ? text.substring(0, CAPSULE_CHAR_CAP) : text;
    }

    private String firstReadmeSummary(Path root, List<Path> docs)
    {
        for (Path doc : docs) {
            String rel = root.relativize(doc).toString().toLowerCase(Locale.ROOT);
            if (rel.equals("readme.md") || rel.equals("readme.markdown")) {
                try {
                    List<String> lines = Files.readAllLines(doc, StandardCharsets.UTF_8);
                    StringBuilder summary = new StringBuilder();
                    for (String line : lines) {
                        if (headingLevel(line) > 0) {
                            if (summary.length() > 0) {
                                break;
                            }
                            continue;
                        }
                        if (!line.isBlank()) {
                            summary.append(line.trim()).append(' ');
                        }
                        if (summary.length() > 600) {
                            break;
                        }
                    }
                    return summary.toString().trim();
                }
                catch (IOException e) {
                    return "";
                }
            }
        }
        return "";
    }

    private List<String> topLevelModules(Path root)
    {
        try (Stream<Path> list = Files.list(root)) {
            return list
                    .filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> !n.startsWith("."))
                    .filter(n -> !EXCLUDED_DIRS.contains(n))
                    .sorted()
                    .limit(40)
                    .toList();
        }
        catch (IOException e) {
            return List.of();
        }
    }
}
