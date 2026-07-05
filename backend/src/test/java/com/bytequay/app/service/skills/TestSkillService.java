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

import com.bytequay.app.domain.Skill;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the usage-derived query view on {@link SkillService}, against
 * the real Flyway-migrated SQLite schema.
 */
@SpringBootTest
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestSkillService
{
    @Autowired
    private SkillService service;

    @Test
    void queryFiltersByUsageKindAndSubstring()
    {
        Skill dev = service.create(
                "global", null, null, uniqueName("zzq-dev"),
                "loads when editing", "body", "library", "build", null,
                false, "authored", null);

        assertThat(service.query("development", "global", null, "zzq-dev"))
                .extracting(Skill::id).contains(dev.id());
        // The development filter excludes review rows.
        assertThat(service.query("review", null, null, "zzq-dev"))
                .extracting(Skill::id).doesNotContain(dev.id());
    }

    private static String uniqueName(String prefix)
    {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
