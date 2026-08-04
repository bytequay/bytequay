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

import com.bytequay.app.service.local.ShellRunner;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * The per-commit hard gate while a cherry-pick is running: does this commit
 * <em>compile</em>. Deliberately not the full CI suite — a range can be hundreds
 * of commits, and whether the series is genuinely green is judged once, on the
 * final commit, by the harness watch on the pull request.
 *
 * <p>Compilation is the right gate for a conflicted pick specifically: a commit
 * that still holds conflict markers cannot parse, so the gate cannot be
 * satisfied until the markers are gone.
 */
@Component
public class CherryPickCompileGate
{
    /** Only build tools, never a shell: no operators, redirects or substitutions. */
    private static final String SAFE_COMMAND = "^(?:\\./mvnw|mvn)(?:\\s+[A-Za-z0-9_@%+=:,./~-]+)*$";
    private static final String DEFAULT_COMMAND = "./mvnw clean install -DskipTests";
    private static final long TIMEOUT_SECONDS = 900;
    private static final int OUTPUT_CAP = 256 * 1024;

    private final ShellRunner shell;

    public CherryPickCompileGate(ShellRunner shell)
    {
        this.shell = requireNonNull(shell, "shell is null");
    }

    /**
     * Resolution order, most explicit first:
     * <ol>
     *   <li>a run script the user typed on the request;</li>
     *   <li>the script learned from the CI job the user named;</li>
     *   <li>a plain compile of the whole project.</li>
     * </ol>
     *
     * @param userScript   verbatim script from the request, may be null
     * @param learnedFromCi script derived from the named CI job, may be null
     * @param module       module the commit touched, scoped with {@code -pl} when known
     */
    public static Resolution resolve(
            String userScript,
            String learnedFromCi,
            String module,
            Set<String> knownModules)
    {
        if (notBlank(userScript)) {
            return new Resolution(argv(userScript.strip(), module, knownModules), "script");
        }
        if (notBlank(learnedFromCi)) {
            return new Resolution(argv(learnedFromCi.strip(), module, knownModules), "ci-job");
        }
        return new Resolution(argv(DEFAULT_COMMAND, module, knownModules), "default");
    }

    /** Rejected rather than escaped: an unrunnable command must not look like a red gate. */
    public static void validateScript(String script)
    {
        if (notBlank(script) && !script.strip().matches(SAFE_COMMAND)) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "compile command must be a plain mvn/./mvnw invocation: " + script);
        }
    }

    private static List<String> argv(String command, String module, Set<String> knownModules)
    {
        if (!command.matches(SAFE_COMMAND)) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "compile command must be a plain mvn/./mvnw invocation: " + command);
        }
        List<String> argv = new ArrayList<>(List.of(command.split("\\s+")));
        boolean alreadyScoped = argv.stream()
                .anyMatch(token -> token.equals("-pl") || token.startsWith("-pl="));
        if (module != null && !module.isBlank() && !"root".equals(module)
                && knownModules != null && knownModules.contains(module)
                && !alreadyScoped) {
            argv.add(1, "-am");
            argv.add(1, module);
            argv.add(1, "-pl");
        }
        return List.copyOf(argv);
    }

    public Outcome run(Path worktree, Resolution resolution)
            throws InterruptedException
    {
        ShellRunner.Result result = shell.runArgv(
                worktree, resolution.argv(), Map.of("CI", "true"), TIMEOUT_SECONDS, OUTPUT_CAP);
        boolean timedOut = result.error() != null && result.error().startsWith("timed out");
        // A command that never ran is not a compile failure. Reporting it as one
        // would send the agent chasing a defect that is not in the code.
        if (!result.ran()) {
            return new Outcome(false, false, String.valueOf(result.error()));
        }
        String output = result.output().isBlank()
                ? String.valueOf(result.error())
                : result.output();
        return new Outcome(!timedOut && result.exitCode() == 0, true, tail(output));
    }

    private static String tail(String output)
    {
        if (output == null) {
            return "";
        }
        List<String> lines = output.lines().toList();
        return String.join("\n", lines.subList(Math.max(0, lines.size() - 80), lines.size()));
    }

    private static boolean notBlank(String value)
    {
        return value != null && !value.isBlank();
    }

    public record Resolution(List<String> argv, String source) {}

    /**
     * @param compiled   the gate is satisfied
     * @param reproduced the command actually executed; false means the toolchain
     *                   is unavailable, which is an escalation, not a red gate
     */
    public record Outcome(boolean compiled, boolean reproduced, String outputTail) {}
}
