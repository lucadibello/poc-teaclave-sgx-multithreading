package ch.usi.inf.confidentialstorm.common.api;

import org.apache.teaclave.javasdk.common.annotations.EnclaveService;

@EnclaveService
public interface SimpleService {
    boolean startAsyncThread();
    void stopAsyncThread();
    boolean isAsyncThreadRunning();
    int someWork();

    /**
     * Starts a background thread that continuously allocates heap objects (HashMaps, HashSets,
     * ArrayLists) to trigger GC pressure inside the enclave isolate.
     * This is used by Test 6 to reproduce the GC safepoint crash when a concurrent ECall
     * arrives while the GC is running inside the isolate.
     */
    boolean startHeapAllocatingThread();

    /**
     * Performs heap-allocating work (builds a Map with String keys and Long values) and returns
     * it. Used as the concurrent ECall in Test 6 — its return value requires serialization of
     * non-primitive types, exercising the full ECall result path.
     *
     * @param entries number of map entries to produce
     * @return a map of string keys to long values
     */
    java.util.Map<String, Long> computeResult(int entries);

    // ---- Test 7: Reproduce exact production crash pattern ----

    /**
     * Adds a contribution to shared staging buffers (mimics the production
     * {@code StreamingDPMechanism.addContribution()} path). The background thread started by
     * {@link #startDPSnapshotThread(int, int)} drains these buffers, creating a concurrent
     * read/write race between the ECALL IsolateThread and the background IsolateThread on
     * the same shared native heap.
     *
     * @param userId user identifier
     * @param word   aggregation key
     * @param count  clamped contribution value
     */
    void addContribution(String userId, String word, double count);

    /**
     * Starts a background thread inside the enclave that mimics the production
     * {@code StreamingDPMechanism.snapshot()} workload:
     * <ul>
     *   <li>Swaps staging buffers (shared with {@link #addContribution})</li>
     *   <li>Builds binary aggregation trees with Gaussian noise (SecureRandom)</li>
     *   <li>Performs Honaker variance-reduced prefix sums (sqrt, log, bit shifts)</li>
     *   <li>Runs empty-key prediction (Algorithm 3 — the CPU-dominant inner loop)</li>
     *   <li>Produces a sorted histogram result</li>
     * </ul>
     *
     * @param numKeys      number of keys to simulate
     * @param maxTimeSteps maximum time steps for prediction (controls CPU intensity)
     * @return true if the thread was started, false if one is already running
     */
    boolean startDPSnapshotThread(int numKeys, int maxTimeSteps);

    /**
     * Returns the snapshot result computed by the background thread started via
     * {@link #startDPSnapshotThread}, or null if not yet complete.
     */
    java.util.Map<String, Long> pollDPSnapshotResult();

    // ---- Test 8: Minimal crash reproduction ----

    /**
     * Starts a background thread that allocates Gaussian noise via {@code SecureRandom}
     * into a large {@code double[]} array — the minimal workload that corrupts the
     * isolate heap when a concurrent ECALL arrives.
     *
     * @param arraySize number of doubles to fill with Gaussian noise
     * @return true if started, false if already running
     */
    boolean startSecureRandomThread(int arraySize);

    /**
     * No-op ECALL. Enters the enclave, does nothing, returns.
     * Used to prove that the mere act of attaching a second IsolateThread
     * while SecureRandom is running is enough to corrupt the heap.
     */
    void noop();

    // ---- Test 9: Control test with java.util.Random ----

    /**
     * Same as {@link #startSecureRandomThread(int)} but uses {@code java.util.Random}
     * instead of {@code SecureRandom}. If Test 8 crashes but Test 9 does not, the root
     * cause is {@code SecureRandom}'s native entropy source, not general multithreading.
     *
     * @param arraySize number of doubles to fill with Gaussian noise
     * @return true if started, false if already running
     */
    boolean startPlainRandomThread(int arraySize);

    // ---- Test 10/11: Crypto isolation ----

    /**
     * Starts a background thread that performs ChaCha20-Poly1305 encryption in a loop.
     * Used to test whether JCA crypto operations on a background thread cause the same
     * hang/corruption as SecureRandom when concurrent ECALLs arrive.
     *
     * @param iterations number of encrypt/decrypt cycles
     * @return true if started, false if already running
     */
    boolean startCryptoThread(int iterations);

    /**
     * Starts a background thread that performs ChaCha20-Poly1305 encryption using
     * {@code java.util.Random} for nonce generation (no SecureRandom).
     * Isolates whether JCA Cipher operations alone are the problem, or only when
     * combined with SecureRandom for nonce generation.
     *
     * @param iterations number of encrypt/decrypt cycles
     * @return true if started, false if already running
     */
    boolean startCryptoWithPlainRandomThread(int iterations);

    // ---- Test 12: Cipher with counter-based nonces (no SecureRandom) ----

    /**
     * Starts a background thread that performs ChaCha20-Poly1305 encryption using a
     * monotonic counter encoded into the nonce bytes — guaranteeing nonce uniqueness
     * without any {@code SecureRandom} call.
     * <p>
     * Answers: is {@code Cipher.getInstance()} / {@code cipher.init()} / {@code cipher.doFinal()}
     * itself a native safepoint hazard, or only when combined with {@code SecureRandom}?
     * <p>
     * Expected: PASS if JCA cipher operations are pure-Java-safe, FAIL if cipher JNI/native
     * intrinsics trigger the same IsolateThread corruption as SecureRandom.
     *
     * @param iterations number of encrypt/decrypt cycles
     * @return true if started, false if already running
     */
    boolean startCipherCounterNonceThread(int iterations);

