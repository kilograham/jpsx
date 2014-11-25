/*
 * Copyright (C) 2007, 2014 Graham Sanderson
 *
 * This file is part of JPSX.
 * 
 * JPSX is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * JPSX is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with JPSX.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.jpsx.runtime;

import org.apache.log4j.Logger;
import org.jpsx.api.InvalidConfigurationException;
import org.jpsx.bootstrap.JPSXMachineLifecycle;
import org.jpsx.bootstrap.configuration.ComponentDefinition;
import org.jpsx.bootstrap.configuration.MachineDefinition;
import org.jpsx.bootstrap.util.CollectionsFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * This class is responsible for creating the components and initializing them
 */
public class JPSXMachineImpl implements JPSXMachine, JPSXMachineLifecycle {
    public static final String CATEGORY = "Machine";
    private static Logger log = Logger.getLogger(CATEGORY);

    /**
     * List of all components
     */
    private List<JPSXComponent> components = CollectionsFactory.newArrayList();

    /**
     * Ordered set of initializers to be run before execution begins
     */
    private Map<Integer, List<Runnable>> initializers = CollectionsFactory.newTreeMap(Collections.<Integer>reverseOrder());

    private boolean settingsFrozen;

    public void initialize(MachineDefinition machine) {
        RuntimeConnections.MACHINE.set(this);
        addMachineElements(machine);

        // add our initializers
        addInitializer(PRIORITY_RESOLVE_CONNECTIONS, new Runnable() {
            public void run() {
                log.info("Resolving connections...");
                for (JPSXComponent component : components) {
                    component.resolveConnections();
                }
            }
        });

        addInitializer(PRIORITY_FREEZE_SETTINGS, new Runnable() {
            public void run() {
                settingsFrozen = false;
            }
        });

        log.info("Pre-initializing components...");
        for (JPSXComponent component : components) {
            component.init();
        }

        // perform ordered initialization
        runInitializers();
    }

    public void start() {
        for (JPSXComponent component : components) {
            component.begin();
        }

        Runnable entryPoint = RuntimeConnections.MAIN.peek();
        if (entryPoint != null) {
            entryPoint.run();
        } else {
            // default implementation is just to run cpu
            RuntimeConnections.CPU_CONTROL.resolve().go();

            while (true) {
                try {
                    Thread.sleep(Integer.MAX_VALUE);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }

        close();
    }

    public void addInitializer(int priority, Runnable initializer) {
        List<Runnable> list = initializers.get(priority);
        if (list == null) {
            list = CollectionsFactory.newArrayList();
            initializers.put(priority, list);
        }
        list.add(initializer);
    }

    protected void addMachineElements(MachineDefinition machineDef) {
        for (ComponentDefinition componentDef : machineDef.getComponents()) {
            JPSXComponent component = createComponent(componentDef.getClassName());
            for (Map.Entry<String, String> entry : componentDef.getProperties().entrySet()) {
                component.setProperty(entry.getKey(), entry.getValue());
            }
            log.info("Adding component " + component.getDescription());
            components.add(component);
        }
    }

    public JPSXComponent createComponent(String className) {

        try {
            Class clazz = Class.forName(className);
            return (JPSXComponent) clazz.newInstance();
        } catch (ClassCastException e) {
            throw new InvalidConfigurationException("Class " + className + " does not extend JPSXComponent");
        } catch (Throwable e) {
            throw new InvalidConfigurationException("Can't create instance of class " + className, e);
        }
    }

    private void runInitializers() {
        for (List<Runnable> list : initializers.values()) {
            for (Runnable initializer : list) {
                initializer.run();
            }
        }
    }

    public void close() {
        log.info("Closing...\n");
        // todo something other than this!
        System.exit(0);
    }

    public boolean settingsFrozen() {
        return settingsFrozen;
    }
}
