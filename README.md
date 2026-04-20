# Proof of Concept — Concurrent ECALLs on SGX using Modified Apache Teaclave

>[!NOTE]
>This is a testbed repository that validates the modified SDK's concurrency capabilities. The test suite is now maintained in the [modified SDK repository](https://github.com/lucadibello/teaclave-java-tee-sdk/blob/master/test/host/src/main/java/org/apache/teaclave/javasdk/test/host/TestEnclaveConcurrency.java).

## Goal

This PoC validates that a **modified version of the Apache Teaclave Java SDK** supports **concurrent ECALLs from multiple host threads** into a single SGX enclave. This was previously impossible due to illegal thread registration and TCS management issues in the upstream SDK.

The modified SDK is available at: [lucadibello/teaclave-java-tee-sdk](https://github.com/lucadibello/teaclave-java-tee-sdk)

## Quick Start

```bash
make build test          # Build + run tests (Standard CI output)
make build test-debug    # Build + run tests with full native logs
```

### Key Improvements
1. **Multi-threading Support:** Enables concurrent ingestion and processing within the same enclave.
2. **TCS Cache Mode:** Implements a per-TCS `IsolateThread` cache to prevent "zombie" threads and illegal registrations in GraalVM.
3. **Synchronous Pthread Shim:** Ensures enclave-side `Thread.start()` executes safely within the calling thread's context.

## What is tested

The test suite (`CrashMe.java`) uses **JUnit 6** to stress various concurrency patterns:

| Test Group | Description |
|------------|-------------|
| **Baseline** | Verifies basic ECall entry/exit and clean enclave destruction. |
| **Host Concurrency** | Fires simultaneous ECALLs from multiple host threads. |
| **Enclave GC** | Triggers enclave-side GC pressure while host threads are active. |
| **Production DP** | Replicates the "Snapshot + Ingestion" pattern used in production. |
| **SecureRandom** | Stresses the native entropy OCALLs from background threads. |
| **Inline Threads** | Validates the safe execution of enclave-side `java.lang.Thread`. |

## Running

The project uses a `Makefile` to simplify the build and test process:

```bash
# Display all available targets
make help

# Build the entire project (Common, Enclave, Host)
make build

# Run tests via Maven Surefire (Standard CI output)
make test

# Run tests via JUnit Console Launcher (Recommended for debugging)
# This bypasses Surefire's stream capture to show raw native enclave logs.
make test-debug

# Run a specific test method
make test-method METHOD=host_concurrentEcalls

# Run a specific test method with full native logs
make test-method-debug METHOD=host_concurrentEcalls

# Run a manual crash triage
make run-local
```

## Debugging & Logging

Logs are prefixed to help identify the source of each message:
- `[host]` - Java code running on the Host JVM.
- `[enclave]` - Java code running inside the Enclave Isolate.
- `[pool:]` / `[destroy]` - Low-level native logs from the SGX shim.

**Note:** If you use `make test`, native logs may be hidden or reported as "Corrupted Channel" by Surefire. Use `make test-debug` (or `test-method-debug`) to see the full interleaved output of both Host and Enclave.


## Requirements

- **Hardware:** SGX2-enabled hardware (AES-M, `/dev/sgx_enclave`).
- **Software:** Intel SGX SDK, GraalVM with Native Image, and the [Modified Teaclave SDK](https://github.com/lucadibello/teaclave-java-tee-sdk).
- **Permissions:** `sudo` access is required for interacting with the SGX device driver.

## Technical background

Detailed root cause analysis and the architectural solution (per-TCS IsolateThread cache) can be found in the patched SDK repository: [lucadibello/teaclave-java-tee-sdk - MULTITHREADING.md](https://github.com/lucadibello/teaclave-java-tee-sdk/blob/multithreading-support/MULTITHREADING.md).
