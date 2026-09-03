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

class TestReadOnlyGitHubCommand
{
    @Test
    void readsNeedNoCard()
    {
        assertThat(ReadOnlyGitHubCommand.isReadOnly("/bin/zsh -lc \"gh issue list --repo trinodb/trino --state open --label 'correctness' --limit 100\"")).isTrue();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh pr view 1234 --json title,body")).isTrue();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh pr checks 1234")).isTrue();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh search issues --repo trinodb/trino 'memory leak'")).isTrue();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh label list --repo trinodb/trino --limit 200")).isTrue();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh status")).isTrue();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh auth status")).isTrue();
    }

    @Test
    void anythingThatCouldWriteStaysACard()
    {
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh issue create --title x")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh pr merge 1234")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh pr checkout 1234")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh api repos/trinodb/trino/issues")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh run download 42")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh issue view 12 --web")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh pr list -w")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("/bin/zsh -lc 'gh label list --repo t/t && gh issue list --repo t/t'")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("/bin/zsh -lc 'gh issue list --repo t/t > issues.json'")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("git status")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh")).isFalse();
    }
}
