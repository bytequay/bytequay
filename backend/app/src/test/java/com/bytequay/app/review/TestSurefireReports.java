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
package com.bytequay.app.review;

import com.bytequay.core.review.LocalRun;
import com.bytequay.core.review.LocalRun.MethodStatus;
import com.bytequay.core.review.LocalRun.TestClass;
import com.bytequay.core.review.LocalRun.TestMethod;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

class TestSurefireReports
{
    static final String REPORT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="demo.TestCode" time="1,234.5" tests="4" errors="1" skipped="1" failures="1">
              <properties><property name="java.version" value="25"/></properties>
              <testcase name="passes" classname="demo.TestCode" time="0.002"/>
              <testcase name="fails" classname="demo.TestCode" time="0.012">
                <failure message="expected: &lt;1&gt; but was: &lt;2&gt;" type="org.opentest4j.AssertionFailedError"><![CDATA[org.opentest4j.AssertionFailedError: expected: <1> but was: <2>
            \tat demo.TestCode.fails(TestCode.java:14)]]></failure>
                <system-out><![CDATA[not a trace]]></system-out>
              </testcase>
              <testcase name="breaks" classname="demo.TestCode" time="1,001.5">
                <error message="boom" type="java.lang.IllegalStateException">java.lang.IllegalStateException: boom</error>
              </testcase>
              <testcase name="later" classname="demo.TestCode" time="0">
                <skipped message="disabled"/>
              </testcase>
            </testsuite>
            """;

    @TempDir
    private Path reports;

    @Test
    void readsFailuresErrorsSkipsAndNestedClassReports()
            throws IOException
    {
        Instant startedAt = Instant.now().minusSeconds(5);
        write("TEST-demo.TestCode.xml", REPORT, startedAt);
        write("TEST-demo.TestCode$Inner.xml", """
                <testsuite name="demo.TestCode$Inner"><testcase name="inner" classname="demo.TestCode$Inner" time="0.004"/></testsuite>
                """, startedAt.plusMillis(1));
        // Another class sharing the prefix, and a report an earlier run left behind, are not this run's.
        write("TEST-demo.TestCodeExtra.xml", REPORT, startedAt);
        write("TEST-demo.TestCode$Stale.xml", REPORT, startedAt.minusMillis(1));

        List<TestClass> classes = SurefireReports.read(reports, "demo.TestCode", startedAt).orElseThrow();

        assertThat(classes).containsExactly(
                new TestClass("demo.TestCode", OptionalLong.of(1_001_514), ImmutableList.of(
                        new TestMethod("passes", MethodStatus.PASSED, OptionalLong.of(2), Optional.empty(), Optional.empty()),
                        new TestMethod("fails", MethodStatus.FAILED, OptionalLong.of(12), Optional.of("expected: <1> but was: <2>"),
                                Optional.of("org.opentest4j.AssertionFailedError: expected: <1> but was: <2>\n\tat demo.TestCode.fails(TestCode.java:14)")),
                        new TestMethod("breaks", MethodStatus.ERROR, OptionalLong.of(1_001_500), Optional.of("boom"), Optional.of("java.lang.IllegalStateException: boom")),
                        new TestMethod("later", MethodStatus.SKIPPED, OptionalLong.of(0), Optional.of("disabled"), Optional.empty()))),
                new TestClass("demo.TestCode$Inner", OptionalLong.of(4), ImmutableList.of(
                        new TestMethod("inner", MethodStatus.PASSED, OptionalLong.of(4), Optional.empty(), Optional.empty()))));
        assertThat(SurefireReports.counts(classes)).isEqualTo(new LocalRun.Counts(5, 1, 1, 1));
    }

    @Test
    void boundsTracesAndDistinguishesMissingFromUnreadableReports()
            throws IOException
    {
        Instant startedAt = Instant.now().minusSeconds(5);
        assertThat(SurefireReports.read(reports.resolve("missing"), "demo.TestCode", startedAt)).contains(ImmutableList.of());

        write("TEST-demo.TestCode.xml", """
                <testsuite name="demo.TestCode"><testcase name="fails"><failure message="%s">%s</failure></testcase></testsuite>
                """.formatted("m".repeat(10_000), "x".repeat(40_000)), startedAt);
        TestMethod bounded = SurefireReports.read(reports, "demo.TestCode", startedAt).orElseThrow().getFirst().methods().getFirst();
        assertThat(bounded.trace().orElseThrow()).hasSize(16 * 1024);
        assertThat(bounded.message().orElseThrow()).hasSize(4 * 1024 + 1).endsWith("m…");
        assertThat(bounded.durationMs()).isEmpty();

        write("TEST-demo.TestCode.xml", "<testsuite name=\"demo.TestCode\"><testcase name=\"cut\"", startedAt);
        assertThat(SurefireReports.read(reports, "demo.TestCode", startedAt)).isEmpty();

        Path secret = Files.writeString(reports.resolve("secret.txt"), "secret");
        write("TEST-demo.TestCode.xml", """
                <?xml version="1.0"?>
                <!DOCTYPE testsuite [<!ENTITY secret SYSTEM "%s">]>
                <testsuite name="demo.TestCode"><testcase name="fails"><failure>&secret;</failure></testcase></testsuite>
                """.formatted(secret.toUri()), startedAt);
        assertThat(SurefireReports.read(reports, "demo.TestCode", startedAt)).isEmpty();
    }

    private void write(String name, String content, Instant modifiedAt)
            throws IOException
    {
        Files.setLastModifiedTime(Files.writeString(reports.resolve(name), content), FileTime.from(modifiedAt));
    }
}
