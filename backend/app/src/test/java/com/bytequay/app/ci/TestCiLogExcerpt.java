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

        CiLogExcerpt excerpt = CiLogExcerpt.from(source, 32 * 1024, 32 * 1024);

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

        CiLogExcerpt excerpt = CiLogExcerpt.from(source, 4096, 4096);

        assertThat(excerpt.text()).contains("error: original cause", "fatal: final diagnostic", "Process exited");
        assertThat(excerpt.text().getBytes(UTF_8).length).isLessThanOrEqualTo(4096);
    }

    @Test
    void keepsOriginalFormattingAndRepeatedLines()
    {
        String source = "2026-09-13T09:03:40.1Z \u001b[31merror: compilation failed\u001b[0m\n"
                + "2026-09-13T09:03:40.2Z downloading dependency\n"
                + "2026-09-13T09:03:40.3Z downloading dependency\n"
                + "2026-09-13T09:03:40.4Z downloading dependency\n"
                + "2026-09-13T09:03:40.5Z     at Example.main(Example.java:17)\n";

        CiLogExcerpt excerpt = CiLogExcerpt.from(source, 4096, 4096);

        assertThat(excerpt.text()).isEqualTo(source);
        assertThat(excerpt.compacted()).isFalse();
    }

    @Test
    void boundsUtf8AndKeepsFallbackContextWithoutRecognizedErrorWords()
    {
        String source = "Run ./validate.sh\n" + "日志🙂".repeat(15000) + "\nExpected 42, got 41\nDone";
        for (int budget : new int[] {1, 8, 128, 256, 1024, 4096}) {
            CiLogExcerpt excerpt = CiLogExcerpt.from(source, budget, budget);

            assertThat(excerpt.text().getBytes(UTF_8).length).isLessThanOrEqualTo(budget);
            assertThat(excerpt.text()).doesNotContain("\ufffd");
            assertThat(excerpt.compacted()).isTrue();
            assertThat(excerpt.diagnosticsMatched()).isFalse();
            if (budget >= 256) {
                assertThat(excerpt.text()).contains("Expected 42, got 41", "Done").doesNotContain("Run ./validate.sh");
            }
        }
    }

    @Test
    void onlyExitStatusReturnsTheLast32KiBAndStackMarkersAreDiagnostics()
    {
        String source = "setup at beginning\n" + "downloaded artifact\n".repeat(4000) + "##[error]Process completed with exit code 1.\n";
        CiLogExcerpt excerpt = CiLogExcerpt.from(source, 32 * 1024, 1024 * 1024);
        assertThat(excerpt.diagnosticsMatched()).isFalse();
        assertThat(excerpt.text()).isEqualTo(source.substring(source.length() - 32 * 1024));
        for (String diagnostic : new String[] {"    at Worker.call(Worker.java:1)\n", "    Suppressed: worker unavailable\n", "Caused by: timeout\n"}) {
            CiLogExcerpt matched = CiLogExcerpt.from(diagnostic + "    at Worker.end(Worker.java:2)\n", 32 * 1024, 32 * 1024);
            assertThat(matched.diagnosticsMatched()).isTrue();
            assertThat(matched.text()).startsWith(diagnostic);
        }
        String command = "##[group]Run ./validate.sh\n  MAVEN_OPTS: -XX:+ExitOnOutOfMemoryError\n##[endgroup]\nValidation complete\n";
        for (String ending : new String[] {"", "##[error]Process completed with exit code 0.\n", "##[error]Process completed with exit code 0\n"}) {
            assertThat(CiLogExcerpt.from(command + ending, 32 * 1024, 32 * 1024).diagnosticsMatched()).isFalse();
        }
    }

    @Test
    void leavesAlreadyCompactEvidenceUntouched()
    {
        CiLogExcerpt excerpt = CiLogExcerpt.from("error: invalid option\n  command: build --invalid", 4096, 4096);

        assertThat(excerpt.text()).isEqualTo("error: invalid option\n  command: build --invalid");
        assertThat(excerpt.compacted()).isFalse();
    }

    @Test
    void selectsFailedCommandOutputInsteadOfSetupOptionsAndEchoedScript()
    {
        String timestamp = "2026-10-01T06:18:41.0908244Z ";
        String setup = timestamp + "##[group]Run actions/checkout@v7.0.1\n"
                + timestamp + "env:\n"
                + timestamp + "  MAVEN_OPTS: -Xmx512M -XX:+ExitOnOutOfMemoryError\n"
                + timestamp + "##[endgroup]\n";
        String checkout = timestamp + " * [new branch] vector-api-error-messages -> origin/vector-api-error-messages\n"
                + (timestamp + " * [new tag] release -> release\n").repeat(1500);
        String command = timestamp + "##[group]Run ./check-commit-messages.sh\n"
                + timestamp + "\u001b[36;1mecho 'error: invalid base ref'\u001b[0m\n"
                + timestamp + "env:\n"
                + timestamp + "  MAVEN_OPTS: -XX:+ExitOnOutOfMemoryError\n"
                + timestamp + "##[endgroup]\n";
        String failure = timestamp + "Commit descriptions should wrap at 72 characters.\n"
                + timestamp + "6405a0d6df6d Abort output stream on file writer rollback\n"
                + timestamp + "  line 3: 89 characters\n"
                + timestamp + "  Rollback discards the pending upload instead of publishing the file and then deleting it.\n"
                + timestamp + "##[error]Process completed with exit code 1.\n";
        String cleanup = timestamp + "##[end-action outcome=failure;conclusion=failure]\n" + timestamp + "Post job cleanup.\n";

        for (String noise : new String[] {"", checkout}) {
            for (String compilerError : new String[] {"", timestamp + "[ERROR] Compilation failed.\n"}) {
                CiLogExcerpt excerpt = CiLogExcerpt.from(setup + noise + command + compilerError + failure + cleanup, 32 * 1024, 1024 * 1024);

                assertThat(excerpt.text()).contains(failure).doesNotContain("MAVEN_OPTS", "ExitOnOutOfMemoryError", "invalid base ref", "[new tag]");
                assertThat(excerpt.text()).startsWith("[... log lines omitted ...]\n" + compilerError + timestamp + "Commit descriptions");
                assertThat(excerpt.diagnosticsMatched()).isTrue();
                assertThat(excerpt.compacted()).isTrue();
            }
        }
    }

    @Test
    void aJobThatStopsWithoutReportingAFailureShowsItsUnfinishedStep()
    {
        String checkout = "##[group]Run actions/checkout@v6.0.2\n##[endgroup]\n"
                + " * [new branch] improve-error-handling -> origin/improve-error-handling\n"
                + " * [new tag] release -> release\n".repeat(3000);
        String tests = "##[group]Run ./mvnw test\n##[endgroup]\n"
                + "WARN SQL failure: restart transaction\n"
                + "[INFO] Running ExampleTest\n".repeat(2000)
                + "INFO last line before the runner was lost\n";

        CiLogExcerpt excerpt = CiLogExcerpt.from(checkout + tests, 32 * 1024, 1024 * 1024);

        assertThat(excerpt.text()).contains("SQL failure", "last line before the runner was lost").doesNotContain("[new branch]", "[new tag]");
        assertThat(excerpt.diagnosticsMatched()).isTrue();
    }

    @Test
    void preservesTheEntireFailedTestBeforeLaterPassingSummariesAndCleanup()
    {
        String failure = "[ERROR] ExampleTest.testQuery -- Time elapsed: 1 s <<< FAILURE!\n"
                + "java.lang.AssertionError:\n\nExpecting actual:\n  CONNECTION_FAILED\nto be in:\n  [EXPECTED_ERROR]\n"
                + "    at ExampleTest.testQuery(ExampleTest.java:42)\n"
                + "    Suppressed: QueryFailedException: worker unavailable\n"
                + "        at Worker.call(Worker.java:18)\n"
                + "        at Worker.call(Worker.java:18)\n"
                + "Caused by: java.net.SocketTimeoutException: " + "long diagnostic ".repeat(200) + "\n"
                + IntStream.range(0, 400).mapToObj(index -> "    at Worker.frame" + index + "(Worker.java:" + index + ")\n").collect(joining())
                + "    Suppressed: java.io.IOException: connection closed\n"
                + "        at Socket.close(Socket.java:99)\n"
                + "Caused by: java.util.concurrent.TimeoutException: final cause\n"
                + "    at Timer.expire(Timer.java:123)\n";
        String noise = IntStream.range(0, 3000)
                .mapToObj(index -> "[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in PassingTest" + index + "\n")
                .collect(joining());
        String source = noise + failure + noise + "[WARN] Error closing test worker\n##[error]Process completed with exit code 1.\n";

        CiLogExcerpt excerpt = CiLogExcerpt.from(source, 32 * 1024, 1024 * 1024);

        assertThat(excerpt.text()).contains(failure).doesNotContain("PassingTest1500", "line shortened", "previous line repeated");
        assertThat(excerpt.text()).contains("log lines omitted");
    }

    @Test
    void exceedsTheTargetToKeepAnOversizedStackIntact()
    {
        String failure = "[ERROR] ExampleTest.testQuery <<< ERROR!\r\n"
                + "java.lang.IllegalStateException: failure 日志🙂\r\n"
                + "    at Worker.repeat(Worker.java:42)\r\n".repeat(1400)
                + "Caused by: java.net.ConnectException: refused\r\n"
                + "    at Client.connect(Client.java:55)\r\n";
        String source = "[INFO] noise\n".repeat(4000) + failure + "[INFO] finished\n";

        CiLogExcerpt excerpt = CiLogExcerpt.from(source, 32 * 1024, 1024 * 1024);

        assertThat(excerpt.text()).contains(failure).doesNotContain("previous line repeated", "line shortened");
        assertThat(excerpt.text().getBytes(UTF_8).length).isGreaterThan(32 * 1024).isLessThan(1024 * 1024);
    }

    @Test
    void aHardResponseLimitOmitsAnOversizedStackAsAWhole()
    {
        String failure = "[ERROR] ExampleTest.testQuery <<< ERROR!\n"
                + "java.lang.IllegalStateException: failure\n"
                + "    at Worker.repeat(Worker.java:42)\n".repeat(1400)
                + "Caused by: java.net.ConnectException: refused\n"
                + "    at Client.connect(Client.java:55)\n";

        for (String prefix : new String[] {"", "[INFO] starting\n"}) {
            CiLogExcerpt excerpt = CiLogExcerpt.from(prefix + failure + "[INFO] finished\n", 4096, 4096);

            assertThat(excerpt.text()).doesNotContain("ExampleTest", "Worker.repeat", "ConnectException", "Client.connect");
            assertThat(excerpt.text()).contains("log lines omitted", "[INFO] finished");
            assertThat(excerpt.text().getBytes(UTF_8).length).isLessThanOrEqualTo(4096);
        }
    }

    @Test
    void keepsLogPrefixesAndEveryFrameOfAPrefixedException()
    {
        String failure = "[ERROR] ExampleTest.testQuery <<< FAILURE!\n"
                + "[ERROR] java.lang.AssertionError:\n[ERROR] \n[ERROR] expected: 1\n[ERROR]  but was: 2\n"
                + "[ERROR]     at Worker.repeat(Worker.java:42)\n".repeat(900)
                + "[ERROR] Caused by: java.io.IOException: actual cause\n"
                + "[ERROR]     at Worker.open(Worker.java:51)\n";

        CiLogExcerpt excerpt = CiLogExcerpt.from("[INFO] noise\n".repeat(4000) + failure + "[INFO] done\n", 32 * 1024, 1024 * 1024);

        assertThat(excerpt.text()).contains(failure);
    }

    @Test
    void aCompleteBlockCanUseTheEntireHardLimit()
    {
        String failure = "java.lang.IllegalStateException: failed\n" + "    at Worker.repeat(Worker.java:42)\n".repeat(1400);

        CiLogExcerpt excerpt = CiLogExcerpt.from(failure, 32 * 1024, failure.getBytes(UTF_8).length);

        assertThat(excerpt.text()).isEqualTo(failure);
        assertThat(excerpt.compacted()).isFalse();
    }
}
