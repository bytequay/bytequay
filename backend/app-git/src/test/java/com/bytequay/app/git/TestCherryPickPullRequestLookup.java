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

import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.HostingTarget;
import com.bytequay.core.hosting.PullRequestAssociation;
import com.bytequay.core.hosting.PullRequestContentReader;
import com.bytequay.core.hosting.PullRequestWriter;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static com.bytequay.app.service.hosting.HostingIntegrationFixtures.integrations;
import static com.bytequay.app.service.hosting.HostingIntegrationFixtures.pullRequests;
import static com.bytequay.core.hosting.HostingFailure.Kind.RATE_LIMITED;
import static com.bytequay.core.hosting.HostingFailure.Kind.UNAVAILABLE;
import static com.bytequay.core.hosting.HostingProvider.GITHUB;
import static com.bytequay.core.hosting.HostingProvider.GITLAB;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestCherryPickPullRequestLookup
{
    private static final String SHA = "a".repeat(40);
    private static final HostingTarget GITHUB_TARGET = target(1, GITHUB, "https://github.com", "https://api.github.com", 11, "owner/repository");
    private static final HostingTarget GITLAB_TARGET = target(2, GITLAB, "https://gitlab.example.test", "https://gitlab.example.test/api/v4", 12, "group/sub/project");

    private final PullRequestContentReader gitHub = mock(PullRequestContentReader.class);
    private final PullRequestContentReader gitLab = mock(PullRequestContentReader.class);
    private final CherryPickPullRequestLookup lookup = new CherryPickPullRequestLookup(integrations(
            pullRequests(GITHUB, gitHub, mock(PullRequestWriter.class)), pullRequests(GITLAB, gitLab, mock(PullRequestWriter.class))));

    @Test
    void groupsACommitUnderItsSingleMergedPullRequest()
    {
        when(gitHub.mergedWithCommit(GITHUB_TARGET, SHA)).thenReturn(ImmutableList.of(merged(23401, "master")));

        List<String> warnings = new ArrayList<>();
        PullRequestLookupResult result = lookup.lookUpResult(GITHUB_TARGET, ImmutableList.of(SHA), "master", warnings);

        assertThat(association(result, SHA)).isEqualTo(merged(23401, "master"));
        assertThat(result.failedShas()).isEmpty();
        assertThat(warnings).isEmpty();
    }

    @Test
    void asksTheTargetsProviderAndKeepsEachRepositorysAnswerApart()
    {
        when(gitHub.mergedWithCommit(GITHUB_TARGET, SHA)).thenReturn(ImmutableList.of(merged(1, "main")));
        when(gitLab.mergedWithCommit(GITLAB_TARGET, SHA)).thenReturn(ImmutableList.of(merged(2, "main")));

        assertThat(association(lookup.lookUpResult(GITHUB_TARGET, ImmutableList.of(SHA), "main", new ArrayList<>()), SHA).number()).isEqualTo(1);
        assertThat(association(lookup.lookUpResult(GITLAB_TARGET, ImmutableList.of(SHA), "main", new ArrayList<>()), SHA).number()).isEqualTo(2);
        assertThat(association(lookup.lookUpResult(GITLAB_TARGET, ImmutableList.of(SHA), "main", new ArrayList<>()), SHA).number()).isEqualTo(2);
        verify(gitLab).mergedWithCommit(GITLAB_TARGET, SHA);
    }

    @Test
    void breaksATieOnTheSourceBranchAndOtherwiseLeavesTheCommitUngrouped()
    {
        String tie = "b".repeat(40);
        when(gitHub.mergedWithCommit(GITHUB_TARGET, SHA)).thenReturn(ImmutableList.of(merged(1, "release-477"), merged(2, "master")));
        when(gitHub.mergedWithCommit(GITHUB_TARGET, tie)).thenReturn(ImmutableList.of(merged(3, "release-477"), merged(4, "release-478")));

        List<String> warnings = new ArrayList<>();
        PullRequestLookupResult result = lookup.lookUpResult(GITHUB_TARGET, ImmutableList.of(SHA, tie), "master", warnings);

        assertThat(association(result, SHA).number()).as("base ref breaks the tie").isEqualTo(2);
        assertThat(result.associations()).as("an ambiguous commit is never guessed").noneMatch(value -> value.sha().equals(tie));
        assertThat(warnings).anyMatch(warning -> warning.contains("no single merged pull request"));
    }

    @Test
    void leavesACommitWithNoMergedPullRequestUngrouped()
    {
        when(gitHub.mergedWithCommit(GITHUB_TARGET, SHA)).thenReturn(ImmutableList.of());

        List<String> warnings = new ArrayList<>();
        PullRequestLookupResult result = lookup.lookUpResult(GITHUB_TARGET, ImmutableList.of(SHA), "master", warnings);

        assertThat(result.associations()).isEmpty();
        assertThat(result.failedShas()).isEmpty();
        assertThat(warnings).anyMatch(warning -> warning.contains("listed individually"));
    }

    @Test
    void keepsCommitsSelectableWhenTheProviderFails()
    {
        when(gitLab.mergedWithCommit(GITLAB_TARGET, SHA)).thenThrow(new HostingFailure(UNAVAILABLE, "GitLab is unavailable"));

        List<String> warnings = new ArrayList<>();
        PullRequestLookupResult result = lookup.lookUpResult(GITLAB_TARGET, ImmutableList.of(SHA), "main", warnings);

        assertThat(result.associations()).isEmpty();
        assertThat(result.failedShas()).containsExactly(SHA);
        assertThat(warnings).anyMatch(warning -> warning.contains("could not be checked")).noneMatch(warning -> warning.contains("no single merged pull request"));
    }

    @Test
    void stopsAskingAfterARateLimitAndLeavesTheRestFailed()
    {
        List<String> shas = IntStream.range(0, 20).mapToObj(index -> "%040x".formatted(index)).toList();
        when(gitLab.mergedWithCommit(eq(GITLAB_TARGET), anyString())).thenThrow(new HostingFailure(RATE_LIMITED, "GitLab's rate limit was reached"));

        PullRequestLookupResult result = lookup.lookUpResult(GITLAB_TARGET, shas, "main", new ArrayList<>());

        assertThat(result.failedShas()).containsExactlyElementsOf(shas);
        // Only the lookups already running when the first limit came back reach the provider.
        verify(gitLab, atMost(8)).mergedWithCommit(eq(GITLAB_TARGET), anyString());
    }

    @Test
    void asksOncePerCommitEvenWhenLookedUpAgain()
    {
        when(gitHub.mergedWithCommit(GITHUB_TARGET, SHA)).thenReturn(ImmutableList.of(merged(5, "master")));

        lookup.lookUpResult(GITHUB_TARGET, ImmutableList.of(SHA), "master", new ArrayList<>());

        assertThat(association(lookup.lookUpResult(GITHUB_TARGET, ImmutableList.of(SHA), "master", new ArrayList<>()), SHA).number()).isEqualTo(5);
        verify(gitHub).mergedWithCommit(GITHUB_TARGET, SHA);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void negativeAndAmbiguousResultsCanBecomePositive(boolean ambiguous)
    {
        when(gitHub.mergedWithCommit(GITHUB_TARGET, SHA))
                .thenReturn(ambiguous ? ImmutableList.of(merged(1, "release-477"), merged(2, "release-478")) : ImmutableList.of())
                .thenReturn(ImmutableList.of(merged(3, "master")));

        PullRequestLookupResult first = lookup.lookUpResult(GITHUB_TARGET, ImmutableList.of(SHA), "master", new ArrayList<>());

        assertThat(first.associations()).isEmpty();
        assertThat(first.failedShas()).isEmpty();
        assertThat(association(lookup.lookUpResult(GITHUB_TARGET, ImmutableList.of(SHA), "master", new ArrayList<>()), SHA).number()).isEqualTo(3);
    }

    @Test
    void positiveMemoizationRetainsTheSourceBranchUsedToDisambiguate()
    {
        when(gitHub.mergedWithCommit(GITHUB_TARGET, SHA)).thenReturn(ImmutableList.of(merged(1, "release-477"), merged(2, "master")));

        assertThat(association(lookup.lookUpResult(GITHUB_TARGET, ImmutableList.of(SHA), "master", new ArrayList<>()), SHA).number()).isEqualTo(2);
        assertThat(association(lookup.lookUpResult(GITHUB_TARGET, ImmutableList.of(SHA), "release-477", new ArrayList<>()), SHA).number()).isEqualTo(1);
        assertThat(association(lookup.lookUpResult(GITHUB_TARGET, ImmutableList.of(SHA), "master", new ArrayList<>()), SHA).number()).isEqualTo(2);
        verify(gitHub, times(2)).mergedWithCommit(GITHUB_TARGET, SHA);
    }

    private static PullRequestAssociation merged(int number, String baseRef)
    {
        return new PullRequestAssociation(number, "Change " + number, "https://example.test/" + number, baseRef, "2026-08-0" + Math.min(number, 9) + "T00:00:00Z");
    }

    private static PullRequestAssociation association(PullRequestLookupResult result, String sha)
    {
        return result.associations().stream()
                .filter(value -> value.sha().equals(sha))
                .findFirst()
                .orElseThrow()
                .pullRequest();
    }

    private static HostingTarget target(long instanceId, HostingProvider provider, String web, String api, long repositoryId, String path)
    {
        HostingInstance instance = new HostingInstance(instanceId, provider, URI.create(web), URI.create(api));
        return new HostingTarget(new HostingAccess(instance, "test-token"), new HostingRepository(repositoryId, instanceId, "100", path, URI.create(web + "/" + path),
                URI.create(web + "/" + path + ".git"), Optional.empty(), Optional.of("main")));
    }
}
