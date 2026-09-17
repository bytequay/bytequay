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

import com.bytequay.app.review.MavenCommands.Maven;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestMavenCommands
{
    @Test
    void unwrapsCodexShellScriptsAndReadsALoneMavenSelector()
    {
        assertThat(MavenCommands.detect(ImmutableList.of("/bin/zsh -lc './mvnw -B -pl core test -Dtest=io.trino.TestAccess#testBuiltin'")))
                .contains(new Maven("./mvnw -B -pl core test -Dtest=io.trino.TestAccess#testBuiltin", true, Optional.of("io.trino.TestAccess"), Optional.of("testBuiltin")));
        assertThat(MavenCommands.detect(ImmutableList.of("bash -c \"mvn test \\\"-Dtest=TestAccess\\\"\"")))
                .contains(new Maven("mvn test \"-Dtest=TestAccess\"", true, Optional.of("TestAccess"), Optional.empty()));
        assertThat(MavenCommands.detect(ImmutableList.of("MAVEN_OPTS='-Xmx2g' ./mvnw verify")))
                .contains(new Maven("MAVEN_OPTS='-Xmx2g' ./mvnw verify", true, Optional.empty(), Optional.empty()));
    }

    @Test
    void findsMavenInAnySegmentButOnlyALoneInvocationKeepsItsExit()
    {
        // Claude sends the bare script.
        assertThat(MavenCommands.detect(ImmutableList.of("cd core && ../mvnw test -Dtest=TestAccess | tail -40")))
                .contains(new Maven("cd core && ../mvnw test -Dtest=TestAccess | tail -40", false, Optional.of("TestAccess"), Optional.empty()));
        assertThat(MavenCommands.detect(ImmutableList.of("./mvnw test > build.log 2>&1")).orElseThrow().lone()).isFalse();
        assertThat(MavenCommands.detect(ImmutableList.of("git status; mvn -q\ttest")).orElseThrow().lone()).isFalse();
        assertThat(MavenCommands.detect(ImmutableList.of("time ./mvnw test")).orElseThrow().lone()).isTrue();
    }

    @Test
    void anythingButOneClassSelectorIsACommand()
    {
        assertThat(MavenCommands.detect(ImmutableList.of("./mvnw test -Dtest=TestA,TestB")).orElseThrow().testClass()).isEmpty();
        assertThat(MavenCommands.detect(ImmutableList.of("./mvnw test -Dtest='Test*'")).orElseThrow().testClass()).isEmpty();
        assertThat(MavenCommands.detect(ImmutableList.of("./mvnw test -Dtest=TestA && ./mvnw test -Dtest=TestB")).orElseThrow().testClass()).isEmpty();
    }

    @Test
    void ignoresCommandsThatDoNotRunMaven()
    {
        assertThat(MavenCommands.detect(ImmutableList.of("/bin/zsh -lc 'rg mvnw pom.xml'"))).isEmpty();
        assertThat(MavenCommands.detect(ImmutableList.of("cat mvnw"))).isEmpty();
        assertThat(MavenCommands.detect(ImmutableList.of("echo 'mvn test'"))).isEmpty();
        assertThat(MavenCommands.detect(ImmutableList.of("ls # && mvn test"))).isEmpty();
        assertThat(MavenCommands.detect(ImmutableList.of())).isEmpty();
    }
}
