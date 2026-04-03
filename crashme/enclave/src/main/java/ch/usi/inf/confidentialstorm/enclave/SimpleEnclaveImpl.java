package ch.usi.inf.confidentialstorm.enclave;

import ch.usi.inf.confidentialstorm.common.api.SimpleService;
import com.google.auto.service.AutoService;

@AutoService(SimpleService.class)
public class SimpleEnclaveImpl implements SimpleService {

    private Thread asyncThread;
    
    @Override
    public boolean startAsyncThread() {
        asyncThread = new Thread(() -> {
            System.out.println("Async thread started in enclave, thread ID: " + Thread.currentThread().getId());
            // Busy-wait to simulate a long-running job without relying on Thread.sleep(),
            // which may not work correctly inside the enclave JVM.
            long end = System.currentTimeMillis() + 10_000;
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
            try {
                asyncThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
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
}
