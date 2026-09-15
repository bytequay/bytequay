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
package com.bytequay.core.context;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestContextInputFingerprint
{
    @Test
    void hashesSortedLengthFramedUtf8Inputs()
    {
        Map<String, String> inputs = new LinkedHashMap<>();
        inputs.put("scope:configuration", "é🧊");
        inputs.put("git:é.xml", "100644 blob " + "a".repeat(40));
        ContextInputFingerprint fingerprint = ContextInputFingerprint.create("scope:数据库", inputs, ImmutableList.of());
        ContextInputFingerprint reordered = ContextInputFingerprint.create("scope:数据库", ImmutableMap.of(
                "git:é.xml", "100644 blob " + "a".repeat(40), "scope:configuration", "é🧊"), ImmutableList.of());

        assertThat(fingerprint.digest()).contains("sha256:d090511c723a58b4a2a073a91d175f07ede3c7150872143f4a7751a5776d1f8c");
        assertThat(fingerprint.inputBytes()).isEqualTo(87);
        assertThat(reordered.digest()).isEqualTo(fingerprint.digest());
        inputs.put("git:é.xml", "ABSENT");
        assertThat(fingerprint.inputs().get("git:é.xml")).isEqualTo("100644 blob " + "a".repeat(40));
        assertThat(ContextInputFingerprint.create("scope:数据库", inputs, ImmutableList.of()).digest()).isNotEqualTo(fingerprint.digest());
    }

    @Test
    void distinguishesScopeAbsenceAndFrameBoundaries()
    {
        ContextInputFingerprint first = ContextInputFingerprint.create("project", ImmutableMap.of("ab", "c"), ImmutableList.of());
        ContextInputFingerprint reframed = ContextInputFingerprint.create("project", ImmutableMap.of("a", "bc"), ImmutableList.of());
        ContextInputFingerprint otherScope = ContextInputFingerprint.create("other", first.inputs(), ImmutableList.of());
        ContextInputFingerprint absent = ContextInputFingerprint.create("project", ImmutableMap.of("git:pom.xml", "ABSENT"), ImmutableList.of());
        ContextInputFingerprint empty = ContextInputFingerprint.create("project", ImmutableMap.of(), ImmutableList.of());

        assertThat(first.digest()).isNotEqualTo(reframed.digest()).isNotEqualTo(otherScope.digest());
        assertThat(absent.digest()).isNotEqualTo(empty.digest());
        assertThat(empty.digest()).isPresent();
        assertThatThrownBy(() -> new ContextInputFingerprint("project", reframed.inputs(), first.digest(), ImmutableList.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not match");
    }

    @Test
    void incompleteObservationsRetainInputsWithoutClaimingDigest()
    {
        List<String> gaps = new ArrayList<>(ImmutableList.of("pom.xml could not be read"));
        ContextInputFingerprint incomplete = ContextInputFingerprint.create("project", ImmutableMap.of("scope:configuration", "saved-config"), gaps);
        gaps.clear();

        assertThat(incomplete.digest()).isEmpty();
        assertThat(incomplete.inputs()).containsEntry("scope:configuration", "saved-config");
        assertThat(incomplete.gaps()).containsExactly("pom.xml could not be read");
        assertThatThrownBy(() -> new ContextInputFingerprint("project", ImmutableMap.of(), Optional.empty(), ImmutableList.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Only complete");
        Optional<String> digest = ContextInputFingerprint.create("project", ImmutableMap.of(), ImmutableList.of()).digest();
        assertThatThrownBy(() -> new ContextInputFingerprint("project", ImmutableMap.of(), digest, ImmutableList.of("Missing input")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Only complete");
    }

    @Test
    void boundsInputCountKeysValuesAndTotalUtf8Bytes()
    {
        Map<String, String> inputs = new LinkedHashMap<>();
        IntStream.range(0, 16).forEach(index -> inputs.put(String.valueOf(1000 + index), "é".repeat(1000)));
        inputs.put("rest", "a".repeat(700));
        assertThat(ContextInputFingerprint.create("project", inputs, ImmutableList.of()).inputBytes()).isEqualTo(ContextInputFingerprint.MAXIMUM_INPUT_BYTES);
        inputs.put("rest", "a".repeat(701));
        assertThatThrownBy(() -> ContextInputFingerprint.create("project", inputs, ImmutableList.of())).isInstanceOf(IllegalArgumentException.class);

        assertThat(ContextInputFingerprint.create("project", ImmutableMap.of("é".repeat(600), "é".repeat(1000)), ImmutableList.of()).inputBytes()).isEqualTo(3200);
        assertThatThrownBy(() -> ContextInputFingerprint.create("project", ImmutableMap.of("é".repeat(601), "ABSENT"), ImmutableList.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContextInputFingerprint.create("project", ImmutableMap.of("git:input", "é".repeat(1001)), ImmutableList.of()))
                .isInstanceOf(IllegalArgumentException.class);
        Map<String, String> many = new LinkedHashMap<>();
        IntStream.range(0, 128).forEach(index -> many.put("input-" + index, "ABSENT"));
        assertThat(ContextInputFingerprint.create("project", many, ImmutableList.of()).inputs()).hasSize(128);
        many.put("one-too-many", "ABSENT");
        assertThatThrownBy(() -> ContextInputFingerprint.create("project", many, ImmutableList.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void boundsIncompleteObservationDiagnostics()
    {
        List<String> gaps = IntStream.range(0, 10).mapToObj(index -> "Missing input " + index).collect(toImmutableList());
        assertThat(ContextInputFingerprint.create("project", ImmutableMap.of(), gaps).gaps()).hasSize(10);
        List<String> tooMany = ImmutableList.<String>builder().addAll(gaps).add("Another missing input").build();
        assertThatThrownBy(() -> ContextInputFingerprint.create("project", ImmutableMap.of(), tooMany)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ContextInputFingerprint.create("project", ImmutableMap.of(), ImmutableList.of("é".repeat(1000))).digest()).isEmpty();
        assertThatThrownBy(() -> ContextInputFingerprint.create("project", ImmutableMap.of(), ImmutableList.of("é".repeat(1001))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
