/**
 * Copyright (C) 2010 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms.submission;

import org.orbeon.oxf.common.OXFException;
import org.orbeon.oxf.externalcontext.ForwardExternalContextRequestWrapper;
import org.orbeon.oxf.pipeline.api.ExternalContext;
import org.orbeon.oxf.util.*;
import org.orbeon.oxf.xforms.*;
import org.orbeon.oxf.xforms.event.events.XFormsSubmitDoneEvent;
import org.orbeon.oxf.xml.XMLUtils;

import java.io.*;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Optimized submission doesn't issue HTTP requests but goes through the Servlet API.
 */
public class OptimizedSubmission extends BaseSubmission {

    // TODO: harmonize with regular HTTP submission headers configuration (property)
    public static String[] MINIMAL_HEADERS_TO_FORWARD = { "cookie", "authorization" };
    public static String[] STANDARD_HEADERS_TO_FORWARD = { "cookie", "authorization", "user-agent"};

    public OptimizedSubmission(XFormsModelSubmission submission) {
        super(submission);
    }

    public String getType() {
        return "optimized";
    }

    /**
     * Check whether optimized submission is allowed, depending on a series of conditions.
     *
     * Log a lot of stuff for development, as it is not always obvious why we pick an optimized vs. regular submission.
     */
    public boolean isMatch(PropertyContext propertyContext, XFormsModelSubmission.SubmissionParameters p,
                           XFormsModelSubmission.SecondPassParameters p2, XFormsModelSubmission.SerializationParameters sp) {

        final ExternalContext.Request request = XFormsUtils.getExternalContext(propertyContext).getRequest();
        final IndentedLogger indentedLogger = getDetailsLogger(p, p2);

        final boolean isDebugEnabled = indentedLogger.isDebugEnabled();
        if (isDebugEnabled) {
            indentedLogger.logDebug("", "checking whether optimized submission is allowed",
                "resource", p2.actionOrResource, "noscript", Boolean.toString(p.isNoscript),
                "is ajax portlet", Boolean.toString(XFormsProperties.isAjaxPortlet(containingDocument)),
                "is asynchronous", Boolean.toString(p2.isAsynchronous),
                "container type", request.getContainerType(), "norewrite", Boolean.toString(submission.isURLNorewrite()),
                "url type", submission.getUrlType(),
                "local-submission-forward", Boolean.toString(XFormsProperties.isOptimizeLocalSubmissionForward(containingDocument)),
                "local-submission-include", Boolean.toString(XFormsProperties.isOptimizeLocalSubmissionInclude(containingDocument))
            );
        }

        // Absolute URL is not optimized
        if (NetUtils.urlHasProtocol(p2.actionOrResource)) {
            if (isDebugEnabled)
                indentedLogger.logDebug("", "skipping optimized submission",
                        "reason", "resource URL has protocol", "resource", p2.actionOrResource);
            return false;
        }

        // TODO: why is this condition here?
        if (p.isNoscript && !XFormsProperties.isAjaxPortlet(containingDocument)) {
            if (isDebugEnabled)
                indentedLogger.logDebug("", "skipping optimized submission",
                        "reason", "noscript mode enabled and not in ajax portlet mode");
            return false;
        }

        // For now, we don't handle optimized async; could be optimized in the future
        if (p2.isAsynchronous) {
            if (isDebugEnabled)
                indentedLogger.logDebug("", "skipping optimized submission",
                        "reason", "asynchronous mode is not supported yet");
            return false;
        }

        if (request.getContainerType().equals("portlet")) {
            // Portlet

            if (submission.isURLNorewrite()) {
                if (isDebugEnabled)
                    indentedLogger.logDebug("", "skipping optimized submission",
                            "reason", "norewrite is specified in portlet environment");
                return false;
            }

            // NOTE: we could optimize for resource URLs:
            // o JSR-268 local resource handling, can access portlet resources the same way as with render/action
            // o In include mode, Servlet resources can be accessed using request dispatcher to servlet

            if ("resource".equals(submission.getUrlType())) {
                if (isDebugEnabled)
                    indentedLogger.logDebug("", "skipping optimized submission",
                            "reason", "resource URL type is specified in portlet environment");
                return false;
            }
        } else if (p.isReplaceAll) {
            // Servlet, replace all
            if (!XFormsProperties.isOptimizeLocalSubmissionForward(containingDocument)) {
                if (isDebugEnabled)
                    indentedLogger.logDebug("", "skipping optimized submission",
                            "reason", "forward submissions are disallowed in properties");
                return false;
            }
        } else {
            // Servlet, other
            if (!XFormsProperties.isOptimizeLocalSubmissionInclude(containingDocument)) {
                if (isDebugEnabled)
                    indentedLogger.logDebug("", "skipping optimized submission",
                            "reason", "include submissions are disallowed in properties");
                return false;
            }
        }

        if (isDebugEnabled)
            indentedLogger.logDebug("", "enabling optimized submission");

        return true;
    }

