# Proof of Concept — Concurrent ECALLs on SGX using Apache Teaclave

## Goal

This PoC validates that the modified Apache Teaclave Java SDK supports **concurrent ECALLs
from multiple host threads** into a single SGX enclave. The target pattern is:

1. A host thread continuously calls `addContribution()` (stream ingestion).
2. A separate host thread periodically calls a snapshot ECALL while ingestion continues.
3. Both threads operate concurrently on the same enclave instance without crashing or hanging.

This pattern was previously impossible with the upstream Teaclave SDK. See
`references/teaclave-java-tee-sdk/MULTITHREADING.md` for a full technical explanation of
the root causes and the fix.

## What is tested

The test suite (`CrashMe.java`) is now a **JUnit 5** suite. Each test runs in a dedicated
subprocess to ensure clean native SGX state.

| Test Group | Description | Status |
|------------|-------------|--------|
| **0. Baseline** | Single ECALL + clean `enclave.destroy()` | Pass |
| **1. Host Concurrency** | Concurrent ECALLs from multiple host threads (stress tests) | Pass |
| **2. Enclave GC** | Enclave-side background GC pressure + host ECALLs | Pass |
| **3. Production DP** | Full DP snapshot pattern (snapshot thread + ingestion ECALLs) | Pass |
| **4. SecureRandom** | `SecureRandom` background thread (OCALLs) + host ECALLs | Pass |
| **5. Inline Threads** | Enclave-side `Thread.start()` (synchronous shim) validation | Pass |

### Test details

- **`baseline_singleEcall`** — validates TCS cache mode initializes and `enclave.destroy()` completes cleanly.
- **`host_concurrentEcalls`** — the primary validation: 4 host threads fire 200 ECALLs each simultaneously. Reuses cached `IsolateThread` via `enter()`.
- **`enclave_gcPressureBgThread`** — enclave-side thread allocates heavily (HashMap forests) to trigger GC while host threads fire ECALLs.
- **`enclave_dpSnapshotPattern`** — replicates Confidential Storm: background snapshot thread + concurrent `addContribution()` ECALLs.
- **`enclave_secureRandomBgThread`** — `SecureRandom` background thread (native OCALLs) while host threads fire `noop()`.
- **`enclave_inlineThread*`** — validates the `pthread_create` shim: enclave-side `Thread.start()` is synchronous on the calling TCS, preventing "zombie" `IsolateThread` hangs.
- **`host_*` stress tests** — exercise the TCS cache under cold-start storms, sustained throughput, thread churn, and capacity saturation.

## Execution model — Dispatcher / Worker

To ensure a fresh native state for every test, `CrashMe` uses a **dispatcher/worker** pattern:

1. **Dispatcher** (Maven/Surefire): Runs the `@Test` method. It identifies itself as the dispatcher because the `CRASHME_SUBPROCESS` environment variable is unset. It forks a new JVM using `sudo java -jar crashme.jar <testName>`.
2. **Worker** (Forked JVM): Launched by `CrashMe.main()`. It sets `CRASHME_SUBPROCESS=<testName>`. `@BeforeEach` loads the enclave, the test logic runs, `@AfterEach` destroys the enclave, and the JVM exits.

The dispatcher captures the worker's output and asserts an exit code of `0`.

## Running

```bash
# Build the project
mvn clean install -DskipTests

# Run all tests via JUnit (requires sudo for the subprocesses)
# Ensure -Dcrashme.jar points to the host JAR
mvn test -pl host -am -Dcrashme.jar=$(pwd)/host/target/crashme-host.jar

# Run a specific test
mvn test -pl host -Dtest=CrashMe#host_concurrentEcalls -Dcrashme.jar=...

# Run a worker directly (for debugging)
sudo CRASHME_SUBPROCESS=baseline_singleEcall \
     java -jar host/target/crashme-host.jar baseline_singleEcall
```

## Notes

- Requires real SGX2 hardware with the Intel SGX SDK and kernel driver installed.
  Use the provided devcontainer for a ready-to-run environment.
- The `pthread_create` shim ensures that `Thread.start()` inside the enclave executes synchronously on the calling thread's TCS, avoiding illegal thread registrations in GraalVM.
- The enclave must be created and destroyed on the same host thread (in the worker process).
- `Thread.sleep()` does not work reliably inside the enclave JVM. Background thread simulations use `System.currentTimeMillis()` busy-waits instead.
- Tested on SGX2 hardware with the modified Teaclave SDK (April 2026), Java 17.

## Technical background

The root causes and the per-TCS IsolateThread cache solution are documented in detail in:

- `references/teaclave-java-tee-sdk/MULTITHREADING.md` — design, implementation, and test results
- `enclave-multithreading-findings.md` — the 14-experiment root cause analysis on real SGX hardware
