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

import com.bytequay.app.agenda.AgendaSchedule;
import com.bytequay.app.agenda.AgendaService;
import com.bytequay.app.agenda.AgendaSettings;
import com.bytequay.app.agenda.AgendaView;
import com.bytequay.core.domain.AgendaDay;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

@RestController
@RequestMapping("/api")
public class AgendaController
{
    private final AgendaService agenda;
    private final AgendaSettings settings;

    public AgendaController(AgendaService agenda, AgendaSettings settings)
    {
        this.agenda = requireNonNull(agenda, "agenda is null");
        this.settings = requireNonNull(settings, "settings is null");
    }

    @GetMapping("/agenda/today")
    public AgendaView today(@RequestParam(required = false) String workspaceId)
    {
        return agenda.today(Optional.ofNullable(workspaceId).filter(value -> !value.isBlank()));
    }

    @PostMapping("/agenda/today/run")
    public AgendaView makeAgain()
    {
        return agenda.makeAgain();
    }

    @PutMapping("/agenda/today/marks")
    public AgendaView mark(@RequestBody MarkRequest request)
    {
        checkArgument(request.ref() != null && !request.ref().isBlank(), "ref is required");
        return agenda.mark(request.ref(), request.mark());
    }

    /** An empty mark clears the user's own, so stored facts decide again. */
    public record MarkRequest(String ref, Optional<AgendaDay.Mark> mark) {}

    @PostMapping("/agenda/today/start")
    public AgendaView start(@RequestBody StartRequest request)
    {
        checkArgument(request.ref() != null && !request.ref().isBlank(), "ref is required");
        return agenda.start(request.ref());
    }

    public record StartRequest(String ref) {}

    @GetMapping("/settings/agenda")
    public AgendaSchedule schedule()
    {
        return settings.schedule();
    }

    @PutMapping("/settings/agenda")
    public AgendaSchedule saveSchedule(@RequestBody AgendaSchedule schedule)
    {
        checkArgument(schedule != null, "schedule is required");
        return settings.save(schedule);
    }
}
