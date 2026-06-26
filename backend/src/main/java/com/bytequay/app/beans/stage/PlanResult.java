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
package com.bytequay.app.beans.stage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * The structured plan a PlanStage records — the brain agent produces it via
 * the {@code record_plan} tool, it persists as the {@code PLAN_RECORDED}
 * event payload, and the user reviews it before approving. Mirrors the
 * frontend {@code PlanResult} TypeScript contract one-to-one; unknown
 * fields are ignored so the wire format can grow without breaking parse.
 *
 * @param id             server-assigned id of this plan revision
 * @param status         {@code suggested} (trunk draft) or {@code finalized}
 * @param source         {@code trunk} / {@code brain} / {@code brain-revision}
 *                       / {@code brain-confirmation}
 * @param revisionOf     id of the previous revision, or null
 * @param revisionReason what prompted this revision, or null
 * @param plannedAt      server-assigned ISO timestamp
 * @param understanding  what the agent understands about the change
 * @param intent         what the agent intends to do
 * @param signals        risk / effort / value signals
 * @param uncertainAreas trunk-only: areas left for the brain to finalize
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanResult(
        String id,
        String status,
        String source,
        String revisionOf,
        String revisionReason,
        String plannedAt,
        /** One concise sentence naming the objective — the card headline. */
        String goal,
        Understanding understanding,
        Intent intent,
        Signals signals,
        List<String> uncertainAreas)
{
    /** "Proven" — what the agent understands about the change. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Understanding(
            String summary,
            List<AffectedComponent> affectedComponents,
            List<String> existingPatterns,
            List<String> constraints) {}

    /** One file/class touched by the change and its role in it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AffectedComponent(String path, String role) {}

    /** "Plan" — what the agent intends to do. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Intent(
            String summary,
            List<Step> steps,
            String validationStrategy,
            String pushStrategy) {}

    /** One ordinal-numbered, optionally file-scoped step the dev agent walks. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Step(int ordinal, String action, List<String> files, String rationale) {}

    /** Risk / effort / value signals shown as at-a-glance pills. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Signals(
            String riskLevel,
            List<String> riskNotes,
            int componentsCount,
            String estimatedComplexity,
            String expectedGain,
            /** Overall confidence the plan succeeds as written: high / medium / low. */
            String confidence) {}
}
