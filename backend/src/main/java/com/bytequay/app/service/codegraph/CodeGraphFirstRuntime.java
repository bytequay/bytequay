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
package com.bytequay.app.service.codegraph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Per-turn state and command shims for the CodeGraph-first CLI policy.
 *
 * <p>The provider CLIs launch their own shell tools, outside ByteQuay's MCP
 * dispatcher. Prepending a tiny managed shim directory to their {@code PATH}
 * lets ByteQuay redirect broad discovery commands before the real executable
 * runs. The shims never replace normal command execution permanently: one
 * CodeGraph attempt unlocks the real commands, and two ignored redirects fail
 * open so a missing tool cannot strand a turn.
 *
 * <p>State is scoped by thread + registry agent key and reset immediately
 * before each CLI subprocess starts. Files under the process-private temp
 * directory make the state visible both to the backend MCP handler and to
 * sandboxed child commands without opening a localhost network path.
 */
public final class CodeGraphFirstRuntime
{
    private static final Logger log = LoggerFactory.getLogger(CodeGraphFirstRuntime.class);

    private static final int MAX_REDIRECTS = 2;
    private static final String ATTEMPTED_FILE = "codegraph-attempted";
    private static final String REDIRECT_COUNT_FILE = "redirect-count";
    private static final String STATE_DIRECTORY_ENV = "BYTEQUAY_CODEGRAPH_STATE_DIR";
    private static final String SHIM_DIRECTORY_ENV = "BYTEQUAY_CODEGRAPH_SHIM_DIR";
    private static final Set<String> GUARDED_COMMANDS = Set.of(
            "rg", "grep", "egrep", "fgrep", "git", "find", "fd", "fdfind", "tree");

    private static final Path ROOT = Path.of(System.getProperty("java.io.tmpdir"),
            "bytequay-codegraph-first-" + ProcessHandle.current().pid());
    private static final Path SHIM_DIRECTORY = ROOT.resolve("bin-v1");
    private static final Object LOCK = new Object();
    private static volatile boolean shimsReady;

    private CodeGraphFirstRuntime() {}

    /** Reset one agent's turn state and install the managed search shims. */
    public static void prepare(ProcessBuilder process, String threadId, String agentKey)
    {
        requireNonNull(process, "process is null");
        if (!validScope(threadId, agentKey)) {
            return;
        }
        try {
            Path stateDirectory = stateDirectory(threadId, agentKey);
            Files.createDirectories(stateDirectory);
            Files.deleteIfExists(stateDirectory.resolve(ATTEMPTED_FILE));
            Files.deleteIfExists(stateDirectory.resolve(REDIRECT_COUNT_FILE));
            ensureShims();

            String currentPath = process.environment().getOrDefault("PATH", "");
            String shimPrefix = SHIM_DIRECTORY + File.pathSeparator;
            if (!currentPath.startsWith(shimPrefix)) {
                process.environment().put("PATH", shimPrefix + currentPath);
            }
            process.environment().put(STATE_DIRECTORY_ENV, stateDirectory.toString());
            process.environment().put(SHIM_DIRECTORY_ENV, SHIM_DIRECTORY.toString());
        }
        catch (IOException | RuntimeException e) {
            // Preference policy, not a correctness/security boundary: a temp
            // directory problem must never prevent the user's task from running.
            log.warn("Could not prepare CodeGraph-first command shims for {}/{}: {}",
                    threadId, agentKey, e.getMessage());
        }
    }

