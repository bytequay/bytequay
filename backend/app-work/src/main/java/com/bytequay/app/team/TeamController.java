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

import com.bytequay.app.team.TeamCircle.Avatar;
import com.bytequay.core.domain.RepositoryContributor;
import com.google.common.collect.ImmutableList;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@RestController
@RequestMapping("/api/teams")
public class TeamController
{
    private final TeamService teams;

    public TeamController(TeamService teams)
    {
        this.teams = requireNonNull(teams, "teams is null");
    }

    @GetMapping
    public List<TeamDirectory> directories()
    {
        return teams.directories();
    }

    @GetMapping("/{accountId}/contributors")
    public List<RepositoryContributor> contributors(@PathVariable long accountId)
    {
        return teams.contributors(accountId);
    }

    @PostMapping("/{accountId}/circles")
    public TeamDirectory create(@PathVariable long accountId, @RequestBody CircleRequest request)
    {
        return teams.create(accountId, request.name(), request.description(), request.avatar(), request.repositories(), request.members());
    }

    @PutMapping("/{accountId}/circles/{id}")
    public TeamDirectory update(@PathVariable long accountId, @PathVariable String id, @RequestBody CircleRequest request)
    {
        return teams.update(accountId, id, request.name(), request.description(), request.avatar(), request.repositories());
    }

    @DeleteMapping("/{accountId}/circles/{id}")
    public TeamDirectory remove(@PathVariable long accountId, @PathVariable String id)
    {
        return teams.remove(accountId, id);
    }

    @PostMapping("/{accountId}/circles/{id}/members")
    public TeamDirectory addMember(@PathVariable long accountId, @PathVariable String id, @RequestBody TeamMemberInput request)
    {
        return teams.addMember(accountId, id, request);
    }

    @DeleteMapping("/{accountId}/circles/{id}/members/{remoteId}")
    public TeamDirectory removeMember(@PathVariable long accountId, @PathVariable String id, @PathVariable long remoteId)
    {
        return teams.removeMember(accountId, id, remoteId);
    }

    public record CircleRequest(String name, String description, Avatar avatar, List<String> repositories, List<TeamMemberInput> members)
    {
        public CircleRequest
        {
            avatar = Optional.ofNullable(avatar).orElse(Avatar.DEFAULT);
            repositories = Optional.ofNullable(repositories).map(ImmutableList::copyOf).orElse(ImmutableList.of());
            members = Optional.ofNullable(members).map(ImmutableList::copyOf).orElse(ImmutableList.of());
        }
    }
}
