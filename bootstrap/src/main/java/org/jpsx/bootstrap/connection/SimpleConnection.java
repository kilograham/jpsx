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
package org.jpsx.bootstrap.connection;

import org.jpsx.api.InvalidConfigurationException;

import java.util.EnumSet;

/**
 * A {@link SimpleConnection} represents a connection to a single implementation object.
 * <p/>
 * This type of connection is useful when you want to be able to connect to a single implementor
 * of an interface for some specific purpose but do not want to be involved with the details of
 * how that implementor is constructed.<p>
 * <p/>
 * The target of the connection calls {@link #set} to initialize itself as the target.<p>
 * <p/>
 * The client of the connection calls {@link #resolve} as usual, or {@link #peek} if it wants null returned
 * if there is no implementor.
 */
public class SimpleConnection<I> extends Connection<I> {
    private I target;

    public SimpleConnection(String name, Class<I> ifc, EnumSet<Flags> flags) {
        super(name, ifc, flags);
    }

    public void set(I target) {
        if (target == null)
            throw new IllegalStateException("Target for simple connection '" + getName() + "' may only be set once");
        this.target = target;
    }

    /**
     * @return the target of the connection
     * @throws org.jpsx.api.InvalidConfigurationException
     *          if there is no target
     */
    @Override
    public I resolve() {
        if (target == null)
            throw new InvalidConfigurationException("No target for simple connection '" + getName() + "'");
        // todo we need a wrapper which respects open/closed
        return target;
    }

    /**
     * @return the target of the connection or null if there is one
     */
    public I peek() {
        return target;
    }

    /**
     * Factory method to create a simple connection to an implementor of a given interface
     *
     * @param name
     * @param clazz
     * @return
     */
    public static <T> SimpleConnection<T> create(String name, Class<T> clazz) {
        return new SimpleConnection<T>(name, clazz, EnumSet.noneOf(Flags.class));
    }

    /**
     * Factory method to create a simple connection to an implementor of a given interface, specifying
     * specifc flags for the connection
     *
     * @param name
     * @param clazz
     * @param flags
     * @return
     */
    public static <T> SimpleConnection<T> create(String name, Class<T> clazz, EnumSet<Flags> flags) {
        return new SimpleConnection<T>(name, clazz, flags);
    }
}
