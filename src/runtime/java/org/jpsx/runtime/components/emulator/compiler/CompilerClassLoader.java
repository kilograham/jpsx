/*
Copyright (C) 2007 graham sanderson

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation; either version 2
of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
*/
package org.jpsx.runtime.components.emulator.compiler;

import org.apache.log4j.Logger;

// TODO allow for complete generator

public class CompilerClassLoader extends ClassLoader {
    private static final Logger logger = Logger.getLogger(MultiStageCompiler.CATEGORY);
    private String description;

    public CompilerClassLoader(String description, ClassLoader parent) {
        super(parent);
        this.description = description;
    }

    public Class findClass(final String name) throws ClassNotFoundException {
        // make sure we haven't defined it already
        Class c = findLoadedClass(name);
        if (c == null) {
            if (logger.isDebugEnabled()) {
                logger.debug(description + ": createClass " + name);
            }
            c = MultiStageCompiler.generateClass(name);
        }
        return c;
    }

    public Class createClass(final String name, byte[] classData) {
        if (logger.isDebugEnabled()) {
            logger.debug(description + ": createClass " + name);
        }
        return defineClass(name, classData, 0, classData.length, null);
    }

    public String toString() {
        return description;
    }
}
