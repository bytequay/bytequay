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
import com.bytequay.app.notices.AppNotice;
import com.bytequay.app.notices.AppNoticeService;
import com.bytequay.app.service.epic.EpicNextWork;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

import java.util.List;

import static java.util.Objects.requireNonNull;

@RestController
public class AppNoticeController
{
    private final AppNoticeService notices;
    private final WriteVersion writes;
    private final EpicNextWork next;

    public AppNoticeController(AppNoticeService notices, WriteVersion writes, EpicNextWork next)
    {
        this.notices = requireNonNull(notices, "notices is null");
        this.writes = requireNonNull(writes, "writes is null");
        this.next = requireNonNull(next, "next is null");
    }

    @GetMapping("/agent/notices")
    public List<AppNotice> current(WebRequest request)
    {
        // Polled every five seconds; a rebuild reads every recent task's turns.
        if (request.checkNotModified(writes.current() + "-" + next.revision())) {
            return null;
        }
        return notices.current();
    }
}
