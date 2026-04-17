package ch.usi.inf.confidentialstorm.enclave;

import ch.usi.inf.confidentialstorm.common.api.SimpleService;
import com.google.auto.service.AutoService;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;

@AutoService(SimpleService.class)
public class SimpleEnclaveImpl implements SimpleService {

    private Thread asyncThread;

    // ---- Test 7: Shared state mimicking StreamingDPMechanism ----
    // Staging buffers written by addContribution() ECALLs, drained by the background thread
    private final Object bufferLock = new Object();
    private Map<String, Double> stagingCounts = new HashMap<>();
    private Map<String, Set<String>> stagingUsers = new HashMap<>();
    private volatile Map<String, Long> dpSnapshotResult;
    private volatile Throwable dpSnapshotError;
    
    @Override
    public boolean startAsyncThread() {
        if (asyncThread != null && asyncThread.isAlive()) {
            System.out.println("Async thread already running, rejecting start request");
            return false;
        }
        asyncThread = new Thread(() -> {
            System.out.println("Async thread started in enclave, thread ID: " + Thread.currentThread().getId());
            // Busy-wait to simulate a long-running job without relying on Thread.sleep(),
            // which may not work correctly inside the enclave JVM.
            long end = System.currentTimeMillis() + 3_000;
            while (System.currentTimeMillis() < end) {
                // spin
            }
            System.out.println("Async thread finished work in enclave, thread ID: " + Thread.currentThread().getId());
        });
        asyncThread.setDaemon(true); // Don't block enclave shutdown if the job is still running
        asyncThread.start();
        return true;
    }

    @Override
    public void stopAsyncThread() {
        if (asyncThread != null && asyncThread.isAlive()) {
            asyncThread.interrupt();
            // Do not join — Thread.interrupt() may not wake a busy-spinning enclave thread,
            // and joining would block the ECall indefinitely. The daemon flag ensures the
            // thread does not prevent JVM exit.
        }
    }

    @Override
    public boolean isAsyncThreadRunning() {
        return asyncThread != null && asyncThread.isAlive();
    }

    @Override
    public int someWork() {
        System.out.println("Doing some work in enclave, thread ID: " + Thread.currentThread
                ().getId());
        // fib(30) is a CPU-intensive task that will take some time to complete
        return fib(30);
    }

    private int fib(int n) {
        if (n <= 1) return n;
        return fib(n - 1) + fib(n - 2);
    }

    @Override
    public boolean startHeapAllocatingThread() {
        if (asyncThread != null && asyncThread.isAlive()) {
            return false;
        }
        asyncThread = new Thread(() -> {
            System.out.println("[enclave-bg] Heap-allocating thread started, id=" + Thread.currentThread().getId());
            // Continuously allocate and discard large object graphs to trigger GC safepoints
            // inside the isolate as frequently as possible. Larger forests = more allocation
            // per iteration = more frequent GC = higher probability of a safepoint coinciding
            // with a concurrent ECall IsolateThread.
            long iterations = 0;
            while (!Thread.currentThread().isInterrupted()) {
                HashMap<String, HashSet<String>> forest = new HashMap<>();
                for (int k = 0; k < 2000; k++) {
                    HashSet<String> users = new HashSet<>();
                    for (int u = 0; u < 500; u++) {
                        users.add("user-" + u + "-key-" + k + "-iter-" + iterations);
                    }
                    forest.put("key-" + k, users);
                }
                LinkedHashMap<String, Long> result = new LinkedHashMap<>();
                for (Map.Entry<String, HashSet<String>> e : forest.entrySet()) {
                    result.put(e.getKey(), (long) e.getValue().size());
                }
                ArrayList<Map<String, Long>> history = new ArrayList<>();
                for (int h = 0; h < 50; h++) {
                    history.add(new LinkedHashMap<>(result));
                }
                iterations++;
            }
            System.out.println("[enclave-bg] Heap-allocating thread finished after " + iterations + " iterations");
        });
        asyncThread.setDaemon(true);
        asyncThread.start();
        return true;
    }

    @Override
    public Map<String, Long> computeResult(int entries) {
        // Allocate heavily inside the ECall to maximise the chance of a GC safepoint
        // occurring while this IsolateThread is active alongside the background thread.
        LinkedHashMap<String, Long> result = new LinkedHashMap<>();
        for (int i = 0; i < entries; i++) {
            // Build intermediate strings to force allocations on the enclave heap
            String key = "result-key-" + i + "-suffix-" + (i * 31);
            HashSet<String> scratch = new HashSet<>();
            for (int j = 0; j < 100; j++) {
                scratch.add("scratch-" + i + "-" + j);
            }
            result.put(key, (long) scratch.size() + fib(15 + (i % 8)));
        }
        return result;
    }

