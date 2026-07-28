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
package com.bytequay.app.developmentflow.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.ACCEPTED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.REJECTED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.SUCCEEDED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.OwnerKind.TASK_TURN;
import static org.assertj.core.api.Assertions.assertThat;

class TestTaskTurnResultDeliveryRouter
{
    @TempDir
    Path tempDir;

    @Test
    void routesByPersistedPurposeAndRejectsUnknownOwners()
            throws Exception
    {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("turn-router.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE task_turn (
                    id TEXT PRIMARY KEY,
                    operation_id TEXT NOT NULL UNIQUE,
                    purpose TEXT NOT NULL)
                """);
        jdbc.update("""
                INSERT INTO task_turn(id, operation_id, purpose)
                VALUES ('turn-1', 'operation-1', 'PLAN_DRAFT')
                """);
        AtomicInteger deliveries = new AtomicInteger();
        ExecutionPorts.ResultDeliveryPort plan = (owner, fence, result) -> {
            deliveries.incrementAndGet();
            return new DispatchTicket.DeliveryReceipt(ACCEPTED, "plan");
        };
        TaskTurnResultDeliveryRouter router = new TaskTurnResultDeliveryRouter(
                jdbc, Map.of("PLAN_DRAFT", plan));
        DispatchTicket.OperationFence fence = new DispatchTicket.OperationFence(
                1L, "stage-1", 1L, "operation-1", 1,
                "code", "head", "base");
        DispatchTicket.DispatchResult result = new DispatchTicket.DispatchResult(
                fence, SUCCEEDED, "{}", "{}", null);

        assertThat(router.deliver(
                new DispatchTicket.OwnerReference(
                        TASK_TURN, "turn-1", "TASK_TURN_RESULT"),
                fence, result).acceptance())
                .isEqualTo(ACCEPTED);
        assertThat(deliveries).hasValue(1);

        assertThat(router.deliver(
                new DispatchTicket.OwnerReference(
                        TASK_TURN, "unknown", "TASK_TURN_RESULT"),
                fence, result).acceptance())
                .isEqualTo(REJECTED);
        assertThat(deliveries).hasValue(1);
    }
}
