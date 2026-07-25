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
package com.bytequay.app.service.tools;

import com.bytequay.app.domain.KnowledgeItem;
import com.bytequay.app.domain.MemoryItem;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.repository.sqlite.KnowledgeItemStore;
import com.bytequay.app.service.concepts.ConceptRegistry;
import com.bytequay.app.service.concepts.ConceptSpec;
import com.bytequay.app.service.learning.KnowledgeRetrievalService;
import com.bytequay.app.service.learning.ProjectLearningRun;
import com.bytequay.app.service.learning.ProjectLearningStore;
import com.bytequay.app.service.workspaces.WorkspaceRepositoryResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * The provider-neutral front door into Project Intelligence: one question in,
 * typed reference manifests out — active knowledge, live memory, document
 * sections, evidence pointers, and glossary concepts, each with enough
 * provenance to look up the exact source. Never a synthesized answer, and
 * never pending/decayed/retired knowledge.
 */
@Component
public class ProjectIntelligenceToolHandlers
{
    private static final int KNOWLEDGE_LIMIT = 6;
    private static final int MEMORY_LIMIT = 4;
    private static final int DOCS_LIMIT = 4;
    private static final int EVIDENCE_LIMIT = 4;
    private static final int CONCEPT_LIMIT = 3;

    private final KnowledgeRetrievalService retrieval;
    private final KnowledgeItemStore knowledge;
    private final ConceptRegistry concepts;
    private final ThreadStore threadStore;
    private final WorkspaceRepositoryResolver repositories;
    private final WatchedRepoStore watchedRepos;
    private final ProjectLearningStore learningRuns;
    private final ObjectMapper mapper;

