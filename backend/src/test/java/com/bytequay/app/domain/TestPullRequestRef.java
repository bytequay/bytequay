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
package com.bytequay.app.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestPullRequestRef
{
    @Test
    void testParseRoundTripsFullName()
    {
        for (PullRequestRef ref : new PullRequestRef[] {
                PullRequestRef.of("apache", "trino", 1),
                PullRequestRef.of("trinodb", "trino", 24601),
                PullRequestRef.of("acme", "widgets", 7)}) {
            assertThat(PullRequestRef.parse(ref.fullName())).contains(ref);
        }
    }

    @Test
    void testParseRejectsMalformed()
    {
        for (String bad : new String[] {
                null, "", "norepo#1", "owner/repo#", "owner/repo#0", "owner/repo", "owner/repo#abc"}) {
            assertThat(PullRequestRef.parse(bad)).isEmpty();
        }
    }

    @Test
    void testParseTrimsNumber()
    {
        assertThat(PullRequestRef.parse("owner/repo# 42 "))
                .contains(PullRequestRef.of("owner", "repo", 42));
    }
}
