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
package com.bytequay.app.scheduler;

import com.bytequay.agent.cli.claude.ClaudeHome;
import com.bytequay.agent.cli.codex.CodexHome;
import com.bytequay.app.agent.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;

import static com.bytequay.app.config.AsyncConfig.IO_EXECUTOR;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * Bounds the provider homes ByteQuay owns. Both CLIs keep every thread's session transcript
 * forever, and nothing else deletes them: the task retention job covers ByteQuay's own runtime
 * directories and task logs, which is a different tree. These ByteQuay-owned homes are swept;
 * the user's {@code ~/.codex} and {@code ~/.claude} are never ByteQuay's to touch.
 */
@Component
public class ProviderSessionRetentionJob
{
    private static final Logger log = LoggerFactory.getLogger(ProviderSessionRetentionJob.class);

    private final AgentProperties properties;
    private final Duration retention;
    private final ExecutorService executor;

    public ProviderSessionRetentionJob(AgentProperties properties, @Value("${bytequay.agent.session-retention-days:30}") int retentionDays,
            @Qualifier(IO_EXECUTOR) ExecutorService executor)
    {
        this.properties = requireNonNull(properties, "properties is null");
        checkArgument(retentionDays >= 1, "session-retention-days must be at least one day");
        this.retention = Duration.ofDays(retentionDays);
        this.executor = requireNonNull(executor, "executor is null");
    }

    @Scheduled(
            initialDelayString = "PT2M",
            fixedDelayString = "PT6H")
    public void tick()
    {
        // Every @Scheduled job here shares the single agent approval thread, and this one walks and
        // deletes files. Off that thread, so a pending approval never waits on the disk.
        executor.execute(this::sweep);
    }

    private void sweep()
    {
        Instant deleteBefore = Instant.now().minus(retention);
        sweep("Codex", () -> CodexHome.pruneSessions(properties.codexHome(), deleteBefore));
        sweepHomes(properties.codexHome().resolve("deepseek"), "DeepSeek", home -> CodexHome.pruneSessions(home, deleteBefore));
        sweep("Claude", () -> ClaudeHome.pruneSessions(properties.claudeHome(), deleteBefore));
        sweepHomes(properties.claudeHome().resolve("accounts"), "Claude", home -> ClaudeHome.pruneSessions(home, deleteBefore));
    }

    private void sweepHomes(Path root, String lane, HomeSweep sweep)
    {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (DirectoryStream<Path> homes = Files.newDirectoryStream(root)) {
            for (Path home : homes) {
                if (Files.isDirectory(home, LinkOption.NOFOLLOW_LINKS)) {
                    sweep(lane, () -> sweep.run(home));
                }
            }
        }
        catch (IOException failure) {
            log.warn("{} session retention sweep failed: {}", lane, failure.getMessage());
        }
    }

    private void sweep(String lane, Sweep sweep)
    {
        try {
            int deleted = sweep.run();
            if (deleted > 0) {
                log.info("{} session retention deleted {} transcript directories older than {}", lane, deleted, retention);
            }
        }
        catch (IOException | RuntimeException failure) {
            // One unreadable home must not stop the other from being swept.
            log.warn("{} session retention sweep failed: {}", lane, failure.getMessage());
        }
    }

    @FunctionalInterface
    private interface HomeSweep
    {
        int run(Path home)
                throws IOException;
    }

    @FunctionalInterface
    private interface Sweep
    {
        int run()
                throws IOException;
    }
}
