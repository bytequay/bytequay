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
import com.bytequay.agent.spi.LocalReviewBridgeResolver;
import com.bytequay.app.localreview.LocalReviewService;
import com.bytequay.app.localreview.LocalReviewView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Resolves lazily because the local discussion service also starts turns through the provider drivers. */
@Component
public class LocalReviewBridgeResolverImpl
        implements LocalReviewBridgeResolver
{
    static final int MAXIMUM_OUTPUT_LENGTH = 64_000;

    private final ObjectProvider<LocalReviewService> services;
    private final ObjectMapper objectMapper;

    public LocalReviewBridgeResolverImpl(ObjectProvider<LocalReviewService> services, ObjectMapper objectMapper)
    {
        this.services = requireNonNull(services, "services is null");
        this.objectMapper = requireNonNull(objectMapper, "objectMapper is null");
    }

    @Override
    public Optional<LocalReviewBridge> resolve(String taskId)
    {
        LocalReviewService service = services.getObject();
        if (!service.availableForAgent(taskId)) {
            return Optional.empty();
        }
        return Optional.of(new LocalReviewBridge(
                commentId -> list(service.listForAgent(taskId), commentId),
                reply -> mutation(service.replyFromAgent(taskId, reply.commentId(), reply.body(), reply.operationKey())),
                resolution -> mutation(service.resolveFromAgent(taskId, resolution.commentId(), resolution.resolved(), resolution.operationKey())),
                LocalReviewBridge.INSTRUCTION));
    }

    private String list(List<LocalReviewView> threads, Optional<String> commentId)
    {
        if (commentId.isPresent()) {
            LocalReviewView thread = threads.stream()
                    .filter(value -> value.id().equals(commentId.orElseThrow()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Local review comment does not belong to this task"));
            ObjectNode result = anchor(thread);
            excerpt(result, "code", thread.code(), 4000);
            ArrayNode messages = result.putArray("messages");
            result.put("omittedMessageCount", thread.messages().size());
            // Add newest messages first for the budget, but return the retained conversation in time order.
            for (int index = thread.messages().size() - 1; index >= Math.max(0, thread.messages().size() - 8); index--) {
                messages.insert(0, message(thread.messages().get(index), 1000));
                result.put("omittedMessageCount", index);
                if (json(result).length() > MAXIMUM_OUTPUT_LENGTH) {
                    messages.remove(0);
                    result.put("omittedMessageCount", index + 1);
                    break;
                }
            }
            return json(result);
        }
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode comments = result.putArray("comments");
        result.put("omittedCommentCount", threads.size());
        List<LocalReviewView> recent = threads.stream()
                .sorted(Comparator.comparing(LocalReviewView::resolved).thenComparing(LocalReviewView::updatedAt, Comparator.reverseOrder()))
                .limit(20)
                .toList();
        for (LocalReviewView thread : recent) {
            ObjectNode summary = anchor(thread);
            summary.put("messageCount", thread.messages().size());
            if (!thread.messages().isEmpty()) {
                summary.set("latestMessage", message(thread.messages().getLast(), 256));
            }
            comments.add(summary);
            result.put("omittedCommentCount", threads.size() - comments.size());
            if (json(result).length() > MAXIMUM_OUTPUT_LENGTH) {
                comments.remove(comments.size() - 1);
                result.put("omittedCommentCount", threads.size() - comments.size());
                break;
            }
        }
        return json(result);
    }

    private ObjectNode anchor(LocalReviewView thread)
    {
        return objectMapper.createObjectNode()
                .put("id", thread.id())
                .put("repo", thread.repo())
                .put("number", thread.number())
                .put("path", thread.path())
                .put("side", thread.side())
                .put("startLine", thread.startLine())
                .put("line", thread.line())
                .put("baseSha", thread.baseSha().orElse(null))
                .put("headSha", thread.headSha().orElse(null))
                .put("resolved", thread.resolved())
                .put("version", thread.version())
                .put("updatedAt", thread.updatedAt().toString());
    }

    private ObjectNode message(LocalReviewView.Message message, int limit)
    {
        ObjectNode result = objectMapper.createObjectNode()
                .put("id", message.id())
                .put("author", message.author().name())
                .put("createdAt", message.createdAt().toString());
        excerpt(result, "body", message.body(), limit);
        return result;
    }

    private static void excerpt(ObjectNode result, String field, String value, int limit)
    {
        result.put(field, value.substring(0, Math.min(limit, value.length())));
        result.put(field + "Truncated", value.length() > limit);
    }

    private String mutation(LocalReviewView thread)
    {
        ObjectNode result = objectMapper.createObjectNode()
                .put("id", thread.id())
                .put("resolved", thread.resolved())
                .put("version", thread.version())
                .put("messageCount", thread.messages().size());
        if (!thread.messages().isEmpty()) {
            result.put("latestMessageId", thread.messages().getLast().id());
        }
        return json(result);
    }

    private String json(Object value)
    {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("Unable to encode local review comments", failure);
        }
    }
}
