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
package com.bytequay.app.service.harness;

import com.bytequay.app.service.harness.HarnessModels.BootstrapProfile;
import com.bytequay.app.service.harness.HarnessModels.FixResult;
import com.bytequay.app.service.harness.HarnessModels.VerifiedFix;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.local.ShellRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestHarnessVerifier
{
    @TempDir
    Path root;

    private final ShellRunner shell = mock(ShellRunner.class);
    private final GitRunner git = mock(GitRunner.class);
    private final HarnessVerifier verifier = new HarnessVerifier(shell, git);

    @BeforeEach
    void setUp()
            throws Exception
    {
        Files.writeString(root.resolve("pom.xml"), "changed");
        when(git.statusPorcelainZ(root)).thenReturn(" M pom.xml\u0000");
        when(shell.runArgv(any(), any(), any(), anyLong(), anyInt()))
                .thenReturn(new ShellRunner.Result(true, 0, "ok", false, null));
    }

    @Test
    void runsOnlyTheGenericVerbRequestedByTheFix()
            throws Exception
    {
        BootstrapProfile profile = profile(
                Map.of(
                        "style", List.of("mvn checkstyle:check"),
                        "build", List.of("mvn package"),
                        "test", List.of("./mvnw -q test"),
                        "regen", List.of("mvn generate-resources")),
                Map.of("module-a/", "module-a"), Map.of("JAVA_VERSION", "21"));

        VerifiedFix verified = verifier.verify(
                root, fix(List.of("test:ExampleTest#works")), profile, "module-a");

        assertThat(verified.verification().passed()).isTrue();
        assertThat(verified.fix().filesChanged()).containsExactly("pom.xml");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> argv = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> environment = ArgumentCaptor.forClass(Map.class);
        verify(shell).runArgv(eq(root), argv.capture(), environment.capture(), anyLong(), anyInt());
        assertThat(argv.getValue())
                .containsExactly("./mvnw", "-pl", "module-a", "-am", "-q", "test");
        assertThat(environment.getValue()).containsExactlyInAnyOrderEntriesOf(
                Map.of("JAVA_VERSION", "21", "CI", "true"));
    }

    @Test
    void refusesAnUnavailableRequestedVerbWithoutRunningAnotherOne()
            throws Exception
    {
        BootstrapProfile profile = profile(
                Map.of("build", List.of("mvn package")), Map.of(), Map.of());

        assertThat(verifier.verify(root, fix(List.of("test")), profile, "root").verification())
                .satisfies(result -> {
                    assertThat(result.passed()).isFalse();
                    assertThat(result.reproducible()).isFalse();
                    assertThat(result.reason()).contains("test");
                });
        verify(shell, never()).runArgv(any(), any(), any(), anyLong(), anyInt());
    }

    @Test
    void rejectsShellSyntaxInsteadOfInterpretingIt()
    {
        assertThatThrownBy(() -> HarnessVerifier.argv(
                "mvn test && touch owned", "root", BootstrapProfile.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsafe");
    }

    @Test
    void failsWhenRegenerationIsNotIdempotent()
            throws Exception
    {
        Path generated = root.resolve("generated.txt");
        Files.writeString(generated, "once");
        when(git.statusPorcelainZ(root)).thenReturn(" M generated.txt\u0000");
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 2) {
                Files.writeString(generated, "twice");
            }
            return new ShellRunner.Result(true, 0, "ok", false, null);
        }).when(shell).runArgv(any(), any(), any(), anyLong(), anyInt());
        BootstrapProfile profile = profile(
                Map.of("regen", List.of("mvn generate-resources")), Map.of(), Map.of());

        assertThat(verifier.verify(root, fix(List.of("regen")), profile, "root"))
                .satisfies(verified -> {
                    assertThat(verified.fix().filesChanged()).containsExactly("generated.txt");
                    assertThat(verified.verification().passed()).isFalse();
                    assertThat(verified.verification().reason()).contains("not idempotent");
                    assertThat(verified.verification().commands()).hasSize(2);
                });
    }

    @Test
    void preparesPureRegenerationAndReturnsItsCompleteStableDelta()
            throws Exception
    {
        Path generated = root.resolve("generated.txt");
        when(git.statusPorcelainZ(root))
                .thenReturn("?? generated.txt\u0000");
        doAnswer(invocation -> {
            Files.writeString(generated, "stable");
            return new ShellRunner.Result(true, 0, "ok", false, null);
        }).when(shell).runArgv(any(), any(), any(), anyLong(), anyInt());
        BootstrapProfile profile = profile(
                Map.of("regen", List.of("mvn generate-resources")), Map.of(), Map.of());

        VerifiedFix verified = verifier.verify(
                root, new FixResult(
                        List.of(), "Target", List.of("regen"), "recipe:regenerate"),
                profile, "root");

        assertThat(verified.verification().passed()).isTrue();
        assertThat(verified.fix().filesChanged()).containsExactly("generated.txt");
        verify(shell, times(2)).runArgv(any(), any(), any(), anyLong(), anyInt());
    }

    @Test
    void returnsGeneratorPathsWhenPreparationFails()
            throws Exception
    {
        Path generated = root.resolve("partial.txt");
        when(git.statusPorcelainZ(root)).thenReturn("?? partial.txt\u0000");
        doAnswer(invocation -> {
            Files.writeString(generated, "partial");
            return new ShellRunner.Result(true, 1, "failed", false, null);
        }).when(shell).runArgv(any(), any(), any(), anyLong(), anyInt());
        BootstrapProfile profile = profile(
                Map.of("regen", List.of("mvn generate-resources")), Map.of(), Map.of());

        VerifiedFix verified = verifier.verify(
                root, fix(List.of("regen")), profile, "root");

        assertThat(verified.verification().passed()).isFalse();
        assertThat(verified.fix().filesChanged()).containsExactly("partial.txt");
    }

    @Test
    void rejectsAValidationCommandThatWidensThePreparedFix()
            throws Exception
    {
        Path report = root.resolve("report.txt");
        when(git.statusPorcelainZ(root))
                .thenReturn(
                        " M pom.xml\u0000",
                        " M pom.xml\u0000?? report.txt\u0000");
        doAnswer(invocation -> {
            Files.writeString(report, "unexpected output");
            return new ShellRunner.Result(true, 0, "ok", false, null);
        }).when(shell).runArgv(any(), any(), any(), anyLong(), anyInt());
        BootstrapProfile profile = profile(
                Map.of("build", List.of("mvn package")), Map.of(), Map.of());

        VerifiedFix verified = verifier.verify(
                root, fix(List.of("build")), profile, "root");

        assertThat(verified.verification().passed()).isFalse();
        assertThat(verified.verification().reason()).contains("mutated");
        assertThat(verified.fix().filesChanged())
                .containsExactly("pom.xml", "report.txt");
    }

    @Test
    void capturesBothSidesOfARename()
    {
        assertThat(HarnessVerifier.changedPaths("R  new.txt\u0000old.txt\u0000"))
                .containsExactly("new.txt", "old.txt");
    }

    private static BootstrapProfile profile(
            Map<String, List<String>> steps,
            Map<String, String> modules,
            Map<String, String> environment)
    {
        return new BootstrapProfile("github-actions", Set.of("maven"), List.of("ci.yml"), steps,
                Set.of(), Set.of(), modules, Map.of(), environment, List.of());
    }

    private static FixResult fix(List<String> verify)
    {
        return new FixResult(List.of("pom.xml"), "Target", verify, "agent");
    }
}
