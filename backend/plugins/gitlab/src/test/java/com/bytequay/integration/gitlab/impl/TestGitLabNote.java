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
package com.bytequay.integration.gitlab.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestGitLabNote
{
    @Test
    void systemNoteShowsItsFirstLineAsPlainText()
    {
        assertThat(system("""
                added 2 commits

                <ul><li>1a2b3c4d - 1 commit from branch <code>main</code></li><li>5e6f7a8b - Merge main</li></ul>

                [Compare with previous version](/owner/repo/-/merge_requests/1/diffs?diff_id=2&start_sha=1a2b3c4d)""").timelineBody())
                .isEqualTo("added 2 commits");
        assertThat(system("changed title from **{-Old title-}** to **{+New title+}**").timelineBody())
                .isEqualTo("changed title from Old title to New title");
        assertThat(system("mentioned in [commit 1a2b3c4d](/owner/repo/-/commit/1a2b3c4d)").timelineBody())
                .isEqualTo("mentioned in commit 1a2b3c4d");
    }

    @Test
    void commentsKeepTheirMarkdown()
    {
        String body = "Please see **this** <br> [link](https://example.com)";

        assertThat(new GitLabNote(1L, body, null, "2026-01-01T00:00:00Z", false, false, null, null).timelineBody()).isEqualTo(body);
    }

    private static GitLabNote system(String body)
    {
        return new GitLabNote(1L, body, null, "2026-01-01T00:00:00Z", true, false, null, null);
    }
}
