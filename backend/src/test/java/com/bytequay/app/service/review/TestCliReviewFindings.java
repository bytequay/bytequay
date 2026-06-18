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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.ReviewFindingSeverity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestCliReviewFindings
{
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesAnchoredAndWholePrFindingsFromTheSentinelBlock()
    {
        String output = """
                Here is my review. The retry path looks racy.

                %s
                [
                  {"path": "src/Foo.java", "line": 42, "severity": "blocker", "summary": "Null deref on retry."},
                  {"severity": "nit", "summary": "Inconsistent logging across the PR."}
                ]
                %s
                """.formatted(CliReviewFindings.BEGIN, CliReviewFindings.END);

        List<CliReviewFindings.Parsed> findings = CliReviewFindings.parse(output, mapper);

        assertThat(findings).hasSize(2);
        assertThat(findings.get(0).path()).isEqualTo("src/Foo.java");
        assertThat(findings.get(0).line()).isEqualTo(42);
        assertThat(findings.get(0).severity()).isEqualTo(ReviewFindingSeverity.BLOCKER);
        assertThat(findings.get(0).summary()).isEqualTo("Null deref on retry.");
        // Whole-PR finding: no path/line.
        assertThat(findings.get(1).path()).isNull();
        assertThat(findings.get(1).line()).isNull();
        assertThat(findings.get(1).severity()).isEqualTo(ReviewFindingSeverity.NIT);
    }

    @Test
    void toleratesAMarkdownFenceInsideTheSentinels()
    {
        String output = CliReviewFindings.BEGIN + "\n```json\n"
                + "[{\"path\":\"a.ts\",\"line\":1,\"severity\":\"major\",\"summary\":\"x\"}]\n"
                + "```\n" + CliReviewFindings.END;

        List<CliReviewFindings.Parsed> findings = CliReviewFindings.parse(output, mapper);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).path()).isEqualTo("a.ts");
        assertThat(findings.get(0).severity()).isEqualTo(ReviewFindingSeverity.MAJOR);
    }

    @Test
    void returnsEmptyWhenThereIsNoBlockOrItIsMalformed()
    {
        assertThat(CliReviewFindings.parse("just prose, no findings block", mapper)).isEmpty();
        assertThat(CliReviewFindings.parse(null, mapper)).isEmpty();
        // An empty array is well-formed but yields nothing.
        assertThat(CliReviewFindings.parse(
                CliReviewFindings.BEGIN + "\n[]\n" + CliReviewFindings.END, mapper)).isEmpty();
        // Malformed JSON degrades gracefully to no structured findings.
        assertThat(CliReviewFindings.parse(
                CliReviewFindings.BEGIN + "\n[not json}\n" + CliReviewFindings.END, mapper)).isEmpty();
    }

    @Test
    void skipsEntriesWithoutASummary()
    {
        String output = CliReviewFindings.BEGIN
                + "[{\"path\":\"a.ts\",\"severity\":\"nit\"},{\"severity\":\"major\",\"summary\":\"keep\"}]"
                + CliReviewFindings.END;

        List<CliReviewFindings.Parsed> findings = CliReviewFindings.parse(output, mapper);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).summary()).isEqualTo("keep");
    }
}
