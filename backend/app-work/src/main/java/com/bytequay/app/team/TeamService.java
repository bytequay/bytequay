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

import com.bytequay.app.service.hosting.HostingIntegrations;
import com.bytequay.app.team.TeamCircle.Avatar;
import com.bytequay.core.domain.RepositoryContributor;
import com.bytequay.core.hosting.HostingPerson;
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
import java.util.UUID;
import java.util.function.UnaryOperator;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Private circles of each hosting account. No operation changes a provider team or follows a user. */
@Service
public class TeamService
{
    private static final String PREFIX = "teams.directory.";

    private final AppSettingsStore settings;
    private final ObjectMapper mapper;
    private final TeamAccounts accounts;
    private final HostingIntegrations integrations;
    private final RepositoryMetadataStore metadata;
    private final Object directoryLock = new Object();

    TeamService(AppSettingsStore settings, ObjectMapper mapper, TeamAccounts accounts, HostingIntegrations integrations, RepositoryMetadataStore metadata)
    {
        this.settings = requireNonNull(settings, "settings is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.accounts = requireNonNull(accounts, "accounts is null");
        this.integrations = requireNonNull(integrations, "integrations is null");
        this.metadata = requireNonNull(metadata, "metadata is null");
    }

    /** Every account's circles. */
    public List<TeamDirectory> directories()
    {
        return accounts.all().stream().map(this::read).collect(toImmutableList());
    }

    /**
     * The circles of the accounts known without contacting a provider, for read paths that must answer offline; the desktop sign-in's
     * appear once a read has verified it.
     */
    public List<TeamCircle> cachedCircles()
    {
        return accounts.cached().stream().flatMap(account -> read(account).circles().stream()).collect(toImmutableList());
    }

    TeamAccount account(long accountId)
    {
        return accounts.require(accountId);
    }

    List<TeamAccount> accounts()
    {
        return accounts.all();
    }

    TeamDirectory directory(TeamAccount account)
    {
        return read(account);
    }

    /** The people cached for the repositories on the account's server, to pick circle members from. */
    public List<RepositoryContributor> contributors(long accountId)
    {
        return metadata.findPeople(accounts.require(accountId).account().instanceId());
    }

    public TeamDirectory create(long accountId, String name, String description, Avatar avatar, List<String> repositories, List<TeamMemberInput> selected)
    {
        TeamCircle circle = new TeamCircle(UUID.randomUUID().toString(), name, description, avatar, repositories, ImmutableList.of());
        List<TeamMemberInput> requested = ImmutableList.copyOf(selected).stream().distinct().collect(toImmutableList());
        TeamAccount owner = accounts.require(accountId);
        Map<Long, TeamMember> members = new LinkedHashMap<>();
        requested.forEach(input -> {
            TeamMember member = resolveMember(owner, input);
            members.put(member.remoteId(), member);
        });
        TeamCircle populated = new TeamCircle(circle.id(), circle.name(), circle.description(), circle.avatar(), circle.repositories(), ImmutableList.copyOf(members.values()));
        return mutate(owner, circles -> ImmutableList.<TeamCircle>builder().addAll(circles).add(populated).build());
    }

    public TeamDirectory update(long accountId, String id, String name, String description, Avatar avatar, List<String> repositories)
    {
        return change(accounts.require(accountId), id, circle -> new TeamCircle(circle.id(), name, description, avatar, repositories, circle.members()));
    }

    public TeamDirectory remove(long accountId, String id)
    {
        return mutate(accounts.require(accountId), circles -> {
            requireCircle(circles, id);
            return circles.stream().filter(circle -> !circle.id().equals(id)).collect(toImmutableList());
        });
    }

    public TeamDirectory addMember(long accountId, String id, TeamMemberInput selected)
    {
        requireNonNull(selected, "selected is null");
        TeamAccount owner = accounts.require(accountId);
        requireCircle(read(owner).circles(), id);
        // Resolve outside the directory lock; concurrent edits are reread when this member is saved.
        TeamMember member = resolveMember(owner, selected);
        return change(owner, id, circle -> {
            List<TeamMember> members = new ArrayList<>(circle.members());
            members.removeIf(existing -> existing.remoteId() == member.remoteId());
            members.add(member);
            return new TeamCircle(circle.id(), circle.name(), circle.description(), circle.avatar(), circle.repositories(), members);
        });
    }

    public TeamDirectory removeMember(long accountId, String id, long remoteId)
    {
        checkArgument(remoteId > 0, "User ID must be positive");
        return change(accounts.require(accountId), id, circle -> new TeamCircle(circle.id(), circle.name(), circle.description(), circle.avatar(), circle.repositories(),
                circle.members().stream().filter(member -> member.remoteId() != remoteId).collect(toImmutableList())));
    }

    private TeamMember resolveMember(TeamAccount owner, TeamMemberInput selected)
    {
        HostingPerson person = integrations.of(owner.server().provider()).team().person(owner.session().access(), selected.login());
        if (person.id() != selected.remoteId()) {
            // The login leads the message so the dialog can mark which selected person to remove.
            throw new ResponseStatusException(HttpStatus.CONFLICT, selected.login() + " couldn't be verified: the cached contributor no longer matches their account");
        }
        return new TeamMember(person.id(), person.login(), person.name(), person.avatarUrl());
    }

    private TeamDirectory change(TeamAccount owner, String id, UnaryOperator<TeamCircle> edit)
    {
        return mutate(owner, circles -> {
            TeamCircle existing = requireCircle(circles, id);
            TeamCircle updated = edit.apply(existing);
            return circles.stream().map(circle -> circle.id().equals(id) ? updated : circle).collect(toImmutableList());
        });
    }

    private TeamDirectory mutate(TeamAccount owner, UnaryOperator<List<TeamCircle>> edit)
    {
        // ponytail: one local directory lock; split by account only if concurrent account editing becomes a feature.
        synchronized (directoryLock) {
            Saved updated = new Saved(edit.apply(read(owner).circles()));
            try {
                settings.put(PREFIX + owner.id(), mapper.writeValueAsString(updated));
            }
            catch (JsonProcessingException failure) {
                throw new IllegalStateException("Private circles could not be saved", failure);
            }
            return directory(owner, updated.circles());
        }
    }

    private TeamDirectory read(TeamAccount owner)
    {
        return directory(owner, settings.get(PREFIX + owner.id()).map(value -> {
            try {
                return requireNonNull(mapper.readValue(value, Saved.class), "Saved private circles are null").circles();
            }
            catch (JsonProcessingException failure) {
                throw new IllegalStateException("Saved private circles could not be read", failure);
            }
        }).orElse(ImmutableList.of()));
    }

    private static TeamDirectory directory(TeamAccount owner, List<TeamCircle> circles)
    {
        return new TeamDirectory(owner.id(), owner.account().login(), owner.server(), circles);
    }

    private static TeamCircle requireCircle(List<TeamCircle> circles, String id)
    {
        checkText(id, "Circle ID");
        return circles.stream().filter(circle -> circle.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Private circle was not found"));
    }

    private record Saved(List<TeamCircle> circles)
    {
        private Saved
        {
            circles = ImmutableList.copyOf(circles);
        }
    }
}
