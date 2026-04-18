package ch.usi.inf.confidentialstorm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.usi.inf.confidentialstorm.common.api.SimpleService;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.teaclave.javasdk.host.Enclave;
import org.apache.teaclave.javasdk.host.EnclaveFactory;
import org.apache.teaclave.javasdk.host.EnclaveType;
import org.apache.teaclave.javasdk.host.exception.EnclaveCreatingException;
import org.apache.teaclave.javasdk.host.exception.EnclaveDestroyingException;
import org.apache.teaclave.javasdk.host.exception.ServicesLoadingException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(
    value = 300,
    unit = TimeUnit.SECONDS,
    threadMode = Timeout.ThreadMode.SEPARATE_THREAD // start each test in a separate thread to be able to interrupt it if it hangs
)
public class CrashMe {

    private Enclave currentEnclave;
    protected SimpleService service;

    @BeforeEach
    void setUp() throws EnclaveCreatingException, ServicesLoadingException {
        System.out.println("[host] Creating enclave...");
        currentEnclave = EnclaveFactory.create(EnclaveType.TEE_SDK);
        Iterator<SimpleService> iter = currentEnclave.load(SimpleService.class);
        if (!iter.hasNext()) throw new RuntimeException(
            "No SimpleService found"
        );
        service = iter.next();
    }

    @AfterEach
    @Timeout(
        value = 30,
        unit = TimeUnit.SECONDS,
        threadMode = Timeout.ThreadMode.SEPARATE_THREAD // run teardown in a separate thread to be able to interrupt it if it hangs
    )
    void tearDown() throws EnclaveDestroyingException {
        if (currentEnclave != null) {
            System.out.println("[host] Destroying enclave...");
            currentEnclave.destroy();
        }
    }

    /**
     * Verifies basic ECall functionality with a single synchronous call.
     */
    @Test
    void baseline_singleEcall() throws Exception {
        System.out.println("[host] Starting baseline_singleEcall");
        assertTrue(service.someWork() > 0);
    }

    /**
     * Tests concurrent ECalls from multiple host threads to verify thread-safety 
     * of the enclave entry/exit logic and service implementation.
     */
    @Test
    void host_concurrentEcalls() throws Exception {
        System.out.println("[host] Starting host_concurrentEcalls");
        int hostThreads = 4,
            callsPerThread = 200;
        AtomicInteger total = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(hostThreads);
        for (int t = 0; t < hostThreads; t++) {
            new Thread(() -> {
                try {
                    for (int c = 0; c < callsPerThread; c++) {
                        if (service.someWork() <= 0) throw new AssertionError();
                        total.incrementAndGet();
                    }
                } catch (Throwable ignored) {
                } finally {
                    done.countDown();
                }
            })
                .start();
        }
        assertTrue(done.await(60, TimeUnit.SECONDS));
        assertEquals(hostThreads * callsPerThread, total.get());
    }

    /**
     * Creates GC pressure within the enclave via a background thread while 
     * simultaneously performing ECalls to trigger race conditions during GC safepoints.
     */
    @Test
    void enclave_gcPressureBgThread() throws Exception {
        System.out.println("[host] Starting enclave_gcPressureBgThread");
        assertTrue(service.startHeapAllocatingThread());
        Thread.sleep(500);
        int hostThreads = 4,
            callsPerThread = 100;
        CountDownLatch done = new CountDownLatch(hostThreads);
        for (int t = 0; t < hostThreads; t++) {
            new Thread(() -> {
                try {
                    for (
                        int c = 0;
                        c < callsPerThread;
                        c++
                    ) service.computeResult(200);
                } catch (Throwable ignored) {
                } finally {
                    done.countDown();
                }
            })
                .start();
        }
        assertTrue(done.await(60, TimeUnit.SECONDS));
        service.stopAsyncThread();
    }

