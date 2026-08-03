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
package com.bytequay.app.developmentflow;

import com.bytequay.app.developmentflow.task.TaskManager;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * The verdict a reviewing Brain turn reports as its result.
 *
 * <p>One declaration, deliberately. This shape and its two rules — APPROVED
 * carries no findings, CHANGES_REQUESTED carries at least one — used to be
 * written out four separate times: once in the pre-delivery gate and once in
 * each of the three runtimes that consume a verdict. Four copies of a contract
 * drift, and a rule that holds in three of four places is worse than one that
 * holds nowhere, because the gap is invisible until a Turn is thrown away.
 *
 * <p>Still {@code finalText}-shaped: a reviewing Turn reports by formatting
 * JSON into its final message rather than by calling a result tool the way a
 * Local Development Turn now does. Converting it is a separate change, and
 * having one contract to convert instead of four is the point of this one.
 */
public record AgentBrainResult(
        int schemaVersion,
        String verdict,
        String summary,
        List<String> findings)
{
    private static final String APPROVED = "APPROVED";
    private static final String CHANGES_REQUESTED = "CHANGES_REQUESTED";

    public AgentBrainResult
    {
        findings = List.copyOf(requireNonNull(findings, "findings is null"));
    }

    /**
     * The reader every caller must use. Unknown properties, trailing tokens and
     * duplicate keys are all rejected — a Turn that reports a field we do not
     * know is reporting against some other contract.
     */
    public static ObjectReader reader(ObjectMapper json)
    {
        // Coercion off, on a copy so the shared mapper is untouched. Jackson
        // will otherwise read {"schemaVersion":"1"} as 1 and a numeric summary
        // as its digits, which turns a Turn reporting the wrong types into one
        // that appears to have reported correctly.
        ObjectMapper strict = requireNonNull(json, "json is null").copy();
        strict.coercionConfigFor(LogicalType.Integer)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        strict.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        return strict.readerFor(AgentBrainResult.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    /**
     * Parse and validate one reported verdict.
     *
     * @param label names the reporting Turn in every failure message, e.g.
     *              {@code "Development Brain"}. Callers concatenate the message
     *              into a failure detail or a retry brief; nothing matches on
     *              its wording, so it is written for whoever reads it next.
     */
    public static AgentBrainResult decode(
            ObjectReader reader, String value, String label)
    {
        requireNonNull(reader, "reader is null");
        requireNonNull(label, "label is null");
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " result is missing");
        }
        AgentBrainResult result;
        try {
            result = reader.readValue(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    label + " result is not strict JSON", e);
        }
        if (result.schemaVersion() != 1) {
            throw new IllegalArgumentException(
                    "Unsupported " + label + " result version");
        }
        if (result.summary() == null || result.summary().isBlank()) {
            throw new IllegalArgumentException(label + " summary is missing");
        }
        if (result.findings().stream()
                .anyMatch(finding -> finding == null || finding.isBlank())) {
            throw new IllegalArgumentException(
                    label + " findings must be non-blank strings");
        }
        return result;
    }

    /**
     * The typed verdict, once its findings agree with it. Split from
     * {@link #decode} because the gate validates without consuming, while the
     * runtimes consume what they validate.
     */
    public TaskManager.BrainVerdict requireVerdict(String label)
    {
        TaskManager.BrainVerdict typed = switch (verdict == null ? "" : verdict) {
            case APPROVED -> TaskManager.BrainVerdict.APPROVED;
            case CHANGES_REQUESTED -> TaskManager.BrainVerdict.CHANGES_REQUESTED;
            default -> throw new IllegalArgumentException(
                    "Unknown " + label + " verdict: " + verdict);
        };
        if (typed == TaskManager.BrainVerdict.APPROVED && !findings.isEmpty()
                || typed == TaskManager.BrainVerdict.CHANGES_REQUESTED
                    && findings.isEmpty()) {
            throw new IllegalArgumentException(
                    label + " verdict and findings disagree");
        }
        return typed;
    }
}
