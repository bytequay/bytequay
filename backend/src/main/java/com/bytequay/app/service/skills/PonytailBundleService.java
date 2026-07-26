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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Service
public class PonytailBundleService
{
    static final String BUNDLED_VERSION = "4.8.4";
    private static final String PACKAGE_NAME = "@dietrichgebert/ponytail";
    private static final String LICENSE = "MIT";
    private static final String RESOURCE_ROOT = "managed-skills/ponytail/" + BUNDLED_VERSION + "/";
    private static final String ACTIVE_FILE = "active.json";
    private static final String METADATA_FILE = "metadata.json";

    private final ObjectMapper mapper;
    private final Path cacheRoot;

    @Autowired
    public PonytailBundleService(ObjectMapper mapper)
    {
        this(mapper, defaultCacheRoot());
    }

    PonytailBundleService(ObjectMapper mapper, Path cacheRoot)
    {
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.cacheRoot = requireNonNull(cacheRoot, "cacheRoot is null");
    }

    public ManagedSkillBundle snapshot()
    {
        return withBundledInternalSkills(cachedBundle().orElseGet(this::bundledPonytailBundle));
    }

    public Status status()
    {
        ManagedSkillBundle active = snapshot();
        return new Status(BUNDLED_VERSION, active.version(), active.source(), cacheRoot.toString());
    }

    void installCache(DownloadedPackage pkg)
    {
        requireNonNull(pkg, "pkg is null");
        Path versionDir = cacheRoot.resolve(pkg.version());
        try {
            Files.createDirectories(versionDir.resolve("skills/ponytail"));
            Files.createDirectories(versionDir.resolve("skills/ponytail-review"));
            Files.writeString(versionDir.resolve("skills/ponytail/SKILL.md"),
                    pkg.ponytailSkill(), StandardCharsets.UTF_8);
            Files.writeString(versionDir.resolve("skills/ponytail-review/SKILL.md"),
                    pkg.reviewSkill(), StandardCharsets.UTF_8);
            Files.writeString(versionDir.resolve("LICENSE"), pkg.licenseText(), StandardCharsets.UTF_8);

            ObjectNode metadata = mapper.createObjectNode();
            metadata.put("packageName", PACKAGE_NAME);
            metadata.put("version", pkg.version());
            metadata.put("license", LICENSE);
            metadata.put("integrity", pkg.integrity());
            metadata.put("refreshedAt", Instant.now().toString());
            Files.writeString(versionDir.resolve(METADATA_FILE),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(metadata),
                    StandardCharsets.UTF_8);

            ObjectNode active = mapper.createObjectNode();
            active.put("version", pkg.version());
            Files.writeString(cacheRoot.resolve(ACTIVE_FILE), active.toString(), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException("install Ponytail " + pkg.version(), e);
        }
    }

    private Optional<ManagedSkillBundle> cachedBundle()
    {
        Path active = cacheRoot.resolve(ACTIVE_FILE);
        if (!Files.isRegularFile(active)) {
            return Optional.empty();
        }
        try {
            JsonNode activeJson = mapper.readTree(Files.readString(active, StandardCharsets.UTF_8));
            String version = activeJson.path("version").asText("");
            if (version.isBlank()) {
                return Optional.empty();
            }
            Path dir = cacheRoot.resolve(version);
            JsonNode metadata = mapper.readTree(Files.readString(dir.resolve(METADATA_FILE), StandardCharsets.UTF_8));
            if (!PACKAGE_NAME.equals(metadata.path("packageName").asText())
                    || !LICENSE.equals(metadata.path("license").asText())
                    || !version.equals(metadata.path("version").asText())) {
                return Optional.empty();
            }
            return Optional.of(loadFromFiles(version, "cache", dir));
        }
        catch (RuntimeException | IOException e) {
            return Optional.empty();
        }
    }

    private ManagedSkillBundle bundledPonytailBundle()
    {
        return new ManagedSkillBundle(BUNDLED_VERSION, "bundled", Map.of(
                ManagedSkillPolicy.PONYTAIL,
                new ManagedSkill(ManagedSkillPolicy.PONYTAIL,
                        readResource(RESOURCE_ROOT + "skills/ponytail/SKILL.md")),
                ManagedSkillPolicy.PONYTAIL_REVIEW,
                new ManagedSkill(ManagedSkillPolicy.PONYTAIL_REVIEW,
                        readResource(RESOURCE_ROOT + "skills/ponytail-review/SKILL.md"))));
    }

    private ManagedSkillBundle withBundledInternalSkills(ManagedSkillBundle bundle)
    {
        Map<String, ManagedSkill> skills = new LinkedHashMap<>(bundle.skills());
        skills.put(CavemanPrompt.NAME, new ManagedSkill(CavemanPrompt.NAME, CavemanPrompt.body()));
        skills.put(ManagedSkillPolicy.TRUNK_PLANNER, new ManagedSkill(
                ManagedSkillPolicy.TRUNK_PLANNER,
                readResource("managed-skills/bytequay/trunk-planner/SKILL.md")));
        skills.put(ManagedSkillPolicy.CODEGRAPH_FIRST, new ManagedSkill(
                ManagedSkillPolicy.CODEGRAPH_FIRST,
                readResource("managed-skills/bytequay/codegraph-first/SKILL.md")));
        skills.put(ManagedSkillPolicy.TASK_EXECUTION, new ManagedSkill(
                ManagedSkillPolicy.TASK_EXECUTION,
                readResource("managed-skills/bytequay/task-execution/SKILL.md")));
        skills.put(ManagedSkillPolicy.I_HAVE_ADHD, new ManagedSkill(
                ManagedSkillPolicy.I_HAVE_ADHD,
                readResource("managed-skills/i-have-adhd/16a42a01f7783e29db8557dfc46226baf8015618/SKILL.md")));
        return new ManagedSkillBundle(bundle.version(), bundle.source(), Map.copyOf(skills));
    }

    private ManagedSkillBundle loadFromFiles(String version, String source, Path dir)
            throws IOException
    {
        String ponytail = Files.readString(dir.resolve("skills/ponytail/SKILL.md"), StandardCharsets.UTF_8);
        String review = Files.readString(dir.resolve("skills/ponytail-review/SKILL.md"), StandardCharsets.UTF_8);
        if (ponytail.isBlank() || review.isBlank()) {
            throw new IOException("empty Ponytail skill file");
        }
        return new ManagedSkillBundle(version, source, Map.of(
                ManagedSkillPolicy.PONYTAIL, new ManagedSkill(ManagedSkillPolicy.PONYTAIL, ponytail),
                ManagedSkillPolicy.PONYTAIL_REVIEW, new ManagedSkill(ManagedSkillPolicy.PONYTAIL_REVIEW, review)));
    }

    private static String readResource(String name)
    {
        try (var in = PonytailBundleService.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException("read resource " + name, e);
        }
    }

    private static Path defaultCacheRoot()
    {
        return Path.of(System.getProperty("user.home"), "Library", "Application Support",
                "ByteQuay", "skills", "vendor", "ponytail");
    }

    record DownloadedPackage(
            String version,
            String integrity,
            String ponytailSkill,
            String reviewSkill,
            String licenseText)
    {
    }

    public record Status(String bundledVersion, String activeVersion, String activeSource, String cacheRoot)
    {
    }
}