    /**
     * Replicates a production workload pattern: a background snapshot thread 
     * performing complex math while host threads concurrently add data.
     */
    @Test
    void enclave_dpSnapshotPattern() throws Exception {
        System.out.println("[host] Starting enclave_dpSnapshotPattern");
        for (int i = 0; i < 500; i++) service.addContribution(
            "u-" + (i % 50),
            "k-" + (i % 200),
            1.0
        );
        assertTrue(service.startFullSnapshotNoSecureRandomThread(500, 100));
        Thread.sleep(200);
        int hostThreads = 4,
            callsPerThread = 500;
        CountDownLatch done = new CountDownLatch(hostThreads);
        for (int t = 0; t < hostThreads; t++) {
            new Thread(() -> {
                try {
                    for (
                        int c = 0;
                        c < callsPerThread;
                        c++
                    ) service.addContribution("u", "k", 1.0);
                } catch (Throwable ignored) {
                } finally {
                    done.countDown();
                }
            })
                .start();
        }
        assertTrue(done.await(60, TimeUnit.SECONDS));
        assertNotNull(service.pollFullSnapshotNoSecureRandomResult());
    }

    /**
     * Exercises the SecureRandom native entropy source from an enclave background thread 
     * while the host performs frequent no-op ECalls.
     */
    @Test
    void enclave_secureRandomBgThread() throws Exception {
        System.out.println("[host] Starting enclave_secureRandomBgThread");
        assertTrue(service.startSecureRandomThread(1_000_000));
        Thread.sleep(100);
        int hostThreads = 4,
            callsPerThread = 200;
        CountDownLatch done = new CountDownLatch(hostThreads);
        for (int t = 0; t < hostThreads; t++) {
            new Thread(() -> {
                try {
                    for (int c = 0; c < callsPerThread; c++) service.noop();
                } catch (Throwable ignored) {
                } finally {
                    done.countDown();
                }
            })
                .start();
        }
        assertTrue(done.await(60, TimeUnit.SECONDS));
        service.stopAsyncThread();
    }

    /**
     * Verifies that enclave-side threads (pthread-based) can complete successfully 
     * and return results to the host.
     */
    @Test
    void enclave_inlineThreadCompletion() throws Exception {
        System.out.println("[host] Starting enclave_inlineThreadCompletion");
        assertEquals(6765, service.runInlineThread(20));
    }

    /**
     * Checks if enclave threads are truly asynchronous (non-blocking) relative 
     * to the thread that started them.
     */
    @Test
    void enclave_inlineThreadAsync() throws Exception {
        System.out.println("[host] Starting enclave_inlineThreadAsync");
        // Real sgx_pthread threads run on a separate TCS; Thread.start()
        // returns before the worker body executes. isInlineThreadSynchronous()
        // returns the pre-join flag state — expect false (async).
        assertFalse(service.isInlineThreadSynchronous());
    }

    /**
     * Ensures no resources (TCS/memory) are leaked or corrupted after an 
     * enclave-side thread completes.
     */
    @Test
    void enclave_noZombieAfterInlineThread() throws Exception {
        System.out.println("[host] Starting enclave_noZombieAfterInlineThread");
        assertEquals(610, service.runInlineThread(15));
        int hostThreads = 4,
            callsPerThread = 200;
        CountDownLatch done = new CountDownLatch(hostThreads);
        for (int t = 0; t < hostThreads; t++) {
            new Thread(() -> {
                try {
                    for (int c = 0; c < callsPerThread; c++) service.someWork();
                } catch (Throwable ignored) {
                } finally {
                    done.countDown();
                }
            })
                .start();
        }
        assertTrue(done.await(60, TimeUnit.SECONDS));
    }

