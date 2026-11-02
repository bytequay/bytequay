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

import org.junit.jupiter.api.Test;

import static com.bytequay.core.domain.Checks.isSha;
import static org.assertj.core.api.Assertions.assertThat;

class TestChecks
{
    @Test
    void acceptsOnlyFullLowercaseObjectIds()
    {
        assertThat(isSha("a".repeat(40))).isTrue();
        assertThat(isSha("0".repeat(64))).isTrue();
        assertThat(isSha("a".repeat(39))).isFalse();
        assertThat(isSha("a".repeat(41))).isFalse();
        assertThat(isSha("A".repeat(40))).isFalse();
        assertThat(isSha("a".repeat(40) + "\n")).isFalse();
    }
}
