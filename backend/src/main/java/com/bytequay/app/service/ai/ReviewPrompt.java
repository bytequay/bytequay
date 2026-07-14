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
package com.bytequay.app.service.ai;

import com.bytequay.app.domain.ReviewRequest;
import com.bytequay.app.service.skills.CavemanPrompt;

import static java.util.Objects.requireNonNullElse;

/**
 * Shared prompt fragments for AI PR review. Kept here so that all provider
 * implementations (Claude, OpenAI, local) see the same instructions and
 * produce comparable JSON output.
 */
final class ReviewPrompt
{
    private ReviewPrompt() {}

    static final int MAX_DIFF_CHARS = 200_000;

    /** Builds the system prompt for a request, prepending the per-
     *  reviewer persona voice (if any) and appending the matching
     *  review-skill context (if any). The two sections sit on
     *  opposite sides of {@link #SYSTEM}: persona is who's reviewing
     *  (voice + concerns), skill is what to look for in this repo. */
    static String systemPrompt(ReviewRequest req)
    {
        StringBuilder out = new StringBuilder();
        String persona = req.personaPrompt();
        if (persona != null && !persona.isBlank()) {
            out.append("Reviewer voice:\n").append(persona.strip()).append("\n\n");
        }
        out.append(SYSTEM);
        String skill = req.skillContext();
        if (skill != null && !skill.isBlank()) {
            out.append("\n\nRepository-specific review context:\n").append(skill.strip()).append("\n");
        }
        return CavemanPrompt.wrap(out.toString());
    }

    static final String SYSTEM = """
            You are a senior engineer performing a careful code review of a pull request.

            Output format (REQUIRED): a single JSON object with exactly this shape,
            no prose before or after, no markdown fences:

            {
              "summary": "string — 2-4 sentence summary of what the PR does and its overall shape",
              "comments": [
                {
                  "file": "path/to/file.java",
                  "line": 42,
                  "severity": "info" | "suggestion" | "warning" | "blocker",
                  "body": "string — the review comment, conversational and concrete"
                }
              ]
            }

            Guidance for the comments:
            - Only comment where a comment genuinely adds value. Zero-filler — no
              "LGTM" style notes. If the PR looks good, return an empty comments array.
            - Anchor each comment to a specific line that EXISTS in the PR's diff.
              The line number must be a line in the NEW file after the patch is applied.
            - Prefer actionable suggestions over vague concerns. If you propose a change,
              be concrete.
            - Severities: "info" (nit / FYI), "suggestion" (would improve the code),
              "warning" (likely issue), "blocker" (must-fix before merge).
            - Cap at ~10 comments. Pick the most important ones.
            """;

    static String userMessage(ReviewRequest req)
    {
        String diff = requireNonNullElse(req.diff(), "");
        if (diff.length() > MAX_DIFF_CHARS) {
            diff = diff.substring(0, MAX_DIFF_CHARS) + "\n... [truncated: diff too large]";
        }
        String body = requireNonNullElse(req.body(), "");
        return """
                Review this pull request.

                Repo: %s
                PR: #%d
                Title: %s
                Head SHA: %s

                Description:
                %s

                Unified diff:
                ```diff
                %s
                ```
                """.formatted(
                        req.repo(),
                        req.number(),
                        requireNonNullElse(req.title(), ""),
                        requireNonNullElse(req.headSha(), ""),
                        body.isBlank() ? "(no description)" : body,
                        diff);
    }
}
