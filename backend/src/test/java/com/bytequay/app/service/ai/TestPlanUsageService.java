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

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(usage.providers().get(0).label()).isEqualTo("Codex");
        assertThat(usage.providers().get(0).plan()).isEqualTo("plus");
        assertThat(usage.providers().get(0).limits())
                .extracting(PlanUsageService.LimitWindow::label)
                .containsExactly("5-hour", "Weekly");
        assertThat(usage.providers().get(1).label()).isEqualTo("Claude");
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

        assertThat(usage.providers()).singleElement().satisfies(provider -> {
            assertThat(provider.label()).isEqualTo("Claude");
            assertThat(provider.limits()).isEmpty();
            assertThat(provider.message()).contains("Enable Claude usage sync");
        });
    }
}
