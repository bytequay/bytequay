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
package com.bytequay.app.git;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static com.bytequay.app.git.RepositoryUrls.isPlainHttps;
import static org.assertj.core.api.Assertions.assertThat;

class TestRepositoryUrls
{
    @ParameterizedTest
    @ValueSource(strings = {
            "https://github.com/owner/repo.git",
            "HTTPS://GITHUB.COM/owner/repo.git",
            "https://git.example.com:8443/owner/repo.git",
            "https://localhost/repo",
            "https://127.0.0.1/repo",
            "https://[::1]:8443/repo",
            "https://github.com"})
    void acceptsPlainHttpsRepositoryLocations(String url)
    {
        assertThat(isPlainHttps(URI.create(url))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "/owner/repo.git",
            "https:owner/repo.git",
            "https:///owner/repo.git",
            "http://github.com/owner/repo.git",
            "ssh://github.com/owner/repo.git",
            "file:///owner/repo.git",
            "https://user:pass@github.com/owner/repo.git",
            "https://@github.com/owner/repo.git",
            "https://github.com/owner/repo.git?token=secret",
            "https://github.com/owner/repo.git?",
            "https://github.com/owner/repo.git#branch",
            "https://github.com/owner/repo.git#"})
    void rejectsUrlsWithUnsafeRoutingComponents(String url)
    {
        assertThat(isPlainHttps(URI.create(url))).isFalse();
    }
}
