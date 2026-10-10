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
package com.bytequay.app.google;

import com.bytequay.agent.cli.process.Utf8LineBuffer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/** Owns a local connector process, never a Gemini model session or OAuth credentials. */
@Component
public class WorkspaceConnector
{
    private static final String SOURCE = "https://github.com/gemini-cli-extensions/workspace";
    private static final String REVISION = "089927ead01433f38c65c12cdcd2ed9a18165277";
    private static final Set<String> TOOLS = ImmutableSet.of("people_getMe", "gmail_search", "gmail_get", "calendar_list", "calendar_listEvents",
            "gmail_modify", "gmail_send", "gmail_createDraft", "gmail_sendDraft");
    private final ObjectMapper mapper;
    private final Path directory;
    private final String node;
    private final String gemini;
    private final String npm;

    public WorkspaceConnector(ObjectMapper mapper,
            @Value("${bytequay.google.extension-directory:${user.home}/.gemini/extensions/google-workspace}") Path directory,
            @Value("${bytequay.google.node:node}") String node,
            @Value("${bytequay.google.gemini:gemini}") String gemini,
            @Value("${bytequay.google.npm:npm}") String npm)
    {
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.directory = requireNonNull(directory, "directory is null").toAbsolutePath().normalize();
        this.node = requireNonNull(node, "node is null");
        this.gemini = requireNonNull(gemini, "gemini is null");
        this.npm = requireNonNull(npm, "npm is null");
    }

    public boolean installed()
    {
        return Files.isRegularFile(directory.resolve("workspace-server/dist/index.js"));
    }

    public void install()
    {
        // Reuse the user's extension without replacing an existing checkout or changing its auto-update policy.
        if (!Files.isRegularFile(directory.resolve("gemini-extension.json"))) {
            run(ImmutableList.of(gemini, "extensions", "install", SOURCE, "--ref", REVISION, "--consent", "--skip-settings"),
                    Path.of(System.getProperty("user.home")), "Install Gemini CLI first, then retry installing the Google connector.");
        }
        checkState(Files.isRegularFile(directory.resolve("gemini-extension.json")), "The Google Workspace extension was not found after installation.");
        run(ImmutableList.of(npm, Files.isRegularFile(directory.resolve("package-lock.json")) ? "ci" : "install", "--no-audit", "--no-fund"),
                directory, "Connector setup failed. Check Node.js, npm and your network, then retry.");
        checkState(installed(), "The Google connector did not finish building. Retry installation.");
    }

    // The upstream extension hard-codes one credential namespace. Isolate both Keychain and its encrypted-file fallback without editing the installation.
    static final String PROFILE_BOOTSTRAP = """
            const fs = require('node:fs'), path = require('node:path'), Module = require('node:module');
            const [entry, root, profile] = process.argv.slice(1);
            const allowed = new Set(['PATH', 'HOME', 'TMPDIR', 'LANG', 'LC_ALL', 'BROWSER', 'HTTP_PROXY', 'HTTPS_PROXY', 'NO_PROXY']);
            for (const key of Object.keys(process.env)) if (!allowed.has(key)) delete process.env[key];
            if (!/^[a-f0-9-]{36}$/.test(profile)) throw new Error('Invalid Google profile');
            let source = fs.readFileSync(entry, 'utf8');
            const marker = '"gemini-cli-workspace-oauth"';
            if (source.split(marker).length !== 2) throw new Error('Unsupported Workspace connector credential layout. Repair connector.');
            source = source.replace(marker, JSON.stringify('com.bytequay.google.' + profile));
            const emailScope = '"userinfo.profile"';
            if (source.split(emailScope).length !== 2) throw new Error('Unsupported Workspace connector identity scope layout. Repair connector.');
            source = source.replace(emailScope, '"userinfo.profile","userinfo.email"');
            const filename = path.join(root, 'workspace-server/dist/index.js');
            fs.mkdirSync(path.dirname(filename), { recursive: true, mode: 0o700 });
            fs.writeFileSync(path.join(root, 'gemini-extension.json'), '{}', { mode: 0o600 });
            const mod = new Module(filename);
            mod.filename = filename;
            mod.paths = Module._nodeModulePaths(path.dirname(entry));
            mod._compile(source, filename);
            """;

    public List<String> profileCommand(String profile)
    {
        checkArgument(profile != null && profile.matches("[a-f0-9-]{36}"), "Invalid Google profile.");
        Path root = Path.of(System.getProperty("user.home"), "Library", "Application Support", "ByteQuay", "google-accounts", profile);
        return command(ImmutableList.of(node, "-e", PROFILE_BOOTSTRAP, directory.resolve("workspace-server/dist/index.js").toString(), root.toString(), profile),
                System.getenv()).command();
    }

