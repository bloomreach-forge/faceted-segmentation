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

import org.hippoecm.hst.core.parameters.DropDownList;
import org.hippoecm.hst.core.parameters.JcrPath;
import org.hippoecm.hst.core.parameters.Parameter;
import org.onehippo.cms7.essentials.components.EssentialsDocumentComponent;

/**
 * Channel-Manager parameters for {@link FacetedSegmentationComponent}.
 */
public interface FacetedSegmentationInfo {

    /**
     * The document whose faceted segmentation sections this component renders, relative to the
     * channel's content root — the convention {@link EssentialsDocumentComponent} resolves it
     * under. Left unrestricted here; a project should override to pin {@code pickerSelectableNodeTypes}
     * to its own type.
     */
    @Parameter(name = "document", required = true, displayName = "Document")
    @JcrPath(isRelative = true, pickerConfiguration = "cms-pickers/documents-only")
    String getDocument();

    /**
     * What happens when a request carries no value at all for a facet a rule names.
     *
     * Strict       - fails both {@code IN} and {@code NOT IN}.
     * Permissive   - still fails {@code IN} but lets {@code NOT IN} pass, on the reading that there is nothing to
     * exclude so the exclusion is satisfied.
     */
    @Parameter(name = "absentFacetPolicy", defaultValue = "strict", displayName = "Absent facet policy")
    @DropDownList({"strict", "permissive"})
    String getAbsentFacetPolicy();

    /**
     * How many of a document's sections may render when more than one rule matches.
     *
     * Section order is always priority.
     * {@code first-match} (the default) renders only the earliest matching section and stops.
     * {@code all-matches} renders every matching section, in stored order.
     *
     * The choice interacts with {@link #getPublicContentMode()}
     */
    @Parameter(name = "sectionMatchMode", defaultValue = "first-match", displayName = "Section match mode")
    @DropDownList({"first-match", "all-matches"})
    String getSectionMatchMode();

    /**
     * Whether the document's public content still renders once a section has matched.
     *
     * {@code fallback} (the default) treats public content as the ungated alternative; it renders only when no section matched.
     * {@code always} renders public content first and then appends whatever sections matched.
     */
    @Parameter(name = "publicContentMode", defaultValue = "fallback", displayName = "Public content mode")
    @DropDownList({"fallback", "always"})
    String getPublicContentMode();

    /**
     * Used exclusively in the Experience Manager to preview the component with different facet values.
     */
    @Parameter(name = "previewModeQueryParams", defaultValue = "day-of-the-week=monday&signedin=in", displayName = "Preview mode query parameters")
    String getPreviewModeQueryParams();
}
