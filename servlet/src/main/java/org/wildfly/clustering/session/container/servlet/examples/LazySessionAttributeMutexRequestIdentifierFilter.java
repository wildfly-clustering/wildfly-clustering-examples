/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.clustering.session.container.servlet.examples;

import java.io.Serial;

import jakarta.servlet.ServletContextListener;
import jakarta.servlet.http.HttpSession;

/**
 * A request identifier filter that performs thread-safe reads/writes to the session using the monitor of an HttpSession attribute.
 * However, the put-if-absent logic of the mutex itself is not thread-safe, and can result in a stale read for the first requests for a given session.
 * @author Paul Ferraro
 */
public class LazySessionAttributeMutexRequestIdentifierFilter extends AbstractIntegerRequestIdentifierFilter implements ServletContextListener {
	@Serial
	private static final long serialVersionUID = -2687109660865258448L;
	private static final String REQUEST_ID_MUTEX = "x-request-id-mutex";

	/** Default constructor */
	public LazySessionAttributeMutexRequestIdentifierFilter() {
		// Do nothing
	}

	@Override
	Integer getCurrentRequestId(HttpSession session) {
		Object mutex = session.getAttribute(REQUEST_ID_MUTEX);
		if (mutex == null) {
			mutex = new byte[0];
			session.setAttribute(REQUEST_ID_MUTEX, mutex);
		}
		synchronized (mutex) {
			return super.getCurrentRequestId(session);
		}
	}
}
