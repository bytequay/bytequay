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
package com.bytequay.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TestLauncherExitWatch
{
    @TempDir
    private Path directory;

    @Test
    void exitsOnlyAfterTheLauncherIsGone()
            throws Exception
    {
        Process launcher = new ProcessBuilder("sleep", "30").start();
        CountDownLatch exited = new CountDownLatch(1);

        try {
            LauncherExitWatch.exitWith(launcher.pid(), exited::countDown);
            assertThat(exited.await(200, TimeUnit.MILLISECONDS)).isFalse();
            launcher.destroyForcibly();
            assertThat(exited.await(10, TimeUnit.SECONDS)).isTrue();
        }
        finally {
            launcher.destroyForcibly();
        }
    }

    @Test
    void exitsBeforeOpeningTheDataDirectoryWhenTheLauncherAlreadyExited()
            throws Exception
    {
        Process launcher = new ProcessBuilder("true").start();
        assertThat(launcher.waitFor(10, TimeUnit.SECONDS)).isTrue();
        Path output = directory.resolve("backend.log");
        Process backend = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dbytequay.launcher-pid=" + launcher.pid(), "-Duser.home=" + directory,
                "-cp", System.getProperty("java.class.path"), ByteQuayApplication.class.getName())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(backend.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(backend.exitValue()).isZero();
            assertThat(directory.resolve("Library")).doesNotExist();
        }
        finally {
            backend.destroyForcibly();
        }
    }
}
