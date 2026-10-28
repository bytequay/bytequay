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
package com.bytequay.core.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestGitHubIssueSource
{
    @ParameterizedTest
    @CsvSource({
            "github.com, org/repo, https://github.com/org/repo/issues/7",
            "https://gitlab.com, org/repo, https://gitlab.com/org/repo/-/issues/7",
            "https://gitlab.com, org/repo, https://gitlab.com/org/repo/-/work_items/7",
            "https://git.example.com:8443/gitlab, group/subgroup/repo, https://git.example.com:8443/gitlab/group/subgroup/repo/-/work_items/7"})
    void acceptsIssueUrlsWithoutChangingTheirIdentity(String host, String repo, String url)
    {
        GitHubIssueSource source = new GitHubIssueSource(host, 70, repo, 7, url, "Fix selection", "Context", Instant.EPOCH);

        assertThat(source.url()).isEqualTo(url);
        assertThat(source.host()).isEqualTo(host);
        assertThat(source.repo()).isEqualTo(repo);
        assertThat(source.issueId()).isEqualTo(70);
        assertThat(source.number()).isEqualTo(7);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://other.example.com:8443/gitlab/group/subgroup/repo/-/work_items/7",
            "https://git.example.com:9443/gitlab/group/subgroup/repo/-/work_items/7",
            "https://git.example.com:8443/other/group/subgroup/repo/-/work_items/7",
            "https://git.example.com:8443/gitlab/group/other/repo/-/work_items/7",
            "https://git.example.com:8443/gitlab/group/subgroup/repo/-/work_items/8"})
    void rejectsWorkItemUrlsForAnotherIdentity(String url)
    {
        assertThatThrownBy(() -> new GitHubIssueSource("https://git.example.com:8443/gitlab", 70, "group/subgroup/repo", 7,
                url, "Fix selection", "Context", Instant.EPOCH)).isInstanceOf(IllegalArgumentException.class);
    }
}