    // ---- Test 7: Exact production crash reproduction ----

    @Override
    public void addContribution(String userId, String word, double count) {
        // Mirrors StreamingDPMechanism.addContribution() — writes to staging buffers
        // while the background thread may be draining them
        synchronized (bufferLock) {
            stagingCounts.merge(word, count, Double::sum);
            stagingUsers.computeIfAbsent(word, k -> new HashSet<>()).add(userId);
        }
    }

    @Override
    public boolean startDPSnapshotThread(int numKeys, int maxTimeSteps) {
        if (asyncThread != null && asyncThread.isAlive()) {
            return false;
        }
        dpSnapshotResult = null;
        dpSnapshotError = null;

        asyncThread = new Thread(() -> {
            try {
                System.out.println("[enclave-bg] DP snapshot thread started, id=" + Thread.currentThread().getId());
                dpSnapshotResult = runDPSnapshot(numKeys, maxTimeSteps);
                System.out.println("[enclave-bg] DP snapshot thread completed successfully");
            } catch (Throwable t) {
                dpSnapshotError = t;
                System.out.println("[enclave-bg] DP snapshot thread FAILED: " + t.getMessage());
                t.printStackTrace(System.out);
            }
        }, "enclave-dp-snapshot");
        asyncThread.setDaemon(true);
        asyncThread.start();
        return true;
    }

    @Override
    public Map<String, Long> pollDPSnapshotResult() {
        if (dpSnapshotError != null) {
            throw new RuntimeException("DP snapshot failed: " + dpSnapshotError.getMessage(), dpSnapshotError);
        }
        return dpSnapshotResult; // null if not yet complete
    }

