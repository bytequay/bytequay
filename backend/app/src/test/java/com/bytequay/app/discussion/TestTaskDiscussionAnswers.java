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
package com.bytequay.app.discussion;

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventSource;
import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskResources;
import com.bytequay.agent.core.TaskSummary;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.store.TaskLogStore;
import com.bytequay.agent.spi.AgentCapabilities;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentEventSink;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentSession;
import com.bytequay.agent.spi.AgentSessionContext;
import com.bytequay.agent.spi.AgentSessionRef;
import com.bytequay.agent.spi.AgentTurnCommand;
import com.bytequay.agent.spi.AgentTurnResult;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.agent.spi.DiscussionContextBridge;
import com.bytequay.agent.spi.ProviderAgentEvent.MessageKind;
import com.bytequay.agent.spi.ProviderAgentEvent.MessagePhase;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.agent.spi.RunningTurn;
import com.bytequay.agent.spi.SessionAccess;
import com.bytequay.app.agent.TaskPullRequestOwnership;
import com.bytequay.app.service.ai.BetterWordsService;
import com.bytequay.app.service.ai.BetterWordsService.ReplyVoice;
import com.bytequay.core.discussion.DiscussionAnchor;
import com.bytequay.core.discussion.DiscussionMessage;
import com.bytequay.core.discussion.DiscussionQuotes;
import com.bytequay.core.discussion.DiscussionSession;
import com.bytequay.core.discussion.ReviewCommentAnchor;
import com.bytequay.core.discussion.TaskDiscussion;
import com.bytequay.core.discussion.TaskDiscussionStore;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PrTimelineComment;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PrTimelinePayload;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.review.ReviewNotFoundException;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.PullRequestStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The answer path, driven by a scripted provider: what each question sends, what a lost session
 * costs, and what a failure leaves behind for the reader.
 */
class TestTaskDiscussionAnswers
{
    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");
    private static final TaskId TASK = TaskId.random();
    private static final String SEED = "--- conversation so far ---\n\n[agent]\nIt throws INVALID_TABLE_PROPERTY.";
    private static final String REPO = "owner/repo";
    private static final String KEY = "review_comment:123";
    private static final TaskId REVIEW_TASK = TaskId.random();
    private static final String REVIEW = "review-1";
    private static final String VERDICT = """
            {"items":[{"ref":"c1","ask":"Rename the flag.","valid":"yes","why":"The name hides its effect.","do":"yes","plan":"Rename it in Config.","size":"small"}]}""";
    private static final String NORMALIZED = """
            {"ask":"Rename the flag.","valid":"yes","why":"The name hides its effect.","do":"yes","plan":"Rename it in Config.","size":"small"}""";

    private final Map<String, TaskDiscussion> rows = new LinkedHashMap<>();
    private final List<AgentEventEnvelope> events = new ArrayList<>();
    private final List<String> prompts = new ArrayList<>();
    private final List<Optional<AgentSessionRef>> opened = new ArrayList<>();
    private final List<Runnable> held = new ArrayList<>();
    private final List<Boolean> pendingWhenStored = new ArrayList<>();
    private final Map<String, TaskDiscussion> racing = new LinkedHashMap<>();
    private final Set<String> conflicting = new HashSet<>();
    private final ScriptedDriver driver = new ScriptedDriver();
    private final AgentTaskService tasks = mock(AgentTaskService.class);
    private final TaskPullRequestOwnership ownership = mock(TaskPullRequestOwnership.class);
    private final BetterWordsService writer = mock(BetterWordsService.class);
    private final AgentReviewStore reviews = mock(AgentReviewStore.class);
    private boolean hold;
    private boolean answerDuringList;
    private final TaskDiscussionService service = service();

