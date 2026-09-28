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
import static java.util.Objects.requireNonNull;

/** One sourced note; knowledge may be an overview or a focused topic within its scope. */
public record ContextEntry(
        String id,
        ContextEntryKind kind,
        String scopeId,
        String title,
        String summary,
        String body,
        List<ContextClaim> claims,
        List<String> sourceIds,
        ContextEntryOrigin origin,
        boolean enabled,
        long version)
{
    public ContextEntry
    {
        ContextLimits.text(id, "entry id", 1200);
        requireNonNull(kind, "kind is null");
        ContextLimits.text(scopeId, "scope id", 1100);
        ContextLimits.text(title, "title", 200);
        ContextLimits.text(summary, "summary", 1000);
        ContextLimits.text(body, "body", ContextLimits.MAX_BODY_BYTES);
        claims = ImmutableList.copyOf(claims);
        sourceIds = ImmutableList.copyOf(sourceIds);
        ContextLimits.unique(sourceIds, "entry sources", ContextLimits.MAX_SOURCES);
        checkArgument(claims.size() <= 12, "Too many claims");
        Set<String> retainedSources = ImmutableSet.copyOf(sourceIds);
        checkArgument(claims.stream().allMatch(claim -> retainedSources.containsAll(claim.sourceIds())), "Claim sources must belong to the entry");
        requireNonNull(origin, "origin is null");
        checkArgument(version > 0, "Entry version must be positive");
        checkArgument(kind != ContextEntryKind.KNOWLEDGE || belongsToKnowledgeScope(id, scopeId),
                "Knowledge id must belong to scope %s", scopeId);
        checkArgument(kind != ContextEntryKind.MEMORY || origin == ContextEntryOrigin.USER, "Memory must be user-owned");
        checkArgument(kind != ContextEntryKind.MEMORY || !sourceIds.isEmpty(), "Memory needs retained evidence");
        checkArgument(origin != ContextEntryOrigin.GENERATED || !sourceIds.isEmpty(), "Generated knowledge needs retained evidence");
    }

    public static String knowledgeId(String scopeId)
    {
        return "knowledge:" + scopeId;
    }

    public static boolean belongsToKnowledgeScope(String id, String scopeId)
    {
        String overview = knowledgeId(scopeId);
        return id.equals(overview) || (id.startsWith(overview + ":") && id.length() > overview.length() + 1);
    }

    public long proseBytes()
    {
        return ContextLimits.bytes(title) + ContextLimits.bytes(summary) + ContextLimits.bytes(body)
                + claims.stream().mapToLong(claim -> ContextLimits.bytes(claim.text())).sum();
    }
}
