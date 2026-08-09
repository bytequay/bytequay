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
package com.bytequay.app.service.harness;

import com.bytequay.app.service.harness.HarnessModels.BootstrapProfile;
import com.google.common.collect.ImmutableSet;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/** Deterministically discovers CI structure and verification commands from the checkout. */
@Component
public class HarnessBootstrapper
{
    private static final long MAX_WORKFLOW_BYTES = 512_000;

    private final HarnessWorktreeProvisioner worktrees;

    public HarnessBootstrapper(HarnessWorktreeProvisioner worktrees)
    {
        this.worktrees = requireNonNull(worktrees, "worktrees is null");
    }

    public BootstrapResult bootstrap(
            String owner,
            String repo,
            String requestedLocalPath,
            String watchId,
            String branch)
    {
        Path root = worktrees.prepare(owner, repo, requestedLocalPath, watchId, branch);
        Set<String> ecosystems = new LinkedHashSet<>();
        Map<String, String> modules = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();

        if (Files.isRegularFile(root.resolve("pom.xml"))) {
            ecosystems.add("maven");
            modules.putAll(readMavenModules(root, warnings));
        }
        if (Files.isRegularFile(root.resolve("build.gradle"))
                || Files.isRegularFile(root.resolve("build.gradle.kts"))) {
            ecosystems.add("gradle");
        }
        if (Files.isRegularFile(root.resolve("package.json"))) {
            ecosystems.add("node");
        }
        if (Files.isRegularFile(root.resolve("pyproject.toml"))
                || Files.isRegularFile(root.resolve("pytest.ini"))) {
            ecosystems.add("python");
        }
        if (Files.isRegularFile(root.resolve("Cargo.toml"))) {
            ecosystems.add("cargo");
        }
        if (Files.isRegularFile(root.resolve("go.mod"))) {
            ecosystems.add("go");
        }

        List<String> workflowFiles = new ArrayList<>();
        Map<String, List<String>> verifySteps = new LinkedHashMap<>();
        Set<String> aggregators = new LinkedHashSet<>();
        Set<String> infra = new LinkedHashSet<>();
        Map<String, String> runtimeMetadata = new LinkedHashMap<>();
        Map<String, String> verificationEnvironment = new LinkedHashMap<>();
        Path workflows = root.resolve(".github/workflows");
        if (Files.isDirectory(workflows)) {
            try (var stream = Files.list(workflows)) {
                for (Path file : stream
                        .filter(Files::isRegularFile)
                        .filter(HarnessBootstrapper::isWorkflow)
                        .sorted()
                        .limit(50)
                        .toList()) {
                    String relative = root.relativize(file).toString();
                    workflowFiles.add(relative);
                    if (Files.size(file) > MAX_WORKFLOW_BYTES) {
                        warnings.add(relative + " exceeds the workflow inspection cap");
                        continue;
                    }
                    inspectWorkflow(Files.readAllLines(file, StandardCharsets.UTF_8),
                            verifySteps, aggregators, infra, runtimeMetadata,
                            verificationEnvironment);
                }
            }
            catch (IOException e) {
                warnings.add("Unable to inspect workflow directory: " + e.getMessage());
            }
        }
        if (workflowFiles.isEmpty()) {
            warnings.add("No GitHub Actions workflows found");
        }
        if (verifySteps.values().stream().allMatch(List::isEmpty)) {
            warnings.add("No locally reproducible CI run steps were discovered");
        }

        BootstrapProfile profile = new BootstrapProfile(
                "github-actions", ImmutableSet.copyOf(ecosystems), List.copyOf(workflowFiles),
                immutableLists(verifySteps), ImmutableSet.copyOf(aggregators), ImmutableSet.copyOf(infra),
                Map.copyOf(modules), Map.copyOf(runtimeMetadata),
                Map.copyOf(verificationEnvironment), List.copyOf(warnings));
        return new BootstrapResult(root, profile);
    }

    private static boolean isWorkflow(Path path)
    {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }

