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
package com.bytequay.integration.gitlab.impl;

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
import static com.bytequay.integration.gitlab.impl.GitLabHttpClient.encode;
import static com.google.common.base.Preconditions.checkArgument;

public final class GitLabConnectionClient
        implements HostingAccounts, HostingRepositories
{
    private final GitLabHttpClient http;

    public GitLabConnectionClient(HttpClient http, ObjectMapper mapper)
    {
        this.http = new GitLabHttpClient(http, mapper);
    }

    @Override
    public RemoteHostingAccount verify(HostingAccess access)
    {
        GitLabAccountResponse user = http.get(access, "user", Optional.empty(), GitLabAccountResponse.class).body().orElseThrow();
        try {
            checkArgument(user.id() != null && user.id() > 0, "account id must be positive");
            return new RemoteHostingAccount(user.id().toString(), checkText(user.username(), "username"));
        }
        catch (IllegalArgumentException | NullPointerException e) {
            throw new HostingFailure(INVALID_RESPONSE, "GitLab returned invalid account identity");
        }
    }

    @Override
    public RemoteHostingRepository findByPath(HostingAccess access, String fullPath)
    {
        HostingUrls.checkRepositoryPath(fullPath);
        checkArgument(fullPath.matches("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+"), "invalid repository path");
        GitLabRepositoryResponse repository = http.get(access, "projects/" + encode(fullPath), Optional.empty(), GitLabRepositoryResponse.class).body().orElseThrow();
        try {
            checkArgument(repository.id() != null && repository.id() > 0, "repository id must be positive");
            return new RemoteHostingRepository(repository.id().toString(), checkText(repository.pathWithNamespace(), "pathWithNamespace"),
                    URI.create(checkText(repository.webUrl(), "webUrl")), URI.create(checkText(repository.httpUrlToRepo(), "httpUrlToRepo")),
                    Optional.ofNullable(repository.sshUrlToRepo()).filter(value -> !value.isBlank()),
                    Optional.ofNullable(repository.defaultBranch()).filter(value -> !value.isBlank()));
        }
        catch (IllegalArgumentException | NullPointerException e) {
            throw new HostingFailure(INVALID_RESPONSE, "GitLab returned invalid repository coordinates");
        }
    }
}
