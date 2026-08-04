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
package com.bytequay.app.service.workspaces;

import com.bytequay.app.service.local.ShellRunner;
import com.bytequay.app.service.workspaces.CherryPickCompileGate.Outcome;
import com.bytequay.app.service.workspaces.CherryPickCompileGate.Resolution;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestCherryPickCompileGate
{
    private static final Set<String> MODULES = Set.of("module-a", "module-b");

    @Test
    void anExplicitScriptWinsOverALearnedJobAndTheDefault()
    {
        Resolution resolved = CherryPickCompileGate.resolve(
                "mvn -B compile", "mvn verify", null, MODULES);

        assertThat(resolved.source()).isEqualTo("script");
        assertThat(resolved.argv()).containsExactly("mvn", "-B", "compile");
    }

    @Test
    void aLearnedCiJobScriptWinsOverTheDefault()
    {
        Resolution resolved = CherryPickCompileGate.resolve(
                "  ", "mvn -B verify -DskipTests", null, MODULES);

        assertThat(resolved.source()).isEqualTo("ci-job");
        assertThat(resolved.argv()).containsExactly("mvn", "-B", "verify", "-DskipTests");
    }

    @Test
    void withNeitherItFallsBackToAPlainCompile()
    {
        Resolution resolved = CherryPickCompileGate.resolve(null, null, null, MODULES);

        assertThat(resolved.source()).isEqualTo("default");
        assertThat(resolved.argv())
                .containsExactly("./mvnw", "clean", "install", "-DskipTests");
    }

    @Test
    void aKnownModuleScopesTheBuildButAnUnknownOrPreScopedOneDoesNot()
    {
        assertThat(CherryPickCompileGate.resolve(null, null, "module-a", MODULES).argv())
                .containsExactly("./mvnw", "-pl", "module-a", "-am", "clean", "install", "-DskipTests");

        // A module the build does not know would make -pl fail outright.
        assertThat(CherryPickCompileGate.resolve(null, null, "not-a-module", MODULES).argv())
                .doesNotContain("-pl");
        assertThat(CherryPickCompileGate.resolve(null, null, "root", MODULES).argv())
                .doesNotContain("-pl");

        // Never scope twice.
        assertThat(CherryPickCompileGate.resolve(
                "mvn -pl module-b compile", null, "module-a", MODULES).argv())
                .containsExactly("mvn", "-pl", "module-b", "compile");
    }

    @Test
    void aScriptThatIsNotAPlainBuildInvocationIsRejected()
    {
        assertThatThrownBy(() -> CherryPickCompileGate.validateScript("mvn compile && rm -rf /"))
                .hasMessageContaining("plain mvn");
        assertThatThrownBy(() -> CherryPickCompileGate.validateScript("bash -c 'mvn compile'"))
                .hasMessageContaining("plain mvn");
        assertThatThrownBy(() -> CherryPickCompileGate.validateScript("mvn compile > /tmp/out"))
                .hasMessageContaining("plain mvn");
        // A blank script simply means "not provided".
        CherryPickCompileGate.validateScript("  ");
        CherryPickCompileGate.validateScript(null);
    }

    @Test
    void aMissingToolchainIsNotReportedAsAFailedCompile()
            throws Exception
    {
        ShellRunner shell = mock(ShellRunner.class);
        when(shell.runArgv(any(), any(), any(), anyLong(), anyInt()))
                .thenReturn(ShellRunner.Result.refused("spawn failed: mvnw not found"));

        Outcome outcome = new CherryPickCompileGate(shell)
                .run(Path.of("/tmp"), CherryPickCompileGate.resolve(null, null, null, MODULES));

        // Not reproduced: the agent must not be sent to fix a defect that is not
        // in the code, and the job must escalate instead.
        assertThat(outcome.compiled()).isFalse();
        assertThat(outcome.reproduced()).isFalse();
        assertThat(outcome.outputTail()).contains("mvnw not found");
    }

    @Test
    void aNonZeroExitIsARedGateAndZeroIsGreen()
            throws Exception
    {
        ShellRunner shell = mock(ShellRunner.class);
        when(shell.runArgv(any(), any(), any(), anyLong(), anyInt()))
                .thenReturn(new ShellRunner.Result(true, 1, "COMPILATION ERROR", false, null))
                .thenReturn(new ShellRunner.Result(true, 0, "BUILD SUCCESS", false, null));
        CherryPickCompileGate gate = new CherryPickCompileGate(shell);
        Resolution resolution = CherryPickCompileGate.resolve(null, null, null, MODULES);

        Outcome red = gate.run(Path.of("/tmp"), resolution);
        assertThat(red.compiled()).isFalse();
        assertThat(red.reproduced()).isTrue();
        assertThat(red.outputTail()).contains("COMPILATION ERROR");

        assertThat(gate.run(Path.of("/tmp"), resolution).compiled()).isTrue();
    }

    @Test
    void aTimeoutIsRedRatherThanQuietlyGreen()
            throws Exception
    {
        ShellRunner shell = mock(ShellRunner.class);
        when(shell.runArgv(any(), any(), any(), anyLong(), anyInt()))
                .thenReturn(new ShellRunner.Result(true, 0, "", false, "timed out after 900s"));

        Outcome outcome = new CherryPickCompileGate(shell)
                .run(Path.of("/tmp"), CherryPickCompileGate.resolve(null, null, null, MODULES));

        assertThat(outcome.compiled()).isFalse();
    }

    @Test
    void theEnvironmentMarksTheRunAsCi()
            throws Exception
    {
        ShellRunner shell = mock(ShellRunner.class);
        when(shell.runArgv(any(), any(), any(), anyLong(), anyInt()))
                .thenReturn(new ShellRunner.Result(true, 0, "ok", false, null));

        new CherryPickCompileGate(shell)
                .run(Path.of("/tmp"), CherryPickCompileGate.resolve(null, null, null, MODULES));

        ArgumentCaptor<Map<String, String>> env = ArgumentCaptor.forClass(Map.class);
        verify(shell).runArgv(any(), any(), env.capture(), anyLong(), anyInt());
        assertThat(env.getValue()).containsEntry("CI", "true");
    }

    @Test
    void theDefaultCommandSkipsTestsBecauseTheGateIsCompilationOnly()
    {
        List<String> argv = CherryPickCompileGate.resolve(null, null, null, MODULES).argv();

        assertThat(argv).contains("-DskipTests");
    }
}
