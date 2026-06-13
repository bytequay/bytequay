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

import com.bytequay.app.domain.Skill;
import com.bytequay.app.repository.SkillStore;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteSkillStore
        implements SkillStore
{
    private final SkillJpaRepository repo;

    public SqliteSkillStore(SkillJpaRepository repo)
    {
        this.repo = requireNonNull(repo, "repo is null");
    }

    @Override
    public List<Skill> list()
    {
        return repo.findAllByOrderByNameAsc().stream()
                .map(SqliteSkillStore::toDomain)
                .collect(toImmutableList());
    }

    @Override
    public Optional<Skill> byId(long id)
    {
        return repo.findById(id).map(SqliteSkillStore::toDomain);
    }

    @Override
    public Optional<Skill> byName(String name)
    {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return repo.findByName(name).map(SqliteSkillStore::toDomain);
    }

    @Override
    public List<Skill> findGlobal()
    {
        return repo.findByScopeAndEnabledTrueOrderByNameAsc("global").stream()
                .map(SqliteSkillStore::toDomain)
                .collect(toImmutableList());
    }

    @Override
    public List<Skill> findByRepo(String repoSlug)
    {
        if (repoSlug == null || repoSlug.isBlank()) {
            return List.of();
        }
        return repo.findByScopeAndRepoAndEnabledTrueOrderByIsDefaultDescNameAsc("repo", repoSlug).stream()
                .map(SqliteSkillStore::toDomain)
                .collect(toImmutableList());
    }

    @Override
    public Optional<Skill> findRubricForRepo(String repoSlug)
    {
        return findByRepo(repoSlug).stream()
                .filter(s -> "rubric".equals(s.kind()))
                .findFirst();
    }

    @Override
    @Transactional
    public Skill create(
            String scope,
            String repoSlug,
            String threadId,
            String name,
            String description,
            String body,
            String kind,
            String usage,
            String roleTag,
            boolean isDefault,
            String source,
            String provenance)
    {
        SkillEntity e = new SkillEntity();
        e.setScope(scope);
        e.setRepo(blankToNull(repoSlug));
        e.setThreadId(blankToNull(threadId));
        e.setName(name);
        e.setDescription(description == null ? "" : description);
        e.setBody(body == null ? "" : body);
        e.setKind(kind);
        e.setUsage(usage == null || usage.isBlank() ? "build" : usage);
        e.setRoleTag(blankToNull(roleTag));
        e.setDefault(isDefault);
        e.setSource(source == null ? "authored" : source);
        e.setProvenance(blankToNull(provenance));
        e.setContentHash(hash(body));
        try {
            return toDomain(this.repo.save(e));
        }
        catch (DataIntegrityViolationException ex) {
            throw new IllegalStateException(
                    "skill name '" + name + "' already exists", ex);
        }
    }

    @Override
    @Transactional
    public Skill update(
            long id,
            String scope,
            String repoSlug,
            String threadId,
            String name,
            String description,
            String body,
            String kind,
            String usage,
            String roleTag,
            boolean isDefault)
    {
        SkillEntity e = this.repo.findById(id)
                .orElseThrow(() -> new IllegalStateException("skill " + id + " not found"));
        e.setScope(scope);
        e.setRepo(blankToNull(repoSlug));
        e.setThreadId(blankToNull(threadId));
        e.setName(name);
        e.setDescription(description == null ? "" : description);
        e.setBody(body == null ? "" : body);
        e.setKind(kind);
        e.setUsage(usage == null || usage.isBlank() ? "build" : usage);
        e.setRoleTag(blankToNull(roleTag));
        e.setDefault(isDefault);
        e.setContentHash(hash(body));
        try {
            return toDomain(this.repo.save(e));
        }
        catch (DataIntegrityViolationException ex) {
            throw new IllegalStateException(
                    "skill name '" + name + "' already exists", ex);
        }
    }

    @Override
    @Transactional
    public void delete(long id)
    {
        this.repo.deleteById(id);
    }

    @Override
    @Transactional
    public Skill setEnabled(long id, boolean enabled)
    {
        SkillEntity e = this.repo.findById(id)
                .orElseThrow(() -> new IllegalStateException("skill " + id + " not found"));
        e.setEnabled(enabled);
        return toDomain(this.repo.save(e));
    }

    private static String blankToNull(String s)
    {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String hash(String body)
    {
        String input = body == null ? "" : body;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        }
        catch (NoSuchAlgorithmException ex) {
            // SHA-256 is required by the JRE spec; surfacing it here would only
            // happen on a broken runtime.
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static Skill toDomain(SkillEntity e)
    {
        return new Skill(
                e.getId(),
                e.getScope(),
                e.getRepo(),
                e.getThreadId(),
                e.getName(),
                e.getDescription(),
                e.getBody(),
                e.getKind(),
                e.getUsage() == null ? "build" : e.getUsage(),
                e.getRoleTag(),
                e.isEnabled(),
                e.isDefault(),
                e.getSource(),
                e.getProvenance(),
                e.getContentHash(),
                e.getCreatedAt(),
                e.getUpdatedAt());
    }
}
