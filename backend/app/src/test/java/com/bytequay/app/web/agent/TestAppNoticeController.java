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
package com.bytequay.app.web.agent;

import com.bytequay.app.config.WriteVersion;
import com.bytequay.app.notices.AppNoticeService;
import com.bytequay.app.service.epic.EpicNextWork;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestAppNoticeController
{
    @Test
    void answersUnchangedNoticesWithoutRebuildingThemUntilAWriteOrAPick()
            throws Exception
    {
        AppNoticeService notices = mock(AppNoticeService.class);
        EpicNextWork next = mock(EpicNextWork.class);
        WriteVersion writes = new WriteVersion();
        when(notices.alerts()).thenReturn(ImmutableList.of());
        var mvc = standaloneSetup(new AppNoticeController(notices, writes, next)).build();

        String etag = mvc.perform(get("/agent/notices")).andExpect(status().isOk()).andExpect(content().json("[]"))
                .andReturn().getResponse().getHeader("ETag");
        mvc.perform(get("/agent/notices").header("If-None-Match", etag)).andExpect(status().isNotModified());
        writes.written();
        String written = mvc.perform(get("/agent/notices").header("If-None-Match", etag)).andExpect(status().isOk())
                .andReturn().getResponse().getHeader("ETag");
        when(next.revision()).thenReturn(1L);
        mvc.perform(get("/agent/notices").header("If-None-Match", written)).andExpect(status().isOk()).andExpect(header().exists("ETag"));

        verify(notices, times(3)).alerts();
    }
}
