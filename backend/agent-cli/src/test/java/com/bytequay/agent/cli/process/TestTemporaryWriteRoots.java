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
package com.bytequay.agent.cli.process;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TestTemporaryWriteRoots
{
    @TempDir
    Path temporary;

    @Test
    void includesHostAndConfiguredTemporaryDirectoriesWithoutGrantingInvalidPaths()
            throws Exception
    {
        var baseline = BuildCacheRoots.temporary(ImmutableMap.of());
        assertThat(baseline).contains(Path.of("/tmp").toRealPath(), Path.of(System.getProperty("java.io.tmpdir")).toRealPath()).doesNotHaveDuplicates();
        Path custom = Files.createDirectory(temporary.resolve("custom"));
        Path link = Files.createSymbolicLink(temporary.resolve("tmp-link"), custom);
        assertThat(BuildCacheRoots.temporary(ImmutableMap.of("TMPDIR", link.toString())))
                .contains(custom.toRealPath()).doesNotContain(link);
        for (String invalid : ImmutableList.of("relative/path", "invalid\0path", temporary.resolve("missing").toString(), Files.createFile(temporary.resolve("file")).toString())) {
            assertThat(BuildCacheRoots.temporary(ImmutableMap.of("TMPDIR", invalid))).isEqualTo(baseline);
        }
    }
}
