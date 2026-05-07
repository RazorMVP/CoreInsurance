package com.nubeero.cia.api.security;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ControllerAuthorizationCoverageTest {

    private static final List<Class<?>> MAPPING_ANNOTATIONS = List.of(
            GetMapping.class,
            PostMapping.class,
            PutMapping.class,
            PatchMapping.class,
            DeleteMapping.class,
            RequestMapping.class
    );

    @Test
    void backOfficeRestEndpointsDeclareMethodAuthorization() throws Exception {
        List<String> missingAuthorization = new ArrayList<>();

        for (Class<?> controller : scanRestControllers()) {
            if (isPartnerScopeController(controller)) {
                continue;
            }

            boolean classAuthorized = controller.isAnnotationPresent(PreAuthorize.class);
            for (Method method : controller.getDeclaredMethods()) {
                if (!isRequestHandler(method)) {
                    continue;
                }

                if (!classAuthorized && !method.isAnnotationPresent(PreAuthorize.class)) {
                    missingAuthorization.add(controller.getSimpleName() + "#" + method.getName());
                }
            }
        }

        assertThat(missingAuthorization)
                .as("Every back-office REST handler must declare @PreAuthorize; partner API handlers are covered by PartnerScopeFilter")
                .isEmpty();
    }

    private List<Class<?>> scanRestControllers() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        List<Class<?>> controllers = new ArrayList<>();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        for (var beanDefinition : scanner.findCandidateComponents("com.nubeero.cia")) {
            controllers.add(Class.forName(beanDefinition.getBeanClassName(), false, classLoader));
        }
        return controllers;
    }

    private boolean isRequestHandler(Method method) {
        return Arrays.stream(method.getAnnotations())
                .anyMatch(annotation -> MAPPING_ANNOTATIONS.contains(annotation.annotationType()));
    }

    private boolean isPartnerScopeController(Class<?> controller) {
        return controller.getPackageName().equals("com.nubeero.cia.partner.controller")
                && !controller.getSimpleName().equals("PartnerAppController");
    }
}
