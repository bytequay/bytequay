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
package com.bytequay.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Boots the entire Spring application context — every controller, every
 * service, every JPA repository, the Flyway migration set — and asserts
 * that the bean graph wires up without errors. Catches DI / config
 * regressions that the slice-only {@code @WebMvcTest} suites miss
 * (missing constructor args, broken Qualifiers, malformed Flyway
 * migrations, beans that fail their own validation in @PostConstruct).
 *
 * <p>Intentionally has no body: the {@link Test} method exists only to
 * trigger the {@code SpringBootTest} context load. If the context fails
 * to start, JUnit fails this test with the underlying cause.
 */
@SpringBootTest
class TestApplicationContextSmoke
{
    @Test
    void contextLoads()
    {
        // The body is intentionally empty — see the class javadoc.
    }
}