    public Session open(String profile, boolean interactive)
    {
        checkState(installed(), "Install the Google connector in Settings first.");
        try {
            ProcessBuilder builder = command(profileCommand(profile), System.getenv());
            builder.directory(directory.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
            if (!interactive) {
                // Refresh and agent reads must never open an unexpected login window.
                builder.environment().put("BROWSER", "www-browser");
            }
            return new Session(builder.start(), mapper, interactive ? Duration.ofMinutes(6) : Duration.ofSeconds(45));
        }
        catch (IOException failure) {
            throw new IllegalStateException("Cannot start the Google connector. Check that Node.js is installed.");
        }
    }

    private static void run(List<String> command, Path cwd, String error)
    {
        Process process = null;
        try {
            process = command(command, System.getenv()).directory(cwd.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            process.getOutputStream().close();
            checkState(process.waitFor(10, TimeUnit.MINUTES) && process.exitValue() == 0, error);
        }
        catch (IOException failure) {
            throw new IllegalStateException(error);
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Google connector setup was interrupted.");
        }
        finally {
            if (process != null) {
                stop(process);
            }
        }
    }

    private static void stop(Process process)
    {
        try {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
        }
        finally {
            process.destroyForcibly();
        }
    }

    static ProcessBuilder command(List<String> arguments, Map<String, String> environment)
    {
        // Finder does not inherit Homebrew's PATH. Resolve the executable as well as the PATH used by npm's Node shebang.
        String path = environment.getOrDefault("PATH", "/usr/bin:/bin") + ":/opt/homebrew/bin:/usr/local/bin";
        String executable = arguments.getFirst();
        if (!executable.contains("/")) {
            executable = Stream.of(path.split(":"))
                    .filter(value -> !value.isBlank() && Path.of(value).isAbsolute())
                    .map(value -> Path.of(value).resolve(arguments.getFirst()))
                    .filter(value -> Files.isRegularFile(value) && Files.isExecutable(value))
                    .map(Path::toString).findFirst().orElse(executable);
        }
        ProcessBuilder builder = new ProcessBuilder(ImmutableList.<String>builder().add(executable).addAll(arguments.subList(1, arguments.size())).build());
        // The sidecar's desktop token and model credentials must not become connector or npm environment variables.
        builder.environment().clear();
        builder.environment().putAll(environment);
        builder.environment().keySet().retainAll(ImmutableSet.of("PATH", "HOME", "TMPDIR", "TMP", "TEMP", "LANG", "LC_ALL",
                "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "http_proxy", "https_proxy", "no_proxy", "SSL_CERT_FILE", "NODE_EXTRA_CA_CERTS"));
        builder.environment().put("PATH", path);
        return builder;
    }

    public static class Session
            implements AutoCloseable
    {
        private final Process process;
        private final ObjectMapper mapper;
        private final Duration timeout;
        private final ArrayBlockingQueue<JsonNode> responses = new ArrayBlockingQueue<>(32);
        private int sequence;

        Session(Process process, ObjectMapper mapper, Duration timeout)
        {
            this.process = requireNonNull(process, "process is null");
            this.mapper = requireNonNull(mapper, "mapper is null");
            this.timeout = requireNonNull(timeout, "timeout is null");
            Thread.ofVirtual().name("google-workspace-output").start(this::read);
            try {
                JsonNode initialized = request("initialize", ImmutableMap.of("protocolVersion", "2024-11-05", "capabilities", ImmutableMap.of(),
                        "clientInfo", ImmutableMap.of("name", "ByteQuay", "version", "1")));
                checkState(initialized.path("capabilities").has("tools"), "The Google connector does not provide tools.");
                send(ImmutableMap.of("jsonrpc", "2.0", "method", "notifications/initialized"));
            }
            catch (RuntimeException failure) {
                close();
                throw failure;
            }
        }

        public JsonNode call(String tool, Map<String, Object> arguments)
        {
            checkArgument(TOOLS.contains(tool), "This Google tool is not available in ByteQuay.");
            JsonNode result = request("tools/call", ImmutableMap.of("name", tool, "arguments", arguments));
            checkState(!result.path("isError").asBoolean(), "Google could not complete %s. Reconnect in Settings and check account permissions.", tool);
            try {
                for (JsonNode content : result.path("content")) {
                    if (content.path("type").asText().equals("text")) {
                        JsonNode data = mapper.readTree(content.path("text").asText());
                        // Some upstream tools return errors as ordinary JSON text instead of MCP isError.
                        checkState(data != null && !data.has("error"), "Google could not complete %s. Reconnect in Settings and check account permissions.", tool);
                        return data;
                    }
                }
            }
            catch (IOException failure) {
                throw new IllegalStateException("The Google connector returned an unreadable result.");
            }
            throw new IllegalStateException("The Google connector returned no data.");
        }

        private JsonNode request(String method, Map<String, Object> params)
        {
            int id = ++sequence;
            send(ImmutableMap.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
            long deadline = System.nanoTime() + timeout.toNanos();
            try {
                while (System.nanoTime() < deadline) {
                    JsonNode response = responses.poll(Math.min(TimeUnit.SECONDS.toNanos(1), deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    if (response != null && response.path("id").asInt(-1) == id) {
                        checkState(!response.has("error") && response.has("result"), "Google connector request failed. Reconnect in Settings.");
                        return response.path("result");
                    }
                    checkState(process.isAlive() || !responses.isEmpty(), "The Google connector stopped. Reinstall it in Settings and try again.");
                }
                throw new IllegalStateException("Google did not respond in time. Complete browser sign-in or try again.");
            }
            catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Google request was interrupted.");
            }
        }

        private void send(Map<String, Object> message)
        {
            try {
                process.getOutputStream().write((mapper.writeValueAsString(message) + "\n").getBytes(UTF_8));
                process.getOutputStream().flush();
            }
            catch (IOException failure) {
                throw new IllegalStateException("The Google connector connection closed.");
            }
        }

        private void read()
        {
            Utf8LineBuffer lines = new Utf8LineBuffer(8 * 1024 * 1024, true, line -> {
                try {
                    JsonNode value = mapper.readTree(line);
                    if (value != null && value.has("id")) {
                        checkState(responses.offer(value), "Too many Google connector responses.");
                    }
                }
                catch (IOException failure) {
                    throw new IllegalStateException("Invalid Google connector response.");
                }
            });
            try (var input = process.getInputStream()) {
                byte[] bytes = new byte[8192];
                int length;
                while ((length = input.read(bytes)) != -1) {
                    lines.accept(ByteBuffer.wrap(bytes, 0, length));
                }
                lines.finish();
            }
            catch (IOException | RuntimeException failure) {
                stop(process);
            }
        }

        @Override
        public void close()
        {
            stop(process);
        }
    }
}
