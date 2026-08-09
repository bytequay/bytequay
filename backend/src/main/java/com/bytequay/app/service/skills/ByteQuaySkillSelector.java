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
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.SkillStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.google.common.collect.ImmutableSet;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/** Selects and resolves a bounded skill body set before provider dispatch. */
@Service
public class ByteQuaySkillSelector
{
    private static final Pattern WORD_BREAK = Pattern.compile("[^a-z0-9]+");
    private static final Set<String> STOP_WORDS = ImmutableSet.of(
            "about", "after", "again", "also", "before", "from", "have", "into",
            "just", "more", "please", "that", "this", "using", "want", "with");

    private final SkillStore store;
    private final WatchedRepoStore watchedRepos;
    private final PonytailBundleService managedBundles;

    public ByteQuaySkillSelector(
            SkillStore store,
            WatchedRepoStore watchedRepos,
            PonytailBundleService managedBundles)
    {
        this.store = requireNonNull(store, "store is null");
        this.watchedRepos = requireNonNull(watchedRepos, "watchedRepos is null");
        this.managedBundles = requireNonNull(managedBundles, "managedBundles is null");
    }

    /**
     * Resolve policy-selected built-ins first, then fill the remaining slots
     * with authored skills selected by ByteQuay. Providers never see the
     * catalog or decide which bodies to load.
     */
    public List<ManagedSkill> select(
            List<String> managedNames,
            ByteQuayRole role,
            String threadId,
            String workingDir,
            String input,
            int limit)
    {
        requireNonNull(managedNames, "managedNames is null");
        requireNonNull(role, "role is null");
        if (limit < managedNames.size()) {
            throw new IllegalStateException("managed skill policy exceeds selection limit " + limit);
        }

        ManagedSkillBundle bundle = managedBundles.snapshot();
        List<ManagedSkill> selected = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (String name : managedNames) {
            ManagedSkill skill = bundle.skills().get(name);
            if (skill == null || skill.body() == null || skill.body().isBlank()) {
                throw new IllegalStateException("missing ByteQuay managed skill: " + name);
            }
            selected.add(skill);
            names.add(name);
        }

        String repo = repoFor(workingDir);
        List<Candidate> authored = store.list().stream()
                .filter(Skill::enabled)
                .filter(skill -> usageMatches(skill, role))
                .filter(skill -> roleMatches(skill, role))
                .filter(skill -> scopeMatches(skill, threadId, repo))
                .filter(skill -> skill.body() != null && !skill.body().isBlank())
                .filter(skill -> !names.contains(skill.name()))
                .map(skill -> new Candidate(skill, score(skill, input)))
                .filter(candidate -> candidate.score() > 0)
                .sorted(Comparator
                        .comparingInt(Candidate::score).reversed()
                        .thenComparingInt(candidate -> scopeRank(candidate.skill().scope()))
                        .thenComparing(candidate -> candidate.skill().name()))
                .toList();
        for (Candidate candidate : authored) {
            if (selected.size() == limit) {
                break;
            }
            Skill skill = candidate.skill();
            selected.add(new ManagedSkill(skill.name(), skill.body()));
        }
        return List.copyOf(selected);
    }

    private String repoFor(String workingDir)
    {
        if (workingDir == null || workingDir.isBlank()) {
            return null;
        }
        Path cwd;
        try {
            cwd = Path.of(workingDir).toAbsolutePath().normalize();
        }
        catch (RuntimeException ignored) {
            return null;
        }
        return watchedRepos.findAll().stream()
                .filter(repo -> repo.localClonePath() != null && !repo.localClonePath().isBlank())
                .filter(repo -> startsWith(cwd, repo.localClonePath()))
                .max(Comparator.comparingInt(repo -> repo.localClonePath().length()))
                .map(WatchedRepo::fullName)
                .orElse(null);
    }

    private static boolean startsWith(Path cwd, String clonePath)
    {
        try {
            return cwd.startsWith(Path.of(clonePath).toAbsolutePath().normalize());
        }
        catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean usageMatches(Skill skill, ByteQuayRole role)
    {
        String required = role == ByteQuayRole.REVIEWER ? "review" : "build";
        return required.equals(skill.usage());
    }

    private static boolean roleMatches(Skill skill, ByteQuayRole role)
    {
        return skill.roleTag() == null
                || skill.roleTag().isBlank()
                || skill.roleTag().equalsIgnoreCase(role.id());
    }

    private static boolean scopeMatches(Skill skill, String threadId, String repo)
    {
        return switch (skill.scope()) {
            case "global" -> true;
            case "thread" -> threadId != null && threadId.equals(skill.threadId());
            case "repo" -> repo != null && repo.equalsIgnoreCase(skill.repo());
            default -> false;
        };
    }

    private static int score(Skill skill, String input)
    {
        int score = 0;
        if (skill.isDefault()) {
            score += 100;
        }
        if ("persona".equals(skill.kind())) {
            score += 90;
        }
        if ("thread".equals(skill.scope()) || "rubric".equals(skill.kind())) {
            score += 80;
        }

        Set<String> inputWords = words(input);
        Set<String> triggerWords = words(skill.name() + " " + skill.description());
        triggerWords.retainAll(inputWords);
        score += triggerWords.size() * 10;

        String normalizedName = normalize(skill.name());
        if (!normalizedName.isBlank() && normalize(input).contains(normalizedName)) {
            score += 50;
        }
        return score;
    }

    private static Set<String> words(String value)
    {
        Set<String> words = new HashSet<>();
        for (String word : WORD_BREAK.split(normalize(value))) {
            if (word.length() >= 3 && !STOP_WORDS.contains(word)) {
                words.add(word);
            }
        }
        return words;
    }

    private static String normalize(String value)
    {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static int scopeRank(String scope)
    {
        return switch (scope) {
            case "thread" -> 0;
            case "repo" -> 1;
            default -> 2;
        };
    }

    private record Candidate(Skill skill, int score) {}
}
