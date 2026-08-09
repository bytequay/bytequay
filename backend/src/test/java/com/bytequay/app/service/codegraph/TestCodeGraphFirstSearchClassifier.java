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

import com.bytequay.app.service.mcp.approval.ApprovalContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestCodeGraphFirstSearchClassifier
{
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void redirectsBroadRepositoryDiscoveryCommands()
    {
        assertThat(shell("rg -n 'DailyCard|daily-card'")).isTrue();
        assertThat(shell("pwd && rg --files -g '*.java'")).isTrue();
        assertThat(shell("grep -Rin controller backend/src")).isTrue();
        assertThat(shell("git grep ThreadRegistry")).isTrue();
        assertThat(shell("find . -type f")).isTrue();
        assertThat(shell("fd Controller frontend")).isTrue();
        assertThat(shell("tree backend/src")).isTrue();
    }

    @Test
    void allowsExactChecksAndCommandsThatAreNotCodeDiscovery()
    {
        assertThat(shell("rg -F 'exact literal'")).isFalse();
        assertThat(shell("rg Thing backend/src/Thing.java")).isFalse();
        assertThat(shell("rg --files -g AGENTS.md")).isFalse();
        assertThat(shell("grep Thing backend/src/Thing.java")).isFalse();
        assertThat(shell("git status --short")).isFalse();
        assertThat(shell("mvn test")).isFalse();
    }

    @Test
    void redirectsStructuredGrepAndGlobUnlessTheyTargetAKnownFile()
            throws Exception
    {
        assertThat(CodeGraphFirstSearchClassifier.isBroadDiscovery(
                context("Grep", "{\"pattern\":\"DailyCard\",\"path\":\"frontend/src\"}")))
                .isTrue();
        assertThat(CodeGraphFirstSearchClassifier.isBroadDiscovery(
                context("Grep", "{\"pattern\":\"DailyCard\",\"path\":\"frontend/src/DailyCard.tsx\"}")))
                .isFalse();
        assertThat(CodeGraphFirstSearchClassifier.isBroadDiscovery(
                context("Glob", "{\"pattern\":\"**/*.java\"}")))
                .isTrue();
        assertThat(CodeGraphFirstSearchClassifier.isBroadDiscovery(
                context("Glob", "{\"pattern\":\"**/AGENTS.md\"}")))
                .isFalse();
    }

    @Test
    void derivesSymbolAndSemanticSuggestionsFromRejectedCalls()
            throws Exception
    {
        assertThat(CodeGraphFirstSearchClassifier.suggestion(
                context("Grep", "{\"pattern\":\"DailyCard\",\"path\":\"frontend/src\"}")))
                .isEqualTo(new CodeGraphFirstSearchClassifier.Suggestion("DailyCard", true));

        assertThat(CodeGraphFirstSearchClassifier.suggestion(
                context("Glob", "{\"pattern\":\"**/*.java\"}")))
                .satisfies(suggestion -> {
                    assertThat(suggestion.symbol()).isFalse();
                    assertThat(suggestion.query()).contains("**/*.java", "symbols", "call paths");
                });

        assertThat(CodeGraphFirstSearchClassifier.suggestion(
                context("Bash", "{\"command\":\"rg -n AuthToken backend/src\"}")))
                .isEqualTo(new CodeGraphFirstSearchClassifier.Suggestion("AuthToken", true));
    }

    private static boolean shell(String command)
    {
        return CodeGraphFirstSearchClassifier.isBroadShellDiscovery(command);
    }

    private ApprovalContext context(String toolName, String input)
            throws Exception
    {
        JsonNode parsed = mapper.readTree(input);
        return new ApprovalContext(
                "thread-1", "task-1", "task-1",
                JsonNodeFactory.instance.numberNode(1), toolName, "call-1", parsed, ImmutableSet.of());
    }
}
