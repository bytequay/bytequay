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
package com.bytequay.agent.cli.codex;

import com.bytequay.agent.cli.process.SecretRedactor;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestCodexEventMapper
{
    private final CodexProtocolCodec codec = new CodexProtocolCodec(new ObjectMapper());
    private final CodexEventMapper mapper = new CodexEventMapper();

    @TempDir
    private Path worktree;

    @Test
    void mapsRealAppServerCommandMessageUsageAndCompletionFixtures()
    {
        ProviderAgentEvent.Command started = only("""
                {"method":"item/started","params":{"item":{"type":"commandExecution","cwd":"/tmp/wt","commandActions":[],
                "id":"exec-1","command":"rg --files | wc -l","status":"inProgress",
                "aggregatedOutput":null,"exitCode":null},"threadId":"thread-1","turnId":"turn-1"},
                "emittedAtMs":1786884869335}
                """, ProviderAgentEvent.Command.class);
        assertThat(started.state()).isEqualTo(ProviderAgentEvent.ActivityState.STARTED);
        assertThat(started.argv()).containsExactly("rg --files | wc -l");
        assertThat(started.source().providerThreadId()).contains("thread-1");
        assertThat(started.source().providerTurnId()).contains("turn-1");
        assertThat(started.source().providerItemId()).contains("exec-1");

        ProviderAgentEvent.Command output = only("""
                {"method":"item/commandExecution/outputDelta","params":{"threadId":"thread-1",
                "turnId":"turn-1","itemId":"exec-1","delta":"5941 total\\n"},
                "emittedAtMs":1786884869371}
                """, ProviderAgentEvent.Command.class);
        assertThat(output.state()).isEqualTo(ProviderAgentEvent.ActivityState.PROGRESS);
        assertThat(output.output()).contains("5941 total\n");
        assertThat(output.source()).isEqualTo(started.source());

        ProviderAgentEvent.Message answer = only("""
                {"method":"item/completed","params":{"item":{"type":"agentMessage",
                "id":"msg-1","text":"5,941 lines.","phase":"final_answer"},
                "threadId":"thread-1","turnId":"turn-1"},"emittedAtMs":1786884871436}
                """, ProviderAgentEvent.Message.class);
        assertThat(answer.text()).isEqualTo("5,941 lines.");
        assertThat(answer.phase()).isEqualTo(ProviderAgentEvent.MessagePhase.FINAL);
        assertThat(answer.source().providerItemId()).contains("msg-1");

        ProviderAgentEvent.Usage usage = only("""
                {"method":"thread/tokenUsage/updated","params":{"threadId":"thread-1",
                "turnId":"turn-1","tokenUsage":{"total":{"totalTokens":65312,
                "inputTokens":64841,"cachedInputTokens":50432,"outputTokens":471,
                "reasoningOutputTokens":138},"last":{"totalTokens":1,"inputTokens":1,
                "cachedInputTokens":0,"outputTokens":0,"reasoningOutputTokens":0}}},
                "emittedAtMs":1786884871458}
                """, ProviderAgentEvent.Usage.class);
        assertThat(usage.sample().inputTokens()).isEqualTo(64841);
        assertThat(usage.sample().outputTokens()).isEqualTo(471);
        assertThat(usage.source().providerThreadId()).contains("thread-1");
        assertThat(usage.source().providerTurnId()).contains("turn-1");
        assertThat(usage.source().providerItemId()).isEmpty();

        ProviderAgentEvent.Lifecycle completed = only("""
                {"method":"turn/completed","params":{"threadId":"thread-1","turn":{
                "id":"turn-1","status":"completed","error":null,"durationMs":14575,
                "items":[]}},
                "emittedAtMs":1786884871461}
                """, ProviderAgentEvent.Lifecycle.class);
        assertThat(completed.phase()).isEqualTo(ProviderAgentEvent.LifecyclePhase.TURN_COMPLETED);
        assertThat(completed.source().providerThreadId()).contains("thread-1");
        assertThat(completed.source().providerTurnId()).contains("turn-1");
    }

    @Test
    void rejectsMissingRequiredFieldsDuringDeserialization()
    {
        assertThatThrownBy(() -> codec.decodeNotification("""
                {"method":"item/commandExecution/outputDelta","params":{
                "turnId":"turn-1","itemId":"exec-1","delta":"output"}}
                """))
                .hasRootCauseMessage("threadId is null");

        assertThatThrownBy(() -> codec.decodeNotification("""
                {"method":"thread/tokenUsage/updated","params":{"threadId":"thread-1",
                "turnId":"turn-1","tokenUsage":{"total":{"totalTokens":10,
                "inputTokens":8,"cachedInputTokens":2}}}}
                """))
                .hasRootCauseMessage("outputTokens is null");
    }

    @Test
    void ignoresUnknownNotificationsItemsAndFields()
    {
        CodexProtocol.Notification notification = codec.decodeNotification("""
                {"method":"future/event","params":{"threadId":"thread-1","future":true},
                "emittedAtMs":1786884871461,"extra":"ignored"}
                """);
        assertThat(notification).isInstanceOf(CodexProtocol.UnknownNotification.class);
        assertThat(mapper.map(notification, true)).isEmpty();

        CodexProtocol.Notification item = codec.decodeNotification("""
                {"method":"item/completed","params":{"item":{"type":"futureItem",
                "id":"item-1","future":true},"threadId":"thread-1","turnId":"turn-1"}}
                """);
        assertThat(mapper.map(item, true)).isEmpty();
    }

    @Test
    void deserializesFullCommandApprovalRequestAndBothKinds()
    {
        CodexProtocol.ServerRequest request = codec.decodeServerRequest("""
                {"id":7,"method":"item/commandExecution/requestApproval","params":{
                "additionalPermissions":{"network":{"enabled":true}},
                "approvalId":"approval-1","availableDecisions":["accept","decline"],
                "command":"mvn verify","commandActions":[{"type":"unknown","command":"mvn verify"}],
                "cwd":"/worktree","environmentId":"environment-1","itemId":"exec-1",
                "kind":"writeStdin","networkApprovalContext":{"host":"example.com","protocol":"https"},
                "proposedExecpolicyAmendment":["mvn verify"],
                "proposedNetworkPolicyAmendments":[{"host":"example.com","action":"allow"}],
                "reason":"needs network","startedAtMs":1786884871461,
                "threadId":"thread-1","turnId":"turn-1"}}
                """);

        assertThat(request).isInstanceOf(CodexProtocol.CommandApprovalRequest.class);
        CodexProtocol.CommandApprovalRequest approval = (CodexProtocol.CommandApprovalRequest) request;
        assertThat(approval.params().additionalPermissions()).hasValueSatisfying(value ->
                assertThat(value.path("network").path("enabled").asBoolean()).isTrue());
        assertThat(approval.params().approvalId()).contains("approval-1");
        assertThat(approval.params().availableDecisions().orElseThrow())
                .extracting(JsonNode::asText)
                .containsExactly("accept", "decline");
        assertThat(approval.params().command()).contains("mvn verify");
        assertThat(approval.params().commandActions().orElseThrow()).singleElement().satisfies(action ->
                assertThat(action.path("type").asText()).isEqualTo("unknown"));
        assertThat(approval.params().cwd()).contains("/worktree");
        assertThat(approval.params().environmentId()).contains("environment-1");
        assertThat(approval.params().kind()).contains("writeStdin");
        assertThat(approval.params().networkApprovalContext()).hasValueSatisfying(value ->
                assertThat(value.path("host").asText()).isEqualTo("example.com"));
        assertThat(approval.params().proposedExecpolicyAmendment().orElseThrow()).containsExactly("mvn verify");
        assertThat(approval.params().proposedNetworkPolicyAmendments().orElseThrow()).singleElement().satisfies(amendment ->
                assertThat(amendment.path("action").asText()).isEqualTo("allow"));
        assertThat(approval.params().reason()).contains("needs network");
        assertThat(approval.turnId()).contains("turn-1");

        CodexProtocol.CommandApprovalRequest command = (CodexProtocol.CommandApprovalRequest) codec.decodeServerRequest("""
                {"id":8,"method":"item/commandExecution/requestApproval","params":{
                "threadId":"thread-1","turnId":"turn-1","itemId":"exec-2",
                "kind":"command","startedAtMs":1786884871462}}
                """);
        assertThat(command.params().kind()).contains("command");
    }

    @Test
    void deserializesFileAndPermissionsApprovalRequests()
    {
        CodexProtocol.FileChangeApprovalRequest file = (CodexProtocol.FileChangeApprovalRequest) codec.decodeServerRequest("""
                {"id":9,"method":"item/fileChange/requestApproval","params":{
                "grantRoot":"/worktree/src","itemId":"patch-1","reason":"write files",
                "startedAtMs":1786884871463,"threadId":"thread-1","turnId":"turn-1"}}
                """);
        assertThat(file.params().grantRoot()).contains("/worktree/src");
        assertThat(file.params().reason()).contains("write files");
        assertThat(file.turnId()).contains("turn-1");

        CodexProtocol.ServerRequest request = codec.decodeServerRequest("""
                {"id":10,"method":"item/permissions/requestApproval","params":{
                "cwd":"/worktree","environmentId":"environment-1","itemId":"permission-1",
                "permissions":{"fileSystem":{"write":["/worktree/generated"]},"network":{"enabled":true}},
                "reason":"generate files","startedAtMs":1786884871464,
                "threadId":"thread-1","turnId":"turn-1"}}
                """);
        assertThat(request).isInstanceOf(CodexProtocol.PermissionsApprovalRequest.class);
        CodexProtocol.PermissionsApprovalRequest permissions = (CodexProtocol.PermissionsApprovalRequest) request;
        assertThat(permissions.params().cwd()).isEqualTo("/worktree");
        assertThat(permissions.params().environmentId()).contains("environment-1");
        assertThat(permissions.params().permissions().path("network").path("enabled").asBoolean()).isTrue();
        assertThat(permissions.params().reason()).contains("generate files");
        assertThat(permissions.turnId()).contains("turn-1");
    }

    @Test
    void mapsPartialMessagesAndUsesCompletedItemsAsAuthoritativeEvents()
    {
        ProviderAgentEvent.Message partial = only("""
                {"method":"item/agentMessage/delta","params":{"threadId":"thread-1",
                "turnId":"turn-1","itemId":"message-1","delta":"draft answer"}}
                """, ProviderAgentEvent.Message.class);
        assertThat(partial.phase()).isEqualTo(ProviderAgentEvent.MessagePhase.PARTIAL);
        assertThat(partial.text()).isEqualTo("draft answer");
        assertThat(partial.providerEventId()).isEmpty();
        assertThat(partial.source().providerThreadId()).contains("thread-1");
        assertThat(partial.source().providerTurnId()).contains("turn-1");
        assertThat(partial.source().providerItemId()).contains("message-1");

        ProviderAgentEvent.Message completed = only("""
                {"method":"item/completed","params":{"item":{"type":"agentMessage",
                "id":"message-1","text":"authoritative answer","phase":"final_answer"},
                "threadId":"thread-1","turnId":"turn-1"}}
                """, ProviderAgentEvent.Message.class);
        assertThat(completed.phase()).isEqualTo(ProviderAgentEvent.MessagePhase.FINAL);
        assertThat(completed.text()).isEqualTo("authoritative answer");
        assertThat(completed.providerEventId()).contains("message-1:completed");
        assertThat(completed.source()).isEqualTo(partial.source());
    }

    @Test
    void mapsReadableReasoningSummariesAndPlanTextButNeverHiddenReasoning()
    {
        ProviderAgentEvent.Message summary = only("""
                {"method":"item/completed","params":{"item":{"type":"reasoning","id":"reasoning-1",
                "summary":["Looked at the failing test","Chose the smaller fix"],
                "content":["hidden chain of thought"]},"threadId":"thread-1","turnId":"turn-1"}}
                """, ProviderAgentEvent.Message.class);
        assertThat(summary.messageKind()).isEqualTo(ProviderAgentEvent.MessageKind.REASONING_SUMMARY);
        assertThat(summary.phase()).isEqualTo(ProviderAgentEvent.MessagePhase.FINAL);
        assertThat(summary.text()).isEqualTo("Looked at the failing test\n\nChose the smaller fix");
        assertThat(summary.text()).doesNotContain("hidden");

        // Reasoning that starts, or completes with nothing readable, is not a transcript row.
        assertThat(mapper.map(codec.decodeNotification("""
                {"method":"item/started","params":{"item":{"type":"reasoning","id":"reasoning-2",
                "summary":[],"content":["hidden"]},"threadId":"thread-1","turnId":"turn-1"}}
                """), true)).isEmpty();
        assertThat(mapper.map(codec.decodeNotification("""
                {"method":"item/completed","params":{"item":{"type":"reasoning","id":"reasoning-2",
                "content":["hidden"]},"threadId":"thread-1","turnId":"turn-1"}}
                """), true)).isEmpty();

        ProviderAgentEvent.Message planDelta = only("""
                {"method":"item/plan/delta","params":{"threadId":"thread-1","turnId":"turn-1",
                "itemId":"plan-1","delta":"1. Reproduce"}}
                """, ProviderAgentEvent.Message.class);
        assertThat(planDelta.messageKind()).isEqualTo(ProviderAgentEvent.MessageKind.PLAN_TEXT);
        assertThat(planDelta.phase()).isEqualTo(ProviderAgentEvent.MessagePhase.PARTIAL);
        assertThat(planDelta.source().providerItemId()).contains("plan-1");

        ProviderAgentEvent.Message planText = only("""
                {"method":"item/completed","params":{"item":{"type":"plan","id":"plan-1",
                "text":"1. Reproduce\\n2. Fix"},"threadId":"thread-1","turnId":"turn-1"}}
                """, ProviderAgentEvent.Message.class);
        assertThat(planText.messageKind()).isEqualTo(ProviderAgentEvent.MessageKind.PLAN_TEXT);
        assertThat(planText.phase()).isEqualTo(ProviderAgentEvent.MessagePhase.FINAL);
        assertThat(planText.text()).isEqualTo("1. Reproduce\n2. Fix");
    }

    @Test
    void mapsCommandFactsAndBuiltInToolsIntoActivity()
    {
        CodexEventMapper worktreeMapper = new CodexEventMapper(
                worktree,
                SecretRedactor.fromEnvironment(ImmutableMap.of()));
        String cwd = worktree.resolve("backend").toString();
        String file = worktree.resolve("backend/App.java").toString();
        List<ProviderAgentEvent> events = worktreeMapper.map(codec.decodeNotification("""
                {"method":"item/completed","params":{"item":{"type":"commandExecution","id":"exec-2",
                "command":"sed -n 1,40p App.java && rg TODO","status":"completed",
                "aggregatedOutput":"ok","exitCode":0,"cwd":"%s","durationMs":1450,
                "commandActions":[
                  {"type":"read","command":"sed -n 1,40p App.java","name":"App.java","path":"%s"},
                  {"type":"search","command":"rg TODO","query":"TODO","path":null},
                  {"type":"listFiles","command":"ls","path":"/elsewhere"},
                  {"type":"somethingNew","command":"x"}]},
                "threadId":"thread-1","turnId":"turn-1"}}
                """.formatted(cwd, file)), true);
        assertThat(events).hasSize(1);
        ProviderAgentEvent.Command command = (ProviderAgentEvent.Command) events.getFirst();
        assertThat(command.cwd()).contains("backend");
        assertThat(command.duration()).contains(Duration.ofMillis(1450));
        assertThat(command.actions()).extracting(ProviderAgentEvent.CommandAction::kind).containsExactly(
                ProviderAgentEvent.CommandActionKind.READ,
                ProviderAgentEvent.CommandActionKind.SEARCH,
                ProviderAgentEvent.CommandActionKind.LIST_FILES,
                ProviderAgentEvent.CommandActionKind.OTHER);
        assertThat(command.actions().get(0).path()).contains("backend/App.java");
        assertThat(command.actions().get(1).query()).contains("TODO");
        // A path outside the worktree is dropped rather than shown.
        assertThat(command.actions().get(2).path()).isEmpty();

        ProviderAgentEvent.Tool search = only("""
                {"method":"item/completed","params":{"item":{"type":"webSearch","id":"search-1",
                "query":"trino release notes","action":null},"threadId":"thread-1","turnId":"turn-1"}}
                """, ProviderAgentEvent.Tool.class);
        assertThat(search.name()).isEqualTo("Web search");
        assertThat(search.summary()).isEqualTo("trino release notes");
        assertThat(search.state()).isEqualTo(ProviderAgentEvent.ActivityState.COMPLETED);

        ProviderAgentEvent.Tool view = (ProviderAgentEvent.Tool) worktreeMapper.map(codec.decodeNotification("""
                {"method":"item/started","params":{"item":{"type":"imageView","id":"view-1",
                "path":"%s"},"threadId":"thread-1","turnId":"turn-1"}}
                """.formatted(worktree.resolve("docs/shot.png"))), true).getFirst();
        assertThat(view.name()).isEqualTo("Image view");
        assertThat(view.summary()).isEqualTo("docs/shot.png");
        assertThat(view.state()).isEqualTo(ProviderAgentEvent.ActivityState.STARTED);

        ProviderAgentEvent.Tool compaction = only("""
                {"method":"item/completed","params":{"item":{"type":"contextCompaction","id":"compact-1"},
                "threadId":"thread-1","turnId":"turn-1"}}
                """, ProviderAgentEvent.Tool.class);
        assertThat(compaction.name()).isEqualTo("Context compaction");

        ProviderAgentEvent.Tool mcp = only("""
                {"method":"item/completed","params":{"item":{"type":"mcpToolCall","id":"mcp-1",
                "server":"docs","tool":"lookup","status":"completed","durationMs":20},
                "threadId":"thread-1","turnId":"turn-1"}}
                """, ProviderAgentEvent.Tool.class);
        assertThat(mcp.duration()).contains(Duration.ofMillis(20));
    }

    @Test
    void mapsCompletedFileChangesInsideTheWorktree()
    {
        CodexEventMapper worktreeMapper = new CodexEventMapper(
                worktree,
                SecretRedactor.fromEnvironment(ImmutableMap.of()));
        String file = worktree.resolve("src/App.java").toString();
        List<ProviderAgentEvent> events = worktreeMapper.map(codec.decodeNotification("""
                {"method":"item/completed","params":{"item":{"type":"fileChange",
                "id":"patch-1","status":"completed","changes":[
                {"path":"%s","kind":{"type":"add"},"diff":"+class App {}"},
                {"path":"%s","kind":{"type":"add"},"diff":"+class Other {}"},
                {"path":"/outside/secret","kind":{"type":"delete"},"diff":"-secret"}]},
                "threadId":"thread-1","turnId":"turn-1"}}
                """.formatted(file, worktree.resolve("src/Other.java"))), true);

        assertThat(events).hasSize(2);
        assertThat(events)
                .extracting(event -> event.source().providerItemId().orElseThrow())
                .containsExactly("patch-1:0", "patch-1:1");
        assertThat(events.getFirst()).isInstanceOfSatisfying(
                ProviderAgentEvent.FileChange.class,
                change -> {
                    assertThat(change.relativePath()).isEqualTo("src/App.java");
                    assertThat(change.change()).isEqualTo(ProviderAgentEvent.ChangeKind.ADDED);
                    assertThat(change.diff()).contains("+class App {}");
                });
    }

    @Test
    void redactsKnownEnvironmentCredentialsFromMappedEvents()
    {
        CodexEventMapper redactingMapper = new CodexEventMapper(
                worktree,
                SecretRedactor.fromEnvironment(ImmutableMap.of("OPENAI_API_KEY", "known-secret")));
        List<ProviderAgentEvent> events = redactingMapper.map(codec.decodeNotification("""
                {"method":"item/completed","params":{"item":{"type":"commandExecution","cwd":"/tmp/wt","commandActions":[],
                "id":"exec-1","command":"echo known-secret","status":"completed",
                "aggregatedOutput":"known-secret","exitCode":0},
                "threadId":"thread-1","turnId":"turn-1"}}
                """), true);

        ProviderAgentEvent.Command command = (ProviderAgentEvent.Command) events.getFirst();
        assertThat(command.argv()).containsExactly("echo [REDACTED]");
        assertThat(command.output()).contains("[REDACTED]");
        assertThat(command.toString()).doesNotContain("known-secret");
    }

    private <T extends ProviderAgentEvent> T only(String json, Class<T> type)
    {
        List<ProviderAgentEvent> events = mapper.map(codec.decodeNotification(json), true);
        assertThat(events).hasSize(1);
        return type.cast(events.getFirst());
    }
}
