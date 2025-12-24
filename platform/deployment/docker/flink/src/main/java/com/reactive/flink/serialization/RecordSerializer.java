package com.reactive.flink.serialization;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;

/**
 * Kryo serializer for Java records.
 *
 * Flink's default Kryo FieldSerializer doesn't support Java records.
 * This serializer uses the record's canonical constructor and components
 * to properly serialize/deserialize record instances.
 *
 * Implements Serializable for Flink's ExecutionConfig registration.
 */
public class RecordSerializer extends Serializer<Record> implements Serializable {
    private static final long serialVersionUID = 1L;

    private final Class<? extends Record> recordClass;
    private transient RecordComponent[] components;
    private transient Constructor<?> constructor;

    public RecordSerializer(Class<? extends Record> recordClass) {
        this.recordClass = recordClass;
        initTransientFields();
    }

    private void initTransientFields() {
        if (components != null && constructor != null) {
            return;
        }

        this.components = recordClass.getRecordComponents();

        // Get the canonical constructor (matches component types in order)
        Class<?>[] componentTypes = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            componentTypes[i] = components[i].getType();
        }

        try {
            this.constructor = recordClass.getDeclaredConstructor(componentTypes);
            this.constructor.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(
                    "Cannot find canonical constructor for record: " + recordClass.getName(), e);
        }
    }

    @Override
    public void write(Kryo kryo, Output output, Record record) {
        initTransientFields();
        for (RecordComponent component : components) {
            try {
                Object value = component.getAccessor().invoke(record);
                kryo.writeClassAndObject(output, value);
            } catch (Exception e) {
                throw new RuntimeException(
                        "Failed to read component " + component.getName() + " from " + recordClass.getName(), e);
            }
        }
    }

    @Override
    public Record read(Kryo kryo, Input input, Class<Record> type) {
        initTransientFields();
        Object[] args = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            args[i] = kryo.readClassAndObject(input);
        }

        try {
            return (Record) constructor.newInstance(args);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to construct record " + recordClass.getName(), e);
        }
    }
}
