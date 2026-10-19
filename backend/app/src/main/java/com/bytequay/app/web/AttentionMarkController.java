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

import com.bytequay.app.service.attention.AttentionMarkService;
import com.bytequay.core.domain.AttentionMark;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

@RestController
@RequestMapping("/api/attention/marks")
public class AttentionMarkController
{
    private final AttentionMarkService marks;

    public AttentionMarkController(AttentionMarkService marks)
    {
        this.marks = requireNonNull(marks, "marks is null");
    }

    @GetMapping
    public List<AttentionMark> marks()
    {
        return marks.marks();
    }

    /** The marks a list added or changed, and the keys of the ones it cleared, applied together. */
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void change(@RequestBody Changes request)
    {
        checkArgument(request != null && request.saved() != null && request.removed() != null, "saved and removed are required");
        checkArgument(!request.saved().contains(null), "saved holds an empty mark");
        request.removed().forEach(key -> checkText(key, "removed", 500));
        marks.change(request.saved(), request.removed());
    }

    public record Changes(List<AttentionMark> saved, List<String> removed) {}
}
