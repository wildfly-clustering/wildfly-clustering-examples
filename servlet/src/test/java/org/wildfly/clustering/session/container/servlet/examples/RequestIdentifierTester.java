/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.clustering.session.container.servlet.examples;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.assertj.core.api.Assertions;
import org.wildfly.clustering.arquillian.Deployment;
import org.wildfly.clustering.arquillian.Tester;
import org.wildfly.clustering.function.Consumer;
import org.wildfly.clustering.function.Function;
import org.wildfly.clustering.function.IntUnaryOperator;

/**
 * Tests the ability of a servlet container to retain consistency of a monotonically increasing request identifier persisted within a distributed session.
 * @author Paul Ferraro
 */
public class RequestIdentifierTester implements Tester {
	private static final Consumer<HttpResponse<Void>> VALIDATOR = response -> Assertions.assertThat(response.statusCode()).isEqualTo(HttpURLConnection.HTTP_OK);
	private static final Function<HttpResponse<Void>, Optional<String>> SESSION = response -> response.headers().firstValue(SessionServlet.SESSION);
	private static final Function<HttpResponse<Void>, OptionalLong> REQUEST_ID = response -> response.headers().firstValueAsLong(RequestIdentifierProvider.REQUEST_IDENTIFIER);
	private static final Consumer<HttpResponse<Void>> REPORTER = response -> System.out.println("Cookies: " + response.headers().allValues("Set-Cookie"));

	/** Encapsulates the configuration of this tester */
	interface Configuration {
		/**
		 * Returns the number of clients/sessions.
		 * @return the number of clients/sessions.
		 */
		int getClients();

		/**
		 * Returns the number of request invocations per client.
		 * @return the number of request invocations per client.
		 */
		int getRequests();

		/**
		 * Returns the number of concurrent request threads per client.
		 * @return the number of concurrent request threads per client.
		 */
		int getThreads();

		/**
		 * Returns the duration between client requests per thread.
		 * @return the duration between client requests per thread.
		 */
		Duration getInterval();

		/**
		 * Returns the ratio of requests that should failover to a random member rather than stick to the previous one.
		 * @return the ratio of requests that should failover to a random member rather than stick to the previous one.
		 */
		double getFailoverRatio();
	}

	private final IntUnaryOperator router;
	private final Consumer<CompletableFuture<Void>> handler;
	private final int requests;
	private final Deque<Runnable> closeTasks = new LinkedList<>();
	private final List<HttpClient> clients;
	private final Executor executor;

	public RequestIdentifierTester(Configuration configuration) {
		Duration interval = configuration.getInterval();
		this.handler = interval.isZero() ? Consumer.of() : future -> {
			try {
				TimeUnit.NANOSECONDS.sleep(interval.toNanos());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		};
		this.requests = configuration.getRequests();
		int clients = configuration.getClients();
		ExecutorService executor = (clients > 1) ? Executors.newFixedThreadPool(clients) : null;
		if (executor != null) {
			this.closeTasks.add(executor::shutdown);
		}
		this.executor = Optional.<Executor>ofNullable(executor).orElse(Runnable::run);
		this.clients = new ArrayList<>(clients);
		int threads = configuration.getThreads();
		for (int i = 0; i < clients; ++i) {
			ExecutorService clientExecutor = (threads > 0) ? Executors.newFixedThreadPool(threads) : null;
			if (clientExecutor != null) {
				this.closeTasks.add(clientExecutor::shutdown);
			}
			this.clients.add(HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).executor((clientExecutor != null) ? clientExecutor : Runnable::run).build());
		}
		Random random = new Random();
		double failoverRatio = configuration.getFailoverRatio();
		this.router = index -> ((random.nextDouble() + failoverRatio) >= 1) ? random.nextInt(Integer.MAX_VALUE) : index;
	}

