package com.nubeero.cia.api.keycloak;

import org.springframework.beans.factory.ObjectProvider;

import java.util.stream.Stream;

/**
 * Minimal {@link ObjectProvider} adapter for test wiring — wraps a single
 * resolved instance so the production syncer code can be exercised outside
 * a Spring context. Used by the manual-construction Keycloak ITs that
 * deliberately skip {@code @SpringBootTest} to avoid the ~5s per-class
 * boot tax on top of the Keycloak container's cold start.
 *
 * <p>Only the methods actually called by the syncers are meaningful
 * ({@code getIfAvailable}, {@code getObject}); the remaining {@link
 * ObjectProvider} surface returns the same instance for simplicity. If a
 * future test wants distinct provider semantics it should bring its own
 * adapter rather than extending this one.
 */
final class StaticObjectProvider<T> implements ObjectProvider<T> {

    private final T value;

    StaticObjectProvider(T value) {
        this.value = value;
    }

    @Override public T getObject()                         { return value; }
    @Override public T getObject(Object... args)           { return value; }
    @Override public T getIfAvailable()                    { return value; }
    @Override public T getIfUnique()                       { return value; }
    @Override public Stream<T> stream()                    { return Stream.of(value); }
    @Override public Stream<T> orderedStream()             { return Stream.of(value); }
}
