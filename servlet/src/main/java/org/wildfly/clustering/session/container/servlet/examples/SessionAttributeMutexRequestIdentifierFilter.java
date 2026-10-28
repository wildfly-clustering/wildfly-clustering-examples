/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.clustering.session.container.servlet.examples;

import java.io.Serial;

import jakarta.servlet.annotation.WebFilter;

/**
 * A request identifier filter that performs thread-safe reads/writes to the session using the monitor of an HttpSession attribute.
 * This implementation is safe for use by concurrent requests for the same session, on any spec-compliant servlet container.
 * @author Paul Ferraro
 */
@WebFilter(urlPatterns = "/*")
public class SessionAttributeMutexRequestIdentifierFilter extends MutexRequestIdentifierFilter {
	@Serial
	private static final long serialVersionUID = -2687109660865258448L;
	private static final String MUTEX_NAME = "x-request-id-mutex";

	/** Default constructor */
	public SessionAttributeMutexRequestIdentifierFilter() {
		super(session -> {
			Object mutex = session.getAttribute(MUTEX_NAME);
			// Should only be the case when the session is new
			// Otherwise, this put-if-absent logic would not be thread-safe
			if (mutex == null) {
				mutex = new byte[0];
				session.setAttribute(MUTEX_NAME, mutex);
			}
			return mutex;
		});
	}
}
