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

import com.bytequay.app.service.harness.HarnessLogParser.ParsedFailure;
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

        // A generated name is often several hex segments; leaving any of them in
        // is as fatal to the fingerprint as leaving all of them.
        assertThat(HarnessLogParser.normalize("Expecting empty but was: [\"tmp_trino_05cae137_7ee766c9\"]"))
                .isEqualTo(HarnessLogParser.normalize(
                        "Expecting empty but was: [\"tmp_trino_bcfdb9df_c5453dc4\"]"));

        // Over-scrubbing is the opposite failure: two real bugs must not collapse.
        assertThat(HarnessLogParser.normalize("cache_abcdef_bar missing"))
                .isNotEqualTo(HarnessLogParser.normalize("cache_abcdef_baz missing"));
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

    /**
     * The shape a real suite produces: the failing tests are reported where they
     * fail, thousands of lines of teardown noise follow, and surefire's recap is
     * the last word. The recap names the tests; the sections it summarises hold
     * the stacks, and the two have to be joined back together.
     */
    @Test
    void surefireRecapIsExpandedIntoTheSectionItSummarisesAndBeatsTeardownNoise()
    {
        String log = """
                [ERROR] Tests run: 3, Failures: 2, Errors: 0, Skipped: 0, Time elapsed: 41.01 s <<< FAILURE! -- in io.trino.plugin.postgresql.TestPostgreSqlRollbacks
                [ERROR] io.trino.plugin.postgresql.TestPostgreSqlRollbacks.testRollbackCreateTableAsSelect -- Time elapsed: 0.988 s <<< FAILURE!
                java.lang.AssertionError:

                Expecting empty but was: ["tmp_trino_05cae137_7ee766c9"]
                \tat io.trino.plugin.postgresql.TestPostgreSqlRollbacks.testRollbackCreateTableAsSelect(TestPostgreSqlRollbacks.java:83)

                [ERROR] io.trino.plugin.postgresql.TestPostgreSqlRollbacks.testRollbackMerge -- Time elapsed: 31.37 s <<< FAILURE!
                java.lang.AssertionError:

                Expecting empty but was: ["tmp_trino_bcfdb9df_c5453dc4"]
                \tat io.trino.plugin.postgresql.TestPostgreSqlRollbacks.testRollbackMerge(TestPostgreSqlRollbacks.java:182)
                \tSuppressed: java.lang.AssertionError:
                \t\tat io.trino.plugin.postgresql.TestPostgreSqlRollbacks.ensureNoTemporaryTablesRemain(TestPostgreSqlRollbacks.java:66)

                [INFO] Shutting down
                ERROR page-buffer-client-callback-0 io.trino.operator.HttpPageBufferClient Request to delete http://127.0.0.1:39849/v1/task/20260806_082234_00491 failed
                io.trino.server.remotetask.SimpleHttpResponseHandler$ServiceUnavailableException: Server returned SERVICE_UNAVAILABLE: http://127.0.0.1:43739/v1/task/x
                ERROR page-buffer-client-callback-1 io.trino.operator.HttpPageBufferClient Request to delete http://127.0.0.1:42415/v1/task/20260806_082302_00518 failed
                [INFO] Results:
                [INFO]
                [ERROR] Failures:
                [ERROR]   TestPostgreSqlRollbacks.testRollbackCreateTableAsSelect:83
                Expecting empty but was: ["tmp_trino_05cae137_7ee766c9"]
                [ERROR]   TestPostgreSqlRollbacks.testRollbackMerge:182
                Expecting empty but was: ["tmp_trino_bcfdb9df_c5453dc4"]
                [ERROR]   TestRemoteQueryCommentLogging.testShouldLogContextInComment:61
                expected: 1
                 but was: 0
                [INFO]
                [ERROR] Tests run: 714, Failures: 3, Errors: 0, Skipped: 73
                [INFO] BUILD FAILURE
                [ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.5:test (default-test) on project trino-postgresql: There are test failures.
                [ERROR] See /home/runner/work/trino_new/plugin/trino-postgresql/target/surefire-reports for the individual test results.
                """;

        List<ParsedFailure> failures = parser.parse("run", 7, "test (plugin/trino-postgresql)", log, profile());

        // The three the suite named — not one per teardown task, and not the
        // goal-level "[ERROR] See …/surefire-reports" line beside them.
        assertThat(failures).hasSize(3);
        assertThat(failures).extracting(ParsedFailure::testMethod).containsExactly(
                "testRollbackCreateTableAsSelect", "testRollbackMerge", "testShouldLogContextInComment");
        assertThat(failures).noneSatisfy(failure ->
                assertThat(failure.signature()).contains("SERVICE_UNAVAILABLE"));

        // Expanded back to the section, so the agent gets the stack and the
        // suppressed cause the recap line does not carry.
        assertThat(failures.get(1).logExcerpt())
                .contains("TestPostgreSqlRollbacks.java:182")
                .contains("Suppressed: java.lang.AssertionError")
                .doesNotContain("testRollbackCreateTableAsSelect");

        // A recap entry whose section was cut still stands on its own message.
        assertThat(failures.get(2).logExcerpt()).contains("expected: 1");
        assertThat(failures.get(2).signature()).contains("testShouldLogContextInComment", "expected: 1");

        // The per-run temporary table name must not enter the fingerprint, or the
        // same flake never matches itself twice.
        assertThat(failures.getFirst().signature())
                .contains("testRollbackCreateTableAsSelect", "Expecting empty")
                .doesNotContain("05cae137");
    }

    /**
     * A class that dies building itself is reported against the class, with no
     * method on the header line, while the recap still names the method that
     * called it. Matching only the method form loses the stack entirely.
     */
    @Test
    void aClassLevelSectionIsFoundFromARecapEntryThatNamesAMethod()
    {
        String log = """
                [ERROR] Tests run: 1, Failures: 0, Errors: 1, Skipped: 0, Time elapsed: 1.311 s <<< FAILURE! -- in io.trino.plugin.google.sheets.TestGoogleSheets
                [ERROR] io.trino.plugin.google.sheets.TestGoogleSheets -- Time elapsed: 1.311 s <<< ERROR!
                com.google.api.client.auth.oauth2.TokenResponseException:
                400 Bad Request
                \tat io.trino.plugin.google.sheets.TestGoogleSheets.createSpreadsheetWithTestdata(TestGoogleSheets.java:84)
                [ERROR] io.trino.plugin.google.sheets.TestGoogleSheetsWithoutMetadataSheetId.testOther -- Time elapsed: 0.5 s <<< FAILURE!
                java.lang.AssertionError: something else entirely
                [INFO] Results:
                [ERROR] Errors:
                [ERROR]   TestGoogleSheets.createQueryRunner:62->createSpreadsheetWithTestdata:84 » TokenResponse 400 Bad Request
                {
                  "error": "invalid_grant"
                }
                """;

        assertThat(parser.parse("run", 7, "test", log, profile()))
                .singleElement()
                .satisfies(failure -> {
                    assertThat(failure.testMethod()).isEqualTo("createQueryRunner");
                    assertThat(failure.logExcerpt())
                            .contains("TokenResponseException", "TestGoogleSheets.java:84")
                            // TestGoogleSheets must not swallow TestGoogleSheetsWithoutMetadataSheetId.
                            .doesNotContain("something else entirely");
                    assertThat(failure.signature()).contains("invalid_grant");
                });
    }

    private static BootstrapProfile profile()
    {
        return new BootstrapProfile("github-actions", Set.of("maven"), List.of(), Map.of(),
                Set.of(), Set.of(), Map.of("module-a/", "module-a"),
                Map.of(), Map.of(), List.of());
    }
}
