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

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hippoecm.hst.content.beans.standard.HippoBean;
import org.hippoecm.hst.core.component.HstComponentException;
import org.hippoecm.hst.core.component.HstRequest;
import org.hippoecm.hst.core.component.HstResponse;
import org.hippoecm.hst.core.parameters.ParametersInfo;
import org.hippoecm.hst.core.request.HstRequestContext;
import org.onehippo.cms7.essentials.components.EssentialsDocumentComponent;
import org.bloomreach.forge.facetedsegmentation.catalog.CatalogReaderHolder;
import org.bloomreach.forge.facetedsegmentation.catalog.ValueListCatalogReader;
import org.bloomreach.forge.facetedsegmentation.expression.AbsentFacetPolicy;
import org.bloomreach.forge.facetedsegmentation.expression.Audience;
import org.bloomreach.forge.facetedsegmentation.expression.EvaluateOptions;
import org.bloomreach.forge.facetedsegmentation.expression.ExpressionEvaluator;
import org.bloomreach.forge.facetedsegmentation.expression.ExpressionNode;
import org.bloomreach.forge.facetedsegmentation.expression.ExpressionParseException;
import org.bloomreach.forge.facetedsegmentation.expression.ExpressionParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders the sections of a document whose segmentation rules match the current request.
 *
 * Request attributes:
 * document — the configured document.
 * matchedSections — the matching sections, in document order.
 * renderPublicContent — whether to also render the public content.
 * audience — the request's facet values.
 * evaluateOptions — the options the sections were evaluated with.
 *
 * Facet values come from query parameters by default, so rules can be tried from a browser. To use
 * another source, such as an identity service, override {@link #resolveAudience}. In preview they
 * always come from the {@code previewModeQueryParams} parameter.
 */
@ParametersInfo(type = FacetedSegmentationInfo.class)
public class FacetedSegmentationComponent extends EssentialsDocumentComponent {

    private static final Logger log = LoggerFactory.getLogger(FacetedSegmentationComponent.class);

    public static final String ATTRIBUTE_DOCUMENT = "document";
    public static final String ATTRIBUTE_MATCHED_SECTIONS = "matchedSections";
    public static final String ATTRIBUTE_RENDER_PUBLIC_CONTENT = "renderPublicContent";
    public static final String ATTRIBUTE_AUDIENCE = "audience";
    /**
     * The options the sections were evaluated with, for a subclass that calls {@link #matches} itself.
     * Absent, like {@link #ATTRIBUTE_AUDIENCE}, when nothing was evaluated. Evaluate nothing then:
     * against an empty audience, negative rules such as {@code NOT IN} would pass.
     */
    public static final String ATTRIBUTE_EVALUATE_OPTIONS = "evaluateOptions";

    /** {@code sectionMatchMode}: render every matching section rather than only the first. */
    private static final String MATCH_MODE_ALL = "all-matches";
    /** {@code publicContentMode}: render public content regardless of what matched. */
    private static final String PUBLIC_CONTENT_ALWAYS = "always";
    /** The section property holding its expression. */
    private static final String DEFAULT_EXPRESSION_PROPERTY = "facetseg:faceted_segmentation";
    /** The node name of a document's sections. */
    private static final String DEFAULT_SECTION_NODE_NAME = "facetseg:FacetedSegmentationSection";

    @Override
    public void doBeforeRender(final HstRequest request, final HstResponse response)
            throws HstComponentException {
        super.doBeforeRender(request, response);
        evaluateSections(request);
    }

    /** Split from {@link #doBeforeRender} so it can be tested without a live HST request. */
    void evaluateSections(final HstRequest request) {
        final FacetedSegmentationInfo info = getComponentParametersInfo(request);
        if (info == null || info.getDocument() == null) {
            log.debug("No document configured on this component instance — nothing to render.");
            return;
        }

        final HstRequestContext context = request.getRequestContext();
        final HippoBean document = (HippoBean) request.getAttribute(ATTRIBUTE_DOCUMENT);
        if (document == null) {
            log.warn("No document found at '{}' — check the component's configuration.", info.getDocument());
            return;
        }

        final ValueListCatalogReader.Facets facets;
        try {
            facets = getCatalogReader().read(context, false);
        } catch (final RuntimeException e) {
            // Without the catalog nothing can match, so render public content whatever the mode.
            // Audience and options stay unset on purpose: see ATTRIBUTE_EVALUATE_OPTIONS.
            log.error("Could not read the facet catalog; falling back to public content.", e);
            request.setAttribute(ATTRIBUTE_MATCHED_SECTIONS, List.of());
            request.setAttribute(ATTRIBUTE_RENDER_PUBLIC_CONTENT, true);
            return;
        }

        // Decided here rather than in resolveAudience, so preview keeps working when that's overridden.
        final Audience audience = context.isPreview()
                ? toAudience(parseQueryParams(info.getPreviewModeQueryParams()), facets)
                : resolveAudience(request, facets);
        final EvaluateOptions options = EvaluateOptions.builder()
                .absentFacetPolicy(resolvePolicy(info))
                .build();

        // Authors can reorder sections, which changes which one wins under first-match.
        final List<HippoBean> sections = document.getChildBeansByName(getSectionNodeName());
        final boolean stopAtFirst = !MATCH_MODE_ALL.equalsIgnoreCase(trimmed(info.getSectionMatchMode()));

        final List<HippoBean> matched = new ArrayList<>();
        for (final HippoBean section : sections) {
            if (matches(section, audience, options)) {
                matched.add(section);
                if (stopAtFirst) {
                    break;
                }
            }
        }

        final boolean publicAlways =
                PUBLIC_CONTENT_ALWAYS.equalsIgnoreCase(trimmed(info.getPublicContentMode()));

        request.setAttribute(ATTRIBUTE_MATCHED_SECTIONS, List.copyOf(matched));
        request.setAttribute(ATTRIBUTE_RENDER_PUBLIC_CONTENT, publicAlways || matched.isEmpty());
        request.setAttribute(ATTRIBUTE_AUDIENCE, audience);
        request.setAttribute(ATTRIBUTE_EVALUATE_OPTIONS, options);

        if (log.isDebugEnabled()) {
            log.debug("{} of {} section(s) matched for document '{}' (mode={}, publicContent={}).",
                    matched.size(), sections.size(), info.getDocument(),
                    stopAtFirst ? "first-match" : "all-matches",
                    publicAlways || matched.isEmpty() ? "rendered" : "suppressed");
        }
    }

    private static String trimmed(final String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * The facet values of a live request. Override this to source them from somewhere other than
     * query parameters. Not called in preview.
     */
    protected Audience resolveAudience(final HstRequest request,
                                       final ValueListCatalogReader.Facets facets) {
        // The servlet request, because query parameters belong to the page, not the component.
        return toAudience(request.getRequestContext().getServletRequest().getParameterMap(), facets);
    }

    /** Maps facet ids and value keys onto the display names and labels expressions use. */
    private Audience toAudience(final Map<String, String[]> parameters,
                                final ValueListCatalogReader.Facets facets) {
        final Audience.Builder builder = Audience.builder();

        for (final Map.Entry<String, String[]> entry : parameters.entrySet()) {
            final String facetId = entry.getKey();
            final String displayName = facets.displayNameForId(facetId);
            if (displayName == null) {
                // Not a facet: an unrelated parameter or a typo.
                log.debug("Ignoring request parameter '{}': no value list with that id.", facetId);
                continue;
            }

            final List<String> labels = new ArrayList<>();
            for (final String raw : entry.getValue()) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                // Accept a comma-separated list too, so ?size=S,M works as well as ?size=S&size=M.
                for (final String candidate : raw.split(",")) {
                    final String trimmed = candidate.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    final String label = facets.labelForKey(facetId, trimmed);
                    if (label == null) {
                        log.debug("Ignoring value '{}' for facet '{}': not in that value list.",
                                trimmed, facetId);
                        continue;
                    }
                    if (!labels.contains(label)) {
                        labels.add(label);
                    }
                }
            }

            if (!labels.isEmpty()) {
                builder.putAll(displayName, labels);
            }
        }

        return builder.build();
    }

    /** Parses a {@code key=value&key=value} string into a parameter map. */
    private Map<String, String[]> parseQueryParams(final String rawQueryParams) {
        final Map<String, List<String>> collected = new LinkedHashMap<>();
        if (rawQueryParams == null || rawQueryParams.isBlank()) {
            return Collections.emptyMap();
        }

        for (final String pair : rawQueryParams.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            final int eq = pair.indexOf('=');
            final String rawKey = eq == -1 ? pair : pair.substring(0, eq);
            final String rawValue = eq == -1 ? "" : pair.substring(eq + 1);
            final String key = decode(rawKey);
            if (key.isBlank()) {
                continue;
            }
            collected.computeIfAbsent(key, k -> new ArrayList<>()).add(decode(rawValue));
        }

        final Map<String, String[]> result = new LinkedHashMap<>();
        for (final Map.Entry<String, List<String>> entry : collected.entrySet()) {
            result.put(entry.getKey(), entry.getValue().toArray(new String[0]));
        }
        return result;
    }

    private static String decode(final String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    /**
     * Evaluates one section's rule. A blank or malformed expression never matches. Elsewhere in the
     * library an empty expression means "always", but here that would let a half-edited section win.
     */
    protected boolean matches(final HippoBean section, final Audience audience,
                              final EvaluateOptions options) {
        final String expression = section.getSingleProperty(getExpressionPropertyName());
        if (expression == null || expression.isBlank()) {
            return false;
        }

        final ExpressionNode parsed;
        try {
            parsed = ExpressionParser.parse(expression);
        } catch (final ExpressionParseException e) {
            log.error("Section '{}' has an unparseable segmentation rule, so it will NOT be rendered. "
                            + "{}", section.getPath(), e.describe());
            return false;
        }

        return ExpressionEvaluator.evaluate(parsed, audience, options);
    }

    private AbsentFacetPolicy resolvePolicy(final FacetedSegmentationInfo info) {
        final String configured = info.getAbsentFacetPolicy();
        if (configured == null || configured.isBlank()) {
            return AbsentFacetPolicy.STRICT;
        }
        try {
            return AbsentFacetPolicy.valueOf(configured.trim().toUpperCase());
        } catch (final IllegalArgumentException e) {
            // Fall back to the safe policy rather than failing the page.
            log.warn("Unrecognised absentFacetPolicy '{}'; falling back to STRICT.", configured);
            return AbsentFacetPolicy.STRICT;
        }
    }

    /** The catalog reader shared with the catalog endpoint, so both use one cache. */
    protected ValueListCatalogReader getCatalogReader() {
        final ValueListCatalogReader configured = CatalogReaderHolder.get();
        if (configured != null) {
            return configured;
        }
        throw new IllegalStateException(
                "No ValueListCatalogReader is configured. The plugin's HST addon module should define "
                        + "one — check that bloomreach-facetedsegmentation-hst is on the site's "
                        + "classpath so META-INF/hst-assembly/addon/module.xml is picked up.");
    }

    protected String getExpressionPropertyName() {
        return DEFAULT_EXPRESSION_PROPERTY;
    }

    protected String getSectionNodeName() {
        return DEFAULT_SECTION_NODE_NAME;
    }
}
