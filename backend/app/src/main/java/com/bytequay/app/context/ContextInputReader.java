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
package com.bytequay.app.context;

import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.core.context.ContextEntryKind;
import com.bytequay.core.context.ContextInputFingerprint;
import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.context.ContextMatch;
import com.bytequay.core.context.ContextReference;
import com.bytequay.core.context.ContextScope;
import com.bytequay.core.context.ContextSourceEvidence;
import com.bytequay.core.context.RegisteredContextSource;
import com.bytequay.core.context.RepositoryNotebook;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static com.bytequay.app.context.RepositoryContextReader.repositoryId;
import static com.bytequay.core.context.ContextLimits.bytes;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/** Reads local Git object identities; neither source applicability nor effective dependency versions are inferred. */
@Service
public class ContextInputReader
{
    private static final int SCOPE_PATH_LIMIT = 120;
    private static final int TOTAL_PATH_LIMIT = 512;
    private static final int BATCH_SIZE = 128;
    private static final int OUTPUT_LIMIT = 256 * 1024;
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(10);
    private static final TopKSelector SCOPE_PATHS = new TopKSelector(SCOPE_PATH_LIMIT);
    private static final TopKSelector TOTAL_PATHS = new TopKSelector(TOTAL_PATH_LIMIT);
    private static final List<String> MANIFESTS = ImmutableList.of(
            "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "gradle.properties", "gradle/libs.versions.toml",
            "package.json", "package-lock.json", "pnpm-lock.yaml", "yarn.lock", "go.mod", "go.sum", "Cargo.toml", "Cargo.lock",
            "pyproject.toml", "requirements.txt", "uv.lock", "setup.py", "setup.cfg", "Makefile", "CMakeLists.txt", "WORKSPACE", "WORKSPACE.bazel", "MODULE.bazel");
    private static final List<String> PROJECT_PATHS = ImmutableList.of("README", "README.md", "README.rst", "README.txt", "CONTRIBUTING.md", "ARCHITECTURE.md",
            "docs", "doc", "documentation");

    private final GitCommandRunner git;

    public ContextInputReader(GitCommandRunner git)
    {
        this.git = requireNonNull(git, "git is null");
    }

    /**
     * Captures comparable inputs at an immutable monitored commit, without reading HEAD, disk files or remote content.
     * Scope/source configuration is hashed in full. Expected missing paths remain explicit inputs, so their later addition invalidates reuse.
     * At most five Git commands inspect 512 distinct requested paths; excluded or unreadable essential inputs remove the digest.
     */
    public List<ContextInputFingerprint> capture(
            WorkspaceRepositoryContext workspace, Optional<String> baselineSha, RepositoryNotebook notebook, List<ContextScope> scopes)
    {
        requireNonNull(workspace, "workspace is null");
        requireNonNull(baselineSha, "baselineSha is null");
        requireNonNull(notebook, "notebook is null");
        ContextLimits.unique(scopes.stream().map(ContextScope::id).toList(), "fingerprint scopes", 100);
        if (scopes.isEmpty()) {
            return ImmutableList.of();
        }
        long deadline = System.nanoTime() + TOTAL_TIMEOUT.toNanos();
        Read root = baselineSha.filter(sha -> sha.matches("[0-9a-f]{40}|[0-9a-f]{64}"))
                .map(sha -> read(workspace, sha, ImmutableList.of(), deadline))
                .orElseGet(() -> new Read(ImmutableMap.of(), Optional.of("The captured immutable monitored baseline is unavailable.")));
        String catalog = catalogHash(notebook.sources());
        List<Plan> plans = scopes.stream().map(scope -> plan(workspace, notebook, scope, root, catalog)).collect(toImmutableList());
        List<String> requested = TOTAL_PATHS.select(plans.stream().flatMap(plan -> plan.paths().stream())
                .filter(path -> path.contains("/")).distinct().sorted()).collect(toImmutableList());
        Map<String, String> objects = new LinkedHashMap<>(root.objects());
        Map<String, String> failures = new LinkedHashMap<>();
        if (root.gap().isEmpty()) {
            for (int from = 0; from < requested.size(); from += BATCH_SIZE) {
                List<String> batch = requested.subList(from, Math.min(requested.size(), from + BATCH_SIZE));
                Read read = read(workspace, baselineSha.orElseThrow(), batch, deadline);
                for (String path : batch) {
                    if (read.gap().isPresent()) {
                        failures.put(path, read.gap().orElseThrow());
                    }
                    else {
                        objects.put(path, read.objects().getOrDefault(path, "ABSENT"));
                    }
                }
            }
        }
        return plans.stream().map(plan -> fingerprint(plan, root, objects, failures)).collect(toImmutableList());
    }

