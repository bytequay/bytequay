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

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.REJECTED;
import static java.util.Objects.requireNonNull;

/** Routes a TaskTurn result by its persisted, immutable purpose. */
public final class TaskTurnResultDeliveryRouter
        implements ExecutionPorts.ResultDeliveryPort
{
    private final JdbcTemplate jdbc;
    private final Map<String, ExecutionPorts.ResultDeliveryPort> routes;

    public TaskTurnResultDeliveryRouter(
            JdbcTemplate jdbc,
            Map<String, ExecutionPorts.ResultDeliveryPort> routes)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        requireNonNull(routes, "routes is null");
        routes.forEach((purpose, port) -> {
            if (purpose == null || purpose.isBlank() || port == null) {
                throw new IllegalArgumentException(
                        "TaskTurn purpose and delivery port are required");
            }
        });
        this.routes = Map.copyOf(routes);
    }

    @Override
    public DispatchTicket.DeliveryReceipt deliver(
            DispatchTicket.OwnerReference owner,
            DispatchTicket.OperationFence expectedFence,
            DispatchTicket.DispatchResult rawResult)
            throws Exception
    {
        requireNonNull(owner, "owner is null");
        requireNonNull(expectedFence, "expectedFence is null");
        requireNonNull(rawResult, "rawResult is null");
        if (owner.kind() != DispatchTicket.OwnerKind.TASK_TURN) {
            return rejected("non-TaskTurn owner");
        }
        String purpose = jdbc.query("""
                SELECT purpose FROM task_turn
                WHERE id = ? AND operation_id = ?
                """, (rs, row) -> rs.getString("purpose"),
                owner.id(), expectedFence.operationId())
                .stream().findFirst().orElse(null);
        ExecutionPorts.ResultDeliveryPort port = purpose == null
                ? null
                : routes.get(purpose);
        if (port == null) {
            return rejected("unknown TaskTurn purpose");
        }
        return port.deliver(owner, expectedFence, rawResult);
    }

    private static DispatchTicket.DeliveryReceipt rejected(String result)
    {
        return new DispatchTicket.DeliveryReceipt(
                REJECTED,
                "{\"schema\":\"TASK_TURN_DELIVERY_ROUTER_V1\","
                        + "\"result\":\"" + result + "\"}");
    }
}
