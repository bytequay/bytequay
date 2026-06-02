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
package com.bytequay.app.service.hello;

import org.springframework.web.bind.annotation.GetMapping;

/**
 * Liveness probe used by the Electron host to confirm the Java
 * sidecar is up. Intentionally trivial — the implementation returns a
 * fixed string. Any failure to bind this endpoint means the bean
 * graph itself didn't come up, which is exactly what the probe wants
 * to surface.
 */
public interface HelloService
{
    @GetMapping("/hello")
    String hello();
}