    private static Plan plan(WorkspaceRepositoryContext workspace, RepositoryNotebook notebook, ContextScope scope, Read root, String catalog)
    {
        Map<String, String> inputs = new LinkedHashMap<>();
        inputs.put("scope-configuration", scopeHash(scope));
        inputs.put("registered-documentation-catalog-remote-content-unchecked", catalog);
        inputs.put("repository", repositoryId(workspace) + ":" + workspace.monitoredRepository().fullName().toLowerCase(Locale.ROOT));
        Set<String> gaps = new LinkedHashSet<>();
        root.gap().ifPresent(gaps::add);
        Set<String> sourceIds = new LinkedHashSet<>();
        notebook.entries().stream().filter(entry -> entry.enabled() && entry.kind() == ContextEntryKind.KNOWLEDGE && entry.scopeId().equals(scope.id()))
                .forEach(entry -> sourceIds.addAll(entry.sourceIds()));
        List<ContextSourceEvidence> sources = notebook.sourceEvidence().stream().filter(source -> sourceIds.contains(source.id()))
                .sorted(Comparator.comparing(ContextSourceEvidence::id)).collect(toImmutableList());
        Set<String> referenceInputs = new LinkedHashSet<>();
        List<String> files = new ArrayList<>();
        for (ContextSourceEvidence source : sources) {
            referenceInputs.add(sourceIdentity(source));
            switch (source.reference()) {
                case ContextReference.RepositoryFile file -> {
                    if (file.repositoryId() == repositoryId(workspace) && file.repository().fullName().equalsIgnoreCase(workspace.monitoredRepository().fullName())) {
                        files.add(file.path());
                    }
                    else {
                        gaps.add("A retained repository-file source belongs to another repository and was not inspected.");
                    }
                }
                case ContextReference.Document document -> {
                    boolean registered = notebook.sources().stream().anyMatch(sourceConfig -> sourceConfig.id().equals(document.sourceId())
                            && (sourceConfig.kind() == RegisteredContextSource.Kind.DOCUMENTATION_ROOT
                                    ? document.url().startsWith(sourceConfig.url()) : document.url().equals(sourceConfig.url())));
                    if (!registered) {
                        gaps.add("A retained document is outside the registered source catalog.");
                    }
                    if (source.capture() != ContextSourceEvidence.Capture.BYTEQUAY_READ || source.truncated() || source.contentHash().isEmpty()) {
                        gaps.add("A retained document lacks a complete application-observed content identity; its content freshness is unverified.");
                    }
                }
                default -> gaps.add("Retained " + source.reference().getKind() + " source freshness is not inspected by this local Git fingerprint.");
            }
        }
        Hasher references = Hashing.sha256().newHasher();
        referenceInputs.stream().sorted().forEach(identity -> frame(references, identity));
        inputs.put("retained-source-identities", references.hash().toString());
        List<String> prefixes = scope.match() instanceof ContextMatch.Selected selected
                ? selected.pathPrefixes().stream().map(prefix -> prefix.substring(0, prefix.length() - 1)).sorted().collect(toImmutableList())
                : ImmutableList.of();
        if (scope.match() instanceof ContextMatch.Selected && prefixes.isEmpty() && files.isEmpty()) {
            gaps.add("The term-only scope has no local source paths; component input freshness is unknown.");
        }
        Stream<String> project = scope.match() instanceof ContextMatch.Repository
                ? Stream.concat(PROJECT_PATHS.stream(), root.objects().keySet().stream().filter(ContextInputReader::projectDocument).sorted())
                : Stream.empty();
        Stream<String> ancestors = Stream.concat(prefixes.stream(), files.stream()).flatMap(ContextInputReader::parents).distinct()
                .filter(parent -> prefixes.stream().noneMatch(prefix -> parent.equals(prefix) || parent.startsWith(prefix + "/")))
                .flatMap(parent -> MANIFESTS.stream().map(manifest -> parent + "/" + manifest));
        List<String> candidates = new TopKSelector(SCOPE_PATH_LIMIT + 1).select(Stream.of(prefixes.stream(), files.stream().sorted(), project, MANIFESTS.stream(), ancestors)
                .flatMap(stream -> stream).distinct()).collect(toImmutableList());
        if (candidates.size() > SCOPE_PATH_LIMIT) {
            gaps.add("Essential paths exceeded the per-scope limit of " + SCOPE_PATH_LIMIT + ".");
        }
        List<String> paths = new ArrayList<>();
        for (String candidate : SCOPE_PATHS.select(candidates)) {
            try {
                ContextLimits.pathPrefix(candidate + "/");
                paths.add(candidate);
            }
            catch (IllegalArgumentException failure) {
                gaps.add("An essential discovered path cannot be represented safely by the local source reader.");
            }
        }
        return new Plan(scope.id(), ImmutableMap.copyOf(inputs), ImmutableList.copyOf(paths), ImmutableList.copyOf(gaps));
    }

