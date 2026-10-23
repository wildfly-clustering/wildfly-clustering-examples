/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.clustering.session.container.servlet.examples;

import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.http.HttpSession;

/**
 * Manages request identifiers with the HttpSession.
 * @author Paul Ferraro
 */
public interface RequestIdentifierManager {

	/**
	 * Returns the identifier of the current request
	 * @param session the current session
	 * @return the identifier of the current request
	 */
	Integer getCurrentRequestIdentifier(HttpSession session);

	/**
	 * Returns the identifier of the previous request
	 * @param session the current session
	 * @return the identifier of the previous request
	 */
	Integer getPreviousRequestIdentifier(HttpSession session);

	/**
	 * Records the identifier of the current request to the specified session.
	 * @param session a new session
	 * @param requestId the identifier of the current request
	 */
	void recordInitialRequestIdentifier(HttpSession session, Integer requestId);

	/** Implementation based on an immutable Integer. */
	class IntegerRequestIdentifierManager implements RequestIdentifierManager {
		IntegerRequestIdentifierManager() {
			// Do nothing
		}

		@Override
		public void recordInitialRequestIdentifier(HttpSession session, Integer requestId) {
			session.setAttribute(RequestIdentifierProvider.REQUEST_IDENTIFIER, requestId);
		}

		@Override
		public Integer getCurrentRequestIdentifier(HttpSession session) {
			Integer previousRequestId = this.getPreviousRequestIdentifier(session);
			Integer currentRequestId = previousRequestId + 1;
			session.setAttribute(RequestIdentifierProvider.REQUEST_IDENTIFIER, currentRequestId);
			return currentRequestId;
		}

		@Override
		public Integer getPreviousRequestIdentifier(HttpSession session) {
			return (Integer) session.getAttribute(RequestIdentifierProvider.REQUEST_IDENTIFIER);
		}
	}

	/** Implementation based on a mutable AtomicInteger. */
	class AtomicIntegerRequestIdentifierManager implements RequestIdentifierManager {
		private boolean resetAfterMutate;

		AtomicIntegerRequestIdentifierManager(boolean resetAfterMutate) {
			this.resetAfterMutate = resetAfterMutate;
		}

		@Override
		public void recordInitialRequestIdentifier(HttpSession session, Integer requestId) {
			session.setAttribute(RequestIdentifierProvider.REQUEST_IDENTIFIER, new AtomicInteger(requestId));
		}

		@Override
		public Integer getCurrentRequestIdentifier(HttpSession session) {
			AtomicInteger requestId = (AtomicInteger) session.getAttribute(RequestIdentifierProvider.REQUEST_IDENTIFIER);
			Integer result = (requestId != null) ? requestId.incrementAndGet() : null;
			if ((result != null) && this.resetAfterMutate) {
				session.setAttribute(RequestIdentifierProvider.REQUEST_IDENTIFIER, requestId);
			}
			return result;
		}

		@Override
		public Integer getPreviousRequestIdentifier(HttpSession session) {
			AtomicInteger requestId = (AtomicInteger) session.getAttribute(RequestIdentifierProvider.REQUEST_IDENTIFIER);
			return (requestId != null) ? requestId.get() : null;
		}
	}
}
