# Proof of Concept - Java multi-threading on SGX2 using Apache Teaclave

## Goal

This PoC investigates whether Apache Teaclave Java SDK supports enclave-side multi-threading, specifically for the following pattern:

1. The host launches a long-running job inside an SGX enclave via an ECALL.
2. The job runs in a background thread inside the enclave.
3. The host polls for the result using a separate ECALL, without blocking on the first one.
4. Once the job finishes, the host can immediately launch a new job in the same enclave.

This pattern is motivated by the need to minimise ECALL lock contention: rather than keeping a single ECALL open for the duration of a long job, the host fires a "start" ECALL and returns, then uses lightweight "poll" ECALLs to check progress.

## What is tested

The test suite (`CrashMe.java`) runs six sequential tests, each in its own dedicated host thread (required by Teaclave: the enclave must be created and destroyed in the same thread):

| Test | Description |
|------|-------------|
| **Test 0** | Baseline: create enclave, perform a single ECALL (`someWork`), destroy. No threads involved. |
| **Test 1** | Start a background thread inside the enclave and verify it is immediately detected as running. |
| **Test 2** | Verify that `someWork()` (a concurrent ECALL) succeeds while the background thread is running. |
| **Test 3** | Poll `isAsyncThreadRunning()` and call `someWork()` repeatedly until the thread finishes. Verifies all results are valid and the thread eventually stops. |
| **Test 4** | Verify the enclave remains usable (further ECALLs succeed) after the background thread has finished. |
| **Test 5** | Simulate the target scenario: start a job, poll until done, then immediately start the next job - repeated 5 times in the same enclave. |

Each test has a 60-second timeout. If the enclave does not complete (including destroy) within that window, the test is marked as failed.

## Results

All functional tests pass. The enclave correctly:
- Starts threads and reports their running state via ECALL
- Accepts concurrent ECALLs while a background thread is running
- Completes background threads and reflects their termination via ECALL
- Supports repeated sequential job lifecycles within a single enclave instance

**Known limitation: enclave shutdown blocks when a thread has been started.**
`enclave.destroy()` hangs indefinitely if any thread was ever started inside the enclave, even after that thread has finished. This is a known limitation of the Teaclave/SGX runtime and is consistent with findings from the Lejacon paper ([IEEE, 2023](https://ieeexplore.ieee.org/document/10172889)), which notes:

> SGX does not allow for parallel computing on both sides. It remains one of the future directions to extend Lejacon such that it allows contexts containing unserializable objects to be synchronized and parallel computing to be performed on both sides.

For this prototype the shutdown issue is acceptable: the target use case only requires the jobs to run and complete correctly, not that the enclave shuts down cleanly afterwards.

## Notes

- `Thread.sleep()` does not work correctly inside the enclave JVM. The background job simulation uses a busy-wait loop (`System.currentTimeMillis()`) instead.
- The enclave must be created and destroyed on the same host thread. Each test therefore runs inside a dedicated thread, with `main` enforcing the timeout via `Thread.join()` - check out this other Proof of Concept where this issue is explored in more detail: [poc-teaclave-crash-isolate-svm](https://github.com/lucadibello/poc-teaclave-crash-isolate-svm).
- Tested on SGX2 hardware with Apache Teaclave Java SDK 0.1.0, Java 17. Use the devcontainer setup in this repo for a ready-to-run environment.
