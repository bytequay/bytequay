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
import com.bytequay.agent.spi.DevelopmentResultBridge;
import com.bytequay.agent.spi.DevelopmentResultReport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;

import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;

/** Strict local tool input and receipt decoding, shared by the two native provider lanes. */
public final class DevelopmentResultTool
{
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> ARGUMENTS = ImmutableSet.of("readyForDelivery", "message");

    private DevelopmentResultTool() {}

    public static DevelopmentResultReport parse(JsonNode arguments)
    {
        checkArgument(arguments != null && arguments.isObject(), "arguments must be an object");
        arguments.fieldNames().forEachRemaining(name -> checkArgument(ARGUMENTS.contains(name), "unknown argument: %s", name));
        checkArgument(arguments.path("readyForDelivery").isBoolean(), "readyForDelivery must be a boolean");
        Optional<String> message = text(arguments, "message");
        return new DevelopmentResultReport(arguments.get("readyForDelivery").booleanValue(), message);
    }

    public static String receipt(DevelopmentResultBridge bridge, DevelopmentResultReport report)
    {
        var result = MAPPER.createObjectNode().put("turnId", bridge.turnId());
        var body = result.putObject("report").put("readyForDelivery", report.readyForDelivery());
        report.message().ifPresent(value -> body.put("message", value));
        return result.toString();
    }

    /** Only a driver-verified successful root tool completion may consume this receipt. */
    public static Optional<DevelopmentResultReport> readReceipt(JsonNode content, DevelopmentResultBridge bridge, SecretRedactor redactor)
    {
        if (content == null || content.path("isError").asBoolean(false)) {
            return Optional.empty();
        }
        JsonNode blocks = content.isObject() ? content.path("content") : content;
        JsonNode text = blocks.isArray() && blocks.size() == 1 && blocks.get(0).path("type").asText().equals("text")
                ? blocks.get(0).path("text") : blocks;
        if (!text.isTextual() || text.textValue().length() > 32_000) {
            return Optional.empty();
        }
        try {
            JsonNode receipt = MAPPER.readTree(text.textValue());
            if (receipt == null || !receipt.isObject() || receipt.size() != 2
                    || !receipt.path("turnId").isTextual() || !receipt.path("turnId").textValue().equals(bridge.turnId()) || !receipt.has("report")) {
                return Optional.empty();
            }
            DevelopmentResultReport report = parse(receipt.get("report"));
            return Optional.of(new DevelopmentResultReport(report.readyForDelivery(), report.message().map(redactor::redact)));
        }
        catch (JsonProcessingException | IllegalArgumentException failure) {
            return Optional.empty();
        }
    }

    private static Optional<String> text(JsonNode arguments, String name)
    {
        if (!arguments.has(name)) {
            return Optional.empty();
        }
        checkArgument(arguments.get(name).isTextual(), "%s must be a string", name);
        return Optional.of(arguments.get(name).textValue());
    }
}
