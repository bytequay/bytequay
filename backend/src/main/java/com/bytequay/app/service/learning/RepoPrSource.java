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

/**
 * One cataloged merged pull request — a {@code repo_pr_source} row. Cheap
 * selection/reproducibility fields only; git already holds the diff and
 * commit history. The idempotency key is
 * {@code (workspace_id, repo, pr_number, source_digest, extractor_version)}
 * so re-cataloging the same source produces no duplicate.
 */
public record RepoPrSource(
        String workspaceId,
        String repo,
        int prNumber,
        String mergedAt,
        String mergeSha,
        String metadataJson,
        String completenessJson,
        String sourceDigest,
        Double priorityScore,
        String analysisState,
        int extractorVersion,
        Long analyzedAtMs,
        String lastError) {}
