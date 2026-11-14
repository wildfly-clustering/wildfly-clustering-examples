/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.clustering.session.container.servlet.examples;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
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
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import jakarta.servlet.http.HttpServletResponse;

import org.wildfly.clustering.arquillian.Deployment;
import org.wildfly.clustering.function.Consumer;
import org.wildfly.clustering.function.Function;
import org.wildfly.clustering.function.IntUnaryOperator;
import org.wildfly.clustering.function.Predicate;
import org.wildfly.clustering.function.Runner;
import org.wildfly.clustering.function.Supplier;
import org.wildfly.clustering.junit.Tester;

/**
 * Tests the ability of a servlet container to retain consistency of a monotonically increasing request identifier persisted within a distributed session.
 * @author Paul Ferraro
 */
public class RequestIdentifierTester implements Tester {
	private static final Function<HttpResponse<Void>, Optional<String>> SESSION = response -> response.headers().firstValue(SessionServlet.SESSION);
	private static final Function<HttpResponse<Void>, OptionalLong> REQUEST_ID = response -> response.headers().firstValueAsLong(RequestIdentifierProvider.REQUEST_ID_KEY);
	private static final Consumer<HttpResponse<Void>> REPORTER = response -> System.out.println("Cookies: " + response.headers().allValues("Set-Cookie"));

	/** Encapsulates the configuration of this tester */
	interface Configuration {
		/**
		 * Returns the number of servers.
		 * @return the number of servers.
		 */
		int getServers();

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

		/**
		 * Returns true, if verbosity is enabled, false otherwise.
		 * @return true, if verbosity is enabled, false otherwise.
		 */
		boolean isVerbose();
	}

	private final IntUnaryOperator router;
	private final Runnable delay;
	private final int requests;
	private final Deque<Runnable> closeTasks = new LinkedList<>();
	private final List<Map.Entry<HttpClient, Executor>> clients;
	private final Executor executor;
	private final boolean verbose;

	public RequestIdentifierTester(Configuration configuration) {
		Duration interval = configuration.getInterval();
		this.delay = interval.isZero() ? Runner.of() : () -> {
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
			this.clients.add(Map.entry(HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build(), (clientExecutor != null) ? clientExecutor : Runnable::run));
		}
		Random random = new Random();
		double failoverRatio = configuration.getFailoverRatio();
		this.router = index -> ((random.nextDouble() + failoverRatio) >= 1d) ? random.nextInt(configuration.getServers()) : index;
		this.verbose = configuration.isVerbose();
	}

	interface ClientRecord {
		Map<String, Queue<Long>> getRequestIdentifiers();
		CompletableFuture<Duration> getDuration();
		AtomicInteger getClientFailures();
		Map<Integer, AtomicInteger> getResponseStatusCodes();
	}

	interface ClientStatistics {
		Duration getDuration();
		int getLostSessions();
		long getDirtyReads();
	}

