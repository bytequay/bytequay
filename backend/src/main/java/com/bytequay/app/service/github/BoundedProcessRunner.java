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
package com.bytequay.app.service.github;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs one argv-based subprocess with a timeout and bounded combined output. */
@Component
public class BoundedProcessRunner
{
    private static final Logger log = LoggerFactory.getLogger(BoundedProcessRunner.class);

    public Result run(Path workingDirectory, List<String> arguments, long timeoutSeconds, int maxOutputBytes)
            throws InterruptedException
    {
        if (arguments == null || arguments.isEmpty()) {
            return Result.failed("arguments are empty");
        }
        Process process;
        try {
            process = new ProcessBuilder(arguments)
                    .directory(workingDirectory.toFile())
                    .redirectErrorStream(true)
                    .start();
        }
        catch (IOException e) {
            return Result.failed("spawn failed: " + e.getMessage());
        }

        StringBuffer output = new StringBuffer();
        AtomicBoolean truncated = new AtomicBoolean(false);
        Thread drain = new Thread(
                () -> drain(process, arguments, output, truncated, maxOutputBytes),
                "github-cli-output");
        drain.setDaemon(true);
        drain.start();

        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
                drain.join(1_000L);
                return new Result(false, -1, output.toString(), true,
                        "timed out after " + timeoutSeconds + "s");
            }
        }
        catch (InterruptedException e) {
            process.destroyForcibly();
            throw e;
        }

        drain.join(2_000L);
        return new Result(true, process.exitValue(), output.toString(), truncated.get(), null);
    }

    private static void drain(
            Process process,
            List<String> arguments,
            StringBuffer output,
            AtomicBoolean truncated,
            int maxOutputBytes)
    {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                int room = maxOutputBytes - output.length();
                if (read >= room) {
                    output.append(buffer, 0, Math.max(0, room));
                    output.append("\n…[truncated]\n");
                    truncated.set(true);
                    return;
                }
                output.append(buffer, 0, read);
            }
        }
        catch (IOException e) {
            log.debug("Output drain ended for {}: {}", arguments, e.getMessage());
        }
    }

    public record Result(boolean ran, int exitCode, String output, boolean truncated, String error)
    {
        private static Result failed(String reason)
        {
            return new Result(false, -1, "", false, reason);
        }
    }
}
