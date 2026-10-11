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

import com.bytequay.app.notices.AppNotice;
import com.bytequay.app.notices.AppNoticeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static java.util.Objects.requireNonNull;

@RestController
public class AppNoticeController
{
    private final AppNoticeService notices;

    public AppNoticeController(AppNoticeService notices)
    {
        this.notices = requireNonNull(notices, "notices is null");
    }

    @GetMapping("/agent/notices")
    public List<AppNotice> current()
    {
        return notices.current();
    }
}
