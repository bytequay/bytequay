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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The parser against whole GitHub Actions job logs, kept verbatim under
 * {@code src/test/resources/harness/ci} and gzipped for size.
 *
 * <p>Hand-written fixtures cannot cover what these do. The failure a run has to
 * find sits thousands of lines from the end, behind a teardown storm that mints
 * plausible-looking errors of its own, and the shape of the surrounding text —
 * which is what every rule here keys on — is not something worth guessing at.
 * Each case asserts the exact failures the parser yields, so a rule that starts
 * matching more or less than it did says so.
 */
class TestHarnessLogParserOnCapturedLogs
{
    private final HarnessLogParser parser = new HarnessLogParser();

    /**
     * chenjian2664/trino_new run 31081060186, job 92552903460 — three failing
     * tests reported where they failed, at line 5,005 of 9,881, then four
     * thousand lines of query-runner teardown, then surefire's recap.
     */
    @Test
    void everyFailingTestIsFoundBehindTheTeardownStormThatFollowsIt()
    {
        List<ParsedFailure> failures = parse("surefire-method-sections");

        assertThat(failures).extracting(ParsedFailure::signature).containsExactly(
                "TestPostgreSqlRollbacks.testRollbackCreateTableAsSelect Expecting empty but was: [\"tmp_trino\"]",
                "TestPostgreSqlRollbacks.testRollbackMerge Expecting empty but was: [\"tmp_trino\"]",
                "TestRemoteQueryCommentLogging.testShouldLogContextInComment expected: 1 but was: 0");
        assertThat(failures).extracting(ParsedFailure::testClass, ParsedFailure::testMethod).containsExactly(
                tuple("TestPostgreSqlRollbacks", "testRollbackCreateTableAsSelect"),
                tuple("TestPostgreSqlRollbacks", "testRollbackMerge"),
                tuple("TestRemoteQueryCommentLogging", "testShouldLogContextInComment"));

        // Each excerpt is the section surefire printed for that test: its header
        // line through the last frame of its stack, and nothing after.
        assertThat(failures.getFirst().logExcerpt()).isEqualTo(String.join("\n",
                "[ERROR] io.trino.plugin.postgresql.TestPostgreSqlRollbacks.testRollbackCreateTableAsSelect"
                        + " -- Time elapsed: 0.988 s <<< FAILURE!",
                "java.lang.AssertionError: ",
                "",
                "Expecting empty but was: [\"tmp_trino_05cae137_7ee766c9\"]",
                "\tat io.trino.plugin.postgresql.TestPostgreSqlRollbacks"
                        + ".testRollbackCreateTableAsSelect(TestPostgreSqlRollbacks.java:83)"));

        // The suppressed cause is what says the leak is checked in teardown, so
        // the section must not stop at the blank line before it.
        assertThat(failures.get(1).logExcerpt())
                .contains("Suppressed: java.lang.AssertionError")
                .endsWith("ensureNoTemporaryTablesRemain(TestPostgreSqlRollbacks.java:66)");

        // The teardown storm is 37 distinct-looking errors. None of them is a
        // failure, and none of them may crowd out the three above.
        assertThat(failures).allSatisfy(failure -> assertThat(failure.signature())
                .doesNotContain("SERVICE_UNAVAILABLE", "PageTransportErrorException", "Request to delete"));
    }

    /**
     * Run 31081060186, job 92552903399 — one test failure reported against its
     * method, and one class that died building its query runner, which surefire
     * reports against the class alone while the recap still names the method.
     */
    @Test
    void aClassThatDiedBuildingItselfIsFoundFromTheMethodTheRecapNames()
    {
        List<ParsedFailure> failures = parse("surefire-class-section");

        assertThat(failures).extracting(ParsedFailure::testClass, ParsedFailure::testMethod).containsExactly(
                tuple("TestGoogleSheetsWithoutMetadataSheetId", "testSheetQuerySimple"),
                tuple("TestGoogleSheets", "createQueryRunner"));

        // Header line has no method on it — only the class-level fallback finds it.
        assertThat(failures.get(1).logExcerpt())
                .startsWith("[ERROR] io.trino.plugin.google.sheets.TestGoogleSheets -- Time elapsed: 1.311 s <<< ERROR!")
                .contains("com.google.api.client.auth.oauth2.TokenResponseException")
                .contains("\"error\": \"invalid_grant\"")
                .endsWith("TestGoogleSheets.createQueryRunner(TestGoogleSheets.java:62)");

        // TestGoogleSheets must not swallow TestGoogleSheetsWithoutMetadataSheetId's
        // section, and the class-level block must stop at the suite's next log
        // record rather than running to the line cap.
        assertThat(failures.get(1).logExcerpt())
                .doesNotContain("testSheetQuerySimple")
                .doesNotContain("BlockEncodingSimdSupport");
        assertThat(failures.get(1).logExcerpt().lines()).hasSize(20);
    }