    // ---- Test 13: Long-running pure-Java snapshot, concurrent addContribution() ----

    /**
     * Starts a background thread that replicates the full {@code StreamingDPMechanism.snapshot()}
     * workload — buffer swap, binary tree construction, Honaker prefix sums, Algorithm 3
     * prediction loop — using only {@code java.util.Random} (no SecureRandom).
     * <p>
     * Concurrent ECALLs are {@code addContribution()} calls (not {@code noop()}) to replicate
     * the exact production scenario for Crash 3.
     * <p>
     * Answers: does a long-running (~200ms) pure-Java background thread cause the ECALL hang
     * observed in Crash 3, independent of SecureRandom?
     * <p>
     * Expected: FAIL (silent hang on addContribution) if duration/allocation alone is sufficient
     * to cause enterAttachThread() to spin-wait indefinitely; PASS if the hang was caused
     * exclusively by SecureRandom safepoint gaps.
     *
     * @param numKeys      number of keys to simulate (controls allocation volume)
     * @param maxTimeSteps maximum time steps for prediction (controls CPU intensity / duration)
     * @return true if started, false if already running
     */
    boolean startFullSnapshotNoSecureRandomThread(int numKeys, int maxTimeSteps);

    /**
     * Returns the result from the thread started by {@link #startFullSnapshotNoSecureRandomThread},
     * or {@code null} if computation is still in progress.
     * Throws {@code RuntimeException} if the background thread encountered an error.
     */
    java.util.Map<String, Long> pollFullSnapshotNoSecureRandomResult();

    // ---- Test 14: Option A validation — snapshot on bg thread, encrypt on poll ECALL ----

    /**
     * Starts a background thread that computes only the plaintext snapshot
     * ({@code mechanism.snapshot()} equivalent) — no encryption.
     * The encrypt step is deferred to {@link #pollOptionAResult()}, which runs on the ECALL
     * thread (no background thread is active when that ECALL fires).
     * <p>
     * This is the Option A architectural fix from the findings document: move {@code encrypt()}
     * out of the background thread into the {@code pollEncryptedSnapshot()} ECALL.
     * <p>
     * Expected: PASS — background thread does only pure-Java work; poll ECALL does encryption
     * synchronously on the ECALL thread while no background thread is running.
     *
     * @param numKeys      number of keys to simulate
     * @param maxTimeSteps maximum time steps for prediction
     * @return true if started, false if already running
     */
    boolean startOptionASnapshotThread(int numKeys, int maxTimeSteps);

    /**
     * Polls the result of the Option A snapshot. If the background snapshot is complete,
     * performs ChaCha20-Poly1305 encryption synchronously on the ECALL thread and returns the
     * ciphertext length (proxy for a real {@code EncryptedDataPerturbationSnapshot}).
     * Returns {@code -1} if the snapshot is not yet ready.
     * Throws {@code RuntimeException} if the background thread encountered an error.
     */
    int pollOptionAResult();

    // ---- Test 5: Enclave-side Thread.start() with synchronous pthread_create shim ----

    /**
     * Spawns a thread inside the enclave via {@code new Thread().start()} and returns the
     * result computed by that thread. Under the synchronous {@code pthread_create} shim,
     * the thread function runs on the calling TCS before {@code Thread.start()} returns,
     * so this ECALL blocks until the inner work is done.
     *
     * @param fibN the Fibonacci index to compute inside the spawned thread
     * @return the Fibonacci result produced by the enclave-side thread
     */
    int runInlineThread(int fibN);

    /**
     * Spawns a thread inside the enclave that sets a shared flag, then returns whether
     * the flag was set by the time the ECALL returns. Under the synchronous shim,
     * {@code Thread.start()} blocks until the thread function completes, so the flag
     * must always be set on return. This validates the blocking semantics directly.
     *
     * @return true if the enclave-side thread set the flag before the ECALL returned
     */
    boolean isInlineThreadSynchronous();

    /**
     * Spawns N threads sequentially inside the enclave, each doing CPU work, and returns
     * the total accumulated result. Used to verify that multiple sequential
     * {@code Thread.start()} calls on the same TCS all execute correctly and that no
     * zombie IsolateThread is accumulated between calls.
     *
     * @param n   number of threads to start sequentially
     * @param fibN Fibonacci index each thread computes
     * @return sum of all Fibonacci results
     */
    long runNInlineThreads(int n, int fibN);

    // ---- Host-side multithreading stress tests ----

    /**
     * Returns the input value unchanged. Used by the host-side multithreading
     * stress tests to verify that no result-crossing occurs under concurrent
     * ECALLs: each caller knows exactly what value to expect back.
     */
    int echoInt(int value);

    /**
     * Returns the input value after spinning for roughly {@code cpuLoops}
     * fibonacci-style operations. Lets the host-side stress tests tune the
     * in-enclave residency time per ECALL to maximise overlap between
     * concurrent calls without adding new service methods for each scenario.
     */
    int echoIntWithWork(int value, int cpuLoops);
}
