# 10,000 Threads, One Second: What a Virtual Thread Really Does

*Modern Java, Explained Like We're Pairing · #1 · Java 21*

---

Quick question before we start. Don't scroll, just guess.

You submit 10,000 tasks. Each one does nothing but sleep for one second. How long until all of them finish?

```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    IntStream.range(0, 10_000).forEach(i -> executor.submit(() -> {
        Thread.sleep(Duration.ofSeconds(1));
        return i;
    }));
}
```

Got a number? Hold on to it.

On my 2-core test box, this finished in **1,120 milliseconds**. Not 10,000 seconds. Not even 10. About one second, which is the time a *single* task takes.

If your guess was "it depends on the thread pool size", you're thinking like most of us were trained to think. And that's exactly the instinct this post is going to retrain. By the end, you'll know what happens inside the JVM when a virtual thread blocks, why that makes the snippet above cheap, and the one mental model that tells you when virtual threads help and when they don't.

---

## First, why would this normally be slow?

Let's rewind to the world you probably write code in today.

A classic Java thread (now called a **platform thread**) is a thin wrapper around an operating system thread. The OS gives it a real stack, often around a megabyte reserved. When your code calls `Thread.sleep`, or waits on a database, or reads from a socket, that OS thread just… sits there. Blocked. Holding its memory. Doing nothing useful.

So we pool them. 200 threads in Tomcat, 50 in an executor, and we try very hard not to block them. If 10,000 requests each wait one second on a slow API, a 200-thread pool needs about 50 seconds to get through them all.

That's the whole reason reactive frameworks exist. They avoid blocking by chaining callbacks, and you pay for it in code that's hard to read, hard to debug and hard to hire for.

Here's the question Java 21 asks instead: *what if blocking were cheap?*

## The mental model: tasks are passengers, carriers are cars

Picture a ride-share company with only two cars, which is how many cores my test box has.

- **Virtual threads are passengers.** You can have millions of them. They're cheap: a small object on the heap, not a reserved OS stack.
- **Carrier threads are the cars.** They're ordinary platform threads in a `ForkJoinPool`, roughly one per CPU core.
- A passenger only occupies a car while actually **riding**, meaning running Java code.

Now, what happens when a passenger needs to wait 10 minutes for a friend?

In the old world, the passenger keeps the car and the meter running. In Java 21, the passenger **gets out**. The car drives off and picks someone else up. When the friend arrives, the passenger hops into whichever car is free next.

That "getting out" has a name: the virtual thread **unmounts** from its carrier. Getting back in is **mounting**. Hold on to those two words; we'll keep using them throughout the series.

> A virtual thread doesn't make waiting faster. It makes waiting free.

## Let's prove there really are only a few cars

Don't take my word for the analogy. Let's ask the JVM which carrier each virtual thread ran on. A mounted virtual thread prints its carrier in `toString()`:

```java
Set<String> carriers = ConcurrentHashMap.newKeySet();

try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    for (int i = 0; i < 1_000; i++) {
        executor.submit(() -> {
            // e.g. VirtualThread[#42]/runnable@ForkJoinPool-1-worker-1
            String s = Thread.currentThread().toString();
            carriers.add(s.substring(s.indexOf('@') + 1));
        });
    }
}
System.out.println(carriers);
```

Before you run it: how many distinct carriers do you expect to see for 1,000 virtual threads?

Here's what I got:

```text
1,000 virtual threads ran on 2 carriers: [ForkJoinPool-1-worker-1, ForkJoinPool-1-worker-2]
```

Two cars, a thousand passengers. That's the whole trick, sitting in plain sight.

## So what actually happens at `Thread.sleep`?

Let's slow down and walk through one virtual thread hitting a blocking call, step by step:

1. Your task runs on carrier `worker-1`, executing ordinary Java code.
2. It calls `Thread.sleep(1s)`, or a JDBC query, or `socket.read()`.
3. The JDK sees that a virtual thread is about to block. Instead of blocking the carrier, it **saves the virtual thread's stack frames to the heap** (internally, a *continuation*).
4. The carrier is now free and immediately runs another waiting virtual thread.
5. When the sleep ends or the data arrives, the virtual thread is **scheduled again**, possibly on `worker-2` this time, and its stack is restored.
6. Your code continues on the next line as if nothing happened.

That's why the 10,000-sleep demo takes one second. All 10,000 virtual threads park almost instantly. Two carriers spend that second mostly idle, then wake everyone up.

And notice what you *didn't* write: no callbacks, no `Mono`, no `thenCompose`. Plain blocking code, top to bottom.

## Now the part most posts skip: when this does not help

Here's a quick quiz. Which of these gets faster with virtual threads?

- **A)** A service that waits 200 ms on three downstream APIs per request
- **B)** A batch job that resizes 50,000 images in memory
- **C)** A report that runs one huge SQL query

Answer: **only A**.

Virtual threads help when you have **many tasks spending most of their time waiting**. They don't add CPUs, so image resizing (B) is exactly as fast as before. And one big query (C) is one task, not many.

Three more things worth knowing before you switch anything on:

- **Don't pool them.** A pool exists to ration expensive things, and virtual threads aren't expensive. Create one per task with `newVirtualThreadPerTaskExecutor()`. If a downstream system can only take 20 calls at a time, guard *it* with a `Semaphore`; that's post #3 in this series.
- **They're always daemon threads.** `Thread.ofVirtual().unstarted(...).isDaemon()` is `true`, so they won't keep the JVM alive on their own.
- **There's one way to keep the passenger stuck in the car.** In Java 21, blocking inside a `synchronized` block means the virtual thread can't unmount; it **pins** its carrier. With only a handful of carriers, a few pinned threads can stall everything. That's the next post, and we'll catch it red-handed with JFR.

## Try it yourself

Every snippet in this post is a passing test in the companion repo:

```bash
git clone https://github.com/venkatsaid/modern-java-notes
cd modern-java-notes
mvn test -Dtest=VirtualThreadBasicsTest
```

Then play. Change the sleep to a CPU loop, say summing to a billion, and watch the "free waiting" benefit disappear. That one experiment teaches the boundary better than any diagram.

## Wrapping up

A virtual thread is a cheap passenger that gets out of the car whenever it has to wait, so a few carrier threads can serve thousands of blocked tasks. Use them for waiting-heavy work, create one per task, and don't expect them to make CPU work faster.

Your next step: find one place in your codebase where a fixed thread pool exists only to "not block too much". That's your first candidate.

Now I'm curious. What's the blocking call in your system that you'd most like to stop working around?

---

**TL;DR**
- Virtual threads unmount from their carrier when they block, so waiting costs almost nothing.
- 10,000 one-second sleeps finished in about 1.1 s on 2 carriers in my test.
- They speed up waiting-heavy work, not CPU-heavy work. Don't pool them, and watch for pinning.

**Tags:** Java, Programming, Software Development, Concurrency, Backend Development
