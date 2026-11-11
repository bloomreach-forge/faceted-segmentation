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
package org.bloomreach.forge.facetedsegmentation.component;

import java.util.List;

import org.bloomreach.forge.facetedsegmentation.catalog.FacetCatalog;
import org.bloomreach.forge.facetedsegmentation.catalog.StubCatalogReader;
import org.bloomreach.forge.facetedsegmentation.catalog.ValueListCatalogReader;
import org.bloomreach.forge.facetedsegmentation.expression.AbsentFacetPolicy;
import org.bloomreach.forge.facetedsegmentation.expression.Audience;
import org.bloomreach.forge.facetedsegmentation.expression.EvaluateOptions;
import org.hippoecm.hst.core.component.HstRequest;
import org.hippoecm.hst.mock.content.beans.standard.MockHippoBean;
import org.hippoecm.hst.mock.core.component.MockHstRequest;
import org.hippoecm.hst.mock.core.container.MockComponentManager;
import org.hippoecm.hst.mock.core.request.MockHstRequestContext;
import org.hippoecm.hst.site.HstServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.bloomreach.forge.facetedsegmentation.component.FacetedSegmentationComponent.ATTRIBUTE_AUDIENCE;
import static org.bloomreach.forge.facetedsegmentation.component.FacetedSegmentationComponent.ATTRIBUTE_DOCUMENT;
import static org.bloomreach.forge.facetedsegmentation.component.FacetedSegmentationComponent.ATTRIBUTE_EVALUATE_OPTIONS;
import static org.bloomreach.forge.facetedsegmentation.component.FacetedSegmentationComponent.ATTRIBUTE_MATCHED_SECTIONS;
import static org.bloomreach.forge.facetedsegmentation.component.FacetedSegmentationComponent.ATTRIBUTE_RENDER_PUBLIC_CONTENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The request contract {@link FacetedSegmentationComponent} offers a subclass: which attributes are
 * set, and which are deliberately left unset when evaluation could not run.
 */
class FacetedSegmentationComponentTest {

    /**
     * The Essentials base constructor looks up an optional extension from HST's component manager;
     * an empty mock makes it fall back to its no-op default.
     */
    @BeforeEach
    void registerComponentManager() {
        HstServices.setComponentManager(new MockComponentManager());
    }

    @AfterEach
    void clearComponentManager() {
        HstServices.setComponentManager(null);
    }

    private static final List<FacetCatalog.Facet> SIZE = List.of(new FacetCatalog.Facet(
            "size", "Size", "/size", List.of(new FacetCatalog.FacetValue("s", "Small"))));

    @Test
    void publishesTheOptionsTheSectionsWereEvaluatedWith() {
        final MockHstRequest request = requestWithDocument(false);

        new TestComponent("permissive", StubCatalogReader.serving(List.of())).evaluateSections(request);

        final EvaluateOptions options = (EvaluateOptions) request.getAttribute(ATTRIBUTE_EVALUATE_OPTIONS);
        assertNotNull(options);
        assertEquals(AbsentFacetPolicy.PERMISSIVE, options.absentFacetPolicy());
        assertNotNull(request.getAttribute(ATTRIBUTE_AUDIENCE));
    }

    @Test
    void anUnrecognisedPolicyIsPublishedAsTheStrictFallback() {
        final MockHstRequest request = requestWithDocument(false);

        new TestComponent("permisive", StubCatalogReader.serving(List.of())).evaluateSections(request);

        final EvaluateOptions options = (EvaluateOptions) request.getAttribute(ATTRIBUTE_EVALUATE_OPTIONS);
        assertEquals(AbsentFacetPolicy.STRICT, options.absentFacetPolicy());
    }