    private static ContextInputFingerprint fingerprint(Plan plan, Read root, Map<String, String> objects, Map<String, String> failures)
    {
        Map<String, String> inputs = new LinkedHashMap<>(plan.inputs());
        Set<String> gaps = new LinkedHashSet<>(plan.gaps());
        int inputBytes = inputs.entrySet().stream().mapToInt(entry -> bytes(entry.getKey()) + bytes(entry.getValue())).sum();
        for (String path : plan.paths()) {
            if (root.gap().isPresent()) {
                continue;
            }
            if (failures.containsKey(path)) {
                gaps.add(failures.get(path));
                continue;
            }
            if (path.contains("/") && !objects.containsKey(path)) {
                gaps.add("Essential paths exceeded the shared limit of " + TOTAL_PATH_LIMIT + ".");
                continue;
            }
            String key = "git:" + path;
            String identity = objects.getOrDefault(path, "ABSENT");
            int added = bytes(key) + bytes(identity);
            if (inputBytes + added > ContextInputFingerprint.MAXIMUM_INPUT_BYTES) {
                gaps.add("Essential inputs exceeded the per-scope UTF-8 byte budget.");
                continue;
            }
            inputs.put(key, identity);
            inputBytes += added;
        }
        List<String> retainedGaps = new TopKSelector(9).select(gaps.stream()).collect(toImmutableList());
        if (gaps.size() > retainedGaps.size()) {
            retainedGaps = ImmutableList.<String>builder().addAll(retainedGaps).add("Additional fingerprint limitations were omitted.").build();
        }
        return ContextInputFingerprint.create(plan.scopeId(), inputs, retainedGaps);
    }

