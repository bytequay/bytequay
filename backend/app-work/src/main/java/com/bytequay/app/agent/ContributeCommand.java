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
package com.bytequay.app.agent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.regex.Pattern.CASE_INSENSITIVE;

/**
 * A message that starts with {@code @contribute} asks the task to find and fix one small, worthwhile problem in its repository. The transcript
 * keeps the user's short command; only the provider's input carries the full brief. How far the work goes is the task's automation setting.
 */
final class ContributeCommand
{
    private static final Pattern COMMAND = Pattern.compile("^\\s*@contribute(?:\\s+|$)", CASE_INSENSITIVE);
    static final String BRIEF = """
            Find one small, worthwhile contribution to this repository that fixes a concrete problem real users or operators hit.

            Before choosing:
            1. Read the contribution guidelines and the pull-request template.
            2. Search open and recently closed issues and pull requests. Skip anything already fixed, claimed, or in review.
            3. Establish, with evidence:
               - Who is affected, and under what realistic conditions.
               - The observable problem, shown by a reproduction or measurement.
               - The expected outcome, and how to check it.

            Prefer a focused bug fix with a regression test that fails before the fix and passes after. Performance, resource, or size \
            changes need real before/after measurements. Skip cleanup, unused-code removal, cosmetic refactors, and speculative hardening \
            unless they produce such an outcome; fewer lines alone is not a reason. If nothing qualifies, say so and stop instead of \
            manufacturing a change.

            Implement the smallest justified fix:
            - Match the surrounding code: naming, formatting, comment density, and test layout. Run the repository's formatter and linter \
            when it has them.
            - Follow the repository's commit-message style from its history, one logical change per commit.

            Write the pull-request title and description as any contributor would:
            - Use the repository's template. Keep its headings and checkboxes and fill in only what applies.
            - Stay under 100 words: the problem, who hits it, the evidence, and the fix.
            - Leave tests and local verification out: no description of added tests, commands, test runs, results, or "tested locally".
            - Add no AI attribution, disclosure, co-author, or generated-with text, and leave any AI-use question in the template unanswered.
            """;

    private ContributeCommand() {}

    /** The message with a leading {@code @contribute} replaced by the brief; anything the user wrote after it follows the brief. */
    static String expand(String message)
    {
        Matcher command = COMMAND.matcher(message);
        if (!command.find()) {
            return message;
        }
        String rest = message.substring(command.end()).strip();
        return rest.isEmpty() ? BRIEF.strip() : BRIEF + "\n" + rest;
    }
}
