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

import com.bytequay.app.domain.TaskGroup;
import com.bytequay.app.service.tasks.TaskService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * REST surface for the rail's Groups section: list, create, delete.
 * Renaming / reordering land in a follow-up once the create flow is
 * proven out.
 */
@RestController
@RequestMapping("/api/task-groups")
public class TaskGroupController
{
    private final TaskService tasks;

    public TaskGroupController(TaskService tasks)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
    }

    @GetMapping
    public List<TaskGroup> list()
    {
        return tasks.listGroups();
    }

    @PostMapping
    public TaskGroup create(@RequestBody NewGroupBody body)
    {
        requireNonNull(body, "body is null");
        if (body.name() == null || body.name().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        return tasks.createGroup(new TaskService.NewGroupRequest(
                body.name(),
                body.glyph(),
                body.color(),
                body.sortOrder()));
    }

    @PatchMapping("/{id}")
    public TaskGroup update(@PathVariable String id, @RequestBody PatchGroupBody body)
    {
        requireNonNull(body, "body is required");
        return tasks.updateGroup(id, new TaskService.GroupPatch(
                body.name(), body.glyph(), body.color()));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id)
    {
        tasks.deleteGroup(id);
    }

    public record NewGroupBody(String name, String glyph, String color, int sortOrder) {}

    public record PatchGroupBody(String name, String glyph, String color) {}
}
