/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.clustering.session.container.servlet.examples;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Provides a unique identifier to requests for a given session.
 * @author Paul Ferraro
 */
public interface RequestIdentifierProvider {
	/** The name of the header/attribute storing the request index */
	String REQUEST_IDENTIFIER = "x-request-id";

	/**
	 * Returns the unique request identifier for the current request.
	 * @param request the current request.
	 * @return the unique request identifier for the current session.
	 */
	default Integer getRequestId(HttpServletRequest request) {
		return (Integer) request.getAttribute(REQUEST_IDENTIFIER);
	}
}
