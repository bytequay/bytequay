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
package com.bytequay.app.utils;

import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.RepoRef;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestPullRequestRefUtil
{
    @Test
    void testParseRefSplitsOwnerAndRepo()
    {
        PullRequestRef ref = PullRequestRefUtil.parseRef("trinodb/trino", 42);

        assertThat(ref.owner()).isEqualTo("trinodb");
        assertThat(ref.repo()).isEqualTo("trino");
        assertThat(ref.number()).isEqualTo(42);
    }

    @Test
    void testParseRefRejectsRepoWithoutSlash()
    {
        assertThatThrownBy(() -> PullRequestRefUtil.parseRef("no-slash", 1))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void testParseRefRejectsBlankOwner()
    {
        assertThatThrownBy(() -> PullRequestRefUtil.parseRef("/repo", 1))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void testParseRefRejectsBlankRepo()
    {
        assertThatThrownBy(() -> PullRequestRefUtil.parseRef("owner/", 1))
                .isInstanceOf(ResponseStatusException.class);
    }

    // parseRepoRef is the number-free variant used by reaction endpoints
    // (those URLs target a comment id from the path, not a PR number).
    // The 0-as-placeholder workaround used to trip parseRef's
    // number-must-be-positive invariant — these tests pin the new
    // helper's behaviour so that bug stays fixed.

    @Test
    void testParseRepoRefSplitsOwnerAndRepo()
    {
        RepoRef ref = PullRequestRefUtil.parseRepoRef("apache/trino");

        assertThat(ref.owner()).isEqualTo("apache");
        assertThat(ref.repo()).isEqualTo("trino");
    }

    @Test
    void testParseRepoRefAcceptsRepoNamesWithDots()
    {
        RepoRef ref = PullRequestRefUtil.parseRepoRef("foo/bar.baz");

        assertThat(ref.owner()).isEqualTo("foo");
        assertThat(ref.repo()).isEqualTo("bar.baz");
    }

    @Test
    void testParseRepoRefRejectsMissingSlash()
    {
        assertThatThrownBy(() -> PullRequestRefUtil.parseRepoRef("trinodb"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void testParseRepoRefRejectsBlankSegments()
    {
        assertThatThrownBy(() -> PullRequestRefUtil.parseRepoRef("/trino"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> PullRequestRefUtil.parseRepoRef("trinodb/"))
                .isInstanceOf(ResponseStatusException.class);
    }
}
