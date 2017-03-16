package org.fiolino.common.reflection;

import java.lang.invoke.MethodHandle;

/**
 * Created by kuli on 14.03.17.
 */
public interface OneTimeExecution extends Registry {
    /**
     * Updates to a new return value.
     *
     * The original execution handle will not be triggered even if it never was yet.
     *
     * If the handle does not return a value, the new value is ignored completely. Otherwise it must match the target's return type.
     *
     * If there is some concurrent thread execution my target, then this methods waits until it's finished.
     *
     * @param newValue Sets this as the new result value
     */
    void updateTo(Object newValue);

    /**
     * Creates a registry builder for parameter-less handles where updates are expected to never or very rarely happen.
     *
     * @param execution The task which will be executed on the first run
     * @return A registry builder for that target
     */
    static OneTimeExecution createFor(MethodHandle execution) {
        return new OneTimeRegistryBuilder(execution, false);
    }

    /**
     * Creates a registry builder for parameter-less handles where updates are expected to happen frequently.
     *
     * @param execution The task which will be executed on the first run
     * @return A registry builder for that target
     */
    static OneTimeExecution createForVolatile(MethodHandle execution) {
        return new OneTimeRegistryBuilder(execution, true);
    }
}
