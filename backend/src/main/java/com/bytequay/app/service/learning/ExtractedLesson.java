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
package com.bytequay.app.service.learning;

import com.google.common.collect.ImmutableSet;

import java.util.List;
import java.util.Set;

/**
 * One candidate lesson the extraction model distilled from a merged-PR
 * evidence bundle, already schema-validated: the kind is canonical, every
 * cited evidence id resolved to a bundle ref, and the route names a real
 * destination. Producing zero of these for a PR is an expected, correct
 * outcome — a normal implementation detail is not a durable lesson.
 *
 * @param evidenceRefs indexes into the bundle's stable refs the model cited
 * ({@code E1}, {@code E4}, …) resolved back to ref positions.
 * @param explicitSourceQuote true when the statement is explicit in the
 * source language (a reviewer or author actually said it), which the
 * restricted kinds require before automatic activation.
 * @param duplicateOf id of an existing knowledge item this repeats, when the
 * model recognized one from the provided nearby-knowledge list.
 * @param conflictsWith ids of existing knowledge items this contradicts.
 * @param route {@code knowledge} for repository knowledge (including
 * glossary), or {@code workspace-memory} for a cross-task operating decision
 * proposed to the workspace brain.
 * @param memoryKind DECISION or CONVENTION when routed to workspace memory.
 */
public record ExtractedLesson(
        String kind,
        String title,
        String statement,
        String rationale,
        List<String> modules,
        List<String> paths,
        List<String> symbols,
        List<String> concepts,
        List<String> audiences,
        List<Integer> evidenceRefs,
        boolean explicitSourceQuote,
        String confidence,
        String duplicateOf,
        List<String> conflictsWith,
        String route,
        String memoryKind)
{
    public static final Set<String> KINDS = ImmutableSet.of(
            "architecture-principle", "domain-invariant", "investigation-recipe",
            "recurring-concern", "design-rationale", "performance-assumption",
            "compatibility-contract", "glossary", "build-test-rule");

    /** Kinds that must not activate from a single diff-shaped inference:
     *  they need explicit source language or independent confirmation. */
    public static final Set<String> RESTRICTED_KINDS = ImmutableSet.of(
            "architecture-principle", "domain-invariant",
            "compatibility-contract", "design-rationale");

    public static final Set<String> CONFIDENCES = ImmutableSet.of("high", "medium", "low");
    public static final Set<String> ROUTES = ImmutableSet.of("knowledge", "workspace-memory");
}
