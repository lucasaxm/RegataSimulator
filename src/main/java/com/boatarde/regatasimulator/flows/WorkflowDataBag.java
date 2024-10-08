package com.boatarde.regatasimulator.flows;

import java.util.HashMap;
import java.util.Map;

@SuppressWarnings("unchecked")
public class WorkflowDataBag {
    private final Map<WorkflowDataKey, Object> data = new HashMap<>();

    public void put(WorkflowDataKey key, Object value) {
        data.put(key, value);
    }

    public void putIfAbsent(WorkflowDataKey key, Object value) {
        data.putIfAbsent(key, value);
    }

    public <T> T get(WorkflowDataKey key, Class<T> clazz) {
        return clazz.cast(data.get(key));
    }

    public <T> T getGeneric(WorkflowDataKey key, Class<?> rawType, Class<?>... parameterTypes) {
        Object object = data.get(key);
        if (object == null) {
            return null;
        }

        // Check if the object is instance of the raw type
        if (!rawType.isInstance(object)) {
            throw new ClassCastException("Object is not of the expected raw type " + rawType.getName());
        }

        if (rawType.getTypeParameters().length != parameterTypes.length) {
            throw new IllegalArgumentException("Incorrect number of type parameters.");
        }

        return (T) rawType.cast(object);
    }

    public void remove(WorkflowDataKey key) {
        data.remove(key);
    }
}
