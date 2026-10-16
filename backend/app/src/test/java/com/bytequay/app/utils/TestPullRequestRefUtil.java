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

import com.bytequay.core.domain.PullRequestRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest
    @ValueSource(strings = {"no-slash", "/repo", "owner/"})
    void testParseRefRejectsMalformedRepository(String repository)
    {
        assertThatThrownBy(() -> PullRequestRefUtil.parseRef(repository, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
