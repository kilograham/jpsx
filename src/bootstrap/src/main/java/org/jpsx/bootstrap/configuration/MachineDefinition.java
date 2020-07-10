/*
 * Copyright (C) 2003, 2014 Graham Sanderson
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
package org.jpsx.bootstrap.configuration;

import org.jpsx.bootstrap.util.CollectionsFactory;

import java.util.Collections;
import java.util.List;

/**
 * A machine definition is just the in memory representation of the ordered
 * list of component that make up the machine.
 *
 * Each component is described by a {@link ComponentDefinition}<p>
 *
 * An instance of this class is the raw input used by the {@link org.jpsx.bootstrap.classloader.JPSXClassLoader} to construct a new machine,
 * however instances of this class may be built from another source (e.g. {@link XMLMachineDefinitionParser})<p>
 *
 * In addition to defining the components, the machine definition also includes the class name prefixes that the {@link org.jpsx.bootstrap.classloader.JPSXClassLoader}
 * should consider to be part of machine runtime. All runtime classes should be loaded by the {@link org.jpsx.bootstrap.classloader.JPSXClassLoader} instead of the
 * usual class loader, such that code modification/generation and multiple machine instances work correctly.
 */
public class MachineDefinition {
    private final List<ComponentDefinition> components = CollectionsFactory.newArrayList();
    private final List<String> classNamePrefixes = CollectionsFactory.newArrayList();

    public void addComponent(ComponentDefinition definition) {
        components.add(definition);
    }

    public void removeComponent(ComponentDefinition definition) {
        components.remove(definition);
    }

    public List<ComponentDefinition> getComponents() {
        return Collections.unmodifiableList(components);
    }

    /**
     * Add a class prefix to the list of prefixes of classes which are part of the machine runtime
     * @param classPrefix A string which is compared with fully qualified classname, so "com.fo" would be a prefix match "com.foo.Foo"
     */
    public void addClassPrefix(String classPrefix) {
        classNamePrefixes.add(classPrefix);
    }

    public List<String> getClassNamePrefixes() {
        return Collections.unmodifiableList(classNamePrefixes);
    }
}
