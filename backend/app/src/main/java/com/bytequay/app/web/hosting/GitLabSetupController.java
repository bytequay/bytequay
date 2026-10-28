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
package com.bytequay.app.web.hosting;

import com.bytequay.app.service.gitlab.GitLabCliStatus;
import com.bytequay.app.service.gitlab.GlabCliService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static java.util.Objects.requireNonNull;

@RestController
@RequestMapping("/api/hosting/gitlab/cli")
public class GitLabSetupController
{
    private final GlabCliService cli;

    public GitLabSetupController(GlabCliService cli)
    {
        this.cli = requireNonNull(cli, "cli is null");
    }

    @GetMapping
    public GitLabCliStatus status()
    {
        return cli.status();
    }

    @PostMapping("/install")
    public GitLabCliStatus install()
    {
        return cli.install();
    }
}
