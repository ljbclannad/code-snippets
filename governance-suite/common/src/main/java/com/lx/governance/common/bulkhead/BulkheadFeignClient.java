package com.lx.governance.common.bulkhead;

import feign.Client;
import feign.Request;
import feign.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class BulkheadFeignClient implements Client {

    private static final Logger log = LoggerFactory.getLogger(BulkheadFeignClient.class);

    private final Client delegate;
    private final BulkheadThreadPoolManager poolManager;
    private final BulkheadProperties properties;

    public BulkheadFeignClient(Client delegate,
                                BulkheadThreadPoolManager poolManager,
                                BulkheadProperties properties) {
        this.delegate = delegate;
        this.poolManager = poolManager;
        this.properties = properties;
    }

    @Override
    public Response execute(Request request, Request.Options options) throws IOException {
        String serviceName = extractServiceName(request);

        if (!properties.isEnabled() || serviceName == null) {
            return delegate.execute(request, options);
        }

        ThreadPoolExecutor pool = poolManager.getOrCreatePool(serviceName);
        BulkheadProperties.PoolConfig config = properties.getConfigForService(serviceName);

        Callable<Response> task = () -> delegate.execute(request, options);
        Future<Response> future;

        try {
            future = pool.submit(task);
        } catch (RejectedExecutionException e) {
            log.warn("Bulkhead rejected request to service: {}, pool full (active={}/{}, queued={}/{})",
                serviceName,
                pool.getActiveCount(),
                pool.getMaximumPoolSize(),
                pool.getQueue().size(),
                pool.getQueue().size() + pool.getQueue().remainingCapacity());
            throw new IOException("Bulkhead rejected: " + serviceName, e);
        }

        try {
            Response response = future.get(config.getTimeoutSeconds(), TimeUnit.SECONDS);
            log.debug("Bulkhead executed request to {} successfully", serviceName);
            return response;

        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("Bulkhead timeout for service: {}, timeout: {}s", serviceName, config.getTimeoutSeconds());
            throw new IOException("Bulkhead timeout: " + serviceName, e);

        } catch (Exception e) {
            log.error("Bulkhead execution error for service: {}", serviceName, e);
            throw new IOException("Bulkhead error: " + serviceName, e);
        }
    }

    private String extractServiceName(Request request) {
        try {
            URI uri = URI.create(request.url());
            String host = uri.getHost();
            if (host != null && !host.isEmpty()) {
                return host;
            }
        } catch (Exception e) {
            log.debug("Failed to extract service name from URL: {}", request.url(), e);
        }
        return null;
    }
}
