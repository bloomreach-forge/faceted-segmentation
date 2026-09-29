/*
 * Copyright 2026 Bloomreach
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.bloomreach.forge.facetedsegmentation.catalog;

/**
 * Bridges the Spring-managed {@link ValueListCatalogReader} to HST components.
 *
 * <p>This exists because of an ownership mismatch: the reader is a Spring bean (defined in the
 * plugin's HST addon module, so it can be configured and shared), but HST instantiates components
 * itself — they are not Spring beans and cannot be injected. Rather than have each component build
 * its own reader (which would mean one cache per component instance, and the value lists re-read on
 * every page for every instance), the addon publishes the single configured reader here at startup
 * and components look it up.
 *
 * <p>Deliberately a plain static holder rather than a service-registry lookup: there is exactly one
 * reader per site webapp, it is set once during Spring context initialisation and never reassigned at
 * request time, so the visibility guarantee of {@code volatile} is sufficient and a heavier mechanism
 * would buy nothing.
 */
public final class CatalogReaderHolder {

    private static volatile ValueListCatalogReader reader;

    private CatalogReaderHolder() {
    }

    /** Called by the addon's Spring wiring. */
    public static void set(final ValueListCatalogReader reader) {
        CatalogReaderHolder.reader = reader;
    }

    /** The configured reader, or {@code null} if the plugin's addon module was never loaded. */
    public static ValueListCatalogReader get() {
        return reader;
    }
}
