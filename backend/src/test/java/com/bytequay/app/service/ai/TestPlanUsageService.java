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
package com.bytequay.app.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TestPlanUsageService
{
    @TempDir
    Path tempDir;

    @Test
    void normalizesCodexAndClaudeWindows()
            throws IOException
    {
        Path codex = tempDir.resolve("codex");
        Path claude = tempDir.resolve("claude");
        Path cache = tempDir.resolve("app/claude-statusline.json");
        Files.createDirectories(codex.resolve("2026/07/20"));
        Files.createDirectories(claude);
        Files.createDirectories(cache.getParent());
        Files.writeString(codex.resolve("2026/07/20/rollout-test.jsonl"), """
                {"type":"event_msg","timestamp":"2026-07-20T05:19:54.710Z","payload":{"type":"token_count","rate_limits":{"plan_type":"plus","primary":{"used_percent":54.0,"window_minutes":300,"resets_at":1784526000},"secondary":{"used_percent":55.0,"window_minutes":10080,"resets_at":1785127148}}}}
                """);
        Files.writeString(cache, """
                {"rate_limits":{"five_hour":{"used_percentage":12.5,"resets_at":1784526000},"seven_day":{"used_percentage":98,"resets_at":1785127148}}}
                """);

        PlanUsageService.PlanUsage usage = new PlanUsageService(
                new ObjectMapper(), codex, claude, cache).current();

        assertThat(usage.providers()).hasSize(2);
        assertThat(usage.providers().get(0).label()).isEqualTo("Codex CLI");
        assertThat(usage.providers().get(0).plan()).isEqualTo("plus");
        assertThat(usage.providers().get(0).limits())
                .extracting(PlanUsageService.LimitWindow::label)
                .containsExactly("5-hour", "Weekly");
        assertThat(usage.providers().get(1).label()).isEqualTo("Claude CLI");
        assertThat(usage.providers().get(1).limits())
                .extracting(PlanUsageService.LimitWindow::usedPercent)
                .containsExactly(12.5, 98.0);
    }

    @Test
    void reportsClaudeSyncStateWithoutInventingUsage()
            throws IOException
    {
        Path codex = tempDir.resolve("missing-codex");
        Path claude = tempDir.resolve("claude");
        Files.createDirectories(claude);

        PlanUsageService.PlanUsage usage = new PlanUsageService(
                new ObjectMapper(), codex, claude, tempDir.resolve("missing-cache.json")).current();

        assertThat(usage.providers()).filteredOn(provider -> provider.provider().equals("anthropic"))
                .singleElement().satisfies(provider -> {
            assertThat(provider.label()).isEqualTo("Claude CLI");
            assertThat(provider.limits()).isEmpty();
            assertThat(provider.message()).contains("Refresh Claude CLI usage");
        });
    }

    @Test
    void reportsMissingCliExecutables()
    {
        PlanUsageService.PlanUsage usage = new PlanUsageService(
                new ObjectMapper(),
                tempDir.resolve("codex"),
                tempDir.resolve("claude"),
                tempDir.resolve("claude-statusline.json"),
                tempDir.resolve("claude-plan-usage.json"),
                null,
                null).current();

        assertThat(usage.providers())
                .extracting(PlanUsageService.ProviderUsage::message)
                .containsExactly("Codex CLI is not available.", "Claude CLI is not available.");
    }

    @Test
    void parsesLiveCodexCliBuckets()
            throws IOException
    {
        String response = """
                {"id":1,"result":{"rateLimits":{"limitId":"codex","limitName":null,"primary":{"usedPercent":27,"windowDurationMins":10080,"resetsAt":1785127148},"secondary":null,"planType":"prolite"},"rateLimitsByLimitId":{"codex_bengalfox":{"limitId":"codex_bengalfox","limitName":"GPT-5.3-Codex-Spark","primary":{"usedPercent":0,"windowDurationMins":10080,"resetsAt":1785138246},"secondary":null,"planType":"prolite"},"codex":{"limitId":"codex","limitName":null,"primary":{"usedPercent":27,"windowDurationMins":10080,"resetsAt":1785127148},"secondary":null,"planType":"prolite"}}}}
                """;

        PlanUsageService.ProviderUsage usage = PlanUsageService.parseCodexUsage(
                new ObjectMapper().readTree(response), Instant.ofEpochMilli(1234));

        assertThat(usage.label()).isEqualTo("Codex CLI");
        assertThat(usage.plan()).isEqualTo("prolite");
        assertThat(usage.source()).isEqualTo("Codex CLI app-server");
        assertThat(usage.limits())
                .extracting(PlanUsageService.LimitWindow::usedPercent)
                .containsExactly(27.0, 0.0);
        assertThat(usage.limits().get(1).model()).isEqualTo("GPT-5.3-Codex-Spark");
    }

    @Test
    void parsesInteractiveClaudeUsageIncludingModelLimit()
    {
        Instant capturedAt = ZonedDateTime.of(
                2026, 7, 20, 13, 30, 0, 0, ZoneId.of("Asia/Singapore")).toInstant();
        String output = """
                Opus 4.8 (1M context) with xhigh effort · Claude Max · account
                Current session
                2% 2% used
                Resets 7:59pm (Asia/Singapore)
                Current week (all models)
                55% 55% used
                Resets Jul 24 at 7:59am (Asia/Singapore)
                +50% weekly limits promo through Aug 19
                Current week (Fable)
                98% 98% used
                Resets Jul 24 at 7:59am (Asia/Singapore)
                Usage credits
                """;

        PlanUsageService.ProviderUsage usage = PlanUsageService.parseClaudeUsage(output, capturedAt);

        assertThat(usage.plan()).isEqualTo("Max");
        assertThat(usage.label()).isEqualTo("Claude CLI");
        assertThat(usage.source()).isEqualTo("Claude CLI /usage");
        assertThat(usage.limits())
                .extracting(PlanUsageService.LimitWindow::id)
                .containsExactly("current_session", "all_models", "model:fable");
        assertThat(usage.limits())
                .extracting(PlanUsageService.LimitWindow::usedPercent)
                .containsExactly(2.0, 55.0, 98.0);
        assertThat(usage.limits().get(2).model()).isEqualTo("Fable");
        assertThat(Instant.ofEpochMilli(usage.limits().get(0).resetsAt()))
                .isEqualTo(ZonedDateTime.of(
                        2026, 7, 20, 19, 59, 0, 0, ZoneId.of("Asia/Singapore")).toInstant());
        assertThat(Instant.ofEpochMilli(usage.limits().get(1).resetsAt()))
                .isEqualTo(ZonedDateTime.of(
                        2026, 7, 24, 7, 59, 0, 0, ZoneId.of("Asia/Singapore")).toInstant());
    }

    @Test
    void parsesClaudeUsageWhenRedrawGluesTheSessionHeading()
    {
        Instant capturedAt = ZonedDateTime.of(
                2026, 7, 20, 13, 30, 0, 0, ZoneId.of("Asia/Singapore")).toInstant();
        // The screen-reader TUI's cursor-move/erase codes strip to nothing, gluing
        // the first heading onto preceding chrome ("Esc to cancelCurrent session")
        // so it is no longer at a line start — the real capture that returned 0
        // limits and 500'd the refresh endpoint.
        String output = """
                Esc to cancelCurrent session
                63% 63% used
                Resets 2:20pm (Asia/Singapore)
                Current week (all models)
                64% 64% used
                Resets Jul 24 at 8am (Asia/Singapore)
                +50% weekly limits promo through Aug 19
                Current week (Fable)
                98% 98% used
                Resets Jul 24 at 8am (Asia/Singapore)
                """;

        PlanUsageService.ProviderUsage usage = PlanUsageService.parseClaudeUsage(output, capturedAt);

        assertThat(usage.limits())
                .extracting(PlanUsageService.LimitWindow::id)
                .containsExactly("current_session", "all_models", "model:fable");
    }

    @Test
    void refreshesClaudeUsageThroughExpect()
            throws IOException
    {
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/expect")),
                "interactive Claude probe requires expect");
        Path claude = tempDir.resolve("claude");
        Files.writeString(claude, """
                #!/bin/sh
                printf '$\\033[4G'
                IFS= read -r command
                [ "$command" = "/usage" ] || exit 1
                printf 'Claude Max\\nCurrent session\\n2%% used\\nResets 7:59pm (Asia/Singapore)\\nUsage credits\\n'
                IFS= read -r command
                """);
        Files.setPosixFilePermissions(claude, PosixFilePermissions.fromString("rwx------"));
        Path cache = tempDir.resolve("app/claude-plan-usage.json");
        PlanUsageService service = new PlanUsageService(
                new ObjectMapper(),
                tempDir.resolve("codex"),
                tempDir.resolve("claude-config"),
                tempDir.resolve("app/claude-statusline.json"),
                cache,
                null,
                claude.toString());

        PlanUsageService.PlanUsage result = service.refreshClaude();

        assertThat(result.providers()).filteredOn(provider -> provider.provider().equals("anthropic"))
                .singleElement().satisfies(provider -> {
            assertThat(provider.plan()).isEqualTo("Max");
            assertThat(provider.limits()).extracting(PlanUsageService.LimitWindow::usedPercent)
                    .containsExactly(2.0);
        });
        assertThat(cache).isRegularFile();
    }
}
