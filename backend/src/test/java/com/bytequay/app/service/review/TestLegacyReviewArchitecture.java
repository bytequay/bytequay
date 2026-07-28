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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestLegacyReviewArchitecture
{
    private static final Path MAIN_SOURCE = Path.of("src/main/java");
    private static final Path SOURCE = Path.of(
            "src/main/java/com/bytequay/app/service/review");

    @Test
    void leadAndReviewerProviderLaunchesHaveOneAdmissionBoundary()
            throws IOException
    {
        String lead = source("LeadOrchestrator.java");
        assertThat(occurrences(lead, "runner.runTurn(")).isOne();
        assertThat(occurrences(lead, "admission.invoke(")).isOne();

        String reviewer = source("ReviewerSeat.java");
        assertThat(occurrences(reviewer, "runner.runTurn(")).isOne();
        assertThat(occurrences(reviewer, "cliRunner.run(")).isOne();
        assertThat(occurrences(reviewer, "admission.invoke(")).isEqualTo(2);
        assertThat(occurrences(reviewer, "admission.requireCurrent(")).isEqualTo(2);
        assertThat(reviewer)
                .contains("ReviewMessage runDispatchedTurnAlreadyAdmitted(")
                .doesNotContain("public ReviewMessage runDispatchedTurnAlreadyAdmitted(");
    }

    @Test
    void reviewFanOutUsesTheSharedReviewAdmissionBoundary()
            throws IOException
    {
        assertThat(source("ReviewPassService.java"))
                .contains("reviewAdmission.invokeAll(work)")
                .doesNotContain("AgentScheduler");
        assertThat(source("LeadToolset.java"))
                .contains("reviewAdmission.invokeAll(work)")
                .doesNotContain("AgentScheduler");
    }

    @Test
    void cliRunnerOwnsNoIndependentConcurrencyGate()
            throws IOException
    {
        assertThat(source("CliReviewRunner.java"))
                .contains("ProcessBuilder")
                .doesNotContain(
                        "Semaphore",
                        "MAX_CONCURRENT",
                        "slots.acquire",
                        "slots.release");
    }

    @Test
    void investigationLaunchesRemainExactlyOnceSchedulerAdmitted()
            throws IOException
    {
        String investigation = source("InvestigationReviewRunner.java");
        assertThat(occurrences(investigation, "scheduler.invokeAll(")).isEqualTo(3);
        assertThat(occurrences(investigation, "scheduler.invokeCli(")).isEqualTo(3);
        assertThat(investigation)
                .doesNotContain("LegacyReviewAdmission")
                .contains("cliRunner.runWithSchedulerCapacity(");
    }

    @Test
    void everyCliReviewSubprocessCallHasOneExternalAdmissionOwner()
            throws IOException
    {
        List<String> launchFiles;
        try (var paths = Files.walk(MAIN_SOURCE)) {
            launchFiles = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> {
                        try {
                            return Files.readString(path).contains("cliRunner.run");
                        }
                        catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .map(MAIN_SOURCE::relativize)
                    .map(Path::toString)
                    .toList();
        }
        assertThat(launchFiles).containsExactlyInAnyOrder(
                "com/bytequay/app/service/review/ReviewerSeat.java",
                "com/bytequay/app/service/review/InvestigationReviewRunner.java",
                "com/bytequay/app/service/ai/GlobalReviewRunner.java",
                "com/bytequay/app/service/learning/LessonExtractor.java");

        assertSchedulerAdmittedCliCalls(
                MAIN_SOURCE.resolve("com/bytequay/app/service/review/InvestigationReviewRunner.java"),
                3);
        assertSchedulerAdmittedCliCalls(
                MAIN_SOURCE.resolve("com/bytequay/app/service/ai/GlobalReviewRunner.java"),
                1);
        assertSchedulerAdmittedCliCalls(
                MAIN_SOURCE.resolve("com/bytequay/app/service/learning/LessonExtractor.java"),
                1);
        String reviewer = source("ReviewerSeat.java");
        assertThat(occurrences(reviewer, "cliRunner.run(")).isOne();
        assertThat(reviewer)
                .contains("admission.invoke(", "admission.requireCurrent(");
    }

    private static void assertSchedulerAdmittedCliCalls(Path path, int expected)
            throws IOException
    {
        String content = Files.readString(path);
        assertThat(occurrences(content, "cliRunner.runWithSchedulerCapacity("))
                .isEqualTo(expected);
        assertThat(occurrences(content, "scheduler.invokeCli("))
                .isEqualTo(expected);
    }

    private static String source(String name)
            throws IOException
    {
        return Files.readString(SOURCE.resolve(name));
    }

    private static int occurrences(String value, String needle)
    {
        return (value.length() - value.replace(needle, "").length()) / needle.length();
    }
}
