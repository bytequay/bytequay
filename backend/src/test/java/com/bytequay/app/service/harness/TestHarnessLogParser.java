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
package com.bytequay.app.service.harness;

import com.bytequay.app.service.harness.HarnessModels.BootstrapProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class TestHarnessLogParser
{
    private final HarnessLogParser parser = new HarnessLogParser();

    @Test
    void normalizesVolatileLogValuesAndDeduplicatesFailures()
    {
        String first = "Caused by: module-a/src/Widget.java java.lang.AssertionError: /tmp/run_abcdef "
                + "2026-07-24T01:02:03Z at Widget.java:123)";
        String second = "Caused by: module-a/src/Widget.java java.lang.AssertionError: /private/tmp/run_fedcba "
                + "2026-07-24T09:08:07Z at Widget.java:987)";

        assertThat(HarnessLogParser.normalize(first))
                .isEqualTo(HarnessLogParser.normalize(second));
        assertThat(parser.parse("run", 7, "tests", first + "\n" + second, profile()))
                .singleElement()
                .satisfies(failure -> {
                    assertThat(failure.module()).isEqualTo("module-a");
                    assertThat(failure.signature()).contains("<tmp>", "<ts>", ":<n>)");
                });
    }

    @Test
    void scrubsElapsedTimesAndIdentityHashesButKeepsRealDifferences()
    {
        // Both appear on essentially every surefire failure line and vary per run.
        assertThat(HarnessLogParser.normalize(
                "testWriterScaling(io.x.TestFoo)  Time elapsed: 0.052 s  <<< FAILURE!"))
                .isEqualTo(HarnessLogParser.normalize(
                        "testWriterScaling(io.x.TestFoo)  Time elapsed: 0.071 s  <<< FAILURE!"));
        assertThat(HarnessLogParser.normalize("expected: <io.x.Row@1f3a9c2b> but was: <io.x.Row@7de4a01f>"))
                .isEqualTo(HarnessLogParser.normalize("expected: <io.x.Row@9ab2ff31> but was: <io.x.Row@0c1d88ee>"));

        // Over-scrubbing is the opposite failure: two real bugs must not collapse.
        assertThat(HarnessLogParser.normalize("testAlpha(io.x.TestFoo) Time elapsed: 0.05 s <<< FAILURE!"))
                .isNotEqualTo(HarnessLogParser.normalize(
                        "testBeta(io.x.TestFoo) Time elapsed: 0.05 s <<< FAILURE!"));
        assertThat(HarnessLogParser.normalize("cannot find symbol: method getFoo()"))
                .isNotEqualTo(HarnessLogParser.normalize("cannot find symbol: method getBar()"));
        assertThat(HarnessLogParser.normalize("expected:<[5]> but was:<[7]>"))
                .isNotEqualTo(HarnessLogParser.normalize("expected:<[5]> but was:<[9]>"));
    }

    @Test
    void stripsActionsTimestampPrefixSoTheRootCauseWalkStillFires()
    {
        String body = """
                [ERROR] Failed to execute goal on project module-a: Compilation failure
                Caused by: java.lang.NoSuchMethodError: io.x.Bar.baz()
                """;
        String raw = body.lines()
                .map(line -> "2026-08-04T10:11:12.3456789Z " + line)
                .collect(Collectors.joining("\n"));

        // Unstripped, every startsWith("caused by:") test misses and the signature
        // degrades to the outer wrapper line.
        assertThat(parser.parse("run", 7, "build", raw, profile()))
                .singleElement()
                .satisfies(failure -> assertThat(failure.signature())
                        .contains("NoSuchMethodError")
                        .doesNotContain("2026-08-04"));

        // The prefixed and unprefixed logs must fingerprint identically.
        assertThat(parser.parse("run", 7, "build", raw, profile()).getFirst().signature())
                .isEqualTo(parser.parse("run", 7, "build", body, profile()).getFirst().signature());
    }

    private static BootstrapProfile profile()
    {
        return new BootstrapProfile("github-actions", Set.of("maven"), List.of(), Map.of(),
                Set.of(), Set.of(), Map.of("module-a/", "module-a"),
                Map.of(), Map.of(), List.of());
    }
}
