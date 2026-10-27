# Virtual threads

## What it is

A **thread** is a path of execution inside a program; the operating system schedules threads. Normally a Java thread is heavy (about a megabyte of stack) and a server can have only a few thousand. A **virtual thread** (Java 21 and later) is a thread managed by the JVM, not the operating system. It is cheap enough to have a million. When a virtual thread waits for something (a network answer, `Thread.sleep`), the JVM parks it and lets the underlying operating-system thread run another virtual thread.

## Why this repository uses it

The price service makes blocking calls to a provider that may take two seconds each. With ordinary threads, a few hundred slow calls could use all the request threads, and even endpoints that do not call the provider would stop answering. With virtual threads, waiting is cheap, so the catalog endpoint keeps its latency while the provider is sick. The fake provider and the load generator use virtual threads for the same reason.

## Where it appears

| Path | Role |
| --- | --- |
| `application.properties` | `spring.threads.virtual.enabled=true`: each web request runs on a virtual thread |
| `provider/.../FakeProvider.java` | `Executors.newVirtualThreadPerTaskExecutor()` serves every request on a new virtual thread |
| `loadgen/.../LoadRunner.java` | one virtual thread per outgoing request |
| `ResilienceAnnotationsTest.java`, `PriceServiceBehaviorIT.java` | launch 20 or more concurrent calls cheaply |

## How it works underneath

1. A **carrier thread** is a real operating-system thread. The JVM mounts a virtual thread on a carrier to run it, and unmounts it when it blocks.
2. Code does not change: blocking calls such as `Thread.sleep` or reading from a socket still look blocking. The JVM does the parking.
3. Virtual threads do not make a single call faster. They make waiting for many calls affordable.
4. They do not remove limits elsewhere: the provider, a database pool or a rate limit still cap throughput. That is why the service also has a concurrency limit.
5. An `ExecutorService` created with `newVirtualThreadPerTaskExecutor()` starts a fresh virtual thread for every task; closing it in a try-with-resources waits for the tasks.

## Walkthrough of this repository's use

```properties
spring.threads.virtual.enabled=true
```

With this property Spring Boot makes Tomcat run each request on a virtual thread.

```java
server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
```

The provider serves every request on its own virtual thread. A `HANG` request sleeps for 10 seconds on its virtual thread and costs almost nothing.

```java
try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
    for (int number = 0; number < total; number++) {
        sleepUntil(startNanos + number * (1_000_000_000L / requestsPerSecond));
        URI uri = urlForRequest.apply(number);
        pool.submit(() -> send(uri, latencies, errors, stale));
    }
    pool.shutdown();
    pool.awaitTermination(REQUEST_TIMEOUT.toSeconds() + 5, TimeUnit.SECONDS);
```

The load generator submits one task per request to a virtual-thread executor, pacing the submissions at a fixed rate. A thousand requests waiting on a hung service are a thousand parked virtual threads.

And in a test:

```java
try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
    List<Future<?>> calls = new ArrayList<>();
    for (int call = 0; call < 20; call++) {
        calls.add(
                pool.submit(
                        () -> {
                            slow.blockingCall();
                            return null;
                        }));
    }
    for (Future<?> call : calls) {
        call.get();
    }
}
```

Twenty virtual threads call the limited method at once.

## Try it yourself

1. In `jshell`: `Thread.startVirtualThread(() -> System.out.println(Thread.currentThread()))` prints something like `VirtualThread[#...]/runnable@ForkJoinPool-1-worker-1`.
2. Start 100,000 sleeping virtual threads: `try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) { for (int i = 0; i < 100_000; i++) pool.submit(() -> { Thread.sleep(1000); return 0; }); }` finishes in a couple of seconds. Try the same with `newFixedThreadPool(100)` and compare.
3. With the provider hanging and a burst of requests running, `curl -s -o /dev/null -w '%{time_total}\n' localhost:18104/skus/sku-1` is still fast.

## Mistakes people make

- Thinking virtual threads speed up CPU work: they help waiting, not computing.
- Pooling virtual threads: create one per task; do not reuse them.
- Holding a lock (`synchronized`) while blocking in older JDKs: it can pin the carrier. JDK 24 and later removed most pinning for `synchronized`.
- Forgetting other limits (database connections) still exist.

## Check yourself

1. What is a carrier thread? *A real OS thread that runs virtual threads.*
2. What happens to a virtual thread that blocks on the network? *It is parked and the carrier runs another one.*
3. Do virtual threads make one slow call faster? *No.*
4. Which setting turns them on for web requests? *`spring.threads.virtual.enabled=true`.*
5. Why is a concurrency limit still needed? *The provider cannot take unlimited calls.*

## Further reading

- JEP 444, Virtual Threads: https://openjdk.org/jeps/444
- Spring Boot 4.1.2, virtual threads: https://docs.spring.io/spring-boot/4.1.2/reference/features/task-execution-and-scheduling.html#features.task-execution-and-scheduling.virtual-threads