	@Override
	public void accept(List<Deployment> deployments) {

		List<URI> uris = new ArrayList<>(deployments.size());
		for (Deployment deployment : deployments) {
			URI baseURI = Objects.requireNonNull(deployment.locate(SessionServlet.class));
			uris.add(baseURI.resolve(SessionServlet.NAME));
		}
		List<ClientRecord> clientRecords = new ArrayList<>(this.clients.size());
		for (int clientIndex = 0; clientIndex < this.clients.size(); ++clientIndex) {
			Map.Entry<HttpClient, Executor> clientEntry = this.clients.get(clientIndex);
			HttpClient client = clientEntry.getKey();
			Executor executor = clientEntry.getValue();
			ClientRecord clientRecord = new ClientRecord() {
				private final Map<String, Queue<Long>> requestIdentifiers = new ConcurrentHashMap<>();
				private final CompletableFuture<Duration> duration = new CompletableFuture<>();
				private final AtomicInteger clientFailures = new AtomicInteger(0);
				private final Map<Integer, AtomicInteger> responseStatusCodes = new ConcurrentHashMap<>();

				@Override
				public Map<String, Queue<Long>> getRequestIdentifiers() {
					return this.requestIdentifiers;
				}

				@Override
				public CompletableFuture<Duration> getDuration() {
					return this.duration;
				}

				@Override
				public AtomicInteger getClientFailures() {
					return this.clientFailures;
				}

				@Override
				public Map<Integer, AtomicInteger> getResponseStatusCodes() {
					return this.responseStatusCodes;
				}
			};
			clientRecords.add(clientRecord);
			// Simulate load balancing of initial (session establishing) request
			int initialServerIndex = clientIndex % uris.size();
			Iterator<URI> clientRouter = IntStream.iterate(initialServerIndex, this.router).mapToObj(uris::get).iterator();
			Function<String, Queue<Long>> queueFactory = session -> new ArrayBlockingQueue<>(this.requests);
			Function<Integer, AtomicInteger> responseCodeFactory = Function.of(Consumer.of(), AtomicInteger::new);
			Consumer<HttpResponse<Void>> recorder = response -> {
				clientRecord.getResponseStatusCodes().computeIfAbsent(response.statusCode(), responseCodeFactory).incrementAndGet();
				SESSION.apply(response).ifPresent(session -> {
					Queue<Long> queue = clientRecord.getRequestIdentifiers().computeIfAbsent(session, queueFactory);
					REQUEST_ID.apply(response).ifPresent(queue::add);
				});
			};
			URI initialURI = clientRouter.next();
			Runnable delay = this.delay;
			System.out.println(String.format("Client #%d establishing session on %s via %s", clientIndex + 1, deployments.get(initialServerIndex).getContainer().getName(), initialURI));
			this.executor.execute(() -> {
				Instant start = Instant.now();
				try {
					// Establish session
					recorder.andThen(REPORTER).accept(client.send(HttpRequest.newBuilder(initialURI).POST(BodyPublishers.noBody()).build(), BodyHandlers.discarding()));
					if (this.requests > 1) {
						Consumer<URI> request = target -> {
							try {
								recorder.accept(client.send(HttpRequest.newBuilder(target).GET().build(), BodyHandlers.discarding()));
							} catch (IOException e) {
								clientRecord.getClientFailures().incrementAndGet();
							} catch (InterruptedException e) {
								Thread.currentThread().interrupt();
								throw new CancellationException();
							}
						};
						AtomicInteger completed = new AtomicInteger(1);
						for (int i = 1; i < this.requests; ++i) {
							URI nextURI = clientRouter.next();
							CompletableFuture.runAsync(Runner.of(Supplier.of(nextURI), request), executor).whenComplete((ignore, exception) -> {
								if (completed.incrementAndGet() == this.requests) {
									clientRecord.getDuration().complete(Duration.between(start, Instant.now()));
								}
								if (exception != null) {
									exception.printStackTrace();
								}
							});
							delay.run();
						}
					}
				} catch (IOException e) {
					clientRecord.getDuration().completeExceptionally(e);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					clientRecord.getDuration().cancel(true);
				}
			});
		}

		List<ClientStatistics> statistics = new ArrayList<>(this.clients.size());
		for (ClientRecord clientRecord : clientRecords) {
			Duration duration = clientRecord.getDuration().join();
			Map<String, Queue<Long>> requestIdsPerSession = clientRecord.getRequestIdentifiers();
			// There should only be one entry - anything more indicates lost sessions
			int lostSessions = requestIdsPerSession.size() - 1;
			// Duplicate identifiers indicate a dirty read
			long dirtyReads = requestIdsPerSession.values().stream().mapToLong(queue -> queue.size() - queue.stream().distinct().count()).sum();
			statistics.add(new ClientStatistics() {
				@Override
				public Duration getDuration() {
					return duration;
				}

				@Override
				public int getLostSessions() {
					return lostSessions;
				}

				@Override
				public long getDirtyReads() {
					return dirtyReads;
				}
			});
		}

		List<List<CompletableFuture<Long>>> futureIds = new ArrayList<>(uris.size());
		for (URI uri : uris) {
			List<CompletableFuture<Long>> clientFutureIds = new ArrayList<>(this.clients.size());
			futureIds.add(clientFutureIds);
			for (Map.Entry<HttpClient, Executor> clientEntry : this.clients) {
				HttpClient client = clientEntry.getKey();
				CompletableFuture<Long> clientFutureId = new CompletableFuture<>();
				Consumer<HttpResponse<Void>> recorder = response -> clientFutureId.complete(REQUEST_ID.apply(response).orElse(0L));
				clientFutureIds.add(clientFutureId);
				client.sendAsync(HttpRequest.newBuilder(uri).method("HEAD", BodyPublishers.noBody()).build(), BodyHandlers.discarding())
						.orTimeout(30, TimeUnit.SECONDS)
						.thenAccept(recorder)
						.whenComplete((ignore, exception) -> {
							if (exception != null) {
								clientFutureId.completeExceptionally(exception);
							}
						});
			}
		}

		long expectedLastId = AbstractRequestIdentifierFilter.INITIAL_REQUEST_IDENTIFIER + this.requests - 1;
		List<List<Long>> lostWrites = new ArrayList<>(uris.size());
		for (List<CompletableFuture<Long>> clientFutureIds : futureIds) {
			List<Long> clientLostWrites = new ArrayList<>(clientFutureIds.size());
			lostWrites.add(clientLostWrites);
			for (int clientIndex = 0; clientIndex < this.clients.size(); ++clientIndex) {
				clientLostWrites.add(expectedLastId - clientFutureIds.get(clientIndex).join() - statistics.get(clientIndex).getDirtyReads());
			}
		}

		System.out.println("****************************************************************");
		if (this.verbose) {
			for (int clientIndex = 0; clientIndex < this.clients.size(); ++clientIndex) {
				System.out.println(String.format("Client #%d:", clientIndex + 1));
				ClientRecord clientRecord = clientRecords.get(clientIndex);
				System.out.println(String.format("\tExecution time: %s", clientRecord.getDuration().join()));
				Map<String, Queue<Long>> result = clientRecord.getRequestIdentifiers();
				for (Map.Entry<String, Queue<Long>> entry : result.entrySet()) {
					System.out.println(String.format("\tSession: %s", entry.getKey()));
					System.out.println(String.format("\t\tRecorded request IDs: %s", entry.getValue().stream().toList()));
					System.out.println(String.format("\t\t  Sorted request IDs: %s", entry.getValue().stream().sorted().toList()));
					System.out.println(String.format("\t\tExpected request IDs: %s", Arrays.toString(LongStream.iterate(AbstractRequestIdentifierFilter.INITIAL_REQUEST_IDENTIFIER, Math::incrementExact).limit(this.requests).toArray())));
				}
				if (uris.size() > 1) {
					System.out.println("\tLast request ID:");
					for (int serverIndex = 0; serverIndex < uris.size(); ++serverIndex) {
						System.out.println(String.format("\t\t%s: %s", deployments.get(serverIndex).getContainer().getName(), futureIds.get(serverIndex).get(clientIndex).join()));
					}
				}
				System.out.println(String.format("\tClient failures: %s", clientRecord.getClientFailures().get()));
				Predicate<Integer> unexpectedStatusCode = Predicate.equalTo(HttpServletResponse.SC_OK).negate();
				if (clientRecord.getResponseStatusCodes().keySet().stream().anyMatch(unexpectedStatusCode)) {
					System.out.println("\tUnexpected response status codes:");
					for (Map.Entry<Integer, AtomicInteger> entry : clientRecord.getResponseStatusCodes().entrySet()) {
						if (unexpectedStatusCode.test(entry.getKey())) {
							System.out.println(String.format("\t\t%d: %d", entry.getKey(), entry.getValue().get()));
						}
					}
				}
			}
			System.out.println("----------------------------------------------------------------");
		}
		System.out.println(String.format("Execution time:"));
		if (this.clients.size() > 1) {
			statistics.sort(Comparator.comparing(ClientStatistics::getDuration));
			System.out.println(String.format("\t   Mean: %s", (statistics.stream().map(ClientStatistics::getDuration).reduce(Duration::plus).orElse(Duration.ZERO).dividedBy(this.clients.size()))));
			System.out.println(String.format("\t Median: %s", statistics.get(this.clients.size() / 2).getDuration()));
			System.out.println(String.format("\tMinimum: %s", statistics.get(0).getDuration()));
			System.out.println(String.format("\tMaximum: %s", statistics.get(this.clients.size() - 1).getDuration()));
		} else {
			System.out.println(String.format("\t%s", statistics.get(0).getDuration()));
		}
		System.out.println("Lost sessions:");
		if (this.clients.size() > 1) {
			statistics.sort(Comparator.comparing(ClientStatistics::getLostSessions));
			System.out.println(String.format("\t   Mean: %d", (statistics.stream().mapToInt(ClientStatistics::getLostSessions).sum() / this.clients.size())));
			System.out.println(String.format("\t Median: %d", statistics.get(this.clients.size() / 2).getLostSessions()));
			System.out.println(String.format("\tMinimum: %d", statistics.get(0).getLostSessions()));
			System.out.println(String.format("\tMaximum: %d", statistics.get(this.clients.size() - 1).getLostSessions()));
		} else {
			System.out.println(String.format("\t%d", statistics.get(0).getLostSessions()));
		}
		System.out.println(String.format("Stale reads (out of %d):", this.requests));
		if (this.clients.size() > 1) {
			statistics.sort(Comparator.comparing(ClientStatistics::getDirtyReads));
			System.out.println(String.format("\t   Mean: %d", (statistics.stream().mapToLong(ClientStatistics::getDirtyReads).sum() / this.clients.size())));
			System.out.println(String.format("\t Median: %d", statistics.get(this.clients.size() / 2).getDirtyReads()));
			System.out.println(String.format("\tMinimum: %d", statistics.get(0).getDirtyReads()));
			System.out.println(String.format("\tMaximum: %d", statistics.get(this.clients.size() - 1).getDirtyReads()));
		} else {
			System.out.println(String.format("\t%d", statistics.get(0).getDirtyReads()));
		}
		// If deployed to a cluster, validate last request ID on each server
		if (uris.size() > 1) {
			for (int i = 0; i < lostWrites.size(); ++i) {
				List<Long> containerLostWrites = lostWrites.get(i);
				Collections.sort(containerLostWrites);
				System.out.println(String.format("Lost writes (out of %d): %s", this.requests, deployments.get(i).getContainer().getName()));
				if (this.clients.size() > 1) {
					System.out.println(String.format("\t   Mean: %d", (containerLostWrites.stream().mapToLong(Long::longValue).sum() / this.clients.size())));
					System.out.println(String.format("\t Median: %d", containerLostWrites.get(this.clients.size() / 2)));
					System.out.println(String.format("\tMinimum: %d", containerLostWrites.get(0)));
					System.out.println(String.format("\tMaximum: %d", containerLostWrites.get(this.clients.size() - 1)));
				} else {
					System.out.println(String.format("\t%d", containerLostWrites.get(0)));
				}
			}
		}
		System.out.println("****************************************************************");

		// Delete active sessions
		for (Map.Entry<HttpClient, Executor> clientEntry : this.clients) {
			HttpClient client = clientEntry.getKey();
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
