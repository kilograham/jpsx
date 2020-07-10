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
package org.jpsx.bootstrap.classloader;

import org.apache.bcel.classfile.ClassParser;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.ClassGen;
import org.apache.bcel.generic.InstructionList;
import org.apache.bcel.generic.MethodGen;
import org.jpsx.api.InvalidConfigurationException;
import org.jpsx.bootstrap.JPSXMachineLifecycle;
import org.jpsx.bootstrap.configuration.MachineDefinition;
import org.jpsx.bootstrap.util.CollectionsFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collection;
import java.util.List;
import java.util.Set;

// todo wildcard rather than prefix for modifiers/generators
// todo class modifiers

/**
 * JPSX ClassLoader.
 * <p/>
 * This classloader relies on its parent classloader
 * for getting RAW .class data, but it does not
 * necessarily follow the Java 2 delegation model.
 * <p/>
 * All JPSX classes should be loaded by this classloader
 * instead, since they mey reference other classes which
 * need to be modified before they are defined.
 * <p/>
 * Therefore this class recognizes certain package
 * prefixes as JPSX classes
 */

// todo multiple modifiers?

public class JPSXClassLoader extends ClassLoader {
    public static final boolean dumpClasses = false;
    private static final String JPSX_MACHINE_CLASS = "org.jpsx.runtime.JPSXMachineImpl";

    private static int BUFFER_SIZE = 8192;

    private final List<String> generatorClassnamePrefixes = CollectionsFactory.newArrayList();
    private final List<ClassGenerator> generators = CollectionsFactory.newArrayList();
    private final List<String> modifierClassnamePrefixes = CollectionsFactory.newArrayList();
    private final List<ClassModifier> modifiers = CollectionsFactory.newArrayList();
    private final Set<String> jpsxClassnamePrefixes = CollectionsFactory.newHashSet();

    private JPSXClassLoader(ClassLoader parent, MachineDefinition machineDefinition) {
        super(parent);
        jpsxClassnamePrefixes.add("org.jpsx.runtime");
        jpsxClassnamePrefixes.addAll(machineDefinition.getClassNamePrefixes());
    }

    public static JPSXMachineLifecycle newMachine(ClassLoader parent, MachineDefinition machineDefinition) throws InvalidConfigurationException {
        // each machine needs to have its own classloader instance
        JPSXClassLoader loader = new JPSXClassLoader(parent, machineDefinition);
        try {
            Class clazz = Class.forName(JPSX_MACHINE_CLASS, true, loader);
            JPSXMachineLifecycle rc = (JPSXMachineLifecycle) clazz.newInstance();
            rc.initialize(machineDefinition);
            return rc;
        } catch (Exception e) {
            throw new InvalidConfigurationException("Failed to create machine instance", e);
        }
    }

    private int prefixIndex(Collection<String> prefixes, String name) {
        int i = 0;
        for (String prefix : prefixes) {
            if (name.startsWith(prefix)) {
                return i;
            }
            i++;
        }
        return -1;
    }

    public static void registerClassGenerator(String classnamePrefix, ClassGenerator generator) {
        JPSXClassLoader instance = getLoaderInstance(generator);
        instance.generatorClassnamePrefixes.add(classnamePrefix);
        instance.generators.add(generator);
    }

    public static void registerClassModifier(String classnamePrefix, ClassModifier modifier) {
        JPSXClassLoader instance = getLoaderInstance(modifier);
        instance.modifierClassnamePrefixes.add(classnamePrefix);
        instance.modifiers.add(modifier);
    }

    private static JPSXClassLoader getLoaderInstance(Object obj) {
        ClassLoader loader = obj.getClass().getClassLoader();
        assert (loader != null && (loader instanceof JPSXClassLoader)) : obj + " was not loaded by JPSXClassLoader";
        return (JPSXClassLoader) loader;
    }

    /**
     * Utility method to define a class from a stream of bytes
     *
     * @param stream    the stream containing the binary class data
     * @param classname the class name for the class
     */
    private Class getClassFromStream(InputStream stream, String classname)
            throws IOException {
        // read the stream into a byte array
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_SIZE];

        int bytesRead;
        while ((bytesRead = stream.read(buffer, 0, BUFFER_SIZE)) != -1) {
            baos.write(buffer, 0, bytesRead);
        }

        byte[] classData = baos.toByteArray();

        // and define the class using our protection domain
        return defineClass(classname, classData, 0, classData.length, null);
    }

    /**
     * Override of the standard loadClass method to allow for class duplication.
     * <p/>
     * If we determine that we should duplicate the class then we do so, otherwise
     * we invoke the superclasses' method which follows the standard Java 2 delegation
     * model
     */
    public synchronized Class loadClass(final String name, boolean resolve) throws ClassNotFoundException {
        // make sure we haven't defined it already
        Class c = findLoadedClass(name);
        if (c == null) {
            //System.out.println( "load class "+name);
            if (-1 != prefixIndex(jpsxClassnamePrefixes, name)) {
                c = makeClass(name);
            } else {
                return super.loadClass(name, resolve);
            }
        }
        if (resolve) {
            resolveClass(c);
        }
        return c;
    }

    private Class makeClass(final String name) throws ClassNotFoundException {
        ClassGen cgen = null;

        int genIndex = prefixIndex(generatorClassnamePrefixes, name);
        int modIndex = prefixIndex(modifierClassnamePrefixes, name);

        if (genIndex != -1) {
            //System.out.println("generating class "+name);
            cgen = generators.get(genIndex).generateClass(name);
        } else {
            // make the ClassGen from the raw data from our parent loader
            final URL url = getResource(getClassFilename(name));
            if (url != null) {
                try {
                    InputStream stream = url.openStream();
                    if (modIndex == -1) {
                        //System.out.println("copying parent class "+name);
                        return getClassFromStream(stream, name);
                    } else {
                        cgen = new ClassGen((new ClassParser(stream, getClassFilename(name))).parse());
                    }
                } catch (IOException e) {
                    // will cause a class not found exception later
                }
            }
        }
        if (cgen != null && modIndex != -1) {
            //System.out.println("modifying class "+name);
            // modify the class if needed
            cgen = modifiers.get(modIndex).modifyClass(name, cgen);
        }

        if (cgen == null) {
            throw new ClassNotFoundException(name);
        }

        JavaClass jclass = cgen.getJavaClass();

        if (dumpClasses) {
            try {
                jclass.dump("modified_" + name + ".class");
            } catch (IOException e) {
            }
        }

        byte[] classData = jclass.getBytes();
        return defineClass(name, classData, 0, classData.length, null);
    }

    public static MethodGen emptyMethod(ClassGen cgen, Method m) {
        MethodGen mg = new MethodGen(m, cgen.getClassName(), cgen.getConstantPool());
        return new MethodGen(mg.getAccessFlags(),
                mg.getReturnType(),
                mg.getArgumentTypes(),
                mg.getArgumentNames(),
                mg.getName(),
                mg.getClassName(),
                new InstructionList(),
                mg.getConstantPool());
    }

    /**
     * Utility method to convert class name to filename
     */
    public static String getClassFilename(String classname) {
        return classname.replace('.', '/') + ".class";
    }
}
