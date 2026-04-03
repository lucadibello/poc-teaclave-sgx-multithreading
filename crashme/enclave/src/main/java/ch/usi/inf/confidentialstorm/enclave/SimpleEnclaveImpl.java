package ch.usi.inf.confidentialstorm.enclave;

import ch.usi.inf.confidentialstorm.common.api.SimpleService;
import com.google.auto.service.AutoService;

@AutoService(SimpleService.class)
public class SimpleEnclaveImpl implements SimpleService {

    private Thread asyncThread;
    
    @Override
    public boolean startAsyncThread() {
        asyncThread = new Thread(() -> {
            System.out.println("Async thread started in enclave, thread ID: " + Thread.currentThread
                    ().getId());
            try {
                Thread.sleep(3000); // Simulate some work
                fib(30); // Simulate CPU-intensive work
                System.out.println("Async thread finished work in enclave, thread ID: " + Thread.currentThread().getId());
            } catch (InterruptedException e) {
                System.out.println("Async thread interrupted in enclave, thread ID: " + Thread.currentThread
                        ().threadId());
                return;
            }});
        asyncThread.setDaemon(true); // Ensure the thread does not prevent enclave shutdown
        asyncThread.start();
        return true;
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
