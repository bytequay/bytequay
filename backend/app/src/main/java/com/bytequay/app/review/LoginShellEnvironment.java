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

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/**
 * The user's own environment for commands run on their behalf, read once from an interactive login shell.
 *
 * <p>A Finder-launched app inherits launchd's environment, which has neither the user's {@code PATH} nor a locale.
 * ByteQuay's own {@code BYTEQUAY_*} variables, including the session token, never reach the probe or its result.
 */
@Component
class LoginShellEnvironment
{
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern JAVA_VERSION = Pattern.compile("JAVA_VERSION=\"(\\d+)(?:\\.(\\d+))?.*\"");

    private final Map<String, String> backendEnvironment;
    private final Path javaHomeTool;
    private Optional<Map<String, String>> environment = Optional.empty();

    LoginShellEnvironment()
    {
        this(System.getenv(), Path.of("/usr/libexec/java_home"));
    }

    LoginShellEnvironment(Map<String, String> backendEnvironment, Path javaHomeTool)
    {
        this.backendEnvironment = ImmutableMap.copyOf(backendEnvironment);
        this.javaHomeTool = requireNonNull(javaHomeTool, "javaHomeTool is null");
    }

    /** The cached login environment; a failed probe is not cached, so the next run tries again. */
    synchronized Map<String, String> get()
    {
        if (environment.isEmpty()) {
            environment = Optional.of(probe());
        }
        return environment.orElseThrow();
    }

    /** The newest installed JDK, when its feature release is at least the given one. */
    Optional<Path> javaHomeAtLeast(int feature)
    {
        if (!executable(javaHomeTool)) {
            return Optional.empty();
        }
        // The tool's own version filter falls back to the default JDK when nothing matches, so the release file decides.
        return run(withoutByteQuay(backendEnvironment), new ProcessBuilder(javaHomeTool.toString()))
                .map(String::strip)
                .filter(home -> !home.isEmpty())
                .map(Path::of)
                .filter(home -> featureRelease(home).filter(release -> release >= feature).isPresent());
    }

    private static Optional<Integer> featureRelease(Path javaHome)
    {
        try {
            for (String line : Files.readAllLines(javaHome.resolve("release"))) {
                Matcher version = JAVA_VERSION.matcher(line);
                if (version.matches()) {
                    // Releases before 9 are numbered 1.x.
                    boolean legacy = version.group(1).equals("1") && version.group(2) != null;
                    return Optional.of(Integer.parseInt(version.group(legacy ? 2 : 1)));
                }
            }
        }
        catch (IOException | NumberFormatException failure) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private Map<String, String> probe()
    {
        Map<String, String> probeEnvironment = withoutByteQuay(backendEnvironment);
        probeEnvironment.putIfAbsent("LANG", "en_US.UTF-8");
        String shell = Optional.ofNullable(backendEnvironment.get("SHELL")).filter(value -> !value.isBlank()).orElse("/bin/zsh");
        String output = run(probeEnvironment, new ProcessBuilder(shell, "-ilc", "env -0"))
                .orElseThrow(() -> new LocalRunRefusedException("Your login shell environment could not be read"));
        Map<String, String> login = new HashMap<>();
        for (String entry : Splitter.on('\0').omitEmptyStrings().split(output)) {
            int equals = entry.indexOf('=');
            if (equals < 0) {
                continue;
            }
            // Startup files may print text before the first entry.
            String name = entry.substring(entry.lastIndexOf('\n', equals) + 1, equals);
            if (NAME.matcher(name).matches()) {
                login.put(name, entry.substring(equals + 1));
            }
        }
        return ImmutableMap.copyOf(withoutByteQuay(login));
    }

    private static Map<String, String> withoutByteQuay(Map<String, String> variables)
    {
        Map<String, String> copy = new HashMap<>(variables);
        copy.keySet().removeIf(name -> name.startsWith("BYTEQUAY_"));
        return copy;
    }

    /** Standard output of a command that exited 0 within the probe timeout, run with exactly the given environment. */
    private static Optional<String> run(Map<String, String> environment, ProcessBuilder builder)
    {
        Path output;
        try {
            // Owner-only, since the output is the user's environment.
            output = Files.createTempFile("bytequay-login-shell", ".out");
        }
        catch (IOException failure) {
            return Optional.empty();
        }
        try {
            builder.environment().clear();
            builder.environment().putAll(environment);
            // A file rather than a pipe: a background job a startup file leaves behind can hold a pipe open long after the shell exits.
            builder.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                    .redirectOutput(output.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            Process process = builder.start();
            try {
                if (!process.waitFor(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS) || process.exitValue() != 0) {
                    return Optional.empty();
                }
                return Optional.of(new String(Files.readAllBytes(output), UTF_8));
            }
            finally {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
        catch (IOException failure) {
            return Optional.empty();
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        finally {
            try {
                Files.deleteIfExists(output);
            }
            catch (IOException ignored) {
                // The temporary directory is cleaned by the system.
            }
        }
    }

    private static boolean executable(Path file)
    {
        return Files.isRegularFile(file) && Files.isExecutable(file);
    }
}
