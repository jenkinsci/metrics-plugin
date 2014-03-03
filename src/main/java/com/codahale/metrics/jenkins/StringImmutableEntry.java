package com.codahale.metrics.jenkins;

import java.util.Map;

/**
 * @author Stephen Connolly
 */
class StringImmutableEntry<T> implements Map.Entry<String, T> {
    private final String name;
    private final T value;

    public StringImmutableEntry(String name, T value) {
        this.name = name;
        this.value = value;
    }

    public String getKey() {
        return name;
    }

    public T getValue() {
        return value;
    }

    public T setValue(T value) {
        throw new UnsupportedOperationException();
    }
}