    public SubmissionResult connect(final PropertyContext propertyContext, final XFormsModelSubmission.SubmissionParameters p,
                                    final XFormsModelSubmission.SecondPassParameters p2, final XFormsModelSubmission.SerializationParameters sp) throws Exception {
        // This is an "optimized" submission, i.e. one that does not use an actual protocol handler to
        // access the resource, but instead uses servlet forward/include for servlets, or a local
        // mechanism for portlets.

        // NOTE: Optimizing with include() for servlets doesn't allow detecting errors caused by the
        // included resource. [As of 2009-02-13, not sure if this is the case.]

        // NOTE: For portlets, paths are served directly by the portlet, NOT as resources.

        // f:url-norewrite="true" with an absolute path allows accessing other servlet contexts.

        // Current limitations:
        // o Portlets cannot access resources outside the portlet except by using absolute URLs (unless f:url-type="resource")

        // URI with xml:base resolution
        final URI resolvedURI = XFormsUtils.resolveXMLBase(containingDocument, submission.getSubmissionElement(), p2.actionOrResource);

        // NOTE: We don't want any changes to happen to the document upon xxforms-submit when producing
        // a new document so we don't dispatch xforms-submit-done and pass a null XFormsModelSubmission
        // in that case

        // NOTE about headers forwarding: forward user-agent header for replace="all", since that *usually*
        // simulates a request from the browser! Useful in particular when the target URL renders XForms
        // in noscript mode, where some browser sniffing takes place for handling the <button> vs. <submit>
        // element.
        final String[] headersToForward = p.isReplaceAll ? STANDARD_HEADERS_TO_FORWARD : MINIMAL_HEADERS_TO_FORWARD;
        // TODO: Harmonize with HTTP submission handling of headers

        final IndentedLogger timingLogger = getTimingLogger(p, p2);
        final IndentedLogger detailsLogger = getDetailsLogger(p, p2);

        // Evaluate headers if any
        final Map<String, String[]> customHeaderNameValues = evaluateHeaders(propertyContext, p.contextStack);

        final String submissionEffectiveId = submission.getEffectiveId();

        // Pack external call into a Runnable so it can be run:
        // o now and synchronously
        // o now and asynchronously
        // o later as a "foreground" asynchronous submission
        final Callable<SubmissionResult> callable = new Callable<SubmissionResult>() {
            public SubmissionResult call() throws Exception {

                // TODO: This refers to PropertyContext, XFormsContainingDocument, and Submission. FIXME!

                // Open the connection
                final boolean[] status = { false , false};
                ConnectionResult connectionResult = null;
                try {
                    connectionResult = openOptimizedConnection(propertyContext, XFormsUtils.getExternalContext(propertyContext),
                        containingDocument, detailsLogger, p.isDeferredSubmissionSecondPassReplaceAll ? null : submission,
                        p.actualHttpMethod, resolvedURI.toString(), submission.isURLNorewrite(), sp.actualRequestMediatype, sp.messageBody,
                        sp.queryString, p.isReplaceAll, headersToForward, customHeaderNameValues);

                    // Update status
                    status[0] = true;

                    if (connectionResult.dontHandleResponse) {
                        // This means we got a submission with replace="all" and openOptimizedConnection() already did all the work
                        // TODO: Could this be done in a Replacer instead?
                        containingDocument.setGotSubmissionReplaceAll();

                        // Update status
                        status[1] = true;

                        // Caller has nothing to do
                        return null;
                    } else {
                        // Obtain replacer
                        final Replacer replacer = submission.getReplacer(propertyContext, connectionResult, p);

                        // Deserialize
                        replacer.deserialize(propertyContext, connectionResult, p, p2);

                        // Update status
                        status[1] = true;

                        // Return result
                        return new SubmissionResult(submissionEffectiveId, replacer, connectionResult);
                    }
                } catch (Throwable throwable) {
                    // Exceptions are handled further down
                    return new SubmissionResult(submissionEffectiveId, throwable, connectionResult);
                } finally {
                    if (p2.isAsynchronous && timingLogger.isDebugEnabled())
                        timingLogger.endHandleOperation("id", submissionEffectiveId, "asynchronous", Boolean.toString(p2.isAsynchronous),
                                "connected", Boolean.toString(status[0]), "deserialized", Boolean.toString(status[1]));
                }
            }
        };

        // Submit the callable
        // This returns null if the execution is deferred
        return submitCallable(propertyContext, p, p2, callable);
    }

