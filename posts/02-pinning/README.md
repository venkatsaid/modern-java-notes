# The Keyword That Quietly Pins Your Virtual Threads

*Modern Java, Explained Like We're Pairing · #2 · Java 21 (and what changed in 24)*

---

You flipped on virtual threads. Load test day comes around, and… throughput is *worse* than before. No exceptions, no errors in the logs. Requests just hang for a while and then finish.

I want you to look at this method and tell me what's wrong with it. Take your time.

```java
private final Object monitor = new Object();

void updateBalance() {
    synchronized (monitor) {
        ledgerClient.post(entry);   // a blocking HTTP call, ~50 ms
    }
}
```

Looks fine, right? It's thread-safe and it's the kind of code we've written for twenty years.

On Java 21, with virtual threads, this is the code that can bring your service to a crawl. In this post we'll see *why*, catch it happening with a tool that's already inside your JDK, and fix it with a three-line change. We'll also look at why Java 24 changed the rules, because the right answer depends on your version.

---

## Quick recap: passengers and cars

In [post #1](../01-how-virtual-threads-work/README.md) we built a mental model:

- **Virtual threads** are passengers: cheap, millions of them.
- **Carrier threads** are cars: real OS threads, about one per CPU core.
- When a passenger has to wait, it **unmounts** (gets out), and the car picks up someone else.

Everything good about virtual threads depends on that "gets out" step. So here's the question for this post: *what if a passenger can't get out?*

## Pinning: the passenger who won't leave the car

When a virtual thread blocks but **cannot** unmount, we say it is **pinned** to its carrier. The car is stuck with the meter running, exactly like an old platform thread.

On Java 21, two situations pin a virtual thread:

1. It blocks **inside a `synchronized` block or method**.
2. It blocks while inside a **native method or foreign function** call.

The first one is the trap, because `synchronized` is everywhere: in your code, in older libraries, in JDBC drivers, in caches.

Now do the maths with me. My test box has **2** carriers. If 2 requests are pinned on a 50 ms call inside `synchronized`, *every other virtual thread in the JVM* waits for a car. You haven't run out of threads, you've run out of carriers, and it looks just like the thread-pool starvation virtual threads were supposed to end.

> Virtual threads don't remove your bottleneck. Pinning moves it to the one place you're not looking: the carriers.

## Catching it red-handed with JFR

Guessing is no fun, so let's measure. The JDK ships with **Java Flight Recorder**, and on Java 21 it emits an event called **`jdk.VirtualThreadPinned`** whenever a virtual thread blocks while pinned for longer than a threshold (20 ms by default).

We can record it from a plain test, no agent and no extra dependency:

```java
try (Recording recording = new Recording()) {
    recording.enable("jdk.VirtualThreadPinned").withThreshold(Duration.ofMillis(10));
    recording.start();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        for (int i = 0; i < 5; i++) executor.submit(this::blockInsideSynchronized);
    }

    recording.stop();
    recording.dump(file);
}
long pinned = RecordingFile.readAllEvents(file).stream()
        .filter(e -> e.getEventType().getName().equals("jdk.VirtualThreadPinned"))
        .count();
```

Five virtual threads, each sleeping 50 ms inside `synchronized`. Before you look at the output, how many pinned events would you expect?

```text
synchronized: 5 pinned events
```

Every single one. Each event even carries the stack trace, so in a real app you can point straight at the offending line.

Two more ways to find pinning on Java 21:

- Start the JVM with **`-Djdk.tracePinnedThreads=full`** and the JDK prints a stack trace whenever a thread blocks while pinned. It's handy in dev, too noisy for production. (Heads up: this flag was removed in later versions.)
- In production, keep a JFR recording running and alert on `jdk.VirtualThreadPinned`.

## The fix: use a lock the JDK can unmount from

Here's the same method, swapping `synchronized` for a `ReentrantLock`:

```java
private final ReentrantLock lock = new ReentrantLock();

void updateBalance() {
    lock.lock();
    try {
        ledgerClient.post(entry);   // now the virtual thread can unmount here
    } finally {
        lock.unlock();
    }
}
```

Same test, same five threads, same 50 ms of blocking:

```text
ReentrantLock: 0 pinned events
```

Zero. The `java.util.concurrent` locks are built on top of `LockSupport.park`, which knows how to unmount a virtual thread. `synchronized` is implemented deep in the JVM's object monitors, which on Java 21 tie the lock to the carrier thread.

Two rules of thumb before you go find-and-replace your codebase:

- **Only blocking inside matters.** A `synchronized` block that increments a counter and returns in nanoseconds is harmless. Look for I/O, `sleep`, `wait()`, or a call to another service *inside* the lock.
- **Better still, shrink the lock.** Often the real fix is to do the slow call *outside* the critical section and lock only the in-memory update.

## Plot twist: Java 24 changed the rules

Here's the part that will make you sound like you actually read the JEPs.

**JEP 491**, delivered in **Java 24**, reworked how the JVM implements monitors so that virtual threads can acquire, hold and release them *independently of their carrier*. In plain words: on Java 24 and later, blocking inside `synchronized` **no longer pins**.

So what's the right advice?

- **On Java 21 (still the most common LTS in production):** pinning from `synchronized` is real. Find it with JFR and fix the hot spots.
- **On Java 24+ (including the Java 25 LTS):** `synchronized` is fine again. Pinning can still happen when native code calls back into Java and blocks, and `jdk.VirtualThreadPinned` is still there to catch it.

That's also why the test in the companion repo only *asserts* pinning when it runs on a version below 24. Code that knows its own JDK version beats a blog post that's quietly out of date.

## Try it yourself

```bash
git clone https://github.com/venkatsaid/modern-java-notes
cd modern-java-notes
mvn test -Dtest=PinningTest
```

Then experiment:

- Drop the sleep to 1 ms. Do you still see events? (Think about the threshold.)
- Run it on Java 24 or 25 and compare the counts.

## Wrapping up

On Java 21, a virtual thread that blocks inside `synchronized` pins its carrier, and a few pinned threads can starve a whole application. JFR's `jdk.VirtualThreadPinned` event shows you exactly where it happens, and a `ReentrantLock` (or a smaller lock) fixes it. On Java 24+, JEP 491 removes this trap for `synchronized`.

Your next step: turn on a short JFR recording during your next load test and search for `VirtualThreadPinned`. You might be surprised which library shows up.

Which version are you running virtual threads on in production: 21, or have you already moved to 25?

---

**TL;DR**
- On Java 21, blocking inside `synchronized` pins a virtual thread to its carrier.
- In my test: 5 pinned events with `synchronized`, 0 with `ReentrantLock`, measured with JFR.
- Java 24's JEP 491 removed `synchronized` pinning; native callbacks can still pin.

**Tags:** Java, Concurrency, Programming, Software Engineering, Performance
