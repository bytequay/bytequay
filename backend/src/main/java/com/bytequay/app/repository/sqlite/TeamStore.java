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
package com.bytequay.app.repository.sqlite;

import com.bytequay.app.domain.Team;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

/**
 * Local persistence for teams. Member rosters are stored in a separate
 * table; the store presents them as a single {@link Team} record so callers
 * never juggle two collections.
 */
@Repository
public class TeamStore
{
    private final TeamJpaRepository teamRepo;
    private final TeamMemberJpaRepository memberRepo;

    public TeamStore(TeamJpaRepository teamRepo, TeamMemberJpaRepository memberRepo)
    {
        this.teamRepo = requireNonNull(teamRepo, "teamRepo is null");
        this.memberRepo = requireNonNull(memberRepo, "memberRepo is null");
    }

    @Transactional(readOnly = true)
    public List<Team> findAll()
    {
        return teamRepo.findAll().stream()
                .map(this::toDomain)
                .collect(toImmutableList());
    }

    @Transactional(readOnly = true)
    public Optional<Team> find(long id)
    {
        return teamRepo.findById(id).map(this::toDomain);
    }

    @Transactional
    /** Creates a new team with the given roster. Throws if {@code name} is
    *  already in use. {@code description} may be null. */
    public Team create(String name, String avatar, String color, String description, Set<String> members)
    {
        validateRequired(name, "name");
        validateRequired(avatar, "avatar");
        validateRequired(color, "color");
        if (teamRepo.findByNameIgnoreCase(name).isPresent()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409), "team name '" + name + "' already in use");
        }
        TeamEntity team = new TeamEntity();
        team.setName(name);
        team.setAvatar(avatar);
        team.setColor(color);
        team.setDescription(normaliseDescription(description));
        TeamEntity saved = teamRepo.save(team);
        writeMembers(saved.getId(), members);
        return toDomain(saved);
    }

    @Transactional
    /** Renames / re-colours / re-describes an existing team without touching
    *  its roster. {@code description} may be null. */
    public Team update(long id, String name, String avatar, String color, String description)
    {
        validateRequired(name, "name");
        validateRequired(avatar, "avatar");
        validateRequired(color, "color");
        TeamEntity team = teamRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatusCode.valueOf(404), "team " + id + " not found"));
        // Reject a rename that would collide with another team.
        teamRepo.findByNameIgnoreCase(name).ifPresent(existing -> {
            if (!existing.getId().equals(team.getId())) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(409), "team name '" + name + "' already in use");
            }
        });
        team.setName(name);
        team.setAvatar(avatar);
        team.setColor(color);
        team.setDescription(normaliseDescription(description));
        return toDomain(teamRepo.save(team));
    }

    /** Trim and treat blank as null so the DB column is genuinely empty
     *  rather than an empty-string "" sentinel that the UI then has to
     *  filter out everywhere it reads the field. */
    private static String normaliseDescription(String raw)
    {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Transactional
    /** Replaces the entire roster. Logins are normalised to lowercase. */
    public Team replaceMembers(long id, Set<String> members)
    {
        TeamEntity team = teamRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatusCode.valueOf(404), "team " + id + " not found"));
        memberRepo.deleteByTeamId(id);
        writeMembers(id, members);
        return toDomain(team);
    }

    @Transactional
    public void delete(long id)
    {
        memberRepo.deleteByTeamId(id);
        teamRepo.deleteById(id);
    }

    private void writeMembers(long teamId, Set<String> members)
    {
        if (members == null) {
            return;
        }
        // Normalise to lowercase; the Set already dedupes by reference value,
        // but case-folded duplicates ("Alice" vs "alice") still need filtering.
        members.stream()
                .filter(m -> m != null && !m.isBlank())
                .map(m -> m.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .forEach(login -> memberRepo.save(new TeamMemberEntity(teamId, login)));
    }

    private static void validateRequired(String value, String fieldName)
    {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), fieldName + " must not be blank");
        }
    }

    private Team toDomain(TeamEntity entity)
    {
        // ImmutableSet preserves insertion order, so the on-disk listing
        // order is what callers see — matters for the editor's chip layout.
        Set<String> members = memberRepo.findByTeamId(entity.getId()).stream()
                .map(TeamMemberEntity::getLogin)
                .collect(toImmutableSet());
        return new Team(
                entity.getId(),
                entity.getName(),
                entity.getAvatar(),
                entity.getColor(),
                entity.getDescription(),
                members,
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
