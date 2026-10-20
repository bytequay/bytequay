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
package com.bytequay.app.agent;

import com.bytequay.agent.spi.LocalReviewBridge;
import com.bytequay.app.localreview.LocalReviewService;
import com.bytequay.app.localreview.LocalReviewService.AgentFinding;
import com.bytequay.app.localreview.LocalReviewView;
import com.bytequay.core.localreview.LocalReviewLearningAnchor;
import com.bytequay.core.localreview.LocalReviewMessage.Author;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.ReviewAnchor;
import com.bytequay.core.review.ReviewEvidence;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.review.ReviewMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.IntStream;

import static com.bytequay.app.review.ReviewTestData.openReview;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestLocalReviewBridgeResolverImpl
{
    @Test
    void bindsEveryLocalOperationToTheResolvedDevelopmentTask()
            throws Exception
    {
        LocalReviewService service = mock(LocalReviewService.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("localReviewService", service);
        LocalReviewBridgeResolverImpl resolver = new LocalReviewBridgeResolverImpl(beans.getBeanProvider(LocalReviewService.class), mapper);
        LocalReviewView thread = new LocalReviewView("comment-1", "task-1", "workspace-1", "owner/repo", 7,
                "Example.java", "RIGHT", 1, 2, Optional.of("base"), Optional.of("head"), "selected code", false, 1,
                Instant.EPOCH, Instant.EPOCH, 0, ImmutableList.of(), Optional.empty());
        when(service.availableForAgent("task-1")).thenReturn(true);
        when(service.listForAgent("task-1")).thenReturn(ImmutableList.of(thread));
        when(service.replyFromAgent("task-1", "comment-1", "Answer", "reply-1")).thenReturn(thread);
        when(service.resolveFromAgent("task-1", "comment-1", false, "reopen-1")).thenReturn(thread);

        assertThat(resolver.resolve("other-task")).isEmpty();
        LocalReviewBridge bridge = resolver.resolve("task-1").orElseThrow();
        // Not a development task, so nothing here speaks of review findings.
        assertThat(bridge.instruction()).isEqualTo(LocalReviewBridge.INSTRUCTION);
        JsonNode listed = mapper.readTree(bridge.list().apply(Optional.empty(), 0));
        assertThat(listed.path("comments").get(0).path("id").asText()).isEqualTo("comment-1");
        assertThat(listed.has("findings") || listed.has("omittedFindingCount")).isFalse();
        assertThat(mapper.readTree(bridge.list().apply(Optional.of("comment-1"), 0)).path("code").asText()).isEqualTo("selected code");
        assertThatThrownBy(() -> bridge.list().apply(Optional.of("foreign-comment"), 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("is available to this task");
        assertThat(mapper.readTree(bridge.reply().apply(new LocalReviewBridge.Reply("comment-1", "Answer", "reply-1"))).path("id").asText()).isEqualTo("comment-1");
        assertThat(mapper.readTree(bridge.resolve().apply(new LocalReviewBridge.Resolution("comment-1", false, "reopen-1"))).has("operations")).isFalse();
        verify(service).replyFromAgent("task-1", "comment-1", "Answer", "reply-1");
        verify(service).resolveFromAgent("task-1", "comment-1", false, "reopen-1");
    }

    @Test
    void developmentAgentReadsReviewFindingsAndRepliesButCannotResolveThem()
            throws Exception
    {
        LocalReviewService service = mock(LocalReviewService.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("localReviewService", service);
        LocalReviewBridgeResolverImpl resolver = new LocalReviewBridgeResolverImpl(beans.getBeanProvider(LocalReviewService.class), mapper);
        AgentReview own = review("own", Optional.of("task-1"));
        AgentReview pullRequest = review("pull-request", Optional.empty());
        ReviewFinding resolved = finding("finding-1", "own", 1, ReviewFinding.State.RESOLVED, ImmutableList.of());
        ReviewFinding open = finding("finding-2", "own", 2, ReviewFinding.State.OPEN,
                ImmutableList.of(new ReviewEvidence("E1", "Example.java:10", "source read", "return left == right;", "Compares identity")));
        ReviewFinding other = finding("finding-3", "pull-request", 1, ReviewFinding.State.OPEN, ImmutableList.of());
        List<ReviewMessage> discussion = ImmutableList.of(
                message("question", ReviewMessage.Author.USER, Optional.empty()),
                message("answer", ReviewMessage.Author.AGENT, Optional.empty()),
                message("earlier-developer", ReviewMessage.Author.AGENT, Optional.of("task-0")),
                message("reply", ReviewMessage.Author.AGENT, Optional.of("task-1")));
        when(service.availableForAgent("task-1")).thenReturn(true);
        when(service.readsFindings("task-1")).thenReturn(true);
        when(service.listForAgent("task-1")).thenReturn(ImmutableList.of());
        when(service.findingsForAgent("task-1")).thenReturn(ImmutableList.of(
                new AgentFinding(own, resolved, ImmutableList.of()), new AgentFinding(own, open, discussion), new AgentFinding(pullRequest, other, ImmutableList.of())));

        LocalReviewBridge bridge = resolver.resolve("task-1").orElseThrow();
        assertThat(bridge.instruction()).isEqualTo(LocalReviewBridge.INSTRUCTION + LocalReviewBridge.FINDINGS_INSTRUCTION);
        JsonNode listed = mapper.readTree(bridge.list().apply(Optional.empty(), 0));
        assertThat(listed.path("comments")).isEmpty();
        // Open findings come first, and the two F-1 of different reviews stay apart by id and review.
        assertThat(listed.path("findings")).extracting(finding -> finding.path("id").asText()).containsExactly("finding-2", "finding-3", "finding-1");
        assertThat(listed.path("findings")).extracting(finding -> finding.path("label").asText()).containsExactly("F-2", "F-1", "F-1");
        assertThat(listed.path("findings")).extracting(finding -> finding.path("reviewOf").asText()).containsExactly("THIS_TASK", "PULL_REQUEST", "THIS_TASK");
        assertThat(listed.path("omittedFindingCount").intValue()).isZero();
        // A resolved finding says who resolved it; this one is the user's, so it is not the agent's to reopen.
        assertThat(listed.path("findings")).extracting(finding -> finding.path("resolvedBy").asText()).containsExactly("", "", "USER");
        JsonNode summary = listed.at("/findings/0");
        assertThat(summary.path("reviewId").asText()).isEqualTo("own");
        assertThat(summary.path("number").intValue()).isEqualTo(7);
        assertThat(summary.path("path").asText()).isEqualTo("Example.java");
        assertThat(summary.path("line").intValue()).isEqualTo(11);
        assertThat(summary.path("messageCount").intValue()).isEqualTo(4);
        assertThat(summary.at("/latestMessage/author").asText()).isEqualTo("DEVELOPER");
        assertThat(summary.has("body") || summary.has("evidence")).isFalse();

        JsonNode detail = mapper.readTree(bridge.list().apply(Optional.of("finding-2"), 0));
        assertThatThrownBy(() -> bridge.list().apply(Optional.of("finding-2"), 4000))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("selected code or saved learning cards");
        assertThat(detail.path("body").asText()).isEqualTo("Body of finding-2");
        assertThat(detail.path("code").asText()).isEqualTo("first\nsecond");
        assertThat(detail.at("/evidence/0/excerpt").asText()).isEqualTo("return left == right;");
        assertThat(detail.path("omittedEvidenceCount").intValue()).isZero();
        assertThat(detail.path("messages")).extracting(message -> message.path("author").asText()).containsExactly("USER", "REVIEWER", "DEVELOPER", "DEVELOPER");
        assertThat(detail.path("messages")).extracting(message -> message.path("developer").asText()).containsExactly("", "", "ANOTHER_TASK", "THIS_TASK");
        assertThat(mapper.readTree(bridge.list().apply(Optional.of("finding-1"), 0)).path("stateReason").asText()).isEqualTo("Reason");

        when(service.replyToFindingFromAgent("task-1", "finding-2", "Fixed in abc1234", "reply-1")).thenReturn(Optional.of(discussion.getLast()));
        JsonNode replied = mapper.readTree(bridge.reply().apply(new LocalReviewBridge.Reply("finding-2", "Fixed in abc1234", "reply-1")));
        assertThat(replied.path("id").asText()).isEqualTo("finding-2");
        assertThat(replied.path("latestMessageId").asText()).isEqualTo("reply");
        verify(service, never()).replyFromAgent(anyString(), anyString(), anyString(), anyString());

        when(service.resolveFindingFromAgent("task-1", "finding-2", true)).thenReturn(Optional.of(open.resolvedByDeveloper("task-1", Instant.EPOCH)));
        JsonNode closed = mapper.readTree(bridge.resolve().apply(new LocalReviewBridge.Resolution("finding-2", true, "resolve-1")));
        assertThat(closed.path("id").asText()).isEqualTo("finding-2");
        assertThat(closed.path("state").asText()).isEqualTo("RESOLVED");
        assertThat(closed.path("resolvedBy").asText()).isEqualTo("THIS_TASK");
        when(service.resolveFindingFromAgent("task-1", "finding-2", false)).thenReturn(Optional.of(open));
        JsonNode reopened = mapper.readTree(bridge.resolve().apply(new LocalReviewBridge.Resolution("finding-2", false, "reopen-1")));
        assertThat(reopened.path("state").asText()).isEqualTo("OPEN");
        assertThat(reopened.path("resolvedBy").isNull()).isTrue();
        verify(service, never()).resolveFromAgent(anyString(), anyString(), anyBoolean(), anyString());
    }

    @Test
    void boundsFindingOutputAndMarksWhatItLeftOut()
            throws Exception
    {
        LocalReviewService service = mock(LocalReviewService.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("localReviewService", service);
        LocalReviewBridgeResolverImpl resolver = new LocalReviewBridgeResolverImpl(beans.getBeanProvider(LocalReviewService.class), mapper);
        AgentReview review = review("own", Optional.of("task-1"));
        List<ReviewEvidence> evidence = IntStream.range(0, 60)
                .mapToObj(index -> new ReviewEvidence("E" + index, "Example.java", "source read", "\u0001".repeat(20_000), "x".repeat(20_000)))
                .collect(toImmutableList());
        List<ReviewMessage> discussion = IntStream.range(0, 200)
                .mapToObj(index -> new ReviewMessage("message-" + index, "own", Optional.of("finding-0"), Optional.empty(), ReviewMessage.Author.USER,
                        "\u0001".repeat(16_000), 1, "run", Optional.empty(), Optional.empty(), 0, Instant.EPOCH))
                .collect(toImmutableList());
        // The first finding is large but ordinary text; every other one is made of characters JSON escapes to six.
        List<AgentFinding> findings = IntStream.range(0, 300)
                .mapToObj(index -> {
                    String filler = index == 0 ? "x" : "\u0001";
                    return new AgentFinding(review, new ReviewFinding("finding-" + index, "own", "run", index + 1, filler.repeat(4096), filler.repeat(60_000), Optional.of("high"),
                            Optional.of(new ReviewAnchor(filler.repeat(4096), "RIGHT", 1, 2, ImmutableList.of(filler.repeat(60_000)))), evidence, 1, ReviewFinding.State.RESOLVED,
                            Optional.of(filler.repeat(60_000)), "b".repeat(40), false, 0, Instant.EPOCH, Instant.EPOCH, ImmutableList.of(), ReviewFinding.Origin.AUTOMATIC),
                            discussion);
                })
                .collect(toImmutableList());
        when(service.availableForAgent("task-1")).thenReturn(true);
        when(service.readsFindings("task-1")).thenReturn(true);
        when(service.listForAgent("task-1")).thenReturn(ImmutableList.of());
        when(service.findingsForAgent("task-1")).thenReturn(findings);
        LocalReviewBridge bridge = resolver.resolve("task-1").orElseThrow();

        String summary = bridge.list().apply(Optional.empty(), 0);
        assertThat(summary.length()).isLessThanOrEqualTo(LocalReviewBridgeResolverImpl.MAXIMUM_OUTPUT_LENGTH);
        JsonNode listed = mapper.readTree(summary);
        assertThat(listed.path("findings")).isNotEmpty();
        assertThat(listed.path("findings").size() + listed.path("omittedFindingCount").intValue()).isEqualTo(300);
        assertThat(listed.at("/findings/0/latestMessage/bodyTruncated").booleanValue()).isTrue();

        String detail = bridge.list().apply(Optional.of("finding-0"), 0);
        assertThat(detail.length()).isLessThanOrEqualTo(LocalReviewBridgeResolverImpl.MAXIMUM_OUTPUT_LENGTH);
        JsonNode read = mapper.readTree(detail);
        assertThat(read.path("bodyTruncated").booleanValue()).isTrue();
        assertThat(read.path("codeTruncated").booleanValue()).isTrue();
        assertThat(read.path("stateReasonTruncated").booleanValue()).isTrue();
        assertThat(read.path("evidence")).isNotEmpty();
        assertThat(read.path("omittedEvidenceCount").intValue()).isGreaterThan(0);
        assertThat(read.path("evidence").size() + read.path("omittedEvidenceCount").intValue()).isEqualTo(60);
        assertThat(read.at("/evidence/0/excerptTruncated").booleanValue()).isTrue();
        // The evidence stops early enough to return the newest discussion.
        assertThat(read.path("messages")).isNotEmpty();
        assertThat(read.path("messages").get(read.path("messages").size() - 1).path("id").asText()).isEqualTo("message-199");
        assertThat(read.path("omittedMessageCount").intValue()).isGreaterThan(0);

        String escaped = bridge.list().apply(Optional.of("finding-1"), 0);
        assertThat(escaped.length()).isLessThanOrEqualTo(LocalReviewBridgeResolverImpl.MAXIMUM_OUTPUT_LENGTH);
        assertThat(mapper.readTree(escaped).path("omittedEvidenceCount").intValue()).isEqualTo(60);
        assertThat(mapper.readTree(escaped).path("messages").get(0).path("id").asText()).isEqualTo("message-199");
    }

    @Test
    void learningToolsExposeTheSavedCardAndOmitNonexistentCodeLocations()
            throws Exception
    {
        LocalReviewService service = mock(LocalReviewService.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("localReviewService", service);
        LocalReviewBridgeResolverImpl resolver = new LocalReviewBridgeResolverImpl(beans.getBeanProvider(LocalReviewService.class), mapper);
        LocalReviewLearningAnchor anchor = new LocalReviewLearningAnchor("summary-turn", "purpose", "What this change does", "Saved implementation summary");
        LocalReviewView thread = new LocalReviewView("learning", "task-1", "workspace-1", "owner/repo", 0,
                "", "RIGHT", 0, 0, Optional.empty(), Optional.empty(), "", false, 1,
                Instant.EPOCH, Instant.EPOCH, 0, ImmutableList.of(), Optional.of(anchor));
        when(service.availableForAgent("task-1")).thenReturn(true);
        when(service.listForAgent("task-1")).thenReturn(ImmutableList.of(thread));
        LocalReviewBridge bridge = resolver.resolve("task-1").orElseThrow();
        var detail = mapper.readTree(bridge.list().apply(Optional.of("learning"), 0));
        assertThat(detail.at("/learningAnchor/turnId").asText()).isEqualTo("summary-turn");
        assertThat(detail.at("/learningAnchor/body").asText()).isEqualTo("Saved implementation summary");
        assertThat(detail.has("path") || detail.has("number") || detail.has("side") || detail.has("line") || detail.has("code")).isFalse();
        var listed = mapper.readTree(bridge.list().apply(Optional.empty(), 0)).path("comments").get(0);
        assertThat(listed.path("learningAnchor")).isEqualTo(detail.path("learningAnchor"));
        assertThat(listed.has("path")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retrievesCompleteSavedContextInBoundedPages(boolean learning)
            throws Exception
    {
        LocalReviewService service = mock(LocalReviewService.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("localReviewService", service);
        LocalReviewBridgeResolverImpl resolver = new LocalReviewBridgeResolverImpl(beans.getBeanProvider(LocalReviewService.class), mapper);
        String context = "x".repeat(3999) + "\uD83D\uDE80" + "\u0001".repeat(55_999);
        Optional<LocalReviewLearningAnchor> anchor = learning ? Optional.of(new LocalReviewLearningAnchor("summary-turn", "purpose", "Saved card", context)) : Optional.empty();
        LocalReviewView thread = new LocalReviewView("comment", "task-1", "workspace-1", "owner/repo", learning ? 0 : 7,
                learning ? "" : "src/Example.java", "RIGHT", learning ? 0 : 1, learning ? 0 : 2, Optional.empty(), Optional.empty(),
                learning ? "" : context, false, 1, Instant.EPOCH, Instant.EPOCH, 0, ImmutableList.of(), anchor);
        when(service.availableForAgent("task-1")).thenReturn(true);
        when(service.listForAgent("task-1")).thenReturn(ImmutableList.of(thread));
        LocalReviewBridge bridge = resolver.resolve("task-1").orElseThrow();
        StringBuilder restored = new StringBuilder();
        int offset = 0;
        while (true) {
            String output = bridge.list().apply(Optional.of("comment"), offset);
            assertThat(output.length()).isLessThanOrEqualTo(LocalReviewBridgeResolverImpl.MAXIMUM_OUTPUT_LENGTH);
            JsonNode page = mapper.readTree(output);
            String text = page.at(learning ? "/learningAnchor/body" : "/code").asText();
            assertThat(text.length()).isBetween(1, 4000);
            assertThat(Character.isHighSurrogate(text.charAt(text.length() - 1)) || Character.isLowSurrogate(text.charAt(0))).isFalse();
            assertThat(page.path("contextOffset").intValue()).isEqualTo(offset);
            assertThat(page.path("contextLength").intValue()).isEqualTo(context.length());
            restored.append(text);
            if (page.path("nextContextOffset").isNull()) {
                break;
            }
            assertThat(page.path("nextContextOffset").intValue()).isEqualTo(offset + text.length());
            offset = page.path("nextContextOffset").intValue();
        }
        assertThat(restored.toString()).isEqualTo(context);
        JsonNode end = mapper.readTree(bridge.list().apply(Optional.of("comment"), context.length()));
        assertThat(end.at(learning ? "/learningAnchor/body" : "/code").asText()).isEmpty();
        assertThat(end.path("nextContextOffset").isNull()).isTrue();
        for (int invalid : ImmutableList.of(-1, 4000, context.length() + 1, Integer.MAX_VALUE)) {
            assertThatThrownBy(() -> bridge.list().apply(Optional.of("comment"), invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> bridge.list().apply(Optional.empty(), 4000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bridge.list().apply(Optional.of("foreign-comment"), 0)).isInstanceOf(IllegalArgumentException.class);
        verify(service, never()).listForAgent("task-2");
    }

    @Test
    void boundsEscapedModelOutputAndMarksOmittedDiscussion()
            throws Exception
    {
        LocalReviewService service = mock(LocalReviewService.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("localReviewService", service);
        LocalReviewBridgeResolverImpl resolver = new LocalReviewBridgeResolverImpl(beans.getBeanProvider(LocalReviewService.class), mapper);
        List<LocalReviewView.Message> messages = IntStream.range(0, 200)
                .mapToObj(index -> new LocalReviewView.Message("message-" + index, Author.USER, "\u0001".repeat(16_000), Instant.EPOCH,
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()))
                .toList();
        List<LocalReviewView> threads = IntStream.range(0, 500)
                .mapToObj(index -> new LocalReviewView("comment-" + index, "task-1", "workspace-1", "owner/repo", 7,
                        "\u0001".repeat(4096), "RIGHT", 1, 2, Optional.empty(), Optional.empty(), "\u0001".repeat(60_000),
                        index != 499, 1, Instant.EPOCH, Instant.EPOCH, 0, messages, Optional.empty()))
                .toList();
        when(service.availableForAgent("task-1")).thenReturn(true);
        when(service.listForAgent("task-1")).thenReturn(threads);
        when(service.replyFromAgent("task-1", "comment-499", "Answer", "reply-1")).thenReturn(threads.getLast());
        LocalReviewBridge bridge = resolver.resolve("task-1").orElseThrow();

        String summary = bridge.list().apply(Optional.empty(), 0);
        assertThat(summary.length()).isLessThanOrEqualTo(LocalReviewBridgeResolverImpl.MAXIMUM_OUTPUT_LENGTH);
        assertThat(mapper.readTree(summary).at("/comments/0/id").asText()).isEqualTo("comment-499");
        assertThat(mapper.readTree(summary).path("omittedCommentCount").intValue()).isGreaterThan(0);
        assertThat(mapper.readTree(summary).at("/comments/0/latestMessage/bodyTruncated").booleanValue()).isTrue();
        String detail = bridge.list().apply(Optional.of("comment-499"), 0);
        assertThat(detail.length()).isLessThanOrEqualTo(LocalReviewBridgeResolverImpl.MAXIMUM_OUTPUT_LENGTH);
        assertThat(mapper.readTree(detail).path("codeTruncated").booleanValue()).isTrue();
        assertThat(mapper.readTree(detail).path("omittedMessageCount").intValue()).isGreaterThan(0);
        assertThat(mapper.readTree(detail).path("messages").get(mapper.readTree(detail).path("messages").size() - 1).path("id").asText()).isEqualTo("message-199");
        assertThat(mapper.readTree(detail).at("/messages/0/bodyTruncated").booleanValue()).isTrue();
        String mutation = bridge.reply().apply(new LocalReviewBridge.Reply("comment-499", "Answer", "reply-1"));
        assertThat(mutation.length()).isLessThan(500);
        assertThat(mapper.readTree(mutation).path("latestMessageId").asText()).isEqualTo("message-199");
        assertThat(mapper.readTree(mutation).has("messages")).isFalse();
    }

    private static AgentReview review(String id, Optional<String> sourceTaskId)
    {
        return openReview(id, "workspace-1", "owner/repo", OptionalInt.of(7), "task-of-" + id, sourceTaskId, Instant.EPOCH);
    }

    private static ReviewFinding finding(String id, String reviewId, int ordinal, ReviewFinding.State state, List<ReviewEvidence> evidence)
    {
        return new ReviewFinding(id, reviewId, "run", ordinal, "Title of " + id, "Body of " + id, Optional.of("medium"),
                Optional.of(new ReviewAnchor("Example.java", "RIGHT", 10, 11, ImmutableList.of("first", "second"))), evidence, 1, state,
                state == ReviewFinding.State.OPEN ? Optional.empty() : Optional.of("Reason"), "b".repeat(40), false, 0, Instant.EPOCH, Instant.EPOCH,
                ImmutableList.of(), ReviewFinding.Origin.AUTOMATIC);
    }

    private static ReviewMessage message(String id, ReviewMessage.Author author, Optional<String> developerTaskId)
    {
        return new ReviewMessage(id, "own", Optional.of("finding-2"), Optional.empty(), author, "Body of " + id, 1, "run", Optional.empty(), Optional.empty(), 0,
                Instant.EPOCH, developerTaskId);
    }
}
