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
package com.bytequay.app.developmentflow.stage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestMavenCompilerLogParser
{
    @Test
    void parsesEveryCompilerErrorIntoCanonicalSortedProof()
    {
        MavenCompilerLogParser.Proof proof = MavenCompilerLogParser.parse("""
                2026-08-02T01:02:03Z [ERROR] COMPILATION ERROR :
                2026-08-02T01:02:03Z [INFO] -------------------------------------------------------------
                2026-08-02T01:02:03Z [ERROR] /home/runner/work/bytequay/bytequay/backend/src/test/java/example/Zed.java:[91,7] package missing.api does not exist
                2026-08-02T01:02:03Z [ERROR] /home/runner/work/bytequay/bytequay/backend/src/main/java/example/App.java:[12,3] cannot find symbol
                2026-08-02T01:02:03Z [ERROR]   symbol:   class MissingType
                2026-08-02T01:02:03Z [ERROR]   location: class example.App
                2026-08-02T01:02:03Z [INFO] 2 errors
                2026-08-02T01:02:03Z [ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:compile
                2026-08-02T01:02:03Z [ERROR] -> [Help 1]
                """);

        assertThat(proof.source()).isEqualTo("ACTIONS_JOB_LOG_V1");
        assertThat(proof.parser()).isEqualTo("MAVEN_COMPILER_V1");
        assertThat(proof.version()).isEqualTo(1);
        assertThat(proof.complete()).isTrue();
        assertThat(proof.canonicalDiagnostics())
                .extracting(MavenCompilerLogParser.Diagnostic::file)
                .containsExactly(
                        "backend/src/main/java/example/App.java",
                        "backend/src/test/java/example/Zed.java");
        assertThat(proof.canonicalDiagnostics().getFirst())
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.kind()).isEqualTo("CANNOT_FIND_SYMBOL");
                    assertThat(diagnostic.message()).isEqualTo("cannot find symbol");
                    assertThat(diagnostic.symbol()).isEqualTo("class MissingType");
                    assertThat(diagnostic.location()).isEqualTo("class example.App");
                });
        assertThat(proof.fingerprints())
                .hasSize(2)
                .isSorted()
                .allMatch(value -> value.matches("[0-9a-f]{64}"));
    }

    @Test
    void parsesGitHubActionsTimestampedJavacContinuationsWithoutCollapsingDiagnostics()
    {
        MavenCompilerLogParser.Proof proof = MavenCompilerLogParser.parse("""
                2026-08-01T19:22:25.8607359Z [ERROR] COMPILATION ERROR :
                2026-08-01T19:22:25.8609622Z [ERROR] /home/runner/work/bytequay/bytequay/backend/src/test/java/example/TestThing.java:[22,52] cannot find symbol
                2026-08-01T19:22:25.8639483Z   symbol:   class FirstMissingType
                2026-08-01T19:22:25.8643240Z   location: package example.migration
                2026-08-01T19:22:25.8645388Z [ERROR] /home/runner/work/bytequay/bytequay/backend/src/test/java/example/TestThing.java:[23,52] cannot find symbol
                2026-08-01T19:22:25.8654049Z   symbol:   class SecondMissingType
                2026-08-01T19:22:25.8657729Z   location: package example.migration
                2026-08-01T19:22:25.8665038Z [ERROR] /home/runner/work/bytequay/bytequay/backend/src/test/java/example/TestThing.java:[67,9] method copyTo cannot be applied to given types;
                2026-08-01T19:22:25.8666719Z   required: java.nio.file.Path
                2026-08-01T19:22:25.8667829Z   found: java.nio.file.Path,java.lang.String
                2026-08-01T19:22:25.8670247Z   reason: actual and formal argument lists differ in length
                2026-08-01T19:22:25.8672000Z [ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:testCompile
                """);

        assertThat(proof.complete()).isTrue();
        assertThat(proof.canonicalDiagnostics()).hasSize(3);
        assertThat(proof.canonicalDiagnostics())
                .extracting(MavenCompilerLogParser.Diagnostic::symbol)
                .containsExactlyInAnyOrder(
                        null, "class FirstMissingType", "class SecondMissingType");
        assertThat(proof.canonicalDiagnostics())
                .filteredOn(diagnostic -> diagnostic.symbol() == null)
                .singleElement()
                .satisfies(diagnostic -> assertThat(diagnostic.message())
                        .contains("required: java.nio.file.Path")
                        .contains("found: java.nio.file.Path,java.lang.String")
                        .contains("reason: actual and formal argument lists differ in length"));
        assertThat(proof.fingerprints()).hasSize(3).isSorted();
    }

    @Test
    void fingerprintsIgnoreDiagnosticOrderLineColumnsAndWorkspacePrefix()
    {
        MavenCompilerLogParser.Proof runner = MavenCompilerLogParser.parse("""
                [ERROR] COMPILATION ERROR :
                [ERROR] /home/runner/work/bytequay/bytequay/backend/src/test/java/example/Zed.java:[91,7] package missing.api does not exist
                [ERROR] /home/runner/work/bytequay/bytequay/backend/src/main/java/example/App.java:[12,3] cannot find symbol
                [ERROR] symbol: class MissingType
                [ERROR] location: class example.App
                [INFO] 2 errors
                """);
        MavenCompilerLogParser.Proof local = MavenCompilerLogParser.parse("""
                09:15:30 [ERROR] COMPILATION ERROR:
                09:15:30 [ERROR] /Users/dev/project/bytequay/backend/src/main/java/example/App.java:[400,22] cannot find symbol
                09:15:30 [ERROR] symbol: class MissingType
                09:15:30 [ERROR] location: class example.App
                09:15:30 [ERROR] /Users/dev/project/bytequay/backend/src/test/java/example/Zed.java:[2,1] package missing.api does not exist
                09:15:30 [INFO] 2 errors
                """);

        assertThat(runner.complete()).isTrue();
        assertThat(local.complete()).isTrue();
        assertThat(local.canonicalDiagnostics())
                .isEqualTo(runner.canonicalDiagnostics());
        assertThat(local.fingerprints()).isEqualTo(runner.fingerprints());
    }

    @Test
    void partialOrUnrecognizedCompilerOutputHasNoAuthoritativeFingerprint()
    {
        MavenCompilerLogParser.Proof partial = MavenCompilerLogParser.parse("""
                [ERROR] COMPILATION ERROR :
                [ERROR] warnings found and -Werror specified
                [ERROR] /home/runner/work/bytequay/bytequay/backend/src/main/java/example/App.java:[12,3] cannot find symbol
                [INFO] 2 errors
                """);
        MavenCompilerLogParser.Proof unrecognized =
                MavenCompilerLogParser.parse("""
                        [ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:compile
                        [ERROR] -> [Help 1]
                        """);

        assertThat(partial.complete()).isFalse();
        assertThat(partial.canonicalDiagnostics()).hasSize(1);
        assertThat(partial.fingerprints()).isEmpty();
        assertThat(unrecognized.complete()).isFalse();
        assertThat(unrecognized.canonicalDiagnostics()).isEmpty();
        assertThat(unrecognized.fingerprints()).isEmpty();
    }
}
