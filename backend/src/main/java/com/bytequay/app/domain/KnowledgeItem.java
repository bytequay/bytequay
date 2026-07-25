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
package com.bytequay.app.domain;

import java.util.List;

/**
 * One canonical repository-knowledge row ({@code knowledge_item}). The
 * lifecycle is {@code pending → active → decayed → retired}; only active
 * rows may influence an agent. {@code kind} is the knowledge kind
 * ({@code architecture-principle}, {@code compatibility-contract},
 * {@code investigation-recipe}, {@code glossary}, {@code doc-note}, …) and
 * maps to the {@code subtype} column; {@code lifecycle} maps to
 * {@code state}. Evidence lives in {@code knowledge_provenance} rows, never
 * inside the statement text.
 */
public record KnowledgeItem(
        String id,
        String workspaceId,
        String repo,
        String kind,
        String title,
        String statement,
        String rationale,
        List<String> audiences,
        String confidence,
        String lifecycle,
        String validatedAtCommit,
        Long lastVerifiedAtMs,
        String createdBy,
        String statementDigest,
        String countersJson,
        long createdAtMs,
        long updatedAtMs)
{
    public static final String LIFECYCLE_PENDING = "pending";
    public static final String LIFECYCLE_ACTIVE = "active";
    public static final String LIFECYCLE_DECAYED = "decayed";
    public static final String LIFECYCLE_RETIRED = "retired";

    public boolean isActive()
    {
        return LIFECYCLE_ACTIVE.equals(lifecycle);
    }

    /**
     * One evidence link ({@code knowledge_provenance} row). The
     * {@code (item, kind, ref)} key is what lets equivalent lessons from
     * later PRs merge provenance instead of duplicating items.
     */
    public record Provenance(
            String sourceKind,
            String sourceRef,
            String commitSha,
            String filePath,
            String url,
            String contentDigest) {}

    /** One structured applicability tag ({@code knowledge_applicability}
     *  row); {@code kind} is module | path | symbol | concept. */
    public record Applicability(String kind, String value) {}
}
