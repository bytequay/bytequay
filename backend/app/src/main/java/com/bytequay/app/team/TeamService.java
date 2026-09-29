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

import com.bytequay.app.repository.github.GitHubAccountClient;
import com.bytequay.app.repository.github.GitHubAccountClient.UserIdentity;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.credentials.CredentialService.GitHubCredential;
import com.bytequay.core.domain.RepositoryContributor;
import com.bytequay.core.store.AppSettingsStore;
import com.bytequay.core.store.RepositoryMetadataStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static com.bytequay.app.utils.StringInputUtil.requireNotBlank;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Private account-owned preferences. No operation changes a GitHub team or follows a GitHub user. */
@Service
public class TeamService
{
    private static final String PREFIX = "teams.directory.";

    private final AppSettingsStore settings;
    private final ObjectMapper mapper;
    private final CredentialService credentials;
    private final GitHubAccountClient github;
    private final RepositoryMetadataStore metadata;
    private final Object identityLock = new Object();
    private final Object directoryLock = new Object();
    private volatile Optional<Account> account = Optional.empty();

    public TeamService(AppSettingsStore settings, ObjectMapper mapper, CredentialService credentials, GitHubAccountClient github, RepositoryMetadataStore metadata)
    {
        this.settings = requireNonNull(settings, "settings is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.github = requireNonNull(github, "github is null");
        this.metadata = requireNonNull(metadata, "metadata is null");
    }

    public TeamDirectory directory()
    {
        return read(currentAccount());
    }

    /** Stable account namespace shared by the separately collected activity. */
    public long accountId()
    {
        return currentAccount().identity().id();
    }

    public List<RepositoryContributor> contributors()
    {
        return metadata.findAllContributors();
    }

    public TeamDirectory create(String name, String description, List<String> repositories, List<TeamMemberInput> selected)
    {
        TeamCircle circle = new TeamCircle(UUID.randomUUID().toString(), name, description, repositories, ImmutableList.of());
        List<TeamMemberInput> requested = ImmutableList.copyOf(selected).stream().distinct().collect(toImmutableList());
        Account owner = currentAccount();
        Map<Long, TeamMember> members = new LinkedHashMap<>();
        requested.forEach(input -> {
            TeamMember member = resolveMember(owner, input);
            members.put(member.githubId(), member);
        });
        TeamCircle populated = new TeamCircle(circle.id(), circle.name(), circle.description(), circle.repositories(), ImmutableList.copyOf(members.values()));
        return mutate(owner, circles -> ImmutableList.<TeamCircle>builder().addAll(circles).add(populated).build());
    }

    public TeamDirectory update(String id, String name, String description, List<String> repositories)
    {
        return change(currentAccount(), id, circle -> new TeamCircle(circle.id(), name, description, repositories, circle.members()));
    }

    public TeamDirectory remove(String id)
    {
        return mutate(currentAccount(), circles -> {
            requireCircle(circles, id);
            return circles.stream().filter(circle -> !circle.id().equals(id)).collect(toImmutableList());
        });
    }

    public TeamDirectory addMember(String id, TeamMemberInput selected)
    {
        requireNonNull(selected, "selected is null");
        Account owner = currentAccount();
        requireCircle(read(owner).circles(), id);
        // Resolve outside the directory lock; concurrent edits are reread when this member is saved.
        TeamMember member = resolveMember(owner, selected);
        return change(owner, id, circle -> {
            List<TeamMember> members = new ArrayList<>(circle.members());
            members.removeIf(existing -> existing.githubId() == member.githubId());
            members.add(member);
            return new TeamCircle(circle.id(), circle.name(), circle.description(), circle.repositories(), members);
        });
    }

    public TeamDirectory removeMember(String id, long githubId)
    {
        checkArgument(githubId > 0, "GitHub user ID must be positive");
        return change(currentAccount(), id, circle -> new TeamCircle(circle.id(), circle.name(), circle.description(), circle.repositories(),
                circle.members().stream().filter(member -> member.githubId() != githubId).collect(toImmutableList())));
    }

    private TeamMember resolveMember(Account owner, TeamMemberInput selected)
    {
        UserIdentity identity = github.fetchUserIdentity(owner.credential().token(), Optional.of(selected.login()));
        if (identity.id() != selected.githubId()) {
            // The login leads the message so the dialog can mark which selected person to remove.
            throw new ResponseStatusException(HttpStatus.CONFLICT, selected.login() + " couldn't be verified: the cached contributor no longer matches their GitHub account");
        }
        return new TeamMember(
                identity.id(), identity.login(), identity.name().filter(value -> !value.isBlank()).orElse(identity.login()), identity.avatarUrl().orElse(""));
    }

    private TeamDirectory change(Account owner, String id, UnaryOperator<TeamCircle> edit)
    {
        return mutate(owner, circles -> {
            TeamCircle existing = requireCircle(circles, id);
            TeamCircle updated = edit.apply(existing);
            return circles.stream().map(circle -> circle.id().equals(id) ? updated : circle).collect(toImmutableList());
        });
    }

    private TeamDirectory mutate(Account owner, UnaryOperator<List<TeamCircle>> edit)
    {
        // ponytail: one local directory lock; split by account only if concurrent account editing becomes a feature.
        synchronized (directoryLock) {
            TeamDirectory updated = new TeamDirectory(owner.identity().login(), edit.apply(read(owner).circles()));
            try {
                settings.put(PREFIX + owner.identity().id(), mapper.writeValueAsString(updated));
            }
            catch (JsonProcessingException failure) {
                throw new IllegalStateException("Private circles could not be saved", failure);
            }
            return updated;
        }
    }

    private TeamDirectory read(Account owner)
    {
        return settings.get(PREFIX + owner.identity().id()).map(value -> {
            try {
                TeamDirectory saved = requireNonNull(mapper.readValue(value, TeamDirectory.class), "Saved private circles are null");
                return new TeamDirectory(owner.identity().login(), saved.circles());
            }
            catch (JsonProcessingException failure) {
                throw new IllegalStateException("Saved private circles could not be read", failure);
            }
        }).orElseGet(() -> new TeamDirectory(owner.identity().login(), ImmutableList.of()));
    }

    private Account currentAccount()
    {
        GitHubCredential credential = credentials.resolveGitHubCredential();
        synchronized (identityLock) {
            Optional<Account> cached = account.filter(value -> value.credential() == credential);
            if (cached.isPresent()) {
                return cached.orElseThrow();
            }
            UserIdentity identity = github.fetchUserIdentity(credential.token(), Optional.empty());
            Account resolved = new Account(credential, identity);
            account = Optional.of(resolved);
            return resolved;
        }
    }

    private static TeamCircle requireCircle(List<TeamCircle> circles, String id)
    {
        requireNotBlank(id, "Circle ID is required");
        return circles.stream().filter(circle -> circle.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Private circle was not found"));
    }

    private record Account(GitHubCredential credential, UserIdentity identity) {}
}
