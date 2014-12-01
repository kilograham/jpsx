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
package org.jpsx.runtime.components.hardware;

import org.jpsx.api.components.hardware.cd.CDAudioSink;
import org.jpsx.api.components.hardware.cd.CDDrive;
import org.jpsx.api.components.hardware.gpu.Display;
import org.jpsx.api.components.hardware.gpu.DisplayManager;
import org.jpsx.api.components.hardware.sio.SerialPort;
import org.jpsx.bootstrap.connection.SimpleConnection;

/**
 * Stock connections used by the base JPSX hardware implementations
 */
public class HardwareComponentConnections {
    public static final SimpleConnection<CDDrive> CD_DRIVE = SimpleConnection.create("CD Drive", CDDrive.class);
    public static final SimpleConnection<CDAudioSink> CD_AUDIO_SINK = SimpleConnection.create("CD Audio Sink", CDAudioSink.class);
    public static final SimpleConnection<SerialPort> LEFT_PORT_INSTANCE = SimpleConnection.create("Left Serial Port", SerialPort.class);
    public static final SimpleConnection<SerialPort> RIGHT_PORT_INSTANCE = SimpleConnection.create("Right Serial Port", SerialPort.class);
    public static final SimpleConnection<Display> DISPLAY = SimpleConnection.create("JPSX GPU Display", Display.class);
    public static final SimpleConnection<DisplayManager> DISPLAY_MANAGER = SimpleConnection.create("JPXS GPU Display Manager", DisplayManager.class);
}
