/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.clustering.session.container.servlet.examples;

import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.http.HttpSession;

/**
 * A servlet filter that provides a unique identifier to requests for a given session via a mutable AtomicInteger session attribute.
 * This implementation is safe for use by concurrent requests for the same session, on any spec-compliant servlet container.
 * @author Paul Ferraro
 */
public class AtomicIntegerResetRequestIdentifierFilter extends AbstractAtomicIntegerRequestIdentifierFilter {
	private static final long serialVersionUID = 2933347474895361068L;

	/** Default constructor */
	public AtomicIntegerResetRequestIdentifierFilter() {
		// Do nothing
	}

	@Override
	public Integer getCurrentRequestId(HttpSession session) {
		AtomicInteger requestId = this.readRequestId(session);
		Integer currentRequestId = this.next(requestId);
		// Some distributed servlet containers require a redundant to HttpSession.setAttribute(...) when an an attribute is mutated, even though the object reference has not changed!
		this.writeRequestId(session, requestId);
		return currentRequestId;
	}
}
