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

import com.bytequay.app.domain.ReviewFindingSeverity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Lifts structured findings out of a CLI reviewer's output.
 *
 * <p>The Claude / Codex CLIs run as their own agents and can't call the
 * in-JVM {@code report_finding} tool the API reviewers use. Instead they're
 * told (see {@link #INSTRUCTION}) to append a sentinel-delimited JSON array
 * of findings at the very end of their review; this parser pulls that block
 * back out so a CLI seat populates the same findings rail as an API seat.
 *
 * <p>Parsing is best-effort: a missing or malformed block yields an empty
 * list so the seat still contributes its prose review rather than failing.
 */
final class CliReviewFindings
{
    private static final Logger log = LoggerFactory.getLogger(CliReviewFindings.class);

    /** Sentinels the reviewer wraps its findings JSON array in. */
    static final String BEGIN = "<<BQ_FINDINGS>>";
    static final String END = "<<BQ_FINDINGS_END>>";

    /**
     * Appended to a CLI reviewer's directive so its review ends with a
     * parseable findings block. Kept terse — the CLI is a full agent and
     * over-instruction tends to derail it.
     */
    static final String INSTRUCTION = """
            After your review, on its own lines, output your structured findings as a \
            JSON array between the exact markers %s and %s — nothing else between them. \
            Each element: {"path": "<file or omit for whole-PR>", "line": <new-file \
            line number or omit>, "severity": "blocker|major|nit|question", "summary": \
            "<one line>"}. Anchor a code-specific finding to its path and line. Emit an \
            empty array [] if you found nothing.""".formatted(BEGIN, END);

    private CliReviewFindings()
    {
    }

    /** One finding parsed out of the CLI's findings block. */
    record Parsed(String path, Integer line, ReviewFindingSeverity severity, String summary)
    {
    }

    /**
     * Parse the findings block from a CLI reviewer's full output. Returns
     * the findings in document order; an empty list when there's no
     * well-formed block.
     */
    static List<Parsed> parse(String output, ObjectMapper mapper)
    {
        if (output == null) {
            return List.of();
        }
        int begin = output.lastIndexOf(BEGIN);
        if (begin < 0) {
            return List.of();
        }
        int from = begin + BEGIN.length();
        int end = output.indexOf(END, from);
        String json = stripFence((end < 0 ? output.substring(from) : output.substring(from, end)).strip());
        if (json.isEmpty()) {
            return List.of();
        }
        try {
            JsonNode root = mapper.readTree(json);
            if (!root.isArray()) {
                return List.of();
            }
            List<Parsed> findings = new ArrayList<>();
            for (JsonNode node : root) {
                String summary = node.path("summary").asText("").strip();
                if (summary.isEmpty()) {
                    continue;
                }
                String rawPath = node.path("path").asText("").strip();
                String path = rawPath.isEmpty() ? null : rawPath;
                Integer line = node.has("line") && node.path("line").asInt(0) > 0
                        ? node.path("line").asInt()
                        : null;
                ReviewFindingSeverity severity =
                        SeatToolset.severityFrom(node.path("severity").asText(""));
                findings.add(new Parsed(path, line, severity, summary));
            }
            return findings;
        }
        catch (JsonProcessingException e) {
            log.debug("CLI reviewer findings block was not valid JSON; ignoring it: {}", e.getMessage());
            return List.of();
        }
    }

    /** Drop a ```json fence the model may have wrapped the array in. */
    private static String stripFence(String value)
    {
        String trimmed = value.strip();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        if (firstNewline >= 0) {
            trimmed = trimmed.substring(firstNewline + 1);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed.strip();
    }
}
