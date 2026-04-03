package ch.usi.inf.confidentialstorm;

import ch.usi.inf.confidentialstorm.common.api.SimpleService;
import org.apache.teaclave.javasdk.host.Enclave;
import org.apache.teaclave.javasdk.host.EnclaveFactory;
import org.apache.teaclave.javasdk.host.EnclaveType;
import org.apache.teaclave.javasdk.host.exception.EnclaveCreatingException;
import org.apache.teaclave.javasdk.host.exception.EnclaveDestroyingException;
import org.apache.teaclave.javasdk.host.exception.ServicesLoadingException;

import java.util.Iterator;

public class CrashMe {
    private static Enclave currentEnclave;

    public static SimpleService loadTestService() {
        // Create enclave for SimpleService
        // NOTE: the enclave is started in this thread!
        System.out.println("Starting enclave in thread: " + Thread.currentThread().getId());
        try {
            CrashMe.currentEnclave = EnclaveFactory.create(EnclaveType.TEE_SDK);
        } catch (EnclaveCreatingException e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to create enclave: " + e.getMessage());
        }
        System.out.println("Enclave started in thread: " + Thread.currentThread().getId());

        // Load the service into the enclave
        System.out.println("Loading SimpleService in thread: " + Thread.currentThread().getId());
        Iterator<SimpleService> iter = null;
        try {
            iter = currentEnclave.load(SimpleService.class);
        } catch (ServicesLoadingException e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to load SimpleService: " + e.getMessage());
        }
        if (!iter.hasNext()) {
            throw new RuntimeException("No SimpleService implementation found");
        }
        SimpleService simpleService = iter.next();
        System.out.println("SimpleService loaded in thread: " + Thread.currentThread().getId());
        return simpleService;
    }

    /**
     * Test 1 - Create and use an enclave in the same thread. Destroy in main thread
     */
    public static void test1() throws EnclaveDestroyingException  {
        SimpleService simpleService = loadTestService();

        // start a background thread inside the enclave
        boolean state = simpleService.startAsyncThread();
        if (!state) {
            throw new RuntimeException("Failed to start async thread in enclave");
        }

        // Wait for the async thread to finish
        while (simpleService.isAsyncThreadRunning()) {
            try {
                // make call when the thread is still running to see if it works
                int result = simpleService.someWork();
                System.out.println("Result of someWork while async thread is running: " + result);
                Thread.sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        // Ensure that the async thread has finished before destroying the enclave
        boolean isRunning = simpleService.isAsyncThreadRunning();
        System.out.println("Is async thread still running before destroy? " + isRunning);

        // Load the test service
        CrashMe.currentEnclave.destroy();
    }

    public static void main(String[] args) throws EnclaveDestroyingException, InterruptedException {
        System.out.println();
        test1();
    }
}