    /**
     * Perform an optimized local connection using the Servlet API instead of using a URLConnection.
     */
    public static ConnectionResult openOptimizedConnection(PropertyContext propertyContext, ExternalContext externalContext,
                                                           XFormsContainingDocument containingDocument,
                                                           IndentedLogger indentedLogger,
                                                           XFormsModelSubmission xformsModelSubmission,
                                                           String httpMethod, final String resource, boolean isNorewrite, String mediatype,
                                                           byte[] messageBody, String queryString,
                                                           boolean isReplaceAll, String[] headerNames,
                                                           Map<String, String[]> customHeaderNameValues) {

        // NOTE: This code does custom rewriting of the path on the action, taking into account whether
        // the page was produced through a filter in separate deployment or not.
        final boolean isContextRelative;
        final String effectiveAction;
        if (!isNorewrite) {
            // Must rewrite
            if (containingDocument.getDeploymentType() != XFormsConstants.DeploymentType.separate) {
                // We are not in separate deployment, so keep path relative to the current servlet context
                isContextRelative = true;
                effectiveAction = resource;
            } else {
                // We are in separate deployment, so prepend request context path and mark path as not relative to the current context`
                final String contextPath = containingDocument.getRequestContextPath();
                isContextRelative = false;
                effectiveAction = contextPath + resource;
            }
        } else {
            // Must not rewrite anyway, so mark path as not relative to the current context
            isContextRelative = false;
            effectiveAction = resource;
        }

        return openOptimizedConnection(propertyContext, externalContext, indentedLogger, containingDocument.getResponse(),
                                xformsModelSubmission, httpMethod, effectiveAction, isContextRelative, mediatype,
                                messageBody, queryString, isReplaceAll, headerNames, customHeaderNameValues);
    }