    private String savedContext(String section)
    {
        try {
            return new ObjectMapper().readTree(driver.lastContext.discussionContext().orElseThrow().read()
                    .apply(new DiscussionContextBridge.Query(section, 0))).path("text").asText();
        }
        catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    void sendsThePassageFirstAndOnlyTheQuestionOnResumeWithSavedContextAvailable()
    {
        store(discussion(Optional.empty(), List.of()));
        service.ask(new AskDiscussionCommand("w1", TASK.toString(), "d1", "why per thread?"));

        assertThat(opened).containsExactly(Optional.empty());
        assertThat(prompts).singleElement().satisfies(prompt -> {
            assertThat(prompt).contains("read_discussion_context", "INVALID_TABLE_PROPERTY", "why per thread?").doesNotContain(SEED);
            assertThat(prompt).contains("--- question ---");
        });
        assertThat(savedContext("history")).isEqualTo(SEED);
        // The answer landed with the session it ran in, so the next question can resume it.
        assertThat(rows.get("d1").sessionReference()).contains(new DiscussionSession(1, "session-1"));

        service.ask(new AskDiscussionCommand("w1", TASK.toString(), "d1", "and on four cores?"));

        assertThat(opened).last().isEqualTo(Optional.of(new AgentSessionRef(AgentLaneId.CLAUDE_CLI, 1, "session-1")));
        // A resumed conversation already holds the premise, so re-sending it would duplicate.
        assertThat(prompts.getLast()).doesNotContain(SEED);
        assertThat(prompts.getLast()).isEqualTo("and on four cores?");
        assertThat(savedContext("history")).isEqualTo(SEED);
        assertThat(savedContext("discussion")).contains("why per thread?", "Answered.").doesNotContain("and on four cores?");
    }

    @Test
    void restoresEveryPriorMessageThroughTheToolWhenTheRecordedSessionIsGone()
    {
        store(discussion(Optional.of(new DiscussionSession(1, "lost-session")), List.of(
                new DiscussionMessage("m1", DiscussionMessage.Author.USER, "why per thread?", NOW, Optional.empty()),
                new DiscussionMessage("m2", DiscussionMessage.Author.AGENT, "The pool is sized from CPU count.", NOW, Optional.empty()))));
        driver.failResume = true;

        service.ask(new AskDiscussionCommand("w1", TASK.toString(), "d1", "and on four cores?"));

        // A failed resume starts a new session; retained context remains accessible even without runtime events.
        assertThat(prompts).hasSize(1);
        assertThat(prompts.getLast()).contains("Read the discussion section", "and on four cores?")
                .doesNotContain(SEED, "why per thread?", "The pool is sized from CPU count.");
        assertThat(events).isEmpty();
        assertThat(savedContext("history")).isEqualTo(SEED);
        assertThat(savedContext("discussion")).contains("why per thread?", "The pool is sized from CPU count.");
        assertThat(rows.get("d1").messages()).hasSize(4);
        assertThat(rows.get("d1").messages().getLast().body()).isEqualTo("Answered.");
    }

    @Test
    void shutdownInterruptsTheAnswerAndDoesNotRestartItsSession()
            throws InterruptedException
    {
        store(discussion(Optional.of(new DiscussionSession(1, "session-1")), ImmutableList.of()));
        driver.waitForAnswer = true;
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        TaskDiscussionService background = service(executor);
        try {
            background.ask(new AskDiscussionCommand("w1", TASK.toString(), "d1", "why per thread?"));
            assertThat(driver.answerStarted.await(5, TimeUnit.SECONDS)).isTrue();

            background.close();

            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            assertThat(opened).hasSize(1);
            assertThat(driver.closed).isEqualTo(1);
            assertThat(rows.get("d1").messages().getLast().error()).isPresent();
            assertThat(background.list("w1", TASK.toString()).getFirst().pending()).isFalse();
        }
        finally {
            background.close();
            executor.shutdownNow();
        }
    }

    @Test
    void marksTheQuestionThatFailedAndForgetsTheSessionThatMayBeWhy()
    {
        store(discussion(Optional.of(new DiscussionSession(1, "session-1")), List.of()));
        driver.result = new AgentTurnResult(AgentTurnResult.State.FAILED, Optional.empty(), Optional.of("The provider stopped"), Duration.ZERO, true);

        service.ask(new AskDiscussionCommand("w1", TASK.toString(), "d1", "why per thread?"));

        TaskDiscussion after = rows.get("d1");
        assertThat(after.messages()).singleElement().satisfies(message -> {
            assertThat(message.author()).isEqualTo(DiscussionMessage.Author.USER);
            assertThat(message.error()).contains("The provider stopped");
        });
        // A session that may itself be the failure is not carried into the retry.
        assertThat(after.sessionReference()).isEmpty();
    }

    @Test
    void retryRerunsTheRecordedQuestionRatherThanAppendingAnother()
    {
        store(discussion(Optional.empty(), List.of(new DiscussionMessage("m1", DiscussionMessage.Author.USER, "why per thread?", NOW, Optional.of("The provider stopped")))));

        service.retry("w1", TASK.toString(), "d1", "m1");

        TaskDiscussion after = rows.get("d1");
        assertThat(after.messages()).hasSize(2);
        assertThat(after.messages().getFirst().id()).isEqualTo("m1");
        assertThat(after.messages().getFirst().error()).isEmpty();
        assertThat(after.messages().getLast().author()).isEqualTo(DiscussionMessage.Author.AGENT);
        assertThat(prompts.getLast()).contains("why per thread?");
    }

    @Test
    void opensEveryAnswerReadOnlyAtTheTaskWorktreeUnderTheTaskIdentity()
    {
        store(discussion(Optional.empty(), List.of()));
        service.ask(new AskDiscussionCommand("w1", TASK.toString(), "d1", "why per thread?"));

        AgentSessionContext context = driver.lastContext;
        assertThat(context.access()).isEqualTo(SessionAccess.READ_ONLY);
        assertThat(context.taskId()).isEqualTo(TASK.toString());
        assertThat(context.worktree()).isEqualTo(worktree().canonicalWorktree());
        // Scratch is its own, so the task's provider configuration is never clobbered.
        assertThat(context.runtimeDirectory()).isNotEqualTo(worktree().canonicalWorktree());
    }

    @Test
    void refusesASecondQuestionOnTheTaskWhileOneIsAnsweringAndStoresNothingForIt()
    {
        store(discussion(Optional.empty(), List.of()));
        answered(TurnId.random(), "It throws INVALID_TABLE_PROPERTY.");
        hold = true;

        assertThat(service.ask(new AskDiscussionCommand("w1", TASK.toString(), "d1", "why per thread?")).pending()).isTrue();

        assertThatThrownBy(() -> service.open(new OpenDiscussionCommand("w1", TASK.toString(), TurnId.random().toString(), "INVALID_TABLE_PROPERTY", 1, "why?")))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("already answering");
        assertThatThrownBy(() -> service.askComment(askComment(KEY, "")))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("already answering");
        assertThat(rows).containsOnlyKeys("d1");
        assertThat(service.list("w1", TASK.toString())).singleElement().satisfies(view -> assertThat(view.pending()).isTrue());

        runHeld();

        assertThat(service.list("w1", TASK.toString())).singleElement().satisfies(view -> assertThat(view.pending()).isFalse());
        assertThat(service.ask(new AskDiscussionCommand("w1", TASK.toString(), "d1", "and on four cores?")).pending()).isTrue();
    }

    @Test
    void retryAnswersAQuestionARestartOrphaned()
    {
        store(discussion(Optional.empty(), List.of(new DiscussionMessage("m1", DiscussionMessage.Author.USER, "why per thread?", NOW, Optional.empty()))));

        service.retry("w1", TASK.toString(), "d1", "m1");

        assertThat(rows.get("d1").messages()).extracting(DiscussionMessage::author)
                .containsExactly(DiscussionMessage.Author.USER, DiscussionMessage.Author.AGENT);
        assertThat(rows.get("d1").messages().getFirst().id()).isEqualTo("m1");
    }

    @Test
    void anAnswerStoredWhileTheListIsReadLeavesItsQuestionPendingRatherThanFailed()
    {
        store(discussion(Optional.empty(), ImmutableList.of()));
        hold = true;
        service.ask(new AskDiscussionCommand("w1", TASK.toString(), "d1", "why per thread?"));
        answerDuringList = true;

        // The rows were read with the question trailing, and the answer landed before the slot was consulted.
        assertThat(service.list("w1", TASK.toString())).singleElement().satisfies(view -> {
            assertThat(view.messages()).hasSize(1);
            assertThat(view.pending()).isTrue();
        });
        assertThat(rows.get("d1").messages()).hasSize(2);
        assertThat(service.list("w1", TASK.toString())).singleElement().satisfies(view -> assertThat(view.pending()).isFalse());
    }

    @Test
    void aBlankAskStoresTheHiddenRequestThenItsSummaryAndNoSeed()
    {
        answered(TurnId.random(), "I renamed nothing yet.");
        driver.answers.add(VERDICT);

        TaskDiscussionView view = service.askComment(askComment(KEY, ""));

        TaskDiscussion thread = rows.get(view.id());
        assertThat(thread.seed()).isEmpty();
        assertThat(thread.comment()).hasValueSatisfying(comment -> {
            assertThat(comment.key()).isEqualTo(KEY);
            assertThat(comment.snapshot()).contains("\"author\":\"bob\"", "Please rename this flag.");
        });
        assertThat(thread.anchor()).isEqualTo(thread.comment().orElseThrow().anchor());
        assertThat(thread.messages()).extracting(DiscussionMessage::body).containsExactly(DiscussionPrompt.SUMMARY_REQUEST, NORMALIZED);
        assertThat(thread.sessionReference()).isPresent();
        assertThat(view.comment()).contains(new TaskDiscussionView.Comment(REPO, 12, KEY));
        assertThat(prompts).singleElement().satisfies(prompt -> assertThat(prompt).startsWith(DiscussionPrompt.COMMENT_PREAMBLE)
                .contains("read_discussion_context", DiscussionPrompt.SUMMARY_REQUEST).doesNotContain("I renamed nothing yet.", "Please rename this flag."));
        assertThat(savedContext("history")).contains("I renamed nothing yet.");
        assertThat(savedContext("source")).contains("\"ref\":\"c1\"", "Please rename this flag.");

        // Asking again with no text returns the same thread unchanged.
        assertThat(service.askComment(askComment(KEY, "")).messages()).hasSize(2);
        assertThat(prompts).hasSize(1);
    }

    @Test
    void aBlankAskBeforeTheFirstAnswerIsRefusedAndStoresNothing()
    {
        assertThatThrownBy(() -> service.askComment(askComment(KEY, "")))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessage("Waiting for the agent's first answer");
        assertThat(rows).isEmpty();

        // The refusal gave the task's slot back.
        answered(TurnId.random(), "Done.");
        driver.answers.add(VERDICT);
        assertThat(service.askComment(askComment(KEY, "")).messages()).hasSize(1);
    }

    @Test
    void anInvalidSummaryFailsTheHiddenRequestAndRetryAsksForOneSummaryAgain()
    {
        answered(TurnId.random(), "Done.");
        driver.answers.add("Looks fine to me.");

        String id = service.askComment(askComment(KEY, "")).id();

        DiscussionMessage request = rows.get(id).messages().getLast();
        assertThat(request.body()).isEqualTo(DiscussionPrompt.SUMMARY_REQUEST);
        assertThat(request.error()).hasValueSatisfying(error -> assertThat(error).startsWith("The agent's summary was not in the expected form"));
        assertThat(rows.get(id).sessionReference()).isEmpty();

        driver.answers.add(VERDICT);
        service.retry("w1", TASK.toString(), id, request.id());

        assertThat(rows.get(id).messages()).extracting(DiscussionMessage::body).containsExactly(DiscussionPrompt.SUMMARY_REQUEST, NORMALIZED);
        assertThat(prompts.getLast()).contains("Comment references: c1.", "--- question ---\n\n" + DiscussionPrompt.SUMMARY_REQUEST);
        assertThat(prompts.getLast()).doesNotContain("--- discussion so far ---");
    }

    @Test
    void aFollowUpWithoutASessionExposesCurrentHistoryAndSavedDiscussionThroughTheTool()
    {
        answered(TurnId.random(), "First answer.");
        ReviewCommentAnchor comment = anchor(KEY, "{\"kind\":\"review_comment\",\"body\":\"Rename it.\"}");
        store(commentThread("t1", comment, ImmutableList.of(
                new DiscussionMessage("m1", DiscussionMessage.Author.USER, DiscussionPrompt.SUMMARY_REQUEST, NOW, Optional.empty()),
                new DiscussionMessage("m2", DiscussionMessage.Author.AGENT, NORMALIZED, NOW, Optional.empty()))));
        answered(TurnId.random(), "Second answer.");

        service.ask(new AskDiscussionCommand("w1", TASK.toString(), "t1", "Is the old name used anywhere else?"));

        assertThat(opened).containsExactly(Optional.empty());
        assertThat(prompts.getLast()).contains("Read the discussion section", "Is the old name used anywhere else?")
                .doesNotContain("First answer.", "Second answer.", "Rename it.", NORMALIZED);
        assertThat(savedContext("history")).containsSubsequence("First answer.", "Second answer.");
        assertThat(savedContext("source")).contains("Rename it.", "\"ref\":\"c1\"");
        assertThat(savedContext("discussion")).contains("Summarize this review comment.", "Rename the flag.");
        // That answer's session belongs to this thread alone, so it is kept.
        assertThat(rows.get("t1").sessionReference()).isPresent();
    }

    @Test
    void refusesAPullRequestTheTaskDoesNotOwnOrAKeyOutsideItsCachedDetail()
    {
        answered(TurnId.random(), "Done.");

        assertThatThrownBy(() -> service.askComment(new AskCommentCommand("w1", TASK.toString(), REPO, 13, KEY, "")))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("not this task's");
        assertThatThrownBy(() -> service.askComment(askComment("review_comment:999", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not in this pull request");
        assertThat(rows).isEmpty();

        driver.answers.add(VERDICT);
        assertThat(service.askComment(askComment(KEY, "")).messages()).hasSize(1);
    }

    @Test
    void passageDiscussionsAndCommentThreadsHaveSeparateCaps()
    {
        TurnId turn = TurnId.random();
        answered(turn, "It throws INVALID_TABLE_PROPERTY.");
        IntStream.range(0, TaskDiscussionService.MAXIMUM_DISCUSSIONS_PER_TASK).forEach(index -> store(new TaskDiscussion("p" + index, TASK.toString(),
                new DiscussionAnchor("turn-" + index, "quote", 1, DiscussionQuotes.hash("quote", index + 1)), Optional.empty(), Optional.of(SEED),
                "claude", Optional.empty(), Optional.empty(), ImmutableList.of(), false, NOW, NOW, 0, Optional.empty(), Optional.empty())));
        driver.answers.add(VERDICT);

        assertThat(service.askComment(askComment(KEY, "")).comment()).isPresent();
        assertThatThrownBy(() -> service.open(new OpenDiscussionCommand("w1", TASK.toString(), turn.toString(), "INVALID_TABLE_PROPERTY", 1, "why?")))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("most discussions");

        rows.clear();
        IntStream.range(0, TaskDiscussionService.MAXIMUM_COMMENT_THREADS_PER_TASK)
                .forEach(index -> store(commentThread("c" + index, anchor("review:" + index, "{}"), ImmutableList.of())));

        assertThatThrownBy(() -> service.askComment(askComment(KEY, "")))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("most private threads");
        assertThat(service.open(new OpenDiscussionCommand("w1", TASK.toString(), turn.toString(), "INVALID_TABLE_PROPERTY", 1, "why?")).comment()).isEmpty();
    }

    @Test
    void automaticallyPreparesAPrivateDraftOnceWithoutTheSummaryOnlyOption()
    {
        AgentTask task = task();
        when(tasks.getInteractive("w1", TASK)).thenReturn(task.withDefaults(task.defaults()
                .withDevelopmentAutomation(new DevelopmentAutomation(true, false, false, Optional.empty(), false, true)), NOW));
        answered(TurnId.random(), "Done.");
        driver.answers.add(VERDICT);
        when(writer.draftReply(anyString(), eq(REPO), eq(Optional.of("w1")), eq(ReplyVoice.AUTHOR))).thenReturn("Thanks, I will rename it.");
        List<ReviewCommentAnchor> comments = ImmutableList.of(anchor(KEY, "{\"body\":\"Rename it.\"}"));

        service.summarize("w1", TASK.toString(), comments);

        TaskDiscussion stored = rows.get(idOf(KEY));
        assertThat(stored.replyDraft()).contains("Thanks, I will rename it.");
        assertThat(stored.replyDraftError()).isEmpty();
        assertThat(service.list("w1", TASK.toString())).singleElement().satisfies(view -> {
            assertThat(view.replyDraft()).isEqualTo(stored.replyDraft());
            assertThat(view.pending()).isFalse();
        });
        service.summarize("w1", TASK.toString(), comments);
        verify(writer).draftReply(anyString(), eq(REPO), eq(Optional.of("w1")), eq(ReplyVoice.AUTHOR));
        assertThat(prompts).hasSize(1);
    }

    @Test
    void refusesManualDraftingWhileTheAutomaticDraftHoldsTheDiscussionSlot()
    {
        AgentTask task = task();
        when(tasks.getInteractive("w1", TASK)).thenReturn(task.withDefaults(task.defaults()
                .withDevelopmentAutomation(new DevelopmentAutomation(true, false, false, Optional.empty(), false, true)), NOW));
        answered(TurnId.random(), "Done.");
        driver.answers.add(VERDICT);
        when(writer.draftReply(anyString(), eq(REPO), eq(Optional.of("w1")), eq(ReplyVoice.AUTHOR))).thenAnswer(call -> {
            assertThat(service.list("w1", TASK.toString())).singleElement().satisfies(view -> assertThat(view.pending()).isTrue());
            assertThatThrownBy(() -> service.draftReply("w1", TASK.toString(), idOf(KEY)))
                    .isInstanceOf(AgentConflictException.class).hasMessage("This task is already answering a question");
            return "Thanks, I will rename it.";
        });

        service.summarize("w1", TASK.toString(), ImmutableList.of(anchor(KEY, "{\"body\":\"Rename it.\"}")));

        verify(writer).draftReply(anyString(), eq(REPO), eq(Optional.of("w1")), eq(ReplyVoice.AUTHOR));
        assertThat(rows.get(idOf(KEY)).replyDraft()).contains("Thanks, I will rename it.");
        assertThat(service.isAnswering(TASK)).isFalse();
    }

    @Test
    void retainsTheAnalysisAndShowsADraftFailureForManualRetry()
    {
        AgentTask task = task();
        when(tasks.getInteractive("w1", TASK)).thenReturn(task.withDefaults(task.defaults()
                .withDevelopmentAutomation(new DevelopmentAutomation(true, false, false, Optional.empty(), false, true)), NOW));
        answered(TurnId.random(), "Done.");
        driver.answers.add(VERDICT);
        when(writer.draftReply(anyString(), eq(REPO), eq(Optional.of("w1")), eq(ReplyVoice.AUTHOR)))
                .thenThrow(new AgentUnavailableException("Account unavailable"));

        service.summarize("w1", TASK.toString(), ImmutableList.of(anchor(KEY, "{\"body\":\"Rename it.\"}")));

        TaskDiscussion stored = rows.get(idOf(KEY));
        assertThat(stored.messages()).extracting(DiscussionMessage::body).containsExactly(DiscussionPrompt.SUMMARY_REQUEST, NORMALIZED);
        assertThat(stored.replyDraft()).isEmpty();
        assertThat(stored.replyDraftError()).contains("Account unavailable");
        assertThat(service.list("w1", TASK.toString())).noneMatch(TaskDiscussionView::pending);

        when(writer.draftReply(anyString(), eq(REPO), eq(Optional.of("w1")), eq(ReplyVoice.AUTHOR))).thenReturn("I will rename it.");
        assertThat(service.draftReply("w1", TASK.toString(), stored.id())).isEqualTo("I will rename it.");
        assertThat(rows.get(stored.id()).replyDraftError()).isEmpty();
    }

    @Test
    void draftsAReplyFromTheVisibleThreadAndStoresNothing()
            throws Exception
    {
        ReviewCommentAnchor comment = anchor(KEY, "{\"kind\":\"review_comment\",\"author\":\"bob\",\"body\":\"Rename it.\"}");
        store(commentThread("t1", comment, ImmutableList.of(
                new DiscussionMessage("m1", DiscussionMessage.Author.USER, DiscussionPrompt.SUMMARY_REQUEST, NOW, Optional.empty()),
                new DiscussionMessage("m2", DiscussionMessage.Author.AGENT, NORMALIZED, NOW, Optional.empty()),
                new DiscussionMessage("m3", DiscussionMessage.Author.USER, "Is it used elsewhere?", NOW, Optional.empty()),
                new DiscussionMessage("m4", DiscussionMessage.Author.AGENT, "Only in Config.", NOW, Optional.empty()))));
        TaskDiscussion before = rows.get("t1");
        when(writer.draftReply(anyString(), eq(REPO), eq(Optional.of("w1")), eq(ReplyVoice.AUTHOR))).thenReturn("Good catch, I will rename it.");

        assertThat(service.draftReply("w1", TASK.toString(), "t1")).isEqualTo("Good catch, I will rename it.");

        ArgumentCaptor<String> context = ArgumentCaptor.forClass(String.class);
        verify(writer).draftReply(context.capture(), eq(REPO), eq(Optional.of("w1")), eq(ReplyVoice.AUTHOR));
        JsonNode json = new ObjectMapper().readTree(context.getValue());
        assertThat(json.path("comment")).isEqualTo(new ObjectMapper().readTree(comment.snapshot()));
        assertThat(json.path("thread").toString()).isEqualTo(
                new ObjectMapper().createArrayNode()
                        .add(new ObjectMapper().createObjectNode().put("from", "agent").put("text", NORMALIZED))
                        .add(new ObjectMapper().createObjectNode().put("from", "you").put("text", "Is it used elsewhere?"))
                        .add(new ObjectMapper().createObjectNode().put("from", "agent").put("text", "Only in Config."))
                        .toString());
        assertThat(context.getValue()).doesNotContain(DiscussionPrompt.SUMMARY_REQUEST);
        assertThat(rows.get("t1")).isEqualTo(before);
        assertThat(prompts).isEmpty();

        AgentTask released = task().complete(new TaskSummary("done", Optional.empty(), AgentMetrics.empty(), Optional.empty(), ImmutableList.of()), NOW, NOW.plusSeconds(60))
                .withWorktreeRemovedAt(NOW.plusSeconds(60));
        when(tasks.getInteractive("w1", TASK)).thenReturn(released);
        assertThatThrownBy(() -> service.draftReply("w1", TASK.toString(), "t1"))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("read-only");
    }

    @Test
    void summarizesABatchInOneSessionAndGivesEachThreadItsVerdictWithoutASession()
    {
        answered(TurnId.random(), "Done.");
        driver.answers.add("""
                {"items":[
                {"ref":"c2","ask":"Two.","valid":"no","why":"It already does.","do":"no","plan":"","size":""},
                {"ref":"c1","ask":"One.","valid":"yes","why":"It holds.","do":"yes","plan":"Fix it.","size":"trivial"},
                {"ref":"c3","ask":"Three.","valid":"unsure","why":"Needs a run.","do":"your_call","plan":"Run the suite.","size":"big"}]}""");
        List<ReviewCommentAnchor> comments = ImmutableList.of(
                anchor("review:1", "{\"body\":\"one\"}"), anchor("review_comment:2", "{\"body\":\"two\"}"), anchor("review_comment:3", "{\"body\":\"three\"}"));
        hold = true;

        service.summarize("w1", TASK.toString(), comments);

        // Every thread is pending from its first stored row, before the batch has run.
        assertThat(pendingWhenStored).containsExactly(true, true, true);
        assertThat(rows.values()).allSatisfy(thread -> assertThat(thread.messages()).extracting(DiscussionMessage::body).containsExactly(DiscussionPrompt.SUMMARY_REQUEST));
        runHeld();

        assertThat(prompts).singleElement().satisfies(prompt -> assertThat(prompt)
                .contains("Comment references: c1 through c3.", "Summarize these 3 review comments.").doesNotContain("\"body\":\"one\""));
        assertThat(savedContext("source")).containsSubsequence("\"ref\":\"c1\"", "\"body\":\"one\"", "\"ref\":\"c2\"", "\"body\":\"two\"", "\"ref\":\"c3\"", "\"body\":\"three\"");
        assertThat(rows.values()).extracting(thread -> thread.comment().orElseThrow().key()).containsExactly("review:1", "review_comment:2", "review_comment:3");
        assertThat(rows.values()).allSatisfy(thread -> {
            assertThat(thread.sessionReference()).isEmpty();
            assertThat(thread.messages()).hasSize(2);
        });
        assertThat(rows.values()).extracting(thread -> thread.messages().getLast().body()).containsExactly(
                "{\"ask\":\"One.\",\"valid\":\"yes\",\"why\":\"It holds.\",\"do\":\"yes\",\"plan\":\"Fix it.\",\"size\":\"trivial\"}",
                "{\"ask\":\"Two.\",\"valid\":\"no\",\"why\":\"It already does.\",\"do\":\"no\",\"plan\":\"\",\"size\":\"\"}",
                "{\"ask\":\"Three.\",\"valid\":\"unsure\",\"why\":\"Needs a run.\",\"do\":\"your_call\",\"plan\":\"Run the suite.\",\"size\":\"big\"}");
        assertThat(service.list("w1", TASK.toString())).noneMatch(TaskDiscussionView::pending);

        // Comments that already have threads are skipped, so the same batch again starts nothing.
        service.summarize("w1", TASK.toString(), comments);
        assertThat(held).isEmpty();
        assertThat(prompts).hasSize(1);
    }

    @Test
    void aBatchFailureFailsEveryThreadAndARacedCommentLeavesTheSlot()
    {
        answered(TurnId.random(), "Done.");
        driver.answers.add(VERDICT);
        ReviewCommentAnchor raced = anchor("review_comment:9", "{\"body\":\"raced\"}");
        racing.put(raced.anchor().quoteHash(), commentThread("elsewhere", raced, ImmutableList.of()));
        hold = true;

        service.summarize("w1", TASK.toString(), ImmutableList.of(anchor("review:1", "{\"body\":\"one\"}"), raced, anchor("review:3", "{\"body\":\"three\"}")));

        assertThat(service.list("w1", TASK.toString())).extracting(TaskDiscussionView::id, TaskDiscussionView::pending)
                .containsExactlyInAnyOrder(tuple(idOf("review:1"), true), tuple("elsewhere", false), tuple(idOf("review:3"), true));
        runHeld();

        // One verdict for two comments fails the whole batch, and each hidden request carries the reason.
        assertThat(prompts).singleElement().satisfies(prompt -> assertThat(prompt).contains("Summarize these 2 review comments.").doesNotContain("raced"));
        for (String key : ImmutableList.of("review:1", "review:3")) {
            assertThat(rows.get(idOf(key)).messages()).singleElement().satisfies(request -> {
                assertThat(request.body()).isEqualTo(DiscussionPrompt.SUMMARY_REQUEST);
                assertThat(request.error()).hasValueSatisfying(error -> assertThat(error).contains("exactly 2 items"));
            });
        }
        assertThat(rows.get("elsewhere").messages()).isEmpty();
    }

    @Test
    void summarizeGivesTheSlotBackWheneverItRefusesAfterClaiming()
    {
        List<ReviewCommentAnchor> one = ImmutableList.of(anchor(KEY, "{\"body\":\"one\"}"));

        assertThatThrownBy(() -> service.summarize("w1", TASK.toString(), one))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessage("Waiting for the agent's first answer");

        answered(TurnId.random(), "x".repeat(TaskDiscussion.MAX_SEED_CHARACTERS));
        assertThatThrownBy(() -> service.summarize("w1", TASK.toString(), one))
                .isInstanceOf(AgentConflictException.class)
                .hasMessage("This task's history is too long to ask about");
        assertThat(rows).isEmpty();

        events.clear();
        answered(TurnId.random(), "Done.");
        store(commentThread("t1", anchor(KEY, "{}"), ImmutableList.of()));
        service.summarize("w1", TASK.toString(), one);

        IntStream.range(1, TaskDiscussionService.MAXIMUM_COMMENT_THREADS_PER_TASK)
                .forEach(index -> store(commentThread("c" + index, anchor("review:" + index, "{}"), ImmutableList.of())));
        assertThatThrownBy(() -> service.summarize("w1", TASK.toString(), ImmutableList.of(anchor("review_comment:7", "{}"))))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("most private threads");
        assertThat(prompts).isEmpty();

        // Each refusal gave the task's slot back, so the next question on the task is answered.
        rows.clear();
        driver.answers.add(VERDICT);
        assertThat(service.askComment(askComment(KEY, "")).messages()).hasSize(1);
        assertThat(prompts).hasSize(1);
    }

    @Test
    void askingAboutAHistoryOverItsBudgetSaysWhy()
    {
        answered(TurnId.random(), "x".repeat(TaskDiscussion.MAX_SEED_CHARACTERS));

        assertThatThrownBy(() -> service.askComment(askComment(KEY, "")))
                .isInstanceOf(AgentConflictException.class)
                .hasMessage("This task's history is too long to ask about");
        assertThat(rows).isEmpty();
    }

    @Test
    void summarizeTrimsABatchToTheRoomLeftForCommentThreads()
    {
        answered(TurnId.random(), "Done.");
        IntStream.range(0, TaskDiscussionService.MAXIMUM_COMMENT_THREADS_PER_TASK - 1)
                .forEach(index -> store(commentThread("c" + index, anchor("review:" + index, "{}"), ImmutableList.of())));
        driver.answers.add(VERDICT);

        service.summarize("w1", TASK.toString(), ImmutableList.of(
                anchor("review_comment:1", "{\"body\":\"one\"}"), anchor("review_comment:2", "{\"body\":\"two\"}"), anchor("review_comment:3", "{\"body\":\"three\"}")));

        assertThat(rows).hasSize(TaskDiscussionService.MAXIMUM_COMMENT_THREADS_PER_TASK);
        assertThat(rows.get(idOf("review_comment:1")).messages()).extracting(DiscussionMessage::body).containsExactly(DiscussionPrompt.SUMMARY_REQUEST, NORMALIZED);
        assertThat(prompts).singleElement().satisfies(prompt -> assertThat(prompt)
                .contains("--- question ---\n\n" + DiscussionPrompt.SUMMARY_REQUEST)
                .doesNotContain("{\"body\":\"two\"}", "{\"body\":\"three\"}"));
    }

    @Test
    void aVerdictThatCannotBeStoredFailsOnlyItsOwnThread()
    {
        answered(TurnId.random(), "Done.");
        driver.answers.add("""
                {"items":[
                {"ref":"c1","ask":"One.","valid":"yes","why":"It holds.","do":"yes","plan":"Fix it.","size":"trivial"},
                {"ref":"c2","ask":"Two.","valid":"yes","why":"It holds.","do":"yes","plan":"Fix it.","size":"trivial"},
                {"ref":"c3","ask":"Three.","valid":"yes","why":"It holds.","do":"yes","plan":"Fix it.","size":"trivial"}]}""");
        hold = true;
        service.summarize("w1", TASK.toString(), ImmutableList.of(
                anchor("review:1", "{\"body\":\"one\"}"), anchor("review:2", "{\"body\":\"two\"}"), anchor("review:3", "{\"body\":\"three\"}")));
        conflicting.add(idOf("review:2"));

        runHeld();

        assertThat(rows.get(idOf("review:1")).messages()).extracting(DiscussionMessage::author)
                .containsExactly(DiscussionMessage.Author.USER, DiscussionMessage.Author.AGENT);
        assertThat(rows.get(idOf("review:2")).messages()).singleElement()
                .satisfies(request -> assertThat(request.error()).contains("This discussion changed while you were reading it"));
        assertThat(rows.get(idOf("review:3")).messages()).extracting(DiscussionMessage::author)
                .containsExactly(DiscussionMessage.Author.USER, DiscussionMessage.Author.AGENT);
    }

    @Test
    void refusesAThreadStoredForAnotherPullRequestAndGivesTheSlotBack()
    {
        answered(TurnId.random(), "Done.");
        store(commentThread("t13", new ReviewCommentAnchor(REPO, 13, KEY, "{}"), ImmutableList.of()));

        assertThatThrownBy(() -> service.askComment(askComment(KEY, "Why?")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not in this pull request");
        assertThat(rows.get("t13").messages()).isEmpty();

        store(discussion(Optional.empty(), ImmutableList.of()));
        assertThat(service.ask(new AskDiscussionCommand("w1", TASK.toString(), "d1", "why per thread?")).pending()).isTrue();
    }

    @Test
    void draftingRefusesAPassageDiscussionAndAThreadTheAgentHasNotAnswered()
    {
        store(discussion(Optional.empty(), ImmutableList.of(
                new DiscussionMessage("m1", DiscussionMessage.Author.USER, "why per thread?", NOW, Optional.empty()),
                new DiscussionMessage("m2", DiscussionMessage.Author.AGENT, "The pool is sized from CPU count.", NOW, Optional.empty()))));
        store(commentThread("t1", anchor(KEY, "{}"), ImmutableList.of(
                new DiscussionMessage("m1", DiscussionMessage.Author.USER, DiscussionPrompt.SUMMARY_REQUEST, NOW, Optional.of("The provider stopped")))));

        assertThatThrownBy(() -> service.draftReply("w1", TASK.toString(), "d1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Only a private thread on a review comment can draft a reply");
        assertThatThrownBy(() -> service.draftReply("w1", TASK.toString(), "t1"))
                .isInstanceOf(AgentConflictException.class)
                .hasMessage("Ask the agent before drafting a reply");
        verifyNoInteractions(writer);
    }

    @Test
    void anotherSpellingOfTheTaskIdSharesItsSlotAndItsThreads()
    {
        String shouted = TASK.toString().toUpperCase(Locale.ROOT);
        store(discussion(Optional.empty(), ImmutableList.of()));
        answered(TurnId.random(), "Done.");
        hold = true;
        service.ask(new AskDiscussionCommand("w1", TASK.toString(), "d1", "why per thread?"));

        assertThatThrownBy(() -> service.askComment(new AskCommentCommand("w1", shouted, REPO, 12, KEY, "")))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("already answering");
        runHeld();

        driver.answers.add(VERDICT);
        String id = service.askComment(new AskCommentCommand("w1", shouted, REPO, 12, KEY, "")).id();

        assertThat(rows.get(id).taskId()).isEqualTo(TASK.toString());
        assertThat(service.list("w1", shouted)).extracting(TaskDiscussionView::id).containsExactlyInAnyOrder("d1", id);
    }

    @Test
    void aPreparedWorkingCopyAnswersWithoutAnyReviewRunsOrTaskAnswers()
    {
        hostReview(review(AgentReview.State.OPEN, OptionalInt.of(12), Optional.empty()));
        when(reviews.listRuns(REVIEW)).thenReturn(ImmutableList.of());
        when(reviews.listFindings(REVIEW)).thenReturn(ImmutableList.of());
        driver.answers.add("The author's reply addresses the concern.");

        TaskDiscussionView view = service.askReviewComment(REVIEW, KEY, "Does the reply address my concern?");

        assertThat(rows.get(view.id()).messages()).extracting(DiscussionMessage::body)
                .containsExactly("Does the reply address my concern?", "The author's reply addresses the concern.");
        assertThat(prompts).singleElement().satisfies(prompt -> assertThat(prompt)
                .contains("A full review may not have run", "read_discussion_context", "Does the reply address my concern?")
                .doesNotContain("request you reviewed"));
        assertThat(driver.lastContext.access()).isEqualTo(SessionAccess.READ_ONLY);
        verify(tasks, never()).getInteractive(any(), eq(REVIEW_TASK));
    }

    @Test
    void aReviewAnswersAboutItsOwnPullRequestFromWhatItSavedRatherThanItsLog()
    {
        hostReview(review(AgentReview.State.OPEN, OptionalInt.of(12), Optional.empty()));
        when(reviews.listFindings(REVIEW)).thenReturn(ImmutableList.of(finding("The cache leaks entries")));
        // Whatever the task log holds, a review's thread never starts from it.
        answered(TurnId.random(), "Saved the investigation.");
        driver.answers.add(VERDICT);

        TaskDiscussionView view = service.askReviewComment(REVIEW, KEY, "");

        TaskDiscussion thread = rows.get(view.id());
        assertThat(thread.taskId()).isEqualTo(REVIEW_TASK.toString());
        assertThat(thread.messages()).extracting(DiscussionMessage::body).containsExactly(DiscussionPrompt.SUMMARY_REQUEST, NORMALIZED);
        assertThat(view.comment()).contains(new TaskDiscussionView.Comment(REPO, 12, KEY));
        assertThat(prompts).singleElement().satisfies(prompt -> assertThat(prompt)
                .startsWith(DiscussionPrompt.REVIEW_COMMENT_PREAMBLE).contains(DiscussionPrompt.SUMMARY_REQUEST)
                .doesNotContain("The cache leaks entries", "Please rename this flag.", "Saved the investigation."));
        assertThat(savedContext("history")).contains("The cache leaks entries").doesNotContain("Saved the investigation.");
        assertThat(savedContext("source")).contains("Please rename this flag.");
        assertThat(driver.lastContext.access()).isEqualTo(SessionAccess.READ_ONLY);
        assertThat(driver.lastContext.taskId()).isEqualTo(REVIEW_TASK.toString());
        assertThat(service.listReviewThreads(REVIEW)).extracting(TaskDiscussionView::id).containsExactly(view.id());
        verify(tasks, never()).getInteractive(any(), eq(REVIEW_TASK));

        // A lost session re-seeds from the review's current results.
        when(reviews.listFindings(REVIEW)).thenReturn(ImmutableList.of(finding("The cache leaks entries"), finding("close() runs twice")));
        driver.failResume = true;
        service.askReviewComment(REVIEW, KEY, "Is close() always called?");

        assertThat(prompts.getLast()).startsWith(DiscussionPrompt.REVIEW_COMMENT_PREAMBLE)
                .contains("Read the discussion section", "Is close() always called?").doesNotContain("close() runs twice");
        assertThat(savedContext("history")).contains("close() runs twice");
        assertThat(savedContext("discussion")).contains("Summarize this review comment.", "Rename the flag.");
    }

    @Test
    void aReviewThatCannotAnswerRefusesWhileItsThreadsStayReadableAndResolvable()
    {
        hostReview(review(AgentReview.State.OPEN, OptionalInt.of(12), Optional.empty()));
        driver.answers.add(VERDICT);
        String id = service.askReviewComment(REVIEW, KEY, "").id();

        when(reviews.findReview(REVIEW)).thenReturn(Optional.of(review(AgentReview.State.CLOSED, OptionalInt.of(12), Optional.empty())));
        assertThatThrownBy(() -> service.askReviewComment(REVIEW, KEY, "Still?")).hasMessage("This review has ended, so its threads are read-only");
        assertThatThrownBy(() -> service.retryReviewThread(REVIEW, id, "m1")).hasMessage("This review has ended, so its threads are read-only");
        assertThatThrownBy(() -> service.draftReviewReply(REVIEW, id)).hasMessage("This review has ended, so its threads are read-only");
        assertThat(service.listReviewThreads(REVIEW)).extracting(TaskDiscussionView::id).containsExactly(id);
        assertThat(service.resolveReviewThread(REVIEW, id, true, rows.get(id).version()).resolved()).isTrue();

        when(reviews.findReview(REVIEW)).thenReturn(Optional.of(review(AgentReview.State.OPEN, OptionalInt.of(12), Optional.empty())));
        when(tasks.get(REVIEW_TASK)).thenReturn(reviewTask()
                .complete(new TaskSummary("done", Optional.empty(), AgentMetrics.empty(), Optional.empty(), ImmutableList.of()), NOW, NOW.plusSeconds(60))
                .withWorktreeRemovedAt(NOW.plusSeconds(60)));
        assertThatThrownBy(() -> service.askReviewComment(REVIEW, KEY, "Still?")).hasMessage("This review has ended, so its threads are read-only");

        when(reviews.findReview(REVIEW)).thenReturn(Optional.of(review(AgentReview.State.OPEN, OptionalInt.empty(), Optional.of(new AgentReview.Replay(
                "replay-1",
                12,
                true,
                NOW)))));
        assertThatThrownBy(() -> service.askReviewComment(
                REVIEW,
                KEY,
                "")).isInstanceOf(AgentConflictException.class).hasMessage("A replay reviews past code, so it has no private threads");
        when(reviews.findReview(REVIEW)).thenReturn(Optional.of(new AgentReview(
                REVIEW,
                "w1",
                REPO,
                OptionalInt.empty(),
                OptionalLong.empty(),
                REVIEW_TASK.toString(),
                Optional.of("development-task"),
                AgentReview.State.OPEN,
                false,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                NOW,
                NOW,
                Optional.empty(),
                Optional.empty())));
        assertThatThrownBy(() -> service.askReviewComment(REVIEW, KEY, "")).isInstanceOf(AgentConflictException.class).hasMessage("This review has no pull request");
        assertThatThrownBy(() -> service.listReviewThreads("missing")).isInstanceOf(ReviewNotFoundException.class);
    }

    @Test
    void aReviewReachesOnlyItsOwnPullRequestAndItsOwnTasksThreadsAndDraftsAsAReviewer()
    {
        answered(TurnId.random(), "Done.");
        driver.answers.add(VERDICT);
        String taskThread = service.askComment(askComment(KEY, "")).id();
        hostReview(review(AgentReview.State.OPEN, OptionalInt.of(12), Optional.empty()));

        assertThatThrownBy(() -> service.askReviewComment(REVIEW, "review_comment:999", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not in this pull request");
        assertThatThrownBy(() -> service.resolveReviewThread(REVIEW, taskThread, true, rows.get(taskThread).version()))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("no longer exists");

        driver.answers.add(VERDICT);
        String reviewThread = service.askReviewComment(REVIEW, KEY, "").id();
        assertThat(reviewThread).isNotEqualTo(taskThread);
        when(writer.draftReply(anyString(), eq(REPO), eq(Optional.of("w1")), eq(ReplyVoice.REVIEWER))).thenReturn("+1, close() evicts them.");
        assertThat(service.draftReviewReply(REVIEW, reviewThread)).isEqualTo("+1, close() evicts them.");
    }

    @Test
    void reviewPassagesReuseTheTaskDiscussionLifecycleInAReadOnlySession()
    {
        hostReview(review(AgentReview.State.OPEN, OptionalInt.of(12), Optional.empty()));
        TurnId turn = TurnId.random();
        events.add(new AgentEventEnvelope(AgentEventEnvelope.CURRENT_SCHEMA_VERSION, EventId.random(), 1, REVIEW_TASK, Optional.of(turn),
                AgentEventSource.root(), NOW, new AgentEventPayload.AssistantMessage(MessageKind.ANSWER, MessagePhase.FINAL, "Cancellation releases the writer.")));

        TaskDiscussionView opened = service.openReviewDiscussion(REVIEW, turn.toString(), "releases the writer", 1, "How?");
        assertThat(opened.taskId()).isEqualTo(REVIEW_TASK.toString());
        assertThat(opened.comment()).isEmpty();
        assertThat(driver.lastContext.access()).isEqualTo(SessionAccess.READ_ONLY);
        assertThat(prompts.getFirst()).contains("releases the writer", "How?").doesNotContain("Cancellation releases the writer.");
        assertThat(savedContext("history")).contains("Cancellation releases the writer.");
        verify(tasks, never()).getInteractive(anyString(), eq(REVIEW_TASK));

        assertThatThrownBy(() -> service.askReviewDiscussion(REVIEW, opened.id(), " "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Question must contain");
        assertThatThrownBy(() -> service.askReviewDiscussion(REVIEW, opened.id(), "x".repeat(16_001)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Question must contain");
        service.askReviewDiscussion(REVIEW, opened.id(), "What if cancellation repeats?");
        assertThat(rows.get(opened.id()).messages()).extracting(DiscussionMessage::body)
                .containsExactly("How?", "Answered.", "What if cancellation repeats?", "Answered.");
        assertThat(service.openReviewDiscussion(REVIEW, turn.toString(), "releases the writer", 1, "Again?").id()).isEqualTo(opened.id());
        assertThat(service.listReviewThreads(REVIEW)).hasSize(1);
        assertThat(service.resolveReviewThread(REVIEW, opened.id(), true, rows.get(opened.id()).version()).resolved()).isTrue();

        when(reviews.findReview(REVIEW)).thenReturn(Optional.of(review(AgentReview.State.CLOSED, OptionalInt.of(12), Optional.empty())));
        assertThatThrownBy(() -> service.openReviewDiscussion(REVIEW, turn.toString(), "releases the writer", 1, "How?"))
                .isInstanceOf(AgentConflictException.class).hasMessageContaining("read-only");
        assertThatThrownBy(() -> service.askReviewDiscussion(REVIEW, opened.id(), "Still?"))
                .isInstanceOf(AgentConflictException.class).hasMessageContaining("read-only");
        assertThatThrownBy(() -> service.retryReviewThread(REVIEW, opened.id(), "missing"))
                .isInstanceOf(AgentConflictException.class).hasMessageContaining("read-only");
        assertThat(service.listReviewThreads(REVIEW)).hasSize(1);
        assertThat(service.resolveReviewThread(REVIEW, opened.id(), false, rows.get(opened.id()).version()).resolved()).isFalse();
    }

    @Test
    void localAndReplayReviewsCanDiscussTheirOwnAnswersAndRetryFailures()
    {
        hostReview(new AgentReview(
                REVIEW,
                "w1",
                REPO,
                OptionalInt.empty(),
                OptionalLong.empty(),
                REVIEW_TASK.toString(),
                Optional.of(TASK.toString()),
                AgentReview.State.OPEN,
                false,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                NOW,
                NOW,
                Optional.empty(),
                Optional.empty()));
        TurnId turn = TurnId.random();
        events.add(new AgentEventEnvelope(AgentEventEnvelope.CURRENT_SCHEMA_VERSION, EventId.random(), 1, REVIEW_TASK, Optional.of(turn),
                AgentEventSource.root(), NOW, new AgentEventPayload.AssistantMessage(MessageKind.ANSWER, MessagePhase.FINAL, "Cancellation releases the writer.")));
        driver.result = new AgentTurnResult(AgentTurnResult.State.FAILED, Optional.empty(), Optional.of("Provider failed"), Duration.ZERO, true);
        String id = service.openReviewDiscussion(REVIEW, turn.toString(), "releases the writer", 1, "How?").id();
        assertThat(rows.get(id).messages().getLast().error()).isPresent();
        driver.result = new AgentTurnResult(AgentTurnResult.State.COMPLETED, Optional.of("Answered."), Optional.empty(), Duration.ZERO, true);
        service.retryReviewThread(REVIEW, id, rows.get(id).messages().getLast().id());
        assertThat(rows.get(id).messages()).extracting(DiscussionMessage::body).containsExactly("How?", "Answered.");

        hostReview(review(AgentReview.State.OPEN, OptionalInt.empty(), Optional.of(new AgentReview.Replay("replay-1", 12, true, NOW))));
        service.askReviewDiscussion(REVIEW, id, "At this historical revision?");
        assertThat(rows.get(id).messages().getLast().body()).isEqualTo("Answered.");
    }

    @Test
    void reviewPassagesCannotQuoteOrContinueAnotherTasksConversation()
    {
        hostReview(review(AgentReview.State.OPEN, OptionalInt.of(12), Optional.empty()));
        TurnId turn = TurnId.random();
        answered(turn, "Cancellation releases the writer.");
        String other = service.open(new OpenDiscussionCommand("w1", TASK.toString(), turn.toString(), "releases the writer", 1, "How?")).id();
        assertThatThrownBy(() -> service.openReviewDiscussion(REVIEW, turn.toString(), "releases the writer", 1, "How?"))
                .isInstanceOf(AgentUnavailableException.class);
        assertThatThrownBy(() -> service.askReviewDiscussion(REVIEW, other, "How?"))
                .isInstanceOf(AgentConflictException.class).hasMessageContaining("no longer exists");
        assertThatThrownBy(() -> service.retryReviewThread(REVIEW, other, "missing"))
                .isInstanceOf(AgentConflictException.class).hasMessageContaining("no longer exists");
        assertThat(service.listReviewThreads(REVIEW)).isEmpty();
    }

    private String idOf(String key)
    {
        return rows.values().stream().filter(thread -> thread.comment().map(ReviewCommentAnchor::key).filter(key::equals).isPresent())
                .findFirst().orElseThrow().id();
    }

    private void runHeld()
    {
        hold = false;
        List<Runnable> jobs = ImmutableList.copyOf(held);
        held.clear();
        jobs.forEach(Runnable::run);
    }

    private TaskDiscussionService service()
    {
        return service(job -> {
            if (hold) {
                held.add(job);
            }
            else {
                job.run();
            }
        });
    }

    private TaskDiscussionService service(Executor executor)
    {
        TaskDiscussionStore store = mock(TaskDiscussionStore.class);
        when(store.find(any())).thenAnswer(call -> Optional.ofNullable(rows.get(call.getArgument(0, String.class))));
        when(store.update(any())).thenAnswer(call -> {
            TaskDiscussion updated = call.getArgument(0, TaskDiscussion.class);
            if (conflicting.remove(updated.id())) {
                throw new AgentConflictException("This discussion changed while you were reading it");
            }
            rows.put(updated.id(), updated);
            return updated;
        });
        when(store.findByAnchor(any(), any(), any())).thenAnswer(call -> rows.values().stream()
                .filter(row -> row.taskId().equals(call.getArgument(0)) && row.anchor().turnId().equals(call.getArgument(1))
                        && row.anchor().quoteHash().equals(call.getArgument(2)))
                .findFirst());
        when(store.listByTask(any())).thenAnswer(call -> {
            List<TaskDiscussion> read = rows.values().stream().filter(row -> row.taskId().equals(call.getArgument(0))).collect(toImmutableList());
            if (answerDuringList) {
                answerDuringList = false;
                runHeld();
            }
            return read;
        });
        when(store.insertOrGet(any())).thenAnswer(call -> {
            TaskDiscussion inserted = call.getArgument(0, TaskDiscussion.class);
            Optional.ofNullable(racing.remove(inserted.anchor().quoteHash())).ifPresent(this::store);
            Optional<TaskDiscussion> existing = rows.values().stream()
                    .filter(row -> row.taskId().equals(inserted.taskId()) && row.anchor().quoteHash().equals(inserted.anchor().quoteHash()))
                    .findFirst();
            if (existing.isPresent()) {
                return existing.orElseThrow();
            }
            store(inserted);
            List<TaskDiscussionView> listed = inserted.taskId().equals(TASK.toString()) ? this.service.list("w1", TASK.toString()) : this.service.listReviewThreads(REVIEW);
            pendingWhenStored.add(listed.stream().filter(view -> view.id().equals(inserted.id())).findFirst().orElseThrow().pending());
            return inserted;
        });
        when(tasks.getInteractive("w1", TASK)).thenReturn(task());
        AgentResourceService resources = mock(AgentResourceService.class);
        when(resources.allocate(any())).thenAnswer(call -> {
            Path root = Path.of("/tmp/bytequay-discussion-scratch").resolve(call.getArgument(0).toString());
            return new TaskResources(root, root.resolve("events.jsonl"), root.resolve("blobs"), root.resolve("provider/scratch"));
        });
        AgentDriverRegistry drivers = mock(AgentDriverRegistry.class);
        when(drivers.require(AgentLaneId.CLAUDE_CLI)).thenReturn(driver);
        TaskLogStore logStore = mock(TaskLogStore.class);
        when(logStore.readAfter(any(), anyLong(), anyInt())).thenAnswer(call -> events.stream()
                .filter(event -> event.taskId().equals(call.getArgument(0)) && event.sequence() > call.getArgument(1, Long.class))
                .limit(call.getArgument(2, Integer.class))
                .collect(toImmutableList()));
        when(ownership.owns(any(), eq(REPO), eq(12), eq(false))).thenReturn(true);
        PullRequestStore pullRequests = mock(PullRequestStore.class);
        when(pullRequests.findIdByRepoAndNumber(REPO, 12)).thenReturn(Optional.of(12L));
        PrDetailStore details = mock(PrDetailStore.class);
        when(details.find(12L)).thenReturn(Optional.of(detail()));
        return new TaskDiscussionService(store, tasks, resources, drivers, logStore, Clock.fixed(NOW, ZoneOffset.UTC), ownership,
                new ReviewCommentReader(pullRequests, details), writer, reviews, executor);
    }

    private void hostReview(AgentReview review)
    {
        when(reviews.findReview(REVIEW)).thenReturn(Optional.of(review));
        when(reviews.findReviewByTask(REVIEW_TASK.toString())).thenReturn(Optional.of(review));
        when(tasks.get(REVIEW_TASK)).thenReturn(reviewTask());
    }

    private static AgentReview review(AgentReview.State state, OptionalInt number, Optional<AgentReview.Replay> replay)
    {
        return new AgentReview(REVIEW, "w1", REPO, number, OptionalLong.empty(), REVIEW_TASK.toString(), Optional.empty(), state, false,
                Optional.empty(), Optional.empty(), Optional.empty(), 0, NOW, NOW, state == AgentReview.State.CLOSED ? Optional.of(NOW) : Optional.empty(), replay);
    }

    private static ReviewFinding finding(String title)
    {
        return new ReviewFinding("finding-" + title.length(), REVIEW, "run-1", 1, title, "Body of " + title, Optional.of("major"), Optional.empty(), ImmutableList.of(), 1,
                ReviewFinding.State.OPEN, Optional.empty(), "a".repeat(40), false, 0, NOW, NOW, ImmutableList.of(), ReviewFinding.Origin.AUTOMATIC);
    }

    private static AgentTask reviewTask()
    {
        TaskWorktree worktree = new TaskWorktree(REVIEW_TASK, "w1", new RepositoryIdentity("owner", "repo"),
                Path.of("/tmp/bytequay-discussions").resolve(REVIEW_TASK.toString()), "bytequay/task/" + REVIEW_TASK, "c".repeat(40));
        return AgentTask.created(REVIEW_TASK, false, "Review owner/repo#12", TitleSource.USER, AgentLaneId.CLAUDE_CLI, worktree,
                new AgentTaskDefaults(Optional.of("opus"), Optional.empty(), AgentPermissionPolicy.AUTO), NOW);
    }

    private void store(TaskDiscussion discussion)
    {
        rows.put(discussion.id(), discussion);
    }

    private void answered(TurnId turn, String answer)
    {
        events.add(event(turn, new AgentEventPayload.UserMessage("Rename the flag", ImmutableList.of(), AgentEventPayload.DeliveryKind.TURN)));
        events.add(event(turn, new AgentEventPayload.AssistantMessage(MessageKind.ANSWER, MessagePhase.FINAL, answer)));
    }

    private AgentEventEnvelope event(TurnId turn, AgentEventPayload payload)
    {
        return new AgentEventEnvelope(AgentEventEnvelope.CURRENT_SCHEMA_VERSION, EventId.random(), events.size() + 1, TASK, Optional.of(turn),
                AgentEventSource.root(), NOW, payload);
    }

    private static AskCommentCommand askComment(String key, String question)
    {
        return new AskCommentCommand("w1", TASK.toString(), REPO, 12, key, question);
    }

    private static ReviewCommentAnchor anchor(String key, String snapshot)
    {
        return new ReviewCommentAnchor(REPO, 12, key, snapshot);
    }

    private static TaskDiscussion commentThread(String id, ReviewCommentAnchor comment, List<DiscussionMessage> messages)
    {
        return new TaskDiscussion(id, TASK.toString(), comment.anchor(), Optional.of(comment), Optional.empty(), "claude", Optional.empty(), Optional.empty(),
                messages, false, NOW, NOW, 0, Optional.empty(), Optional.empty());
    }

    private static StoredPrDetail detail()
    {
        PrRawDetail raw = new PrRawDetail(null, null, null, null, null, null, null, null, "Body", ImmutableList.of(), null, ImmutableList.of(), null, false, null,
                "unknown", 0, 0, 0, 0, ImmutableList.of(), "b".repeat(40), "branch", null);
        PrTimelineComment comment = new PrTimelineComment(123L, "bob", null, false, NOW, "Please rename this flag.", "https://github.com/owner/repo/pull/12#r123",
                null, null, ImmutableList.of());
        PrTimelineEvent thread = new PrTimelineEvent(123L, "review_thread", "bob", NOW, null,
                PrTimelinePayload.builder().path("src/Config.java").line(42).diffHunk("@@ -1 +1 @@\n-old\n+new").comments(ImmutableList.of(comment)).build(), Optional.empty());
        return new StoredPrDetail(raw, ImmutableList.of(), ImmutableList.of(thread), ImmutableList.of(), ImmutableList.of());
    }

    private static TaskDiscussion discussion(Optional<DiscussionSession> session, List<DiscussionMessage> messages)
    {
        return new TaskDiscussion("d1", TASK.toString(),
                new DiscussionAnchor("turn-10", "INVALID_TABLE_PROPERTY", 1, DiscussionQuotes.hash("INVALID_TABLE_PROPERTY", 1)),
                Optional.empty(), Optional.of(SEED), "claude", Optional.empty(), session, messages, false, NOW, NOW, 0, Optional.empty(), Optional.empty());
    }

    private static TaskWorktree worktree()
    {
        return new TaskWorktree(TASK, "w1", new RepositoryIdentity("bytequay", "sample"),
                Path.of("/tmp/bytequay-discussions").resolve(TASK.toString()), "bytequay/task/" + TASK, "a".repeat(40));
    }

    private static AgentTask task()
    {
        return AgentTask.created(TASK, true, "Discussable task", TitleSource.USER, AgentLaneId.CLAUDE_CLI, worktree(),
                new AgentTaskDefaults(Optional.of("opus"), Optional.empty(), AgentPermissionPolicy.AUTO), NOW);
    }

    /** A provider that records what it was asked and answers however the test needs. */
    private final class ScriptedDriver
            implements AgentDriver
    {
        private final Deque<String> answers = new ArrayDeque<>();
        private final CountDownLatch answerStarted = new CountDownLatch(1);
        private boolean failResume;
        private boolean waitForAnswer;
        private int closed;
        private AgentSessionContext lastContext;
        private AgentTurnResult result = new AgentTurnResult(AgentTurnResult.State.COMPLETED, Optional.of("Answered."), Optional.empty(), Duration.ZERO, true);

        @Override
        public AgentLaneId id()
        {
            return AgentLaneId.CLAUDE_CLI;
        }

        // A discussion never probes or inspects capabilities, so a fixture that pretends to
        // answer either is claiming more than this test knows.
        @Override
        public AgentProviderStatus probe()
        {
            throw new UnsupportedOperationException("This scripted provider is never probed");
        }

        @Override
        public AgentCapabilities capabilities()
        {
            throw new UnsupportedOperationException("This scripted provider declares no capabilities");
        }

        @Override
        public AgentSession open(AgentSessionContext context, AgentEventSink events)
        {
            opened.add(context.sessionReference());
            lastContext = context;
            if (failResume && context.sessionReference().isPresent()) {
                throw new AgentUnavailableException("No conversation found with that session id");
            }
            return new ScriptedSession(context);
        }

        private final class ScriptedSession
                implements AgentSession
        {
            private final AgentSessionRef reference;

            private ScriptedSession(AgentSessionContext context)
            {
                this.reference = context.sessionReference()
                        .orElseGet(() -> new AgentSessionRef(AgentLaneId.CLAUDE_CLI, 1, "session-1"));
            }

            @Override
            public AgentSessionRef reference()
            {
                return reference;
            }

            @Override
            public RunningTurn start(AgentTurnCommand command)
            {
                prompts.add(command.message());
                AgentTurnResult answer = answers.isEmpty() ? result
                        : new AgentTurnResult(AgentTurnResult.State.COMPLETED, Optional.of(answers.poll()), Optional.empty(), Duration.ZERO, true);
                return new RunningTurn()
                {
                    @Override
                    public ProviderTurnRef reference()
                    {
                        return new ProviderTurnRef(command.turnId());
                    }

                    @Override
                    public CompletionStage<AgentTurnResult> completion()
                    {
                        answerStarted.countDown();
                        if (waitForAnswer) {
                            return new CompletableFuture<>();
                        }
                        return CompletableFuture.completedFuture(answer);
                    }

                    @Override
                    public void cancel() {}
                };
            }

            @Override
            public void close()
            {
                closed++;
            }
        }
    }
}