    /** A deliberately small YAML reader: it recognizes the stable Actions
     * job/name/needs/run/uses/with surface without pretending to implement YAML. */
    static void inspectWorkflow(
            List<String> lines,
            Map<String, List<String>> verifySteps,
            Set<String> aggregators,
            Set<String> infra,
            Map<String, String> runtimeMetadata,
            Map<String, String> verificationEnvironment)
    {
        String jobId = null;
        String jobName = null;
        int jobIndent = -1;
        boolean inJobs = false;
        boolean hasNeeds = false;
        boolean hasSubstantiveRun = false;
        boolean hasAggregatorRun = false;
        boolean hasUses = false;
        boolean infraJob = false;
        int envIndent = -1;

        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            String trimmed = stripComment(raw).strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            int indent = indentation(raw);
            if (envIndent >= 0 && indent > envIndent) {
                int colon = trimmed.indexOf(':');
                if (colon > 0) {
                    String name = trimmed.substring(0, colon).strip();
                    String value = scalar(trimmed.substring(colon + 1));
                    if (name.matches("[A-Za-z_][A-Za-z0-9_]*")
                            && !value.isBlank() && !value.contains("${{")
                            && !value.contains("\n") && !value.contains("\r")) {
                        verificationEnvironment.put(name, value);
                    }
                }
            }
            else if (envIndent >= 0 && indent <= envIndent) {
                envIndent = -1;
            }
            if ("env:".equals(trimmed)) {
                envIndent = indent;
                continue;
            }
            if ("jobs:".equals(trimmed)) {
                inJobs = true;
                continue;
            }
            if (!inJobs) {
                continue;
            }
            if (indent == 2 && trimmed.matches("[A-Za-z0-9_.-]+:")) {
                finishJob(jobId, jobName, hasNeeds, hasSubstantiveRun,
                        hasAggregatorRun, hasUses, infraJob, aggregators, infra);
                jobId = trimmed.substring(0, trimmed.length() - 1);
                jobName = jobId;
                jobIndent = indent;
                hasNeeds = false;
                hasSubstantiveRun = false;
                hasAggregatorRun = false;
                hasUses = false;
                infraJob = false;
                continue;
            }
            if (jobId == null || indent <= jobIndent) {
                continue;
            }
            if (trimmed.startsWith("name:")) {
                jobName = scalar(trimmed.substring("name:".length()));
            }
            if (trimmed.startsWith("needs:")) {
                hasNeeds = true;
            }
            String lower = trimmed.toLowerCase(Locale.ROOT);
            String step = lower.startsWith("- ") ? lower.substring(2).stripLeading() : lower;
            if (step.startsWith("uses:")) {
                hasUses = true;
            }
            if (lower.contains("${{ secrets.") || lower.contains("id-token: write")
                    || lower.contains("aws-actions/") || lower.contains("google-github-actions/")
                    || lower.contains("azure/") || lower.contains("runs-on: macos")
                    || lower.contains("runs-on: windows")) {
                infraJob = true;
            }
            if (lower.contains("uses: actions/setup-java")) {
                runtimeMetadata.put("runtime", "java");
            }
            if (lower.startsWith("java-version:")) {
                runtimeMetadata.put("java-version", scalar(trimmed.substring(trimmed.indexOf(':') + 1)));
            }
            if (step.startsWith("run:")) {
                String command = trimmed.substring(trimmed.indexOf(':') + 1).strip();
                if ("|".equals(command) || ">".equals(command) || command.isEmpty()) {
                    StringBuilder block = new StringBuilder();
                    int runIndent = indent;
                    while (i + 1 < lines.size() && indentation(lines.get(i + 1)) > runIndent) {
                        String next = lines.get(++i).strip();
                        if (!next.isBlank()) {
                            if (!block.isEmpty()) {
                                block.append(' ');
                            }
                            block.append(next);
                        }
                    }
                    command = block.toString();
                }
                if (!command.isBlank()) {
                    boolean aggregatorRun = isAggregatorCommand(command);
                    hasAggregatorRun |= aggregatorRun;
                    hasSubstantiveRun |= !aggregatorRun;
                    if (!infraJob && isLocallyReproducible(command)) {
                        verifySteps.computeIfAbsent(verb(command), ignored -> new ArrayList<>())
                                .add(command);
                    }
                }
            }
        }
        finishJob(jobId, jobName, hasNeeds, hasSubstantiveRun,
                hasAggregatorRun, hasUses, infraJob, aggregators, infra);
    }

    private static void finishJob(
            String jobId, String jobName, boolean hasNeeds, boolean hasSubstantiveRun,
            boolean hasAggregatorRun, boolean hasUses, boolean infraJob,
            Set<String> aggregators, Set<String> infra)
    {
        if (jobId == null) {
            return;
        }
        if (hasNeeds && hasAggregatorRun && !hasSubstantiveRun && !hasUses) {
            aggregators.add(jobId);
            aggregators.add(jobName);
        }
        if (infraJob) {
            infra.add(jobId);
            infra.add(jobName);
        }
    }

    private static boolean isAggregatorCommand(String command)
    {
        String lower = command.toLowerCase(Locale.ROOT);
        return lower.contains("needs.") && (lower.contains("result") || lower.contains("success"));
    }

    private static boolean isLocallyReproducible(String command)
    {
        // Integration v1 executes Maven only. This is intentionally stricter
        // than a shell parser: every accepted token is safe to pass as argv,
        // and all shell operators, substitutions, redirects and assignments
        // are rejected rather than approximated.
        return command.matches("^(?:\\./mvnw|mvn)(?:\\s+[A-Za-z0-9_@%+=:,./~-]+)*$");
    }

    private static String verb(String command)
    {
        String lower = command.toLowerCase(Locale.ROOT);
        if (lower.contains("generate") || lower.contains("snapshot") || lower.contains("update-resources")) {
            return "regen";
        }
        if (lower.contains("checkstyle") || lower.contains("spotless") || lower.contains("lint")
                || lower.contains("format") || lower.contains("ruff") || lower.contains("clippy")) {
            return "style";
        }
        if (lower.contains(" test") || lower.startsWith("test ") || lower.contains("pytest")
                || lower.contains("jest") || lower.contains("cargo test") || lower.contains("go test")) {
            return "test";
        }
        return "build";
    }

    /**
     * The repository's Maven modules as {@code "<path>/" -> "<module>"}, for
     * callers that need to scope a build to the module a commit touched without
     * provisioning a whole bootstrap worktree.
     */
    public static Map<String, String> mavenModuleMap(Path root)
    {
        return Files.isRegularFile(root.resolve("pom.xml"))
                ? readMavenModules(root, new ArrayList<>())
                : Map.of();
    }

    private static Map<String, String> readMavenModules(Path root, List<String> warnings)
    {
        Map<String, String> modules = new LinkedHashMap<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document document = factory.newDocumentBuilder().parse(root.resolve("pom.xml").toFile());
            NodeList nodes = document.getElementsByTagName("module");
            for (int i = 0; i < nodes.getLength(); i++) {
                String module = nodes.item(i).getTextContent().strip();
                if (!module.isBlank() && !module.contains("..")) {
                    modules.put(module + "/", module);
                }
            }
        }
        catch (Exception e) {
            warnings.add("Unable to derive Maven modules: " + e.getMessage());
        }
        return modules;
    }

    private static int indentation(String line)
    {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    private static String stripComment(String line)
    {
        int hash = line.indexOf('#');
        return hash < 0 ? line : line.substring(0, hash);
    }

    private static String scalar(String value)
    {
        String out = value.strip();
        if (out.length() >= 2 && ((out.startsWith("\"") && out.endsWith("\""))
                || (out.startsWith("'") && out.endsWith("'")))) {
            return out.substring(1, out.length() - 1);
        }
        return out;
    }

    private static Map<String, List<String>> immutableLists(Map<String, List<String>> source)
    {
        Map<String, List<String>> out = new LinkedHashMap<>();
        source.forEach((key, value) -> out.put(key, List.copyOf(value)));
        return Map.copyOf(out);
    }

    public record BootstrapResult(Path root, BootstrapProfile profile) {}
}
