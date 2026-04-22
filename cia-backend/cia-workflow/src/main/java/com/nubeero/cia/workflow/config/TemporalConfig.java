package com.nubeero.cia.workflow.config;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

    @Bean
    public WorkerFactory workerFactory(WorkflowClient client) {
        return WorkerFactory.newInstance(client);
    }
}
