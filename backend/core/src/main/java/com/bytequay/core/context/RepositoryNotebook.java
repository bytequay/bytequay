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
import com.google.common.collect.ImmutableSet;

import java.util.List;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.stream.Collectors.toSet;

/** Current notebook content. Execution receipts are added with preparation, not speculative placeholders. */
public record RepositoryNotebook(
        int schemaVersion,
        long revision,
        boolean useInReviews,
        List<RegisteredContextSource> sources,
        List<ContextScope> scopes,
        List<ContextEntry> entries,
        List<String> suppressedKnowledgeIds,
        List<ContextSourceEvidence> sourceEvidence)
{
    public RepositoryNotebook
    {
        checkArgument(schemaVersion == 1, "Unsupported notebook schema");
        checkArgument(revision >= 0, "Negative notebook revision");
        sources = ImmutableList.copyOf(sources);
        scopes = ImmutableList.copyOf(scopes);
        entries = ImmutableList.copyOf(entries);
        suppressedKnowledgeIds = ImmutableList.copyOf(suppressedKnowledgeIds);
        sourceEvidence = ImmutableList.copyOf(sourceEvidence);
        ContextLimits.unique(sources.stream().map(RegisteredContextSource::id).toList(), "sources", 100);
        ContextLimits.unique(scopes.stream().map(ContextScope::id).toList(), "scopes", 100);
        ContextLimits.unique(entries.stream().map(ContextEntry::id).toList(), "entries", ContextLimits.MAX_ENTRIES);
        ContextLimits.unique(suppressedKnowledgeIds, "suppression", 100);
        ContextLimits.unique(sourceEvidence.stream().map(ContextSourceEvidence::id).toList(), "source evidence", 800);
        Set<String> scopeIds = scopes.stream().map(ContextScope::id).collect(toSet());
        Set<String> evidenceIds = sourceEvidence.stream().map(ContextSourceEvidence::id).collect(toSet());
        checkArgument(scopeIds.contains("project"), "Project scope is missing");
        checkArgument(entries.stream().allMatch(entry -> scopeIds.contains(entry.scopeId())), "Entry scope is missing");
        checkArgument(entries.stream().allMatch(entry -> evidenceIds.containsAll(entry.sourceIds())), "Entry evidence is missing");
        checkArgument(suppressedKnowledgeIds.stream().allMatch(id -> scopeIds.stream().anyMatch(scope -> id.equals(ContextEntry.knowledgeId(scope)))),
                "Invalid knowledge suppression");
        Set<String> suppressed = ImmutableSet.copyOf(suppressedKnowledgeIds);
        checkArgument(entries.stream().noneMatch(entry -> suppressed.contains(entry.id())), "Suppressed entry still exists");
        long proseBytes = entries.stream().mapToLong(ContextEntry::proseBytes).sum()
                + sourceEvidence.stream().mapToLong(source -> ContextLimits.bytes(source.excerpt()) + ContextLimits.bytes(source.provenance())).sum();
        checkArgument(proseBytes <= ContextLimits.MAX_PROSE_BYTES, "Notebook prose exceeds UTF-8 limit");
    }

    public static RepositoryNotebook empty()
    {
        return new RepositoryNotebook(1, 0, true, ImmutableList.of(), ImmutableList.of(ContextScope.project()), ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
    }
}
