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
package com.bytequay.app.web;

import com.bytequay.app.developmentflow.compatibility.V2BranchGuardProjection;
import com.bytequay.app.developmentflow.compatibility.V2ControlRouteStore;
import com.bytequay.app.developmentflow.task.V2BranchSyncPolicyManager;
import com.bytequay.app.domain.BranchGuard;
import com.bytequay.app.service.review.BranchGuardService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestBranchGuardControlRouting
{
    @Test
    void keepsLegacyGuardReadableButRejectsMutation()
    {
        BranchGuardService legacy = mock(BranchGuardService.class);
        V2ControlRouteStore routes = mock(V2ControlRouteStore.class);
        V2BranchSyncPolicyManager policies = mock(
                V2BranchSyncPolicyManager.class);
        V2BranchGuardProjection projection = mock(
                V2BranchGuardProjection.class);
        BranchGuard expected = BranchGuard.disabled("legacy-task");
        when(legacy.get("legacy-task")).thenReturn(expected);
        BranchGuardController controller = controller(
                legacy, routes, policies, projection);

        assertThat(controller.guard("legacy-task")).isEqualTo(expected);
        assertThatThrownBy(() -> controller.updateGuard(
                "legacy-task",
                new BranchGuardController.GuardPatch(true, "nightly")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("read-only");
        verify(legacy).get("legacy-task");
        verify(legacy, never()).update("legacy-task", true, "nightly");
        verifyNoInteractions(policies, projection);
    }

    @Test
    void routesV2TaskOnlyToTheTypedPolicyAndProjection()
    {
        BranchGuardService legacy = mock(BranchGuardService.class);
        V2ControlRouteStore routes = mock(V2ControlRouteStore.class);
        V2BranchSyncPolicyManager policies = mock(
                V2BranchSyncPolicyManager.class);
        V2BranchGuardProjection projection = mock(
                V2BranchGuardProjection.class);
        BranchGuard disabled = BranchGuard.disabled("v2-task");
        BranchGuard enabled = disabled.withEnabled(true);
        when(routes.isV2Task("v2-task")).thenReturn(true);
        when(projection.project("v2-task")).thenReturn(disabled, enabled);

        BranchGuardController controller = controller(
                legacy, routes, policies, projection);

        assertThat(controller.guard("v2-task")).isEqualTo(disabled);
        assertThat(controller.updateGuard(
                "v2-task",
                new BranchGuardController.GuardPatch(true, "nightly")))
                .isEqualTo(enabled);
        verify(policies).update("v2-task", true, "nightly");
        verify(legacy, never()).get("v2-task");
        verify(legacy, never()).update("v2-task", true, "nightly");
    }

    private static BranchGuardController controller(
            BranchGuardService legacy,
            V2ControlRouteStore routes,
            V2BranchSyncPolicyManager policies,
            V2BranchGuardProjection projection)
    {
        BranchGuardController controller = new BranchGuardController(legacy);
        controller.setV2Controls(routes, policies, projection);
        return controller;
    }
}
