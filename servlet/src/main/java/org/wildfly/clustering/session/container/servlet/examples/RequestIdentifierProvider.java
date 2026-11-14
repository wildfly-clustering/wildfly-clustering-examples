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
	String REQUEST_ID_KEY = "x-request-id";
	/** The identifier for requests with no or newly associated sessions. */
	Integer INITIAL_REQUEST_ID = 0;

	/**
	 * Returns the unique request identifier for the current request.
	 * @param request the current request.
	 * @return the unique request identifier for the current session.
	 */
	default Integer getRequestId(HttpServletRequest request) {
		return (Integer) request.getAttribute(REQUEST_ID_KEY);
	}
}
