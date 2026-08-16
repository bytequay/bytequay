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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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
                {"method":"item/started","params":{"item":{"type":"commandExecution",
                "id":"exec-1","command":"rg --files | wc -l","status":"inProgress",
                "aggregatedOutput":null,"exitCode":null},"threadId":"thread-1","turnId":"turn-1"},
                "emittedAtMs":1786884869335}
                """, ProviderAgentEvent.Command.class);
        assertThat(started.state()).isEqualTo(ProviderAgentEvent.ActivityState.STARTED);
        assertThat(started.argv()).containsExactly("rg --files | wc -l");

        ProviderAgentEvent.Command output = only("""
                {"method":"item/commandExecution/outputDelta","params":{"threadId":"thread-1",
                "turnId":"turn-1","itemId":"exec-1","delta":"5941 total\\n"},
                "emittedAtMs":1786884869371}
                """, ProviderAgentEvent.Command.class);
        assertThat(output.state()).isEqualTo(ProviderAgentEvent.ActivityState.PROGRESS);
        assertThat(output.output()).contains("5941 total\n");

        ProviderAgentEvent.Message answer = only("""
                {"method":"item/completed","params":{"item":{"type":"agentMessage",
                "id":"msg-1","text":"5,941 lines.","phase":"final_answer"},
                "threadId":"thread-1","turnId":"turn-1"},"emittedAtMs":1786884871436}
                """, ProviderAgentEvent.Message.class);
        assertThat(answer.text()).isEqualTo("5,941 lines.");
        assertThat(answer.phase()).isEqualTo(ProviderAgentEvent.MessagePhase.FINAL);

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

        ProviderAgentEvent.Lifecycle completed = only("""
                {"method":"turn/completed","params":{"threadId":"thread-1","turn":{
                "id":"turn-1","status":"completed","error":null,"durationMs":14575}},
                "emittedAtMs":1786884871461}
                """, ProviderAgentEvent.Lifecycle.class);
        assertThat(completed.phase()).isEqualTo(ProviderAgentEvent.LifecyclePhase.TURN_COMPLETED);
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
        assertThat(mapper.map(notification)).isEmpty();

        CodexProtocol.Notification item = codec.decodeNotification("""
                {"method":"item/completed","params":{"item":{"type":"futureItem",
                "id":"item-1","future":true},"threadId":"thread-1","turnId":"turn-1"}}
                """);
        assertThat(mapper.map(item)).isEmpty();
    }

    @Test
    void deserializesTypedApprovalRequest()
    {
        CodexProtocol.ServerRequest request = codec.decodeServerRequest("""
                {"id":7,"method":"item/commandExecution/requestApproval","params":{
                "threadId":"thread-1","turnId":"turn-1","itemId":"exec-1",
                "command":"mvn verify","reason":null,"startedAtMs":1786884871461}}
                """);

        assertThat(request).isInstanceOf(CodexProtocol.CommandApprovalRequest.class);
        CodexProtocol.CommandApprovalRequest approval = (CodexProtocol.CommandApprovalRequest) request;
        assertThat(approval.params().command()).contains("mvn verify");
        assertThat(approval.params().reason()).isEmpty();
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

        ProviderAgentEvent.Message completed = only("""
                {"method":"item/completed","params":{"item":{"type":"agentMessage",
                "id":"message-1","text":"authoritative answer","phase":"final_answer"},
                "threadId":"thread-1","turnId":"turn-1"}}
                """, ProviderAgentEvent.Message.class);
        assertThat(completed.phase()).isEqualTo(ProviderAgentEvent.MessagePhase.FINAL);
        assertThat(completed.text()).isEqualTo("authoritative answer");
        assertThat(completed.providerEventId()).contains("message-1:completed");
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
                {"path":"/outside/secret","kind":{"type":"delete"},"diff":"-secret"}]},
                "threadId":"thread-1","turnId":"turn-1"}}
                """.formatted(file)));

        assertThat(events).singleElement().isInstanceOfSatisfying(
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
                {"method":"item/completed","params":{"item":{"type":"commandExecution",
                "id":"exec-1","command":"echo known-secret","status":"completed",
                "aggregatedOutput":"known-secret","exitCode":0},
                "threadId":"thread-1","turnId":"turn-1"}}
                """));

        ProviderAgentEvent.Command command = (ProviderAgentEvent.Command) events.getFirst();
        assertThat(command.argv()).containsExactly("echo [REDACTED]");
        assertThat(command.output()).contains("[REDACTED]");
        assertThat(command.toString()).doesNotContain("known-secret");
    }

    private <T extends ProviderAgentEvent> T only(String json, Class<T> type)
    {
        List<ProviderAgentEvent> events = mapper.map(codec.decodeNotification(json));
        assertThat(events).hasSize(1);
        return type.cast(events.getFirst());
    }
}
