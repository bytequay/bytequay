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
package com.bytequay.app.service;

import com.bytequay.app.domain.CredentialType;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;

import static java.util.Objects.requireNonNull;

@Component
public class CredentialTester
{
    private static final URI GITHUB_USER_URL = URI.create("https://api.github.com/user");

    private final CredentialService credentials;

    public CredentialTester(CredentialService credentials)
    {
        this.credentials = requireNonNull(credentials, "credentials is null");
    }

    public TestResult test(CredentialType type, String name, String instanceName)
    {
        if (type != CredentialType.ACCOUNT || !CredentialService.GITHUB_ACCOUNT_NAME.equals(name)) {
            return TestResult.fail("Only the GitHub account credential is used by this app.");
        }
        String secret = credentials.getSecret(type, name, instanceName).orElse(null);
        if (secret == null || secret.isBlank()) {
            return TestResult.fail("No stored value for this credential.");
        }

        long started = System.nanoTime();
        try {
            RestClient.create()
                    .get()
                    .uri(GITHUB_USER_URL)
                    .header("Accept", "application/vnd.github+json")
                    .header("Authorization", "Bearer " + secret)
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .retrieve()
                    .toBodilessEntity();
            return TestResult.ok("Looks good.", elapsedMillis(started));
        }
        catch (RestClientResponseException e) {
            return TestResult.failWithLatency(
                    "GitHub returned " + e.getStatusCode().value() + ".",
                    elapsedMillis(started));
        }
        catch (RuntimeException e) {
            return TestResult.failWithLatency(
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                    elapsedMillis(started));
        }
    }

    private static long elapsedMillis(long started)
    {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TestResult(boolean ok, String message, Long latencyMs)
    {
        public static TestResult ok(String message, long latencyMs)
        {
            return new TestResult(true, message, latencyMs);
        }

        public static TestResult fail(String message)
        {
            return new TestResult(false, message, null);
        }

        public static TestResult failWithLatency(String message, long latencyMs)
        {
            return new TestResult(false, message, latencyMs);
        }
    }
}
