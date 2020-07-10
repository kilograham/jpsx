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
package org.jpsx.runtime;

import org.jpsx.api.CPUControl;
import org.jpsx.bootstrap.connection.MultipleConnection;
import org.jpsx.bootstrap.connection.SimpleConnection;

import java.awt.event.KeyListener;

public class RuntimeConnections {
    /**
     * This connections is always available
     */
    public static final SimpleConnection<JPSXMachine> MACHINE = SimpleConnection.create("JPSX Machine", JPSXMachine.class);

    public static final SimpleConnection<CPUControl> CPU_CONTROL = SimpleConnection.create("CPU Control", CPUControl.class);
    public static final MultipleConnection<KeyListener> KEY_LISTENERS = MultipleConnection.create("Key Listeners", KeyListener.class);
    public static final SimpleConnection<Runnable> MAIN = SimpleConnection.create("JPSX Main", Runnable.class);
}
