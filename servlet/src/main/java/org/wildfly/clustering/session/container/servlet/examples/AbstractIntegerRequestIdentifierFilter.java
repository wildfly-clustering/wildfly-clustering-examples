/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.clustering.session.container.servlet.examples;

import java.util.function.UnaryOperator;

import jakarta.servlet.http.HttpSession;

/**
 * A request identifier filter managed as an immutable Integer.
 * @author Paul Ferraro
 */
public abstract class AbstractIntegerRequestIdentifierFilter extends AbstractRequestIdentifierFilter<Integer> {
	private static final long serialVersionUID = -3695484296146028028L;

	AbstractIntegerRequestIdentifierFilter() {
		super(UnaryOperator.identity());
	}

	@Override
	Integer next(Integer previousRequestId) {
		return previousRequestId + 1;
	}

	@Override
	Integer current(Integer requestId) {
		return requestId;
	}

	@Override
	Integer getCurrentRequestId(HttpSession session) {
		Integer requestId = super.getCurrentRequestId(session);
		this.writeRequestId(session, requestId);
		return requestId;
	}
}