    /**
     * Replicates the exact computation pattern of StreamingDPMechanism.snapshot():
     * 1. Swap-and-drain staging buffers (under bufferLock)
     * 2. Build binary aggregation trees with Gaussian noise (SecureRandom)
     * 3. Honaker variance-reduced prefix sums (sqrt, log, bit manipulation)
     * 4. Empty-key prediction (Algorithm 3 inner loop — CPU dominant)
     * 5. Produce sorted histogram
     */
    private Map<String, Long> runDPSnapshot(int numKeys, int maxTimeSteps) {
        // --- Step 1: Swap staging buffers (mirrors StreamingDPMechanism.snapshot() lines 224-231) ---
        final Map<String, Double> drainedCounts;
        final Map<String, Set<String>> drainedUsers;
        synchronized (bufferLock) {
            drainedCounts = this.stagingCounts;
            drainedUsers = this.stagingUsers;
            this.stagingCounts = new HashMap<>();
            this.stagingUsers = new HashMap<>();
        }

        System.out.println("[enclave-bg] Drained " + drainedCounts.size() + " keys from staging buffers");

        // --- Step 2: Build binary aggregation trees with Gaussian noise ---
        // Mirrors BinaryAggregationTree constructor: height = ceil(log2(maxTimeSteps)), tree = double[]
        SecureRandom rng = new SecureRandom();
        int height = (int) Math.ceil(Math.log(maxTimeSteps) / Math.log(2));
        int numLeaves = 1 << height;
        int treeSize = 2 * numLeaves - 1;

        // Build a forest of trees (one per key) — mirrors keySelectionForest + histogramForest
        Map<String, double[]> forest = new HashMap<>();
        Set<String> allKeys = new HashSet<>(drainedCounts.keySet());

        // Also add synthetic keys to match the scale of the real mechanism
        for (int k = 0; k < numKeys; k++) {
            allKeys.add("synth-key-" + k);
        }

        System.out.println("[enclave-bg] Building " + allKeys.size() + " aggregation trees (treeSize=" + treeSize + ")");

        for (String key : allKeys) {
            // Initialize tree with Gaussian noise N(0, sigma^2) — mirrors BinaryAggregationTree.initializeTree()
            double sigma = 1.5 + rng.nextDouble();
            double[] tree = new double[treeSize];
            for (int i = 0; i < treeSize; i++) {
                tree[i] = rng.nextGaussian() * sigma;
            }
            forest.put(key, tree);

            // Add contribution values along leaf-to-root paths — mirrors BinaryAggregationTree.add()
            double count = drainedCounts.getOrDefault(key, 0.0);
            for (int t = 0; t < Math.min(maxTimeSteps, numLeaves); t++) {
                int idx = numLeaves - 1 + t;
                double val = count + rng.nextGaussian() * 0.1;
                while (idx > 0) {
                    tree[idx] += val;
                    idx = (idx - 1) / 2;
                }
                tree[0] += val;
            }
        }

        // --- Step 3: Honaker variance-reduced prefix sums ---
        // Mirrors BinaryAggregationTree.getTotalSum() + computeHonakerEstimate()
        Map<String, Double> currentSums = new HashMap<>();
        int[] currentLevel = new int[numLeaves];
        int[] nextLevel = new int[numLeaves];

        System.out.println("[enclave-bg] Computing Honaker prefix sums for " + forest.size() + " keys");

        for (Map.Entry<String, double[]> entry : forest.entrySet()) {
            double[] tree = entry.getValue();
            int timeStep = Math.min(maxTimeSteps - 1, numLeaves - 1);

            // getTotalSum(timeStep) — traverse root to leaf, accumulating Honaker estimates
            int indexBinary = timeStep + 1;
            int nodeIndex = 0;
            double sPriv = 0.0;

            for (int j = 0; j <= height; j++) {
                int levelBit = (indexBinary >> (height - j)) & 1;
                if (levelBit == 1) {
                    int leftSibling;
                    if (nodeIndex == 0) leftSibling = 0;
                    else if (nodeIndex % 2 == 0) leftSibling = nodeIndex - 1;
                    else leftSibling = nodeIndex;

                    int kappa = height - j + 1;

                    // computeHonakerEstimate(leftSibling, kappa)
                    double estimate = 0.0;
                    int currentSize = 0;
                    currentLevel[currentSize++] = leftSibling;

                    for (int lev = 0; lev < kappa; lev++) {
                        double sumLev = 0;
                        int nextSize = 0;
                        for (int ci = 0; ci < currentSize; ci++) {
                            int idx = currentLevel[ci];
                            if (idx < tree.length) {
                                sumLev += tree[idx];
                                if (lev < kappa - 1) {
                                    nextLevel[nextSize++] = 2 * idx + 1;
                                    nextLevel[nextSize++] = 2 * idx + 2;
                                }
                            }
                        }
                        double c_j = (1.0 / (1L << lev)) / (2.0 * (1.0 - 1.0 / (1L << kappa)));
                        estimate += c_j * sumLev;
                        System.arraycopy(nextLevel, 0, currentLevel, 0, nextSize);
                        currentSize = nextSize;
                    }
                    sPriv += estimate;
                }
                if (j < height) {
                    int pathBit = (timeStep >> (height - 1 - j)) & 1;
                    nodeIndex = (pathBit == 0) ? 2 * nodeIndex + 1 : 2 * nodeIndex + 2;
                }
            }

            currentSums.put(entry.getKey(), sPriv);
        }

        // --- Step 4: Empty-key prediction (Algorithm 3) — the CPU-dominant inner loop ---
        // For each key, simulate prediction over ALL remaining time steps
        // This is the O(numKeys * maxTimeSteps) hot loop that dominates snapshot() in production
        System.out.println("[enclave-bg] Running empty-key prediction (maxTimeSteps=" + maxTimeSteps + ")");

        double mu = 5.0;
        // Precompute probit (mirrors PROBIT_1_MINUS_BETA constant)
        // Phi^{-1}(1 - 1e-5) ≈ 4.2649
        double probit = 4.2649;

        int predictionsRun = 0;
        for (Map.Entry<String, double[]> entry : forest.entrySet()) {
            double[] tree = entry.getValue();
            double sigma = 1.5; // approximate

            // For each future time step, compute predicted noisy count and tau
            // Mirrors runEmptyKeyPrediction() inner loop
            for (int tr_p = 1; tr_p < maxTimeSteps; tr_p++) {
                // Compute getTotalSum(tr_p) — lightweight version
                int ib = tr_p + 1;
                int ni = 0;
                double noisyCount = 0.0;

                for (int j = 0; j <= height; j++) {
                    int lb = (ib >> (height - j)) & 1;
                    if (lb == 1) {
                        int ls = (ni == 0) ? 0 : (ni % 2 == 0 ? ni - 1 : ni);
                        if (ls < tree.length) {
                            noisyCount += tree[ls];
                        }
                    }
                    if (j < height) {
                        int pb = (tr_p >> (height - 1 - j)) & 1;
                        ni = (pb == 0) ? 2 * ni + 1 : 2 * ni + 2;
                    }
                }

                // Compute tau = sqrt(variance) * probit — mirrors computeTau()
                double variance = (sigma * sigma) / (2.0 * (1.0 - 1.0 / (1L << height)));
                double tau = Math.sqrt(variance) * probit;

                if (noisyCount >= mu + tau) {
                    predictionsRun++;
                    break; // key predicted to be released
                }
            }
        }

        System.out.println("[enclave-bg] Prediction complete: " + predictionsRun + " keys predicted for release");

        // --- Step 5: Produce sorted histogram — mirrors produceHistogram() ---
        LinkedHashMap<String, Long> histogram = new LinkedHashMap<>();
        currentSums.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .forEach(e -> histogram.put(e.getKey(), Math.max(0L, Math.round(e.getValue()))));

        System.out.println("[enclave-bg] Histogram produced with " + histogram.size() + " keys");
        return histogram;
    }

