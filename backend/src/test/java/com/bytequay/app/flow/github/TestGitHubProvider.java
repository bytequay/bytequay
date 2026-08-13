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
package com.bytequay.app.flow.github;

import com.bytequay.app.flow.gate.UserGateRecords.CiUpdateEffectActivation;
import com.bytequay.app.flow.gate.UserGates;
import com.bytequay.app.flow.github.GitHubEffectRecords.ProbeOutcome;
import com.bytequay.app.flow.runtime.FlowRuntime;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationKind;
import com.bytequay.app.flow.runtime.FlowRuntimeSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Modifier;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class TestGitHubProvider
{
    private static final String EXPECTED = "1111111111111111111111111111111111111111";
    private static final String PROPOSED = "2222222222222222222222222222222222222222";

    @TempDir
    private Path temporaryDirectory;

    private DataSource dataSource;
    private FlowRuntime runtime;

    @BeforeEach
    void setUp()
    {
        dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:" + temporaryDirectory.resolve("flow.db")
                        + "?foreign_keys=ON&busy_timeout=5000");
        FlowRuntimeSchema.install(dataSource);
        runtime = new FlowRuntime(
                dataSource,
                Clock.fixed(
                        Instant.parse("2026-08-11T00:00:00Z"),
                        ZoneOffset.UTC));
    }

    @Test
    void probeUsesOnlyTheExactCredentialFreePushRemote()
    {
        FakeGit git = new FakeGit();
        git.remotes = """
                origin https://github.com/base/repo.git (fetch)
                fork https://github.com/head/repo.git (push)
                """;
        git.remoteHead = EXPECTED;
        GitHubProvider provider = new GitHubProvider(
                runtime,
                (id, owner, name) -> credential(id, "secret-token"),
                git,
                matchingLookup());

        assertThat(provider.probe(claim(), activation()).observation().outcome())
                .isEqualTo(ProbeOutcome.ABSENT);
        assertThat(git.networkArguments).singleElement().satisfies(arguments -> {
            assertThat(arguments).contains(
                    "https://github.com/head/repo.git",
                    "refs/heads/task/one");
            assertThat(arguments).noneMatch(value ->
                    value.contains("secret-token"));
        });
        assertThat(git.networkEnvironment).containsEntry(
                "GIT_NO_REPLACE_OBJECTS", "1");
        assertThat(git.networkEnvironment).containsEntry(
                "GIT_NO_LAZY_FETCH", "1");
        assertThat(git.networkEnvironment)
                .containsEntry("GIT_CONFIG_SYSTEM", "/dev/null")
                .containsEntry("GIT_CONFIG_GLOBAL", "/dev/null");
        assertThat(git.networkEnvironment).containsEntry(
                "GIT_CONFIG_VALUE_4", "true");
        assertThat(git.networkEnvironment).containsEntry(
                "GIT_CONFIG_VALUE_5", "false");
    }

    @Test
    void repositoryIdentityMismatchFailsBeforeRemoteAccess()
    {
        FakeGit git = new FakeGit();
        git.remotes = "fork https://github.com/head/repo.git (push)\n";
        AtomicReference<String> credentialRepository = new AtomicReference<>();
        GitHubProvider provider = new GitHubProvider(
                runtime,
                (externalId, owner, name) -> {
                    credentialRepository.set(externalId);
                    return credential(externalId, "token");
                },
                git,
                (owner, repository, token) ->
                        new GitHubProvider.RepositoryIdentity(
                                true, true, "different-id", owner, repository));

        assertThat(provider.probe(claim(), activation()).failure().kind())
                .isEqualTo(
                        GitHubEffectRecords.ProviderFailureKind.INVALID);
        assertThat(credentialRepository.get()).isEqualTo("head-external-1");
        assertThat(git.networkArguments).isEmpty();
    }

    @Test
    void credentialForAnotherRepositoryIsUnavailableWithoutRemoteAccess()
    {
        FakeGit git = new FakeGit();
        git.remotes = "fork https://github.com/head/repo.git (push)\n";
        GitHubProvider provider = new GitHubProvider(
                runtime,
                (externalId, owner, name) ->
                        credential("different-id", "token"),
                git,
                matchingLookup());

        assertThat(provider.probe(claim(), activation()).failure().kind())
                .isEqualTo(
                        GitHubEffectRecords.ProviderFailureKind.UNAVAILABLE);
        assertThat(git.networkArguments).isEmpty();
    }

    @Test
    void repositoryLookupAcceptsOnlyOneBoundedExactGitHubIdentity()
    {
        String exact = """
                {"id":123,"name":"repo","owner":{"login":"head"}}
                """;
        var matched = new GitHubProvider.DirectRepositoryLookup(
                (uri, token) -> new GitHubProvider.RepositoryHttpResponse(
                        true, 200, exact.getBytes(StandardCharsets.UTF_8)))
                .lookup("head", "repo", "secret".toCharArray());
        assertThat(matched).isEqualTo(
                new GitHubProvider.RepositoryIdentity(
                        true, true, "123", "head", "repo"));

        for (GitHubProvider.RepositoryHttpResponse response : List.of(
                new GitHubProvider.RepositoryHttpResponse(
                        true, 404, new byte[0]),
                new GitHubProvider.RepositoryHttpResponse(
                        true, 302, new byte[0]),
                new GitHubProvider.RepositoryHttpResponse(
                        true, 200, new byte[0]),
                new GitHubProvider.RepositoryHttpResponse(
                        true, 200, "null".getBytes(StandardCharsets.UTF_8)),
                new GitHubProvider.RepositoryHttpResponse(
                        true, 200, "not-json".getBytes(StandardCharsets.UTF_8)),
                new GitHubProvider.RepositoryHttpResponse(
                        false, -1, new byte[0]),
                new GitHubProvider.RepositoryHttpResponse(
                        true, 200, new byte[64 * 1024 + 1]))) {
            assertThat(new GitHubProvider.DirectRepositoryLookup(
                    (uri, token) -> response)
                    .lookup("head", "repo", "secret".toCharArray())
                    .complete()).isFalse();
        }
        assertThat(new GitHubProvider.DirectRepositoryHttp().proxies(
                URI.create("https://api.github.com/repos/head/repo")))
                .containsExactly(Proxy.NO_PROXY);
        assertThat(new GitHubProvider.DirectInitialHttp().proxies(
                URI.create("https://api.github.com/repos/base/repo/pulls")))
                .containsExactly(Proxy.NO_PROXY);
    }

    @Test
    void missingOrInvalidPushTargetNeverBecomesAbsent()
    {
        FakeGit missing = new FakeGit();
        missing.remotes = "fork https://github.com/head/repo.git (push)\n";
        missing.remoteHead = null;
        assertThat(new GitHubProvider(
                runtime,
                (id, owner, name) -> credential(id, "token"),
                missing,
                matchingLookup())
                .probe(claim(), activation()).observation().outcome())
                .isEqualTo(ProbeOutcome.DIVERGED);

        for (String remotes : List.of(
                "fork git@github.com:head/repo.git (push)\n",
                "fork https://user:secret@github.com/head/repo.git (push)\n",
                "fork https://example.com/head/repo.git (push)\n",
                """
                one https://github.com/head/repo.git (push)
                two https://github.com/head/repo.git (push)
                """)) {
            FakeGit invalid = new FakeGit();
            invalid.remotes = remotes;
            assertThat(new GitHubProvider(
                    runtime,
                    (id, owner, name) -> credential(id, "token"),
                    invalid,
                    matchingLookup())
                    .probe(claim(), activation()).failure().kind())
                    .isEqualTo(
                            GitHubEffectRecords.ProviderFailureKind.INVALID);
            assertThat(invalid.networkArguments).isEmpty();
        }
    }

    @Test
    void includedTransportOverrideFailsClosedBeforeNetwork()
    {
        for (String dangerous : List.of(
                "url.https://evil.invalid/.insteadof https://github.com/\n",
                "http.https://github.com/head/repo.git/info/refs.sslverify false\n",
                "push.pushoption secret-option\n",
                "push.gpgsign true\n")) {
            FakeGit git = new FakeGit();
            if (dangerous.startsWith("url.")) {
                git.urlConfig = dangerous;
            }
            else if (dangerous.startsWith("http.")) {
                git.httpConfig = dangerous;
            }
            else {
                git.pushConfig = dangerous;
            }
            assertThat(new GitHubProvider(
                    runtime,
                    (id, owner, name) -> credential(id, "token"),
                    git,
                    matchingLookup())
                    .probe(claim(), activation()).failure().kind())
                    .isEqualTo(
                            GitHubEffectRecords.ProviderFailureKind.INVALID);
            assertThat(git.networkArguments).isEmpty();
        }
    }

    @Test
    void includedLocalHttpOverrideIsActuallyExpandedAndRejected()
            throws IOException
    {
        Path repository = temporaryDirectory.resolve("included-config-repo");
        runGit(repository.getParent(), "init", repository.toString());
        Files.writeString(
                repository.resolve(".git/dangerous.conf"),
                """
                [http "https://github.com/head/repo.git/info/refs"]
                    sslVerify = false
                """);
        Files.writeString(
                repository.resolve(".git/config"),
                Files.readString(repository.resolve(".git/config"))
                        + """
                        [include]
                            path = dangerous.conf
                        """);
        GitHubProvider provider = new GitHubProvider(
                runtime, (id, owner, name) -> credential(id, "token"));

        assertThat(provider.probe(
                claim(), activation(repository)).failure().kind())
                .isEqualTo(
                        GitHubEffectRecords.ProviderFailureKind.INVALID);
    }

    @Test
    void deterministicPreparationFailureCreatesNoProviderCommand()
    {
        FakeGit git = new FakeGit();
        git.remotes = "fork https://github.com/head/repo.git (push)\n";
        git.proofExitCode = 1;
        GitHubProvider provider = new GitHubProvider(
                runtime,
                (id, owner, name) -> credential(id, "token"),
                git,
                matchingLookup());

        assertThat(provider.prepareMutation(
                claim(), activation()).failure().kind())
                .isEqualTo(
                        GitHubEffectRecords.ProviderFailureKind.INVALID);
        assertThat(git.networkArguments).isEmpty();
    }

    @Test
    void preparationFailureAlwaysWipesTheRepositoryCredential()
    {
        FakeGit git = new FakeGit();
        git.remotes = "fork https://github.com/head/repo.git (push)\n";
        char[] token = "bad\nheader".toCharArray();
        GitHubProvider provider = new GitHubProvider(
                runtime,
                (id, owner, name) ->
                        new GitHubProvider.RepositoryCredential(id, token),
                git,
                (owner, repository, ignored) -> {
                    throw new IllegalStateException("lookup failed");
                });

        assertThatThrownBy(() -> provider.prepareMutation(
                claim(), activation()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lookup failed");
        assertThat(token).containsOnly('\0');
        assertThat(git.networkArguments).isEmpty();
    }

    @Test
    void directGitSeparatesStderrAndFailsClosedOnStdoutOverflow()
            throws IOException
    {
        Path repository = temporaryDirectory.resolve("repository");
        runGit(repository.getParent(), "init", repository.toString());
        GitHubProvider.DirectGitProcess direct =
                new GitHubProvider.DirectGitProcess();
        var separated = direct.run(
                repository,
                List.of(
                        "-c",
                        "alias.warn=!f() { echo warning >&2; echo ok; }; f",
                        "warn"),
                Map.of("PATH", "/usr/bin:/bin", "LANG", "C"),
                true);
        assertThat(separated.complete()).isTrue();
        assertThat(separated.output().strip()).isEqualTo("ok");

        StringBuilder config = new StringBuilder("[core]\n\trepositoryformatversion = 0\n");
        for (int index = 0; index < 2_000; index++) {
            config.append("[remote \"r").append(index).append("\"]\n")
                    .append("\turl = https://github.com/head/repo.git\n")
                    .append("\tpushurl = https://github.com/head/repo.git\n");
        }
        Files.writeString(
                repository.resolve(".git/config"),
                config,
                StandardCharsets.UTF_8);
        var overflow = direct.run(
                repository,
                List.of("remote", "-v"),
                Map.of("PATH", "/usr/bin:/bin", "LANG", "C"),
                true);
        assertThat(overflow.complete()).isFalse();
    }

    @Test
    void directGitClosesHeldPipesWithoutLeakingDrainThreads()
            throws IOException
    {
        Path repository = temporaryDirectory.resolve("held-pipe-repository");
        runGit(repository.getParent(), "init", repository.toString());
        GitHubProvider.DirectGitProcess direct =
                new GitHubProvider.DirectGitProcess();

        var held = direct.run(
                repository,
                List.of(
                        "-c",
                        "alias.hold=!sh -c \"(yes held) &\"",
                        "hold"),
                Map.of("PATH", "/usr/bin:/bin", "LANG", "C"),
                true);

        assertThat(held.complete()).isFalse();
        assertThat(held.output()).isEmpty();
        assertThat(GitHubProvider.DirectGitProcess.liveDrainCount()).isZero();
    }

    @Test
    void exactLeaseRejectsAnIntermediateFastForwardRace()
            throws IOException
    {
        Path repository = temporaryDirectory.resolve("lease-repository");
        Path remote = temporaryDirectory.resolve("lease-remote.git");
        runGit(repository.getParent(), "init", repository.toString());
        runGit(repository, "config", "user.email", "test@example.com");
        runGit(repository, "config", "user.name", "Test User");
        Files.writeString(repository.resolve("value.txt"), "expected\n");
        runGit(repository, "add", "value.txt");
        runGit(repository, "commit", "-m", "expected");
        String expected = runGitOutput(repository, "rev-parse", "HEAD");
        Files.writeString(repository.resolve("value.txt"), "intermediate\n");
        runGit(repository, "commit", "-am", "intermediate");
        String intermediate = runGitOutput(repository, "rev-parse", "HEAD");
        Files.writeString(repository.resolve("value.txt"), "proposed\n");
        runGit(repository, "commit", "-am", "proposed");
        String proposed = runGitOutput(repository, "rev-parse", "HEAD");
        runGit(repository.getParent(), "init", "--bare", remote.toString());
        String ref = "refs/heads/task/one";
        runGit(repository, "push", remote.toString(), expected + ":" + ref);
        runGit(repository, "push", remote.toString(), intermediate + ":" + ref);

        List<String> exact = GitHubProvider.exactPushArguments(
                ref, expected, proposed, remote.toString());
        assertThat(runGitExit(repository, exact)).isNotZero();
        assertThat(runGitOutput(remote, "rev-parse", ref))
                .isEqualTo(intermediate);

        runGit(repository, "push", "--force", remote.toString(),
                expected + ":" + ref);
        assertThat(runGitExit(repository, exact)).isZero();
        assertThat(runGitOutput(remote, "rev-parse", ref))
                .isEqualTo(proposed);
        assertThat(exact).doesNotContain("--force");
    }

    @Test
    void initialCreateUsesOnlyTheAtomicEmptyOldLease()
            throws IOException
    {
        Path repository = temporaryDirectory.resolve("initial-repository");
        Path remote = temporaryDirectory.resolve("initial-remote.git");
        runGit(repository.getParent(), "init", repository.toString());
        runGit(repository, "config", "user.email", "test@example.com");
        runGit(repository, "config", "user.name", "Test User");
        Files.writeString(repository.resolve("value.txt"), "first\n");
        runGit(repository, "add", "value.txt");
        runGit(repository, "commit", "-m", "first");
        String first = runGitOutput(repository, "rev-parse", "HEAD");
        Files.writeString(repository.resolve("value.txt"), "second\n");
        runGit(repository, "commit", "-am", "second");
        String second = runGitOutput(repository, "rev-parse", "HEAD");
        runGit(repository.getParent(), "init", "--bare", remote.toString());
        String ref = "refs/heads/task/initial";

        List<String> firstCreate = GitHubProvider.initialCreateRefArguments(
                ref, first, remote.toString());
        assertThat(runGitExit(repository, firstCreate)).isZero();
        assertThat(runGitOutput(remote, "rev-parse", ref)).isEqualTo(first);

        List<String> racedCreate = GitHubProvider.initialCreateRefArguments(
                ref, second, remote.toString());
        assertThat(runGitExit(repository, racedCreate)).isNotZero();
        assertThat(runGitOutput(remote, "rev-parse", ref)).isEqualTo(first);
        assertThat(racedCreate).contains(
                "--force-with-lease=" + ref + ":",
                second + ":" + ref);
        assertThat(racedCreate).doesNotContain("--force");
    }

    @Test
    void legacyGraftCannotFabricateFastForwardAuthority()
            throws IOException
    {
        Path repository = temporaryDirectory.resolve("graft-repository");
        runGit(repository.getParent(), "init", repository.toString());
        runGit(repository, "config", "user.email", "test@example.com");
        runGit(repository, "config", "user.name", "Test User");
        Files.writeString(repository.resolve("value.txt"), "expected\n");
        runGit(repository, "add", "value.txt");
        runGit(repository, "commit", "-m", "expected");
        String expected = runGitOutput(repository, "rev-parse", "HEAD");
        runGit(repository, "checkout", "--orphan", "unrelated");
        Files.writeString(repository.resolve("value.txt"), "proposed\n");
        runGit(repository, "add", "value.txt");
        runGit(repository, "commit", "-m", "proposed");
        String proposed = runGitOutput(repository, "rev-parse", "HEAD");
        Files.createDirectories(repository.resolve(".git/info"));
        Files.writeString(
                repository.resolve(".git/info/grafts"),
                proposed + " " + expected + "\n");
        runGit(repository, "merge-base", "--is-ancestor", expected, proposed);

        GitHubProvider provider = new GitHubProvider(
                runtime,
                (id, owner, name) -> credential(id, "token"),
                new GitHubProvider.DirectGitProcess(),
                matchingLookup());

        assertThat(provider.prepareMutation(
                claim(), activation(repository, expected, proposed))
                .failure().kind()).isEqualTo(
                        GitHubEffectRecords.ProviderFailureKind.INVALID);
    }

    @Test
    void anAuthorizedRewriteMayPrepareANonFastForwardExactLease()
            throws IOException
    {
        Path repository = temporaryDirectory.resolve("rewrite-repository");
        runGit(repository.getParent(), "init", repository.toString());
        runGit(repository, "config", "user.email", "test@example.com");
        runGit(repository, "config", "user.name", "Test User");
        runGit(repository, "remote", "add", "fork",
                "https://github.com/head/repo.git");
        Files.writeString(repository.resolve("value.txt"), "expected\n");
        runGit(repository, "add", "value.txt");
        runGit(repository, "commit", "-m", "expected");
        String expected = runGitOutput(repository, "rev-parse", "HEAD");
        runGit(repository, "checkout", "--orphan", "rewritten");
        Files.writeString(repository.resolve("value.txt"), "rewritten\n");
        runGit(repository, "add", "value.txt");
        runGit(repository, "commit", "-m", "rewritten");
        String proposed = runGitOutput(repository, "rev-parse", "HEAD");
        GitHubProvider provider = new GitHubProvider(
                runtime,
                (id, owner, name) -> credential(id, "token"),
                new GitHubProvider.DirectGitProcess(),
                matchingLookup());

        assertThat(provider.prepareMutation(
                claim(), activation(repository, expected, proposed, false))
                .failure().kind()).isEqualTo(
                        GitHubEffectRecords.ProviderFailureKind.INVALID);
        assertThat(provider.prepareMutation(
                claim(), activation(repository, expected, proposed, true))
                .push()).isNotNull();
        assertThat(GitHubProvider.exactPushArguments(
                "refs/heads/task/one", expected, proposed, "remote"))
                .contains("--force-with-lease=refs/heads/task/one:" + expected)
                .doesNotContain("--force");
    }

    @Test
    void executorRejectsAnAmbientOwnerTransaction()
    {
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        assertThatThrownBy(() -> transaction.execute(ignored -> {
            GitHubCiUpdateExecutor.requireNoAmbientTransaction();
            return null;
        })).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside an owner transaction");
    }

    @Test
    void providerProofAttemptAndSettlementCapabilitiesAreNotConstructible()
    {
        assertThat(GitHubEffectRecords.ProviderObservation.class.isSealed())
                .isTrue();
        assertThat(GitHubEffectRecords.ProviderFailure.class.isSealed())
                .isTrue();
        assertThat(InitialPublishRecords.ProviderFailure.class.isSealed())
                .isTrue();
        assertThat(InitialPublishRecords.RepositoryObservation.class.isSealed())
                .isTrue();
        assertThat(List.of(
                GitHubEffects.ActivatedAttempt.class,
                GitHubEffects.ActivatedInitialAttempt.class,
                GitHubProvider.PreparedPush.class,
                GitHubProvider.PreparedInitialMutation.class,
                GitHubProvider.ExactInitialFailure.class,
                GitHubProvider.ExactInitialRepositoryObservation.class,
                GitHubInitialPublishExecutor.SettlementRequired.class,
                UserGates.PublishDisposition.class))
                .allSatisfy(type -> assertThat(List.of(
                        type.getDeclaredConstructors()))
                        .allSatisfy(constructor -> assertThat(
                                Modifier.isPrivate(constructor.getModifiers()))
                                .isTrue()));
        assertThat(List.of(FlowRuntime.class.getMethods()))
                .extracting(method -> method.getName())
                .doesNotContain(
                        "cancelClaimedPublish",
                        "succeedClaimedPublish",
                        "retryClaimedPublish",
                        "succeedPublishAttempt",
                        "cancelPublishAttempt",
                        "retryPublishAttempt");
    }

    private CiUpdateEffectActivation activation()
    {
        return activation(temporaryDirectory);
    }

    private CiUpdateEffectActivation activation(Path repositoryRoot)
    {
        return activation(repositoryRoot, EXPECTED, PROPOSED);
    }

    private CiUpdateEffectActivation activation(
            Path repositoryRoot, String expected, String proposed)
    {
        return activation(repositoryRoot, expected, proposed, false);
    }

    private CiUpdateEffectActivation activation(
            Path repositoryRoot, String expected, String proposed,
            boolean forcePush)
    {
        return new CiUpdateEffectActivation(
                "authorization-1",
                "plan-1",
                "operation-1",
                "pr-1",
                1,
                repositoryRoot.toString(),
                "head-external-1",
                "head",
                "repo",
                "refs/heads/task/one",
                expected,
                proposed,
                forcePush,
                true,
                "plan-digest-1");
    }

    private static GitHubProvider.RepositoryLookup matchingLookup()
    {
        return (owner, repository, token) ->
                new GitHubProvider.RepositoryIdentity(
                        true,
                        true,
                        "head-external-1",
                        owner,
                        repository);
    }

    private static GitHubProvider.RepositoryCredential credential(
            String externalId, String token)
    {
        return new GitHubProvider.RepositoryCredential(
                externalId, token.toCharArray());
    }

    private Claim claim()
    {
        return new Claim(
                "operation-1",
                "task-1",
                OperationKind.PUBLISH,
                1,
                "claim-token-1",
                "publisher-1",
                Instant.parse("2026-08-11T00:00:00Z")
                        .plus(Duration.ofMinutes(5)));
    }

    private static void runGit(Path directory, String... arguments)
            throws IOException
    {
        List<String> command = new ArrayList<>();
        command.add("/usr/bin/git");
        command.addAll(List.of(arguments));
        try {
            Process process = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .start();
            if (process.waitFor() != 0) {
                throw new IOException("git fixture command failed");
            }
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(interrupted);
        }
    }

    private static String runGitOutput(Path directory, String... arguments)
            throws IOException
    {
        List<String> command = new ArrayList<>();
        command.add("/usr/bin/git");
        command.addAll(List.of(arguments));
        try {
            Process process = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(
                    process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8).strip();
            if (process.waitFor() != 0) {
                throw new IOException("git fixture command failed");
            }
            return output;
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(interrupted);
        }
    }

    private static int runGitExit(Path directory, List<String> arguments)
            throws IOException
    {
        List<String> command = new ArrayList<>();
        command.add("/usr/bin/git");
        command.addAll(arguments);
        try {
            Process process = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .start();
            process.getInputStream().transferTo(
                    OutputStream.nullOutputStream());
            return process.waitFor();
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(interrupted);
        }
    }

    private static final class FakeGit implements GitHubProvider.GitProcess
    {
        private String remotes = "";
        private String remoteHead = EXPECTED;
        private String urlConfig = "";
        private String httpConfig = "";
        private String pushConfig = "";
        private int proofExitCode;
        private final List<List<String>> networkArguments = new ArrayList<>();
        private Map<String, String> networkEnvironment = new HashMap<>();

        @Override
        public GitHubProvider.ProcessResult run(
                Path repositoryRoot,
                List<String> arguments,
                Map<String, String> environment,
                boolean captureOutput)
        {
            if (arguments.contains("--git-common-dir")) {
                return new GitHubProvider.ProcessResult(
                        true,
                        0,
                        repositoryRoot.resolve(".git") + "\n");
            }
            if (arguments.contains("--get-regexp")) {
                String pattern = arguments.getLast();
                String value = pattern.startsWith("^url")
                        ? urlConfig
                        : pattern.startsWith("^http")
                        ? httpConfig
                        : pushConfig;
                return new GitHubProvider.ProcessResult(
                        true, value.isEmpty() ? 1 : 0, value);
            }
            if (arguments.equals(List.of("remote", "-v"))) {
                return new GitHubProvider.ProcessResult(true, 0, remotes);
            }
            if (arguments.getFirst().equals("ls-remote")) {
                networkArguments.add(List.copyOf(arguments));
                networkEnvironment = Map.copyOf(environment);
                String output = remoteHead == null
                        ? ""
                        : remoteHead + "\t" + arguments.getLast() + "\n";
                return new GitHubProvider.ProcessResult(true, 0, output);
            }
            if (arguments.getFirst().equals("push")) {
                networkArguments.add(List.copyOf(arguments));
                networkEnvironment = Map.copyOf(environment);
                return new GitHubProvider.ProcessResult(true, 0, "");
            }
            return new GitHubProvider.ProcessResult(
                    true, proofExitCode, "");
        }
    }
}
