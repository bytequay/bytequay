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
package com.bytequay.agent.cli.codex;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestTaskAllowScope
{
    @Test
    void namesACommandByItsFirstThreeWords()
    {
        assertThat(TaskAllowScope.of("/bin/zsh -lc \"gh issue list --repo trinodb/trino --state open --label 'correctness' --limit 100\""))
                .isEqualTo("gh issue list");
        assertThat(TaskAllowScope.of("/bin/zsh -lc 'gh issue list --repo trinodb/trino --label corruption'")).isEqualTo("gh issue list");
        assertThat(TaskAllowScope.of("bash -c 'npm run lint'")).isEqualTo("npm run lint");
        assertThat(TaskAllowScope.of("python3 scripts/check.py fast --verbose")).isEqualTo("python3 scripts/check.py fast");
    }

    @Test
    void coversOnlyTheIdenticalCommandWhenAShorterPrefixWouldCoverOtherWork()
    {
        assertThat(TaskAllowScope.of("git status --short")).isEqualTo("git status --short");
        assertThat(TaskAllowScope.of("/bin/zsh -lc 'sed -n 1,220p pom.xml'")).isEqualTo("/bin/zsh -lc 'sed -n 1,220p pom.xml'");
        assertThat(TaskAllowScope.of("gh api -X POST /repos/x/y/issues")).isEqualTo("gh api -X POST /repos/x/y/issues");
        assertThat(TaskAllowScope.of("/bin/zsh -lc 'gh label list --repo t/t && gh issue list --repo t/t'"))
                .isEqualTo("/bin/zsh -lc 'gh label list --repo t/t && gh issue list --repo t/t'");
        assertThat(TaskAllowScope.of("/bin/zsh -lc 'printf hello > /Users/me/probe.txt'"))
                .isEqualTo("/bin/zsh -lc 'printf hello > /Users/me/probe.txt'");
        assertThat(TaskAllowScope.of("/bin/zsh -lc 'echo $(date) now here'")).isEqualTo("/bin/zsh -lc 'echo $(date) now here'");
    }
}
