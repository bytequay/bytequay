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
package com.bytequay.integration.github.impl;

import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingAccounts;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingRepositories;
import com.bytequay.core.hosting.HostingUrls;
import com.bytequay.core.hosting.RemoteHostingAccount;
import com.bytequay.core.hosting.RemoteHostingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkText;
import static com.bytequay.core.hosting.HostingFailure.Kind.INVALID_RESPONSE;
import static com.google.common.base.Preconditions.checkArgument;

public final class GitHubConnectionClient
        implements HostingAccounts, HostingRepositories
{
    private final GitHubHttpClient http;

    public GitHubConnectionClient(HttpClient http, ObjectMapper mapper)
    {
        this.http = new GitHubHttpClient(http, mapper);
    }

    @Override
    public RemoteHostingAccount verify(HostingAccess access)
    {
        GitHubAccountResponse user = http.get(access, "user", GitHubAccountResponse.class);
        try {
            checkArgument(user.id() != null && user.id() > 0, "account id must be positive");
            return new RemoteHostingAccount(Long.toString(user.id()), checkText(user.login(), "login"));
        }
        catch (IllegalArgumentException e) {
            throw new HostingFailure(INVALID_RESPONSE, "GitHub returned invalid account metadata");
        }
    }

    @Override
    public RemoteHostingRepository findByPath(HostingAccess access, String fullPath)
    {
        return repository(access, GitHubHttpClient.repositoryPath(fullPath), Optional.empty());
    }

    @Override
    public RemoteHostingRepository findById(HostingAccess access, String remoteId)
    {
        checkArgument(checkText(remoteId, "remoteId").matches("[1-9][0-9]*") && Long.parseLong(remoteId) > 0, "invalid repository id");
        return repository(access, "repositories/" + remoteId, Optional.of(remoteId));
    }

    private RemoteHostingRepository repository(HostingAccess access, String path, Optional<String> expectedId)
    {
        GitHubRepositoryResponse repository = http.get(access, path, GitHubRepositoryResponse.class);
        try {
            checkArgument(repository.id() != null && repository.id() > 0, "repository id must be positive");
            String remoteId = Long.toString(repository.id());
            checkArgument(expectedId.isEmpty() || expectedId.orElseThrow().equals(remoteId), "repository id does not match the request");
            String fullPath = checkText(repository.fullName(), "full_name");
            GitHubHttpClient.repositoryPath(fullPath);
            URI webUrl = HostingUrls.requireWithinBase(URI.create(checkText(repository.htmlUrl(), "html_url")), access.server().webBaseUrl());
            URI cloneUrl = HostingUrls.requireWithinBase(URI.create(checkText(repository.cloneUrl(), "clone_url")), access.server().webBaseUrl());
            String expectedPath = access.server().webBaseUrl().getPath() + "/" + fullPath;
            checkArgument(webUrl.getPath().equals(expectedPath) && cloneUrl.getPath().equals(expectedPath + ".git"), "repository URLs do not match its path");
            return new RemoteHostingRepository(remoteId, fullPath, webUrl, cloneUrl,
                    Optional.ofNullable(repository.sshUrl()).filter(value -> !value.isBlank()), Optional.ofNullable(repository.defaultBranch()).filter(value -> !value.isBlank()));
        }
        catch (IllegalArgumentException e) {
            throw new HostingFailure(INVALID_RESPONSE, "GitHub returned invalid repository coordinates");
        }
    }
}
