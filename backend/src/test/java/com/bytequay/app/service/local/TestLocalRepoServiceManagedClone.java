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
package com.bytequay.app.service.local;

import com.bytequay.app.domain.AttentionReason;
import com.bytequay.app.domain.Credential;
import com.bytequay.app.domain.CredentialType;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.RepoMeta;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.UserProfile;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.repository.CredentialStore;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.PullRequestStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.CredentialService;
import com.bytequay.app.service.ai.LlmReviewerRegistry;
import com.bytequay.app.service.credentials.PatResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TestLocalRepoServiceManagedClone
{
    @TempDir
    Path home;

    @Test
    void planDefaultsToForkWhenDirectWriteIsAvailable()
            throws Exception
    {
        Fixture f = new Fixture();
        f.gitHub.profile = profile("jack");
        f.gitHub.viewerCanWrite = true;

        LocalRepoService.ManagedClonePlan plan = withHome(home,
                () -> f.service.managedClonePlan("trinodb", "trino"));

        assertThat(plan.viewerLogin()).isEqualTo("jack");
        assertThat(plan.directAvailable()).isTrue();
        assertThat(plan.forkAvailable()).isTrue();
        assertThat(plan.defaultWriteMode()).isEqualTo(LocalRepoService.WriteMode.FORK);
        assertThat(plan.destination()).isEqualTo(
                home.resolve("Library/Application Support/ByteQuay/repos/trinodb/trino").toString());
    }

    @Test
    void directModeClonesWatchedRepo()
            throws Exception
    {
        Fixture f = new Fixture();
        Path destination = managedPath(home, "trinodb", "trino");
        f.gitHub.profile = profile("jack");
        f.gitHub.viewerCanWrite = true;

        withHome(home, () -> f.service.cloneManaged("trinodb", "trino", LocalRepoService.WriteMode.DIRECT));

        WatchedRepo watched = f.store.find("trinodb", "trino").orElseThrow();
        assertThat(watched.localClonePath()).isEqualTo(destination.toString());
        assertThat(watched.upstreamRemoteName()).isNull();
        assertThat(f.gitRunner.clonedUrl).isEqualTo("https://github.com/trinodb/trino.git");
        assertThat(f.gitRunner.clonedDestination).isEqualTo(destination);
        assertThat(f.gitRunner.addedRemotes).isEmpty();
    }

    @Test
    void forkModeCreatesForkAndAddsUpstreamRemote()
            throws Exception
    {
        Fixture f = new Fixture();
        Path destination = managedPath(home, "trinodb", "trino");
        RepoRef watched = RepoRef.of("trinodb", "trino");
        f.gitHub.profile = profile("jack");
        f.gitHub.repoMetaResponses.add(Optional.empty());
        f.gitHub.repoMetaResponses.add(Optional.of(forkMeta("jack", "trino", "trinodb", "trino")));

        withHome(home, () -> f.service.cloneManaged("trinodb", "trino", LocalRepoService.WriteMode.FORK));

        WatchedRepo row = f.store.find("trinodb", "trino").orElseThrow();
        assertThat(row.localClonePath()).isEqualTo(destination.toString());
        assertThat(row.upstreamRemoteName()).isEqualTo("upstream");
        assertThat(f.gitHub.createdFork).isEqualTo(watched);
        assertThat(f.gitRunner.clonedUrl).isEqualTo("https://github.com/jack/trino.git");
        assertThat(f.gitRunner.clonedDestination).isEqualTo(destination);
        assertThat(f.gitRunner.addedRemotes)
                .containsExactly(new AddedRemote(destination, "upstream", "https://github.com/trinodb/trino.git"));
        assertThat(f.gitRunner.fetchedRemotes)
                .containsExactly(new FetchedRemote(destination, "upstream"));
    }

    private static UserProfile profile(String login)
    {
        return new UserProfile(login, login, null, "https://github.com/" + login,
                0, 0, 0, null, null, null, null, false);
    }

    private static RepoMeta forkMeta(String owner, String repo, String parentOwner, String parentRepo)
    {
        return new RepoMeta(
                owner + "/" + repo,
                "https://github.com/" + owner + "/" + repo,
                null,
                "main",
                null,
                0,
                0,
                0,
                0,
                0,
                Instant.EPOCH,
                Instant.EPOCH,
                List.of(),
                Map.of(),
                null,
                parentOwner,
                parentRepo,
                "main");
    }

    private static Path managedPath(Path home, String owner, String repo)
    {
        return home.resolve("Library/Application Support/ByteQuay/repos").resolve(owner).resolve(repo);
    }

    private static <T> T withHome(Path home, ThrowingSupplier<T> supplier)
            throws Exception
    {
        String old = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            return supplier.get();
        }
        finally {
            System.setProperty("user.home", old);
        }
    }

    private interface ThrowingSupplier<T>
    {
        T get()
                throws Exception;
    }

    private static final class Fixture
    {
        final InMemoryWatchedRepoStore store = new InMemoryWatchedRepoStore();
        final RecordingGitRunner gitRunner = new RecordingGitRunner();
        final FakeGitHub gitHub = new FakeGitHub();
        final LocalRepoService service;

        Fixture()
        {
            this.service = new LocalRepoService(
                    store,
                    gitRunner,
                    gitHub,
                    new UnsupportedPullRequestStore(),
                    new LlmReviewerRegistry(List.of(), new MemoryAppSettingsStore()),
                    new FixedPatResolver("pat"));
        }
    }

    private record AddedRemote(Path workingDir, String name, String url) {}

    private record FetchedRemote(Path workingDir, String name) {}

    private static final class RecordingGitRunner
            extends GitRunner
    {
        String clonedUrl;
        Path clonedDestination;
        final List<AddedRemote> addedRemotes = new ArrayList<>();
        final List<FetchedRemote> fetchedRemotes = new ArrayList<>();

        @Override
        public void clone(String url, Path destination)
                throws IOException, InterruptedException
        {
            this.clonedUrl = url;
            this.clonedDestination = destination;
            Files.createDirectories(destination);
        }

        @Override
        public void addRemote(Path workingDir, String name, String url)
        {
            addedRemotes.add(new AddedRemote(workingDir, name, url));
        }

        @Override
        public void fetchRemote(Path workingDir, String remote)
        {
            fetchedRemotes.add(new FetchedRemote(workingDir, remote));
        }

        @Override
        public void setRemoteHead(Path workingDir, String remote) {}

        @Override
        public boolean isGitWorkingTree(Path workingDir)
        {
            return true;
        }

        @Override
        public int countDirtyFiles(Path workingDir)
        {
            return 0;
        }

        @Override
        public String currentBranch(Path workingDir)
        {
            return "main";
        }

        @Override
        public Optional<String> defaultBranch(Path workingDir)
        {
            return Optional.of("main");
        }
    }

    private static final class FakeGitHub
            implements PullRequestRepository
    {
        UserProfile profile = profile("jack");
        boolean viewerCanWrite;
        RepoRef createdFork;
        final List<Optional<RepoMeta>> repoMetaResponses = new ArrayList<>();

        @Override
        public UserProfile fetchUserProfile(String pat)
        {
            return profile;
        }

        @Override
        public boolean fetchViewerCanWrite(String pat, RepoRef repo)
        {
            return viewerCanWrite;
        }

        @Override
        public Optional<RepoMeta> findRepoMeta(String pat, RepoRef repo)
        {
            if (repoMetaResponses.isEmpty()) {
                return Optional.empty();
            }
            return repoMetaResponses.remove(0);
        }

        @Override
        public void createFork(String pat, RepoRef repo)
        {
            createdFork = repo;
        }
    }

    private static final class FixedPatResolver
            extends PatResolver
    {
        private final String token;

        FixedPatResolver(String token)
        {
            super(new CredentialService(new EmptyCredentialStore(), new MemoryAppSettingsStore()));
            this.token = token;
        }

        @Override
        public String resolve(String repoFullName)
        {
            return token;
        }

        @Override
        public String resolve()
        {
            return token;
        }
    }

    private static final class UnsupportedPullRequestStore
            implements PullRequestStore
    {
        @Override
        public List<PullRequest> findAll()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void replaceAll(List<PullRequest> pullRequests)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Instant> lastSyncedAt()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<Long, Instant> findUpdatedAtMap()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<Long> findIdsMissingEnrichment()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Long> findIdByRepoAndNumber(String repo, int number)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<PullRequest> findById(long prId)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateEnrichment(
                long prId,
                PullRequestDetail.CiStatus ciStatus,
                int additions,
                int deletions,
                int commentCount,
                AttentionReason attentionReason,
                Boolean mergeable,
                String mergeableState,
                Instant headPushedAt,
                Map<String, String> reviewerVerdicts,
                String headRef)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateCiStatus(long prId, PullRequestDetail.CiStatus ciStatus)
        {
            throw new UnsupportedOperationException();
        }
    }

    private static final class MemoryAppSettingsStore
            implements AppSettingsStore
    {
        private final Map<String, String> values = new LinkedHashMap<>();

        @Override
        public Optional<String> get(String key)
        {
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public void set(String key, String value)
        {
            values.put(key, value);
        }
    }

    private static final class EmptyCredentialStore
            implements CredentialStore
    {
        @Override
        public List<Credential> findAll()
        {
            return List.of();
        }

        @Override
        public List<Credential> findByType(CredentialType type)
        {
            return List.of();
        }

        @Override
        public List<Credential> findByTypeAndName(CredentialType type, String name)
        {
            return List.of();
        }

        @Override
        public Optional<Credential> find(CredentialType type, String name)
        {
            return Optional.empty();
        }

        @Override
        public Optional<Credential> find(CredentialType type, String name, String instanceName)
        {
            return Optional.empty();
        }

        @Override
        public Optional<Credential> findDefault(CredentialType type, String name)
        {
            return Optional.empty();
        }

        @Override
        public Credential setDefault(CredentialType type, String name, String instanceName)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<String> getSecret(CredentialType type, String name)
        {
            return Optional.empty();
        }

        @Override
        public Optional<String> getSecret(CredentialType type, String name, String instanceName)
        {
            return Optional.empty();
        }

        @Override
        public Credential upsert(
                CredentialType type,
                String name,
                String instanceName,
                String rawValue,
                String label,
                String notes,
                String configJson)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(CredentialType type, String name, String instanceName) {}
    }

    private static final class InMemoryWatchedRepoStore
            implements WatchedRepoStore
    {
        private final Map<String, WatchedRepo> rows = new LinkedHashMap<>();
        private long nextId = 1;

        @Override
        public List<WatchedRepo> findAll()
        {
            return List.copyOf(rows.values());
        }

        @Override
        public Optional<WatchedRepo> find(String owner, String repo)
        {
            return Optional.ofNullable(rows.get(key(owner, repo)));
        }

        @Override
        public WatchedRepo add(String owner, String repo)
        {
            WatchedRepo row = new WatchedRepo(nextId++, owner, repo, rows.size(), null, null, null);
            rows.put(key(owner, repo), row);
            return row;
        }

        @Override
        public void remove(String owner, String repo)
        {
            rows.remove(key(owner, repo));
        }

        @Override
        public void setLocalClonePath(String owner, String repo, String localClonePath)
        {
            WatchedRepo row = find(owner, repo).orElseThrow();
            rows.put(key(owner, repo), new WatchedRepo(row.id(), owner, repo, row.displayOrder(),
                    localClonePath, row.upstreamRemoteName(), row.viewFocus()));
        }

        @Override
        public void setUpstreamRemoteName(String owner, String repo, String upstreamRemoteName)
        {
            WatchedRepo row = find(owner, repo).orElseThrow();
            rows.put(key(owner, repo), new WatchedRepo(row.id(), owner, repo, row.displayOrder(),
                    row.localClonePath(), upstreamRemoteName, row.viewFocus()));
        }

        @Override
        public void setViewFocus(String owner, String repo, String viewFocus)
        {
            WatchedRepo row = find(owner, repo).orElseThrow();
            rows.put(key(owner, repo), new WatchedRepo(row.id(), owner, repo, row.displayOrder(),
                    row.localClonePath(), row.upstreamRemoteName(), viewFocus));
        }

        private static String key(String owner, String repo)
        {
            return owner + "/" + repo;
        }
    }
}
