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
package com.bytequay.app.service.skills;

import com.bytequay.app.service.tools.ToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Writes resolved skills to a session-scoped temp directory so a CLI
 * client (Claude Code, Reasonix, …) can read them through its own
 * skill-discovery loop. The DB stays the source of truth; the files
 * are ephemeral — created at session start, deleted at session end.
 *
 * <p>Layout the materializer produces (one folder per resolved skill,
 * mirroring the convention Claude Code expects):
 * <pre>
 *   {baseDir}/
 *     {slug}/SKILL.md       ← frontmatter (name, description) + body
 *     {other-slug}/SKILL.md
 *     …
 * </pre>
 *
 * <p>Filename slugs derive deterministically from skill names so the
 * client can disambiguate between runs. The contents are byte-stable
 * for the same input — no timestamps, no derived ids — so a re-spawn
 * of the same session produces identical files.
 */
@Service
public class SkillMaterializer
{
    private static final Logger log = LoggerFactory.getLogger(SkillMaterializer.class);

    private final SkillManifestService manifest;

    public SkillMaterializer(SkillManifestService manifest)
    {
        this.manifest = requireNonNull(manifest, "manifest is null");
    }

    /**
     * Resolve the manifest for {@code context} and write each entry's
     * SKILL.md into {@code baseDir}. Creates the directory if missing;
     * existing slug folders are overwritten so a re-materialize after
     * an edit produces fresh content. Returns the directory path so
     * the caller can hand it to the CLI subprocess.
     *
     * <p>Each slug folder is the kebab-cased skill name. Two skills
     * with names that collapse to the same slug share the folder —
     * the second write wins. That mirrors how SKILL.md folders work
     * in Claude Code's native discovery, and the manifest service
     * already enforces uniqueness on the {@code name} column so
     * collisions don't happen with authored skills today.
     */
    public Path materialize(Path baseDir, ToolContext context)
    {
        requireNonNull(baseDir, "baseDir is null");
        requireNonNull(context, "context is null");
        try {
            Files.createDirectories(baseDir);
        }
        catch (IOException e) {
            throw new UncheckedIOException("create baseDir " + baseDir, e);
        }
        // Sort by name for deterministic file write order; helps when
        // diffing two session dirs in tests.
        List<SkillManifestEntry> entries = manifest.query(
                SkillManifestQuery.forRepoContext(
                        firstRepo(context),
                        context.role().orElse(null)));
        for (SkillManifestEntry entry : entries.stream()
                .sorted(Comparator.comparing(SkillManifestEntry::name))
                .toList()) {
            writeOne(baseDir, entry);
        }
        return baseDir;
    }

    /** Best-effort recursive delete of the materialized directory.
     *  Swallows IO errors — the caller is the CLI agent's stop()
     *  hook and we don't want a stale temp file to keep the agent
     *  in an error state. JVM exit handles the rest via deleteOnExit. */
    public void cleanup(Path baseDir)
    {
        if (baseDir == null || !Files.exists(baseDir)) {
            return;
        }
        try (var stream = Files.walk(baseDir)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        }
                        catch (IOException e) {
                            log.warn("Failed to delete materialized skill path {}: {}", p, e.getMessage());
                        }
                    });
        }
        catch (IOException e) {
            log.warn("Failed to walk materialized skill dir {}: {}", baseDir, e.getMessage());
        }
    }

    private void writeOne(Path baseDir, SkillManifestEntry entry)
    {
        String slug = slugify(entry.name());
        Path folder = baseDir.resolve(slug);
        try {
            Files.createDirectories(folder);
        }
        catch (IOException e) {
            throw new UncheckedIOException("create skill folder " + folder, e);
        }
        Optional<String> body = manifest.loadBody(entry.name());
        if (body.isEmpty()) {
            // Defensive: the manifest query already filters out
            // disabled rows, but a race between manifest read and
            // body read could surface an empty body. Skip silently.
            return;
        }
        String contents = render(entry, body.get());
        try {
            Files.writeString(folder.resolve("SKILL.md"), contents, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException("write SKILL.md for " + entry.name(), e);
        }
    }

    /** Minimal frontmatter + body. The frontmatter mirrors the
     *  fields a CLI client uses to surface the trigger phrase in its
     *  skill catalogue. Keys are sorted alphabetically so two runs
     *  with the same skill produce byte-identical SKILL.md. */
    private static String render(SkillManifestEntry entry, String body)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("description: ").append(escapeYaml(entry.description())).append('\n');
        sb.append("kind: ").append(entry.kind()).append('\n');
        sb.append("name: ").append(escapeYaml(entry.name())).append('\n');
        if (entry.roleTag() != null) {
            sb.append("role_tag: ").append(entry.roleTag()).append('\n');
        }
        sb.append("scope: ").append(entry.scope()).append('\n');
        if (entry.scope().equals("repo") && entry.repo() != null) {
            sb.append("repo: ").append(entry.repo()).append('\n');
        }
        sb.append("---\n\n");
        sb.append(body);
        if (!body.endsWith("\n")) {
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String escapeYaml(String s)
    {
        if (s == null) {
            return "\"\"";
        }
        // Always quote — keeps the value safe even when it starts with
        // a YAML special char (-, :, [, etc.) or contains commas.
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Kebab-case slug derived from the skill name. Strips
     *  non-alphanumeric runs, collapses whitespace to dashes, and
     *  lowercases. */
    static String slugify(String name)
    {
        String stripped = name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return stripped.isEmpty() ? "skill" : stripped;
    }

    private static String firstRepo(ToolContext context)
    {
        if (context.touchedRepos() == null || context.touchedRepos().isEmpty()) {
            return null;
        }
        return context.touchedRepos().iterator().next();
    }
}
