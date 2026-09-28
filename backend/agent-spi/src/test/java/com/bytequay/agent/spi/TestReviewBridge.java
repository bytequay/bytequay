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
package com.bytequay.agent.spi;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestReviewBridge
{
    @Test
    void refusesRemoteWritesAndCredentialReadsBeforeAnyCard()
    {
        for (String command : ImmutableList.of(
                "git push origin HEAD",
                "git -C /tmp/x push --force",
                "cd plugin && git push",
                "gh pr comment 30304 --body hi",
                "gh pr review --approve",
                "gh api -X POST repos/o/r/issues/1/comments -f body=x",
                "gh api repos/o/r/pulls --method PATCH",
                "gh auth token",
                "cat ~/.config/gh/hosts.yml",
                "cat $HOME/.git-credentials",
                "security find-generic-password -s github",
                "echo hi; git push")) {
            assertThat(ReviewBridge.deniedReason(command)).as(command).isPresent();
        }
        for (String command : ImmutableList.of(
                "git status",
                "git log --oneline -5",
                "git diff HEAD~1",
                "gh pr view 30304",
                "gh api repos/o/r/pulls/1",
                "./mvnw -pl plugin/trino-iceberg test -Dtest=TestIcebergSortField",
                "cat README.md",
                "rg pushback src/",
                "")) {
            assertThat(ReviewBridge.deniedReason(command)).as(command).isEmpty();
        }
    }

    @Test
    void keepsPrivateExperimentsOutOfHistoryWhileAllowingIndexOperations()
    {
        for (String command : ImmutableList.of(
                "git commit -am 'Keep test'",
                "git -C /tmp/x commit --amend --no-edit",
                "git -C '/tmp/review worktree' -c user.name=Reviewer commit -m test",
                "/usr/bin/git --no-optional-locks commit -am test",
                "sh -lc 'git commit -am test'",
                "env GIT_CONFIG_NOSYSTEM=1 git commit -am test",
                "git commit-tree HEAD^{tree} -p HEAD -m experiment",
                "git update-ref refs/heads/task HEAD",
                "git symbolic-ref HEAD refs/heads/other",
                "git symbolic-ref -m 'Switch branch' HEAD refs/heads/other",
                "git symbolic-ref -d HEAD",
                "git symbolic-ref --delete refs/heads/alias",
                "git merge experiment",
                "git rebase main",
                "git cherry-pick other",
                "git revert HEAD",
                "git am experiment.patch",
                "git reset --soft HEAD~1",
                "git reset HEAD~1",
                "git reset --hard HEAD~1",
                "git reset HEAD --hard HEAD~1",
                "git reset -q another-branch",
                "git switch -c experiment",
                "git checkout -b experiment",
                "git checkout -q -B experiment HEAD",
                "git checkout --orphan experiment",
                "git branch experiment",
                "git branch -f task other",
                "git pull --ff-only")) {
            assertThat(ReviewBridge.deniedReason(command)).as(command).hasValueSatisfying(reason -> assertThat(reason).contains("uncommitted"));
        }
        for (String command : ImmutableList.of(
                "git show HEAD:src/HistoryTable.java",
                "git log --grep commit",
                "git merge-base HEAD origin/main",
                "git symbolic-ref HEAD",
                "git symbolic-ref --short HEAD",
                "git symbolic-ref -q HEAD",
                "git symbolic-ref --quiet --short HEAD",
                "git symbolic-ref refs/remotes/origin/HEAD",
                "git diff --cached",
                "git status --porcelain=v1 --untracked-files=all",
                "git branch",
                "git branch --show-current",
                "git branch -av",
                "git branch --list experiment",
                "git branch --contains HEAD",
                "git checkout -q src/HistoryTable.java",
                "git checkout HEAD -- src/HistoryTable.java",
                "git restore --source HEAD --worktree src/HistoryTable.java",
                "git restore -s HEAD -W src/HistoryTable.java",
                "git status && git add TestExperiment.java",
                "git --git-dir=/tmp/repo/.git update-index --add TestExperiment.java",
                "git write-tree",
                "git restore --staged TestExperiment.java",
                "git restore -SW TestExperiment.java",
                "git apply --cached experiment.patch",
                "git apply --index experiment.patch",
                "git apply --3way experiment.patch",
                "git apply -3 experiment.patch",
                "git apply experiment.patch",
                "git reset",
                "git reset HEAD",
                "git reset --hard",
                "git reset --hard HEAD",
                "git reset -q --hard HEAD",
                "git reset -q",
                "git reset --quiet HEAD",
                "git reset -- TestExperiment.java",
                "git reset HEAD -- TestExperiment.java",
                "git reset HEAD~1 -- TestExperiment.java",
                "git reset --quiet HEAD~1 -- TestExperiment.java",
                "git -C '/tmp/review checkout' reset HEAD -- TestExperiment.java",
                "git reset HEAD && ./mvnw test",
                "sh -lc 'git reset HEAD'",
                "cat > TestExperiment.java <<'EOF'\nclass TestExperiment {}\nEOF",
                "./mvnw test -Dtest=TestExperiment")) {
            assertThat(ReviewBridge.deniedReason(command)).as(command).isEmpty();
        }
    }

    @Test
    void supportsPrivateReviewPolicyWithoutReportingTools()
    {
        ReviewBridge review = new ReviewBridge(ImmutableList.of(), "Private experiments must stay uncommitted");
        assertThat(review.tools()).isEmpty();
        assertThat(review.tool("review_finish")).isEmpty();
        assertThat(review.instruction()).isEqualTo("Private experiments must stay uncommitted");
    }

    @Test
    void requiresUniqueReviewToolNamesAndOnlyFixedRepositoryContextExtensions()
    {
        ReviewTool tool = new ReviewTool("review_finish", "finish", ImmutableMap.of("type", "object"), arguments -> "ok");
        assertThat(new ReviewBridge(ImmutableList.of(tool), "policy").tool("review_finish")).isPresent();
        assertThatThrownBy(() -> new ReviewTool("finish", "finish", ImmutableMap.of(), arguments -> "ok"))
                .isInstanceOf(IllegalArgumentException.class);
        for (String name : ImmutableList.of("repository_context_read", "repository_context_prepare", "repository_context_progress",
                "repository_context_discovery_read", "repository_context_discovery_submit")) {
            ReviewTool context = new ReviewTool(name, name, ImmutableMap.of("type", "object"), arguments -> "ok");
            assertThat(new ReviewBridge(ImmutableList.of(context), "policy").tool(name)).isPresent();
        }
        assertThatThrownBy(() -> new ReviewTool("repository_context_delete", "delete", ImmutableMap.of(), arguments -> "ok"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewBridge(ImmutableList.of(tool, tool), "policy"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewBridge(ImmutableList.of(), ""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
