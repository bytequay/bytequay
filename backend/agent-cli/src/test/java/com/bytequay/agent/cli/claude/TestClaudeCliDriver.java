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
package com.bytequay.agent.cli.claude;

import com.bytequay.agent.cli.process.ManagedProcess;
import com.bytequay.agent.cli.process.OwnedProcessRecovery;
import com.bytequay.agent.cli.process.PreparedProcess;
import com.bytequay.agent.cli.process.ProcessExit;
import com.bytequay.agent.cli.process.ProcessOutputSink;
import com.bytequay.agent.cli.process.ProcessSpec;
import com.bytequay.agent.cli.process.ProcessStream;
import com.bytequay.agent.cli.process.ProcessSupervisor;
import com.bytequay.agent.spi.AgentAttachment;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentSession;
import com.bytequay.agent.spi.AgentSessionContext;
import com.bytequay.agent.spi.AgentSessionRef;
import com.bytequay.agent.spi.AgentTurnCommand;
import com.bytequay.agent.spi.AgentTurnFailureKind;
import com.bytequay.agent.spi.AgentTurnResult;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.bytequay.agent.spi.ProviderApprovalDecision;
import com.bytequay.agent.spi.RunningTurn;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestClaudeCliDriver
{
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    private Path worktree;

    @Test
    void probesStreamsResumesHandlesMalformedAndTrailingEventsAndCancels()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        ClaudeCliDriver driver = driver(supervisor);
        List<ProviderAgentEvent> events = new CopyOnWriteArrayList<>();

        assertThat(driver.probe().readiness()).isEqualTo(AgentProviderStatus.Readiness.READY);
        assertThat(driver.capabilities().permissionPolicies())
                .containsExactlyInAnyOrder(AgentPermissionPolicy.values());
        assertThat(driver.capabilities().nestedAgentActivity()).isFalse();
        assertThat(driver.capabilities().attachmentMediaTypes())
                .contains("text/plain; charset=utf-8");
        AgentSession session = driver.open(context(Optional.empty()), events::add);
        AgentSessionRef reference = session.reference();

        AgentTurnResult first = await(session.start(command("first")));
        assertThat(first.state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        assertThat(first.finalResponse()).contains("authoritative [REDACTED]");
        List<ProviderAgentEvent.Message> messages = events.stream()
                .filter(ProviderAgentEvent.Message.class::isInstance)
                .map(ProviderAgentEvent.Message.class::cast)
                .toList();
        assertThat(messages).hasSize(4);
        assertThat(messages.subList(0, 2))
                .allSatisfy(message -> {
                    assertThat(message.phase()).isEqualTo(ProviderAgentEvent.MessagePhase.PARTIAL);
                    assertThat(message.messageKind()).isEqualTo(ProviderAgentEvent.MessageKind.COMMENTARY);
                    assertThat(message.providerEventId()).isEmpty();
                });
        ProviderAgentEvent.Message completedCommentary = messages.get(2);
        assertThat(completedCommentary.messageKind()).isEqualTo(ProviderAgentEvent.MessageKind.COMMENTARY);
        assertThat(completedCommentary.phase()).isEqualTo(ProviderAgentEvent.MessagePhase.FINAL);
        assertThat(completedCommentary.providerEventId()).contains("answer-1:text:0");
        ProviderAgentEvent.Message authoritative = messages.getLast();
        assertThat(authoritative.messageKind()).isEqualTo(ProviderAgentEvent.MessageKind.ANSWER);
        assertThat(authoritative.phase()).isEqualTo(ProviderAgentEvent.MessagePhase.FINAL);
        assertThat(authoritative.providerEventId()).contains("result-1:answer:0");
        assertThat(messages)
                .extracting(message -> message.source().providerItemId().orElseThrow())
                .containsOnly(messages.getFirst().source().providerItemId().orElseThrow());
        Map<String, String> projectedMessages = new LinkedHashMap<>();
        messages.forEach(message -> {
            String itemId = message.source().providerItemId().orElseThrow();
            if (message.phase() == ProviderAgentEvent.MessagePhase.PARTIAL) {
                projectedMessages.merge(itemId, message.text(), String::concat);
            }
            else {
                projectedMessages.put(itemId, message.text());
            }
        });
        assertThat(projectedMessages).containsExactly(Map.entry(
                messages.getFirst().source().providerItemId().orElseThrow(),
                "authoritative [REDACTED]"));
        ProcessSpec firstSpec = supervisor.turns().getFirst().spec();
        assertThat(firstSpec.argv()).containsSequence("--session-id", reference.providerValue());
        assertThat(firstSpec.argv()).contains("--permission-prompt-tool", "mcp__bytequay__permission_prompt");
        assertThat(firstSpec.argv()).containsSequence("--permission-mode", "manual");
        assertThat(firstSpec.argv()).doesNotContain("first");
        assertThat(supervisor.turns().getFirst().prompt()).isEqualTo("first\n");
        assertThat(events).anyMatch(ProviderAgentEvent.Error.class::isInstance);
        assertThat(events).filteredOn(ProviderAgentEvent.Tool.class::isInstance)
                .anySatisfy(event -> assertThat(((ProviderAgentEvent.Tool) event).name())
                        .isEqualTo("hook/Stop"));
        int trailingHook = indexOf(events, event -> event instanceof ProviderAgentEvent.Tool tool
                && tool.name().equals("hook/Stop"));
        int completion = indexOf(events, event -> event instanceof ProviderAgentEvent.Lifecycle lifecycle
                && lifecycle.phase() == ProviderAgentEvent.LifecyclePhase.TURN_COMPLETED);
        assertThat(trailingHook).isLessThan(completion);
        assertThat(events.toString()).doesNotContain("known-secret");

        AgentTurnResult second = await(session.start(command("second")));
        assertThat(second.state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        assertThat(supervisor.turns().get(1).spec().argv())
                .containsSequence("--resume", reference.providerValue());

        AgentTurnResult failed = await(session.start(command("fail")));
        assertThat(failed.state()).isEqualTo(AgentTurnResult.State.FAILED);
        assertThat(failed.terminalReason()).contains("[REDACTED] diagnostic");

        RunningTurn cancelled = session.start(command("cancel"));
        cancelled.cancel();
        assertThat(await(cancelled).state()).isEqualTo(AgentTurnResult.State.CANCELLED);

        Path configFile = runtimeDirectory().resolve("provider/scratch/bytequay-mcp.json");
        assertThat(configFile).exists();
        session.close();
        assertThat(configFile).doesNotExist();

        AgentSession ciSession = driver.open(context(Optional.empty(), AgentPermissionPolicy.CI_AUTOFIX), events::add);
        Path log = runtimeDirectory().resolve("blobs/ci.log");
        Files.createDirectories(log.getParent());
        Files.writeString(log, "failure");
        Path realLog = log.toRealPath();
        AgentAttachment attachment = new AgentAttachment(
                "github-actions.log",
                "text/plain; charset=utf-8",
                log);
        assertThat(await(ciSession.start(command("ci-fix", ImmutableList.of(attachment)))).state())
                .isEqualTo(AgentTurnResult.State.COMPLETED);
        ProcessSpec ciSpec = supervisor.turns().getLast().spec();
        assertThat(ciSpec.argv()).containsSequence("--permission-mode", "auto");
        assertThat(ciSpec.argv()).contains("--strict-mcp-config");
        assertThat(ciSpec.argv()).contains("--setting-sources=");
        assertThat(ciSpec.argv()).containsSequence("--add-dir", realLog.getParent().toString());
        int allowedTools = ciSpec.argv().indexOf("--allowedTools");
        assertThat(allowedTools).isGreaterThanOrEqualTo(0);
        assertThat(ciSpec.argv().get(allowedTools + 1))
                .contains("mcp__bytequay__read_ci_memory", "Read(/" + realLog + ")");
        assertThat(supervisor.turns().getLast().prompt()).contains(realLog.toString());
        assertThat(ciSpec.environment()).doesNotContainKeys("GH_TOKEN", "GITHUB_TOKEN");
        Path settingsFile = runtimeDirectory().resolve("provider/scratch/sandbox-settings.json");
        assertThat(settingsFile).exists();
        ciSession.close();
        assertThat(settingsFile).doesNotExist();

        AgentSession cherryPickSession = driver.open(context(Optional.empty(), AgentPermissionPolicy.CHERRY_PICK), events::add);
        assertThat(await(cherryPickSession.start(command("resolve"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        ProcessSpec cherryPickSpec = supervisor.turns().getLast().spec();
        assertThat(cherryPickSpec.argv()).containsSequence("--permission-mode", "auto");
        assertThat(cherryPickSpec.argv()).contains("--strict-mcp-config", "--setting-sources=", "--settings");
        assertThat(cherryPickSpec.argv()).doesNotContain("--allowedTools");
        assertThat(String.join(" ", cherryPickSpec.argv())).doesNotContain("read_ci_memory");
        assertThat(settingsFile).exists();
        cherryPickSession.close();
        assertThat(settingsFile).doesNotExist();

        AgentSession autoSession = driver.open(context(Optional.empty(), AgentPermissionPolicy.AUTO), events::add);
        assertThat(await(autoSession.start(command("auto"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        ProcessSpec autoSpec = supervisor.turns().getLast().spec();
        assertThat(autoSpec.argv()).containsSequence("--permission-mode", "manual");
        assertThat(autoSpec.argv()).contains("--strict-mcp-config", "--setting-sources=", "--settings");
        assertThat(autoSpec.argv()).contains("--permission-prompt-tool", "mcp__bytequay__permission_prompt");
        assertThat(settingsFile).exists();
        autoSession.close();
        assertThat(settingsFile).doesNotExist();

        AgentSession textSession = driver.open(context(
                Optional.empty(), AgentPermissionPolicy.AUTO, Optional.of("sonnet"), Optional.of("Extra"), true),
                events::add);
        assertThat(await(textSession.start(command("rewrite"))).state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        ProcessSpec textSpec = supervisor.turns().getLast().spec();
        assertThat(textSpec.argv()).contains("--tools=");
        assertThat(textSpec.argv()).contains("--disable-slash-commands", "--no-session-persistence");
        assertThat(textSpec.argv()).containsSequence("--model", "sonnet");
        assertThat(textSpec.argv()).containsSequence("--effort", "xhigh");
        assertThat(settingsFile).exists();
        assertThat(objectMapper.readTree(Files.readString(settingsFile))
                .at("/sandbox/allowUnsandboxedCommands").asBoolean()).isFalse();
        assertThat(objectMapper.readTree(Files.readString(settingsFile))
                .at("/sandbox/network/allowedDomains")).isEmpty();
        textSession.close();
        assertThat(settingsFile).doesNotExist();
        driver.close();
    }

    @Test
    void distinguishesUnsupportedFromUnparseableVersions()
    {
        ScriptedProcessSupervisor unsupportedSupervisor = new ScriptedProcessSupervisor();
        unsupportedSupervisor.versionOutput("1.0.0");
        ClaudeCliDriver unsupportedDriver = driver(unsupportedSupervisor);
        assertThat(unsupportedDriver.probe().readiness()).isEqualTo(AgentProviderStatus.Readiness.UNSUPPORTED);
        unsupportedDriver.close();

        ScriptedProcessSupervisor unparseableSupervisor = new ScriptedProcessSupervisor();
        unparseableSupervisor.versionOutput("future-build");
        ClaudeCliDriver unparseableDriver = driver(unparseableSupervisor);
        assertThat(unparseableDriver.probe().readiness()).isEqualTo(AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE);
        unparseableDriver.close();
    }

    @Test
    void rejectsClaudeWithoutManualPermissionMode()
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        supervisor.helpOutput(supervisor.helpOutput().replace(" \"manual\"", ""));
        ClaudeCliDriver driver = driver(supervisor);

        AgentProviderStatus status = driver.probe();

        assertThat(status.readiness()).isEqualTo(AgentProviderStatus.Readiness.UNSUPPORTED);
        assertThat(status.diagnostic()).contains("manual permission mode");
        assertThat(supervisor.authenticationProbes()).isZero();
        driver.close();
    }

    @Test
    void appliesDistinctPerTurnSnapshotsToNewAndResumedInvocations()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        ClaudeCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(
                Optional.empty(),
                AgentPermissionPolicy.AUTO,
                Optional.of("opus"),
                Optional.of("High"),
                false), ignored -> {});

        await(session.start(new AgentTurnCommand(
                "turn-first",
                "first",
                ImmutableList.of(),
                Duration.ofMinutes(1),
                Optional.of("sonnet"),
                Optional.of("Low"))));
        await(session.start(new AgentTurnCommand(
                "turn-second",
                "second",
                ImmutableList.of(),
                Duration.ofMinutes(1),
                Optional.of("haiku"),
                Optional.of("Extra"))));

        assertThat(supervisor.turns().getFirst().spec().argv())
                .containsSequence("--model", "sonnet")
                .containsSequence("--effort", "low")
                .contains("--session-id")
                .doesNotContain("--resume");
        assertThat(supervisor.turns().get(1).spec().argv())
                .containsSequence("--model", "haiku")
                .containsSequence("--effort", "xhigh")
                .contains("--resume");
        session.close();
        driver.close();
    }

    @Test
    void redactsFailedUpdateDiagnostics()
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        supervisor.updateFailure("known-secret stdout", "known-secret stderr", 1);
        ClaudeCliDriver driver = driver(supervisor);

        assertThatThrownBy(driver::update)
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("[REDACTED]")
                .hasMessageNotContaining("known-secret");
        driver.close();
    }

    @Test
    void redactsTheRuntimeTaskTokenFromTranscriptAndDiagnostics()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        ClaudeCliDriver driver = driver(supervisor);
        List<ProviderAgentEvent> events = new CopyOnWriteArrayList<>();
        AgentSession session = driver.open(context(Optional.empty()), events::add);
        Path configFile = runtimeDirectory().resolve("provider/scratch/bytequay-mcp.json");
        String authorization = objectMapper.readTree(Files.readString(configFile))
                .at("/mcpServers/bytequay/headers/Authorization")
                .asText();
        String token = authorization.substring("Bearer ".length());
        supervisor.runtimeSecret(token);

        AgentTurnResult completed = await(session.start(command("token")));

        assertThat(completed.finalResponse())
                .hasValueSatisfying(response -> assertThat(response).contains("[REDACTED]"));
        assertThat(events).filteredOn(ProviderAgentEvent.Message.class::isInstance)
                .map(ProviderAgentEvent.Message.class::cast)
                .anySatisfy(message -> assertThat(message.text()).contains("[REDACTED]"));
        assertThat(events).filteredOn(ProviderAgentEvent.Tool.class::isInstance)
                .map(ProviderAgentEvent.Tool.class::cast)
                .anySatisfy(tool -> assertThat(tool.summary()).contains("[REDACTED]"));
        assertThat(events.toString()).doesNotContain(token);

        AgentTurnResult failed = await(session.start(command("fail")));
        assertThat(failed.terminalReason()).hasValueSatisfying(reason -> assertThat(reason)
                .contains("[REDACTED]")
                .doesNotContain(token));
        assertThat(events.toString()).doesNotContain(token);
        session.close();
        driver.close();
    }

    @Test
    void usesTypedAuthenticationStatusWithoutParsingStderr()
    {
        ScriptedProcessSupervisor loggedIn = new ScriptedProcessSupervisor();
        loggedIn.authentication(
                "{\"loggedIn\":true,\"authMethod\":\"oauth\",\"apiProvider\":\"firstParty\"}",
                "ignored diagnostic",
                0);
        ClaudeCliDriver loggedInDriver = driver(loggedIn);
        assertThat(loggedInDriver.probe().readiness()).isEqualTo(AgentProviderStatus.Readiness.READY);
        loggedInDriver.close();

        ScriptedProcessSupervisor loggedOut = new ScriptedProcessSupervisor();
        loggedOut.authentication(
                "{\"loggedIn\":false,\"authMethod\":\"none\",\"projectsDirectory\":\"/secret/path\"}",
                "{\"loggedIn\":true}",
                1);
        ClaudeCliDriver loggedOutDriver = driver(loggedOut);

        AgentProviderStatus status = loggedOutDriver.probe();

        assertThat(status.readiness()).isEqualTo(AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED);
        assertThat(status.signInCommand()).contains("claude auth login");
        assertThat(status.diagnostic()).doesNotContain("/secret/path", "authMethod");
        loggedOutDriver.close();

        ScriptedProcessSupervisor malformed = new ScriptedProcessSupervisor();
        malformed.authentication("{\"loggedIn\":false}{\"loggedIn\":true}", "", 1);
        ClaudeCliDriver malformedDriver = driver(malformed);
        assertThat(malformedDriver.probe().readiness())
                .isEqualTo(AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE);
        malformedDriver.close();

        ScriptedProcessSupervisor wrongType = new ScriptedProcessSupervisor();
        wrongType.authentication("{\"loggedIn\":\"false\"}", "", 0);
        ClaudeCliDriver wrongTypeDriver = driver(wrongType);
        assertThat(wrongTypeDriver.probe().readiness())
                .isEqualTo(AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE);
        wrongTypeDriver.close();

        ScriptedProcessSupervisor missingField = new ScriptedProcessSupervisor();
        missingField.authentication("{\"authMethod\":\"oauth\"}", "", 0);
        ClaudeCliDriver missingFieldDriver = driver(missingField);
        assertThat(missingFieldDriver.probe().readiness())
                .isEqualTo(AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE);
        missingFieldDriver.close();

        ScriptedProcessSupervisor nonzeroLoggedIn = new ScriptedProcessSupervisor();
        nonzeroLoggedIn.authentication("{\"loggedIn\":true}", "", 1);
        ClaudeCliDriver nonzeroLoggedInDriver = driver(nonzeroLoggedIn);
        assertThat(nonzeroLoggedInDriver.probe().readiness())
                .isEqualTo(AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE);
        nonzeroLoggedInDriver.close();

        ScriptedProcessSupervisor timedOut = new ScriptedProcessSupervisor();
        timedOut.authenticationTimeout();
        ClaudeCliDriver timedOutDriver = driver(timedOut);
        assertThat(timedOutDriver.probe().readiness())
                .isEqualTo(AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE);
        timedOutDriver.close();

        ScriptedProcessSupervisor hostWaitTimedOut = new ScriptedProcessSupervisor();
        hostWaitTimedOut.authenticationHostWaitTimeout();
        ClaudeCliDriver hostWaitTimedOutDriver = driver(hostWaitTimedOut);
        assertThat(hostWaitTimedOutDriver.probe().readiness())
                .isEqualTo(AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE);
        hostWaitTimedOutDriver.close();

        ScriptedProcessSupervisor truncated = new ScriptedProcessSupervisor();
        truncated.authenticationTruncated();
        ClaudeCliDriver truncatedDriver = driver(truncated);
        assertThat(truncatedDriver.probe().readiness())
                .isEqualTo(AgentProviderStatus.Readiness.TEMPORARILY_UNAVAILABLE);
        truncatedDriver.close();
    }

    @Test
    void classifiesMidTurnAuthenticationExpiryOnlyFromTypedFollowUpProbe()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        ClaudeCliDriver driver = driver(supervisor);
        List<ProviderAgentEvent> events = new CopyOnWriteArrayList<>();
        assertThat(driver.probe().readiness()).isEqualTo(AgentProviderStatus.Readiness.READY);
        supervisor.authentication("{\"loggedIn\":false}", "expired", 1);
        AgentSession session = driver.open(context(Optional.empty()), events::add);

        AgentTurnResult result = await(session.start(command("fail")));

        assertThat(result.state()).isEqualTo(AgentTurnResult.State.FAILED);
        assertThat(result.failureKind()).contains(AgentTurnFailureKind.AUTHENTICATION_REQUIRED);
        assertThat(result.terminalReason()).contains("Claude sign-in required");
        assertThat(supervisor.authenticationProbes()).isEqualTo(2);
        assertThat(events).filteredOn(ProviderAgentEvent.Error.class::isInstance)
                .map(ProviderAgentEvent.Error.class::cast)
                .singleElement()
                .satisfies(error -> {
                    assertThat(error.code()).isEqualTo("AUTHENTICATION_REQUIRED");
                    assertThat(error.message()).isEqualTo("Claude sign-in required");
                });
        session.close();
        driver.close();
    }

    @Test
    void rejectsTurnWhenPromptWriteFails()
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        supervisor.failNextPromptWrite();
        ClaudeCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty()), ignored -> {});

        assertThatThrownBy(() -> session.start(command("write-failure")))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("Unable to write Claude prompt")
                .hasRootCauseMessage("simulated prompt write failure");

        assertThat(supervisor.turns()).singleElement().satisfies(turn -> {
            assertThat(turn.cancelled()).isTrue();
            assertThat(turn.inputClosed()).isFalse();
        });
        session.close();
        driver.close();
    }

    @Test
    void rejectsTurnWhenPromptInputCannotClose()
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        supervisor.failNextPromptClose();
        ClaudeCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty()), ignored -> {});

        assertThatThrownBy(() -> session.start(command("close-failure")))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("Unable to close Claude prompt input")
                .hasRootCauseMessage("simulated prompt close failure");

        assertThat(supervisor.turns()).singleElement().satisfies(turn -> {
            assertThat(turn.cancelled()).isTrue();
            assertThat(turn.inputClosed()).isTrue();
        });
        session.close();
        driver.close();
    }

    @Test
    void interruptsTurnWhenFinalUsageCannotPersist()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        ClaudeCliDriver driver = driver(supervisor);
        List<ProviderAgentEvent> events = new CopyOnWriteArrayList<>();
        AtomicBoolean failUsage = new AtomicBoolean(true);
        AgentSession session = driver.open(context(Optional.empty()), event -> {
            if (event instanceof ProviderAgentEvent.Usage && failUsage.compareAndSet(true, false)) {
                throw new IllegalStateException("simulated usage persistence failure");
            }
            events.add(event);
        });

        AgentTurnResult result = await(session.start(command("usage-failure")));

        assertThat(result.state()).isEqualTo(AgentTurnResult.State.INTERRUPTED);
        assertThat(result.terminalReason())
                .contains("Unable to persist Claude terminal events: simulated usage persistence failure");
        assertThat(events).noneMatch(ProviderAgentEvent.Usage.class::isInstance);
        assertThat(events).anyMatch(ProviderAgentEvent.Error.class::isInstance);
        session.close();
        driver.close();
    }

    @Test
    void refusesToCompleteATurnWhoseProcessTreeIsUnverifiable()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        ClaudeCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty()), event -> {});
        Path scratch = Files.createDirectories(worktree.resolve(".claude/.cc-writes"));

        AgentTurnResult result = await(session.start(command("unverifiable")));

        // A clean provider result is not quiescence: the group may still be writing to the worktree.
        assertThat(result.state()).isEqualTo(AgentTurnResult.State.INTERRUPTED);
        assertThat(result.terminalReason()).contains("Claude process tree could not be verified stopped");
        assertThat(result.finalResponse()).isEmpty();
        assertThat(result.providerQuiescent()).isFalse();
        assertThat(scratch).isDirectory();
        assertThatThrownBy(session::close)
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("process tree could not be verified stopped");

        supervisor.turns().getFirst().verifyNextStop();
        session.close();
        driver.close();
    }

    @Test
    void closingASessionWaitsForItsLiveProcessTreeToStop()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        ClaudeCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty()), event -> {});
        RunningTurn running = session.start(command("cancel"));

        session.close();

        AgentTurnResult result = await(running);
        assertThat(result.state()).isEqualTo(AgentTurnResult.State.CANCELLED);
        assertThat(result.providerQuiescent()).isTrue();
        assertThat(supervisor.turns()).singleElement().satisfies(turn -> assertThat(turn.cancelled()).isTrue());
        driver.close();
    }

    @Test
    void removesOnlyTheEmptyClaudeScratchAfterVerifiedProcessExit()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        ClaudeCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty()), event -> {});
        Path claudeDirectory = Files.createDirectories(worktree.resolve(".claude"));
        Path scratch = Files.createDirectory(claudeDirectory.resolve(".cc-writes"));

        AgentTurnResult result = await(session.start(command("scratch-cleanup")));

        assertThat(result.state()).isEqualTo(AgentTurnResult.State.COMPLETED);
        assertThat(scratch).doesNotExist();
        assertThat(claudeDirectory).doesNotExist();
    }

    @Test
    void leavesClaudeFilesNonemptyDirectoriesAndSymlinksUntouched()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        ClaudeCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty()), event -> {});
        Path claudeDirectory = Files.createDirectories(worktree.resolve(".claude"));
        Path scratch = Files.createDirectory(claudeDirectory.resolve(".cc-writes"));
        Path stagedWrite = Files.writeString(scratch.resolve("pending"), "keep me\n");
        Path settings = Files.writeString(claudeDirectory.resolve("settings.json"), "{}\n");

        assertThat(await(session.start(command("nonempty-scratch"))).state())
                .isEqualTo(AgentTurnResult.State.COMPLETED);
        assertThat(stagedWrite).hasContent("keep me\n");
        assertThat(settings).hasContent("{}\n");

        Files.delete(stagedWrite);
        Files.delete(scratch);
        Path scratchFile = Files.writeString(claudeDirectory.resolve(".cc-writes"), "not a directory\n");
        assertThat(await(session.start(command("scratch-file"))).state())
                .isEqualTo(AgentTurnResult.State.COMPLETED);
        assertThat(scratchFile).hasContent("not a directory\n");

        Files.delete(scratchFile);
        Path outside = Files.createDirectory(worktree.resolve("outside-scratch"));
        Path scratchLink = Files.createSymbolicLink(claudeDirectory.resolve(".cc-writes"), outside);
        assertThat(await(session.start(command("scratch-symlink"))).state())
                .isEqualTo(AgentTurnResult.State.COMPLETED);
        assertThat(Files.isSymbolicLink(scratchLink)).isTrue();
        assertThat(outside).isDirectory();

        Files.delete(scratchLink);
        Files.delete(settings);
        Files.delete(claudeDirectory);
        Path outsideClaude = Files.createDirectories(worktree.resolve("outside-claude/.cc-writes"));
        Path claudeLink = Files.createSymbolicLink(worktree.resolve(".claude"), outsideClaude.getParent());
        assertThat(await(session.start(command("claude-symlink"))).state())
                .isEqualTo(AgentTurnResult.State.COMPLETED);
        assertThat(Files.isSymbolicLink(claudeLink)).isTrue();
        assertThat(outsideClaude).isDirectory();
    }

    @Test
    void failsActionablyWhenEmptyClaudeScratchCannotBeRemoved()
            throws Exception
    {
        ScriptedProcessSupervisor supervisor = new ScriptedProcessSupervisor();
        ClaudeCliDriver driver = driver(supervisor);
        AgentSession session = driver.open(context(Optional.empty()), event -> {});
        Path claudeDirectory = Files.createDirectories(worktree.resolve(".claude"));
        Path scratch = Files.createDirectory(claudeDirectory.resolve(".cc-writes"));
        Files.setPosixFilePermissions(claudeDirectory, PosixFilePermissions.fromString("r-x------"));

        try {
            AgentTurnResult result = await(session.start(command("scratch-cleanup-failure")));

            assertThat(result.state()).isEqualTo(AgentTurnResult.State.FAILED);
            assertThat(result.terminalReason()).hasValueSatisfying(reason -> assertThat(reason)
                    .contains("could not remove Claude's empty .claude/.cc-writes")
                    .contains("worktree was retained"));
            assertThat(result.providerQuiescent()).isTrue();
            assertThat(scratch).isDirectory();
            assertThat(worktree).isDirectory();
        }
        finally {
            Files.setPosixFilePermissions(claudeDirectory, PosixFilePermissions.fromString("rwx------"));
        }
    }

    private ClaudeCliDriver driver(ProcessSupervisor supervisor)
    {
        ClaudePermissionBroker permissions = new ClaudePermissionBroker(
                () -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"),
                objectMapper,
                (taskId, query) -> "memory");
        return new ClaudeCliDriver(
                "/fake/claude",
                objectMapper,
                permissions,
                supervisor,
                ImmutableMap.of(
                        "HOME", worktree.toString(),
                        "ANTHROPIC_API_KEY", "known-secret"));
    }

    private AgentSessionContext context(Optional<AgentSessionRef> reference)
    {
        return context(reference, AgentPermissionPolicy.AUTO);
    }

    private AgentSessionContext context(
            Optional<AgentSessionRef> reference,
            AgentPermissionPolicy permissionPolicy)
    {
        return context(reference, permissionPolicy, false);
    }

    private AgentSessionContext context(
            Optional<AgentSessionRef> reference,
            AgentPermissionPolicy permissionPolicy,
            boolean textOnly)
    {
        return context(reference, permissionPolicy, Optional.empty(), Optional.empty(), textOnly);
    }

    private AgentSessionContext context(
            Optional<AgentSessionRef> reference,
            AgentPermissionPolicy permissionPolicy,
            Optional<String> model,
            Optional<String> effort,
            boolean textOnly)
    {
        return new AgentSessionContext(
                "task-1",
                worktree,
                runtimeDirectory(),
                runtimeDirectory().resolve("provider/scratch"),
                permissionPolicy,
                reference,
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                model,
                effort,
                textOnly);
    }

    private Path runtimeDirectory()
    {
        return worktree.resolve("task-runtime");
    }

    private static AgentTurnCommand command(String message)
    {
        return command(message, ImmutableList.of());
    }

    private static AgentTurnCommand command(String message, List<AgentAttachment> attachments)
    {
        return new AgentTurnCommand("turn-" + message, message, attachments, Duration.ofMinutes(1));
    }

    private static AgentTurnResult await(RunningTurn turn)
            throws Exception
    {
        return turn.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static int indexOf(
            List<ProviderAgentEvent> events,
            Predicate<ProviderAgentEvent> predicate)
    {
        for (int index = 0; index < events.size(); index++) {
            if (predicate.test(events.get(index))) {
                return index;
            }
        }
        return -1;
    }

    private static final class ScriptedProcessSupervisor
            implements ProcessSupervisor
    {
        private final List<ScriptedTurn> turns = new CopyOnWriteArrayList<>();
        private String versionOutput = "2.1.233 (Claude Code)";
        private String helpOutput = """
                --output-format --include-partial-messages --include-hook-events
                --forward-subagent-text --permission-mode (choices: \"auto\" \"manual\") --mcp-config
                --settings --setting-sources --strict-mcp-config --add-dir
                --tools --no-session-persistence --disable-slash-commands --model --effort
                --allowedTools --permission-prompt-tool --print --verbose --resume --session-id
                """;
        private String authenticationStdout = "{\"loggedIn\":true}";
        private String authenticationStderr = "";
        private int authenticationExitCode;
        private int authenticationProbes;
        private boolean authenticationTimeout;
        private boolean authenticationHostWaitTimeout;
        private boolean authenticationTruncated;
        private String updateStdout = "updated";
        private String updateStderr = "";
        private int updateExitCode;
        private String runtimeSecret = "";
        private boolean failNextPromptWrite;
        private boolean failNextPromptClose;

        @Override
        public PreparedProcess prepare(ProcessSpec spec)
        {
            return new ScriptedLaunch(spec);
        }

        @Override
        public List<OwnedProcessRecovery> recoverOwnedProcesses()
        {
            return ImmutableList.of();
        }

        @Override
        public void acknowledge(String launchId) {}

        private final class ScriptedLaunch
                implements PreparedProcess
        {
            private final ProcessSpec spec;

            private ScriptedLaunch(ProcessSpec spec)
            {
                this.spec = spec;
            }

            @Override
            public String launchId()
            {
                return "scripted";
            }

            @Override
            public void abandon() {}

            @Override
            public ManagedProcess start(ProcessOutputSink output)
            {
                return ScriptedProcessSupervisor.this.start(spec, output);
            }
        }

        private ManagedProcess start(ProcessSpec spec, ProcessOutputSink output)
        {
            if (spec.argv().contains("--version")) {
                output.onOutput(ProcessStream.STDOUT, StandardCharsets.UTF_8.encode(versionOutput + "\n"));
                return ImmediateProcess.success();
            }
            if (spec.argv().contains("--help")) {
                output.onOutput(ProcessStream.STDOUT, StandardCharsets.UTF_8.encode(helpOutput));
                return ImmediateProcess.success();
            }
            if (spec.argv().contains("auth") && spec.argv().contains("status")) {
                authenticationProbes++;
                output.onOutput(ProcessStream.STDOUT, StandardCharsets.UTF_8.encode(authenticationStdout));
                output.onOutput(ProcessStream.STDERR, StandardCharsets.UTF_8.encode(authenticationStderr));
                if (authenticationHostWaitTimeout) {
                    return ImmediateProcess.hostWaitTimedOut();
                }
                if (authenticationTimeout) {
                    return ImmediateProcess.timedOut();
                }
                if (authenticationTruncated) {
                    return ImmediateProcess.stdoutTruncated(authenticationExitCode);
                }
                return ImmediateProcess.exited(authenticationExitCode);
            }
            if (spec.argv().contains("update")) {
                output.onOutput(ProcessStream.STDOUT, StandardCharsets.UTF_8.encode(updateStdout));
                output.onOutput(ProcessStream.STDERR, StandardCharsets.UTF_8.encode(updateStderr));
                return ImmediateProcess.exited(updateExitCode);
            }
            ScriptedTurn turn = new ScriptedTurn(
                    spec,
                    output,
                    failNextPromptWrite,
                    failNextPromptClose,
                    runtimeSecret);
            failNextPromptWrite = false;
            failNextPromptClose = false;
            turns.add(turn);
            return turn;
        }

        private List<ScriptedTurn> turns()
        {
            return turns;
        }

        private void versionOutput(String output)
        {
            versionOutput = output;
        }

        private String helpOutput()
        {
            return helpOutput;
        }

        private void helpOutput(String output)
        {
            helpOutput = output;
        }

        private void authentication(String stdout, String stderr, int exitCode)
        {
            authenticationStdout = stdout;
            authenticationStderr = stderr;
            authenticationExitCode = exitCode;
            authenticationTimeout = false;
            authenticationHostWaitTimeout = false;
            authenticationTruncated = false;
        }

        private void authenticationTimeout()
        {
            authenticationTimeout = true;
        }

        private void authenticationHostWaitTimeout()
        {
            authenticationStdout = "{\"loggedIn\":false}";
            authenticationTimeout = false;
            authenticationHostWaitTimeout = true;
        }

        private void authenticationTruncated()
        {
            authenticationStdout = "{\"loggedIn\":true}";
            authenticationExitCode = 0;
            authenticationTimeout = false;
            authenticationHostWaitTimeout = false;
            authenticationTruncated = true;
        }

        private int authenticationProbes()
        {
            return authenticationProbes;
        }

        private void updateFailure(String stdout, String stderr, int exitCode)
        {
            updateStdout = stdout;
            updateStderr = stderr;
            updateExitCode = exitCode;
        }

        private void runtimeSecret(String secret)
        {
            runtimeSecret = secret;
        }

        private void failNextPromptWrite()
        {
            failNextPromptWrite = true;
        }

        private void failNextPromptClose()
        {
            failNextPromptClose = true;
        }
    }

    private static final class ScriptedTurn
            implements ManagedProcess
    {
        private final ProcessSpec spec;
        private final ProcessOutputSink output;
        private final CompletableFuture<ProcessExit> exit = new CompletableFuture<>();
        private final boolean failPromptWrite;
        private final boolean failPromptClose;
        private final String runtimeSecret;
        private ProcessExit.TreeTermination nextStopVerification;
        private String prompt = "";
        private boolean cancelled;
        private boolean inputClosed;

        private ScriptedTurn(
                ProcessSpec spec,
                ProcessOutputSink output,
                boolean failPromptWrite,
                boolean failPromptClose,
                String runtimeSecret)
        {
            this.spec = spec;
            this.output = output;
            this.failPromptWrite = failPromptWrite;
            this.failPromptClose = failPromptClose;
            this.runtimeSecret = runtimeSecret;
        }

        private ProcessSpec spec()
        {
            return spec;
        }

        private String prompt()
        {
            return prompt;
        }

        private boolean cancelled()
        {
            return cancelled;
        }

        private boolean inputClosed()
        {
            return inputClosed;
        }

        private void verifyNextStop()
        {
            nextStopVerification = ProcessExit.TreeTermination.VERIFIED_STOPPED;
        }

        @Override
        public String launchId()
        {
            return "scripted";
        }

        @Override
        public long pid()
        {
            return 4242;
        }

        @Override
        public CompletionStage<Void> write(ByteBuffer bytes)
        {
            if (failPromptWrite) {
                return CompletableFuture.failedFuture(new IllegalStateException("simulated prompt write failure"));
            }
            prompt += StandardCharsets.UTF_8.decode(bytes).toString();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void closeInput()
        {
            inputClosed = true;
            if (failPromptClose) {
                throw new IllegalStateException("simulated prompt close failure");
            }
            if (prompt.equals("cancel\n")) {
                return;
            }
            if (prompt.equals("fail\n")) {
                emit(ProcessStream.STDERR, "known-secret diagnostic"
                        + (runtimeSecret.isEmpty() ? "" : " " + runtimeSecret)
                        + "\n");
                exit.complete(new ProcessExit(1, ProcessExit.Reason.EXITED, ProcessExit.TreeTermination.VERIFIED_STOPPED, Duration.ofMillis(5), false, false));
                return;
            }
            String sessionId = sessionId();
            String runtimeSecretSuffix = runtimeSecret.isEmpty() ? "" : " " + runtimeSecret;
            emit(ProcessStream.STDOUT, "not-json\n");
            emit(ProcessStream.STDOUT,
                    "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"%s\","
                            .formatted(sessionId)
                            + "\"uuid\":\"init-1\"}\n");
            emit(ProcessStream.STDOUT,
                    "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\","
                            + "\"index\":0,\"delta\":{\"type\":\"text_delta\","
                            + "\"text\":\"draft known-secret\"}},\"session_id\":\"%s\","
                                    .formatted(sessionId)
                            + "\"parent_tool_use_id\":null,\"uuid\":\"delta-1\"}\n");
            emit(ProcessStream.STDOUT,
                    "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\","
                            + "\"index\":0,\"delta\":{\"type\":\"text_delta\","
                            + "\"text\":\" still drafting\"}},\"session_id\":\"%s\","
                                    .formatted(sessionId)
                            + "\"parent_tool_use_id\":null,\"uuid\":\"delta-2\"}\n");
            emit(ProcessStream.STDOUT,
                    ("{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":["
                            + "{\"type\":\"text\",\"text\":\"authoritative known-secret%s\"}]},"
                            + "\"parent_tool_use_id\":null,\"session_id\":\"%s\","
                            + "\"uuid\":\"answer-1\"}\n")
                            .formatted(runtimeSecretSuffix, sessionId));
            emit(ProcessStream.STDOUT,
                    ("{\"type\":\"result\",\"is_error\":false,\"duration_ms\":10,"
                            + "\"duration_api_ms\":8,\"num_turns\":1,\"session_id\":\"%s\","
                            + "\"total_cost_usd\":0.01,\"usage\":{\"input_tokens\":2,"
                            + "\"cache_creation_input_tokens\":3,\"cache_read_input_tokens\":4,"
                            + "\"output_tokens\":5},\"subtype\":\"success\","
                            + "\"result\":\"authoritative known-secret%s\","
                            + "\"terminal_reason\":\"completed\",\"uuid\":\"result-1\"}\n")
                            .formatted(sessionId, runtimeSecretSuffix));
            emit(ProcessStream.STDOUT,
                    ("{\"type\":\"system\",\"subtype\":\"hook_response\","
                            + "\"hook_id\":\"hook-1\",\"hook_name\":\"Stop\","
                            + "\"hook_event\":\"Stop\",\"output\":\"trailing event%s\","
                            + "\"exit_code\":0,\"outcome\":\"success\",\"session_id\":\"%s\","
                            + "\"uuid\":\"trailing-1\"}\n")
                            .formatted(runtimeSecretSuffix, sessionId));
            emit(ProcessStream.STDERR, "known-secret warning" + runtimeSecretSuffix + "\n");
            exit.complete(new ProcessExit(
                    0,
                    ProcessExit.Reason.EXITED,
                    prompt.equals("unverifiable\n")
                            ? ProcessExit.TreeTermination.UNVERIFIABLE
                            : ProcessExit.TreeTermination.VERIFIED_STOPPED,
                    Duration.ofMillis(10),
                    false,
                    false));
        }

        private String sessionId()
        {
            int option = spec.argv().indexOf("--session-id");
            if (option < 0) {
                option = spec.argv().indexOf("--resume");
            }
            return spec.argv().get(option + 1);
        }

        private void emit(ProcessStream stream, String text)
        {
            output.onOutput(stream, StandardCharsets.UTF_8.encode(text));
        }

        @Override
        public CompletionStage<ProcessExit> exit()
        {
            return exit;
        }

        @Override
        public void cancel()
        {
            cancelled = true;
            exit.complete(new ProcessExit(143, ProcessExit.Reason.CANCELLED, ProcessExit.TreeTermination.VERIFIED_STOPPED, Duration.ofMillis(5), false, false));
        }

        @Override
        public CompletionStage<ProcessExit.TreeTermination> stopAndVerify()
        {
            cancelled = true;
            ProcessExit terminal = exit.getNow(null);
            ProcessExit.TreeTermination verification = nextStopVerification == null
                    ? terminal == null
                            ? ProcessExit.TreeTermination.VERIFIED_STOPPED
                            : terminal.treeTermination()
                    : nextStopVerification;
            nextStopVerification = null;
            if (terminal == null) {
                exit.complete(new ProcessExit(
                        143,
                        ProcessExit.Reason.CANCELLED,
                        verification,
                        Duration.ofMillis(5),
                        false,
                        false));
            }
            return CompletableFuture.completedFuture(verification);
        }

        @Override
        public void close() {}
    }

    private static final class ImmediateProcess
            implements ManagedProcess
    {
        private final CompletableFuture<ProcessExit> exit;

        private ImmediateProcess(CompletableFuture<ProcessExit> exit)
        {
            this.exit = exit;
        }

        private ImmediateProcess(
                int exitCode,
                ProcessExit.Reason reason,
                boolean stdoutTruncated,
                boolean stderrTruncated)
        {
            exit = CompletableFuture.completedFuture(
                    new ProcessExit(
                            exitCode,
                            reason,
                            ProcessExit.TreeTermination.VERIFIED_STOPPED,
                            Duration.ZERO,
                            stdoutTruncated,
                            stderrTruncated));
        }

        private static ImmediateProcess success()
        {
            return exited(0);
        }

        private static ImmediateProcess exited(int exitCode)
        {
            return new ImmediateProcess(exitCode, ProcessExit.Reason.EXITED, false, false);
        }

        private static ImmediateProcess stdoutTruncated(int exitCode)
        {
            return new ImmediateProcess(exitCode, ProcessExit.Reason.EXITED, true, false);
        }

        private static ImmediateProcess timedOut()
        {
            return new ImmediateProcess(143, ProcessExit.Reason.TIMED_OUT, false, false);
        }

        private static ImmediateProcess hostWaitTimedOut()
        {
            return new ImmediateProcess(new CompletableFuture<>()
            {
                @Override
                public ProcessExit get(long timeout, TimeUnit unit)
                        throws TimeoutException
                {
                    throw new TimeoutException("host wait timed out");
                }
            });
        }

        @Override
        public String launchId()
        {
            return "scripted";
        }

        @Override
        public long pid()
        {
            return 1;
        }

        @Override
        public CompletionStage<Void> write(ByteBuffer bytes)
        {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void closeInput() {}

        @Override
        public CompletionStage<ProcessExit> exit()
        {
            return exit;
        }

        @Override
        public void cancel() {}

        @Override
        public void close() {}
    }
}
