<!--
  Copyright (C) 2011 Orbeon, Inc.

  This program is free software; you can redistribute it and/or modify it under the terms of the
  GNU Lesser General Public License as published by the Free Software Foundation; either version
  2.1 of the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
  See the GNU Lesser General Public License for more details.

  The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
  -->
<p:config xmlns:p="http://www.orbeon.com/oxf/pipeline"
          xmlns:oxf="http://www.orbeon.com/oxf/processors">

    <p:param type="input" name="data"/>
    <p:param type="input" name="bindings"/>
    <p:param type="output" name="data"/>

    <p:processor name="oxf:unsafe-xslt">
        <p:input name="data" href="#data"/>
        <p:input name="config">
            <xsl:stylesheet version="2.0"
                            xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                            xmlns:fr="http://orbeon.org/oxf/xml/form-runner"
                            xmlns:fb="http://orbeon.org/oxf/xml/form-builder"
                            xmlns:xh="http://www.w3.org/1999/xhtml"
                            xmlns:xf="http://www.w3.org/2002/xforms"
                            xmlns:xxf="http://orbeon.org/oxf/xml/xforms"
                            xmlns:ev="http://www.w3.org/2001/xml-events"
                            xmlns:dataModel="java:org.orbeon.oxf.fb.DataModel">

                <xsl:import href="oxf:/oxf/xslt/utils/copy-modes.xsl"/>

                <xsl:variable name="model" select="/*/xh:head/xf:model[@id = 'fr-form-model']"/>

                <!-- Duplicated from model.xml, but we have to right now as the form hasn't been loaded yet at this point -->
                <xsl:variable name="is-custom-instance"
                              select="$model/xf:instance[@id = 'fr-form-metadata']/*/form-instance-mode = 'custom'"/>

                <!-- Custom instance: add dataModel namespace binding to top-level bind -->
                <xsl:template match="xf:bind[@id = 'fr-form-binds']">
                    <xsl:copy>
                        <!-- NOTE: add even if not($is-custom-instance) so that things work if we switch to $is-custom-instance -->
                        <xsl:namespace name="dataModel" select="'java:org.orbeon.oxf.fb.DataModel'"/>
                        <xsl:apply-templates select="@* | node()"/>
                    </xsl:copy>
                </xsl:template>
                <!-- Custom instance: for nested binds, rewrite @ref/@nodeset -->
                <xsl:template match="xf:bind[$is-custom-instance and @id and @id != 'fr-form-binds']/@ref | xf:bind[$is-custom-instance and @id and @id != 'fr-form-binds']/@nodeset">
                    <xsl:attribute name="{name()}" select="dataModel:annotatedBindRef(../@id, .)"/>
                </xsl:template>

                <!-- Temporarily mark read-only instances as read-write -->
                <xsl:template match="xf:instance[@xxf:readonly = 'true']">
                    <xsl:copy>
                        <xsl:attribute name="fb:readonly" select="'true'"/><!-- so we remember to set the value back -->
                        <xsl:apply-templates select="@* except @xxf:readonly | node()"/>
                    </xsl:copy>
                </xsl:template>

                <!-- Update namespace on actions and services so that they don't run at design time -->
                <xsl:template match="xf:model/xf:*[p:classes() = ('fr-service', 'fr-database-service')] | xf:model/xf:action[ends-with(@id, '-binding')]">
                    <xsl:element name="fb:{local-name()}">
                        <xsl:apply-templates select="@* | node()"/>
                    </xsl:element>
                </xsl:template>

                <!-- Disable all event handlers at design time -->
                <xsl:template match="@ev:event | @event">
                    <xsl:attribute name="fb:{local-name()}" select="."/>
                </xsl:template>

                <!--
                    fr:view:
                    - copied over along with nested fr:buttons
                    - the XForms engine must ignore foreign elements such as fr:view in the XForms view
                    - we annotate fr:body below
                -->

                <!-- fr:body → xf:group -->
                <xsl:template match="xh:body//fr:body[not(parent::fr:repeat) and not (parent::fr:grid)]">
                    <xf:group class="fb-body">
                        <!-- Scope $lang which is the language of the form being edited -->
                        <xf:var name="lang" value="xxf:get-variable('fr-form-model', 'fb-lang')" as="element()" class="fb-annotation"/>
                        <!-- Scope $form-resources: resources of the form being edited.
                             Use the same logic as in resources-model. In the builder, we don't have a resources-model running
                             for the form being edited, so we duplicate this here. -->
                        <xf:var name="form-resources" value="instance('fr-form-resources')/(resource[@xml:lang = $lang], resource[1])[1]" as="element(resource)?" class="fb-annotation"/>
                        <!-- Scope $fr-resources for Form Runner resources -->
                        <xf:var name="fr-resources" value="xxf:get-variable('fr-resources-model', 'fr-fr-resources')" as="element(resource)?" class="fb-annotation"/>
                        <!-- Scope $fb-resources for Form Builder resources -->
                        <xf:var name="fb-resources" value="xxf:get-variable('fr-resources-model', 'fr-form-resources')" as="element(resource)?" class="fb-annotation"/>

                        <!-- Apply all the content -->
                        <xsl:apply-templates select="node()"/>

                        <!-- Listen to activations on grid cells -->
                        <xf:action ev:event="DOMActivate" xxf:phantom="true" class="fb-annotation">
                            <xf:var name="control-element" value="xxf:control-element(event('xxf:absolute-targetid'))"/>
                            <xf:action if="tokenize($control-element/@class, '\s+') = 'xforms-activable'">
                                <xf:var name="th-column" value="count($control-element/preceding-sibling::*[@xxf:element = 'xh:th']) + 1"/>
                                <xf:var name="new-selected-cell" value="if ($control-element/@xxf:element = 'xh:th') then
                                    ($control-element/following-sibling::xf:repeat//*[@xxf:element = 'xh:td'])[$th-column]/@id
                                    else $control-element/@id"/>
                                <xf:setvalue ref="xxf:get-variable('fr-form-model', 'selected-cell')" value="$new-selected-cell"/>
                            </xf:action>
                        </xf:action>

                    </xf:group>
                </xsl:template>

                <!-- fr:section → fr:section/(@edit-ref, @xxf:update) -->
                <xsl:template match="xh:body//fr:section">
                    <xsl:copy>
                        <xsl:attribute name="edit-ref"/>
                        <xsl:attribute name="xxf:update" select="'full'"/>
                        <xsl:apply-templates select="@* | node()"/>
                    </xsl:copy>
                </xsl:template>

                <!-- fr:grid → fr:grid/@edit-ref -->
                <xsl:template match="xh:body//fr:grid">
                    <xsl:copy>
                        <xsl:attribute name="edit-ref"/>
                        <xsl:apply-templates select="@* | node()"/>
                    </xsl:copy>
                </xsl:template>

                <!-- Convert MIP names -->
                <xsl:template match="xf:bind/@relevant | xf:bind/@readonly | xf:bind/@required | xf:bind/@constraint | xf:bind/@calculate | xf:bind/@xxf:default">
                    <xsl:attribute name="fb:{local-name()}" select="."/>
                </xsl:template>

                <!-- Add model actions -->
                <xsl:template match="xh:head/xf:model[@id = 'fr-form-model']">
                    <xsl:copy>
                        <xsl:apply-templates select="@* | node()"/>

                        <!-- Upon model creation, recalculation and revalidation, notify Form Builder -->
                        <xsl:for-each select="('xforms-model-construct', 'xforms-recalculate', 'xforms-revalidate', 'xxforms-xpath-error')">
                            <xf:action ev:event="{.}" ev:target="#observer" class="fb-annotation">
                                <!-- Upon MIP XPath error cancel the default error behavior (which otherwise can open an
                                     error dialog at inopportune times.) -->
                                <xsl:if test=". = 'xxforms-xpath-error'">
                                    <xsl:attribute name="ev:defaultAction">cancel</xsl:attribute>
                                </xsl:if>
                                <!-- Dispatch custom event to FB model -->
                                <xf:dispatch name="fb-{.}" targetid="|fr-form-model|"/>
                            </xf:action>
                        </xsl:for-each>

                    </xsl:copy>
                </xsl:template>

                <!-- TODO: convert legacy fr:repeat -->

                <!-- Saxon serialization adds an extra meta element, make sure to remove it -->
                <xsl:template match="xh:head/meta[@http-equiv = 'Content-Type']"/>

            </xsl:stylesheet>
        </p:input>
        <p:output name="data" id="annotated"/>
    </p:processor>

    <!-- Make sure XBL bindings for section templates are present -->
    <p:processor name="oxf:pipeline">
        <p:input name="config" href="add-template-bindings.xpl"/>
        <p:input name="data" href="#annotated"/>
        <p:input name="bindings" href="#bindings"/>
        <p:output name="data" ref="data"/>
    </p:processor>


</p:config>