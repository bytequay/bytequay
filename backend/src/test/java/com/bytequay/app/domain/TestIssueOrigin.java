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
package com.bytequay.app.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestIssueOrigin
{
    @Test
    void detectsVersionedIssueMarkers()
    {
        assertThat(IssueOrigin.detect(null)).isEqualTo(IssueOrigin.UNKNOWN);
        assertThat(IssueOrigin.detect("ordinary report")).isEqualTo(IssueOrigin.USER);
        assertThat(IssueOrigin.detect("bug\n\n" + IssueOrigin.USER_REPORT_MARKER))
                .isEqualTo(IssueOrigin.USER_REPORT);
        assertThat(IssueOrigin.detect("bug\n<!-- bytequay-quality-scan:v1 fingerprint=abc -->"))
                .isEqualTo(IssueOrigin.QUALITY_SCAN);
    }

    @Test
    void marksQualityScanBodiesOnce()
    {
        String marked = IssueOrigin.markQualityScan("Finding details.  \n");

        assertThat(marked).isEqualTo("Finding details.\n\n" + IssueOrigin.QUALITY_SCAN_MARKER);
        assertThat(IssueOrigin.markQualityScan(marked)).isEqualTo(marked);
        assertThat(IssueOrigin.detect(marked)).isEqualTo(IssueOrigin.QUALITY_SCAN);
    }
}