    // ---- Test 8: Minimal crash reproduction ----

    @Override
    public boolean startSecureRandomThread(int arraySize) {
        if (asyncThread != null && asyncThread.isAlive()) {
            return false;
        }
        asyncThread = new Thread(() -> {
            System.out.println("[enclave-bg] SecureRandom thread started, filling double["
                    + arraySize + "] with Gaussian noise");
            SecureRandom rng = new SecureRandom();
            double[] buf = new double[arraySize];
            for (int i = 0; i < buf.length; i++) {
                buf[i] = rng.nextGaussian();
            }
            // prevent dead-code elimination
            double sum = 0;
            for (double v : buf) sum += v;
            System.out.println("[enclave-bg] SecureRandom thread done, sum=" + sum);
        }, "enclave-secure-random");
        asyncThread.setDaemon(true);
        asyncThread.start();
        return true;
    }

    @Override
    public void noop() {
        // intentionally empty — the ECALL entry/exit is the trigger
    }

    // ---- Test 9: Control test with java.util.Random ----

    @Override
    public boolean startPlainRandomThread(int arraySize) {
        if (asyncThread != null && asyncThread.isAlive()) {
            return false;
        }
        asyncThread = new Thread(() -> {
            System.out.println("[enclave-bg] Plain Random thread started, filling double["
                    + arraySize + "] with Gaussian noise");
            Random rng = new Random(42);
            double[] buf = new double[arraySize];
            for (int i = 0; i < buf.length; i++) {
                buf[i] = rng.nextGaussian();
            }
            // prevent dead-code elimination
            double sum = 0;
            for (double v : buf) sum += v;
            System.out.println("[enclave-bg] Plain Random thread done, sum=" + sum);
        }, "enclave-plain-random");
        asyncThread.setDaemon(true);
        asyncThread.start();
        return true;
    }

    // ---- Test 10: Crypto with SecureRandom nonces (production-like) ----

