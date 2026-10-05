package notes.p02;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Post #2 — "The one keyword that quietly kills virtual thread throughput (on Java 21)".
 * Uses JFR's jdk.VirtualThreadPinned event to prove pinning happens, then that it doesn't.
 * Behaviour shown is Java 21. From Java 24 (JEP 491) synchronized no longer pins.
 */
class PinningTest {

    private final Object monitor = new Object();
    private final ReentrantLock lock = new ReentrantLock();

    void blockInsideSynchronized() {
        synchronized (monitor) {
            sleep(50);          // blocking while holding a monitor -> pinned on Java 21
        }
    }

    void blockInsideReentrantLock() {
        lock.lock();
        try {
            sleep(50);          // blocking while holding a j.u.c lock -> unmounts normally
        } finally {
            lock.unlock();
        }
    }

    @Test
    void synchronizedPinsTheCarrierOnJava21() throws Exception {
        long pinned = countPinnedEvents(this::blockInsideSynchronized);
        System.out.println("synchronized: " + pinned + " pinned events");
        if (Runtime.version().feature() < 24) {
            assertTrue(pinned > 0, "expected pinning on Java " + Runtime.version().feature());
        }
    }

    @Test
    void reentrantLockDoesNotPin() throws Exception {
        long pinned = countPinnedEvents(this::blockInsideReentrantLock);
        System.out.println("ReentrantLock: " + pinned + " pinned events");
        assertEquals(0, pinned);
    }

    private long countPinnedEvents(Runnable work) throws Exception {
        Path file = Files.createTempFile("pinning", ".jfr");
        try (Recording recording = new Recording()) {
            recording.enable("jdk.VirtualThreadPinned").withThreshold(Duration.ofMillis(10));
            recording.start();

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < 5; i++) executor.submit(work);
            }

            recording.stop();
            recording.dump(file);
        }
        List<RecordedEvent> events = RecordingFile.readAllEvents(file);
        Files.deleteIfExists(file);
        return events.stream().filter(e -> e.getEventType().getName().equals("jdk.VirtualThreadPinned")).count();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
