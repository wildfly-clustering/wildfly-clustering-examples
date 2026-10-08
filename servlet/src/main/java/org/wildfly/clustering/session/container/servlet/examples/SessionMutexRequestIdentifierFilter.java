/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.clustering.session.container.servlet.examples;

import java.util.function.Function;

import jakarta.servlet.annotation.WebFilter;

/**
 * A request identifier filter that performs thread-safe reads/writes to the session using the monitor of the HttpSession.
 * This implementation may not safe for use by concurrent requests for the same session, depending on the container implementation.
 * @author Paul Ferraro
 */
@WebFilter(urlPatterns = "/*")
public class SessionMutexRequestIdentifierFilter extends MutexRequestIdentifierFilter {
	private static final long serialVersionUID = -642062907208910796L;

	/** Default constructor */
	public SessionMutexRequestIdentifierFilter() {
		super(Function.identity());
	}
}