    private Read read(WorkspaceRepositoryContext workspace, String sha, List<String> paths, long deadline)
    {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            return new Read(ImmutableMap.of(), Optional.of("The local fingerprint time budget was exhausted."));
        }
        ImmutableList.Builder<String> arguments = ImmutableList.<String>builder()
                .add("--literal-pathspecs", "ls-tree", "-t", "-z", "--full-tree", sha, "--").addAll(paths);
        try {
            GitCommandRunner.Result result = git.run(workspace.repository(), Duration.ofNanos(Math.min(remaining, COMMAND_TIMEOUT.toNanos())), arguments.build());
            if (!result.successful() || result.truncated() || bytes(result.output()) > OUTPUT_LIMIT) {
                return new Read(ImmutableMap.of(), Optional.of("A required local Git inventory was unavailable or exceeded its output limit."));
            }
            return new Read(parse(result.output()), Optional.empty());
        }
        catch (RuntimeException failure) {
            return new Read(ImmutableMap.of(), Optional.of("A required local Git inventory could not be read completely."));
        }
    }

    private static Map<String, String> parse(String output)
    {
        Map<String, String> objects = new LinkedHashMap<>();
        checkArgument(output.isEmpty() || output.endsWith("\0"), "Git inventory is not NUL-terminated");
        if (output.isEmpty()) {
            return ImmutableMap.of();
        }
        for (String entry : output.substring(0, output.length() - 1).split("\0", -1)) {
            int tab = entry.indexOf('\t');
            checkArgument(tab > 0, "Git inventory has no path");
            String[] fields = entry.substring(0, tab).split(" ", -1);
            checkArgument(fields.length == 3 && fields[2].matches("[0-9a-f]{40}|[0-9a-f]{64}"), "Invalid Git object identity");
            checkArgument(switch (fields[0]) {
                case "100644", "100755", "120000" -> fields[1].equals("blob");
                case "040000" -> fields[1].equals("tree");
                case "160000" -> fields[1].equals("commit");
                default -> false;
            }, "Invalid Git object mode or type");
            String path = entry.substring(tab + 1);
            checkArgument(Arrays.stream(path.split("/", -1)).noneMatch(part -> part.isEmpty() || part.equals(".") || part.equals("..")), "Invalid Git inventory path");
            checkArgument(objects.putIfAbsent(path, entry.substring(0, tab)) == null, "Duplicate Git inventory path");
        }
        return ImmutableMap.copyOf(objects);
    }

    private static Stream<String> parents(String path)
    {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? Stream.empty() : Stream.iterate(path.substring(0, slash), parent -> !parent.isEmpty(), parent -> {
            int separator = parent.lastIndexOf('/');
            return separator < 0 ? "" : parent.substring(0, separator);
        });
    }

    private static boolean projectDocument(String path)
    {
        return path.toLowerCase(Locale.ROOT).matches("(readme|contributing|architecture)(\\..+)?") || PROJECT_PATHS.contains(path);
    }

    private static String scopeHash(ContextScope scope)
    {
        Hasher hash = Hashing.sha256().newHasher();
        frame(hash, "local-git-inputs-v1");
        frame(hash, scope.id());
        frame(hash, scope.title());
        frame(hash, Boolean.toString(scope.enabled()));
        frame(hash, scope.match().getKind());
        if (scope.match() instanceof ContextMatch.Selected selected) {
            selected.pathPrefixes().stream().sorted().forEach(path -> frame(hash, "path:" + path));
            selected.terms().stream().sorted().forEach(term -> frame(hash, "term:" + term));
        }
        return hash.hash().toString();
    }

    private static String catalogHash(List<RegisteredContextSource> sources)
    {
        Hasher hash = Hashing.sha256().newHasher();
        sources.stream().sorted(Comparator.comparing(RegisteredContextSource::id)).forEach(source -> {
            frame(hash, source.id());
            frame(hash, source.kind().name());
            frame(hash, source.url());
            frame(hash, source.origin().name());
        });
        return hash.hash().toString();
    }

    private static String sourceIdentity(ContextSourceEvidence source)
    {
        Hasher hash = Hashing.sha256().newHasher();
        frame(hash, source.reference().getKind());
        switch (source.reference()) {
            case ContextReference.RepositoryFile file -> {
                frame(hash, Long.toString(file.repositoryId()));
                frame(hash, file.repository().fullName().toLowerCase(Locale.ROOT));
                frame(hash, file.path());
            }
            case ContextReference.Document document -> {
                frame(hash, document.sourceId());
                frame(hash, document.url());
                frame(hash, source.contentHash().orElse(""));
                frame(hash, source.version().orElse(""));
                frame(hash, source.capture().name());
                frame(hash, Boolean.toString(source.truncated()));
            }
            default -> frame(hash, source.reference().toString());
        }
        return hash.hash().toString();
    }

    private static void frame(Hasher hash, String value)
    {
        byte[] encoded = value.getBytes(UTF_8);
        hash.putInt(encoded.length).putBytes(encoded);
    }

    private record Read(Map<String, String> objects, Optional<String> gap) {}

    private record Plan(String scopeId, Map<String, String> inputs, List<String> paths, List<String> gaps) {}
}
