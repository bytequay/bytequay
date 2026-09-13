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
package com.bytequay.app.ci;

import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;

class TestCiLogExcerpt
{
    @Test
    void preservesFirstCauseStackContextAndEndingWhileDroppingBulkNoise()
    {
        String noise = IntStream.range(0, 12000).mapToObj(index -> "Downloaded dependency artifact-" + index + " from repository\n").collect(joining());
        String source = "./mvnw test\n"
                + "[ERROR] Failed to compile Example.java:42\n"
                + "  required: String\n  found: int\n"
                + noise
                + "java.lang.IllegalStateException: test fixture unavailable\n"
                + "    at ExampleTest.prepare(ExampleTest.java:19)\n"
                + "Caused by: java.net.ConnectException: Connection refused\n"
                + "    at Client.connect(Client.java:28)\n"
                + "##[error]Process completed with exit code 1.\n";

        CiLogExcerpt excerpt = CiLogExcerpt.from(source, 32 * 1024);

        assertThat(excerpt.compacted()).isTrue();
        assertThat(excerpt.text()).contains("Example.java:42", "required: String", "found: int",
                "ExampleTest.java:19", "Connection refused", "Client.java:28", "exit code 1", "log lines omitted");
        assertThat(excerpt.text()).doesNotContain("artifact-6000");
        assertThat(excerpt.text().getBytes(UTF_8).length).isLessThan(32 * 1024).isLessThan(source.getBytes(UTF_8).length / 10);
    }

    @Test
    void reservesBothDiagnosticsBeforeSpendingTheBudgetOnContext()
    {
        String source = "error: original cause " + "a".repeat(900) + "\n"
                + IntStream.range(0, 100).mapToObj(index -> "context " + index + " " + "b".repeat(1000) + "\n").collect(joining())
                + "fatal: final diagnostic " + "c".repeat(900) + "\nProcess exited";

        CiLogExcerpt excerpt = CiLogExcerpt.from(source, 4096);

        assertThat(excerpt.text()).contains("error: original cause", "fatal: final diagnostic", "Process exited");
        assertThat(excerpt.text().getBytes(UTF_8).length).isLessThanOrEqualTo(4096);
    }

    @Test
    void collapsesTimestampedDuplicatesWithoutLosingStackIndentation()
    {
        String source = "2026-09-13T09:03:40.1Z \u001b[31merror: compilation failed\u001b[0m\n"
                + "2026-09-13T09:03:40.2Z downloading dependency\n"
                + "2026-09-13T09:03:40.3Z downloading dependency\n"
                + "2026-09-13T09:03:40.4Z downloading dependency\n"
                + "2026-09-13T09:03:40.5Z     at Example.main(Example.java:17)\n";

        CiLogExcerpt excerpt = CiLogExcerpt.from(source, 4096);

        assertThat(excerpt.text()).isEqualTo("error: compilation failed\ndownloading dependency\n"
                + "[previous line repeated 2 more times]\n    at Example.main(Example.java:17)");
        assertThat(excerpt.compacted()).isTrue();
    }

    @Test
    void boundsUtf8AndKeepsFallbackContextWithoutRecognizedErrorWords()
    {
        String source = "Run ./validate.sh\n" + "日志🙂".repeat(15000) + "\nExpected 42, got 41\nDone";
        for (int budget : new int[] {1, 8, 128, 256, 1024, 4096}) {
            CiLogExcerpt excerpt = CiLogExcerpt.from(source, budget);

            assertThat(excerpt.text().getBytes(UTF_8).length).isLessThanOrEqualTo(budget);
            assertThat(excerpt.text()).doesNotContain("\ufffd");
            assertThat(excerpt.compacted()).isTrue();
            if (budget >= 256) {
                assertThat(excerpt.text()).contains("Run ./validate.sh", "Expected 42, got 41", "Done");
            }
        }
    }

    @Test
    void leavesAlreadyCompactEvidenceUntouched()
    {
        CiLogExcerpt excerpt = CiLogExcerpt.from("error: invalid option\n  command: build --invalid", 4096);

        assertThat(excerpt.text()).isEqualTo("error: invalid option\n  command: build --invalid");
        assertThat(excerpt.compacted()).isFalse();
    }
}
