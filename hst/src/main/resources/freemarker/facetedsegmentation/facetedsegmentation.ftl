<#--
  The plugin's own starting-point template for FacetedSegmentationComponent, rendering against the
  generic facetseg:PersonalizedContent / facetseg:FacetedSegmentationSection bean types (see
  org.bloomreach.forge.facetedsegmentation.beans). A project that subclasses the component with its
  own document type (see FacetedSegmentationInfo's class javadoc) should copy this file, adjust the
  bean types, and adjust the manageContent document-type query / rootPath to its own content
  structure.

  Self-contained on purpose: this does NOT rely on a project's own include/imports.ftl (that file's
  relative path is an archetype convention, not something this plugin can assume exists), so the
  taglib assigns and defineObjects call are inlined below instead.
-->
<#assign hst=JspTaglibs["http://www.hippoecm.org/jsp/hst/core"]>
<@hst.defineObjects />

<#-- @ftlvariable name="document" type="org.bloomreach.forge.facetedsegmentation.beans.PersonalizedContent" -->
<#-- @ftlvariable name="matchedSections" type="java.util.List<org.bloomreach.forge.facetedsegmentation.beans.FacetedSegmentationSection>" -->
<#-- @ftlvariable name="renderPublicContent" type="java.lang.Boolean" -->
<hr>
<#if document??>
  <article class="has-edit-button">
    <@hst.manageContent hippobean=document />
    <#--
      The component decides BOTH of these; this template only renders what it is handed.

        matchedSections      every section that won, in stored order. Under the default
                             first-match mode that is at most one; under all-matches it may be
                             several. Looping covers both, so no mode check belongs here.
        renderPublicContent  whether public content renders too. True when nothing matched
                             (public content as the ungated alternative), or whenever
                             publicContentMode is `always` (public content as a preamble).

      Public content is emitted FIRST so that, in `always` mode, it reads as a preamble to the
      personalised blocks rather than a trailing afterthought.
    -->
    <#if renderPublicContent?? && renderPublicContent>
      <#assign publicContent = document.public! />
      <#if publicContent??>
        <@hst.html hippohtml=publicContent />
      </#if>
    </#if>
    <#if matchedSections??>
      <#list matchedSections as section>
        <#if section.content??>
          <@hst.html hippohtml=section.content />
        </#if>
      </#list>
    </#if>
  </article>
<#-- @ftlvariable name="editMode" type="java.lang.Boolean"-->
<#elseif editMode?? && editMode>
  <div class="has-edit-button">
    <img src="<@hst.link path="/images/facetedsegmentationplugin/facetedsegmentation.svg" />" width="35" height="35"> Click to edit Faceted Segmentation
    <@hst.manageContent documentTemplateQuery="new-PersonalizedContent-document" parameterName="document" />
  </div>
<#else>
  <img src="<@hst.link path="/images/facetedsegmentationplugin/facetedsegmentation.svg" />" width="35" height="35"> No document has been selected for this Faceted Segmentation Component
</#if>
