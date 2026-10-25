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
package com.bytequay.app.assistant;

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.ApprovalId;
import com.bytequay.agent.core.TaskResources;
import com.bytequay.agent.spi.AgentCapabilities;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentSession;
import com.bytequay.agent.spi.AgentSessionContext;
import com.bytequay.agent.spi.AgentSessionRef;
import com.bytequay.agent.spi.AgentTurnCommand;
import com.bytequay.agent.spi.AgentTurnResult;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.agent.spi.ReviewTool;
import com.bytequay.agent.spi.RunningTurn;
import com.bytequay.agent.spi.SessionAccess;
import com.bytequay.app.agenda.AgendaFixtures;
import com.bytequay.app.agent.runtime.AgentProperties;
import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.bytequay.app.service.settings.AgentModelSetting;
import com.bytequay.app.service.settings.AgentModelType;
import com.bytequay.app.service.settings.AppSettingsService;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.app.service.settings.SetupPreferences;
import com.bytequay.app.service.settings.SetupSettingsService;
import com.bytequay.app.web.AssistantController;
import com.bytequay.app.web.GlobalExceptionHandler;
import com.bytequay.core.discussion.DiscussionSession;
import com.bytequay.core.domain.AgendaTarget;
import com.bytequay.core.domain.AssistantDay;
import com.bytequay.core.domain.AssistantDraft;
import com.bytequay.core.domain.AssistantItem;
import com.bytequay.core.domain.AssistantList;
import com.bytequay.core.domain.AssistantReceipt;
import com.bytequay.core.store.CredentialStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.bytequay.core.domain.AssistantItem.Kind.FAILURE;
import static com.bytequay.core.domain.AssistantItem.Kind.RECEIPT;
import static com.bytequay.core.domain.AssistantItem.Kind.REPLY;
import static com.bytequay.core.domain.AssistantItem.Kind.USER;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestAssistantConversation
{
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final AgentLaneId LANE = new AgentLaneId("claude");
    private static final LocalDate DAY = LocalDate.parse("2026-10-17");
    private static final AgendaTarget TASK = AgendaTarget.of(AgendaTarget.Kind.TASK, "ws1", "t1");

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @TempDir
    private Path temporaryDirectory;

    private final MemoryAssistantStore store = new MemoryAssistantStore();
    private final AssistantReader reader = mock(AssistantReader.class);
    private final AssistantActions actions = mock(AssistantActions.class);
    private final AssistantShow show = mock(AssistantShow.class);
    private final AssistantRows rows = mock(AssistantRows.class);
    private final AssistantWork work = mock(AssistantWork.class);
    private final ModelSettingsService models = mock(ModelSettingsService.class);
    private final AgentDriverRegistry drivers = mock(AgentDriverRegistry.class);
    private final AgentDriver driver = mock(AgentDriver.class);
    private final AgentSession session = mock(AgentSession.class);
    private final RunningTurn turn = mock(RunningTurn.class);
    private final AgentResourceService resources = mock(AgentResourceService.class);
    // Midday on this machine's clock; each test moves it to the local times it needs.
    private final AtomicReference<Instant> now = new AtomicReference<>(local("2026-10-17T12:00"));
    private final List<AgentSessionContext> opened = new CopyOnWriteArrayList<>();
    private final List<String> prompts = new CopyOnWriteArrayList<>();
    private final Queue<CompletableFuture<AgentTurnResult>> results = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean threadLost = new AtomicBoolean();
    private Path runtime;
    private AssistantService assistant;
    private SetupSettingsService setup;

    @BeforeEach
    void setUp()
    {
        runtime = temporaryDirectory.resolve("runtime");
        when(models.agentModel(AgentModelType.DEVELOPMENT, Optional.empty()))
                .thenReturn(new AgentModelSetting(AgentModelType.DEVELOPMENT, true, "claude", "Claude", "sonnet", "Sonnet", Optional.empty()));
        when(drivers.require(LANE)).thenReturn(driver);
        when(driver.capabilities()).thenReturn(AgentCapabilities.none());
        when(driver.probe()).thenReturn(new AgentProviderStatus(LANE, AgentProviderStatus.Readiness.READY, Optional.empty(), "Ready", AgentCapabilities.none()));
        when(resources.allocate(any())).thenAnswer(call -> {
            Path scratch = runtime.resolve(call.getArgument(0).toString());
            Files.createDirectories(scratch.resolve("provider"));
            return new TaskResources(scratch, scratch.resolve("events.jsonl"), scratch.resolve("blobs"), scratch.resolve("provider"));
        });
        when(reader.running()).thenReturn(new AssistantReader.Page(new AssistantReader.Entries("running", ImmutableList.of()),
                ImmutableMap.of("task:t1", Optional.of(TASK), "ws:ws1", Optional.empty())));
        when(reader.cards(anyList())).thenAnswer(call -> call.<List<String>>getArgument(0).stream().map(ref -> ref + ": Retry Iceberg commit · Allow Claude Bash")
                .collect(toImmutableList()));
        when(actions.tool(any(), any(), any())).thenReturn(new ReviewTool("assistant_act", "Act", ImmutableMap.of("type", "object"), arguments -> "{}", false));
        when(show.tool(any(), any(), any())).thenReturn(new ReviewTool("assistant_show", "Show", ImmutableMap.of("type", "object"), arguments -> "{}", false));
        when(driver.open(any(), any())).thenAnswer(call -> {
            AgentSessionContext context = call.getArgument(0);
            opened.add(context);
            if (threadLost.get() && context.sessionReference().isPresent()) {
                throw new AgentUnavailableException("Codex App Server thread/resume failed: no rollout found for thread");
            }
            context.sessionTools().getFirst().call().apply("{\"kind\":\"running\"}");
            return session;
        });
        when(session.reference()).thenAnswer(call -> new AgentSessionRef(LANE, 1, "session-" + opened.size()));
        when(session.start(any())).thenAnswer(call -> {
            prompts.add(call.<AgentTurnCommand>getArgument(0).message());
            return turn;
        });
        when(turn.completion()).thenAnswer(call -> Optional.ofNullable(results.poll()).orElseGet(() -> CompletableFuture.completedFuture(completed("Answer task:t1 first."))));
        Clock clock = new Clock()
        {
            @Override
            public ZoneId getZone()
            {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone)
            {
                return this;
            }

            @Override
            public Instant instant()
            {
                return now.get();
            }
        };
        CredentialStore credentials = mock(CredentialStore.class);
        when(credentials.exists()).thenReturn(true);
        setup = new SetupSettingsService(new InMemoryAppSettingsStore(), credentials, mock(AppSettingsService.class), models, drivers, AgendaFixtures.mapper());
        assistant = new AssistantService(store, reader, actions, show, rows, work, setup, drivers, resources,
                new AgentProperties(runtime, Duration.ZERO, "", "codex", "0.159.3", temporaryDirectory.resolve("codex-home"), "claude", temporaryDirectory.resolve("claude-home")),
                AgendaFixtures.mapper(), clock, executor);
    }

    @AfterEach
    void stopWorkers()
    {
        executor.shutdownNow();
        executor.close();
    }

    @Test
    void disabledAssistantKeepsHistoryAndReceiptsButAdmitsNoNewMessages()
    {
        store.add(AssistantItem.user(DAY, now.get(), "earlier question", ImmutableList.of(), Optional.empty(), "earlier"));
        store.add(AssistantItem.failure(DAY, now.get(), "The provider signed out", true));
        setup.save(new SetupPreferences(SetupPreferences.Step.COMPLETE, false));

        assertThatThrownBy(() -> assistant.send("what's next?", ImmutableList.of(), Optional.empty(), "new"))
                .isInstanceOf(AgentConflictException.class).hasMessageContaining("Enable Assistant");
        assertThatThrownBy(assistant::retry).isInstanceOf(AgentConflictException.class).hasMessageContaining("Enable Assistant");
        assertThat(assistant.today().items()).hasSize(2);
        assertThat(assistant.today().running()).isFalse();
        assertThat(opened).isEmpty();

        ApprovalId approval = ApprovalId.random();
        when(actions.buttonReceipt(approval, DAY)).thenReturn(AssistantItem.receipt(DAY, now.get(), "Denied",
                AssistantReceipt.done(AssistantReceipt.Operation.ANSWER_APPROVAL, Optional.of(TASK))));
        assistant.answered(approval.toString());
        assertThat(assistant.today().items()).extracting(AssistantItem::kind).containsExactly(USER, FAILURE, RECEIPT);
    }

    @Test
    void keepsOneConversationPerLocalDateWhileTheSharedClockIsUtc()
    {
        // Half past midnight and half past eleven are one local day, which a UTC date splits in any other zone.
        now.set(local("2026-10-17T00:30"));
        String first = assistant.send("what's waiting on me?", ImmutableList.of("approval:a1"), Optional.of("pr:owner/repository#31402"), "key-1");
        waitForReply();
        now.set(local("2026-10-17T23:30"));
        results.add(CompletableFuture.completedFuture(completed("Answer task:t1. task:t12 and ws:ws1 can wait.")));
        assistant.send("and now?", ImmutableList.of(), Optional.empty(), "key-2");
        waitForReply();

        AssistantView view = assistant.today();
        assertThat(view.day()).isEqualTo("2026-10-17");
        assertThat(view.model()).contains("Sonnet");
        assertThat(view.items()).extracting(AssistantItem::kind).containsExactly(USER, REPLY, USER, REPLY);
        assertThat(view.items().getFirst().id()).isEqualTo(first);
        assertThat(view.items().getFirst().visible()).containsExactly("approval:a1");
        // Only a ref the day served, named whole, links; a repository opens no page.
        assertThat(view.items().getLast().targets()).containsExactly(Map.entry("task:t1", TASK));

        AgentSessionContext morning = opened.getFirst();
        assertThat(morning.access()).isEqualTo(SessionAccess.TOOLS_ONLY);
        assertThat(morning.managedInstructions()).isEqualTo(AssistantService.INSTRUCTIONS);
        assertThat(morning.sessionTools()).extracting(tool -> tool.name()).containsExactly("assistant_context", "assistant_act", "assistant_show");
        assertThat(morning.worktree()).isEqualTo(runtime.resolve("assistant").resolve("2026-10-17")).isDirectory();
        assertThat(morning.sessionReference()).isEmpty();
        assertThat(opened.get(1).sessionReference()).contains(new AgentSessionRef(LANE, 1, "session-1"));
        assertThat(prompts.getFirst())
                .contains("On screen: pr:owner/repository#31402", "- approval:a1: Retry Iceberg commit · Allow Claude Bash", "The user's message:\nwhat's waiting on me?")
                .doesNotContain("Earlier today");
        assertThat(prompts.get(1)).contains("Cards on screen: none").doesNotContain("Earlier today");
        assertThat(store.days.get(DAY).served()).containsOnlyKeys("task:t1", "ws:ws1");

        now.set(local("2026-10-18T00:30"));
        assertThat(assistant.today().items()).isEmpty();
        assistant.send("good morning", ImmutableList.of(), Optional.empty(), "key-3");
        waitForReply();
        assertThat(opened.get(2).sessionReference()).isEmpty();
        assertThat(opened.get(2).worktree()).isEqualTo(runtime.resolve("assistant").resolve("2026-10-18"));
        assertThat(store.days).containsOnlyKeys(DAY, DAY.plusDays(1));
    }

    @Test
    void answersOneMessageAtATime()
            throws Exception
    {
        CompletableFuture<AgentTurnResult> slow = new CompletableFuture<>();
        results.add(slow);
        String id = assistant.send("first", ImmutableList.of(), Optional.empty(), "key-1");

        assertThatThrownBy(() -> assistant.send("second", ImmutableList.of(), Optional.empty(), "key-2")).isInstanceOf(AgentConflictException.class);
        assertThatThrownBy(assistant::retry).isInstanceOf(AgentConflictException.class);
        MockMvc http = standaloneSetup(new AssistantController(assistant, mock(AssistantSettings.class))).setControllerAdvice(new GlobalExceptionHandler()).build();
        http.perform(post("/api/assistant/messages").contentType(MediaType.APPLICATION_JSON).content("{\"text\": \"second\", \"key\": \"key-2\"}"))
                .andExpect(status().isConflict());
        // Sent again with its key, the first message is the same message, while its reply runs and after.
        http.perform(post("/api/assistant/messages").contentType(MediaType.APPLICATION_JSON).content("{\"text\": \"first\", \"key\": \"key-1\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.itemId").value(id));
        http.perform(post("/api/assistant/messages").contentType(MediaType.APPLICATION_JSON).content("{\"text\": \" \", \"key\": \"key-3\"}"))
                .andExpect(status().isBadRequest());
        assertThat(assistant.today().running()).isTrue();

        slow.complete(completed("Nothing waits on you."));
        waitForReply();
        assertThat(assistant.send("first", ImmutableList.of(), Optional.empty(), "key-1")).isEqualTo(id);
        assertThat(store.items(DAY)).extracting(AssistantItem::kind).containsExactly(USER, REPLY);
        assertThat(opened).hasSize(1);
    }

    @Test
    void aFreshSessionOpensWithTheDaysLastTwentyItems()
    {
        for (int index = 1; index <= 24; index++) {
            String text = "message %02d ".formatted(index) + (index == 24 ? "long ".repeat(100) : "");
            store.add(index % 2 == 1 ? AssistantItem.user(DAY, now.get(), text, ImmutableList.of(), Optional.empty(), "old-" + index)
                    : AssistantItem.reply(DAY, now.get(), text, ImmutableMap.of()));
        }
        // The day's conversation ran on another agent, whose session this one cannot resume.
        store.save(new AssistantDay(DAY, "codex", "GPT", Optional.of(new DiscussionSession(1, "codex-thread")), ImmutableMap.of(), now.get()));

        assistant.send("what changed?", ImmutableList.of(), Optional.empty(), "key-1");
        waitForReply();

        assertThat(opened.getFirst().sessionReference()).isEmpty();
        String prompt = prompts.getFirst();
        assertThat(prompt).startsWith("Earlier today:\n[user 12:00] message 05\n[you 12:00] message 06\n").contains("[you 12:00] message 24 long", "…")
                .doesNotContain("message 04");
        assertThat(prompt.lines().filter(line -> line.startsWith("[")).count()).isEqualTo(20);
        assertThat(prompt).endsWith("The user's message:\nwhat changed?");
        assertThat(store.days.get(DAY).laneId()).isEqualTo("claude");
        assertThat(store.days.get(DAY).session()).contains(new DiscussionSession(1, "session-1"));
    }

    @Test
    void startsAgainFromTheDaysItemsWhenClaudeNoLongerHasTheConversation()
    {
        store.add(AssistantItem.user(DAY, now.get(), "what's waiting on me?", ImmutableList.of(), Optional.empty(), "key-0"));
        store.add(AssistantItem.reply(DAY, now.get(), "Answer task:t1 first.", ImmutableMap.of("task:t1", TASK)));
        store.save(new AssistantDay(DAY, "claude", "Sonnet", Optional.of(new DiscussionSession(1, "lost")), ImmutableMap.of(), now.get()));
        results.add(CompletableFuture.completedFuture(new AgentTurnResult(AgentTurnResult.State.FAILED, Optional.empty(),
                Optional.of("No conversation found with session ID: lost"), Duration.ofSeconds(1), true)));

        assistant.send("and then?", ImmutableList.of(), Optional.empty(), "key-1");
        waitForReply();

        assertThat(opened).extracting(AgentSessionContext::sessionReference).containsExactly(Optional.of(new AgentSessionRef(LANE, 1, "lost")), Optional.empty());
        assertThat(prompts.getFirst()).doesNotContain("Earlier today");
        assertThat(prompts.get(1)).startsWith("Earlier today:\n[user 12:00] what's waiting on me?\n[you 12:00] Answer task:t1 first.\n");
        assertThat(store.items(DAY)).extracting(AssistantItem::kind).containsExactly(USER, REPLY, USER, REPLY);
        assertThat(store.days.get(DAY).session()).contains(new DiscussionSession(1, "session-2"));
    }

    @Test
    void startsAgainAtOnceWhenCodexRefusesTheThread()
    {
        store.save(new AssistantDay(DAY, "claude", "Sonnet", Optional.of(new DiscussionSession(1, "lost")), ImmutableMap.of(), now.get()));
        threadLost.set(true);

        assistant.send("what's waiting on me?", ImmutableList.of(), Optional.empty(), "key-1");
        waitForReply();

        assertThat(opened).extracting(AgentSessionContext::sessionReference).containsExactly(Optional.of(new AgentSessionRef(LANE, 1, "lost")), Optional.empty());
        assertThat(prompts).hasSize(1);
        assertThat(store.items(DAY)).extracting(AssistantItem::kind).containsExactly(USER, REPLY);
    }

    @Test
    void aResumedTurnThatFailsOnItsOwnKeepsTheConversation()
    {
        store.save(new AssistantDay(DAY, "claude", "Sonnet", Optional.of(new DiscussionSession(1, "kept")), ImmutableMap.of(), now.get()));
        results.add(CompletableFuture.failedFuture(new IllegalStateException("stream closed")));

        assistant.send("what's waiting on me?", ImmutableList.of(), Optional.empty(), "key-1");
        waitForReply();

        assertThat(opened).hasSize(1);
        assertThat(store.items(DAY).getLast().kind()).isEqualTo(FAILURE);
        assertThat(store.items(DAY).getLast().text()).isEqualTo("The model session failed");
        assertThat(store.days.get(DAY).session()).contains(new DiscussionSession(1, "kept"));
    }

    @Test
    void keepsWhyARunFailedAndRetriesTheSameMessage()
    {
        when(driver.probe()).thenReturn(new AgentProviderStatus(LANE, AgentProviderStatus.Readiness.AUTHENTICATION_REQUIRED, Optional.empty(),
                "Claude is signed out. " + "Sign in again from Settings. ".repeat(10), AgentCapabilities.none()));
        String message = assistant.send("what's waiting on me?", ImmutableList.of(), Optional.empty(), "key-1");
        waitForReply();

        AssistantItem notReady = store.items(DAY).getLast();
        assertThat(notReady.kind()).isEqualTo(FAILURE);
        assertThat(notReady.notReady()).isTrue();
        assertThat(notReady.text()).startsWith("Claude is signed out.").endsWith("…").hasSizeLessThanOrEqualTo(200);
        assertThat(opened).isEmpty();

        when(driver.probe()).thenReturn(new AgentProviderStatus(LANE, AgentProviderStatus.Readiness.READY, Optional.empty(), "Ready", AgentCapabilities.none()));
        results.add(CompletableFuture.completedFuture(new AgentTurnResult(AgentTurnResult.State.INTERRUPTED, Optional.empty(), Optional.of("Claude turn timed out"),
                Duration.ofMinutes(3), true)));
        assertThat(assistant.retry()).isEqualTo(message);
        waitForReply();
        AssistantItem timedOut = store.items(DAY).getLast();
        assertThat(timedOut.kind()).isEqualTo(FAILURE);
        assertThat(timedOut.notReady()).isFalse();
        assertThat(timedOut.text()).isEqualTo("Claude turn timed out");

        assertThat(assistant.retry()).isEqualTo(message);
        waitForReply();
        assertThat(store.items(DAY)).extracting(AssistantItem::kind).containsExactly(USER, FAILURE, FAILURE, REPLY);
        assertThat(prompts).hasSize(2).allSatisfy(prompt -> assertThat(prompt).endsWith("what's waiting on me?"));
        assertThatThrownBy(assistant::retry).isInstanceOf(AgentConflictException.class).hasMessage("There is no failed reply to retry");
    }

    @Test
    void keepsReceiptsBetweenAMessageAndItsReplyAndRetriesPastAButtonsReceipt()
    {
        AssistantReceipt allowed = AssistantReceipt.done(AssistantReceipt.Operation.ANSWER_APPROVAL, Optional.of(TASK));
        doAnswer(call -> {
            AssistantItem message = call.getArgument(0);
            Consumer<AssistantItem> receipts = call.getArgument(2);
            return new ReviewTool("assistant_act", "Act", ImmutableMap.of("type", "object"), arguments -> {
                receipts.accept(AssistantItem.receipt(message.day(), now.get(), "Allowed once · Retry Iceberg commit", allowed));
                return "{\"outcome\":\"done\"}";
            }, false);
        }).when(actions).tool(any(), any(), any());
        doAnswer(call -> {
            AgentSessionContext context = call.getArgument(0);
            opened.add(context);
            context.sessionTools().stream().filter(tool -> tool.name().equals("assistant_act")).findFirst().orElseThrow().call().apply("{}");
            return session;
        }).when(driver).open(any(), any());
        results.add(CompletableFuture.completedFuture(new AgentTurnResult(AgentTurnResult.State.FAILED, Optional.empty(), Optional.of("Claude turn failed"),
                Duration.ofSeconds(1), true)));
        String message = assistant.send("allow it", ImmutableList.of("approval:a1"), Optional.empty(), "key-1");
        waitForReply();
        ApprovalId clicked = ApprovalId.random();
        when(actions.buttonReceipt(clicked, DAY)).thenReturn(AssistantItem.receipt(DAY, now.get(), "Denied · Metadata cache", allowed));
        assistant.answered(clicked.toString());

        assertThat(assistant.retry()).isEqualTo(message);
        waitForReply();
        assertThat(store.items(DAY)).extracting(AssistantItem::kind).containsExactly(USER, RECEIPT, FAILURE, RECEIPT, RECEIPT, REPLY);
        verify(actions, atLeastOnce()).tool(eq(store.items(DAY).getFirst()), any(), any());
    }

    @Test
    void readsEachListsRowsWhenTheDayIsRead()
    {
        assertThat(assistant.today().rows()).isEmpty();
        verify(rows, never()).rows(any());

        AgendaTarget pull = AgendaTarget.pull("owner/repository", 31402, Optional.of("ws1"));
        AssistantRow row = new AssistantRow("pr:owner/repository#31402", AssistantRow.Kind.PULL, "Retry Iceberg commit on conflict", Optional.of("#31402"), Optional.of("open"),
                Optional.of("failing"), Optional.empty(), Optional.of(0), false, false, false, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), pull);
        AssistantList shown = new AssistantList(AssistantList.Style.CARDS, ImmutableList.of("pr:owner/repository#31402", "task:gone"));
        store.add(AssistantItem.user(DAY, now.get(), "what is red?", ImmutableList.of(), Optional.empty(), "key-1"));
        store.add(AssistantItem.list(DAY, now.get(), "Red pull requests", shown));
        when(rows.rows(shown.refs())).thenReturn(ImmutableMap.of("pr:owner/repository#31402", row));

        AssistantView view = assistant.today();
        assertThat(view.items().getLast().list()).contains(shown);
        // The day keeps refs only; each read draws them from the stores as they are now, and a ref that is gone has no row.
        assertThat(view.rows()).containsExactly(Map.entry("pr:owner/repository#31402", row));

        // A session that starts over reads what the day's lists named.
        assistant.send("and now?", ImmutableList.of(), Optional.empty(), "key-2");
        waitForReply();
        assertThat(prompts.getFirst()).contains("[you listed 12:00] Red pull requests: pr:owner/repository#31402, task:gone");
    }

    @Test
    void keepsOneReceiptForADraftTheUserCreated()
            throws Exception
    {
        AssistantDraft draft = new AssistantDraft("ws1", "owner/repository", "Track the snapshot work.", "w9");
        AssistantItem drafted = AssistantItem.draft(DAY, now.get(), "Iceberg snapshot metadata", draft);
        store.add(AssistantItem.user(DAY, now.get(), "a new issue for the snapshot work, as an epic", ImmutableList.of(), Optional.empty(), "key-1"));
        store.add(drafted);
        when(work.drafted(eq(draft), eq(DAY), any())).thenReturn(AssistantItem.receipt(DAY, now.get(), "Created “Iceberg snapshot ids” as an Epic",
                new AssistantReceipt(AssistantReceipt.Operation.CREATE_EPIC, AssistantReceipt.Outcome.DONE, Optional.of("owner/repository#123"),
                        Optional.of(AgendaTarget.of(AgendaTarget.Kind.WORK, "ws1", "w9")), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())));

        MockMvc http = standaloneSetup(new AssistantController(assistant, mock(AssistantSettings.class))).setControllerAdvice(new GlobalExceptionHandler()).build();
        http.perform(post("/api/assistant/drafts").contentType(MediaType.APPLICATION_JSON).content("{\"itemId\": \"" + drafted.id() + "\"}"))
                .andExpect(status().isNoContent());
        // A click recorded twice, such as after a lost reply, still leaves one receipt.
        assistant.drafted(drafted.id());
        assertThat(store.items(DAY)).extracting(AssistantItem::kind).containsExactly(USER, AssistantItem.Kind.DRAFT, RECEIPT);
        assertThatThrownBy(() -> assistant.drafted(store.items(DAY).getFirst().id())).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("That is not one of today's drafts");
    }

    @Test
    void stopCancelsTheRunningTurn()
    {
        CompletableFuture<AgentTurnResult> slow = new CompletableFuture<>();
        results.add(slow);
        doAnswer(call -> slow.complete(new AgentTurnResult(AgentTurnResult.State.CANCELLED, Optional.empty(), Optional.of("Claude turn was cancelled"),
                Duration.ofSeconds(2), true))).when(turn).cancel();
        assistant.send("what's waiting on me?", ImmutableList.of(), Optional.empty(), "key-1");
        await().alias("assistant turn start").pollInSameThread().pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(10)).atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(prompts).isNotEmpty());

        assistant.stop();
        waitForReply();

        verify(turn, atLeastOnce()).cancel();
        assertThat(store.items(DAY).getLast().text()).isEqualTo("Claude turn was cancelled");
    }

    @Test
    void aNewDayDropsDaysAndFoldersPastThirtyDays()
            throws Exception
    {
        for (LocalDate day : ImmutableList.of(DAY.minusDays(31), DAY.minusDays(30))) {
            store.save(new AssistantDay(day, "claude", "Sonnet", Optional.empty(), ImmutableMap.of(), now.get()));
            store.add(AssistantItem.user(day, now.get(), "hello", ImmutableList.of(), Optional.empty(), "key-" + day));
            Files.createDirectories(runtime.resolve("assistant").resolve(day.toString()));
            Files.writeString(runtime.resolve("assistant").resolve(day.toString()).resolve("left.txt"), "x");
        }

        assistant.send("what's waiting on me?", ImmutableList.of(), Optional.empty(), "key-1");
        waitForReply();

        assertThat(store.days).containsOnlyKeys(DAY.minusDays(30), DAY);
        assertThat(store.items(DAY.minusDays(31))).isEmpty();
        assertThat(store.items(DAY.minusDays(30))).hasSize(1);
        assertThat(runtime.resolve("assistant").resolve(DAY.minusDays(31).toString())).doesNotExist();
        assertThat(runtime.resolve("assistant").resolve(DAY.minusDays(30).toString())).isDirectory();
        assertThat(runtime.resolve("assistant").resolve(DAY.toString())).isDirectory();
    }

    private void waitForReply()
    {
        await().alias("assistant reply").pollInSameThread().pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(10)).atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(assistant.today().running()).isFalse());
    }

    private static AgentTurnResult completed(String reply)
    {
        return new AgentTurnResult(AgentTurnResult.State.COMPLETED, Optional.of(reply), Optional.empty(), Duration.ofSeconds(5), true);
    }

    private static Instant local(String dateTime)
    {
        return LocalDateTime.parse(dateTime).atZone(ZONE).toInstant();
    }
}
