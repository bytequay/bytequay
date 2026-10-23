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
package com.bytequay.app.team;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static com.bytequay.core.domain.Checks.checkText;
import static com.bytequay.core.domain.Checks.normalizeNames;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;

/** Empty repository scope means all accessible activity collected for the members. */
public record TeamCircle(String id, String name, String description, Avatar avatar, List<String> repositories, List<TeamMember> members)
{
    private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9_-]{1,100}/[A-Za-z0-9_.-]{1,100}");

    public TeamCircle
    {
        checkText(id, "Circle ID");
        checkText(name, "Circle name");
        name = name.strip();
        description = Optional.ofNullable(description).orElse("").strip();
        avatar = Optional.ofNullable(avatar).orElse(Avatar.DEFAULT);
        checkArgument(id.length() <= 100, "Circle ID is too long");
        checkArgument(name.length() <= 100 && name.chars().noneMatch(Character::isISOControl), "Circle name must contain at most 100 printable characters");
        checkArgument(description.length() <= 2000, "Circle description must contain at most 2000 characters");
        repositories = normalizeNames(repositories, "Repositories").stream()
                .map(repository -> {
                    checkArgument(REPOSITORY.matcher(repository).matches() && !repository.endsWith("/.") && !repository.endsWith("/.."), "Enter a repository as owner/name");
                    return repository.toLowerCase(Locale.ROOT);
                })
                .distinct()
                .collect(toImmutableList());
        members = ImmutableList.copyOf(members);
        checkArgument(members.stream().map(TeamMember::githubId).distinct().count() == members.size(), "Circle contains duplicate members");
    }

    /** A circle's avatar: a solid palette color, drawn as a circle or a rounded square. */
    public record Avatar(String color, String shape)
    {
        // Keep in step with CIRCLE_COLORS and CIRCLE_SHAPES in the frontend's teams/types.ts.
        private static final Set<String> COLORS = ImmutableSet.of("blue", "green", "purple", "orange", "red", "pink", "teal", "gray");
        private static final Set<String> SHAPES = ImmutableSet.of("square", "circle");
        // Declared after the sets it is validated against, which class initialization fills in order.
        public static final Avatar DEFAULT = new Avatar("gray", "square");

        public Avatar
        {
            checkArgument(COLORS.contains(color), "Unknown avatar color");
            checkArgument(SHAPES.contains(shape), "Unknown avatar shape");
        }
    }
}