	@Override
	public void accept(List<Deployment> deployments) {

		List<URI> uris = new ArrayList<>(deployments.size());
		for (Deployment deployment : deployments) {
			URI baseURI = Objects.requireNonNull(deployment.locate(SessionServlet.class));
			uris.add(baseURI.resolve(SessionServlet.NAME));
		}

		int queueSize = this.requests + 1;
		List<Map.Entry<Map<String, Queue<Long>>, CompletableFuture<Duration>>> futureResults = new ArrayList<>(this.clients.size());
		for (int clientIndex = 0; clientIndex < this.clients.size(); ++clientIndex) {
			HttpClient client = this.clients.get(clientIndex);
			// Simulate load balancing of session establishing request
			int startTargetIndex = clientIndex % uris.size();
			Iterator<URI> router = IntStream.iterate(startTargetIndex, this.router).mapToObj(index -> uris.get(index % uris.size())).iterator();
			Map<String, Queue<Long>> responses = new ConcurrentHashMap<>();
			Function<String, Queue<Long>> queueFactory = session -> new ArrayBlockingQueue<>(queueSize);
			Consumer<HttpResponse<Void>> recorder = response -> SESSION.apply(response).ifPresent(session -> {
				Queue<Long> queue = responses.computeIfAbsent(session, queueFactory);
				REQUEST_ID.apply(response).ifPresent(queue::add);
			});
			Consumer<HttpResponse<Void>> processor = VALIDATOR.andThen(recorder);
			CompletableFuture<Duration> future = new CompletableFuture<>();
			futureResults.add(Map.entry(responses, future));
			URI uri = router.next();
			System.out.println(String.format("Client #%d establishing session on %s via %s", clientIndex + 1, deployments.get(startTargetIndex).getContainer().getName(), uri));
			this.executor.execute(() -> {
				Instant start = Instant.now();
				try {
					processor.andThen(REPORTER).accept(client.send(HttpRequest.newBuilder(uri).POST(BodyPublishers.noBody()).build(), BodyHandlers.discarding()));
					if (this.requests > 0) {
						AtomicInteger completed = new AtomicInteger(0);
						for (int i = 0; i < this.requests; ++i) {
							this.handler.accept(client.sendAsync(HttpRequest.newBuilder(router.next()).GET().build(), BodyHandlers.discarding())
									.orTimeout(30, TimeUnit.SECONDS)
									.thenAccept(processor)
									.whenComplete((ignore, exception) -> {
										if (exception != null) {
											future.completeExceptionally(exception);
										} else if (completed.incrementAndGet() == this.requests) {
											future.complete(Duration.between(start, Instant.now()));
										}
									}));
						}
					}
				} catch (IOException e) {
					future.completeExceptionally(e);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					future.cancel(true);
				}
			});
		}

		List<Integer> lostSessions = new ArrayList<>(this.clients.size());
		List<Long> lostAttributes = new ArrayList<>(this.clients.size());
		List<Long> dirtyReads = new ArrayList<>(this.clients.size());
		List<Duration> durations = new ArrayList<>(this.clients.size());
		for (Map.Entry<Map<String, Queue<Long>>, CompletableFuture<Duration>> futureRequestIdsPerSession : futureResults) {
			durations.add(futureRequestIdsPerSession.getValue().join());
			Map<String, Queue<Long>> requestIdsPerSession = futureRequestIdsPerSession.getKey();
			// There should only be one entry - anything more indicates lost sessions
			lostSessions.add(requestIdsPerSession.size() - 1);
			// There should only be one initial id in each queue - anything more indicates a lost session attribute
			lostAttributes.add(requestIdsPerSession.values().stream().mapToLong(queue -> queue.stream().filter(Long.valueOf(RequestIdentifierFilter.INITIAL_REQUEST_IDENTIFIER.longValue())::equals).count() - 1).sum());
			// Duplicate identifiers indicate a dirty read
			dirtyReads.add(requestIdsPerSession.values().stream().mapToLong(queue -> queue.size() - queue.stream().distinct().count()).sum());
		}
		Collections.sort(dirtyReads);
		Collections.sort(durations);

		List<List<CompletableFuture<Long>>> futureIds = new ArrayList<>(uris.size());
		for (URI uri : uris) {
			List<CompletableFuture<Long>> clientFutureIds = new ArrayList<>(this.clients.size());
			futureIds.add(clientFutureIds);
			for (HttpClient client : this.clients) {
				CompletableFuture<Long> clientFutureId = new CompletableFuture<>();
				Consumer<HttpResponse<Void>> recorder = response -> clientFutureId.complete(REQUEST_ID.apply(response).orElse(0L));
				Consumer<HttpResponse<Void>> processor = VALIDATOR.andThen(recorder);
				clientFutureIds.add(clientFutureId);
				client.sendAsync(HttpRequest.newBuilder(uri).method("HEAD", BodyPublishers.noBody()).build(), BodyHandlers.discarding())
						.orTimeout(30, TimeUnit.SECONDS)
						.thenAccept(processor)
						.whenComplete((ignore, exception) -> {
							if (exception != null) {
								clientFutureId.completeExceptionally(exception);
							}
						});
			}
		}

		long expectedLastId = RequestIdentifierFilter.INITIAL_REQUEST_IDENTIFIER + this.requests;
		List<List<Long>> lostWrites = new ArrayList<>(uris.size());
		for (List<CompletableFuture<Long>> clientFutureIds : futureIds) {
			List<Long> clientLostWrites = new ArrayList<>(clientFutureIds.size());
			lostWrites.add(clientLostWrites);
			for (CompletableFuture<Long> futureId : clientFutureIds) {
				clientLostWrites.add(expectedLastId - futureId.join());
			}
			Collections.sort(clientLostWrites);
		}

		System.out.println("****************************************************************");
		System.out.println("Test duration:");
		if (this.clients.size() > 1) {
			System.out.println("\t   Mean: " + (durations.stream().reduce(Duration::plus).orElse(Duration.ZERO).dividedBy(this.clients.size())));
			System.out.println("\t Median: " + durations.get(this.clients.size() / 2));
			System.out.println("\tMinimum: " + durations.get(0));
			System.out.println("\tMaximum: " + durations.get(this.clients.size() - 1));
		} else {
			System.out.println("\t" + durations.get(0));
		}
		System.out.println("Lost sessions:");
		if (this.clients.size() > 1) {
			System.out.println("\t   Mean: " + (lostSessions.stream().mapToInt(Integer::intValue).sum() / this.clients.size()));
			System.out.println("\t Median: " + lostSessions.get(this.clients.size() / 2));
			System.out.println("\tMinimum: " + lostSessions.get(0));
			System.out.println("\tMaximum: " + lostSessions.get(this.clients.size() - 1));
		} else {
			System.out.println("\t" + lostSessions.get(0));
		}
		System.out.println("Lost session attributes:");
		if (this.clients.size() > 1) {
			System.out.println("\t   Mean: " + (lostAttributes.stream().mapToLong(Long::longValue).sum() / this.clients.size()));
			System.out.println("\t Median: " + lostAttributes.get(this.clients.size() / 2));
			System.out.println("\tMinimum: " + lostAttributes.get(0));
			System.out.println("\tMaximum: " + lostAttributes.get(this.clients.size() - 1));
		} else {
			System.out.println("\t" + lostAttributes.get(0));
		}
		System.out.println(String.format("Dirty reads (out of %d):", this.requests));
		if (this.clients.size() > 1) {
			System.out.println("\t   Mean: " + (dirtyReads.stream().mapToLong(Long::longValue).sum() / this.clients.size()));
			System.out.println("\t Median: " + dirtyReads.get(this.clients.size() / 2));
			System.out.println("\tMinimum: " + dirtyReads.get(0));
			System.out.println("\tMaximum: " + dirtyReads.get(this.clients.size() - 1));
		} else {
			System.out.println("\t" + dirtyReads.get(0));
		}
		for (int i = 0; i < lostWrites.size(); ++i) {
			List<Long> containerLostWrites = lostWrites.get(i);
			System.out.println(String.format("Lost writes (out of %d): %s", this.requests, deployments.get(i).getContainer().getName()));
			if (this.clients.size() > 1) {
				System.out.println("\t   Mean: " + (containerLostWrites.stream().mapToLong(Long::longValue).sum() / this.clients.size()));
				System.out.println("\t Median: " + containerLostWrites.get(this.clients.size() / 2));
				System.out.println("\tMinimum: " + containerLostWrites.get(0));
				System.out.println("\tMaximum: " + containerLostWrites.get(this.clients.size() - 1));
			} else {
				System.out.println("\t" + containerLostWrites.get(0));
			}
		}
		System.out.println("****************************************************************");

		// Delete active sessions
		for (HttpClient client : this.clients) {
			try {
				client.send(HttpRequest.newBuilder(uris.get(0)).DELETE().build(), BodyHandlers.discarding());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (IOException e) {
				e.printStackTrace(System.err);
			}
		}
	}

	@Override
	public void close() {
		this.closeTasks.descendingIterator().forEachRemaining(Runnable::run);
	}
}
