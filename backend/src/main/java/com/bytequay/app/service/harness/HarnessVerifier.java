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
import com.bytequay.app.service.harness.HarnessModels.CommandResult;
import com.bytequay.app.service.harness.HarnessModels.FixResult;
import com.bytequay.app.service.harness.HarnessModels.VerificationResult;
import com.bytequay.app.service.harness.HarnessModels.VerifiedFix;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.local.ShellRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/** Executes only CI-derived, plain-argv Maven verification steps. */
@Component
public class HarnessVerifier
{
    private static final long TIMEOUT_SECONDS = 600;
    private static final int OUTPUT_CAP = 256 * 1024;
    private static final int MAX_COMMANDS = 12;
    private static final List<String> VERB_ORDER = List.of("regen", "style", "build", "test");

    private final ShellRunner shell;
    private final GitRunner git;

    public HarnessVerifier(ShellRunner shell, GitRunner git)
    {
        this.shell = requireNonNull(shell, "shell is null");
        this.git = requireNonNull(git, "git is null");
    }

    public VerifiedFix verify(
            Path root, FixResult fix, BootstrapProfile profile, String module)
    {
        Set<String> requested = new LinkedHashSet<>();
        try {
            if (fix.verifyCommands() != null) {
                fix.verifyCommands().stream()
                        .map(HarnessModels::verifyVerb)
                        .forEach(requested::add);
            }
        }
        catch (IllegalArgumentException e) {
            return failed(root, fix, false, List.of(), e.getMessage());
        }
        if (requested.isEmpty()) {
            return failed(root, fix, false, List.of(),
                    "Fix did not request a generic CI verification verb");
        }

        List<Step> regeneration = new ArrayList<>();
        List<Step> checks = new ArrayList<>();
        try {
            for (String verb : VERB_ORDER) {
                if (!requested.contains(verb)) {
                    continue;
                }
                List<String> commands = profile.verifySteps().getOrDefault(verb, List.of());
                if (commands.isEmpty()) {
                    return failed(root, fix, false, List.of(),
                            "No locally reproducible CI step was discovered for " + verb);
                }
                for (String command : new LinkedHashSet<>(commands)) {
                    Step step = new Step(command, argv(command, module, profile));
                    ("regen".equals(verb) ? regeneration : checks).add(step);
                }
            }
        }
        catch (RuntimeException e) {
            return failed(root, fix, false, List.of(), e.getMessage());
        }
        if (checks.size() + (2 * regeneration.size()) > MAX_COMMANDS) {
            return failed(root, fix, false, List.of(),
                    "Requested CI verification exceeds the local command cap");
        }

        Map<String, String> environment = new LinkedHashMap<>(profile.verificationEnvironment());
        environment.put("CI", "true");
        Map<String, String> safeEnvironment = Map.copyOf(environment);
        List<CommandResult> results = new ArrayList<>();

        for (Step step : regeneration) {
            CommandResult result = run(root, step.command(), step.argv(), safeEnvironment);
            results.add(result);
            if (result.exitCode() != 0 || result.timedOut()) {
                return failed(root, fix, true, results,
                        "CI-derived regeneration failed: " + step.command());
            }
        }

        FixResult prepared = fix;
        if (!regeneration.isEmpty()) {
            try {
                WorktreeState once = worktreeState(root, fix);
                prepared = once.fix();
                for (Step step : regeneration) {
                    CommandResult result = run(root, step.command(), step.argv(), safeEnvironment);
                    results.add(result);
                    if (result.exitCode() != 0 || result.timedOut()) {
                        return failed(root, prepared, true, results,
                                "Regeneration could not be repeated for idempotence proof");
                    }
                }
                WorktreeState twice = worktreeState(root, prepared);
                prepared = twice.fix();
                if (!once.fingerprint().equals(twice.fingerprint())) {
                    return result(prepared, false, true, results,
                            "Regeneration is not idempotent; the second run changed the worktree");
                }
            }
            catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return failed(root, prepared, true, results,
                        "Unable to prove regeneration idempotence: " + e.getMessage());
            }
        }

