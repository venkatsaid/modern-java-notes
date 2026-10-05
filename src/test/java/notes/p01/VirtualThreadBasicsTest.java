package notes.p01;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Post #1 — "What actually happens when a virtual thread blocks?"
 */
class VirtualThreadBasicsTest {

    @Test
    void tenThousandSleepingTasksFinishInAboutOneSecond() {
        Instant start = Instant.now();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            IntStream.range(0, 10_000).forEach(i -> executor.submit(() -> {
                Thread.sleep(Duration.ofSeconds(1));   // blocking call: the virtual thread unmounts here
                return i;
            }));
        } // close() waits for every task

        Duration took = Duration.between(start, Instant.now());
        System.out.println("10,000 blocking tasks took " + took.toMillis() + " ms");
        assertTrue(took.toSeconds() < 5, "should take ~1s, not 10,000s");
    }

    @Test
    void aVirtualThreadKnowsWhatItIs() throws Exception {
        Thread vt = Thread.ofVirtual().name("payment-1").unstarted(() -> {});
        assertTrue(vt.isVirtual());
        assertTrue(vt.isDaemon(), "virtual threads are always daemon threads");

        Thread platform = Thread.ofPlatform().unstarted(() -> {});
        assertFalse(platform.isVirtual());
    }

    @Test
    void manyVirtualThreadsShareAFewCarrierThreads() throws Exception {
        Set<String> carriers = ConcurrentHashMap.newKeySet();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 1_000; i++) {
                executor.submit(() -> {
                    // toString() of a mounted virtual thread looks like:
                    // VirtualThread[#42]/runnable@ForkJoinPool-1-worker-3
                    String s = Thread.currentThread().toString();
                    carriers.add(s.substring(s.indexOf('@') + 1));
                });
            }
        }

        System.out.println("1,000 virtual threads ran on " + carriers.size() + " carriers: " + carriers);
        assertTrue(carriers.size() <= Runtime.getRuntime().availableProcessors() + 1);
        assertTrue(carriers.stream().allMatch(c -> c.startsWith("ForkJoinPool")));
    }
}
