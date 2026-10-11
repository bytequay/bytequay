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

import com.bytequay.agent.spi.BridgeResolver;
import com.bytequay.agent.spi.LocalReviewBridge;
import com.bytequay.app.localreview.LocalReviewService;
import com.bytequay.app.localreview.LocalReviewService.AgentFinding;
import com.bytequay.app.localreview.LocalReviewView;
import com.bytequay.core.review.ReviewEvidence;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.review.ReviewMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Resolves lazily because the local discussion service also starts turns through the provider drivers. */
@Component
public class LocalReviewBridgeResolverImpl
        implements BridgeResolver<LocalReviewBridge>
{
    static final int MAXIMUM_OUTPUT_LENGTH = 64_000;
    /** Kept free of a finding's evidence, so its newest discussion is always returned. */
    private static final int DISCUSSION_RESERVE = 10_000;

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
        // A review task reads its findings through the review tools, so only a development task is told about them here.
        boolean development = service.readsFindings(taskId);
        return Optional.of(new LocalReviewBridge(
                commentId -> list(taskId, service.listForAgent(taskId), development ? Optional.of(service.findingsForAgent(taskId)) : Optional.empty(), commentId),
                reply -> service.replyToFindingFromAgent(taskId, reply.commentId(), reply.body(), reply.operationKey()).map(this::findingReply)
                        .orElseGet(() -> mutation(service.replyFromAgent(taskId, reply.commentId(), reply.body(), reply.operationKey()))),
                resolution -> {
                    if (service.findingsForAgent(taskId).stream().anyMatch(value -> value.finding().id().equals(resolution.commentId()))) {
                        throw new IllegalArgumentException("Only the user resolves a review finding, and only its reviewer withdraws one; reply with your result instead");
                    }
                    return mutation(service.resolveFromAgent(taskId, resolution.commentId(), resolution.resolved(), resolution.operationKey()));
                },
                development ? LocalReviewBridge.INSTRUCTION + LocalReviewBridge.FINDINGS_INSTRUCTION : LocalReviewBridge.INSTRUCTION));
    }

    private String list(String taskId, List<LocalReviewView> threads, Optional<List<AgentFinding>> findings, Optional<String> commentId)
    {
        if (commentId.isPresent()) {
            Optional<LocalReviewView> selected = threads.stream().filter(value -> value.id().equals(commentId.orElseThrow())).findFirst();
            if (selected.isEmpty()) {
                return findingDetail(taskId, findings.orElse(ImmutableList.of()).stream().filter(value -> value.finding().id().equals(commentId.orElseThrow())).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("No local review comment or review finding with this id is available to this task")));
            }
            LocalReviewView thread = selected.orElseThrow();
            ObjectNode result = anchor(thread);
            if (thread.learningAnchor().isEmpty()) {
                excerpt(result, "code", thread.code(), 4000);
            }
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
        findings.ifPresent(available -> findingSummaries(taskId, available, result));
        return json(result);
    }

    private void findingSummaries(String taskId, List<AgentFinding> findings, ObjectNode result)
    {
        ArrayNode summaries = result.putArray("findings");
        result.put("omittedFindingCount", findings.size());
        // Open findings first; within a state they keep their review and its numbering.
        List<AgentFinding> ordered = findings.stream().sorted(Comparator.comparing(value -> value.finding().state() != ReviewFinding.State.OPEN)).limit(40).toList();
        for (AgentFinding item : ordered) {
            ObjectNode summary = findingAnchor(taskId, item);
            summary.put("messageCount", item.messages().size());
            if (!item.messages().isEmpty()) {
                summary.set("latestMessage", findingMessage(taskId, item.messages().getLast(), 256));
            }
            summaries.add(summary);
            result.put("omittedFindingCount", findings.size() - summaries.size());
            if (json(result).length() > MAXIMUM_OUTPUT_LENGTH) {
                summaries.remove(summaries.size() - 1);
                result.put("omittedFindingCount", findings.size() - summaries.size());
                break;
            }
        }
    }

    private String findingDetail(String taskId, AgentFinding item)
    {
        ReviewFinding finding = item.finding();
        ObjectNode result = findingAnchor(taskId, item);
        // These limits fit the output even when escaping makes every character six.
        excerpt(result, "body", finding.body(), 6000);
        finding.stateReason().ifPresent(reason -> excerpt(result, "stateReason", reason, 500));
        finding.anchor().filter(anchor -> !anchor.excerpt().isEmpty()).ifPresent(anchor -> excerpt(result, "code", String.join("\n", anchor.excerpt()), 2000));
        ArrayNode evidence = result.putArray("evidence");
        result.put("omittedEvidenceCount", finding.evidence().size());
        for (ReviewEvidence saved : finding.evidence()) {
            ObjectNode entry = objectMapper.createObjectNode().put("source", saved.source()).put("provenance", saved.provenance());
            excerpt(entry, "excerpt", saved.excerpt(), 4000);
            excerpt(entry, "explanation", saved.explanation(), 1500);
            evidence.add(entry);
            result.put("omittedEvidenceCount", finding.evidence().size() - evidence.size());
            if (json(result).length() > MAXIMUM_OUTPUT_LENGTH - DISCUSSION_RESERVE) {
                evidence.remove(evidence.size() - 1);
                result.put("omittedEvidenceCount", finding.evidence().size() - evidence.size());
                break;
            }
        }
        ArrayNode messages = result.putArray("messages");
        result.put("omittedMessageCount", item.messages().size());
        for (int index = item.messages().size() - 1; index >= Math.max(0, item.messages().size() - 8); index--) {
            messages.insert(0, findingMessage(taskId, item.messages().get(index), 1000));
            result.put("omittedMessageCount", index);
            if (json(result).length() > MAXIMUM_OUTPUT_LENGTH) {
                messages.remove(0);
                result.put("omittedMessageCount", index + 1);
                break;
            }
        }
        return json(result);
    }

    private ObjectNode findingAnchor(String taskId, AgentFinding item)
    {
        ReviewFinding finding = item.finding();
        ObjectNode result = objectMapper.createObjectNode()
                .put("id", finding.id())
                .put("label", finding.label())
                .put("reviewId", item.review().id())
                .put("reviewOf", item.review().sourceTaskId().map(source -> source.equals(taskId) ? "THIS_TASK" : "ANOTHER_TASK").orElse("PULL_REQUEST"))
                .put("repo", item.review().repo())
                .put("title", clip(finding.title(), 300))
                .put("state", finding.state().name())
                .put("outdated", finding.outdated())
                .put("revision", finding.revision())
                .put("validatedHeadSha", finding.validatedHeadSha())
                .put("updatedAt", finding.updatedAt().toString());
        item.review().number().ifPresent(number -> result.put("number", number));
        finding.severity().ifPresent(severity -> result.put("severity", severity));
        finding.anchor().ifPresent(anchor -> result.put("path", clip(anchor.path(), 500)).put("side", anchor.side())
                .put("startLine", anchor.startLine()).put("line", anchor.endLine()));
        return result;
    }

    private ObjectNode findingMessage(String taskId, ReviewMessage message, int limit)
    {
        ObjectNode result = objectMapper.createObjectNode()
                .put("id", message.id())
                .put("author", message.developerTaskId().isPresent() ? "DEVELOPER" : message.author() == ReviewMessage.Author.USER ? "USER" : "REVIEWER")
                .put("createdAt", message.createdAt().toString());
        message.developerTaskId().ifPresent(developer -> result.put("developer", developer.equals(taskId) ? "THIS_TASK" : "ANOTHER_TASK"));
        excerpt(result, "body", message.body(), limit);
        return result;
    }

    private String findingReply(ReviewMessage reply)
    {
        return json(objectMapper.createObjectNode().put("id", reply.findingId().orElseThrow()).put("latestMessageId", reply.id()));
    }

    private ObjectNode anchor(LocalReviewView thread)
    {
        ObjectNode result = objectMapper.createObjectNode()
                .put("id", thread.id())
                .put("repo", thread.repo())
                .put("resolved", thread.resolved())
                .put("version", thread.version())
                .put("updatedAt", thread.updatedAt().toString());
        if (thread.learningAnchor().isPresent()) {
            var anchor = thread.learningAnchor().orElseThrow();
            ObjectNode learning = result.putObject("learningAnchor").put("turnId", anchor.turnId()).put("cardId", anchor.cardId()).put("title", anchor.title());
            excerpt(learning, "body", anchor.body(), 4000);
        }
        else {
            result.put("number", thread.number()).put("path", thread.path()).put("side", thread.side())
                    .put("startLine", thread.startLine()).put("line", thread.line())
                    .put("baseSha", thread.baseSha().orElse(null)).put("headSha", thread.headSha().orElse(null));
        }
        return result;
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
        result.put(field, clip(value, limit));
        result.put(field + "Truncated", value.length() > limit);
    }

    private static String clip(String value, int limit)
    {
        return value.substring(0, Math.min(limit, value.length()));
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
