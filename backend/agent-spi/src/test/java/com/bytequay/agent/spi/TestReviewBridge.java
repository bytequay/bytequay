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
    void requiresUniquePrefixedToolNames()
    {
        ReviewTool tool = new ReviewTool("review_finish", "finish", ImmutableMap.of("type", "object"), arguments -> "ok");
        assertThat(new ReviewBridge(ImmutableList.of(tool), "policy").tool("review_finish")).isPresent();
        assertThatThrownBy(() -> new ReviewTool("finish", "finish", ImmutableMap.of(), arguments -> "ok"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewBridge(ImmutableList.of(tool, tool), "policy"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewBridge(ImmutableList.of(), "policy"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
