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
package com.bytequay.app.cherrypick;

import com.bytequay.core.cherrypick.CherryPickItem;
import com.bytequay.core.cherrypick.CherryPickItem.ConflictPath;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Builds the one prompt shape used for a conflict and for a validation repair.
 *
 * <p>The agent resolves files. It never moves the sequence, and its reply is
 * shown to the user rather than read: ByteQuay inspects Git and reruns
 * validation itself.
 */
final class CherryPickPromptBuilder
{
    private static final int MAX_LISTED_PATHS = 50;

    private CherryPickPromptBuilder() {}

    static String resolveConflict(
            CherryPickItem item,
            String expectedParentSha,
            Optional<String> validationCommand,
            Optional<String> diagnostic,
            int attempt)
    {
        requireNonNull(item, "item is null");
        StringBuilder prompt = new StringBuilder()
                .append("Resolve only this cherry-pick.\n\n")
                .append("Source commit: ").append(item.sourceSha()).append('\n')
                .append("Subject: ").append(item.title()).append('\n')
                .append("Expected target parent: ").append(expectedParentSha).append('\n')
                .append("Current step: RESOLVING\n")
                .append("Attempt: ").append(attempt).append(" of ").append(CherryPickItem.MAX_AGENT_TURNS)
                .append('\n');
        appendConflicts(prompt, item.conflictPaths());
        diagnostic.ifPresent(value -> prompt.append("Latest program diagnostic:\n- ").append(value).append('\n'));
        prompt.append("""

                Goal:
                - Resolve every conflict and stage the result. Then stop.
                  ByteQuay runs git cherry-pick --continue itself.
                - Leave no conflict markers and no unrelated changes.
                """);
        validationCommand.ifPresent(command -> prompt
                .append("- ByteQuay will run this validation afterwards: ")
                .append(command)
                .append('\n'));
        prompt.append("""

                If git reports that this cherry-pick is empty, stop and leave it open.
                ByteQuay will inspect and skip it safely.

                Do not run git cherry-pick --continue, --skip, or --abort. Do not commit,
                amend, create a fixup commit, pick another commit, rebase, push, use gh, or
                write to GitHub.

                Your final message is for the user. ByteQuay inspects Git and decides.
                """);
        return prompt.toString();
    }

    static String repairValidation(
            CherryPickItem item,
            String targetSha,
            String validationCommand,
            String diagnostic,
            int attempt)
    {
        requireNonNull(item, "item is null");
        return new StringBuilder()
                .append("Repair only this cherry-picked commit.\n\n")
                .append("Source commit: ").append(item.sourceSha()).append('\n')
                .append("Created commit: ").append(targetSha).append('\n')
                .append("Current step: VALIDATING\n")
                .append("Attempt: ").append(attempt).append(" of ").append(CherryPickItem.MAX_AGENT_TURNS)
                .append("\nValidation that failed: ").append(validationCommand)
                .append("\nLatest program diagnostic:\n")
                .append(bounded(diagnostic))
                .append("""

                        Goal:
                        - Fix the failure and amend the same commit with
                          git commit --amend --no-edit.
                        - Create no new commit, and no fixup commit.

                        Do not run git cherry-pick --continue, --skip, or --abort. Do not pick
                        another commit, rebase, push, use gh, or write to GitHub.

                        Your final message is for the user. ByteQuay reruns the validation and
                        inspects Git itself; saying it passes does not make it pass.
                        """)
                .toString();
    }

    private static void appendConflicts(StringBuilder prompt, List<ConflictPath> conflicts)
    {
        if (conflicts.isEmpty()) {
            return;
        }
        prompt.append("Conflict files:\n");
        conflicts.stream().limit(MAX_LISTED_PATHS).forEach(conflict -> prompt
                .append("- ")
                .append(conflict.path())
                .append(" (")
                .append(conflict.type())
                .append(conflict.module().map(module -> ", module " + module).orElse(""))
                .append(")\n"));
        if (conflicts.size() > MAX_LISTED_PATHS) {
            prompt.append("- and ").append(conflicts.size() - MAX_LISTED_PATHS).append(" more\n");
        }
    }

    private static String bounded(String diagnostic)
    {
        return diagnostic.length() <= 8 * 1024 ? diagnostic : diagnostic.substring(0, 8 * 1024);
    }
}