    @Test
    void aCatalogOutageLeavesAudienceAndOptionsUnset() {
        final MockHstRequest request = requestWithDocument(false);

        new TestComponent("permissive", StubCatalogReader.failing()).evaluateSections(request);

        // Unset rather than Audience.empty(): a subclass evaluating an empty audience under
        // permissive would let every NOT IN rule through for the length of the outage.
        assertNull(request.getAttribute(ATTRIBUTE_AUDIENCE));
        assertNull(request.getAttribute(ATTRIBUTE_EVALUATE_OPTIONS));
        assertEquals(List.of(), request.getAttribute(ATTRIBUTE_MATCHED_SECTIONS));
        assertEquals(Boolean.TRUE, request.getAttribute(ATTRIBUTE_RENDER_PUBLIC_CONTENT));
    }

    @Test
    void aLiveRequestTakesItsAudienceFromResolveAudience() {
        final MockHstRequest request = requestWithDocument(false);
        final TestComponent component = new TestComponent("permissive", StubCatalogReader.serving(SIZE));

        component.evaluateSections(request);

        assertTrue(component.resolveAudienceCalled);
        assertEquals(TestComponent.SOURCE_AUDIENCE, request.getAttribute(ATTRIBUTE_AUDIENCE));
    }

    @Test
    void previewTakesItsAudienceFromPreviewParamsEvenWhenResolveAudienceIsOverridden() {
        final MockHstRequest request = requestWithDocument(true);
        final TestComponent component = new TestComponent("permissive", StubCatalogReader.serving(SIZE));

        component.evaluateSections(request);

        assertFalse(component.resolveAudienceCalled);
        final Audience audience = (Audience) request.getAttribute(ATTRIBUTE_AUDIENCE);
        assertEquals(List.of("Small"), audience.get("Size"));
    }

    @Test
    void aMissingDocumentLeavesAudienceAndOptionsUnset() {
        final MockHstRequest request = new MockHstRequest();

        new TestComponent("permissive", StubCatalogReader.serving(List.of())).evaluateSections(request);

        assertNull(request.getAttribute(ATTRIBUTE_AUDIENCE));
        assertNull(request.getAttribute(ATTRIBUTE_EVALUATE_OPTIONS));
    }

    private static MockHstRequest requestWithDocument(final boolean preview) {
        final MockHstRequest request = new MockHstRequest();
        request.setRequestContext(new MockHstRequestContext() {
            @Override
            public boolean isPreview() {
                return preview;
            }
        });
        request.setAttribute(ATTRIBUTE_DOCUMENT, new SectionlessDocument());
        return request;
    }

    /** Supplies parameters and the catalog directly, standing in for HST and the addon module. */
    private static final class TestComponent extends FacetedSegmentationComponent {

        static final Audience SOURCE_AUDIENCE = Audience.builder().put("Size", "Small").build();

        private final FacetedSegmentationInfo info;
        private final ValueListCatalogReader reader;
        boolean resolveAudienceCalled;

        TestComponent(final String absentFacetPolicy, final ValueListCatalogReader reader) {
            this.info = new FixedInfo(absentFacetPolicy);
            this.reader = reader;
        }

        @Override
        @SuppressWarnings("unchecked")
        protected <T> T getComponentParametersInfo(final HstRequest request) {
            return (T) info;
        }

        @Override
        protected ValueListCatalogReader getCatalogReader() {
            return reader;
        }

        /** Stands in for a project's own audience source. */
        @Override
        protected Audience resolveAudience(final HstRequest request,
                                           final ValueListCatalogReader.Facets facets) {
            resolveAudienceCalled = true;
            return SOURCE_AUDIENCE;
        }
    }

    private record FixedInfo(String absentFacetPolicy) implements FacetedSegmentationInfo {
        @Override public String getDocument() { return "personalized-content/personalized-content-1"; }
        @Override public String getAbsentFacetPolicy() { return absentFacetPolicy; }
        @Override public String getSectionMatchMode() { return "first-match"; }
        @Override public String getPublicContentMode() { return "fallback"; }
        @Override public String getPreviewModeQueryParams() { return "size=S"; }
    }

    /** Only the document's presence matters to these tests. */
    private static final class SectionlessDocument extends MockHippoBean {
        @Override
        public <T> List<T> getChildBeansByName(final String childNodeName) {
            return List.of();
        }
    }
}
