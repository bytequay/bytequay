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

    private static BootstrapProfile profile()
    {
        return new BootstrapProfile("github-actions", Set.of("maven"), List.of(), Map.of(),
                Set.of(), Set.of(), Map.of("module-a/", "module-a"),
                Map.of(), Map.of(), List.of());
    }
}
