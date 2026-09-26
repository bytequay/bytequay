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
package com.bytequay.app.service.settings;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static com.bytequay.agent.spi.AgentPermissionPolicy.AUTO;
import static com.bytequay.app.service.settings.AgentSkillScope.DEVELOPMENT;
import static com.bytequay.app.service.settings.AgentSkillScope.GLOBAL;
import static com.bytequay.app.service.settings.AgentSkillScope.REVIEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestAgentSkillService
{
    private final InMemoryAppSettingsStore store = new InMemoryAppSettingsStore();
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new Jdk8Module());
    private final AgentReviewStore reviews = mock(AgentReviewStore.class);
    private final AgentSkillService service = new AgentSkillService(store, mapper, reviews);

    @Test
    void systemGuidanceIsAlwaysAppliedButNeverStoredOrListedAsAUserSkill()
    {
        assertThat(service.list()).isEmpty();
        assertThat(store.get("agent.skills")).isEmpty();
        String system = service.instructions();
        assertThat(system).contains("# ByteQuay agent behavior", "## Pull-request review", "internal\nimplementation details");
        assertThat(system).contains("## Parallel exploration", "only where they help", "Poor fit: reading", "Never: writing", "does not offer subagents explores directly");
        assertThat(system).contains("only purpose and commit explanations", "design", "implementation", "```suggestion", "RIGHT-side line range");
        assertThat(system).contains("short, complete sentences", "usually 1–2 short sentences", "usually 1–3 short sentences",
                "Never pad text", "preserves the reasoning", "including full explanations when asked");
        assertThat(system).doesNotContain("60–100", "50–90", "**Problem**", "**Approach**", "**Result**",
                "- Combined result:", "- Evidence and tests:", "- Limitations");
        assertThat(service.list()).isEmpty();
        assertThat(store.get("agent.skills")).isEmpty();

        service.remove("bytequay-system");
        assertThatThrownBy(() -> service.update("bytequay-system", "Disabled", "Replacement", GLOBAL, false)).isInstanceOf(IllegalArgumentException.class);
        AgentSkillService restarted = new AgentSkillService(store, mapper, reviews);
        assertThat(restarted.list()).isEmpty();
        assertThat(restarted.instructions()).isEqualTo(system);
    }

    @Test
    void readingSavedSkillsDoesNotRewriteSettings()
    {
        AgentSkill skill = service.add("My guidance", "Use the project terminology.", GLOBAL, true).getFirst();
        String saved = store.get("agent.skills").orElseThrow();
        AgentSkillService restarted = new AgentSkillService(store, mapper, reviews);
        assertThat(restarted.list()).containsExactly(skill);
        assertThat(restarted.instructions()).contains("# ByteQuay agent behavior", "Use the project terminology.");
        assertThat(store.get("agent.skills")).contains(saved);
    }

    @ParameterizedTest
    @CsvSource({
            "codex, true, AUTO, true",
            "claude, true, AUTO, true",
            "api, true, AUTO, true",
            "codex, false, CI_AUTOFIX, true",
            "claude, false, CI_AUTOFIX, true",
            "codex, false, CHERRY_PICK, true",
            "claude, false, CHERRY_PICK, true",
            "codex, false, AUTO, false",
            "claude, false, AUTO, false"
    })
    void developmentGuidanceIsScopedByTaskAcrossProviders(String lane, boolean interactive, AgentPermissionPolicy permission, boolean development)
    {
        AgentTask task = task(lane, interactive, permission);
        String instructions = service.instructions(task);
        assertThat(instructions).contains("# ByteQuay agent behavior");
        if (development) {
            assertThat(instructions).contains("# ByteQuay development rules");
        }
        else {
            assertThat(instructions).isEqualTo(service.instructions());
        }
        assertThat(service.instructions()).doesNotContain("# ByteQuay development rules");
        assertThat(service.list()).isEmpty();
        assertThat(store.get("agent.skills")).isEmpty();
    }

    @Test
    void userSkillChangesPreserveDevelopmentGuidanceAndHelperExclusion()
    {
        AgentTask task = task("codex", true, AUTO);
        String development = service.instructions(task);
        AgentSkill custom = service.add("Team style", "Use the project terminology.", GLOBAL, true).getFirst();
        assertThat(service.instructions(task)).containsSubsequence("# ByteQuay agent behavior", "# ByteQuay development rules", "## Team style");
        assertThat(service.instructions()).contains("Use the project terminology.").doesNotContain("# ByteQuay development rules");

        service.update(custom.id(), custom.name(), custom.instructions(), GLOBAL, false);
        assertThat(service.instructions(task)).isEqualTo(development);
        service.remove(custom.id());
        service.remove("bytequay-development");
        assertThatThrownBy(() -> service.update("bytequay-development", "Override", "Replacement", GLOBAL, false)).isInstanceOf(IllegalArgumentException.class);
        AgentSkillService restarted = new AgentSkillService(store, mapper, reviews);
        assertThat(restarted.instructions(task)).isEqualTo(development);
        assertThat(restarted.instructions()).doesNotContain("# ByteQuay development rules");
        assertThat(restarted.list()).isEmpty();
    }

    @Test
    void userEditsTogglesAndRemovalNeverDisableTheSystemGuidance()
    {
        String system = service.instructions();
        AgentSkill custom = service.add("  Team style  ", "Use the project terminology.", GLOBAL, true).getFirst();
        AgentSkill other = service.add("Another preference", "Give useful examples.", GLOBAL, true).getLast();
        assertThat(custom.name()).isEqualTo("Team style");
        assertThat(service.instructions()).containsSubsequence("# ByteQuay agent behavior", "## Team style", "## Another preference");

        service.update(custom.id(), "Team terminology", "Use concise terminology.", GLOBAL, false);
        String disabled = new AgentSkillService(store, mapper, reviews).instructions();
        assertThat(disabled).contains("# ByteQuay agent behavior", "Give useful examples.").doesNotContain("Use the project terminology.", "Use concise terminology.");
        assertThat(system).doesNotContain("Use the project terminology.");

        service.update(custom.id(), "Team terminology", "Use concise terminology.", GLOBAL, true);
        assertThat(service.instructions()).containsSubsequence("# ByteQuay agent behavior", "## Team terminology", "Use concise terminology.", "## Another preference");
        service.remove(custom.id());
        service.remove(other.id());
        assertThat(new AgentSkillService(store, mapper, reviews).instructions()).isEqualTo(system);
        assertThat(service.list()).isEmpty();
    }

    @Test
    void scopedSkillsRoundTripAndMoveWithoutKeepingEarlierApplicability()
    {
        AgentTask development = task("codex", true, AUTO);
        AgentTask review = task("claude", false, AUTO);
        when(reviews.findReviewByTask(review.id().toString())).thenReturn(Optional.of(review(review)));
        AgentSkill added = service.add("Task rules", "Original guidance.", DEVELOPMENT, true).getFirst();
        AgentSkillService restarted = new AgentSkillService(store, mapper, reviews);
        assertThat(restarted.list()).containsExactly(added);
        assertThat(restarted.instructions(development)).contains("Original guidance.");
        assertThat(restarted.instructions(review)).doesNotContain("Original guidance.");
        assertThat(restarted.instructions()).doesNotContain("Original guidance.");

        AgentSkill moved = restarted.update(added.id(), "Review rules", "Revised guidance.", REVIEW, true).getFirst();
        assertThat(moved.id()).isEqualTo(added.id());
        assertThat(moved.scope()).isEqualTo(REVIEW);
        assertThat(service.list()).containsExactly(moved);
        assertThat(service.instructions(development)).contains("scope: DEVELOPMENT", "out-of-scope", "moved to a different scope")
                .doesNotContain("Original guidance.", "Revised guidance.");
        assertThat(service.instructions(review)).contains("scope: REVIEW", "Revised guidance.").doesNotContain("Original guidance.");

        service.update(moved.id(), moved.name(), moved.instructions(), REVIEW, false);
        assertThat(restarted.instructions(review)).doesNotContain("Revised guidance.");
        service.update(moved.id(), moved.name(), moved.instructions(), GLOBAL, true);
        assertThat(restarted.instructions()).contains("Revised guidance.");
        service.remove(moved.id());
        assertThat(restarted.list()).isEmpty();
        assertThat(restarted.instructions(review)).contains("No user-added skills apply to this turn.").doesNotContain("Revised guidance.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"codex", "claude", "api"})
    void userSkillsAreIsolatedAndOrderedByScopeAcrossProviders(String lane)
    {
        service.add("Dev first", "Development rule one.", DEVELOPMENT, true);
        service.add("Review first", "Review rule one.", REVIEW, true);
        service.add("Global first", "Common rule one.", GLOBAL, true);
        service.add("Dev second", "Development rule two.", DEVELOPMENT, true);
        service.add("Global second", "Common rule two.", GLOBAL, true);
        service.add("Review second", "Review rule two.", REVIEW, true);

        AgentTask development = task(lane, true, AUTO);
        AgentTask review = task(lane, false, AUTO);
        when(reviews.findReviewByTask(review.id().toString())).thenReturn(Optional.of(review(review)));
        assertThat(service.instructions(development))
                .containsSubsequence("## Global first", "## Global second", "## Dev first", "## Dev second")
                .doesNotContain("## Review first", "## Review second");
        assertThat(service.instructions(review))
                .containsSubsequence("## Global first", "## Global second", "## Review first", "## Review second")
                .doesNotContain("## Dev first", "## Dev second", "# ByteQuay development rules", "# ByteQuay review rules");
        for (String generic : ImmutableList.of(service.instructions(), service.instructions(task(lane, false, AUTO)))) {
            assertThat(generic).contains("scope: GLOBAL").containsSubsequence("## Global first", "## Global second")
                    .doesNotContain("## Dev first", "## Dev second", "## Review first", "## Review second", "# ByteQuay development rules");
        }
        assertThat(service.list()).extracting(AgentSkill::name)
                .containsExactly("Dev first", "Review first", "Global first", "Dev second", "Global second", "Review second");
    }

    @ParameterizedTest
    @CsvSource({"true, AUTO", "false, CI_AUTOFIX", "false, CHERRY_PICK"})
    void openReviewOwnershipTakesPriorityOverDevelopmentIdentity(boolean interactive, AgentPermissionPolicy permission)
    {
        service.add("Development", "Only for development.", DEVELOPMENT, true);
        service.add("Review", "Only for review.", REVIEW, true);
        AgentTask task = task("codex", interactive, permission);
        AgentReview review = review(task);
        when(reviews.findReviewByTask(task.id().toString())).thenReturn(Optional.of(review));
        assertThat(service.instructions(task)).contains("scope: REVIEW", "Only for review.")
                .doesNotContain("Only for development.", "# ByteQuay development rules");

        when(reviews.findReviewByTask(task.id().toString())).thenReturn(Optional.of(review.closed(Instant.EPOCH)));
        assertThat(service.instructions(task)).contains("scope: DEVELOPMENT", "Only for development.", "# ByteQuay development rules")
                .doesNotContain("Only for review.");
    }

    @Test
    void closedReviewsAndHelpersReceiveOnlyGlobalUserSkills()
    {
        AgentTask task = task("codex", false, AUTO);
        when(reviews.findReviewByTask(task.id().toString())).thenReturn(Optional.of(review(task).closed(Instant.EPOCH)));
        service.add("Review", "Only for review.", REVIEW, true);
        service.add("Development", "Only for development.", DEVELOPMENT, true);
        service.add("Global", "For every session.", GLOBAL, true);
        assertThat(service.instructions(task)).isEqualTo(service.instructions()).contains("For every session.")
                .doesNotContain("Only for review.", "Only for development.");
    }

    @Test
    void missingOrInvalidSavedScopesFailWithoutDefaultingOrRewriting()
    {
        for (String scopeField : ImmutableList.of("", ",\"scope\":null", ",\"scope\":\"INVALID\"", ",\"scope\":1")) {
            String saved = "[{\"id\":\"custom\",\"name\":\"A skill\",\"instructions\":\"Use this guidance\",\"enabled\":true" + scopeField + "}]";
            store.put("agent.skills", saved);
            assertThatThrownBy(service::list).isInstanceOf(IllegalStateException.class).hasMessageContaining("could not be read");
            assertThat(store.get("agent.skills")).contains(saved);
        }
    }

    @Test
    void invalidUpdatesLeaveSavedCollectionUntouched()
    {
        service.add("Existing", "Preserve these instructions.", GLOBAL, true);
        String saved = store.get("agent.skills").orElseThrow();
        assertThatThrownBy(() -> service.add(" ", "Useful instructions", GLOBAL, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.add("x".repeat(101), "Useful instructions", GLOBAL, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.add("Too large", "x".repeat(20_001), GLOBAL, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update("missing", "A name", "Some instructions", GLOBAL, false)).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.get("agent.skills")).contains(saved);
    }

    @Test
    void collectionLimitsRejectWithoutDroppingExistingSkills()
    {
        for (int i = 0; i < 5; i++) {
            service.add("Skill " + i, "x".repeat(20_000), GLOBAL, true);
        }
        assertThatThrownBy(() -> service.add("Too much", "x", GLOBAL, false)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("100000");
        assertThat(service.list()).hasSize(5);

        for (AgentSkill skill : service.list()) {
            service.remove(skill.id());
        }
        for (int i = 0; i < 50; i++) {
            service.add("Skill " + i, "x", GLOBAL, false);
        }
        assertThatThrownBy(() -> service.add("One too many", "x", GLOBAL, true)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("50");
        assertThat(service.list()).hasSize(50);
    }

    @Test
    void corruptSettingsFailWithoutOverwritingSavedData()
    {
        store.put("agent.skills", "broken json");
        assertThatThrownBy(service::instructions).isInstanceOf(IllegalStateException.class).hasMessageContaining("could not be read");
        assertThat(store.get("agent.skills")).contains("broken json");
    }

    @Test
    void collectionLimitAccountsForUtf8ProviderArguments()
    {
        service.add("First", "字".repeat(20_000), GLOBAL, true);
        assertThatThrownBy(() -> service.add("Second", "字".repeat(20_000), GLOBAL, true)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("UTF-8 bytes");
        assertThat(service.list()).hasSize(1);
    }

    private static AgentTask task(String lane, boolean interactive, AgentPermissionPolicy permission)
    {
        return AgentTask.created(
                TaskId.random(),
                Optional.of("workspace"),
                interactive,
                "Task",
                new AgentLaneId(lane),
                new RepositoryIdentity("owner", "repository"),
                Path.of("/worktree"),
                permission,
                Instant.EPOCH);
    }

    private static AgentReview review(AgentTask task)
    {
        return new AgentReview(
                "review-" + task.id(),
                "workspace",
                "owner/repository",
                OptionalInt.of(1),
                OptionalLong.empty(),
                task.id().toString(),
                Optional.empty(),
                AgentReview.State.OPEN,
                false,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                Instant.EPOCH,
                Instant.EPOCH,
                Optional.empty());
    }
}
