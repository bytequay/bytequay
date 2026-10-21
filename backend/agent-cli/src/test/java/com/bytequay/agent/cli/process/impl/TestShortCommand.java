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
package com.bytequay.agent.cli.process.impl;

import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class TestShortCommand
{
    @Test
    void shortCommandsKeepOutputAndFailClosedOnExitOrTimeout()
    {
        assertThat(ShortCommand.run(ImmutableList.of("/bin/sh", "-c", "printf ' value\\n'"), Duration.ofSeconds(5))).contains(" value\n");
        assertThat(ShortCommand.run(ImmutableList.of("/bin/sh", "-c", "exit 2"), Duration.ofSeconds(5))).isEmpty();
        assertThat(ShortCommand.run(ImmutableList.of("/bin/sleep", "10"), Duration.ofMillis(20))).isEmpty();
    }

    @Test
    void shortCommandsPreserveInterruption()
    {
        Thread.currentThread().interrupt();
        try {
            assertThat(ShortCommand.run(ImmutableList.of("/bin/sleep", "10"), Duration.ofSeconds(5))).isEmpty();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        }
        finally {
            Thread.interrupted();
        }
    }
}