    /**
     * Run 31081060186, job 92549759111 — a checks job with no tests at all, so
     * there is no recap and the generic scan is what runs.
     */
    @Test
    void aJobWithNoTestsFallsBackToTheGenericScan()
    {
        List<ParsedFailure> failures = parse("maven-enforcer");

        // The enforcer spends twenty-five [ERROR] lines on one rule. Only the
        // line that opens the run is the failure; the rest are its continuation,
        // and the reactor's own BUILD FAILURE is the one other trigger.
        assertThat(failures).extracting(ParsedFailure::signature).containsExactly(
                "[INFO] BUILD FAILURE",
                "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-enforcer-plugin:3.6.2:enforce"
                        + " (default) on project trino-exchange-filesystem:");
        assertThat(failures).allSatisfy(failure -> assertThat(failure.testMethod()).isNull());
        assertThat(failures.getFirst().logExcerpt())
                .contains("Require upper bound dependencies error for org.apache.httpcomponents.client5:httpclient5:5.6.1");

        // The generic window, pinned: Maven states the problem over a dozen lines
        // and then spends thirty on how to re-run it, so the excerpt has to reach
        // past both. Anything narrower cut the dependency tree in half.
        assertThat(failures.getFirst().logExcerpt().lines()).hasSize(86);
    }

    /**
     * Run 31078918943, job 92543219659 — a malformed pom.xml, which is what a
     * botched conflict resolution looks like from CI.
     *
     * <p>This build died before the reactor started, so there is no
     * {@code BUILD FAILURE} line and no goal that "Failed to execute": the whole
     * log contains the word "failure" once, on an Actions bookkeeping line.
     * Everything that says what went wrong is bracket-tagged, and until the
     * matcher read those tags this log yielded one failure whose signature was
     * "Cleaning up orphan processes" — the tail of the log, which on Actions is
     * post-job cleanup.
     */
    @Test
    void aMalformedPomIsFoundThroughItsBracketTagsAlone()
    {
        List<ParsedFailure> failures = parse("maven-unparseable-pom");

        assertThat(failures).extracting(ParsedFailure::signature).containsExactly(
                "[ERROR] [ERROR] Some problems were encountered while processing the POMs:",
                "[ERROR] The build could not read 1 project -> [Help 1]");
        // Both carry the file and the position, which is the whole of what a fix
        // needs — and neither is the post-job cleanup this used to return.
        assertThat(failures).allSatisfy(failure -> assertThat(failure.logExcerpt())
                .contains("Non-parseable POM")
                .contains("testing/trino-server-dev/pom.xml")
                .contains("unexpected character in markup <"));
        assertThat(failures).noneSatisfy(failure ->
                assertThat(failure.signature()).isEqualTo("Cleaning up orphan processes"));
    }

    private List<ParsedFailure> parse(String name)
    {
        BootstrapProfile profile = new BootstrapProfile(
                "github-actions", Set.of("java"), List.of(), Map.of(), Set.of(), Set.of(),
                Map.of(), Map.of(), Map.of(), List.of());
        return parser.parse("run", 7, "job", read(name), profile);
    }

    private String read(String name)
    {
        String resource = "/harness/ci/" + name + ".log.gz";
        try (InputStream in = new GZIPInputStream(
                requireResource(TestHarnessLogParserOnCapturedLogs.class.getResourceAsStream(resource), resource))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException("failed reading " + resource, e);
        }
    }

    private static InputStream requireResource(InputStream stream, String resource)
    {
        if (stream == null) {
            throw new IllegalStateException("missing test resource " + resource);
        }
        return stream;
    }
}
