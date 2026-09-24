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
package com.bytequay.app.context;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestContextUsageController
{
    private final ContextUsageService usage = mock(ContextUsageService.class);
    private final MockMvc mvc = standaloneSetup(new ContextUsageController(usage)).build();

    @Test
    void noteIdsWithSlashesStayWholeInTheActivityQuery()
            throws Exception
    {
        mvc.perform(get("/api/workspaces/workspace/repository-context/usage/activities")
                        .param("entryId", "knowledge:path:plugin/trino-iceberg/")
                        .param("window", "all")
                        .param("page", "2"))
                .andExpect(status().isOk());

        verify(usage).activities("workspace", "knowledge:path:plugin/trino-iceberg/", "all", 2);
    }

    @Test
    void overviewDefaultsToThirtyDays()
            throws Exception
    {
        mvc.perform(get("/api/workspaces/workspace/repository-context/usage"))
                .andExpect(status().isOk());

        verify(usage).overview("workspace", "30d", 0);
    }
}
