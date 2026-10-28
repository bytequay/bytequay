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
package com.bytequay.core.hosting;

import com.bytequay.core.domain.RepositoryContributor;
import com.bytequay.core.domain.RepositoryLabel;

import java.util.List;
import java.util.Optional;

/** Repository facts used by the workspace header and existing people/label pickers. */
public interface RepositoryMetadataReader
{
    HostingProvider provider();

    RepositoryOverview overview(HostingAccess access, HostingRepository repository);

    Optional<List<RepositoryContributor>> contributors(HostingAccess access, HostingRepository repository);

    List<HostingRepositoryUser> repositoryUsers(HostingAccess access, HostingRepository repository);

    List<String> repositoryTeams(HostingAccess access, HostingRepository repository);

    List<RepositoryLabel> repositoryLabels(HostingAccess access, HostingRepository repository);
}
