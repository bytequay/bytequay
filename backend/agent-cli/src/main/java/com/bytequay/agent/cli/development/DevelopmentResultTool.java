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
package com.bytequay.agent.cli.development;

import com.bytequay.agent.cli.process.SecretRedactor;
import com.bytequay.agent.spi.DevelopmentLearningCommit;
import com.bytequay.agent.spi.DevelopmentLearningSummary;
import com.bytequay.agent.spi.DevelopmentResultBridge;
import com.bytequay.agent.spi.DevelopmentResultReport;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;

/** Strict local tool input and receipt decoding, shared by the two native provider lanes. */
public final class DevelopmentResultTool
{
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new Jdk8Module()).setSerializationInclusion(JsonInclude.Include.NON_ABSENT);
    private static final Set<String> ARGUMENTS = ImmutableSet.of("readyForDelivery", "message", "learningSummary");
    private static final Set<String> SUMMARY_FIELDS = ImmutableSet.of("purpose", "commits");
    private static final Set<String> COMMIT_FIELDS = ImmutableSet.of("sha", "title", "summary");
    private static final int MAXIMUM_RECEIPT_LENGTH = 512_000;

    private DevelopmentResultTool() {}

    public static DevelopmentResultReport parse(JsonNode arguments)
    {
        object(arguments, ARGUMENTS);
        checkArgument(arguments.path("readyForDelivery").isBoolean(), "readyForDelivery must be a boolean");
        return new DevelopmentResultReport(arguments.get("readyForDelivery").booleanValue(), text(arguments, "message"),
                arguments.has("learningSummary") ? Optional.of(summary(arguments.get("learningSummary"))) : Optional.empty());
    }

    public static DevelopmentResultReport parse(JsonNode arguments, DevelopmentResultBridge bridge)
    {
        DevelopmentResultReport report = parse(arguments);
        checkArgument(bridge.learningSummaryAllowed() || report.learningSummary().isEmpty(), "learningSummary is only accepted during summary refinement");
        return report;
    }

    public static Map<String, Object> schema(DevelopmentResultBridge bridge)
    {
        ImmutableMap.Builder<String, Object> properties = ImmutableMap.<String, Object>builder()
                .put("readyForDelivery", ImmutableMap.of("type", "boolean"))
                .put("message", string(DevelopmentResultReport.MAXIMUM_MESSAGE_LENGTH));
        if (bridge.learningSummaryAllowed()) {
            properties.put("learningSummary", ImmutableMap.of(
                    "type", "object",
                    "properties", ImmutableMap.of(
                            "purpose", string(DevelopmentLearningSummary.MAXIMUM_PURPOSE_LENGTH),
                            "commits", ImmutableMap.of("type", "array", "maxItems", DevelopmentLearningSummary.MAXIMUM_COMMITS,
                                    "items", ImmutableMap.of("type", "object", "properties", ImmutableMap.of(
                                            "sha", ImmutableMap.of("type", "string", "pattern", "^(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})$"),
                                            "title", string(DevelopmentLearningCommit.MAXIMUM_TITLE_LENGTH),
                                            "summary", string(DevelopmentLearningCommit.MAXIMUM_SUMMARY_LENGTH)),
                                            "required", ImmutableList.of("sha", "title", "summary"), "additionalProperties", false))),
                    "required", ImmutableList.of("purpose", "commits"), "additionalProperties", false,
                    "description", "Explain the task's intention and each actual commit's design and implementation; at most 64000 total text characters."));
        }
        return ImmutableMap.of("type", "object", "properties", properties.buildOrThrow(), "required", ImmutableList.of("readyForDelivery"), "additionalProperties", false);
    }

    public static String receipt(DevelopmentResultBridge bridge, DevelopmentResultReport report)
    {
        checkArgument(bridge.learningSummaryAllowed() || report.learningSummary().isEmpty(), "learningSummary is only accepted during summary refinement");
        var result = MAPPER.createObjectNode().put("turnId", bridge.turnId());
        ObjectNode reported = MAPPER.valueToTree(report);
        if (reported.get("learningSummary") instanceof ObjectNode summary) {
            summary.retain(SUMMARY_FIELDS);
        }
        result.set("report", reported);
        return result.toString();
    }

    public static DevelopmentResultReport redact(DevelopmentResultReport report, SecretRedactor redactor)
    {
        return new DevelopmentResultReport(report.readyForDelivery(), report.message().map(redactor::redact), report.learningSummary().map(summary ->
                new DevelopmentLearningSummary(redactor.redact(summary.purpose()), summary.commits().stream()
                        .map(commit -> new DevelopmentLearningCommit(redactor.redact(commit.sha()), redactor.redact(commit.title()), redactor.redact(commit.summary())))
                        .collect(toImmutableList()), summary.combinedResult().map(redactor::redact), redactor.redact(summary.evidence()),
                        summary.limitations().stream().map(redactor::redact).collect(toImmutableList()))));
    }

    /** Only a driver-verified successful root tool completion may consume this receipt. */
    public static Optional<DevelopmentResultReport> readReceipt(JsonNode content, DevelopmentResultBridge bridge, SecretRedactor redactor)
    {
        if (content == null || content.path("isError").asBoolean(false)) {
            return Optional.empty();
        }
        JsonNode blocks = content.isObject() ? content.path("content") : content;
        JsonNode text = blocks.isArray() && blocks.size() == 1 && blocks.get(0).path("type").asText().equals("text") ? blocks.get(0).path("text") : blocks;
        if (!text.isTextual() || text.textValue().length() > MAXIMUM_RECEIPT_LENGTH) {
            return Optional.empty();
        }
        try {
            JsonNode receipt = MAPPER.readTree(text.textValue());
            if (receipt == null || !receipt.isObject() || receipt.size() != 2
                    || !receipt.path("turnId").isTextual() || !receipt.path("turnId").textValue().equals(bridge.turnId()) || !receipt.has("report")) {
                return Optional.empty();
            }
            return Optional.of(redact(parse(receipt.get("report"), bridge), redactor));
        }
        catch (JsonProcessingException | IllegalArgumentException failure) {
            return Optional.empty();
        }
    }

    private static DevelopmentLearningSummary summary(JsonNode value)
    {
        object(value, SUMMARY_FIELDS);
        JsonNode commits = value.path("commits");
        checkArgument(commits.isArray() && commits.size() <= DevelopmentLearningSummary.MAXIMUM_COMMITS, "commits must be a bounded array");
        ImmutableList.Builder<DevelopmentLearningCommit> explanations = ImmutableList.builder();
        commits.forEach(commit -> {
            object(commit, COMMIT_FIELDS);
            explanations.add(new DevelopmentLearningCommit(requiredText(commit, "sha"), requiredText(commit, "title"), requiredText(commit, "summary")));
        });
        return new DevelopmentLearningSummary(requiredText(value, "purpose"), explanations.build(), Optional.empty(), "", ImmutableList.of());
    }

    private static void object(JsonNode value, Set<String> fields)
    {
        checkArgument(value != null && value.isObject(), "arguments must be an object");
        value.fieldNames().forEachRemaining(name -> checkArgument(fields.contains(name), "unknown argument: %s", name));
    }

    private static Optional<String> text(JsonNode arguments, String name)
    {
        if (!arguments.has(name)) {
            return Optional.empty();
        }
        checkArgument(arguments.get(name).isTextual(), "%s must be a string", name);
        return Optional.of(arguments.get(name).textValue());
    }

    private static String requiredText(JsonNode arguments, String name)
    {
        return text(arguments, name).orElseThrow(() -> new IllegalArgumentException(name + " is required"));
    }

    private static Map<String, Object> string(int maximumLength)
    {
        return ImmutableMap.of("type", "string", "minLength", 1, "maxLength", maximumLength);
    }
}
