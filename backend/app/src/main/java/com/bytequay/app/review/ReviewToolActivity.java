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
package com.bytequay.app.review;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;

/** The line a review tool returns about what a call read or saved; the task log shows it in place of the tool's name. */
final class ReviewToolActivity
{
    private static final int MAX_LENGTH = 200;

    private ReviewToolActivity() {}

    static String describe(String tool, JsonNode arguments, JsonNode result)
    {
        String line = switch (tool) {
            case "review_get_context" -> context(arguments, result);
            case "review_save_summary" -> "Saved summary";
            case "review_save_investigation" -> "Saved investigation";
            case "review_save_finding" -> "Saved finding" + quoted(" · ", arguments.path("title").asText(""));
            case "review_reply" -> "Replied in discussion";
            case "review_withdraw_finding" -> "Withdrew finding";
            case "review_finish" -> "Finished review";
            default -> tool;
        };
        String single = line.replaceAll("\\s+", " ").strip();
        return single.length() <= MAX_LENGTH ? single : single.substring(0, MAX_LENGTH - 1) + "…";
    }

    private static String context(JsonNode arguments, JsonNode result)
    {
        String kind = arguments.path("kind").asText("").toLowerCase(Locale.ROOT);
        String number = arguments.path("number").isInt() ? " #" + arguments.path("number").asInt()
                : result.path("number").isInt() ? " #" + result.path("number").asInt() : "";
        String page = arguments.path("page").isInt() && arguments.path("page").asInt() > 1 ? " · page " + arguments.path("page").asInt() : "";
        return switch (kind) {
            case "source" -> "Read " + arguments.path("path").asText("") + (result.path("startLine").isInt()
                    ? " · lines " + result.path("startLine").asInt() + "–" + result.path("endLine").asInt() : "")
                    + (arguments.path("revision").asText("head").matches("head|") ? "" : " (" + arguments.path("revision").asText() + ")");
            case "diff" -> "Read diff" + (arguments.path("path").asText("").isBlank() ? "" : " · " + arguments.path("path").asText()) + page;
            case "command_output" -> "Read output of " + result.path("commands").size() + (result.path("commands").size() == 1 ? " command" : " commands");
            case "history" -> "Read " + (arguments.path("sourceKind").asText("").equals("ISSUE") ? "issue" : "PR") + number + " history";
            case "pr" -> "Read PR" + number;
            case "timeline" -> "Read PR" + number + " timeline" + page;
            case "thread" -> "Read review threads" + (number.isEmpty() ? "" : " on PR" + number) + page;
            case "checks" -> "Read checks" + (number.isEmpty() ? "" : " of PR" + number) + page;
            case "issue" -> "Read issue" + number + page;
            case "commit_prs" -> "Looked up pull requests of " + arguments.path("shas").size() + (arguments.path("shas").size() == 1 ? " commit" : " commits");
            case "findings" -> arguments.path("target").asText("").isBlank() ? "Read findings" : "Read finding · " + result.path("title").asText(arguments.path("target").asText());
            case "scope", "investigation", "assignment", "summary", "discussion", "evidence" -> "Read " + kind;
            default -> "Read " + kind + page;
        };
    }

    private static String quoted(String separator, String text)
    {
        return text.isBlank() ? "" : separator + "“" + text.strip() + "”";
    }
}
