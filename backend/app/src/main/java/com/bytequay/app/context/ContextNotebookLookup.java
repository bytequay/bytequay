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
package com.bytequay.app.context;

import com.bytequay.core.context.ContextEntry;
import com.bytequay.core.context.ContextEntryKind;
import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.context.ContextScope;
import com.bytequay.core.context.RepositoryNotebook;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.bytequay.app.context.ContextText.excerpt;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Searches the small current workspace notebook without fetching or refreshing any source. */
@Service
public class ContextNotebookLookup
{
    private static final int MAX_HITS = 5;
    private static final int MAX_SCOPE_STATES = 10;
    private static final int RESULT_BYTES = 12 * 1024;

    private final RepositoryContextService notebooks;
    private final RepositoryContextMatcher matcher;
    private final ContextNoteReader notes;
    private final ObjectMapper objectMapper;

    public ContextNotebookLookup(RepositoryContextService notebooks, RepositoryContextMatcher matcher, ContextNoteReader notes, ObjectMapper objectMapper)
    {
        this.notebooks = requireNonNull(notebooks, "notebooks is null");
        this.matcher = requireNonNull(matcher, "matcher is null");
        this.notes = requireNonNull(notes, "notes is null");
        this.objectMapper = requireNonNull(objectMapper, "objectMapper is null");
    }

    /** Returns ranked identities, never note bodies. The caller must bind and retain the result before giving it to a provider. */
    public SearchResult search(String workspaceId, SearchQuery query)
    {
        RepositoryNotebook notebook = notebooks.getNotebook(workspaceId);
        RepositoryContextMatcher.Selection natural = matcher.select(notebook, query.paths(), query.query(), ImmutableList.of());
        Set<String> selected = new HashSet<>();
        Set<String> scopeOnlyIds = new HashSet<>();
        List<Hit> candidates = new ArrayList<>();
        List<RepositoryContextMatcher.Match> scopeOnly = new ArrayList<>();
        for (RepositoryContextMatcher.Match match : natural.entries()) {
            if (match.rank() == RepositoryContextMatcher.Rank.SCOPE_ONLY_LESSON) {
                scopeOnly.add(match);
                scopeOnlyIds.add(match.entry().id());
                continue;
            }
            candidates.add(hit(match.entry(), match.matchReasons()));
            selected.add(match.entry().id());
        }
        Set<String> words = RepositoryContextMatcher.topicWords(query.query());
        Set<String> enabledScopes = notebook.scopes().stream().filter(ContextScope::enabled).map(ContextScope::id).collect(java.util.stream.Collectors.toSet());
        for (ContextEntry entry : notebook.entries()) {
            if (selected.contains(entry.id()) || scopeOnlyIds.contains(entry.id()) || !entry.enabled() || !enabledScopes.contains(entry.scopeId())
                    || notebook.suppressedKnowledgeIds().contains(entry.id())) {
                continue;
            }
            List<String> shared = RepositoryContextMatcher.topicWords(entry.title() + "\n" + entry.summary()).stream()
                    .filter(words::contains).sorted().toList();
            if (!shared.isEmpty()) {
                candidates.add(hit(entry, ImmutableList.of("Topic match only; component not matched: " + excerpt(String.join(", ", shared), 900))));
                selected.add(entry.id());
            }
        }
        for (RepositoryContextMatcher.Match match : scopeOnly) {
            if (selected.add(match.entry().id())) {
                candidates.add(hit(match.entry(), match.matchReasons()));
            }
        }
        List<ScopeState> scopes = natural.scopes().stream().map(match -> new ScopeState(match.scope().id(),
                match.scope().preparation().map(value -> value.outcome().name()).orElse("NOT_PREPARED"), match.missingKnowledge()))
                .toList();
        List<Hit> hits = new ArrayList<>(candidates.subList(0, Math.min(candidates.size(), MAX_HITS)));
        List<ScopeState> states = new ArrayList<>(scopes.subList(0, Math.min(scopes.size(), MAX_SCOPE_STATES)));
        SearchResult result = result(notebook.revision(), candidates.size(), scopes.size(), hits, states);
        while (ContextLimits.bytes(objectMapper.valueToTree(result).toString()) > RESULT_BYTES && !states.isEmpty()) {
            states.removeLast();
            result = result(notebook.revision(), candidates.size(), scopes.size(), hits, states);
        }
        while (ContextLimits.bytes(objectMapper.valueToTree(result).toString()) > RESULT_BYTES && hits.size() > 1) {
            hits.removeLast();
            result = result(notebook.revision(), candidates.size(), scopes.size(), hits, states);
        }
        checkArgument(ContextLimits.bytes(objectMapper.valueToTree(result).toString()) <= RESULT_BYTES, "Search result exceeds its byte limit");
        return result;
    }

    /** Captures only an enabled, unsuppressed exact version; caller verifies an offer/search reference first. */
    public Optional<Read> readEnabled(String workspaceId, String entryId, long version)
    {
        return notes.readEnabled(workspaceId, entryId, version)
                .map(capture -> new Read(capture.notebookRevision(), capture.entry(), capture.read()));
    }

    private static Hit hit(ContextEntry entry, List<String> reasons)
    {
        return new Hit(entry.id(), entry.version(), entry.kind(), entry.scopeId(), entry.title(), excerpt(entry.summary(), 300),
                ImmutableList.of(excerpt(String.join("; ", reasons), 1000)), "UNVERIFIED");
    }

    private static SearchResult result(long revision, int candidates, int scopes, List<Hit> hits, List<ScopeState> states)
    {
        return new SearchResult(revision, hits.isEmpty() ? "NO_MATCH" : "MATCHES", hits, states,
                candidates - hits.size(), scopes - states.size());
    }

    public record SearchQuery(String query, List<String> paths)
    {
        public SearchQuery
        {
            query = requireNonNull(query, "query is null").strip();
            checkArgument(ContextLimits.bytes(query) <= 200, "Search query exceeds 200 UTF-8 bytes");
            paths = ImmutableList.copyOf(paths);
            ContextLimits.unique(paths, "path hints", 20);
            checkArgument(!query.isEmpty() || !paths.isEmpty(), "Search requires a topic or path hint");
            checkArgument(paths.stream().mapToInt(ContextLimits::bytes).sum() <= 4 * 1024, "Path hints exceed 4 KiB");
            paths.forEach(path -> {
                checkArgument(path.equals(path.strip()), "Path hint has surrounding whitespace");
                ContextLimits.pathPrefix(path.endsWith("/") ? path : path + "/");
            });
        }
    }

    public record SearchResult(long notebookRevision, String contextState, List<Hit> hits, List<ScopeState> scopeStates, int omittedCount, int omittedScopes)
    {
        public SearchResult
        {
            hits = ImmutableList.copyOf(hits);
            scopeStates = ImmutableList.copyOf(scopeStates);
        }
    }

    public record Hit(String entryId, long entryVersion, ContextEntryKind kind, String scopeId, String title, String summary,
            List<String> matchReasons, String applicability)
    {
        public Hit
        {
            matchReasons = ImmutableList.copyOf(matchReasons);
        }
    }

    public record ScopeState(String scopeId, String preparation, boolean missingKnowledge) {}

    public record Read(long notebookRevision, ContextEntry entry, ContextNoteReader.Read capture) {}
}
