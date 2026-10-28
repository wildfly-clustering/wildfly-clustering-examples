/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.clustering.session.container.servlet.examples;

import java.util.function.Function;

import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpSession;

/**
 * A request identifier filter that performs thread-safe reads/writes to the session using a mutex.
 * @author Paul Ferraro
 */
@WebFilter(urlPatterns = "/*")
public class MutexRequestIdentifierFilter extends RequestIdentifierFilter {
	private static final long serialVersionUID = -3559907348470584879L;

	MutexRequestIdentifierFilter(Function<HttpSession, ?> mutexProvider) {
		super(new RequestIdentifierManager() {
			private final RequestIdentifierManager manager = new IntegerRequestIdentifierManager();

			@Override
			public void recordInitialRequestIdentifier(HttpSession session, Integer requestId) {
				mutexProvider.apply(session);
				this.manager.recordInitialRequestIdentifier(session, requestId);
			}

			@Override
			public Integer getCurrentRequestIdentifier(HttpSession session) {
				Object mutex = mutexProvider.apply(session);
				if (mutex == null) return null;
				synchronized (mutex) {
					return this.manager.getCurrentRequestIdentifier(session);
				}
			}

			@Override
			public Integer getPreviousRequestIdentifier(HttpSession session) {
				return this.manager.getPreviousRequestIdentifier(session);
			}
		});
	}
}
