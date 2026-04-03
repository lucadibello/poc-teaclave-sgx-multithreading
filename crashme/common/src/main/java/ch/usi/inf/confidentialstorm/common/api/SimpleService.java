package ch.usi.inf.confidentialstorm.common.api;

import org.apache.teaclave.javasdk.common.annotations.EnclaveService;

@EnclaveService
public interface SimpleService {
    boolean startAsyncThread();
    void stopAsyncThread();
    boolean isAsyncThreadRunning();
    int someWork();
}
