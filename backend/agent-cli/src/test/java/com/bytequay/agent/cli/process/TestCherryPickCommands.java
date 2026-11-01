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
package com.bytequay.agent.cli.process;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class TestCherryPickCommands
{
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    @ParameterizedTest
    @ValueSource(strings = {
            "git commit --fixup=" + SHA,
            "git commit --allow-empty --fixup=" + SHA,
            "git commit --amend --no-edit",
            "/bin/zsh -lc 'git commit --fixup=" + SHA + "'",
            "bash -lc \"git commit --amend --no-edit\"",
            "git status --short",
            "git diff --check",
            "mvn -q -pl core test",
            "zsh -lc 'git log --oneline -3'"})
    void allowsTheFixupAndCommandsThatLeaveHistoryAlone(String command)
    {
        assertThat(CherryPickCommands.allows(command)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "git commit -m change",
            "git commit --fixup=HEAD",
            "git commit --fixup=" + SHA + " && git push",
            "git rebase -i HEAD~2",
            "git reset --hard HEAD~1",
            "git push origin HEAD",
            "git cherry-pick --continue",
            "/bin/zsh -lc 'git rebase --continue'",
            "bash -lc 'git status; git checkout -b other'"})
    void deniesEveryOtherHistoryChange(String command)
    {
        assertThat(CherryPickCommands.allows(command)).isFalse();
    }
}