    /**
     * Heavily stresses the enclave thread pool by running multiple concurrent 
     * enclave-side threads across multiple host threads.
     */
    @Test
    void enclave_concurrentInlineThreads() throws Exception {
        System.out.println("[host] Starting enclave_concurrentInlineThreads");
        int hostThreads = 4,
            callsPerThread = 25,
            inlinePerCall = 5;
        long expected = (long) inlinePerCall * 610;
        CountDownLatch done = new CountDownLatch(hostThreads);
        for (int t = 0; t < hostThreads; t++) {
            new Thread(() -> {
                System.out.printf(
                    "[host] Thread %s starting%n",
                    Thread.currentThread().getName()
                );
                try {
                    for (int c = 0; c < callsPerThread; c++) {
                        final long result = service.runNInlineThreads(
                            inlinePerCall,
                            15
                        );
                        assertEquals(expected, result);
                        System.out.printf(
                            "[host] Thread %s completed call %s with result %s%n",
                            Thread.currentThread().getName(),
                            c,
                            result
                        );
                    }
                } catch (Throwable ignored) {
                } finally {
                    done.countDown();
                }
            })
                .start();
        }
        assertTrue(done.await(120, TimeUnit.SECONDS));
    }

    /**
     * Simulates a "storm" of many host threads performing their first ECall 
     * simultaneously to test isolate initialization concurrency.
     */
    @Test
    void host_coldStartStorm() throws Exception {
        System.out.println("[host] Starting host_coldStartStorm");
        int hostThreads = 64;
        CountDownLatch done = new CountDownLatch(hostThreads);
        AtomicInteger correct = new AtomicInteger();
        for (int t = 0; t < hostThreads; t++) {
            final int tid = t;
            new Thread(() -> {
                try {
                    if (
                        service.echoInt(0xC0FFEE + tid) == 0xC0FFEE + tid
                    ) correct.incrementAndGet();
                } catch (Throwable ignored) {
                } finally {
                    done.countDown();
                }
            })
                .start();
        }
        assertTrue(done.await(60, TimeUnit.SECONDS));
        assertEquals(hostThreads, correct.get());
    }

    /**
     * High-volume throughput test to identify long-term instability or memory leaks.
     */
    @Test
    void host_sustainedThroughput() throws Exception {
        System.out.println("[host] Starting host_sustainedThroughput");
        int hostThreads = 4,
            callsPerThread = 5_000;
        CountDownLatch done = new CountDownLatch(hostThreads);
        AtomicLong ok = new AtomicLong();
        for (int t = 0; t < hostThreads; t++) {
            final int tid = t;
            new Thread(() -> {
                try {
                    for (int c = 0; c < callsPerThread; c++) if (
                        service.echoInt((tid << 20) | c) == ((tid << 20) | c)
                    ) ok.incrementAndGet();
                } catch (Throwable ignored) {
                } finally {
                    done.countDown();
                }
            })
                .start();
        }
        assertTrue(done.await(180, TimeUnit.SECONDS));
        assertEquals((long) hostThreads * callsPerThread, ok.get());
    }

