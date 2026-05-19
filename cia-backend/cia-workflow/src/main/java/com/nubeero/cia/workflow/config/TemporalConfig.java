package com.nubeero.cia.workflow.config;

import com.nubeero.cia.workflow.interceptor.ActivityThreadCleanup;
import com.nubeero.cia.workflow.interceptor.TenantAwareWorkerInterceptor;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerFactoryOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Slf4j
@Configuration
public class TemporalConfig {

    @Bean
    public WorkflowServiceStubs workflowServiceStubs(
            @Value("${cia.temporal.host:localhost:7233}") String host) {
        log.info("Connecting to Temporal at {}", host);
        return WorkflowServiceStubs.newInstance(
                WorkflowServiceStubsOptions.newBuilder().setTarget(host).build());
    }

    @Bean
    public WorkflowClient workflowClient(WorkflowServiceStubs stubs,
            @Value("${cia.temporal.namespace:default}") String namespace) {
        return WorkflowClient.newInstance(stubs,
                WorkflowClientOptions.newBuilder().setNamespace(namespace).build());
    }

    /**
     * Slice 1.8a wires the {@link TenantAwareWorkerInterceptor} into the
     * shared {@link WorkerFactory} so every worker registered by any module
     * (approval, NAICOM, NIID, notification, webhook, backfill) inherits
     * the per-activity thread-local cleanup. Modules contributing extra
     * cleanups register {@link ActivityThreadCleanup} beans;
     * {@code cleanups} arrives empty when no module has any.
     */
    @Bean
    public WorkerFactory workerFactory(
            WorkflowClient client,
            List<ActivityThreadCleanup> cleanups) {
        WorkerInterceptor tenantInterceptor = new TenantAwareWorkerInterceptor(cleanups);
        WorkerFactoryOptions options = WorkerFactoryOptions.newBuilder()
                .setWorkerInterceptors(tenantInterceptor)
                .build();
        log.info("Temporal WorkerFactory configured with TenantAwareWorkerInterceptor ({} cleanup hooks)",
                cleanups.size());
        return WorkerFactory.newInstance(client, options);
    }
}
