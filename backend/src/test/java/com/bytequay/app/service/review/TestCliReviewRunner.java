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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.StreamEvent;
import com.bytequay.app.service.threads.CliStreamParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestCliReviewRunner
{
    @Test
    void buildsClaudeArgvWithStdinPromptAndOptionalResume()
    {
        assertThat(CliReviewRunner.buildArgv(
                CliReviewRunner.Provider.CLAUDE, "claude", null, "/work", null, null))
                .containsExactly("claude", "-p", "--output-format", "stream-json", "--verbose");

        assertThat(CliReviewRunner.buildArgv(
                CliReviewRunner.Provider.CLAUDE, "claude", "sess-1", "/work", null, null))
                .containsExactly("claude", "-p", "--output-format", "stream-json", "--verbose",
                        "--resume", "sess-1");
    }

    @Test
    void claudeArgvWiresTheMcpConfigAndPreAllowsTheReviewTools()
    {
        assertThat(CliReviewRunner.buildArgv(
                CliReviewRunner.Provider.CLAUDE, "claude", null, "/work", null, Path.of("/tmp/mcp.json")))
                .containsExactly("claude", "-p", "--output-format", "stream-json", "--verbose",
                        "--mcp-config", "/tmp/mcp.json",
                        "--allowedTools", CliReviewRunner.ALLOWED_REVIEW_TOOLS);
    }

    @Test
    void buildsTheReviewMcpUrlAndConfigForASeat()
    {
        CliReviewRunner.McpEndpoint mcp = new CliReviewRunner.McpEndpoint("pass-1", "seat-9");
        assertThat(CliReviewRunner.mcpServerUrl(mcp))
                .isEqualTo("http://127.0.0.1:53123/api/reviews/pass-1/seats/seat-9/mcp");
        assertThat(CliReviewRunner.mcpConfigJson(mcp))
                .isEqualTo("{\"mcpServers\":{\"bytequay\":{\"type\":\"http\","
                        + "\"url\":\"http://127.0.0.1:53123/api/reviews/pass-1/seats/seat-9/mcp\"}}}");
    }

    @Test
    void buildsCodexArgvReadOnlyWithTrailingPromptAndOptionalResume()
    {
        assertThat(CliReviewRunner.buildArgv(
                CliReviewRunner.Provider.CODEX, "codex", null, "/work", "review this", null))
                .containsExactly("codex", "exec", "--json", "--skip-git-repo-check",
                        "--sandbox", "read-only", "-C", "/work", "review this");

        assertThat(CliReviewRunner.buildArgv(
                CliReviewRunner.Provider.CODEX, "codex", "sess-2", "/work", "more", null))
                .containsExactly("codex", "exec", "resume", "sess-2", "--json", "--skip-git-repo-check",
                        "--sandbox", "read-only", "-C", "/work", "more");
    }

    @Test
    void assembleJoinsAssistantTextAndCapturesSessionAndCost()
    {
        // A stub parser maps canned stdout lines to events, so this tests the
        // assembly logic without depending on a real CLI's wire format.
        CliStreamParser stub = (line, now) -> switch (line) {
            case "session" -> List.of(new StreamEvent.SessionStarted(now, "sess-9", "/cwd", "model"));
            case "text1" -> List.of(new StreamEvent.AssistantText(now, "First part."));
            case "text2" -> List.of(new StreamEvent.AssistantText(now, "Second part."));
            case "done" -> List.of(new StreamEvent.TurnDone(now, 10, 1234, 5, 6));
            default -> List.of();
        };

        CliReviewRunner.Result result = CliReviewRunner.assemble(
                stub, List.of("session", "text1", "text2", "done", ""));

        assertThat(result.sessionId()).isEqualTo("sess-9");
        assertThat(result.text()).isEqualTo("First part.\n\nSecond part.");
        assertThat(result.costUsdMilli()).isEqualTo(1234);
    }

    @Test
    void providerIdentityMapsCliIds()
    {
        assertThat(CliReviewRunner.Provider.isCliProvider("claude-cli")).isTrue();
        assertThat(CliReviewRunner.Provider.isCliProvider("codex-cli")).isTrue();
        assertThat(CliReviewRunner.Provider.isCliProvider("claude")).isFalse();
        assertThat(CliReviewRunner.Provider.of("codex-cli")).isEqualTo(CliReviewRunner.Provider.CODEX);
    }
}