    /**
     * Tests thread-safety when different types of ECalls (CPU-bound, memory-bound, 
     * IO-bound) are mixed concurrently.
     */
    @Test
    void host_heterogeneousMix() throws Exception {
        System.out.println("[host] Starting host_heterogeneousMix");
        int hostThreads = 8,
            callsPerThread = 400;
        CountDownLatch done = new CountDownLatch(hostThreads);
        for (int t = 0; t < hostThreads; t++) {
            new Thread(() -> {
                try {
                    ThreadLocalRandom rng = ThreadLocalRandom.current();
                    for (int c = 0; c < callsPerThread; c++) {
                        switch (rng.nextInt(5)) {
                            case 0 -> service.echoInt(rng.nextInt());
                            case 1 -> service.someWork();
                            case 2 -> service.computeResult(10);
                            case 3 -> service.addContribution("u", "k", 1.0);
                            default -> service.noop();
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    done.countDown();
                }
            })
                .start();
        }
        assertTrue(done.await(120, TimeUnit.SECONDS));
    }

    /**
     * Tests the impact of rapid host-side thread creation and destruction on 
     * the enclave's internal thread management.
     */
    @Test
    void host_threadChurn() throws Exception {
        System.out.println("[host] Starting host_threadChurn");
        int rounds = 4,
            threadsPerRound = 50,
            callsPerThread = 5;
        AtomicInteger ok = new AtomicInteger();
        for (int r = 0; r < rounds; r++) {
            final int round = r;
            CountDownLatch done = new CountDownLatch(threadsPerRound);
            for (int t = 0; t < threadsPerRound; t++) {
                final int tid = t;
                new Thread(() -> {
                    try {
                        for (int c = 0; c < callsPerThread; c++) if (
                            service.echoInt((round << 24) | (tid << 12) | c) ==
                            ((round << 24) | (tid << 12) | c)
                        ) ok.incrementAndGet();
                    } catch (Throwable ignored) {
                    } finally {
                        done.countDown();
                    }
                })
                    .start();
            }
            done.await(60, TimeUnit.SECONDS);
        }
        assertEquals(rounds * threadsPerRound * callsPerThread, ok.get());
    }

    /**
     * Tests enclave stability under extreme concurrency that exceeds the expected 
     * TCS (Thread Control Structure) capacity.
     */
    @Test
    void host_cacheCapacitySaturation() throws Exception {
        System.out.println("[host] Starting host_cacheCapacitySaturation");
        int hostThreads = 96,
            callsPerThread = 20;
        CountDownLatch done = new CountDownLatch(hostThreads);
        AtomicInteger ok = new AtomicInteger();
        for (int t = 0; t < hostThreads; t++) {
            final int tid = t;
            new Thread(() -> {
                try {
                    for (int c = 0; c < callsPerThread; c++) if (
                        service.echoInt((tid << 16) | c) == ((tid << 16) | c)
                    ) ok.incrementAndGet();
                } catch (Throwable ignored) {
                } finally {
                    done.countDown();
                }
            })
                .start();
        }
        done.await(120, TimeUnit.SECONDS);
        assertEquals(hostThreads * callsPerThread, ok.get());
    }

    /**
     * Tests for race conditions when host threads load the service while others 
     * are actively invoking it.
     */
    @Test
    void host_concurrentLoadAndInvoke() throws Exception {
        System.out.println("[host] Starting host_concurrentLoadAndInvoke");
        int workerThreads = 4,
            callsPerWorker = 400,
            loadReps = 40;
        CountDownLatch done = new CountDownLatch(workerThreads + 1);
        for (int t = 0; t < workerThreads; t++) new Thread(() -> {
            try {
                for (int c = 0; c < callsPerWorker; c++) service.noop();
            } catch (Throwable ignored) {
            } finally {
                done.countDown();
            }
        })
            .start();
        new Thread(() -> {
            try {
                for (int i = 0; i < loadReps; i++) currentEnclave.load(
                    SimpleService.class
                );
            } catch (Throwable ignored) {
            } finally {
                done.countDown();
            }
        })
            .start();
        done.await(120, TimeUnit.SECONDS);
    }

    /**
     * Induces a race condition between enclave destruction and active ECalls. 
     * Expects clean shutdown or manageable exceptions.
     */
    @Test
    void host_destroyRacesEcalls() throws Exception {
        System.out.println("[host] Starting host_destroyRacesEcalls");
        int workerThreads = 4;
        long workDurationMs = 2_000,
            deadline = System.currentTimeMillis() + workDurationMs;
        CountDownLatch done = new CountDownLatch(workerThreads);
        for (int t = 0; t < workerThreads; t++) new Thread(() -> {
            try {
                while (System.currentTimeMillis() < deadline) service.noop();
            } catch (Throwable ignored) {
            } finally {
                done.countDown();
            }
        })
            .start();
        Thread.sleep(workDurationMs / 2);
        currentEnclave.destroy();
        currentEnclave = null;
        done.await(60, TimeUnit.SECONDS);
    }

    /**
     * Tests the stability of the enclave lifecycle (create -> use -> destroy) 
     * when executed multiple times in sequence.
     */
    @Test
    void host_backToBackLifecycles() throws Exception {
        System.out.println("[host] Starting host_backToBackLifecycles");
        for (int cycle = 0; cycle < 4; cycle++) {
            Enclave enc = EnclaveFactory.create(EnclaveType.TEE_SDK);
            SimpleService svc = enc.load(SimpleService.class).next();
            svc.noop();
            enc.destroy();
        }
    }
}
