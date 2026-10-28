/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.clustering.session.container.servlet.examples;

import java.io.Serial;

import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpSession;

/**
 * A request identifier filter that performs thread-safe reads/writes to the session using the monitor of the HttpSession identifier.
 * This implementation may not safe for use by concurrent requests for the same session, depending on the container implementation.
 * @author Paul Ferraro
 */
@WebFilter(urlPatterns = "/*")
public class SessionIdentifierMutexRequestIdentifierFilter extends MutexRequestIdentifierFilter {
	@Serial
	private static final long serialVersionUID = -4008102039114055103L;

	/** Default constructor */
	public SessionIdentifierMutexRequestIdentifierFilter() {
		super(HttpSession::getId);
	}
}
