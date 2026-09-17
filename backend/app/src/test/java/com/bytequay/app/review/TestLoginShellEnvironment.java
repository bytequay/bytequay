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
package com.bytequay.app.review;

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestLoginShellEnvironment
{
    @TempDir
    private Path directory;

    @Test
    void readsTheLoginShellOnceWithoutByteQuayVariables()
            throws IOException
    {
        Path probes = directory.resolve("probes");
        // Reports the environment the probe received, as a login shell's env -0 would, after startup-file noise.
        Path shell = script("shell", """
                #!/bin/sh
                echo probe >> '%s'
                printf 'Welcome back\\n'
                export FROM_PROFILE=yes
                export BYTEQUAY_FROM_PROFILE=leak
                exec /usr/bin/env -0
                """.formatted(probes));
        LoginShellEnvironment environment = new LoginShellEnvironment(
                ImmutableMap.of("SHELL", shell.toString(), "PATH", "/usr/bin:/bin", "BYTEQUAY_SESSION_TOKEN", "secret", "HOME", directory.toString()),
                directory.resolve("no-java-home"));

        Map<String, String> login = environment.get();

        assertThat(login).containsEntry("FROM_PROFILE", "yes")
                .containsEntry("LANG", "en_US.UTF-8")
                .containsEntry("PATH", "/usr/bin:/bin")
                .containsEntry("HOME", directory.toString());
        assertThat(login.keySet()).noneMatch(name -> name.startsWith("BYTEQUAY_"));
        assertThat(environment.get()).isSameAs(login);
        assertThat(Files.readAllLines(probes)).hasSize(1);
    }

    @Test
    void doesNotCacheAFailedProbe()
            throws IOException
    {
        Path probes = directory.resolve("probes");
        Path shell = script("shell", """
                #!/bin/sh
                echo probe >> '%s'
                exit 1
                """.formatted(probes));
        LoginShellEnvironment environment = new LoginShellEnvironment(ImmutableMap.of("SHELL", shell.toString()), directory.resolve("no-java-home"));

        for (int attempt = 1; attempt <= 2; attempt++) {
            assertThatThrownBy(environment::get)
                    .isInstanceOfSatisfying(LocalRunRefusedException.class, refusal -> assertThat(refusal.openTerminal()).isTrue())
                    .hasMessage("Your login shell environment could not be read");
        }
        assertThat(Files.readAllLines(probes)).hasSize(2);
    }

    @Test
    void readsAShellWhoseBackgroundJobKeepsItsOutputOpenAndRereadsAfterForget()
            throws IOException
    {
        Path probes = directory.resolve("probes");
        // The double fork leaves a job that is not a descendant and still holds the shell's standard output.
        Path shell = script("shell", """
                #!/bin/sh
                echo probe >> '%s'
                (sleep 30 &)
                exec /usr/bin/env -0
                """.formatted(probes));
        LoginShellEnvironment environment = new LoginShellEnvironment(ImmutableMap.of("SHELL", shell.toString(), "PATH", "/usr/bin:/bin"), directory.resolve("no-java-home"));

        long started = System.nanoTime();
        assertThat(environment.get()).containsEntry("PATH", "/usr/bin:/bin");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));

        environment.get();
        environment.forget();
        environment.get();
        assertThat(Files.readAllLines(probes)).hasSize(2);
    }

    @Test
    void findsJavaWithoutCountingTheSystemStub()
            throws IOException
    {
        Path jdk = directory.resolve("jdk");
        script("jdk/bin/java", "#!/bin/sh\n");
        Path stubLink = Files.createDirectories(directory.resolve("stub"));
        Files.createSymbolicLink(stubLink.resolve("java"), Path.of("/usr/bin/java"));
        Path missingTool = directory.resolve("no-java-home");
        LoginShellEnvironment environment = new LoginShellEnvironment(ImmutableMap.of(), missingTool);

        assertThat(environment.hasJava(ImmutableMap.of("JAVA_HOME", jdk.toString()))).isTrue();
        assertThat(environment.hasJava(ImmutableMap.of("JAVA_HOME", directory.resolve("gone").toString(), "PATH", stubLink + ":" + jdk.resolve("bin")))).isTrue();
        assertThat(environment.hasJava(ImmutableMap.of("PATH", stubLink + ":/usr/bin"))).isFalse();
        assertThat(new LoginShellEnvironment(ImmutableMap.of(), script("java_home", "#!/bin/sh\nexit 0\n")).hasJava(ImmutableMap.of())).isTrue();
        assertThat(new LoginShellEnvironment(ImmutableMap.of(), script("failing_java_home", "#!/bin/sh\nexit 1\n")).hasJava(ImmutableMap.of())).isFalse();
    }

    private Path script(String name, String content)
            throws IOException
    {
        Path file = directory.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
        return file;
    }
}
