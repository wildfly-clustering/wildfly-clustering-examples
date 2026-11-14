/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.clustering.session.container.servlet.examples;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A request identifier filter managed as a mutable AtomicInteger.
 * @author Paul Ferraro
 */
public abstract class AbstractAtomicIntegerRequestIdentifierFilter extends AbstractRequestIdentifierFilter<AtomicInteger> {
	private static final long serialVersionUID = 2933347474895361068L;

	AbstractAtomicIntegerRequestIdentifierFilter() {
		super(AtomicInteger::new);
	}

	@Override
	Integer next(AtomicInteger previousRequestId) {
		return previousRequestId.incrementAndGet();
	}

	@Override
	Integer current(AtomicInteger requestId) {
		return requestId.get();
	}
}
