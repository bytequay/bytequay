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
package com.bytequay.app.service.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestPonytailBundleService
{
    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void bundledFallbackLoadsRealPonytailSkills()
    {
        ManagedSkillBundle bundle = new PonytailBundleService(mapper, tempDir).snapshot();

        assertThat(bundle.version()).isEqualTo("4.8.4");
        assertThat(bundle.source()).isEqualTo("bundled");
        assertThat(bundle.select(List.of("ponytail")))
                .singleElement()
                .satisfies(skill -> assertThat(skill.body()).contains("Ponytail"));
        assertThat(bundle.select(List.of("ponytail-review")))
                .singleElement()
                .satisfies(skill -> assertThat(skill.body()).contains("over-engineering"));
        assertThat(bundle.select(List.of(CavemanPrompt.NAME)))
                .singleElement()
                .satisfies(skill -> assertThat(skill.body()).contains("Respond terse like smart caveman"));
        assertThat(bundle.select(List.of("i-have-adhd")))
                .singleElement()
                .satisfies(skill -> assertThat(skill.body())
                        .contains("The reader has ADHD")
                        .contains("Lead with the next action"));
        assertThat(bundle.select(List.of("trunk-planner")))
                .singleElement()
                .satisfies(skill -> assertThat(skill.body())
                        .contains("Trunk Planner")
                        .contains("ask_user_question")
                        .contains("Go ahead")
                        .contains("Cut this as")
                        .contains("never ask a question only in prose"));
        assertThat(bundle.select(List.of("codegraph-first")))
                .singleElement()
                .satisfies(skill -> assertThat(skill.body()).contains("CodeGraph First"));
        assertThat(bundle.select(List.of("task-execution")))
                .singleElement()
                .satisfies(skill -> assertThat(skill.body())
                        .contains("Task Execution")
                        .contains("Every commit must build and pass its tests")
                        .contains("at most 50 characters")
                        .contains("Default to a subject-only commit")
                        .contains("record_pr_progress` with `phase: starting")
                        .contains("complete base-to-head commit history")
                        .contains("re-read")
                        .contains("preserve its headings")
                        .contains("checklists, and structure")
                        .contains("Do not call `ship_task`, `push`, or `request_review`")
                        .contains("record_local_review")
                        .contains("outside the provider sandbox")
                        .contains("Never add AI or bot attribution")
                        .contains("read_remote_pr_status")
                        .contains("read_ci_log")
                        .contains("read-only `gh` commands are allowed")
                        .contains("Anything that changes GitHub")
                        .doesNotContain("Anything that reaches GitHub"));
    }

    @Test
    void validCacheOverridesBundledForFutureSnapshots()
    {
        PonytailBundleService service = new PonytailBundleService(mapper, tempDir);
        ManagedSkillBundle before = service.snapshot();

        service.installCache(new PonytailBundleService.DownloadedPackage(
                "9.9.9",
                "sha512-test",
                "cached ponytail",
                "cached review",
                "MIT"));
        ManagedSkillBundle after = service.snapshot();

        assertThat(before.version()).isEqualTo("4.8.4");
        assertThat(after.version()).isEqualTo("9.9.9");
        assertThat(after.source()).isEqualTo("cache");
        assertThat(after.select(List.of("ponytail")))
                .singleElement()
                .satisfies(skill -> assertThat(skill.body()).isEqualTo("cached ponytail"));
        assertThat(after.select(List.of(CavemanPrompt.NAME)))
                .singleElement()
                .satisfies(skill -> assertThat(skill.body()).contains("Respond terse like smart caveman"));
        assertThat(after.select(List.of("i-have-adhd")))
                .singleElement()
                .satisfies(skill -> assertThat(skill.body()).contains("The reader has ADHD"));
        assertThat(after.select(List.of("trunk-planner")))
                .singleElement()
                .satisfies(skill -> assertThat(skill.body()).contains("Trunk Planner"));
    }
}
