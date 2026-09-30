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
import com.bytequay.core.context.RepositoryNotebook;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** User-owned notebook edits; source capture and generated provenance stay behind the service boundary. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/repository-context")
public class RepositoryContextController
{
    private final RepositoryContextService context;
    private final ContextGradeService grades;
    private final ContextPageCaptures pages;

    public RepositoryContextController(RepositoryContextService context, ContextGradeService grades, ContextPageCaptures pages)
    {
        this.context = requireNonNull(context, "context is null");
        this.grades = requireNonNull(grades, "grades is null");
        this.pages = requireNonNull(pages, "pages is null");
    }

    @GetMapping
    public RepositoryNotebook get(@PathVariable String workspaceId)
    {
        return context.getNotebook(workspaceId);
    }

    @GetMapping("/times")
    public ContextTimes times(@PathVariable String workspaceId)
    {
        return context.getTimes(workspaceId);
    }

    @PutMapping("/configuration")
    public RepositoryNotebook configure(@PathVariable String workspaceId, @RequestBody ConfigurationRequest request)
    {
        EditContextConfiguration edit = new EditContextConfiguration(request.useInReviews(), request.useInDevelopment(), request.sources());
        return context.configure(workspaceId, request.expectedRevision(), edit);
    }

    @PutMapping("/entries")
    public RepositoryNotebook edit(@PathVariable String workspaceId, @RequestBody EntryRequest request)
    {
        EditContextEntry edit = new EditContextEntry(request.id(), request.scopeId(), request.title(), request.summary(), request.body(), request.claims(), request.sourceIds());
        return context.editEntry(workspaceId, request.expectedRevision(), edit);
    }

    @PostMapping("/entries/restore")
    public RepositoryNotebook restore(@PathVariable String workspaceId, @RequestBody RestoreRequest request)
    {
        return context.restoreEntry(workspaceId, request.expectedRevision(), request.entryId());
    }

    @PostMapping("/entries/keep")
    public RepositoryNotebook keep(@PathVariable String workspaceId, @RequestBody RestoreRequest request)
    {
        return context.keepEntry(workspaceId, request.expectedRevision(), request.entryId());
    }

    @GetMapping("/disputes")
    public ContextDisputes disputes(@PathVariable String workspaceId)
    {
        return context.getDisputes(workspaceId);
    }

    /** Sources whose cited page a later learning run captured with different text, and when. */
    @GetMapping("/pages/changed")
    public ChangedPages changedPages(@PathVariable String workspaceId)
    {
        return new ChangedPages(pages.changed(workspaceId, context.getNotebook(workspaceId)));
    }

    @GetMapping("/reports")
    public List<ContextGradeService.ReportedNote> reports(@PathVariable String workspaceId)
    {
        return grades.reportedNotes(workspaceId);
    }

    @PostMapping("/reports")
    public List<ContextGradeService.ReportedNote> report(@PathVariable String workspaceId, @RequestBody ReportRequest request)
    {
        grades.report(workspaceId, request.taskId(), request.entryId(), request.entryVersion(), request.reason());
        return grades.reportedNotes(workspaceId);
    }

    @PostMapping("/runs/disable-notes")
    public RepositoryNotebook disableRunNotes(@PathVariable String workspaceId, @RequestBody RunNotesRequest request)
    {
        return context.disableRunNotes(workspaceId, request.expectedRevision(), request.taskId(), request.noteIds());
    }

    @PutMapping("/entries/enabled")
    public RepositoryNotebook enable(@PathVariable String workspaceId, @RequestBody EnabledRequest request)
    {
        return context.setEntryEnabled(workspaceId, request.expectedRevision(), request.entryId(), request.enabled());
    }

    @PostMapping("/lessons")
    public RepositoryNotebook saveLesson(@PathVariable String workspaceId, @RequestBody LessonRequest request)
    {
        SaveContextLesson lesson = new SaveContextLesson(request.scopeId(), request.title(), request.summary(), request.body(),
                request.reviewId(), request.runId(), request.evidenceRefs(), request.messageId());
        return context.saveLesson(workspaceId, request.expectedRevision(), lesson);
    }

    public record ChangedPages(Map<String, Instant> changed)
    {
        public ChangedPages
        {
            changed = ImmutableMap.copyOf(changed);
        }
    }

    public record ConfigurationRequest(
            Long expectedRevision,
            Optional<Boolean> useInReviews,
            Optional<Boolean> useInDevelopment,
            Optional<List<EditContextConfiguration.Source>> sources)
    {
        public ConfigurationRequest
        {
            requireRevision(expectedRevision);
            requireNonNull(useInReviews, "useInReviews is null");
            requireNonNull(useInDevelopment, "useInDevelopment is null");
            sources = requireNonNull(sources, "sources is null").map(ImmutableList::copyOf);
            sources.ifPresent(values -> values.forEach(source -> {
                requireNonNull(source.id(), "source id is null");
                requireNonNull(source.kind(), "source kind is null");
                requireNonNull(source.url(), "source URL is null");
            }));
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

    public record RestoreRequest(Long expectedRevision, String entryId)
    {
        public RestoreRequest
        {
            requireRevision(expectedRevision);
            requireNonNull(entryId, "entryId is null");
        }
    }

    public record ReportRequest(String taskId, String entryId, Long entryVersion, String reason)
    {
        public ReportRequest
        {
            requireNonNull(taskId, "taskId is null");
            requireNonNull(entryId, "entryId is null");
            checkArgument(entryVersion != null && entryVersion > 0, "entryVersion is required and must be positive");
            requireNonNull(reason, "reason is null");
        }
    }

    public record RunNotesRequest(Long expectedRevision, String taskId, List<String> noteIds)
    {
        public RunNotesRequest
        {
            requireRevision(expectedRevision);
            requireNonNull(taskId, "taskId is null");
            noteIds = ImmutableList.copyOf(noteIds);
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
