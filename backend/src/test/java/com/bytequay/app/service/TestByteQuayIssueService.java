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

import com.bytequay.app.domain.RepoIssue;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.service.credentials.PatResolver;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

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
        IssueOriginService origins = mock(IssueOriginService.class);
        when(pats.resolve()).thenReturn("account-pat");
        RepoIssue created = new RepoIssue(
                41L, 7, "Broken button", "chenjian2664", "open",
                "https://github.com/bytequay/bytequay/issues/7",
                Instant.EPOCH, List.of(), 0);
        when(gitHub.createIssue(
                "account-pat", RepoRef.of("bytequay", "bytequay"),
                "Broken button", "Steps to reproduce\n\n"
                        + "<!-- bytequay-origin:v1 kind=user-report -->"))
                .thenReturn(created);

        new ByteQuayIssueService(gitHub, pats, origins)
                .report("Broken button", "Steps to reproduce");

        verify(gitHub).createIssue(
                "account-pat", RepoRef.of("bytequay", "bytequay"),
                "Broken button", "Steps to reproduce\n\n"
                        + "<!-- bytequay-origin:v1 kind=user-report -->");
        verify(origins).recordCreated(created, "user-report");
    }
}
