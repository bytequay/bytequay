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
package com.bytequay.core.hosting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class TestHostingIdentity
{
    @Test
    void preservesInstallationPortsSubpathsAndRepositoryNamespaceCase()
    {
        HostingInstance instance = new HostingInstance(1, HostingProvider.GITLAB,
                URI.create("HTTPS://Git.Example.com:8443/Engineering/"), URI.create("https://git.example.com:8443/Engineering/api/v4/"));

        assertThat(instance.webBaseUrl()).isEqualTo(URI.create("https://git.example.com:8443/Engineering"));
        assertThat(instance.apiBaseUrl()).isEqualTo(URI.create("https://git.example.com:8443/Engineering/api/v4"));
        HostingRepository repository = new HostingRepository(5, instance.id(), "91", "Group/Subgroup/Project",
                URI.create("https://git.example.com:8443/Engineering/Group/Subgroup/Project"),
                URI.create("https://git.example.com:8443/Engineering/Group/Subgroup/Project.git"),
                Optional.of("git@git.example.com:Group/Subgroup/Project.git"), Optional.empty());
        assertThat(repository.fullPath()).isEqualTo("Group/Subgroup/Project");
        assertThat(repository.defaultBranch()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:8080/gitlab", "http://127.0.0.1:8080", "http://127.1.2.3:8080", "http://[::1]:8080/gitlab"})
    void permitsExplicitLoopbackTestInstances(String value)
    {
        URI uri = URI.create(value);
        assertThat(new HostingInstance(1, HostingProvider.GITLAB, uri, uri).webBaseUrl()).isEqualTo(uri);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://git.example.com", "http://localhost.example.com", "http://127.0.0.999", "ftp://git.example.com", "/gitlab",
            "https://user:secret@git.example.com", "https://git.example.com?token=secret", "https://git.example.com#fragment",
            "https://git.example.com/a/../b", "https://git.example.com/a/%2E%2e/b", "https://git.example.com/a%2Fb",
            "https://git.example.com/a%5Cb", "https://git.example.com/a%0Ab", "https://git.example.com:70000"
    })
    void rejectsUnsafeInstanceEndpoints(String value)
    {
        assertThatIllegalArgumentException().isThrownBy(() -> HostingUrls.canonicalBaseUrl(URI.create(value)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://other.example.com/gitlab/group/project", "https://git.example.com:8443/gitlab/group/project",
            "https://git.example.com/gitlab-other/group/project", "https://git.example.com/group/project",
            "https://git.example.com/gitlab/../elsewhere"
    })
    void repositoryCoordinatesCannotEscapeTheInstallation(String value)
    {
        assertThatIllegalArgumentException().isThrownBy(() -> HostingUrls.requireWithinBase(URI.create(value), URI.create("https://git.example.com/gitlab")));
    }

    @Test
    void repositoryCoordinatesAcceptAnExplicitDefaultPortAndNestedPath()
    {
        URI repository = URI.create("https://git.example.com:443/gitlab/Group/Subgroup/Project");
        assertThat(HostingUrls.requireWithinBase(repository, URI.create("https://git.example.com/gitlab/")))
                .isEqualTo(URI.create("https://git.example.com/gitlab/Group/Subgroup/Project"));
    }

    @Test
    void canonicalizesOnlyDefaultPorts()
    {
        assertThat(HostingUrls.canonicalBaseUrl(URI.create("https://gitlab.com:443/"))).isEqualTo(URI.create("https://gitlab.com"));
        assertThat(HostingUrls.canonicalBaseUrl(URI.create("http://127.0.0.1:80/gitlab/"))).isEqualTo(URI.create("http://127.0.0.1/gitlab"));
        assertThat(HostingUrls.canonicalBaseUrl(URI.create("https://git.example.com:8443/gitlab/"))).isEqualTo(URI.create("https://git.example.com:8443/gitlab"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Group//Project", "/Group/Project", "Group/Project/", "Group/../Project", "Group/./Project"})
    void rejectsUnstableRepositoryPaths(String value)
    {
        assertThatIllegalArgumentException().isThrownBy(() -> HostingUrls.checkRepositoryPath(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"git@git.example.com:Group/Project.git", "ssh://git@git.example.com:2222/Group/Project.git", "git@[::1]:Group/Project.git"})
    void acceptsSshCloneCoordinates(String value)
    {
        HostingUrls.checkSshCloneUrl(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://git.example.com/Group/Project.git", "ssh://git:secret@git.example.com/Group/Project", "git@host:path\ncommand"})
    void rejectsInvalidSshCloneCoordinates(String value)
    {
        assertThatIllegalArgumentException().isThrownBy(() -> HostingUrls.checkSshCloneUrl(value));
    }

    @Test
    void credentialsDoNotExposeTheirSecretInDiagnostics()
    {
        HostingCredential credential = new HostingCredential(4, 9, "private-token");
        assertThat(credential.secret()).isEqualTo("private-token");
        assertThat(credential.toString()).contains("accountId=4", "generation=9", "<redacted>").doesNotContain("private-token");
        assertThatIllegalArgumentException().isThrownBy(() -> new HostingCredential(4, 0, "private-token"));
        assertThatIllegalArgumentException().isThrownBy(() -> new HostingAccount(0, 1, "42", "alice"));
    }
}
