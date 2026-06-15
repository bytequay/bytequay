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

class TestRemoteGitClassifier
{
    /** Commands that publish to / mutate GitHub — must be flagged. */
    private static final String[] BLOCKED = {
        "git push",
        "git push origin dev/x --force",
        "git push -u origin HEAD",
        "cd /repo && git push",
        "git -C /repo push",
        "git -c user.name=x push origin main",
        "git remote add origin https://github.com/x/y.git",
        "git remote set-url origin git@github.com:x/y.git",
        "git remote remove origin",
        "gh pr create --fill",
        "gh pr merge 5 --squash",
        "gh pr ready 5",
        "gh pr edit 5 --title x",
        "gh pr close 5",
        "gh release create v1.0",
        "gh api -X POST repos/x/y/pulls",
        "gh api --method DELETE repos/x/y/issues/1",
        "gh api -XPATCH repos/x/y",
        "curl https://api.github.com/repos/x/y/pulls -d @body.json",
        "wget https://github.com/x/y/archive/main.zip",
        "ls && gh pr create",
    };

    /** Read / local work — must NOT be flagged. */
    private static final String[] ALLOWED = {
        "git status",
        "git log --oneline -20",
        "git diff HEAD~1",
        "git commit -m \"fix\"",
        "git add -A",
        "git fetch origin",
        "git remote -v",
        "git remote get-url origin",
        "gh pr view 5",
        "gh pr list",
        "gh pr diff 5",
        "gh pr checks 5",
        "gh api repos/x/y/pulls",
        "gh api -X GET repos/x/y",
        "curl https://example.com/data.json",
        "echo pushing to origin",
        "ls && git status",
        "",
    };

    @Test
    void flagsRemoteMutations()
    {
        for (String cmd : BLOCKED) {
            assertThat(RemoteGitClassifier.findRemoteMutation(cmd))
                    .as("should block: %s", cmd)
                    .isPresent();
        }
    }

    @Test
    void leavesReadAndLocalWorkAlone()
    {
        for (String cmd : ALLOWED) {
            assertThat(RemoteGitClassifier.findRemoteMutation(cmd))
                    .as("should allow: %s", cmd)
                    .isEmpty();
        }
    }

    @Test
    void theMatchNamesTheBlockedCommandAndTheToolToUseInstead()
    {
        RemoteGitClassifier.Match match = RemoteGitClassifier.findRemoteMutation("git push").orElseThrow();
        assertThat(match.blocked()).isEqualTo("git push");
        assertThat(match.useInstead()).contains("push");
    }
}