    @Override
    public boolean startCryptoThread(int iterations) {
        if (asyncThread != null && asyncThread.isAlive()) {
            return false;
        }
        asyncThread = new Thread(() -> {
            try {
                System.out.println("[enclave-bg] Crypto thread started (SecureRandom nonces, "
                        + iterations + " iterations)");
                SecretKey key = KeyGenerator.getInstance("ChaCha20").generateKey();
                SecureRandom rng = new SecureRandom();
                byte[] plaintext = new byte[1024];
                rng.nextBytes(plaintext);

                for (int i = 0; i < iterations; i++) {
                    // Generate nonce with SecureRandom (same as production SealedPayload)
                    byte[] nonce = new byte[12];
                    rng.nextBytes(nonce);

                    Cipher cipher = Cipher.getInstance("ChaCha20-Poly1305");
                    cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(nonce));
                    byte[] ciphertext = cipher.doFinal(plaintext);

                    // Decrypt to verify
                    cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(nonce));
                    cipher.doFinal(ciphertext);
                }
                System.out.println("[enclave-bg] Crypto thread done (" + iterations + " cycles)");
            } catch (Exception e) {
                System.out.println("[enclave-bg] Crypto thread FAILED: " + e.getMessage());
                e.printStackTrace(System.out);
            }
        }, "enclave-crypto");
        asyncThread.setDaemon(true);
        asyncThread.start();
        return true;
    }

    // ---- Test 12: Cipher with counter-based nonces (no SecureRandom anywhere) ----

    @Override
    public boolean startCipherCounterNonceThread(int iterations) {
        if (asyncThread != null && asyncThread.isAlive()) {
            return false;
        }
        asyncThread = new Thread(() -> {
            try {
                System.out.println("[enclave-bg] Cipher counter-nonce thread started ("
                        + iterations + " iterations)");
                // Fixed key — no KeyGenerator (uses SecureRandom internally), no SecureRandom
                byte[] keyBytes = new byte[32];
                new Random(42).nextBytes(keyBytes);
                SecretKey key = new javax.crypto.spec.SecretKeySpec(keyBytes, "ChaCha20");

                byte[] plaintext = new byte[1024];
                new Random(99).nextBytes(plaintext);

                // Counter-based nonce: write a monotonically increasing 96-bit counter into
                // the nonce bytes. This guarantees uniqueness without any SecureRandom call.
                byte[] nonce = new byte[12];

                for (int i = 0; i < iterations; i++) {
                    // Encode counter i into the last 4 bytes of the nonce (big-endian)
                    nonce[8]  = (byte)(i >> 24);
                    nonce[9]  = (byte)(i >> 16);
                    nonce[10] = (byte)(i >> 8);
                    nonce[11] = (byte) i;

                    Cipher cipher = Cipher.getInstance("ChaCha20-Poly1305");
                    cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(nonce));
                    byte[] ciphertext = cipher.doFinal(plaintext);

                    // Decrypt to exercise the full cipher path in both directions
                    cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(nonce));
                    cipher.doFinal(ciphertext);
                }
                System.out.println("[enclave-bg] Cipher counter-nonce thread done (" + iterations + " cycles)");
            } catch (Exception e) {
                System.out.println("[enclave-bg] Cipher counter-nonce thread FAILED: " + e.getMessage());
                e.printStackTrace(System.out);
            }
        }, "enclave-cipher-counter");
        asyncThread.setDaemon(true);
        asyncThread.start();
        return true;
    }

    // ---- Test 13: Full production snapshot, pure Java (no SecureRandom), concurrent addContribution() ----

    private volatile Map<String, Long> fullSnapshotNoSecureRandomResult;
    private volatile Throwable fullSnapshotNoSecureRandomError;

    @Override
    public boolean startFullSnapshotNoSecureRandomThread(int numKeys, int maxTimeSteps) {
        if (asyncThread != null && asyncThread.isAlive()) {
            return false;
        }
        fullSnapshotNoSecureRandomResult = null;
        fullSnapshotNoSecureRandomError = null;

        asyncThread = new Thread(() -> {
            try {
                System.out.println("[enclave-bg] Full-snapshot-no-SecureRandom thread started ("
                        + numKeys + " keys, " + maxTimeSteps + " steps)");
                fullSnapshotNoSecureRandomResult = runDPSnapshotPureJava(numKeys, maxTimeSteps);
                System.out.println("[enclave-bg] Full-snapshot-no-SecureRandom thread completed successfully, "
                        + fullSnapshotNoSecureRandomResult.size() + " keys");
            } catch (Throwable t) {
                fullSnapshotNoSecureRandomError = t;
                System.out.println("[enclave-bg] Full-snapshot-no-SecureRandom thread FAILED: " + t.getMessage());
                t.printStackTrace(System.out);
            }
        }, "enclave-snap-pure-java");
        asyncThread.setDaemon(true);
        asyncThread.start();
        return true;
    }

    @Override
    public Map<String, Long> pollFullSnapshotNoSecureRandomResult() {
        if (fullSnapshotNoSecureRandomError != null) {
            throw new RuntimeException("Full snapshot (no SecureRandom) failed: "
                    + fullSnapshotNoSecureRandomError.getMessage(), fullSnapshotNoSecureRandomError);
        }
        return fullSnapshotNoSecureRandomResult; // null if not yet complete
    }

    /**
     * Mirrors {@link #runDPSnapshot} exactly, but replaces every {@code SecureRandom} call
     * with {@code java.util.Random}. This is the production-equivalent after the Crash 1/2
     * fixes — the workload that still causes Crash 3 (the silent hang).
     */
    private Map<String, Long> runDPSnapshotPureJava(int numKeys, int maxTimeSteps) {
        // --- Step 1: Swap staging buffers ---
        final Map<String, Double> drainedCounts;
        final Map<String, Set<String>> drainedUsers;
        synchronized (bufferLock) {
            drainedCounts = this.stagingCounts;
            drainedUsers  = this.stagingUsers;
            this.stagingCounts = new HashMap<>();
            this.stagingUsers  = new HashMap<>();
        }
        System.out.println("[enclave-bg] Drained " + drainedCounts.size() + " keys from staging buffers");

        // --- Step 2: Build binary aggregation trees with Gaussian noise (pure java.util.Random) ---
        Random rng = new Random();  // no SecureRandom
        int height    = (int) Math.ceil(Math.log(maxTimeSteps) / Math.log(2));
        int numLeaves = 1 << height;
        int treeSize  = 2 * numLeaves - 1;

        Map<String, double[]> forest = new HashMap<>();
        Set<String> allKeys = new HashSet<>(drainedCounts.keySet());
        for (int k = 0; k < numKeys; k++) {
            allKeys.add("synth-key-" + k);
        }

        System.out.println("[enclave-bg] Building " + allKeys.size()
                + " aggregation trees (treeSize=" + treeSize + ") with Random");

        for (String key : allKeys) {
            double sigma = 1.5 + rng.nextDouble();   // pure Java, no native entropy
            double[] tree = new double[treeSize];
            for (int i = 0; i < treeSize; i++) {
                tree[i] = rng.nextGaussian() * sigma; // pure Java Box-Muller
            }
            forest.put(key, tree);

            double count = drainedCounts.getOrDefault(key, 0.0);
            for (int t = 0; t < Math.min(maxTimeSteps, numLeaves); t++) {
                int idx = numLeaves - 1 + t;
                double val = count + rng.nextGaussian() * 0.1;
                while (idx > 0) { tree[idx] += val; idx = (idx - 1) / 2; }
                tree[0] += val;
            }
        }

        // --- Step 3: Honaker variance-reduced prefix sums ---
        Map<String, Double> currentSums = new HashMap<>();
        int[] currentLevel = new int[numLeaves];
        int[] nextLevel    = new int[numLeaves];

        System.out.println("[enclave-bg] Computing Honaker prefix sums for " + forest.size() + " keys");

        for (Map.Entry<String, double[]> entry : forest.entrySet()) {
            double[] tree      = entry.getValue();
            int timeStep       = Math.min(maxTimeSteps - 1, numLeaves - 1);
            int indexBinary    = timeStep + 1;
            int nodeIndex      = 0;
            double sPriv       = 0.0;

            for (int j = 0; j <= height; j++) {
                int levelBit = (indexBinary >> (height - j)) & 1;
                if (levelBit == 1) {
                    int leftSibling;
                    if (nodeIndex == 0) leftSibling = 0;
                    else if (nodeIndex % 2 == 0) leftSibling = nodeIndex - 1;
                    else leftSibling = nodeIndex;

                    int kappa    = height - j + 1;
                    double estimate = 0.0;
                    int currentSize = 0;
                    currentLevel[currentSize++] = leftSibling;

                    for (int lev = 0; lev < kappa; lev++) {
                        double sumLev = 0;
                        int nextSize  = 0;
                        for (int ci = 0; ci < currentSize; ci++) {
                            int idx = currentLevel[ci];
                            if (idx < tree.length) {
                                sumLev += tree[idx];
                                if (lev < kappa - 1) {
                                    nextLevel[nextSize++] = 2 * idx + 1;
                                    nextLevel[nextSize++] = 2 * idx + 2;
                                }
                            }
                        }
                        double c_j = (1.0 / (1L << lev)) / (2.0 * (1.0 - 1.0 / (1L << kappa)));
                        estimate += c_j * sumLev;
                        System.arraycopy(nextLevel, 0, currentLevel, 0, nextSize);
                        currentSize = nextSize;
                    }
                    sPriv += estimate;
                }
                if (j < height) {
                    int pathBit = (timeStep >> (height - 1 - j)) & 1;
                    nodeIndex = (pathBit == 0) ? 2 * nodeIndex + 1 : 2 * nodeIndex + 2;
                }
            }
            currentSums.put(entry.getKey(), sPriv);
        }

        // --- Step 4: Empty-key prediction (Algorithm 3) — CPU-dominant inner loop ---
        System.out.println("[enclave-bg] Running empty-key prediction (maxTimeSteps=" + maxTimeSteps + ")");
        double mu     = 5.0;
        double probit = 4.2649; // Phi^{-1}(1 - 1e-5)
        int predictionsRun = 0;

        for (Map.Entry<String, double[]> entry : forest.entrySet()) {
            double[] tree = entry.getValue();
            double sigma  = 1.5;

            for (int tr_p = 1; tr_p < maxTimeSteps; tr_p++) {
                int ib  = tr_p + 1;
                int ni  = 0;
                double noisyCount = 0.0;

                for (int j = 0; j <= height; j++) {
                    int lb = (ib >> (height - j)) & 1;
                    if (lb == 1) {
                        int ls = (ni == 0) ? 0 : (ni % 2 == 0 ? ni - 1 : ni);
                        if (ls < tree.length) noisyCount += tree[ls];
                    }
                    if (j < height) {
                        int pb = (tr_p >> (height - 1 - j)) & 1;
                        ni = (pb == 0) ? 2 * ni + 1 : 2 * ni + 2;
                    }
                }

                double variance = (sigma * sigma) / (2.0 * (1.0 - 1.0 / (1L << height)));
                double tau      = Math.sqrt(variance) * probit;
                if (noisyCount >= mu + tau) { predictionsRun++; break; }
            }
        }
        System.out.println("[enclave-bg] Prediction complete: " + predictionsRun + " keys predicted for release");

        // --- Step 5: Produce sorted histogram ---
        LinkedHashMap<String, Long> histogram = new LinkedHashMap<>();
        currentSums.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .forEach(e -> histogram.put(e.getKey(), Math.max(0L, Math.round(e.getValue()))));

        System.out.println("[enclave-bg] Histogram produced with " + histogram.size() + " keys");
        return histogram;
    }

    // ---- Test 14: Option A — snapshot on bg thread, encrypt on poll ECALL ----

    private volatile Map<String, Long> optionAPlaintextResult;
    private volatile Throwable optionAError;

    @Override
    public boolean startOptionASnapshotThread(int numKeys, int maxTimeSteps) {
        if (asyncThread != null && asyncThread.isAlive()) {
            return false;
        }
        optionAPlaintextResult = null;
        optionAError = null;

        asyncThread = new Thread(() -> {
            try {
                System.out.println("[enclave-bg] Option A snapshot thread started ("
                        + numKeys + " keys, " + maxTimeSteps + " steps) — NO encryption here");
                // Only snapshot(), no encrypt() — mirrors Option A fix
                optionAPlaintextResult = runDPSnapshotPureJava(numKeys, maxTimeSteps);
                System.out.println("[enclave-bg] Option A snapshot thread done, "
                        + optionAPlaintextResult.size() + " keys ready for encryption on poll ECALL");
            } catch (Throwable t) {
                optionAError = t;
                System.out.println("[enclave-bg] Option A snapshot thread FAILED: " + t.getMessage());
                t.printStackTrace(System.out);
            }
        }, "enclave-option-a-snap");
        asyncThread.setDaemon(true);
        asyncThread.start();
        return true;
    }

    @Override
    public int pollOptionAResult() {
        if (optionAError != null) {
            throw new RuntimeException("Option A snapshot failed: " + optionAError.getMessage(), optionAError);
        }
        Map<String, Long> plaintext = optionAPlaintextResult;
        if (plaintext == null) {
            return -1; // not ready yet
        }
        optionAPlaintextResult = null;

        // Encrypt synchronously HERE — on the ECALL thread, no background thread running.
        // This mirrors AbstractDataPerturbationServiceProvider.pollEncryptedSnapshot() under Option A.
        try {
            // Serialize the histogram map to bytes (mirrors JSON serialization in production)
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Long> e : plaintext.entrySet()) {
                if (!first) sb.append(',');
                sb.append('"').append(e.getKey()).append("\":").append(e.getValue());
                first = false;
            }
            sb.append('}');
            byte[] payloadBytes = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);

            // Encrypt with ChaCha20-Poly1305, counter-based nonce (no SecureRandom)
            byte[] keyBytes = new byte[32];
            new Random(42).nextBytes(keyBytes);
            SecretKey key = new javax.crypto.spec.SecretKeySpec(keyBytes, "ChaCha20");
            byte[] nonce = new byte[12]; // all-zero nonce; only one encryption per poll, so unique
            Cipher cipher = Cipher.getInstance("ChaCha20-Poly1305");
            cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(nonce));
            byte[] ciphertext = cipher.doFinal(payloadBytes);

            System.out.println("[enclave] Option A poll: encrypted " + plaintext.size()
                    + " keys -> " + ciphertext.length + " ciphertext bytes (on ECALL thread)");
            return ciphertext.length;
        } catch (Exception e) {
            throw new RuntimeException("Option A poll encryption failed: " + e.getMessage(), e);
        }
    }

    // ---- Test 11: Crypto with java.util.Random nonces (no SecureRandom) ----

    @Override
    public boolean startCryptoWithPlainRandomThread(int iterations) {
        if (asyncThread != null && asyncThread.isAlive()) {
            return false;
        }
        asyncThread = new Thread(() -> {
            try {
                System.out.println("[enclave-bg] Crypto (plain Random nonces) thread started, "
                        + iterations + " iterations");
                // Pre-generate key outside the loop to avoid SecureRandom in KeyGenerator
                // Use a fixed key instead
                byte[] keyBytes = new byte[32];
                new Random(42).nextBytes(keyBytes);
                SecretKey key = new javax.crypto.spec.SecretKeySpec(keyBytes, "ChaCha20");

                Random rng = new Random(42);
                byte[] plaintext = new byte[1024];
                rng.nextBytes(plaintext);

                for (int i = 0; i < iterations; i++) {
                    // Generate nonce with plain Random (no native entropy)
                    byte[] nonce = new byte[12];
                    rng.nextBytes(nonce);

                    Cipher cipher = Cipher.getInstance("ChaCha20-Poly1305");
                    cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(nonce));
                    byte[] ciphertext = cipher.doFinal(plaintext);

                    // Decrypt to verify
                    cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(nonce));
                    cipher.doFinal(ciphertext);
                }
                System.out.println("[enclave-bg] Crypto (plain Random) thread done ("
                        + iterations + " cycles)");
            } catch (Exception e) {
                System.out.println("[enclave-bg] Crypto (plain Random) thread FAILED: "
                        + e.getMessage());
                e.printStackTrace(System.out);
            }
        }, "enclave-crypto-plain");
        asyncThread.setDaemon(true);
        asyncThread.start();
        return true;
    }

    // ---- Test 5: Enclave-side Thread.start() with synchronous pthread_create shim ----

    @Override
    public int runInlineThread(int fibN) {
        // Holds the result written by the enclave-side thread.
        // Must be a single-element array so the lambda can write to it.
        int[] result = {-1};
        Thread t = new Thread(() -> {
            System.out.println("[enclave-inline] thread started on TCS id="
                    + Thread.currentThread().getId() + ", computing fib(" + fibN + ")");
            result[0] = fib(fibN);
            System.out.println("[enclave-inline] thread finished, fib(" + fibN + ")=" + result[0]);
        }, "enclave-inline-fib");
        // Under the synchronous pthread_create shim this blocks until the lambda returns.
        t.start();
        System.out.println("[enclave-inline] Thread.start() returned, result=" + result[0]);
        return result[0];
    }

    @Override
    public boolean isInlineThreadSynchronous() {
        boolean[] flag = {false};
        Thread t = new Thread(() -> {
            flag[0] = true;
            System.out.println("[enclave-inline] synchrony flag set");
        }, "enclave-inline-flag");
        t.start();
        // If pthread_create is truly synchronous, flag[0] must be true here.
        boolean observed = flag[0];
        System.out.println("[enclave-inline] flag after Thread.start() = " + observed);
        return observed;
    }

    @Override
    public long runNInlineThreads(int n, int fibN) {
        long total = 0;
        for (int i = 0; i < n; i++) {
            final int idx = i;
            long[] partial = {0};
            Thread t = new Thread(() -> {
                partial[0] = fib(fibN);
                System.out.println("[enclave-inline] thread " + idx + " done, fib=" + partial[0]);
            }, "enclave-inline-" + i);
            t.start();
            // Synchronous: partial[0] is set before t.start() returns.
            total += partial[0];
        }
        System.out.println("[enclave-inline] " + n + " threads completed, total=" + total);
        return total;
    }

    // ---- Host-side multithreading stress tests ----

    @Override
    public int echoInt(int value) {
        return value;
    }

    @Override
    public int echoIntWithWork(int value, int cpuLoops) {
        int acc = 0;
        for (int i = 0; i < cpuLoops; i++) {
            acc = (acc * 1103515245 + 12345) & 0x7fffffff;
        }
        // Touch acc so the JIT can't dead-code-eliminate the loop, but
        // always return the caller's value unchanged.
        return value | (acc & 0);
    }
}
