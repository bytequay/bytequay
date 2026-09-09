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
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.store.AppSettingsStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

import static com.bytequay.agent.spi.AgentPermissionPolicy.CHERRY_PICK;
import static com.bytequay.agent.spi.AgentPermissionPolicy.CI_AUTOFIX;
import static com.bytequay.app.service.settings.AgentSkillScope.DEVELOPMENT;
import static com.bytequay.app.service.settings.AgentSkillScope.GLOBAL;
import static com.bytequay.app.service.settings.AgentSkillScope.REVIEW;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

/** Combines private ByteQuay system guidance with the user's app-wide skill collection for each agent turn. */
@Service
public class AgentSkillService
{
    private static final String SETTINGS_KEY = "agent.skills";
    private static final TypeReference<List<AgentSkill>> SKILL_LIST = new TypeReference<>() {};
    private static final String SYSTEM_INSTRUCTIONS = loadInstructions("bytequay-system.md");
    private static final String DEVELOPMENT_INSTRUCTIONS = loadInstructions("bytequay-development.md");
    private static final String INSTRUCTION_POLICY = """
            Current session skill scope: %s. Only applicable user-added skills for this turn follow, with \
            Global skills before matching scoped skills. This list replaces every earlier applicable-skill list; do not \
            keep applying a removed, disabled, or out-of-scope user skill from conversation history, including \
            one moved to a different scope. Each skill's scope also applies to delegated assignments: \
            Development skills apply only to implementation, fixes, refactoring, and conflict resolution; \
            Review skills apply only to reviews. Title, wording, and research assignments use only Global skills. \
            Pass only Global and matching scoped skills when delegating. ByteQuay's system \
            guidance above remains active within its stated scope. Apply user preferences wherever they fit the current task. \
            Required output formats, complete review coverage, evidence, tool contracts, and \
            permission/publication boundaries take precedence. Skills grant no tools or permission and do not \
            require slash commands. For structured output, apply writing preferences within prose fields \
            without omitting required fields or entries.
            """;

    private final AppSettingsStore store;
    private final ObjectMapper mapper;
    private final AgentReviewStore reviews;

    public AgentSkillService(AppSettingsStore store, ObjectMapper mapper, AgentReviewStore reviews)
    {
        this.store = requireNonNull(store, "store is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
    }

    public synchronized List<AgentSkill> list()
    {
        return store.get(SETTINGS_KEY).map(this::decode).orElse(ImmutableList.of());
    }

    public synchronized List<AgentSkill> add(String name, String instructions, AgentSkillScope scope, boolean enabled)
    {
        AgentSkill added = new AgentSkill(UUID.randomUUID().toString(), name, instructions, scope, enabled);
        return save(ImmutableList.<AgentSkill>builder().addAll(list()).add(added).build());
    }

    public synchronized List<AgentSkill> update(String id, String name, String instructions, AgentSkillScope scope, boolean enabled)
    {
        List<AgentSkill> current = list();
        checkArgument(current.stream().anyMatch(skill -> skill.id().equals(id)), "Unknown agent skill: %s", id);
        return save(current.stream()
                .map(skill -> skill.id().equals(id) ? new AgentSkill(id, name, instructions, scope, enabled) : skill)
                .collect(toImmutableList()));
    }

    public synchronized List<AgentSkill> remove(String id)
    {
        return save(list().stream().filter(skill -> !skill.id().equals(id)).collect(toImmutableList()));
    }

    public String instructions()
    {
        return instructions(GLOBAL);
    }

    public String instructions(AgentTask task)
    {
        requireNonNull(task, "task is null");
        if (reviews.findReviewByTask(task.id().toString()).filter(review -> review.state() == AgentReview.State.OPEN).isPresent()) {
            return instructions(REVIEW);
        }
        return instructions(task.interactive() || task.permissionPolicy() == CI_AUTOFIX || task.permissionPolicy() == CHERRY_PICK ? DEVELOPMENT : GLOBAL);
    }

    private String instructions(AgentSkillScope scope)
    {
        String enabled = list().stream()
                .filter(AgentSkill::enabled)
                .filter(skill -> skill.scope() == GLOBAL || skill.scope() == scope)
                .sorted(comparing(skill -> skill.scope() != GLOBAL))
                .map(skill -> "## " + skill.name() + " (" + skill.scope() + ")\n" + skill.instructions())
                .collect(joining("\n\n"));
        String system = SYSTEM_INSTRUCTIONS + (scope == DEVELOPMENT ? "\n\n" + DEVELOPMENT_INSTRUCTIONS : "");
        return system + "\n\n" + INSTRUCTION_POLICY.formatted(scope) + (enabled.isEmpty() ? "\nNo user-added skills apply to this turn." : "\n" + enabled);
    }

    private List<AgentSkill> save(List<AgentSkill> skills)
    {
        validateCollection(skills);
        try {
            store.put(SETTINGS_KEY, mapper.writeValueAsString(skills));
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("Agent skills could not be encoded", failure);
        }
        return ImmutableList.copyOf(skills);
    }

    private List<AgentSkill> decode(String json)
    {
        try {
            List<AgentSkill> skills = mapper.readValue(json, SKILL_LIST);
            validateCollection(skills);
            return ImmutableList.copyOf(skills);
        }
        catch (JsonProcessingException | IllegalArgumentException failure) {
            throw new IllegalStateException("Saved agent skills could not be read", failure);
        }
    }

    private static void validateCollection(List<AgentSkill> skills)
    {
        requireNonNull(skills, "skills is null");
        checkArgument(skills.size() <= 50, "At most 50 agent skills can be saved");
        checkArgument(skills.stream().map(AgentSkill::id).distinct().count() == skills.size(), "Agent skill ids must be unique");
        // Claude receives this text in argv; bound encoded bytes as well as individual text fields.
        checkArgument(skills.stream().mapToInt(skill -> skill.instructions().getBytes(UTF_8).length).sum() <= 100_000, "Combined instructions must be at most 100000 UTF-8 bytes");
    }

    private static String loadInstructions(String name)
    {
        try (InputStream resource = AgentSkillService.class.getResourceAsStream("/agent-skills/" + name)) {
            checkState(resource != null, "ByteQuay system guidance is missing: %s", name);
            return new String(resource.readAllBytes(), UTF_8).strip();
        }
        catch (IOException failure) {
            throw new IllegalStateException("ByteQuay system guidance could not be read: " + name, failure);
        }
    }
}
