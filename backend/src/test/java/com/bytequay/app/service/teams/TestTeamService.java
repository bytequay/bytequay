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
package com.bytequay.app.service.teams;

import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestTeamService
{
    @Test
    void testQueryEmitsRepoQualifierAndAuthorPerLogin()
    {
        String query = TeamService.buildSearchQuery(
                "trino/trino",
                ImmutableList.of("ebyhr", "alice"));
        assertThat(query).isEqualTo("is:pr is:open repo:trino/trino author:ebyhr author:alice");
    }

    @Test
    void testQueryWithSingleAuthor()
    {
        String query = TeamService.buildSearchQuery("acme/widgets", ImmutableList.of("bob"));
        assertThat(query).isEqualTo("is:pr is:open repo:acme/widgets author:bob");
    }

    @Test
    void testQueryWithEmptyAuthorsStillIncludesRepo()
    {
        // Defensive: the caller should never pass an empty chunk, but if it
        // does the query still parses on GitHub's side ("is:pr is:open
        // repo:x") and simply returns every open PR. Ensures we don't blow
        // up generating it.
        String query = TeamService.buildSearchQuery("o/r", ImmutableList.of());
        assertThat(query).isEqualTo("is:pr is:open repo:o/r");
    }
}