    /** Mark CodeGraph as attempted, including unavailable/error outcomes. */
    public static void markAttempted(String threadId, String agentKey)
    {
        if (!validScope(threadId, agentKey)) {
            return;
        }
        try {
            Path directory = stateDirectory(threadId, agentKey);
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(ATTEMPTED_FILE), "attempted\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        }
        catch (IOException | RuntimeException e) {
            log.warn("Could not record CodeGraph attempt for {}/{}: {}",
                    threadId, agentKey, e.getMessage());
        }
    }

    /**
     * Claim one redirect slot for a structured CLI tool call.
     *
     * @return true when the caller should reject the pending discovery call;
     * false after CodeGraph was attempted, after the fail-open cap, or when
     * the state cannot be read/written
     */
    public static boolean shouldRedirect(String threadId, String agentKey)
    {
        if (!validScope(threadId, agentKey)) {
            return false;
        }
        synchronized (LOCK) {
            try {
                Path directory = stateDirectory(threadId, agentKey);
                Files.createDirectories(directory);
                if (Files.isRegularFile(directory.resolve(ATTEMPTED_FILE))) {
                    return false;
                }
                Path countFile = directory.resolve(REDIRECT_COUNT_FILE);
                int count = readRedirectCount(countFile);
                if (count >= MAX_REDIRECTS) {
                    return false;
                }
                Files.writeString(countFile, Integer.toString(count + 1) + "\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);
                return true;
            }
            catch (IOException | RuntimeException e) {
                log.warn("Could not evaluate CodeGraph-first policy for {}/{}: {}",
                        threadId, agentKey, e.getMessage());
                return false;
            }
        }
    }

    private static int readRedirectCount(Path countFile)
            throws IOException
    {
        if (!Files.isRegularFile(countFile)) {
            return 0;
        }
        try {
            return Integer.parseInt(Files.readString(countFile, StandardCharsets.UTF_8).strip());
        }
        catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean validScope(String threadId, String agentKey)
    {
        return threadId != null && !threadId.isBlank()
                && agentKey != null && !agentKey.isBlank();
    }

    private static Path stateDirectory(String threadId, String agentKey)
    {
        return ROOT.resolve("state").resolve(scopeHash(threadId, agentKey));
    }

    private static String scopeHash(String threadId, String agentKey)
    {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((threadId + "\0" + agentKey)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void ensureShims()
            throws IOException
    {
        if (shimsReady) {
            return;
        }
        synchronized (LOCK) {
            if (shimsReady) {
                return;
            }
            Files.createDirectories(SHIM_DIRECTORY);
            for (String command : GUARDED_COMMANDS) {
                Path shim = SHIM_DIRECTORY.resolve(command);
                Files.writeString(shim, SHIM_SCRIPT, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                shim.toFile().setExecutable(true, true);
            }
            shimsReady = true;
        }
    }

    /**
     * POSIX shim shared by the guarded command names above. It resolves the
     * real executable from PATH after removing the managed shim directory,
     * classifies only broad discovery shapes, and otherwise execs immediately.
     */
    private static final String SHIM_SCRIPT = """
            #!/bin/sh
            tool=${0##*/}
            shim_dir=${BYTEQUAY_CODEGRAPH_SHIM_DIR:-${0%/*}}
            old_ifs=$IFS
            IFS=:
            real_path=
            for entry in $PATH; do
              [ "$entry" = "$shim_dir" ] && continue
              if [ -z "$real_path" ]; then
                real_path=$entry
              else
                real_path=$real_path:$entry
              fi
            done
            IFS=$old_ifs
            real=$(PATH="$real_path" command -v "$tool" 2>/dev/null || true)
            if [ -z "$real" ]; then
              printf '%s: command not found\n' "$tool" >&2
              exit 127
            fi

            state_dir=${BYTEQUAY_CODEGRAPH_STATE_DIR:-}
            if [ -z "$state_dir" ] || [ -f "$state_dir/codegraph-attempted" ]; then
              exec "$real" "$@"
            fi

            fixed=0
            explicit_file=0
            guidance_file=0
            recursive=0
            for arg in "$@"; do
              case "$arg" in
                -F|--fixed-strings) fixed=1 ;;
                -*r*|-*R*|--recursive) recursive=1 ;;
                AGENTS.md|*/AGENTS.md|CLAUDE.md|*/CLAUDE.md) guidance_file=1 ;;
              esac
              if [ -f "$arg" ]; then
                explicit_file=1
              fi
            done

            broad=0
            case "$tool" in
              rg)
                if [ "$fixed" -eq 0 ] && [ "$explicit_file" -eq 0 ]; then
                  case " $* " in
                    *" --files "*) [ "$guidance_file" -eq 0 ] && broad=1 ;;
                    *) broad=1 ;;
                  esac
                fi
                ;;
              grep|egrep|fgrep)
                [ "$recursive" -eq 1 ] && [ "$fixed" -eq 0 ] \
                  && [ "$explicit_file" -eq 0 ] && broad=1
                ;;
              git)
                [ "${1:-}" = "grep" ] && [ "$fixed" -eq 0 ] \
                  && [ "$explicit_file" -eq 0 ] && broad=1
                ;;
              find|fd|fdfind)
                [ "$guidance_file" -eq 0 ] && broad=1
                ;;
              tree)
                broad=1
                ;;
            esac

            if [ "$broad" -eq 0 ]; then
              exec "$real" "$@"
            fi

            count=0
            if [ -r "$state_dir/redirect-count" ]; then
              count=$(sed -n '1p' "$state_dir/redirect-count" 2>/dev/null || true)
            fi
            case "$count" in
              ''|*[!0-9]*) count=0 ;;
            esac
            if [ "$count" -ge 2 ]; then
              exec "$real" "$@"
            fi
            next=$((count + 1))
            if ! (umask 077 && printf '%s\n' "$next" > "$state_dir/redirect-count"); then
              exec "$real" "$@"
            fi

            cat >&2 <<'BYTEQUAY_CODEGRAPH_MESSAGE'
            Blocked by ByteQuay's CodeGraph-first exploration policy.
            This looks like broad repository discovery. Call this tool first:

            mcp__bytequay__codegraph_explore({"query":"Map the code relevant to the current task. Return the main implementation files, symbols, callers, tests, and change impact."})

            Then use native search for exact literal checks or completeness verification.
            If CodeGraph is unavailable, retry; ByteQuay fails open after two redirects.
            BYTEQUAY_CODEGRAPH_MESSAGE
            exit 2
            """;
}