    public ProjectIntelligenceToolHandlers(
            KnowledgeRetrievalService retrieval,
            KnowledgeItemStore knowledge,
            ConceptRegistry concepts,
            ThreadStore threadStore,
            WorkspaceRepositoryResolver repositories,
            WatchedRepoStore watchedRepos,
            ProjectLearningStore learningRuns,
            ObjectMapper mapper)
    {
        this.retrieval = requireNonNull(retrieval, "retrieval is null");
        this.knowledge = requireNonNull(knowledge, "knowledge is null");
        this.concepts = requireNonNull(concepts, "concepts is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.repositories = requireNonNull(repositories, "repositories is null");
        this.watchedRepos = requireNonNull(watchedRepos, "watchedRepos is null");
        this.learningRuns = requireNonNull(learningRuns, "learningRuns is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    public record ExploreProjectArgs(
            @ToolParam(description = "What you want to know about this project: a term, "
                    + "a subsystem, a constraint, or a how-does-X-work question.",
                    required = true)
            String question) {}

    @AgentTool(
            name = "explore_project",
            description = "Ask one question about this project and get typed references: "
                    + "active project knowledge (with PR/doc provenance), live workspace "
                    + "memory, indexed documentation sections, related merged-PR evidence, "
                    + "and glossary terms. Returns references to read, not a prose answer.",
            whenToUse = "First stop for what-does-X-mean, how-does-X-work, or "
                    + "were-there-prior-decisions questions before deep file reading. "
                    + "Follow the returned refs with read_file / lookup_memory / "
                    + "codegraph_explore for exact detail.",
            security = SecurityType.MEMORY_READ,
            gating = Gating.AUTO,
            roles = {AgentRole.TRUNK, AgentRole.TASK, AgentRole.REVIEWER})
    public ToolOutcome exploreProject(ExploreProjectArgs args, ToolCall call)
    {
        if (args.question() == null || args.question().isBlank()) {
            return ToolOutcome.Completed.error("question is required");
        }
        Optional<Thread> thread = Optional.ofNullable(call.threadId())
                .filter(id -> !id.isBlank())
                .flatMap(threadStore::findThreadById);
        if (thread.isEmpty()) {
            return ToolOutcome.Completed.error(
                    "explore_project has no thread to resolve a workspace from");
        }
        String workspaceId = thread.get().workspaceId();
        String repo = repoOf(workspaceId, thread.get());
        Path checkout = clonePath(workspaceId);
        String question = args.question();

        ObjectNode out = mapper.createObjectNode();
        ObjectNode project = out.putObject("project");
        project.put("name", repo);
        learningRuns.latestRun(workspaceId, repo)
                .map(ProjectLearningRun::snapshotSha)
                .ifPresent(sha -> project.put("snapshot", sha));

        ArrayNode knowledgeOut = out.putArray("knowledge");
        for (KnowledgeRetrievalService.Retrieved entry : retrieval.retrieve(
                workspaceId, repo, question, null, KNOWLEDGE_LIMIT)) {
            KnowledgeItem item = entry.item();
            ObjectNode node = knowledgeOut.addObject();
            node.put("ref", "knowledge:" + item.id());
            node.put("kind", item.kind());
            node.put("summary", item.title() == null
                    ? item.statement() : item.title() + " — " + item.statement());
            if (item.rationale() != null && !item.rationale().isBlank()) {
                node.put("rationale", item.rationale());
            }
            node.put("confidence", item.confidence());
            if (possiblyStale(item, checkout)) {
                node.put("possiblyStale", true);
            }
            ArrayNode sources = node.putArray("sources");
            knowledge.provenance(item.id()).forEach(source ->
                    sources.add(source.sourceKind() + ":" + source.sourceRef()));
        }

        ArrayNode memoryOut = out.putArray("memory");
        for (MemoryItem item : retrieval.matchingMemory(workspaceId, question, MEMORY_LIMIT)) {
            ObjectNode node = memoryOut.addObject();
            node.put("ref", "memory:" + item.id());
            node.put("kind", item.kind().name());
            node.put("summary", item.text());
        }

        ArrayNode docsOut = out.putArray("docs");
        for (KnowledgeRetrievalService.DocSection section : retrieval.matchingDocs(
                workspaceId, repo, question, DOCS_LIMIT)) {
            ObjectNode node = docsOut.addObject();
            node.put("path", section.path());
            node.put("heading", section.headingPath());
            node.put("lines", section.lineStart() + "-" + section.lineEnd());
        }

        ArrayNode evidenceOut = out.putArray("evidence");
        for (KnowledgeRetrievalService.EvidenceHit hit : retrieval.matchingEvidence(
                workspaceId, repo, question, EVIDENCE_LIMIT)) {
            ObjectNode node = evidenceOut.addObject();
            node.put("ref", "pr:" + repo + "#" + hit.prNumber());
            if (hit.title() != null) {
                node.put("title", hit.title());
            }
            node.put("path", hit.filePath());
        }

        ArrayNode conceptsOut = out.putArray("concepts");
        for (String token : KnowledgeRetrievalService.tokenize(question)) {
            if (conceptsOut.size() >= CONCEPT_LIMIT) {
                break;
            }
            concepts.byName(token.toLowerCase(Locale.ROOT), workspaceId, repo)
                    .or(() -> concepts.byName(token, workspaceId, repo))
                    .ifPresent(spec -> addConcept(conceptsOut, spec));
        }

        return ToolOutcome.Completed.ok(out.toString());
    }

    private void addConcept(ArrayNode conceptsOut, ConceptSpec spec)
    {
        for (int i = 0; i < conceptsOut.size(); i++) {
            if (spec.name().equals(conceptsOut.get(i).path("name").asText())) {
                return;
            }
        }
        ObjectNode node = conceptsOut.addObject();
        node.put("name", spec.name());
        node.put("definition", spec.oneLineDefinition());
        node.put("scope", spec.scope().name());
    }

    /** An active item whose named path anchors are all absent from the
     *  current checkout is surfaced as possibly stale, never as truth. */
    private boolean possiblyStale(KnowledgeItem item, Path checkout)
    {
        if (checkout == null) {
            return false;
        }
        List<KnowledgeItem.Applicability> tags = knowledge.applicability(item.id());
        boolean hasPath = false;
        for (KnowledgeItem.Applicability tag : tags) {
            if (!"path".equals(tag.kind())) {
                continue;
            }
            hasPath = true;
            Path resolved = checkout.resolve(tag.value()).normalize();
            if (resolved.startsWith(checkout.normalize()) && Files.exists(resolved)) {
                return false;
            }
        }
        return hasPath;
    }

    private String repoOf(String workspaceId, Thread thread)
    {
        try {
            return repositories.resolve(workspaceId).fullName();
        }
        catch (RuntimeException e) {
            return thread.title() == null ? "" : thread.title();
        }
    }

    private Path clonePath(String workspaceId)
    {
        try {
            WorkspaceRepositoryResolver.RepositoryIdentity identity =
                    repositories.resolve(workspaceId);
            return watchedRepos.find(identity.owner(), identity.repo())
                    .map(WatchedRepo::localClonePath)
                    .filter(path -> path != null && !path.isBlank())
                    .map(Path::of)
                    .filter(Files::isDirectory)
                    .orElse(null);
        }
        catch (RuntimeException e) {
            return null;
        }
    }
}
