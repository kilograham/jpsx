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

import org.apache.log4j.Logger;
import org.jpsx.api.components.core.addressspace.AddressSpaceRegistrar;
import org.jpsx.api.components.core.addressspace.MemoryMapped;
import org.jpsx.api.components.core.irq.IRQController;
import org.jpsx.api.components.hardware.sio.SerialDevice;
import org.jpsx.api.components.hardware.sio.SerialPort;
import org.jpsx.runtime.SingletonJPSXComponent;
import org.jpsx.runtime.components.core.CoreComponentConnections;
import org.jpsx.runtime.components.core.IRQOwnerBase;
import org.jpsx.runtime.components.hardware.HardwareComponentConnections;
import org.jpsx.runtime.util.MiscUtil;

public class SIO0 extends SingletonJPSXComponent implements MemoryMapped {
    private static final Logger log = Logger.getLogger("SIO0");
    private static final boolean debugSIO = false;

    private static final int ADDR_SIO0_DATA = 0x1f801040;
    private static final int ADDR_SIO0_STATUS = 0x1f801044;
    private static final int ADDR_SIO0_MODE = 0x1f801048;
    private static final int ADDR_SIO0_CTRL = 0x1f80104a;
    private static final int ADDR_SIO0_BAUD = 0x1f80104e;

    private static final int CTL_TX_ENABLE = 0x0001;
    private static final int CTL_DTR = 0x0002;
    private static final int CTL_RX_ENABLE = 0x0004;
    private static final int CTL_ERR_RESET = 0x0010;
    private static final int CTL_RESET = 0x0040;
    private static final int CTL_DSR_IRQ = 0x1000;
    private static final int CTL_SELECT = 0x2000;

    private static final int STAT_TX_READY = 0x0001; // can become set without anything connected
    private static final int STAT_RX_READY = 0x0002; // can become set without anything connected
    private static final int STAT_TX_EMPTY = 0x0004;
    private static final int STAT_PARITY_ERR = 0x0008;
    private static final int STAT_RX_OVERRUN = 0x0010;
    private static final int STAT_FRAMING_ERR = 0x0020;
    private static final int STAT_SYNC_DETEXT = 0x0040;
    private static final int STAT_DSR = 0x0080;
    private static final int STAT_CTS = 0x0100;
    private static final int STAT_IRQ = 0x0200;

    private static SerialDevice[] devices = new SerialDevice[2];

    public SIO0() {
        super("JPSX Serial Controller 0");
    }

    public void init() {
        super.init();
        CoreComponentConnections.ALL_MEMORY_MAPPED.add(this);
        HardwareComponentConnections.LEFT_PORT_INSTANCE.set(new Port(0));
        HardwareComponentConnections.RIGHT_PORT_INSTANCE.set(new Port(1));
        irq = new IRQ();
        CoreComponentConnections.IRQ_OWNERS.add(irq);
    }

    private static class Port implements SerialPort {
        private int index;

        public Port(int index) {
            this.index = index;
        }

        public void connect(SerialDevice device) {
            connectDevice(index, device);
        }

        public void disconnect() {
            disconnectDevice(index);
        }
    }

    private static void connectDevice(int which, SerialDevice device) {
        devices[which] = device;
        log.info("Connected " + device.getDescription() + " to port " + which);
    }

    private static void disconnectDevice(int which) {
        devices[which] = null;
    }

    private static class IRQ extends IRQOwnerBase {
        public IRQ() {
            super(IRQController.IRQ_SIO0, "SIO0");
        }
    }

    private static IRQ irq;

    public void registerAddresses(AddressSpaceRegistrar registrar) {
        registrar.registerWrite8Callback(ADDR_SIO0_DATA, SIO0.class, "writeData8");
        registrar.registerWrite16Callback(ADDR_SIO0_DATA, SIO0.class, "writeData16");
        registrar.registerWrite32Callback(ADDR_SIO0_DATA, SIO0.class, "writeData32");
        registrar.registerRead8Callback(ADDR_SIO0_DATA, SIO0.class, "readData8");
        registrar.registerRead16Callback(ADDR_SIO0_DATA, SIO0.class, "readData16");
        registrar.registerRead32Callback(ADDR_SIO0_DATA, SIO0.class, "readData32");

        registrar.registerWrite16Callback(ADDR_SIO0_MODE, SIO0.class, "writeMode16");
        registrar.registerRead16Callback(ADDR_SIO0_MODE, SIO0.class, "readMode16");
        registrar.registerWrite16Callback(ADDR_SIO0_CTRL, SIO0.class, "writeCtrl16");
        registrar.registerRead16Callback(ADDR_SIO0_CTRL, SIO0.class, "readCtrl16");
        registrar.registerWrite16Callback(ADDR_SIO0_BAUD, SIO0.class, "writeBaud16");
        registrar.registerRead16Callback(ADDR_SIO0_BAUD, SIO0.class, "readBaud16");
        registrar.registerRead16Callback(ADDR_SIO0_STATUS, SIO0.class, "readStatus16");
    }

