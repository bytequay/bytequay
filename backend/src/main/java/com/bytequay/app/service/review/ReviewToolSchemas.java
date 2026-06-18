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
package com.bytequay.app.service.review;

import com.bytequay.app.service.agents.TurnSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Tool descriptors for the review lead + seat toolsets, rendered into
 * whichever {@code tools}-array dialect the seat's transport speaks.
 * Kept as plain (name, description, JSON-schema) triples so both
 * toolsets share one renderer and the catalogs stay greppable.
 */
final class ReviewToolSchemas
{
    private ReviewToolSchemas() {}

    record Tool(String name, String description, String schemaJson)
    {
    }

    static final Tool GET_PR_DIFF = new Tool(
            "get_pr_diff",
            "Read the PR's unified diff. Pass 'path' to get just that file's diff.",
            """
            {"type":"object","properties":{"path":{"type":"string",
            "description":"Optional file path to slice the diff to."}}}""");

    static final Tool GET_FILE_CONTENT = new Tool(
            "get_file_content",
            "Read a file's content at the PR's head commit, optionally a line range.",
            """
            {"type":"object","properties":{
            "path":{"type":"string"},
            "start_line":{"type":"integer"},
            "end_line":{"type":"integer"}},
            "required":["path"]}""");

    static final Tool SEARCH_CODE = new Tool(
            "search_code",
            "Search the PR's diff text for a substring; returns matching lines with file anchors.",
            """
            {"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}""");

    static final Tool REPORT_FINDING = new Tool(
            "report_finding",
            "Record one review finding (file, line, severity, summary) on the pass. "
                    + "Anchor it to a specific changed line whenever the issue is about "
                    + "concrete code — pass both path and line.",
            """
            {"type":"object","properties":{
            "path":{"type":"string","description":"File the finding anchors to (the diff path). REQUIRED whenever the finding is about specific code; omit only for a genuinely PR-wide observation."},
            "line":{"type":"integer","description":"Line number the finding anchors to — use the new-file line from the '+' side of the diff hunk. REQUIRED together with path for any code-specific finding; the finding posts as an inline comment on this line, and a finding with no line can only fold into the review body."},
            "severity":{"type":"string","enum":["blocker","major","nit","question"]},
            "summary":{"type":"string"}},
            "required":["severity","summary"]}""");

    static final Tool SET_AGENDA = new Tool(
            "set_agenda",
            "Set the pass agenda once, at kickoff: an ordered list of phases with stable ids.",
            """
            {"type":"object","properties":{
            "phases":{"type":"array","items":{"type":"object","properties":{
            "id":{"type":"string"},"title":{"type":"string"}},
            "required":["id","title"]}}},
            "required":["phases"]}""");

    static final Tool MARK_PHASE_IN_PROGRESS = new Tool(
            "mark_phase_in_progress",
            "Mark one agenda phase as in progress. Idempotent.",
            """
            {"type":"object","properties":{"phase_id":{"type":"string"}},"required":["phase_id"]}""");

    static final Tool MARK_PHASE_DONE = new Tool(
            "mark_phase_done",
            "Mark one agenda phase as done. Idempotent.",
            """
            {"type":"object","properties":{"phase_id":{"type":"string"}},"required":["phase_id"]}""");

    static final Tool DISPATCH_TO_REVIEWER = new Tool(
            "dispatch_to_reviewer",
            "Send a directive to one reviewer seat and get its reply. The body MUST "
                    + "@-mention the reviewer's label. Multiple dispatches in one turn "
                    + "run in parallel. Pass finding_id when debating one finding so its "
                    + "debate budget is metered.",
            """
            {"type":"object","properties":{
            "participant_id":{"type":"string"},
            "body":{"type":"string"},
            "finding_id":{"type":"string"}},
            "required":["participant_id","body"]}""");

    static final Tool MARK_CONSENSUS = new Tool(
            "mark_consensus",
            "Classify one finding after cross-review: agreed (panel stands behind it), "
                    + "disputed (split, goes to the human ballot), or dropped.",
            """
            {"type":"object","properties":{
            "finding_id":{"type":"string"},
            "status":{"type":"string","enum":["agreed","disputed","dropped"]},
            "severity":{"type":"string","enum":["blocker","major","nit","question"]},
            "sources":{"type":"array","items":{"type":"string"},
            "description":"Participant ids that stand behind the finding."}},
            "required":["finding_id","status"]}""");

    static final Tool RECORD_DISSENT = new Tool(
            "record_dissent",
            "Record one reviewer's dissent on a finding without changing the finding's status.",
            """
            {"type":"object","properties":{
            "finding_id":{"type":"string"},
            "reviewer_id":{"type":"string"},
            "body":{"type":"string"}},
            "required":["finding_id","reviewer_id","body"]}""");

    /** Render a catalog in the transport's tools-array dialect. */
    static ArrayNode render(ObjectMapper mapper, TurnSpec.Transport transport, List<Tool> tools)
    {
        ArrayNode arr = mapper.createArrayNode();
        for (Tool tool : tools) {
            JsonNode schema = parse(mapper, tool.schemaJson());
            if (transport == TurnSpec.Transport.ANTHROPIC) {
                ObjectNode node = mapper.createObjectNode();
                node.put("name", tool.name());
                node.put("description", tool.description());
                node.set("input_schema", schema);
                arr.add(node);
            }
            else {
                ObjectNode fn = mapper.createObjectNode();
                fn.put("name", tool.name());
                fn.put("description", tool.description());
                fn.set("parameters", schema);
                ObjectNode wrapper = mapper.createObjectNode();
                wrapper.put("type", "function");
                wrapper.set("function", fn);
                arr.add(wrapper);
            }
        }
        return arr;
    }

    private static JsonNode parse(ObjectMapper mapper, String schemaJson)
    {
        try {
            return mapper.readTree(schemaJson);
        }
        catch (IOException e) {
            throw new UncheckedIOException("malformed review tool schema", e);
        }
    }
}
