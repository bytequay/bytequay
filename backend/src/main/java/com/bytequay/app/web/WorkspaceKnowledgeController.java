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
/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.bytequay.app.web;

import com.bytequay.app.beans.workspace.DistillOperationDto;
import com.bytequay.app.beans.workspace.DistillRunDto;
import com.bytequay.app.beans.workspace.KBEntryDto;
import com.bytequay.app.beans.workspace.WorkspaceMemoryDto;
import com.bytequay.app.service.workspaces.WorkspaceKnowledgeService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/** Redesigned brain, KB, and reversible distillation API. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/memory")
public class WorkspaceKnowledgeController
{
    private final WorkspaceKnowledgeService knowledge;

    public WorkspaceKnowledgeController(WorkspaceKnowledgeService knowledge)
    {
        this.knowledge = requireNonNull(knowledge, "knowledge is null");
    }

    @GetMapping("/aggregate")
    public WorkspaceMemoryDto aggregate(@PathVariable String workspaceId)
    {
        return knowledge.get(workspaceId);
    }

    @PutMapping("/document")
    public WorkspaceMemoryDto replaceDocument(
            @PathVariable String workspaceId, @RequestBody MarkdownBody body)
    {
        return knowledge.replaceMarkdown(
                workspaceId, body == null || body.markdown() == null ? "" : body.markdown());
    }

    @GetMapping("/knowledge")
    public List<KBEntryDto> listKnowledge(@PathVariable String workspaceId)
    {
        return knowledge.listKnowledge(workspaceId);
    }

    @PostMapping("/knowledge")
    public KBEntryDto createKnowledge(
            @PathVariable String workspaceId, @RequestBody KnowledgeBody body)
    {
        KnowledgeBody request = requireNonNull(body, "body is null");
        return knowledge.saveKnowledge(
                workspaceId, null, request.title(), request.body(),
                request.audience(), request.provenance());
    }

    @GetMapping("/knowledge/{entryId}")
    public KBEntryDto getKnowledge(
            @PathVariable String workspaceId, @PathVariable String entryId)
    {
        return knowledge.getKnowledge(workspaceId, entryId);
    }

    @PutMapping("/knowledge/{entryId}")
    public KBEntryDto updateKnowledge(
            @PathVariable String workspaceId,
            @PathVariable String entryId,
            @RequestBody KnowledgeBody body)
    {
        KnowledgeBody request = requireNonNull(body, "body is null");
        return knowledge.saveKnowledge(
                workspaceId, entryId, request.title(), request.body(),
                request.audience(), request.provenance());
    }

    @DeleteMapping("/knowledge/{entryId}")
    public void deleteKnowledge(
            @PathVariable String workspaceId, @PathVariable String entryId)
    {
        knowledge.deleteKnowledge(workspaceId, entryId);
    }

    @GetMapping("/distill-runs")
    public List<DistillRunDto> listRuns(@PathVariable String workspaceId)
    {
        return knowledge.listRuns(workspaceId);
    }

    @PostMapping("/distill-runs")
    public DistillRunDto createRun(
            @PathVariable String workspaceId, @RequestBody PreviewBody body)
    {
        PreviewBody request = requireNonNull(body, "body is null");
        return knowledge.createPreview(
                workspaceId, request.trigger(), request.sources(), request.operations());
    }

    @PostMapping("/distill-runs/seed")
    public DistillRunDto seed(@PathVariable String workspaceId)
    {
        return knowledge.createSeedPreview(workspaceId);
    }

    @PostMapping("/distill-runs/threads")
    public DistillRunDto distillThreads(@PathVariable String workspaceId)
    {
        return knowledge.createThreadPreview(workspaceId);
    }

    @GetMapping("/distill-runs/{runId}")
    public DistillRunDto getRun(
            @PathVariable String workspaceId, @PathVariable String runId)
    {
        return knowledge.requireRun(workspaceId, runId);
    }

    @PutMapping("/distill-runs/{runId}/decisions")
    public DistillRunDto decide(
            @PathVariable String workspaceId,
            @PathVariable String runId,
            @RequestBody DecisionsBody body)
    {
        return knowledge.decide(
                workspaceId, runId, body == null ? List.of() : body.operations());
    }

    @PostMapping("/distill-runs/{runId}/apply")
    public DistillRunDto apply(
            @PathVariable String workspaceId, @PathVariable String runId)
    {
        return knowledge.apply(workspaceId, runId);
    }

    @PostMapping("/distill-runs/{runId}/revert")
    public DistillRunDto revert(
            @PathVariable String workspaceId, @PathVariable String runId)
    {
        return knowledge.revert(workspaceId, runId);
    }

    public record MarkdownBody(String markdown) {}

    public record KnowledgeBody(
            String title,
            String body,
            List<String> audience,
            Map<String, Object> provenance) {}

    public record PreviewBody(
            String trigger,
            List<Map<String, Object>> sources,
            List<DistillOperationDto> operations) {}

    public record DecisionsBody(List<DistillOperationDto> operations) {}
}
