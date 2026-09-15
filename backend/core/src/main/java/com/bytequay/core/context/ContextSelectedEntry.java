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
package com.bytequay.core.context;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Captured note text or a deferred version reference, with the reasons it was selected. */
public record ContextSelectedEntry(
        String entryId,
        long entryVersion,
        String title,
        Optional<String> text,
        Map<String, String> sourceEvidenceRefs,
        List<String> matchReasons,
        Applicability applicability,
        Inclusion inclusion)
{
    public ContextSelectedEntry
    {
        ContextLimits.text(entryId, "selected entry id", 1200);
        checkArgument(entryVersion > 0, "Selected entry version must be positive");
        ContextLimits.text(title, "selected entry title", 200);
        requireNonNull(text, "text is null");
        text.ifPresent(value -> ContextLimits.text(value, "selected entry text", ContextLimits.MAX_BODY_BYTES));
        sourceEvidenceRefs = ImmutableMap.copyOf(sourceEvidenceRefs);
        checkArgument(sourceEvidenceRefs.size() <= ContextLimits.MAX_SOURCES, "Too many selected entry sources");
        sourceEvidenceRefs.forEach((source, evidence) -> {
            ContextLimits.text(source, "notebook source id", 500);
            ContextLimits.text(evidence, "run evidence reference", 200);
        });
        matchReasons = ImmutableList.copyOf(matchReasons);
        checkArgument(!matchReasons.isEmpty() && matchReasons.size() <= 41, "Selected entry needs bounded matching reasons");
        matchReasons.forEach(reason -> ContextLimits.text(reason, "matching reason", 2000));
        requireNonNull(applicability, "applicability is null");
        requireNonNull(inclusion, "inclusion is null");
        checkArgument((inclusion == Inclusion.INCLUDED) == text.isPresent(), "Only included entries retain selected text");
    }

    public enum Applicability
    {
        MATCHED,
        VERSION_MISMATCH,
        UNVERIFIED
    }

    /** Inclusion in a prepared packet is not proof that a provider received it. */
    public enum Inclusion
    {
        INCLUDED,
        DEFERRED
    }
}
