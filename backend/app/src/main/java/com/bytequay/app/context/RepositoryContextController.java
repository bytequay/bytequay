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

import com.bytequay.core.context.ContextClaim;
import com.bytequay.core.context.ContextMatch;
import com.bytequay.core.context.ContextScope;
import com.bytequay.core.context.RepositoryNotebook;
import com.google.common.collect.ImmutableList;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** User-owned notebook edits; source capture and generated provenance stay behind the service boundary. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/repository-context")
public class RepositoryContextController
{
    private final RepositoryContextService context;

    public RepositoryContextController(RepositoryContextService context)
    {
        this.context = requireNonNull(context, "context is null");
    }

    @GetMapping
    public RepositoryNotebook get(@PathVariable String workspaceId)
    {
        return context.getNotebook(workspaceId);
    }

    @PutMapping("/configuration")
    public RepositoryNotebook configure(@PathVariable String workspaceId, @RequestBody ConfigurationRequest request)
    {
        EditContextConfiguration edit = new EditContextConfiguration(request.useInReviews(), request.sources(),
                request.scopes().map(scopes -> scopes.stream().map(ScopeRequest::toScope).collect(toImmutableList())));
        return context.configure(workspaceId, request.expectedRevision(), edit);
    }

    @PutMapping("/entries")
    public RepositoryNotebook edit(@PathVariable String workspaceId, @RequestBody EntryRequest request)
    {
        EditContextEntry edit = new EditContextEntry(request.id(), request.scopeId(), request.title(), request.summary(), request.body(), request.claims(), request.sourceIds());
        return context.editEntry(workspaceId, request.expectedRevision(), edit);
    }

    @PutMapping("/entries/enabled")
    public RepositoryNotebook enable(@PathVariable String workspaceId, @RequestBody EnabledRequest request)
    {
        return context.setEntryEnabled(workspaceId, request.expectedRevision(), request.entryId(), request.enabled());
    }

    @DeleteMapping("/entries")
    public RepositoryNotebook remove(@PathVariable String workspaceId, @RequestBody RemoveRequest request)
    {
        return context.removeEntry(workspaceId, request.expectedRevision(), request.entryId());
    }

    @PostMapping("/reset")
    public RepositoryNotebook reset(@PathVariable String workspaceId, @RequestBody ResetRequest request)
    {
        return context.resetSuppression(workspaceId, request.expectedRevision(), request.scopeIds());
    }

    @PostMapping("/lessons")
    public RepositoryNotebook saveLesson(@PathVariable String workspaceId, @RequestBody LessonRequest request)
    {
        SaveContextLesson lesson = new SaveContextLesson(request.scopeId(), request.title(), request.summary(), request.body(),
                request.reviewId(), request.runId(), request.evidenceRefs(), request.messageId());
        return context.saveLesson(workspaceId, request.expectedRevision(), lesson);
    }

    public record ConfigurationRequest(
            Long expectedRevision,
            Optional<Boolean> useInReviews,
            Optional<List<EditContextConfiguration.Source>> sources,
            Optional<List<ScopeRequest>> scopes)
    {
        public ConfigurationRequest
        {
            requireRevision(expectedRevision);
            requireNonNull(useInReviews, "useInReviews is null");
            sources = requireNonNull(sources, "sources is null").map(ImmutableList::copyOf);
            scopes = requireNonNull(scopes, "scopes is null").map(ImmutableList::copyOf);
            sources.ifPresent(values -> values.forEach(source -> {
                requireNonNull(source.id(), "source id is null");
                requireNonNull(source.kind(), "source kind is null");
                requireNonNull(source.url(), "source URL is null");
            }));
        }
    }

    public record ScopeRequest(String id, String title, MatchRequest match, Boolean enabled)
    {
        public ScopeRequest
        {
            requireNonNull(id, "id is null");
            requireNonNull(title, "title is null");
            requireNonNull(match, "match is null");
            requireNonNull(enabled, "enabled is null");
        }

        ContextScope toScope()
        {
            return new ContextScope(id, title, match.toMatch(), enabled);
        }
    }

    public record MatchRequest(String kind, Optional<List<String>> pathPrefixes, Optional<List<String>> terms)
    {
        public MatchRequest
        {
            checkArgument("REPOSITORY".equals(kind) || "SELECTED".equals(kind), "Unknown context match kind");
            pathPrefixes = requireNonNull(pathPrefixes, "pathPrefixes is null").map(ImmutableList::copyOf);
            terms = requireNonNull(terms, "terms is null").map(ImmutableList::copyOf);
        }

        ContextMatch toMatch()
        {
            List<String> paths = pathPrefixes.orElse(ImmutableList.of());
            List<String> selectedTerms = terms.orElse(ImmutableList.of());
            if (kind.equals("REPOSITORY")) {
                checkArgument(paths.isEmpty() && selectedTerms.isEmpty(), "Repository match cannot contain path or term rules");
                return new ContextMatch.Repository();
            }
            return new ContextMatch.Selected(paths, selectedTerms);
        }
    }

    public record EntryRequest(
            Long expectedRevision, String id, String scopeId, String title, String summary, String body, List<ContextClaim> claims, List<String> sourceIds)
    {
        public EntryRequest
        {
            requireRevision(expectedRevision);
            requireNonNull(id, "id is null");
            requireNonNull(scopeId, "scopeId is null");
            requireNonNull(title, "title is null");
            requireNonNull(summary, "summary is null");
            requireNonNull(body, "body is null");
            claims = ImmutableList.copyOf(claims);
            sourceIds = ImmutableList.copyOf(sourceIds);
        }
    }

    public record EnabledRequest(Long expectedRevision, String entryId, Boolean enabled)
    {
        public EnabledRequest
        {
            requireRevision(expectedRevision);
            requireNonNull(entryId, "entryId is null");
            requireNonNull(enabled, "enabled is null");
        }
    }

    public record RemoveRequest(Long expectedRevision, String entryId)
    {
        public RemoveRequest
        {
            requireRevision(expectedRevision);
            requireNonNull(entryId, "entryId is null");
        }
    }

    public record ResetRequest(Long expectedRevision, List<String> scopeIds)
    {
        public ResetRequest
        {
            requireRevision(expectedRevision);
            scopeIds = ImmutableList.copyOf(scopeIds);
        }
    }

    public record LessonRequest(
            Long expectedRevision, String scopeId, String title, String summary, String body, String reviewId, String runId,
            List<String> evidenceRefs, Optional<String> messageId)
    {
        public LessonRequest
        {
            requireRevision(expectedRevision);
            requireNonNull(scopeId, "scopeId is null");
            requireNonNull(title, "title is null");
            requireNonNull(summary, "summary is null");
            requireNonNull(body, "body is null");
            requireNonNull(reviewId, "reviewId is null");
            requireNonNull(runId, "runId is null");
            evidenceRefs = ImmutableList.copyOf(evidenceRefs);
            requireNonNull(messageId, "messageId is null");
        }
    }

    private static void requireRevision(Long revision)
    {
        checkArgument(revision != null && revision >= 0, "expectedRevision is required and must not be negative");
    }
}
