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

import java.util.Map;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.REJECTED;
import static java.util.Objects.requireNonNull;

/** Routes one exact callback name to its domain-owned delivery boundary. */
public final class ResultDeliveryRouter
        implements ExecutionPorts.ResultDeliveryPort
{
    private final Map<String, ExecutionPorts.ResultDeliveryPort> routes;

    public ResultDeliveryRouter(
            Map<String, ExecutionPorts.ResultDeliveryPort> routes)
    {
        requireNonNull(routes, "routes is null");
        routes.forEach((route, port) -> {
            if (route == null || route.isBlank() || port == null) {
                throw new IllegalArgumentException(
                        "Result delivery route and port are required");
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
        ExecutionPorts.ResultDeliveryPort port = routes.get(
                owner.callbackRoute());
        if (port == null) {
            return new DispatchTicket.DeliveryReceipt(
                    REJECTED,
                    "{\"schema\":\"RESULT_DELIVERY_ROUTER_V1\","
                            + "\"result\":\"unknown callback route\"}");
        }
        return port.deliver(owner, expectedFence, rawResult);
    }
}
