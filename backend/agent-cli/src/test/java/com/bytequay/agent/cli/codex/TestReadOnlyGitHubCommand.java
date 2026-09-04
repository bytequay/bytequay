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
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh search code 'https://example.com/path'")).isTrue();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh label list --repo trinodb/trino --limit 200")).isTrue();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh repo view owner/repo --json name")).isTrue();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh status")).isTrue();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh auth status")).isTrue();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh api repos/trinodb/trino/issues --paginate --jq '.[].title'")).isTrue();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh api --method GET search/issues --silent")).isTrue();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh api 'repos/{owner}/{repo}/issues'")).isTrue();

        String issueSearch = "/bin/zsh -lc \"gh api "
                + "'search/issues?q=repo%3Atrinodb%2Ftrino+is%3Aissue+created%3A%3E%3D2026-09-01T16%3A00%3A00Z&sort=created&order=desc&per_page=100' "
                + "--jq '{total_count, items: [.items[] | {number, title, state, created_at, url: .html_url, author: .user.login}]}'\"";
        assertThat(ReadOnlyGitHubCommand.isReadOnly(issueSearch)).isTrue();
    }

    @Test
    void anythingThatCouldWriteStaysACard()
    {
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh issue create --title x")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh pr merge 1234")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh pr checkout 1234")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh api graphql -f query='{viewer{login}}'")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh api repos/x/y/issues -f title=x")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh api -X POST repos/x/y/issues")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh api repos/x/y/issues --input body.json")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh api repos/x/y/issues -H 'Authorization: token x'")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh api repos/x/y/issues --hostname example.com")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh api repos/x/y/issues --cache 1h")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh api repos/x/y/issues --verbose")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh api \"repos/$OWNER/$REPO/issues\"")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh api '//example.com/repos/x/y/issues'")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh api \"repos/$(whoami)/issues\"")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("/bin/zsh -lc \"gh api \\`mktemp\\`\"")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("/bin/zsh -lc \"gh api repos/\\$SECRET\"")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("/bin/zsh -lc \"gh api 'repos/$SECRET'\"")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("/bin/zsh -lc 'gh api 'repos/$SECRET''")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh api {-X,POST,repos/x/y/issues}")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh api =(printf repos/x/y/issues)")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh run download 42")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh issue view 12 --web")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh issue view 12 --web=true")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh pr list -w")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh pr list -w=true")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh auth status --show-token")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh auth status -at")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh auth status --hostname attacker.invalid")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh auth status -h attacker.invalid")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh issue list --repo attacker.invalid/x/y --limit 1")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh issue list -R 'git@attacker.invalid:x/y' --limit 1")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh pr list -dRattacker.invalid/x/y --limit 1")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh pr list -dRgit@attacker.invalid:x/y --limit 1")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh repo view attacker.invalid/x/y")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh repo view 'git@attacker.invalid:x/y'")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("/bin/zsh -lc 'gh label list --repo t/t && gh issue list --repo t/t'")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("/bin/zsh -lc 'gh issue list --repo t/t > issues.json'")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("/bin/zsh -lc 'gh issue list --repo t/t\ngh issue create --title x'")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh\fapi repos/x/y/issues")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("git status")).isFalse();
        assertThat(ReadOnlyGitHubCommand.isReadOnly("gh")).isFalse();
    }
}