    private static int m_baud;
    private static int m_mode;
    private static int m_ctrl;
    private static int m_status;

    private static boolean communicating = false;

    private static SerialDevice currentDevice;

    public static void writeData8(int address, int value) {
        value &= 0xff;
        if (debugSIO) System.out.println("SIO DATA WRITE 8 " + MiscUtil.toHex(value, 2));
        if (!communicating) {
            if (value == 1) {
                currentDevice = 0 == (m_ctrl & CTL_SELECT) ? devices[0] : devices[1];
                if (currentDevice != null)
                    currentDevice.prepareForTransfer();
                if (debugSIO) {
                    System.out.println("Select SIO device: " + ((currentDevice == null) ? "not connected" : currentDevice.getDescription()));
                }
                communicating = true;
            } else if (value == 0x81) {
                if (log.isDebugEnabled()) {
                    log.debug("CARD SELECT " + (((m_ctrl & CTL_SELECT) != 0) ? "1" : "0"));
                }
            }
        } else {
            if (currentDevice != null) {
                if (value == 0x43) {
                    m_status |= STAT_DSR;
                }
                currentDevice.send(value);
            }
        }
        m_status |= STAT_RX_READY;
    }

    public static void writeData16(int address, int value) {
        if (debugSIO) System.out.println("SIO DATA WRITE 16 " + MiscUtil.toHex(value, 16));
    }

    public static void writeData32(int address, int value) {
        if (debugSIO) System.out.println("SIO DATA WRITE 32 " + MiscUtil.toHex(value, 32));
    }

    public static int readData8(int address) {
        int rc = 0;

        // this isn't probably right, however the bios does...

        // (enable DSR IRQ)
        // write data
        // sleep
        // clear IRQ
        // wait for receive ready
        // read data
        // wait for IRQ
        //
        // ideally the irq should just be delayed... still this is OK for now
        if (currentDevice != null)
            rc = currentDevice.receive();

        if (debugSIO) System.out.println("SIO DATA READ 8 " + MiscUtil.toHex(rc, 2));
        if (0 != (m_ctrl & CTL_DSR_IRQ)) {
            if (currentDevice != null) {
                if (debugSIO) {
                    System.out.println("SIO setting DSR IRQ at end of read from connected device!");
                }
                m_status |= STAT_IRQ;
                irq.raiseIRQ();
            }
        }
        m_status &= ~STAT_RX_READY;
        return rc;
    }

    public static int readData16(int address) {
        int rc = 0;
        if (debugSIO) System.out.println("SIO DATA READ 16 " + MiscUtil.toHex(rc, 4));
        return rc;
    }

    public static int readData32(int address) {
        int rc = 0;
        if (debugSIO) System.out.println("SIO DATA READ 32 " + MiscUtil.toHex(rc, 8));
        return rc;
    }

    public static void writeMode16(int address, int value) {
        m_mode = value & 0xffff;
        if (debugSIO) System.out.println("SIO MODE WRITE 16 " + MiscUtil.toHex(m_mode, 4));
    }

    public static int readMode16(int address) {
        if (debugSIO) System.out.println("SIO MODE READ 16 " + MiscUtil.toHex(m_mode, 4));
        return m_mode;
    }

    public static void writeCtrl16(int address, int value) {
        if (debugSIO) System.out.println("SIO CTRL WRITE 16 " + MiscUtil.toHex(value, 4));
        m_ctrl = value & ~CTL_ERR_RESET;
        // todo some things
        if (0 != (m_ctrl & CTL_RESET)) {
            if (debugSIO) System.out.println("SIO0 RESET");
            m_status |= STAT_TX_READY;
            m_status &= ~STAT_DSR;
            communicating = false;
        }
        if (0 != (value & CTL_ERR_RESET)) {
            m_status &= ~STAT_IRQ;
        }
        if (value == 0) {
            currentDevice = null;
            communicating = false;
        }
    }

    public static int readCtrl16(int address) {
        int rc = m_ctrl;
        if (debugSIO) System.out.println("SIO CTRL READ 16 " + MiscUtil.toHex(rc, 4));
        return rc;
    }

    public static void writeBaud16(int address, int value) {
        m_baud = value;
        if (debugSIO) System.out.println("SIO BAUD WRITE 16 " + MiscUtil.toHex(m_baud, 4));
    }

    public static int readBaud16(int address) {
        if (debugSIO) System.out.println("SIO BAUD READ 16 " + MiscUtil.toHex(m_baud, 4));
        return m_baud;
    }

    public static int readStatus16(int address) {
        int rc = m_status;
        if (debugSIO) System.out.println("SIO STATUS READ 16 " + MiscUtil.toHex(rc, 4));
        return rc;
    }
}
