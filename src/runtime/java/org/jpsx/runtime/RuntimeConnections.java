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
