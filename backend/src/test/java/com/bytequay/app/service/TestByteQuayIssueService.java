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

import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.service.credentials.PatResolver;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestByteQuayIssueService
{
    @Test
    void reportsToTheCanonicalRepositoryWithoutARepoCredential()
    {
        PullRequestRepository gitHub = mock(PullRequestRepository.class);
        PatResolver pats = mock(PatResolver.class);
        when(pats.resolve()).thenReturn("account-pat");

        new ByteQuayIssueService(gitHub, pats).report("Broken button", "Steps to reproduce");

        verify(gitHub).createIssue(
                "account-pat", RepoRef.of("bytequay", "bytequay"),
                "Broken button", "Steps to reproduce");
    }
}
