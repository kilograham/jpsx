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
package org.jpsx.runtime.debugcomponents.emulator.console;

import org.jpsx.api.CPUControl;
import org.jpsx.api.CPUListener;
import org.jpsx.api.components.core.addressspace.AddressSpace;
import org.jpsx.api.components.core.cpu.R3000;
import org.jpsx.api.components.core.scheduler.Quartz;
import org.jpsx.runtime.JPSXComponent;
import org.jpsx.runtime.RuntimeConnections;
import org.jpsx.runtime.components.core.CoreComponentConnections;
import org.jpsx.runtime.debugcomponents.core.DebugAddressSpaceImpl;
import org.jpsx.runtime.components.hardware.gte.GTE;
import org.jpsx.runtime.util.MiscUtil;

import java.io.*;

public class Console extends JPSXComponent implements Runnable, CPUListener {
    protected int lastDisAddress;
    protected boolean skipShow = true;

    private AddressSpace addressSpace;
    private R3000 r3000;
    private CPUControl cpuControl;
    private Quartz quartz;

    public void init() {
        super.init();
        RuntimeConnections.MAIN.set(this);
        CoreComponentConnections.CPU_LISTENERS.add(this);
    }


    public void resolveConnections() {
        super.resolveConnections();
        addressSpace = CoreComponentConnections.ADDRESS_SPACE.resolve();
        r3000 = CoreComponentConnections.R3000.resolve();
        cpuControl = RuntimeConnections.CPU_CONTROL.resolve();
        quartz = CoreComponentConnections.QUARTZ.resolve();
    }

    public Console() {
        super("JPSX Basic Console");
    }

