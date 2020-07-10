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

import org.jpsx.bootstrap.util.CollectionsFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.EnumSet;
import java.util.List;

/**
 * A {@link MultipleConnection} represent a connection to zero or more instances of a particular interface.
 * <p/>
 * The typical usage pattern might be for a set of listeners:<br>
 * <p/>
 * The listeners register themselves with the connection, and the client calls {@link #resolve} on the connection
 * to get an implementation of the interface which takes care of looping over all the instances and calling each
 * registered listener.<p>
 * <p/>
 * This saves the listeners from needing to know about the caller, and the caller having to keep track of lists of listeners
 */
public class MultipleConnection<I> extends Connection<I> {
    private final List<I> targets = CollectionsFactory.newArrayList();
    private I dispatcher;

    public MultipleConnection(String name, Class<I> ifc, EnumSet<Flags> flags) {
        super(name, ifc, flags);
    }

    public void add(I target) {
        assert target != null;
        // todo remove this restriction, allowing for rewrite of dispatcher
        assert dispatcher == null : "targets may not be added to connection after first dispatch";
        targets.add(target);
    }

    public void remove(I target) {
        // todo remove this restriction, allowing for rewrite of dispatcher
        assert dispatcher == null : "targets may not be removed from connection after first dispatch";
        targets.remove(target);
    }

    @Override
    public I resolve() {
        if (dispatcher == null) {
            if (targets.size() == 1) {
                dispatcher = targets.iterator().next();
            } else {
                // todo optimize this if compiled by factorying a better class than the reflection proxy
                dispatcher = getInterface().cast(Proxy.newProxyInstance(getInterface().getClassLoader(), new Class[]{getInterface()}, new Invoker()));
            }
        }
        return dispatcher;
    }

    protected class Invoker implements InvocationHandler {
        protected Invoker() {
        }

        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            assert isOpen() : "Connection " + getName() + " used when not open";
            Object rc = null;
            for (I target : targets) {
                try {
                    rc = method.invoke(target, args);
                } catch (InvocationTargetException e) {
                    throw e.getTargetException();
                }
            }
            return rc;
        }
    }

    public static <T> MultipleConnection<T> create(String name, Class<T> clazz) {
        return new MultipleConnection<T>(name, clazz, EnumSet.noneOf(Flags.class));
    }

    public static <T> MultipleConnection<T> create(String name, Class<T> clazz, EnumSet<Flags> flags) {
        return new MultipleConnection<T>(name, clazz, flags);
    }
}
