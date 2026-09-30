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
package com.bytequay.app.context;

import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.bytequay.core.domain.RepoRef;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestContextLearningStorage
{
    private static final String WORKSPACE = "workspace";
    private static final String SHA = "a".repeat(40);
    private static final List<String> READ_PATHS = ImmutableList.of("plugin/iceberg/", "plugin/delta/");
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new Jdk8Module()).registerModule(new JavaTimeModule());
    private final InMemoryAppSettingsStore settings = new InMemoryAppSettingsStore();
    private final ContextDiscoveryReportStore reports = new ContextDiscoveryReportStore(settings, mapper);
    private final ContextLearningPlanStore plans = new ContextLearningPlanStore(settings, mapper);

    @Test
    void retainsExplicitComponentAndProjectTargetsSeparatelyFromReadingPaths()
    {
        for (List<String> paths : ImmutableList.of(ImmutableList.of("plugin/iceberg/"), ImmutableList.<String>of())) {
            ContextDiscoveryReport report = report(Optional.of(paths));
            ContextLearningPlan plan = plan(Optional.of(paths));
            reports.save(reports.find(WORKSPACE), report);
            plans.save(plans.find(WORKSPACE), plan);

            assertThat(reports.find(WORKSPACE)).contains(report);
            assertThat(plans.find(WORKSPACE)).contains(plan);
            assertThat(reports.find(WORKSPACE).orElseThrow().topics().getFirst().notePaths()).contains(paths);
            assertThat(plans.find(WORKSPACE).orElseThrow().topics().getFirst().notePaths()).contains(paths);
            assertThat(plan.topics().getFirst().paths()).isEqualTo(READ_PATHS);
        }
    }

    @Test
    void readsOldSuggestionsAndProgressWithoutGuessingApplicabilityOrRewritingThem()
    {
        ObjectNode report = mapper.valueToTree(report(Optional.empty()));
        ObjectNode plan = mapper.valueToTree(plan(Optional.empty()));
        ((ObjectNode) report.path("topics").get(0)).remove("notePaths");
        ((ObjectNode) plan.path("topics").get(0)).remove("notePaths");
        settings.put(ContextDiscoveryReportStore.PREFIX + WORKSPACE, report.toString());
        settings.put(ContextLearningPlanStore.PREFIX + WORKSPACE, plan.toString());

        ContextDiscoveryReport savedReport = reports.find(WORKSPACE).orElseThrow();
        ContextLearningPlan savedPlan = plans.find(WORKSPACE).orElseThrow();

        assertThat(savedReport.topics().getFirst().notePaths()).isEmpty();
        assertThat(savedReport.topics().getFirst().paths()).isEqualTo(READ_PATHS);
        assertThat(savedPlan.topics().getFirst().notePaths()).isEmpty();
        assertThat(savedPlan.topics().getFirst().questions().getFirst().progress()).isEqualTo("Read the metadata entry point.");
        assertThat(settings.get(ContextDiscoveryReportStore.PREFIX + WORKSPACE)).contains(report.toString());
        assertThat(settings.get(ContextLearningPlanStore.PREFIX + WORKSPACE)).contains(plan.toString());
    }

    @Test
    void stillRejectsOtherMissingOrMalformedFields()
    {
        ObjectNode report = mapper.valueToTree(report(Optional.empty()));
        ObjectNode plan = mapper.valueToTree(plan(Optional.empty()));
        ((ObjectNode) report.path("topics").get(0)).remove("paths");
        ((ObjectNode) plan.path("topics").get(0)).remove("scopeMatchDigest");
        settings.put(ContextDiscoveryReportStore.PREFIX + WORKSPACE, report.toString());
        settings.put(ContextLearningPlanStore.PREFIX + WORKSPACE, plan.toString());

        assertThatThrownBy(() -> reports.find(WORKSPACE)).isInstanceOf(ContextStorageException.class);
        assertThatThrownBy(() -> plans.find(WORKSPACE)).isInstanceOf(ContextStorageException.class);

        report = mapper.valueToTree(report(Optional.of(ImmutableList.of("plugin/iceberg/"))));
        ((ObjectNode) report.path("topics").get(0)).putArray("notePaths").add("../delta/");
        settings.put(ContextDiscoveryReportStore.PREFIX + WORKSPACE, report.toString());
        assertThatThrownBy(() -> reports.find(WORKSPACE)).isInstanceOf(ContextStorageException.class);
    }

    private static ContextDiscoveryReport report(Optional<List<String>> notePaths)
    {
        ContextDiscoveryReport.Topic topic = new ContextDiscoveryReport.Topic("topic:1", "Iceberg", ImmutableList.of("How are files selected?"),
                READ_PATHS, notePaths, ImmutableList.of(), ImmutableList.of("read:1"), "Repository guide identifies the connector");
        return new ContextDiscoveryReport("report", WORKSPACE, 1, RepoRef.of("owner", "repository"), SHA, "Connector project",
                ImmutableList.of(topic), ImmutableList.of(), ImmutableList.of(new ContextDiscoveryReport.Evidence("read:1", "README.md", SHA, 1, 1,
                        "Connector guide", false)), ImmutableList.of(), ImmutableList.of(), false, ContextDiscoveryReport.Coverage.none(), Instant.EPOCH);
    }

    private static ContextLearningPlan plan(Optional<List<String>> notePaths)
    {
        ContextLearningPlan.Topic topic = new ContextLearningPlan.Topic("topic:1", "Iceberg", "iceberg", "sha256:" + "b".repeat(64), READ_PATHS,
                notePaths, ImmutableList.of("README.md"), ImmutableList.of(), ImmutableList.of(new ContextLearningPlan.Question(
                        "How are files selected?", ContextLearningPlan.Status.UNFINISHED, "Read the metadata entry point.")));
        return new ContextLearningPlan("plan", WORKSPACE, "report", "accept", "sha256:" + "c".repeat(64), 0, 0, ImmutableList.of("topic:1"),
                1, RepoRef.of("owner", "repository"), SHA, ImmutableList.of(topic), ImmutableList.of(), ContextDocumentInventory.initial(ImmutableList.of()),
                ImmutableList.of(), Instant.EPOCH, Optional.empty());
    }
}
