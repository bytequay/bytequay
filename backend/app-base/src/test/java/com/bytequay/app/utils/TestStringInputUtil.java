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

import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static com.bytequay.core.domain.Checks.normalizeNames;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestStringInputUtil
{
    @Test
    void normalizesNamesToAnImmutableDistinctListInEncounterOrder()
    {
        List<String> values = new ArrayList<>(ImmutableList.of(" bob ", "alice", "bob", "BOB"));

        List<String> normalized = normalizeNames(values, "assignees");
        values.clear();

        assertThat(normalized).containsExactly("bob", "alice", "BOB");
        assertThatThrownBy(() -> normalized.add("carol")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(normalizeNames(ImmutableList.of(), "labels")).isEmpty();
    }

    @Test
    void rejectsANullNamesList()
    {
        assertThatThrownBy(() -> normalizeNames(null, "labels"))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("labels is null");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void rejectsBlankNames(String value)
    {
        List<String> values = new ArrayList<>();
        values.add(value);

        assertThatThrownBy(() -> normalizeNames(values, "assignees"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("assignees is blank");
    }
}
