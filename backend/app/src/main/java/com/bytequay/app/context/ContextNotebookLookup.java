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
import com.bytequay.core.context.ContextMatch;
import com.bytequay.core.context.ContextScope;
import com.bytequay.core.context.RepositoryNotebook;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static com.bytequay.app.context.ContextText.excerpt;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

/** Bounded literal notebook listing; the agent decides which note is relevant. */
@Service
public class ContextNotebookLookup
{
    private static final int PAGE_SIZE = 10;
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

    public SearchResult list(String workspaceId, int page, Optional<Long> notebookRevision)
    {
        return search(workspaceId, new SearchQuery("", ImmutableList.of(), page, notebookRevision));
    }

    /** Filters literal text in title, summary, body, source IDs and source reference labels; paths match saved scope prefixes. */
    public SearchResult search(String workspaceId, SearchQuery query)
    {
        RepositoryNotebook notebook = notebooks.getNotebook(workspaceId);
        query.notebookRevision().ifPresent(revision -> checkArgument(revision == notebook.revision(),
                "Notebook changed during paging; restart at page 1"));
        Map<String, ContextScope> scopes = notebook.scopes().stream().filter(ContextScope::enabled)
                .collect(toImmutableMap(ContextScope::id, scope -> scope));
        Map<String, String> references = notebook.sourceEvidence().stream()
                .collect(toImmutableMap(source -> source.id(), source -> source.reference().toString()));
        List<ContextEntry> matches = notebook.entries().stream()
                .filter(entry -> entry.enabled() && scopes.containsKey(entry.scopeId()))
                .filter(entry -> query.paths().isEmpty() || pathMatches(scopes.get(entry.scopeId()), query.paths()))
                .filter(entry -> query.query().isEmpty() || textMatches(entry, references, query.query()))
                .toList();
        List<RepositoryContextMatcher.ScopeMatch> matchedScopes = matcher.select(notebook, query.paths(), query.query()).scopes();
        List<ScopeState> firstStates = matchedScopes.stream().limit(MAX_SCOPE_STATES).map(match -> new ScopeState(match.scope().id(),
                match.scope().preparation().map(value -> value.outcome().name()).orElse("NOT_PREPARED"), match.missingKnowledge())).toList();
        List<Hit> candidates = matches.stream().map(entry -> hit(entry, scopes.get(entry.scopeId()))).toList();
        int offset = 0;
        for (int page = 1; page <= query.page(); page++) {
            List<ScopeState> states = new ArrayList<>(page == 1 ? firstStates : ImmutableList.of());
            List<Hit> hits = new ArrayList<>();
            while (offset + hits.size() < candidates.size() && hits.size() < PAGE_SIZE) {
                Hit next = candidates.get(offset + hits.size());
                List<Hit> proposed = ImmutableList.<Hit>builder().addAll(hits).add(next).build();
                SearchResult trial = result(notebook.revision(), candidates.size(), offset + proposed.size(), page, proposed, states, matchedScopes.size());
                while (ContextLimits.bytes(objectMapper.valueToTree(trial).toString()) > RESULT_BYTES && !states.isEmpty()) {
                    states.removeLast();
                    trial = result(notebook.revision(), candidates.size(), offset + proposed.size(), page, proposed, states, matchedScopes.size());
                }
                if (ContextLimits.bytes(objectMapper.valueToTree(trial).toString()) > RESULT_BYTES) {
                    checkArgument(!hits.isEmpty(), "One notebook entry exceeds the page byte limit");
                    break;
                }
                hits.add(next);
            }
            SearchResult result = result(notebook.revision(), candidates.size(), offset + hits.size(), page, hits, states, matchedScopes.size());
            while (ContextLimits.bytes(objectMapper.valueToTree(result).toString()) > RESULT_BYTES && !states.isEmpty()) {
                states.removeLast();
                result = result(notebook.revision(), candidates.size(), offset + hits.size(), page, hits, states, matchedScopes.size());
            }
            checkArgument(ContextLimits.bytes(objectMapper.valueToTree(result).toString()) <= RESULT_BYTES,
                    "Notebook page exceeds its byte limit");
            if (page == query.page()) {
                return result;
            }
            offset += hits.size();
        }
        throw new IllegalStateException("Notebook page calculation failed");
    }

    /**
     * Captures only an enabled, unsuppressed exact version; callers retain the response before returning it.
     *
     * @param commit the reader's commit to compare the note's cited lines with
     */
    public Optional<Read> readEnabled(String workspaceId, String entryId, long version, Optional<String> commit)
    {
        return notes.readEnabled(workspaceId, entryId, version, commit)
                .map(capture -> new Read(capture.notebookRevision(), capture.entry(), capture.read(), capture.sourceCheck()));
    }

