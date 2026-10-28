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
package com.bytequay.app.workspace;

import com.bytequay.app.git.command.GitCommandRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

/** Includes intermediate blobs and commit metadata, even when the final diff deletes the disclosure. */
@Component
public class PublicationContent
{
    static final int MAXIMUM_CHARACTERS = 180_000;
    private final GitCommandRunner git;

    public PublicationContent(GitCommandRunner git)
    {
        this.git = requireNonNull(git, "git is null");
    }

    public String read(Path worktree, String base, String head)
    {
        checkState(base.matches("[0-9a-f]{40,64}") && head.matches("[0-9a-f]{40,64}"), "Privacy check requires exact Git revisions");
        checkState(run(worktree, "rev-parse", "--is-shallow-repository").strip().equals("false"), "Privacy check cannot inspect shallow history");
        String objects = run(worktree, "rev-list", "--objects", head, "^" + base, "--");
        StringBuilder content = new StringBuilder(objects);
        for (String entry : objects.lines().toList()) {
            String id = entry.split(" ", 2)[0];
            String type = run(worktree, "cat-file", "-t", id).strip();
            if (type.equals("commit")) {
                String changes = run(worktree, "diff-tree", "--no-commit-id", "--raw", "-r", "--no-renames",
                        "--no-ext-diff", "--no-textconv", "--ignore-submodules=none", base, id, "--");
                // Tree objects also contain unchanged links; only links introduced by this history need new coverage.
                checkState(changes.lines().noneMatch(line -> line.startsWith(":") && line.split(" ", 3)[1].equals("160000")),
                        "Privacy check cannot inspect outgoing submodule content");
            }
            if (type.equals("commit") || type.equals("blob") || type.equals("tree")) {
                String value = run(worktree, "cat-file", "-p", id);
                checkState(value.indexOf('\0') < 0 && value.indexOf('\ufffd') < 0 && !value.startsWith("version https://git-lfs.github.com/spec/"),
                        "Privacy check cannot inspect a binary or LFS object; replace it with a sanitized text reproduction before publication");
                content.append('\n').append(value);
                checkState(content.length() <= MAXIMUM_CHARACTERS, "Privacy check coverage limit exceeded; split the change before publication");
            }
        }
        checkState(content.length() <= MAXIMUM_CHARACTERS, "Privacy check coverage limit exceeded");
        return content.toString();
    }

    private String run(Path worktree, String... arguments)
    {
        var result = git.runLocalBoundedStdout(worktree, Duration.ofSeconds(60), MAXIMUM_CHARACTERS * 4, Arrays.asList(arguments));
        checkState(result.successful() && !result.truncated(), "Privacy check could not completely read Git objects");
        return result.output();
    }
}
