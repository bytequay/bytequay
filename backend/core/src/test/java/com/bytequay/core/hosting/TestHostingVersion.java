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
package com.bytequay.core.hosting;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestHostingVersion
{
    @Test
    void readsReleasesAsServersReportThem()
    {
        assertThat(HostingVersion.parse("14.10.5")).contains(new HostingVersion(14, 10, 5));
        assertThat(HostingVersion.parse("19.6.0-ee")).contains(new HostingVersion(19, 6, 0));
        assertThat(HostingVersion.parse("16.11.0-pre")).contains(new HostingVersion(16, 11, 0));
        assertThat(HostingVersion.parse(" 17.1 ")).contains(new HostingVersion(17, 1, 0));
        assertThat(HostingVersion.parse("unknown")).isEmpty();
        assertThat(HostingVersion.parse("v16.11.0")).isEmpty();
        assertThat(HostingVersion.parse(null)).isEmpty();
    }

    @Test
    void comparesByNumbersNotText()
    {
        assertThat(new HostingVersion(14, 10, 5).isAtLeast(HostingVersion.of(16, 11))).isFalse();
        assertThat(new HostingVersion(16, 11, 0).isAtLeast(HostingVersion.of(16, 11))).isTrue();
        assertThat(new HostingVersion(16, 9, 9).isAtLeast(HostingVersion.of(16, 11))).isFalse();
        assertThat(new HostingVersion(19, 6, 0).isAtLeast(HostingVersion.of(16, 11))).isTrue();
        assertThat(new HostingVersion(14, 10, 5)).hasToString("14.10.5");
    }
}
