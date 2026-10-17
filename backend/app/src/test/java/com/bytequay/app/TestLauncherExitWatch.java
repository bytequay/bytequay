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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TestLauncherExitWatch
{
    @Test
    void exitsOnlyAfterTheLauncherIsGone()
            throws Exception
    {
        Process launcher = new ProcessBuilder("sleep", "30").start();
        CountDownLatch exited = new CountDownLatch(1);

        LauncherExitWatch.exitWith(launcher.toHandle(), exited::countDown);
        assertThat(exited.await(200, TimeUnit.MILLISECONDS)).isFalse();

        launcher.destroyForcibly();
        assertThat(exited.await(10, TimeUnit.SECONDS)).isTrue();
    }
}
