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
package com.bytequay.app.service.concepts;

import com.google.common.collect.ImmutableList;

import java.util.List;

/**
 * Resolved view of one {@link Concept} — what the registry hands
 * out to the meta-tools and to {@code @ToolParam(enumFromConcepts)}
 * schema generation. The {@link #aka}, {@link #examples},
 * {@link #relatedTools}, and {@link #relatedConcepts} arrays are
 * defensively copied to {@link ImmutableList} so callers can't
 * mutate the registry's view.
 *
 * <p>{@link #source} traces the spec back to where it was
 * declared — a fully-qualified type name for a class concept, a
 * {@code Type#methodName} or {@code Type#fieldName} for member
 * concepts, or a {@code file://…#term} URI for glossary / user
 * scopes. The meta-tools surface this so an agent (or a curious
 * human) can audit where a definition came from.
 */
public record ConceptSpec(
        String name,
        List<String> aka,
        ConceptKind kind,
        String definition,
        List<String> examples,
        List<String> relatedTools,
        List<String> relatedConcepts,
        ConceptScope scope,
        String source)
{
    /** Compact constructor — coerces null arrays to empty
     *  immutable lists so callers don't have to null-check. */
    public ConceptSpec
    {
        aka = aka == null ? List.of() : ImmutableList.copyOf(aka);
        examples = examples == null ? List.of() : ImmutableList.copyOf(examples);
        relatedTools = relatedTools == null ? List.of() : ImmutableList.copyOf(relatedTools);
        relatedConcepts = relatedConcepts == null ? List.of() : ImmutableList.copyOf(relatedConcepts);
    }

    /** First sentence (or first 120 chars, whichever is shorter) of
     *  the definition — what {@code list_terms} returns in the
     *  manifest so the per-entry payload stays small. */
    public String oneLineDefinition()
    {
        if (definition == null || definition.isEmpty()) {
            return "";
        }
        int dot = definition.indexOf('.');
        int newline = definition.indexOf('\n');
        int firstStop = -1;
        if (dot >= 0) {
            firstStop = dot;
        }
        if (newline >= 0 && (firstStop < 0 || newline < firstStop)) {
            firstStop = newline;
        }
        String head = firstStop >= 0 ? definition.substring(0, firstStop) : definition;
        return head.length() > 120 ? head.substring(0, 117) + "…" : head;
    }
}
