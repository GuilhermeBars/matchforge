package io.github.guilhermebars.matchforge;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Closed-loop REST write load. Each client owns a funded account and places expiring IOC orders. */
public final class LoadGenerator {
    private record Samples(long[] nanos, long successes, long errors) {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Usage: LoadGenerator <baseUrl> <clients> <seconds>");
        }
        URI base = URI.create(args[0]);
        int clients = Integer.parseInt(args[1]);
        int seconds = Integer.parseInt(args[2]);
        if (clients < 1 || clients > 1024 || seconds < 1 || seconds > 3600) {
            throw new IllegalArgumentException("clients must be 1..1024; seconds must be 1..3600");
        }
        String run = "load-" + UUID.randomUUID();
        try (HttpClient http = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .build();
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // Setup is excluded from measurement; unique IDs make successive runs independent.
            for (int i = 0; i < clients; i++) {
                String account = run + "-" + i;
                requireSuccess(send(http, base, "/api/v1/accounts", "{\"id\":\"" + account + "\"}"));
                requireSuccess(send(
                        http,
                        base,
                        "/api/v1/accounts/" + account + "/deposits",
                        "{\"asset\":\"USD\",\"amount\":\"1000\"}"));
            }
            CountDownLatch start = new CountDownLatch(1);
            long[] deadline = new long[1];
            List<Future<Samples>> futures = new ArrayList<>();
            for (int i = 0; i < clients; i++) {
                String account = run + "-" + i;
                futures.add(executor.submit(() -> {
                    start.await();
                    long[] samples = new long[1024];
                    int count = 0;
                    long successes = 0;
                    long errors = 0;
                    while (System.nanoTime() < deadline[0]) {
                        String body = "{\"accountId\":\"" + account + "\",\"clientOrderId\":\"c" + count
                                + "\",\"symbol\":\"BTC-USD\",\"side\":\"BUY\",\"type\":\"LIMIT\","
                                + "\"timeInForce\":\"IOC\",\"price\":\"0.01\",\"quantity\":\"0.00000001\"}";
                        long before = System.nanoTime();
                        try {
                            int status = send(http, base, "/api/v1/orders", body);
                            if (status >= 200 && status < 300) {
                                successes++;
                            } else {
                                errors++;
                            }
                        } catch (java.io.IOException e) {
                            errors++;
                        }
                        long elapsed = System.nanoTime() - before;
                        if (count == samples.length) {
                            samples = Arrays.copyOf(samples, Math.multiplyExact(samples.length, 2));
                        }
                        samples[count++] = elapsed;
                    }
                    return new Samples(Arrays.copyOf(samples, count), successes, errors);
                }));
            }
            long began = System.nanoTime();
            deadline[0] = began + Duration.ofSeconds(seconds).toNanos();
            start.countDown();
            List<Samples> results = new ArrayList<>();
            for (Future<Samples> future : futures) {
                results.add(future.get());
            }
            double elapsed = (System.nanoTime() - began) / 1e9;
            long[] latencies = results.stream()
                    .flatMapToLong(result -> Arrays.stream(result.nanos()))
                    .sorted()
                    .toArray();
            long successes = results.stream().mapToLong(Samples::successes).sum();
            long errors = results.stream().mapToLong(Samples::errors).sum();
            System.out.printf(
                    Locale.ROOT,
                    "clients=%d elapsed=%.3fs requests=%d successes=%d errors=%d throughput=%.3f req/s successful=%.3f req/s%n",
                    clients,
                    elapsed,
                    latencies.length,
                    successes,
                    errors,
                    latencies.length / elapsed,
                    successes / elapsed);
            System.out.printf(
                    Locale.ROOT,
                    "latency (all attempts): p50=%.3fms p95=%.3fms p99=%.3fms max=%.3fms%n",
                    percentile(latencies, 0.50),
                    percentile(latencies, 0.95),
                    percentile(latencies, 0.99),
                    percentile(latencies, 1));
            if (errors != 0) {
                throw new IllegalStateException("Load run contained " + errors + " failed requests");
            }
        }
    }

    private static int send(HttpClient http, URI base, String path, String body) throws Exception {
        return http.send(
                        HttpRequest.newBuilder(base.resolve(path))
                                .timeout(Duration.ofSeconds(5))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build(),
                        HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }

    private static void requireSuccess(int status) {
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("Account setup failed: HTTP " + status);
        }
    }

    private static double percentile(long[] sorted, double quantile) {
        return sorted.length == 0 ? Double.NaN : sorted[(int) Math.ceil(quantile * sorted.length) - 1] / 1e6;
    }
}
