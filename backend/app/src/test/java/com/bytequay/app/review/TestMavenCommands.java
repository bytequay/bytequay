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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestMavenCommands
{
    @Test
    void unwrapsCodexShellScriptsAndReadsALoneMavenSelector()
    {
        assertThat(MavenCommands.detect(ImmutableList.of("/bin/zsh -lc './mvnw -B -pl core test -Dtest=com.example.TestAccess#testBuiltin'")))
                .contains(new Maven("./mvnw -B -pl core test -Dtest=com.example.TestAccess#testBuiltin", true, Optional.of("com.example.TestAccess"), Optional.of("testBuiltin")));
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
        assertThat(MavenCommands.detect(ImmutableList.of("./mvnw test > build.log 2>&1")).orElseThrow().lone()).isTrue();
        assertThat(MavenCommands.detect(ImmutableList.of("git status; mvn -q\ttest")).orElseThrow().lone()).isFalse();
        assertThat(MavenCommands.detect(ImmutableList.of("time ./mvnw test")).orElseThrow().lone()).isTrue();
    }

    @Test
    void aScriptThatSavesMavensStatusAndExitsWithItKeepsItsExit()
    {
        String script = """
                JAVA_HOME=/jdk ./mvnw -B -pl core -Dtest=TestAccess#testRead test > build.log 2>&1
                review_exit=$?
                tail -n 28 build.log
                exit "$review_exit"
                """;
        assertThat(MavenCommands.detect(ImmutableList.of(script)).orElseThrow())
                .isEqualTo(new Maven(script.strip(), true, Optional.of("TestAccess"), Optional.of("testRead")));
        assertThat(MavenCommands.detect(ImmutableList.of("/bin/zsh -lc 'cd core; ./mvnw test > log; status=$?; tail log; exit ${status}'")).orElseThrow().lone()).isTrue();

        // The saved status is another command's, or the script may end on another one.
        assertThat(MavenCommands.detect(ImmutableList.of("./mvnw test | tee log; status=$?; exit $status")).orElseThrow().lone()).isFalse();
        assertThat(MavenCommands.detect(ImmutableList.of("cd core && ./mvnw test; status=$?; exit $status")).orElseThrow().lone()).isFalse();
        assertThat(MavenCommands.detect(ImmutableList.of("./mvnw test; status=$?; tail log && exit $status")).orElseThrow().lone()).isFalse();
        assertThat(MavenCommands.detect(ImmutableList.of("./mvnw test; status=$?; tail log; exit $other")).orElseThrow().lone()).isFalse();
        assertThat(MavenCommands.detect(ImmutableList.of("./mvnw test; tail log; exit $?")).orElseThrow().lone()).isFalse();
    }

    @Test
    void anythingButOneClassSelectorIsACommand()
    {
        assertThat(MavenCommands.detect(ImmutableList.of("./mvnw test -Dtest=TestA,TestB")).orElseThrow().testClass()).isEmpty();
        assertThat(MavenCommands.detect(ImmutableList.of("./mvnw test -Dtest='Test*'")).orElseThrow().testClass()).isEmpty();
        assertThat(MavenCommands.detect(ImmutableList.of("./mvnw test -Dtest=TestA && ./mvnw test -Dtest=TestB")).orElseThrow().testClass()).isEmpty();
    }

    @Test
    void aLoneCommandIsItsWordsOnlyWithoutSeparatorsPipesOrRedirects()
    {
        List<String> words = ImmutableList.of("./mvnw", "-pl", "core", "test", "-Dtest=TestAccess");
        assertThat(MavenCommands.loneCommand(ImmutableList.of("/bin/zsh -lc './mvnw -pl core test -Dtest=TestAccess'"))).contains(words);
        assertThat(MavenCommands.loneCommand(ImmutableList.of("./mvnw  -pl core test \"-Dtest=TestAccess\""))).contains(words);
        assertThat(MavenCommands.loneCommand(ImmutableList.of("pytest", "tests/test_spec.py"))).contains(ImmutableList.of("pytest", "tests/test_spec.py"));
        assertThat(MavenCommands.loneCommand(ImmutableList.of("./mvnw test -Dtest=TestAccess | tail -40"))).isEmpty();
        assertThat(MavenCommands.loneCommand(ImmutableList.of("cd core && ../mvnw test"))).isEmpty();
        assertThat(MavenCommands.loneCommand(ImmutableList.of("/bin/zsh -lc './mvnw test > build.log'"))).isEmpty();
        assertThat(MavenCommands.loneCommand(ImmutableList.of(" "))).isEmpty();
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

    @Test
    void aTypedCommandLosesCodexsShellWrapperAndExtraWhitespace()
    {
        assertThat(MavenCommands.typedCommand(ImmutableList.of("/bin/zsh -lc 'cd core && ./mvnw  test'"))).isEqualTo("cd core && ./mvnw test");
        assertThat(MavenCommands.typedCommand(ImmutableList.of("./mvnw -pl core test\n"))).isEqualTo("./mvnw -pl core test");
    }
}
