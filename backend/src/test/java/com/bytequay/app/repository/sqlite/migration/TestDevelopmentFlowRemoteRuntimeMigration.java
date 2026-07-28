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
package com.bytequay.app.repository.sqlite.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;

import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.connect;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertCiPolicy;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.insertRemoteOwner;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.migrate;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedPublishedRemoteTask;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedWorkspaceAndTrunk;
import static org.assertj.core.api.Assertions.assertThat;

class TestDevelopmentFlowRemoteRuntimeMigration
{
    @TempDir
    private Path tempDir;

    @Test
    void upgradesRemoteProtocolAndKeepsTheCurrentCodeSubjectReadable()
            throws Exception
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("remote-runtime.db");
        migrate(url, "228");
        try (Connection connection = connect(url)) {
            seedWorkspaceAndTrunk(connection);
            seedPublishedRemoteTask(connection, 1);
        }
        migrate(url, "232");
        try (Connection connection = connect(url)) {
            insertRemoteOwner(connection, 1);
            insertCiPolicy(connection, 1);
        }
        migrate(url, "243");
        try (Connection connection = connect(url)) {
            try (var statement = connection.createStatement();
                    var result = statement.executeQuery("""
                            SELECT code_fingerprint, head_sha, base_sha
                            FROM task_current_code_subject_v230
                            WHERE task_id = 'task-1'
                            """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("code_fingerprint"))
                        .isEqualTo("fingerprint-1");
                assertThat(result.getString("head_sha")).isEqualTo("head-1");
                assertThat(result.getString("base_sha")).isEqualTo("base-1");
            }
        }
    }
}