    public void run() {
        System.out.println("JPSX Copyright (C) 2003, 2014 Graham Sanderson");
        System.out.println("This program comes with ABSOLUTELY NO WARRANTY; type 'l' for details.");
        System.out.println("This is free software, and you are welcome to redistribute it");
        System.out.println("under certain conditions; type 'l' for details.");
        System.out.println();
        dumpMainRegs();

        int lastDumpAddress = 0;
        DataInputStream input = new DataInputStream(System.in);
        boolean quit = false;

        long msBase = System.currentTimeMillis();
        long clockBase = quartz.nanoTime();
        showCurrentInstruction();
        try {
            while (!quit) {
                String line = input.readLine();
                if (line == null || line.length() == 0) {
                    continue;
                }
                switch (line.charAt(0)) {
                    case'r':
                        dumpMainRegs();
                        break;
                    case'2':
                        dumpGTERegs();
                        break;
                    case't':
                        long deltans = 1000000L * (System.currentTimeMillis() - msBase);
                        long deltaClock = quartz.nanoTime() - clockBase;
                        System.out.println("Clocks: " + deltans + " " + deltaClock + " " + (1.0 * deltaClock) / deltans);
                        msBase = System.currentTimeMillis();
                        clockBase = quartz.nanoTime();
                        break;
                    case'g':
                        cpuControl.go();
                        break;
                    case'u': {
                        String addr = line.substring(1).trim();
                        if (addr.length() > 0)
                            lastDisAddress = MiscUtil.parseHex(addr);
                        for (int i = 0; i < 20; i++) {
                            int ci = addressSpace.internalRead32(lastDisAddress);
                            String dis = r3000.disassemble(lastDisAddress, ci);
                            System.out.println(MiscUtil.toHex(lastDisAddress, 8) + ": " + MiscUtil.toHex(ci, 8) + " " + dis);
                            lastDisAddress += 4;
                        }
                        break;
                    }
                    case'o': {
                        String sStart = line.substring(1).trim();
                        int pos = sStart.indexOf(' ');
                        if (pos != -1) {
                            int start = MiscUtil.parseHex(sStart);
                            int end = MiscUtil.parseHex(sStart.substring(pos).trim());
                            try {
                                PrintWriter fw = new PrintWriter(new FileOutputStream("o.dis"));
                                for (int i = start; i < end; i += 4) {
                                    int ci = addressSpace.internalRead32(i);
                                    String dis = MiscUtil.toHex(i, 8) + ": " + MiscUtil.toHex(ci, 8) + " " + r3000.disassemble(i, ci);
                                    fw.println(dis);
                                }
                                fw.close();
                            } catch (IOException e) {
                            }
                        }
                        break;
                    }
                    case'l':
                        LineNumberReader reader = new LineNumberReader(new InputStreamReader(getClass().getClassLoader().getResourceAsStream("LICENSE")));
                        String licenseLine;
                        while (null != (licenseLine = reader.readLine())) {
                            System.out.println(licenseLine);
                        }
                        break;
                    case'b':
                        if (line.length() > 1) {
                            try {
                                switch (line.charAt(1)) {
                                    case 'l': {
                                        int[] bps = cpuControl.getBreakpoints();
                                        for (int i = 0; i < bps.length; i++) {
                                            System.out.println(Integer.toHexString(i) + ": " + MiscUtil.toHex(bps[i], 8));
                                        }
                                        break;
                                    }
                                    case 'p':
                                    case ' ': {
                                        int address = MiscUtil.parseHex(line.substring(2));
                                        cpuControl.addBreakpoint(address);
                                        break;
                                    }
                                    case 'c': {
                                        int address = MiscUtil.parseHex(line.substring(2));
                                        cpuControl.removeBreakpoint(address);
                                        break;
                                    }
                                }
                            } catch (Throwable t) {
                                System.out.println("Failed to set breakpoint: " + t);
                            }
                        } else {
                            cpuControl.pause();
                        }
                        break;
                    // RAM breakpoints
                    case'm':
                        if (line.length() > 1 && addressSpace.getClass() == DebugAddressSpaceImpl.class) {
                            DebugAddressSpaceImpl debugAddressSpace = (DebugAddressSpaceImpl) addressSpace;
                            try {
                                switch (line.charAt(1)) {
                                    case 'l': {
                                        int[] bps = debugAddressSpace.getActiveRAMBreakpoints();
                                        for (int i = 0; i < bps.length; i++) {
                                            System.out.println(Integer.toHexString(i) + ": " + MiscUtil.toHex(bps[i], 8));
                                        }
                                        break;
                                    }
                                    case 'p':
                                    case ' ': {
                                        int address = MiscUtil.parseHex(line.substring(2));
                                        debugAddressSpace.activateRAMBreakpoint(address);
                                        break;
                                    }
                                    case 'c': {
                                        int address = MiscUtil.parseHex(line.substring(2));
                                        debugAddressSpace.deactivateRAMBreakpoint(address);
                                        break;
                                    }
                                }
                            } catch (Throwable t) {
                                System.out.println("Failed to set breakpoint: " + t);
                            }
                        } else if (addressSpace.getClass() != DebugAddressSpaceImpl.class) {
                            System.out.println("You must use DebugAddressSpaceImpl in order to use this feature. Currently using: " + addressSpace.getClass().getName());
                        }
                        break;
                    case'c':
                        System.out.println("calling for gc");
                        System.gc();
                        break;
                    case'i':
                        try {
                            int i = Integer.parseInt(line.substring(1).trim());
                            System.out.println("Raising IRQ " + i);
                            CoreComponentConnections.IRQ_CONTROLLER.resolve().raiseIRQ(i);
                        } catch (Throwable t) {
                        }
                        break;
                    case'w': {
                        int type = 'd';
                        int base = 1;
                        if (base < line.length()) {
                            if (line.charAt(base) == 'w') {
                                type = 'w';
                                base++;
                            } else if (line.charAt(base) == 'b') {
                                type = 'b';
                                base++;
                            }
                        }
                        String parse = line.substring(base);
                        parse = parse.trim();
                        if (parse.length() > 0) {
                            int split = parse.indexOf(' ');
                            try {
                                if (split >= 0) {
                                    int address = MiscUtil.parseHex(parse.substring(0, split).trim());
                                    int value = MiscUtil.parseHex(parse.substring(split).trim());
                                    //System.out.println("address = "+MiscUtil.toHex( address, 8)+" value = "+MiscUtil.toHex( value, 8));
                                    switch (type) {
                                        case'd':
                                            addressSpace.write32(address, value);
                                            break;
                                        case'w':
                                            addressSpace.write16(address, value & 0xffff);
                                            break;
                                        case'b':
                                            addressSpace.write8(address, value & 0xff);
                                            break;
                                    }
                                }
                            } catch (Throwable t) {
                            }
                        }
                        break;
                    }
                    case'd': {
                        int type = 'd';
                        int base = 1;
                        if (base < line.length()) {
                            if (line.charAt(base) == 'w') {
                                type = 'w';
                                base++;
                            } else if (line.charAt(base) == 'b') {
                                type = 'b';
                                base++;
                            }
                        }
                        String parse = line.substring(base);
                        parse = parse.trim();
                        int count = 6;
                        int address = lastDumpAddress;
                        if (parse.length() > 0) {
                            int split = parse.indexOf(' ');
                            try {
                                if (split >= 0) {
                                    count = Integer.parseInt(parse.substring(split).trim());
                                }
                            } catch (Throwable t) {
                            }
                            address = MiscUtil.parseHex(parse);
                        }
                        if (count > 0) {
                            for (int i = 0; i < count; i++, address += 16) {
                                int j;
                                String val = MiscUtil.toHex(address, 8) + ": ";
                                switch (type) {
                                    case'd':
                                        if (0 != (address & 3))
                                            address = address & ~3;
                                        for (j = 0; j < 4; j++) {
                                            val += MiscUtil.toHex(addressSpace.read32(address + j * 4), 8) + " ";
                                        }
                                        break;
                                    case'w':
                                        if (0 != (address & 1))
                                            address = address & ~1;
                                        for (j = 0; j < 8; j++) {
                                            val += MiscUtil.toHex(addressSpace.read16(address + j * 2), 4) + " ";
                                        }
                                        break;
                                    case'b':
                                        for (j = 0; j < 16; j++) {
                                            val += MiscUtil.toHex(addressSpace.read8(address + j), 2) + " ";
                                        }
                                        break;
                                }
                                val += " ";
                                for (j = 0; j < 16; j++) {
                                    int b = addressSpace.read8(address + j);
                                    if (b >= 32 && b < 127) {
                                        val += (char) b;
                                    } else {
                                        val += " ";
                                    }
                                }
                                System.out.println(val);
                            }
                            lastDumpAddress = address;
                        }
                        break;
                    }
                    case's':
                        cpuControl.step();
                        break;
                    case'q':
                        //if (jsx.core.NativeCompiler.profiling) {
                        //    jsx.core.NativeCompiler.dumpProfiling();
                        //}
                        quit = true;
                        break;
                    case'v':
                        String parse = line.substring(2);
                        parse = parse.trim();
                        if (parse.startsWith("fill")) {
                            //SPU.fill();
                            break;
                        }
                        int sp = parse.indexOf(' ');
                        if (sp > 0) {
                            try {
                                int voice = Integer.parseInt(parse.substring(0, sp));
                                parse = parse.substring(sp + 1);
                                if (voice >= 0 && voice < 24) {
                                    if (parse.startsWith("on")) {
                                        if (voice < 16) {
                                            addressSpace.write16(0x1f801d88, 1 << (voice));
                                        } else {
                                            addressSpace.write16(0x1f801d8a, 1 << (voice - 16));
                                        }
                                        System.out.println("voice " + voice + " on");
                                    } else if (parse.startsWith("off")) {
                                        if (voice < 16) {
                                            addressSpace.write16(0x1f801d8c, 1 << (voice));
                                        } else {
                                            addressSpace.write16(0x1f801d8e, 1 << (voice - 16));
                                        }
                                        System.out.println("voice " + voice + " off");
                                    } else if (parse.startsWith("freq")) {
                                        int freq = MiscUtil.parseHex(parse.substring(5));
                                        addressSpace.write16(0x1f801c04 + (voice * 16), freq);
                                        System.out.println("voice " + voice + " freq set to " + MiscUtil.toHex(freq, 4));
                                    }
                                }
                            } catch (Throwable t) {
                            }
                        }
                        break;
                }
            }
            ;
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    public void showCurrentInstruction() {
        if (skipShow) {
            skipShow = false;
        } else {
            int pc = r3000.getPC();
            int ci = addressSpace.internalRead32(pc);
            String dis = r3000.disassemble(pc, ci);
            System.out.println(MiscUtil.toHex(pc, 8) + ": " + MiscUtil.toHex(ci, 8) + " " + dis);
            lastDisAddress = pc + 4;
        }
    }

    public void dumpGTERegs() {
        System.out.println("vxy0   " + MiscUtil.toHex(GTE.readRegister(0x00), 8) + " vz0    " + MiscUtil.toHex(GTE.readRegister(0x01), 8) + " vxy1   " + MiscUtil.toHex(GTE.readRegister(0x02), 8) + " vz1    " + MiscUtil.toHex(GTE.readRegister(0x03), 8));
        System.out.println("vxy2   " + MiscUtil.toHex(GTE.readRegister(0x04), 8) + " vz2    " + MiscUtil.toHex(GTE.readRegister(0x05), 8) + " rgb    " + MiscUtil.toHex(GTE.readRegister(0x06), 8) + " otz    " + MiscUtil.toHex(GTE.readRegister(0x07), 8));
        System.out.println("ir0    " + MiscUtil.toHex(GTE.readRegister(0x08), 8) + " ir1    " + MiscUtil.toHex(GTE.readRegister(0x09), 8) + " ir2    " + MiscUtil.toHex(GTE.readRegister(0x0a), 8) + " ir3    " + MiscUtil.toHex(GTE.readRegister(0x0b), 8));
        System.out.println("sxy0   " + MiscUtil.toHex(GTE.readRegister(0x0c), 8) + " sxy1   " + MiscUtil.toHex(GTE.readRegister(0x0d), 8) + " sxy2   " + MiscUtil.toHex(GTE.readRegister(0x0e), 8) + " sxyp   " + MiscUtil.toHex(GTE.readRegister(0x0f), 8));
        System.out.println("sz0    " + MiscUtil.toHex(GTE.readRegister(0x10), 8) + " sz1    " + MiscUtil.toHex(GTE.readRegister(0x11), 8) + " sz2    " + MiscUtil.toHex(GTE.readRegister(0x12), 8) + " sz3    " + MiscUtil.toHex(GTE.readRegister(0x13), 8));
        System.out.println("rgb0   " + MiscUtil.toHex(GTE.readRegister(0x14), 8) + " rgb1   " + MiscUtil.toHex(GTE.readRegister(0x15), 8) + " rgb2   " + MiscUtil.toHex(GTE.readRegister(0x16), 8) + " res1   " + MiscUtil.toHex(GTE.readRegister(0x17), 8));
        System.out.println("mac0   " + MiscUtil.toHex(GTE.readRegister(0x18), 8) + " mac1   " + MiscUtil.toHex(GTE.readRegister(0x19), 8) + " mac2   " + MiscUtil.toHex(GTE.readRegister(0x1a), 8) + " mac3   " + MiscUtil.toHex(GTE.readRegister(0x1b), 8));
        System.out.println("irgb   " + MiscUtil.toHex(GTE.readRegister(0x1c), 8) + " orgb   " + MiscUtil.toHex(GTE.readRegister(0x1d), 8) + " lzcs   " + MiscUtil.toHex(GTE.readRegister(0x1e), 8) + " lzcr   " + MiscUtil.toHex(GTE.readRegister(0x1f), 8));
        System.out.println("r11r12 " + MiscUtil.toHex(GTE.readRegister(0x20), 8) + " r13r21 " + MiscUtil.toHex(GTE.readRegister(0x21), 8) + " r22r23 " + MiscUtil.toHex(GTE.readRegister(0x22), 8) + " r31r32 " + MiscUtil.toHex(GTE.readRegister(0x23), 8));
        System.out.println("r33    " + MiscUtil.toHex(GTE.readRegister(0x24), 8) + " trx    " + MiscUtil.toHex(GTE.readRegister(0x25), 8) + " try    " + MiscUtil.toHex(GTE.readRegister(0x26), 8) + " trz    " + MiscUtil.toHex(GTE.readRegister(0x27), 8));
        System.out.println("l11r12 " + MiscUtil.toHex(GTE.readRegister(0x28), 8) + " l13r21 " + MiscUtil.toHex(GTE.readRegister(0x29), 8) + " l22r23 " + MiscUtil.toHex(GTE.readRegister(0x2a), 8) + " l31r32 " + MiscUtil.toHex(GTE.readRegister(0x2b), 8));
        System.out.println("l33    " + MiscUtil.toHex(GTE.readRegister(0x2c), 8) + " rbk    " + MiscUtil.toHex(GTE.readRegister(0x2d), 8) + " gbk    " + MiscUtil.toHex(GTE.readRegister(0x2e), 8) + " bbk    " + MiscUtil.toHex(GTE.readRegister(0x2f), 8));
        System.out.println("lr1lr2 " + MiscUtil.toHex(GTE.readRegister(0x30), 8) + " lr3lg1 " + MiscUtil.toHex(GTE.readRegister(0x31), 8) + " lg2lg3 " + MiscUtil.toHex(GTE.readRegister(0x32), 8) + " lb1lb2 " + MiscUtil.toHex(GTE.readRegister(0x33), 8));
        System.out.println("lb3    " + MiscUtil.toHex(GTE.readRegister(0x34), 8) + " rfc    " + MiscUtil.toHex(GTE.readRegister(0x35), 8) + " gfc    " + MiscUtil.toHex(GTE.readRegister(0x36), 8) + " bfc    " + MiscUtil.toHex(GTE.readRegister(0x37), 8));
        System.out.println("ofx    " + MiscUtil.toHex(GTE.readRegister(0x38), 8) + " ofy    " + MiscUtil.toHex(GTE.readRegister(0x39), 8) + " h      " + MiscUtil.toHex(GTE.readRegister(0x3a), 8) + " dqa    " + MiscUtil.toHex(GTE.readRegister(0x3b), 8));
        System.out.println("dqb    " + MiscUtil.toHex(GTE.readRegister(0x3c), 8) + " zsf3   " + MiscUtil.toHex(GTE.readRegister(0x3d), 8) + " zsf4   " + MiscUtil.toHex(GTE.readRegister(0x3e), 8) + " flag   " + MiscUtil.toHex(GTE.readRegister(0x3f), 8));
    }

    public void dumpMainRegs() {
        System.out.println("r0  " + MiscUtil.toHex(r3000.getReg(0), 8) + " r1  " + MiscUtil.toHex(r3000.getReg(1), 8) + " r2  " + MiscUtil.toHex(r3000.getReg(2), 8) + " r3  " + MiscUtil.toHex(r3000.getReg(3), 8) + " pc  " + MiscUtil.toHex(r3000.getPC(), 8));
        System.out.println("r4  " + MiscUtil.toHex(r3000.getReg(4), 8) + " r5  " + MiscUtil.toHex(r3000.getReg(5), 8) + " r6  " + MiscUtil.toHex(r3000.getReg(6), 8) + " r7  " + MiscUtil.toHex(r3000.getReg(7), 8) + " lo  " + MiscUtil.toHex(r3000.getLO(), 8));
        System.out.println("r8  " + MiscUtil.toHex(r3000.getReg(8), 8) + " r9  " + MiscUtil.toHex(r3000.getReg(9), 8) + " r10 " + MiscUtil.toHex(r3000.getReg(10), 8) + " r11 " + MiscUtil.toHex(r3000.getReg(11), 8) + " hi  " + MiscUtil.toHex(r3000.getHI(), 8));
        System.out.println("r12 " + MiscUtil.toHex(r3000.getReg(12), 8) + " r13 " + MiscUtil.toHex(r3000.getReg(13), 8) + " r14 " + MiscUtil.toHex(r3000.getReg(14), 8) + " r15 " + MiscUtil.toHex(r3000.getReg(15), 8));
        System.out.println("r16 " + MiscUtil.toHex(r3000.getReg(16), 8) + " r17 " + MiscUtil.toHex(r3000.getReg(17), 8) + " r18 " + MiscUtil.toHex(r3000.getReg(18), 8) + " r19 " + MiscUtil.toHex(r3000.getReg(19), 8));
        System.out.println("r20 " + MiscUtil.toHex(r3000.getReg(20), 8) + " r21 " + MiscUtil.toHex(r3000.getReg(21), 8) + " r22 " + MiscUtil.toHex(r3000.getReg(22), 8) + " r23 " + MiscUtil.toHex(r3000.getReg(23), 8));
        System.out.println("r24 " + MiscUtil.toHex(r3000.getReg(24), 8) + " r25 " + MiscUtil.toHex(r3000.getReg(25), 8) + " r26 " + MiscUtil.toHex(r3000.getReg(26), 8) + " r27 " + MiscUtil.toHex(r3000.getReg(27), 8));
        System.out.println("r28 " + MiscUtil.toHex(r3000.getReg(28), 8) + " r29 " + MiscUtil.toHex(r3000.getReg(29), 8) + " r30 " + MiscUtil.toHex(r3000.getReg(30), 8) + " r31 " + MiscUtil.toHex(r3000.getReg(31), 8));
    }

    public void cpuResumed() {
    }

    public void cpuPaused() {
        showCurrentInstruction();
    }
}