        WorktreeState beforeChecks;
        try {
            beforeChecks = worktreeState(root, prepared);
            prepared = beforeChecks.fix();
        }
        catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return result(prepared, false, true, results,
                    "Unable to fingerprint the prepared fix: " + e.getMessage());
        }
        for (Step step : checks) {
            CommandResult command = run(root, step.command(), step.argv(), safeEnvironment);
            results.add(command);
            if (command.exitCode() != 0 || command.timedOut()) {
                return failed(root, prepared, true, results,
                        "CI-derived verification failed: " + step.command());
            }
        }
        try {
            WorktreeState afterChecks = worktreeState(root, prepared);
            prepared = afterChecks.fix();
            if (!beforeChecks.fingerprint().equals(afterChecks.fingerprint())) {
                return result(prepared, false, true, results,
                        "CI verification mutated the prepared fix; refusing the widened delta");
            }
        }
        catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return result(prepared, false, true, results,
                    "Unable to capture the verified fix paths: " + e.getMessage());
        }
        if (prepared.filesChanged().isEmpty()) {
            return result(prepared, false, true, results,
                    "Verification produced no worktree change to commit");
        }
        return result(prepared, true, true, results, "all requested CI-derived steps passed");
    }

    private VerifiedFix failed(
            Path root, FixResult fix, boolean reproducible,
            List<CommandResult> commands, String reason)
    {
        try {
            fix = worktreeState(root, fix).fix();
        }
        catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            reason += "; unable to capture all fix paths: " + e.getMessage();
        }
        return result(fix, false, reproducible, commands, reason);
    }

    private static VerifiedFix result(
            FixResult fix, boolean passed, boolean reproducible,
            List<CommandResult> commands, String reason)
    {
        return new VerifiedFix(fix, new VerificationResult(
                passed, reproducible, List.copyOf(commands), reason));
    }

    private CommandResult run(
            Path root, String command, List<String> argv, Map<String, String> environment)
    {
        try {
            ShellRunner.Result result = shell.runArgv(
                    root, argv, environment, TIMEOUT_SECONDS, OUTPUT_CAP);
            boolean timedOut = result.error() != null && result.error().startsWith("timed out");
            int exitCode = result.ran() ? result.exitCode() : -1;
            String output = result.output().isBlank() ? String.valueOf(result.error()) : result.output();
            return new CommandResult(command, exitCode, timedOut, tail(output));
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult(command, -1, true, "verification interrupted");
        }
    }

    static List<String> argv(String command, String module, BootstrapProfile profile)
    {
        if (command == null || !command.matches(
                "^(?:\\./mvnw|mvn)(?:\\s+[A-Za-z0-9_@%+=:,./~-]+)*$")) {
            throw new IllegalArgumentException("unsafe or unsupported CI command: " + command);
        }
        List<String> argv = new ArrayList<>(List.of(command.strip().split("\\s+")));
        if (module != null && !module.isBlank() && !"root".equals(module)
                && profile.modules().containsValue(module)
                && argv.stream().noneMatch(token -> token.equals("-pl") || token.startsWith("-pl="))) {
            argv.add(1, "-am");
            argv.add(1, module);
            argv.add(1, "-pl");
        }
        return List.copyOf(argv);
    }

    private WorktreeState worktreeState(Path root, FixResult fix)
            throws IOException, InterruptedException
    {
        String status = git.statusPorcelainZ(root);
        List<String> files = changedPaths(status);
        FixResult captured = new FixResult(
                files, fix.targetSubject(), fix.verifyCommands(), fix.source());
        Map<String, String> fingerprint = new LinkedHashMap<>();
        fingerprint.put("$status", status);
        Path normalizedRoot = root.toAbsolutePath().normalize();
        for (String relative : files) {
            Path file = normalizedRoot.resolve(relative).normalize();
            if (!file.startsWith(normalizedRoot)) {
                fingerprint.put(relative, "<outside>");
            }
            else if (Files.isSymbolicLink(file)) {
                fingerprint.put(relative, "<symlink>" + Files.readSymbolicLink(file));
            }
            else if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                fingerprint.put(relative, "<absent>");
            }
            else {
                fingerprint.put(relative, sha256(Files.readAllBytes(file)));
            }
        }
        return new WorktreeState(captured, Map.copyOf(fingerprint));
    }

    static List<String> changedPaths(String status)
    {
        Set<String> paths = new LinkedHashSet<>();
        String[] records = status.split("\\u0000", -1);
        for (int i = 0; i < records.length; i++) {
            String record = records[i];
            if (record.length() < 4) {
                continue;
            }
            paths.add(record.substring(3));
            char index = record.charAt(0);
            char worktree = record.charAt(1);
            if ((index == 'R' || index == 'C' || worktree == 'R' || worktree == 'C')
                    && i + 1 < records.length && !records[i + 1].isEmpty()) {
                paths.add(records[++i]);
            }
        }
        return paths.stream().sorted().toList();
    }

    private static String sha256(byte[] bytes)
    {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String tail(String output)
    {
        if (output == null) {
            return "";
        }
        List<String> lines = output.lines().toList();
        String value = String.join("\n", lines.subList(Math.max(0, lines.size() - 60), lines.size()));
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length <= 16_000) {
            return value;
        }
        return new String(encoded, encoded.length - 16_000, 16_000, StandardCharsets.UTF_8);
    }

    private record Step(String command, List<String> argv) {}

    private record WorktreeState(FixResult fix, Map<String, String> fingerprint) {}
}
