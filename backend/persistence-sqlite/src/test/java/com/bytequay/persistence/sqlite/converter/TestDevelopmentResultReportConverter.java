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
package com.bytequay.persistence.sqlite.converter;

import com.bytequay.agent.spi.DevelopmentLearningCommit;
import com.bytequay.agent.spi.DevelopmentLearningSummary;
import com.bytequay.agent.spi.DevelopmentResultReport;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDevelopmentResultReportConverter
{
    private final DevelopmentResultReportConverter converter = new DevelopmentResultReportConverter();

    @Test
    void retainsLearningSummaryAndAcceptsReadinessWithoutOne()
    {
        DevelopmentLearningSummary summary = new DevelopmentLearningSummary("Explain the change",
                ImmutableList.of(new DevelopmentLearningCommit("a".repeat(40), "Commit title", "What the commit does")),
                Optional.empty(), "Read the tests; execution was unavailable", ImmutableList.of("No local test results"));
        DevelopmentResultReport report = new DevelopmentResultReport(true, Optional.empty(), Optional.of(summary));
        assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(report))).isEqualTo(report);
        assertThat(converter.convertToEntityAttribute("{\"readyForDelivery\":false,\"message\":\"Waiting for a choice\"}"))
                .isEqualTo(new DevelopmentResultReport(false, Optional.of("Waiting for a choice")));
        assertThat(converter.convertToEntityAttribute("{\"readyForDelivery\":true}"))
                .isEqualTo(new DevelopmentResultReport(true, Optional.empty()));
        assertThatThrownBy(() -> converter.convertToEntityAttribute("{\"message\":\"No readiness\"}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> converter.convertToEntityAttribute("{\"readyForDelivery\":null}"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
