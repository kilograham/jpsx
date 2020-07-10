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

import java.util.Set;

/**
 * A {@link Connection} object provides a lazily bound connection to one or more implementors
 * of a particular interface.<p>
 * <p/>
 * The client need not know who is on the other end of the connection; indeed the various sub-classes
 * of this class, provide the targets with a way to register themselves with a particular connection instance.<p>
 * <p/>
 * The client can connect to the target(s) by calling {@link #resolve()} which returns an implementor of the parametric type which
 * connects to the targets. Note this method will throw an exception if there is no target.<p>
 * <p/>
 * The connection also has an <i>open</i> flag, which indicates whether the connection is open. It should not be
 * possible to use the the connection (via the implementation returned by {@link #resolve()}) when the connection is closed;<p>
 * <p/>
 * The typical usage pattern is to place an instance of a connection in a class shareable by two otherwise unrelated classes,
 * and one class can become the target of the connection and the other the client without them knowing anything else about eachother.
 *
 * @see SimpleConnection
 * @see MultipleConnection
 */
public abstract class Connection<I> {
    /**
     * Flags for the base connection or any well known subclasses
     */
    enum Flags {
        InitiallyClosed
    }

    /**
     * Name of the connection
     */
    private final String name;

    /**
     * the callbable interface implemented by the return value from {@link #resolve()}
     */
    private final Class<I> ifc;

    /**
     * true if the connection accepts calls
     */
    private boolean open;

    // the dispatcher object should cease to work if the connection is closed
    // this is useful for connections which should only work at certain phases.

    protected Connection(String name, Class<I> ifc, Set<Flags> flags) {
        if (!ifc.isInterface()) throw new IllegalArgumentException("ifc must be an interface");
        this.name = name;
        this.ifc = ifc;
        if (!flags.contains(Flags.InitiallyClosed)) {
            open = true;
        }
    }

    public String getName() {
        return name;
    }

    public final Class<I> getInterface() {
        return ifc;
    }

    public final boolean isOpen() {
        return open;
    }

    public void open() {
        assert !this.open;
        this.open = true;
    }

    // todo flag for only open once
    public void close() {
        assert open;
        open = false;
    }

    /**
     * Return an implementation which acts as the conduit between caller and callees
     */
    public abstract I resolve();
}