    /**
     * Perform an optimized local connection using the Servlet API instead of using a URLConnection.
     */
    private static ConnectionResult openOptimizedConnection(PropertyContext propertyContext, ExternalContext externalContext,
                                                            final IndentedLogger indentedLogger,
                                                            ExternalContext.Response response,
                                                            XFormsModelSubmission xformsModelSubmission,
                                                            String httpMethod, final String resource, boolean isContextRelative, String mediatype,
                                                            byte[] messageBody, String queryString,
                                                            final boolean isReplaceAll, String[] headerNames,
                                                            Map<String, String[]> customHeaderNameValues) {

        // Action must be an absolute path
        if (!resource.startsWith("/"))
            throw new OXFException("Action does not start with a '/': " + resource);

        final XFormsContainingDocument containingDocument = (xformsModelSubmission != null) ? xformsModelSubmission.getContainingDocument() : null;
        try {

            // Get dispatcher
            final ExternalContext.RequestDispatcher requestDispatcher = externalContext.getRequestDispatcher(resource, isContextRelative);
            final boolean isDefaultContext = requestDispatcher.isDefaultContext();

            // Case of empty body
            if (messageBody == null)
                messageBody = new byte[0];

            // Destination context path is the context path of the current request, or the context path implied by the new URI
            final String destinationContextPath = isDefaultContext ? "" : isContextRelative ? externalContext.getRequest().getContextPath() : NetUtils.getFirstPathElement(resource);

            // Create requestAdapter depending on method
            final ForwardExternalContextRequestWrapper requestAdapter;
            final String effectiveResourceURI;
            final String rootAdjustedResourceURI;
            {
                if (httpMethod.equals("POST") || httpMethod.equals("PUT")) {
                    // Simulate a POST or PUT
                    effectiveResourceURI = resource;

                    // Log request body
                    if (indentedLogger.isDebugEnabled() && isLogBody())
                        Connection.logRequestBody(indentedLogger, mediatype, messageBody);

                    rootAdjustedResourceURI = isDefaultContext || isContextRelative ? effectiveResourceURI : NetUtils.removeFirstPathElement(effectiveResourceURI);
                    if (rootAdjustedResourceURI == null)
                        throw new OXFException("Action must start with a servlet context path: " + resource);

                    requestAdapter = new ForwardExternalContextRequestWrapper(externalContext.getRequest(), destinationContextPath,
                            rootAdjustedResourceURI, httpMethod, (mediatype != null) ? mediatype : XMLUtils.XML_CONTENT_TYPE, messageBody, headerNames, customHeaderNameValues);
                } else {
                    // Simulate a GET or DELETE
                    {
                        final StringBuffer updatedActionStringBuffer = new StringBuffer(resource);
                        if (queryString != null) {
                            if (resource.indexOf('?') == -1)
                                updatedActionStringBuffer.append('?');
                            else
                                updatedActionStringBuffer.append('&');
                            updatedActionStringBuffer.append(queryString);
                        }
                        effectiveResourceURI = updatedActionStringBuffer.toString();
                    }

                    rootAdjustedResourceURI = isDefaultContext || isContextRelative ? effectiveResourceURI : NetUtils.removeFirstPathElement(effectiveResourceURI);
                    if (rootAdjustedResourceURI == null)
                        throw new OXFException("Action must start with a servlet context path: " + resource);

                    requestAdapter = new ForwardExternalContextRequestWrapper(externalContext.getRequest(), destinationContextPath,
                            rootAdjustedResourceURI, httpMethod, headerNames, customHeaderNameValues);
                }
            }

            if (indentedLogger.isDebugEnabled())
                indentedLogger.logDebug("", "dispatching request",
                            "method", httpMethod,
                            "mediatype", mediatype,
                            "context path", destinationContextPath,
                            "effective resource URI (original)", effectiveResourceURI,
                            "effective resource URI (relative to servlet root)", rootAdjustedResourceURI);

            // Reason we use a Response passed is for the case of replace="all" when XFormsContainingDocument provides a Response
            final ExternalContext.Response effectiveResponse = !isReplaceAll ? null : response != null ? response : externalContext.getResponse();

            final ConnectionResult connectionResult = new ConnectionResult(effectiveResourceURI) {
                @Override
                public void close() {
                    if (getResponseInputStream() != null) {
                        // Case of !isReplaceAll where we read from the response
                        try {
                            getResponseInputStream().close();
                        } catch (IOException e) {
                            throw new OXFException("Exception while closing input stream for resource: " + resource);
                        }
                    } else {
                        // Case of isReplaceAll where forwarded resource writes to the response directly

                        // Try to obtain, flush and close the stream to work around WebSphere issue
                        try {
                            final OutputStream os = effectiveResponse.getOutputStream();
                            os.flush();
                            os.close();
                        } catch (IllegalStateException e) {
                            indentedLogger.logDebug("", "IllegalStateException caught while closing OutputStream after forward");
                            try {
                                final PrintWriter writer = effectiveResponse.getWriter();
                                writer.flush();
                                writer.close();
                            } catch (IllegalStateException f) {
                                indentedLogger.logDebug("", "IllegalStateException caught while closing Writer after forward");
                            } catch (IOException f) {
                                indentedLogger.logDebug("", "IOException caught while closing Writer after forward");
                            }
                        } catch (IOException e) {
                            indentedLogger.logDebug("", "IOException caught while closing OutputStream after forward");
                        }
                    }
                }
            };
            if (isReplaceAll) {
                // "the event xforms-submit-done is dispatched"
                if (xformsModelSubmission != null)
                    xformsModelSubmission.getXBLContainer(containingDocument).dispatchEvent(propertyContext,
                            new XFormsSubmitDoneEvent(containingDocument, xformsModelSubmission, connectionResult.resourceURI, connectionResult.statusCode));
                // Just forward the reply to the response
                requestDispatcher.forward(requestAdapter, effectiveResponse);
                connectionResult.dontHandleResponse = true;
            } else {
                // We must intercept the reply
                final ResponseAdapter responseAdapter = new ResponseAdapter(externalContext.getNativeResponse());
                requestDispatcher.include(requestAdapter, responseAdapter);

                // Get response information that needs to be forwarded

                // NOTE: Here, the resultCode is not propagated from the included resource
                // when including Servlets. Similarly, it is not possible to obtain the
                // included resource's content type or headers. Because of this we should not
                // use an optimized submission from within a servlet.
                connectionResult.statusCode = responseAdapter.getResponseCode();
                connectionResult.setResponseContentType(XMLUtils.XML_CONTENT_TYPE);
                connectionResult.setResponseInputStream(responseAdapter.getInputStream());
                connectionResult.responseHeaders = ConnectionResult.EMPTY_HEADERS_MAP;
                connectionResult.setLastModified(null);
            }

            return connectionResult;
        } catch (IOException e) {
            throw new OXFException(e);
        }
    }
}