    private static boolean pathMatches(ContextScope scope, List<String> paths)
    {
        if (scope.match() instanceof ContextMatch.Repository) {
            return true;
        }
        ContextMatch.Selected selected = (ContextMatch.Selected) scope.match();
        return paths.stream().anyMatch(path -> selected.pathPrefixes().stream()
                .anyMatch(prefix -> path.startsWith(prefix) || path.equals(prefix.substring(0, prefix.length() - 1))));
    }

    private static boolean textMatches(ContextEntry entry, Map<String, String> references, String query)
    {
        String text = entry.title() + "\n" + entry.summary() + "\n" + entry.body() + "\n" + String.join("\n", entry.sourceIds())
                + "\n" + entry.sourceIds().stream().map(references::get).filter(value -> value != null).collect(joining("\n"));
        return text.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
    }

    private static Hit hit(ContextEntry entry, ContextScope scope)
    {
        List<String> paths = scope.match() instanceof ContextMatch.Selected selected ? selected.pathPrefixes() : ImmutableList.of();
        return new Hit(entry.id(), entry.version(), entry.kind(), entry.scopeId(), entry.title(), excerpt(entry.summary(), 300),
                ImmutableList.of("Saved note; relevance and applicability unverified"), "UNVERIFIED",
                paths.stream().limit(2).toList(), Math.max(0, paths.size() - 2));
    }

    private static SearchResult result(long revision, int total, int end, int page, List<Hit> hits, List<ScopeState> states, int totalScopes)
    {
        return new SearchResult(revision, hits.isEmpty() ? "NO_MATCH" : "MATCHES", hits, states, total - end, totalScopes - states.size(),
                page, end < total ? page + 1 : null, total);
    }

    public record SearchQuery(String query, List<String> paths, int page, Optional<Long> notebookRevision)
    {
        public SearchQuery(String query, List<String> paths)
        {
            this(query, paths, 1, Optional.empty());
        }

        public SearchQuery
        {
            query = requireNonNull(query, "query is null").strip();
            checkArgument(ContextLimits.bytes(query) <= 200, "Search query exceeds 200 UTF-8 bytes");
            paths = ImmutableList.copyOf(paths);
            ContextLimits.unique(paths, "path hints", 20);
            checkArgument(page >= 1 && page <= 100, "Page must be between 1 and 100");
            requireNonNull(notebookRevision, "notebookRevision is null");
            checkArgument(page == 1 || notebookRevision.isPresent(), "Later pages require notebookRevision from page 1");
            notebookRevision.ifPresent(revision -> checkArgument(revision >= 0, "Negative notebook revision"));
            checkArgument(paths.stream().mapToInt(ContextLimits::bytes).sum() <= 4 * 1024, "Path hints exceed 4 KiB");
            paths.forEach(path -> {
                checkArgument(path.equals(path.strip()), "Path hint has surrounding whitespace");
                ContextLimits.pathPrefix(path.endsWith("/") ? path : path + "/");
            });
        }
    }

    public record SearchResult(long notebookRevision, String contextState, List<Hit> hits, List<ScopeState> scopeStates,
            int omittedCount, int omittedScopes, int page, Integer nextPage, int totalMatches)
    {
        public SearchResult(long notebookRevision, String contextState, List<Hit> hits, List<ScopeState> scopeStates,
                int omittedCount, int omittedScopes)
        {
            this(notebookRevision, contextState, hits, scopeStates, omittedCount, omittedScopes, 1, null, hits.size() + omittedCount);
        }

        public SearchResult
        {
            hits = ImmutableList.copyOf(hits);
            scopeStates = ImmutableList.copyOf(scopeStates);
            // Older retained search receipts predate paging metadata.
            if (page == 0) {
                page = 1;
                totalMatches = hits.size() + omittedCount;
            }
        }
    }

    public record Hit(String entryId, long entryVersion, ContextEntryKind kind, String scopeId, String title, String summary,
            List<String> matchReasons, String applicability, List<String> paths, int omittedPaths)
    {
        public Hit(String entryId, long entryVersion, ContextEntryKind kind, String scopeId, String title, String summary,
                List<String> matchReasons, String applicability)
        {
            this(entryId, entryVersion, kind, scopeId, title, summary, matchReasons, applicability, ImmutableList.of(), 0);
        }

        public Hit
        {
            matchReasons = ImmutableList.copyOf(matchReasons);
            paths = paths == null ? ImmutableList.of() : ImmutableList.copyOf(paths);
        }
    }

    public record ScopeState(String scopeId, String preparation, boolean missingKnowledge) {}

    public record Read(long notebookRevision, ContextEntry entry, ContextNoteReader.Read capture, ContextSourceCheck sourceCheck) {}
}
