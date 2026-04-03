package ch.usi.inf.confidentialstorm;

import ch.usi.inf.confidentialstorm.common.api.SimpleService;
import org.apache.teaclave.javasdk.host.Enclave;
import org.apache.teaclave.javasdk.host.EnclaveFactory;
import org.apache.teaclave.javasdk.host.EnclaveType;
import org.apache.teaclave.javasdk.host.exception.EnclaveCreatingException;
import org.apache.teaclave.javasdk.host.exception.EnclaveDestroyingException;
import org.apache.teaclave.javasdk.host.exception.ServicesLoadingException;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class CrashMe {
    private static Enclave currentEnclave;

    // --- Helpers ---

    public static SimpleService loadTestService() {
        System.out.println("[setup] Starting enclave in thread: " + Thread.currentThread().getId());
        try {
            CrashMe.currentEnclave = EnclaveFactory.create(EnclaveType.TEE_SDK);
        } catch (EnclaveCreatingException e) {
            throw new RuntimeException("Failed to create enclave: " + e.getMessage(), e);
        }

        Iterator<SimpleService> iter;
        try {
            iter = currentEnclave.load(SimpleService.class);
        } catch (ServicesLoadingException e) {
            throw new RuntimeException("Failed to load SimpleService: " + e.getMessage(), e);
        }
        if (!iter.hasNext()) {
            throw new RuntimeException("No SimpleService implementation found");
        }
        SimpleService service = iter.next();
        System.out.println("[setup] SimpleService loaded");
        return service;
    }

    private static final long TEST_TIMEOUT_MS = 60_000;

    /**
     * Runs a test body in a dedicated thread (so that the enclave is created and destroyed in the
     * same thread) and fails if it does not complete within TEST_TIMEOUT_MS.
     */
    private static void runTest(String name, TestBody body) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread testThread = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                error.set(t);
            }
        }, "test-thread-" + name);
        testThread.start();

        try {
            testThread.join(TEST_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (testThread.isAlive()) {
            testThread.interrupt();
            throw new AssertionError("FAIL [" + name + "]: did not complete within "
                    + TEST_TIMEOUT_MS / 1000 + "s - likely stuck in enclave destroy");
        }
        if (error.get() != null) {
            throw new AssertionError("FAIL [" + name + "]: " + error.get().getMessage(), error.get());
        }
    }

    @FunctionalInterface
    interface TestBody {
        void run() throws Exception;
    }

    private static void teardown(SimpleService service) throws EnclaveDestroyingException {
        System.out.println("[teardown] Stopping async thread (if running)");
        service.stopAsyncThread();
        System.out.println("[teardown] Destroying enclave");
        CrashMe.currentEnclave.destroy();
        System.out.println("[teardown] Enclave destroyed");
    }

    private static void assertTrue(String label, boolean condition) {
        if (!condition) throw new AssertionError("FAIL [" + label + "]: expected true");
        System.out.println("PASS [" + label + "]");
    }

    private static void assertFalse(String label, boolean condition) {
        if (condition) throw new AssertionError("FAIL [" + label + "]: expected false");
        System.out.println("PASS [" + label + "]");
    }

    private static void assertPositive(String label, int value) {
        if (value <= 0) throw new AssertionError("FAIL [" + label + "]: expected positive, got " + value);
        System.out.println("PASS [" + label + "]: value=" + value);
    }

    // --- Tests ---

    /**
     * Test 0: Baseline - no threads. Create enclave, do a single ECALL, destroy. Should complete immediately.
     */
    static void test0_basicEcallAndDestroy() throws EnclaveDestroyingException {
        System.out.println("\n=== Test 0: basic ECALL and destroy (no threads) ===");
        SimpleService service = loadTestService();
        try {
            int result = service.someWork();
            assertPositive("someWork returns positive result", result);
        } finally {
            teardown(service);
        }
    }

    /**
     * Test 1: Start an async thread inside the enclave and verify it is reported as running.
     */
    static void test1_threadStartsAndIsDetectedAsRunning() throws EnclaveDestroyingException {
        System.out.println("\n=== Test 1: thread starts and is detected as running ===");
        SimpleService service = loadTestService();
        try {
            assertFalse("before start: thread not running", service.isAsyncThreadRunning());

            boolean started = service.startAsyncThread();
            assertTrue("startAsyncThread returns true", started);
            assertTrue("immediately after start: thread is running", service.isAsyncThreadRunning());

            // wait a bit and check again to ensure thread is still running
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            assertTrue("after 3s: thread is still running", service.isAsyncThreadRunning());
        } finally {
            teardown(service);
        }
    }

    /**
     * Test 2: someWork() can be called (ECALL) while the async thread is running inside the enclave.
     */
    static void test2_someWorkSucceedsWhileThreadIsRunning() throws EnclaveDestroyingException {
        System.out.println("\n=== Test 2: someWork() succeeds while async thread is running ===");
        SimpleService service = loadTestService();
        try {
            service.startAsyncThread();
            assertTrue("thread is running before someWork", service.isAsyncThreadRunning());

            int result = service.someWork();
            assertPositive("someWork returns positive result while thread running", result);
        } finally {
            teardown(service);
        }
    }

    /**
     * Test 3: Poll isAsyncThreadRunning() and collect someWork() results until the thread finishes.
     * Verifies that: all concurrent results are valid, and the thread eventually stops.
     */
    static void test3_pollResultsUntilThreadFinishes() throws EnclaveDestroyingException, InterruptedException {
        System.out.println("\n=== Test 3: poll results while thread runs, verify thread eventually stops ===");
        SimpleService service = loadTestService();
        try {
            service.startAsyncThread();

            List<Integer> results = new ArrayList<>();
            int polls = 0;
            while (service.isAsyncThreadRunning()) {
                int result = service.someWork();
                results.add(result);
                polls++;
                System.out.println("  poll #" + polls + ": someWork()=" + result + ", thread still running");
                Thread.sleep(5000);
            }

            assertTrue("at least one concurrent someWork call was made", polls > 0);
            for (int i = 0; i < results.size(); i++) {
                assertPositive("poll #" + (i + 1) + " result is positive", results.get(i));
            }
            assertFalse("thread is no longer running after loop exits", service.isAsyncThreadRunning());
        } finally {
            teardown(service);
        }
    }

    /**
     * Test 4: After the async thread finishes, someWork() still works (enclave remains usable).
     */
    static void test4_enclaveUsableAfterThreadFinishes() throws EnclaveDestroyingException, InterruptedException {
        System.out.println("\n=== Test 4: enclave remains usable after async thread finishes ===");
        SimpleService service = loadTestService();
        try {
            service.startAsyncThread();

            while (service.isAsyncThreadRunning()) {
                Thread.sleep(500);
            }
            assertFalse("thread has stopped", service.isAsyncThreadRunning());

            int result = service.someWork();
            assertPositive("someWork works after thread has finished", result);
        } finally {
            teardown(service);
        }
    }

    /**
     * Test 5: Simulate iterative host tasks - start a job, poll until done, repeat N times in the
     * same enclave. Verifies that the enclave correctly handles multiple sequential thread lifecycles.
     */
    static void test5_repeatedJobsInSameEnclave() throws EnclaveDestroyingException, InterruptedException {
        System.out.println("\n=== Test 5: repeated sequential jobs in the same enclave ===");
        SimpleService service = loadTestService();
        try {
            int iterations = 5;
            for (int i = 1; i <= iterations; i++) {
                System.out.println("  [iteration " + i + "] starting job");
                assertFalse("iteration " + i + ": thread not running before start", service.isAsyncThreadRunning());

                boolean started = service.startAsyncThread();
                assertTrue("iteration " + i + ": job started", started);
                assertTrue("iteration " + i + ": thread is running after start", service.isAsyncThreadRunning());

                int polls = 0;
                while (service.isAsyncThreadRunning()) {
                    polls++;
                    System.out.println("  [iteration " + i + "] poll #" + polls + ": job still running");
                    Thread.sleep(2000);
                }

                assertFalse("iteration " + i + ": thread finished", service.isAsyncThreadRunning());
                System.out.println("  [iteration " + i + "] job finished after " + polls + " poll(s)");
            }
        } finally {
            teardown(service);
        }
    }

    public static void main(String[] args) {
        List<String> failures = new ArrayList<>();

        for (String[] test : new String[][]{
                {"test0", "test0_basicEcallAndDestroy"},
                {"test1", "test1_threadStartsAndIsDetectedAsRunning"},
                {"test2", "test2_someWorkSucceedsWhileThreadIsRunning"},
                {"test3", "test3_pollResultsUntilThreadFinishes"},
                {"test4", "test4_enclaveUsableAfterThreadFinishes"},
                {"test5", "test5_repeatedJobsInSameEnclave"},
        }) {
            String name = test[0];
            try {
                switch (name) {
                    case "test0" -> runTest(name, CrashMe::test0_basicEcallAndDestroy);
                    case "test1" -> runTest(name, CrashMe::test1_threadStartsAndIsDetectedAsRunning);
                    case "test2" -> runTest(name, CrashMe::test2_someWorkSucceedsWhileThreadIsRunning);
                    case "test3" -> runTest(name, CrashMe::test3_pollResultsUntilThreadFinishes);
                    case "test4" -> runTest(name, CrashMe::test4_enclaveUsableAfterThreadFinishes);
                    case "test5" -> runTest(name, CrashMe::test5_repeatedJobsInSameEnclave);
                }
            } catch (AssertionError e) {
                System.out.println(e.getMessage());
                failures.add(name + ": " + e.getMessage());
            }
        }

        System.out.println("\n=== Results ===");
        if (failures.isEmpty()) {
            System.out.println("All tests passed.");
        } else {
            System.out.println(failures.size() + " test(s) failed:");
            failures.forEach(f -> System.out.println("  - " + f));
            System.exit(1);
        }
    }
}
