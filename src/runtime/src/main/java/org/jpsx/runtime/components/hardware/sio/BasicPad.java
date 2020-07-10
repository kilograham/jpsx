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
package org.jpsx.runtime.components.hardware.sio;

import org.jpsx.api.components.hardware.sio.SerialDevice;
import org.jpsx.runtime.JPSXComponent;

/**
 * just old BIOS style pad - no two way comms
 */
public abstract class BasicPad extends JPSXComponent implements SerialDevice {
    public abstract int getType(); // e.g. 0x41 for standard controller (type and size really!)

    public abstract void getState(byte[] buffer);

    protected BasicPad(String description) {
        super(description);
    }

    protected byte[] rxBuffer = new byte[32];
    protected byte[] txBuffer = new byte[32];
    protected int state;
    protected int txIndex;

    public void prepareForTransfer() {
        getState(rxBuffer);
        state = 0;
        txIndex = 0;
    }

    public int receive() {
        int rc;
        if (state == 0) {
            rc = 0x00;
        } else if (state == 1) {
            rc = getType();
            if (rc == 0x41) {
                if (txBuffer[0] == 0x43) {
                    rc = 0x43;
                }
                if (txBuffer[0] == 0x45) {
                    rc = 0xf3;
                }
            }
        } else if (state == 2) {
            rc = 0x5a;
        } else {
            rc = rxBuffer[state - 3];
        }
        state++;
        return rc;
    }

    public void send(int data) {
        txBuffer[txIndex++] = (byte) data;
    }
}
