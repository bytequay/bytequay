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
import com.bytequay.app.localreview.LocalReviewView;
import com.bytequay.core.localreview.LocalReviewMessage.Author;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
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
                Instant.EPOCH, Instant.EPOCH, ImmutableList.of());
        when(service.availableForAgent("task-1")).thenReturn(true);
        when(service.listForAgent("task-1")).thenReturn(ImmutableList.of(thread));
        when(service.replyFromAgent("task-1", "comment-1", "Answer", "reply-1")).thenReturn(thread);
        when(service.resolveFromAgent("task-1", "comment-1", false, "reopen-1")).thenReturn(thread);

        assertThat(resolver.resolve("other-task")).isEmpty();
        LocalReviewBridge bridge = resolver.resolve("task-1").orElseThrow();
        assertThat(mapper.readTree(bridge.list().apply(Optional.empty())).path("comments").get(0).path("id").asText()).isEqualTo("comment-1");
        assertThat(mapper.readTree(bridge.list().apply(Optional.of("comment-1"))).path("code").asText()).isEqualTo("selected code");
        assertThatThrownBy(() -> bridge.list().apply(Optional.of("foreign-comment")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not belong to this task");
        assertThat(mapper.readTree(bridge.reply().apply(new LocalReviewBridge.Reply("comment-1", "Answer", "reply-1"))).path("id").asText()).isEqualTo("comment-1");
        assertThat(mapper.readTree(bridge.resolve().apply(new LocalReviewBridge.Resolution("comment-1", false, "reopen-1"))).has("operations")).isFalse();
        verify(service).replyFromAgent("task-1", "comment-1", "Answer", "reply-1");
        verify(service).resolveFromAgent("task-1", "comment-1", false, "reopen-1");
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
                        index != 499, 1, Instant.EPOCH, Instant.EPOCH, messages))
                .toList();
        when(service.availableForAgent("task-1")).thenReturn(true);
        when(service.listForAgent("task-1")).thenReturn(threads);
        when(service.replyFromAgent("task-1", "comment-499", "Answer", "reply-1")).thenReturn(threads.getLast());
        LocalReviewBridge bridge = resolver.resolve("task-1").orElseThrow();

        String summary = bridge.list().apply(Optional.empty());
        assertThat(summary.length()).isLessThanOrEqualTo(LocalReviewBridgeResolverImpl.MAXIMUM_OUTPUT_LENGTH);
        assertThat(mapper.readTree(summary).at("/comments/0/id").asText()).isEqualTo("comment-499");
        assertThat(mapper.readTree(summary).path("omittedCommentCount").intValue()).isGreaterThan(0);
        assertThat(mapper.readTree(summary).at("/comments/0/latestMessage/bodyTruncated").booleanValue()).isTrue();
        String detail = bridge.list().apply(Optional.of("comment-499"));
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
}
