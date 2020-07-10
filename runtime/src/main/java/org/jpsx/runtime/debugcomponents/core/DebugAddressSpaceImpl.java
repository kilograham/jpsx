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
package org.jpsx.runtime.debugcomponents.core;

import org.apache.bcel.generic.ClassGen;
import org.apache.log4j.Logger;
import org.jpsx.api.CPUListener;
import org.jpsx.api.components.core.addressspace.AddressSpace;
import org.jpsx.api.components.core.addressspace.AddressSpaceRegistrar;
import org.jpsx.api.components.core.addressspace.Pollable;
import org.jpsx.bootstrap.classloader.ClassModifier;
import org.jpsx.bootstrap.util.CollectionsFactory;
import org.jpsx.runtime.RuntimeConnections;
import org.jpsx.runtime.SingletonJPSXComponent;
import org.jpsx.runtime.components.core.AddressSpaceImpl;
import org.jpsx.runtime.components.core.CoreComponentConnections;
import org.jpsx.runtime.util.ClassUtil;

import java.util.List;

/**
 * A debug implementation of address space that gives you the ability to set RAM breakpoints and other goodies.
 */
public final class DebugAddressSpaceImpl  extends SingletonJPSXComponent implements ClassModifier, AddressSpace, AddressSpaceRegistrar, CPUListener {
    private static final Logger logger = Logger.getLogger(DebugAddressSpaceImpl.class);
    private static final AddressSpaceImpl realAddressSpace = new AddressSpaceImpl();
    private static final String HARDWARE_CLASS = ClassUtil.innerClassName(DebugAddressSpaceImpl.class, "Hardware");
    private static List<Integer> ramBreakpoints = CollectionsFactory.newArrayList();
    private static boolean cpuActive = true;
    private static boolean ramBreakpointsActive = false;
    public static int[] ramD = realAddressSpace.ramD;
    public static final int[] scratch = realAddressSpace.scratch;
    public static final int[] bios = realAddressSpace.bios;

    public DebugAddressSpaceImpl() {
        super("Debug JPSX Address Space");
    }

    private static void processAddressWrite(int address) {
        if (ramBreakpointsActive && ramBreakpoints.contains(address)) {
            logger.info("RAM address 0x" + Integer.toHexString(address) + " about to be written; pausing the CPU...");
            // Pause the CPU.
            RuntimeConnections.CPU_CONTROL.resolve().pause();
        }
    }

    public static void activateRAMBreakpoint(int address) {
        ramBreakpoints.add(address);
        logger.info("Adding breakpoint at: 0x" + Integer.toHexString(address));
        ramBreakpointsActive = true && cpuActive;
    }

    public static void deactivateRAMBreakpoint(int address) {
        int index = ramBreakpoints.indexOf(address);
        if (index >= 0) {
            ramBreakpoints.remove(index);
            logger.info("Breakpoint at 0x" + Integer.toHexString(address) + " has been removed.");
            ramBreakpointsActive = ramBreakpoints.size() > 0 && cpuActive;
        } else {
            logger.info("Breakpoint at 0x" + Integer.toHexString(address) + " does not exist.");
        }
    }

    public static int[] getActiveRAMBreakpoints() {
        final int numBreakpoints = ramBreakpoints.size();
        final int[] breakpoints = new int[numBreakpoints];
        for (int i = 0; i < numBreakpoints; i++) {
            breakpoints[i] = ramBreakpoints.get(i);
        }
        return breakpoints;
    }

    @Override
    public void resolveConnections() {
        super.resolveConnections();
        realAddressSpace.resolveConnections();
    }

    @Override
    public void init() {
        super.init();
        realAddressSpace.init();
        CoreComponentConnections.ADDRESS_SPACE.set(this);
        CoreComponentConnections.CPU_LISTENERS.add(this);
    }

    @Override
    public int internalRead32(int address) {
        return realAddressSpace.internalRead32(address);
    }

    public static int _internalRead32(int address) {
        return AddressSpaceImpl._internalRead32(address);
    }

    @Override
    public void internalWrite32(int address, int value) {
        realAddressSpace.internalWrite32(address, value);
    }

    public static void _internalWrite32(int address, int value) {
        AddressSpaceImpl._internalWrite32(address, value);
    }

    @Override
    public int read8(int address) {
        return realAddressSpace.read8(address);
    }

    public static int _read8(int address) {
        return AddressSpaceImpl._read8(address);
    }

    @Override
    public int read16(int address) {
        return realAddressSpace.read16(address);
    }

    public static int _read16(int address) {
        return AddressSpaceImpl._read16(address);
    }

    @Override
    public int read32(int address) {
        return realAddressSpace.read32(address);
    }

    public static int _read32(int address) {
        return AddressSpaceImpl._read32(address);
    }

    @Override
    public void write8(int address, int value) {
        realAddressSpace.write8(address, value);
    }

    public static void _write8(int address, int value) {
        AddressSpaceImpl._write8(address, value);
    }

    @Override
    public void write16(int address, int value) {
        realAddressSpace.write16(address, value);
    }

    public static void _write16(int address, int value) {
        AddressSpaceImpl._write16(address, value);
    }

    @Override
    public void write32(int address, int value) {
        realAddressSpace.write32(address, value);
    }

    public static void _write32(int address, int value) {
        AddressSpaceImpl._write32(address, value);
    }

    @Override
    public void enableMemoryWrite(boolean enable) {
        realAddressSpace.enableMemoryWrite(enable);
        // RAM might now be a dummy array.
        ramD = realAddressSpace.ramD;
    }

    @Override
    public byte getTag(int pc) {
        return realAddressSpace.getTag(pc);
    }

    @Override
    public void orTag(int pc, byte val) {
        realAddressSpace.orTag(pc, val);
    }

    @Override
    public void resolve(int address, ResolveResult result) {
        realAddressSpace.resolve(address, result);
    }

    @Override
    public void resolve(int address, int size, boolean write, ResolveResult result) {
        realAddressSpace.resolve(address, size, write, result);
    }

    @Override
    public int[] getMainRAM() {
        // TODO: Support RAM breakpoints for DMA!
        return realAddressSpace.getMainRAM();
    }

    @Override
    public void tagAddressAccessWrite(int pc, int address) {
        processAddressWrite(address);
        realAddressSpace.tagAddressAccessWrite(pc, address);
    }

    public static void _tagAddressAccessWrite(int pc, int address) {
        processAddressWrite(address);
        AddressSpaceImpl._tagAddressAccessWrite(pc, address);
    }

    @Override
    public void tagAddressAccessRead8(int pc, int address) {
        realAddressSpace.tagAddressAccessRead8(pc, address);
    }

    public static void _tagAddressAccessRead8(int pc, int address) {
        AddressSpaceImpl._tagAddressAccessRead8(pc, address);
    }

    @Override
    public void tagAddressAccessRead16(int pc, int address) {
        realAddressSpace.tagAddressAccessRead16(pc, address);
    }

    public static void _tagAddressAccessRead16(int pc, int address) {
        AddressSpaceImpl._tagAddressAccessRead16(pc, address);
    }

    @Override
    public void tagAddressAccessRead32(int pc, int address) {
        realAddressSpace.tagAddressAccessRead32(pc, address);
    }

    public static void _tagAddressAccessRead32(int pc, int address) {
        AddressSpaceImpl._tagAddressAccessRead32(pc, address);
    }

    @Override
    public void tagClearPollCounters() {
        realAddressSpace.tagClearPollCounters();
    }

    public static void _tagClearPollCounters() {
        AddressSpaceImpl._tagClearPollCounters();
    }

    @Override
    public String getMainStaticInterfaceClassName() {
        return DebugAddressSpaceImpl.class.getName();
    }

    @Override
    public String getHardwareStaticInterfaceClassName() {
        return HARDWARE_CLASS;
    }

    @Override
    public void registerRead8Callback(int address, Class clazz, String methodName) {
        realAddressSpace.registerRead8Callback(address, clazz, methodName);
    }

    @Override
    public void registerRead16Callback(int address, Class clazz, String methodName) {
        realAddressSpace.registerRead16Callback(address, clazz, methodName);
    }

    @Override
    public void registerRead16Callback(int address, Class clazz, String methodName, boolean allowSubRead) {
        realAddressSpace.registerRead16Callback(address, clazz, methodName, allowSubRead);
    }

    @Override
    public void registerRead32Callback(int address, Class clazz, String methodName, boolean allowSubRead) {
        realAddressSpace.registerRead32Callback(address, clazz, methodName, allowSubRead);
    }

    @Override
    public void registerRead32Callback(int address, Class clazz, String methodName) {
        realAddressSpace.registerRead32Callback(address, clazz, methodName);
    }

    @Override
    public void registerWrite8Callback(int address, Class clazz, String methodName) {
        realAddressSpace.registerWrite8Callback(address, clazz, methodName);
    }

    @Override
    public void registerWrite16Callback(int address, Class clazz, String methodName) {
        realAddressSpace.registerWrite16Callback(address, clazz, methodName);
    }

    @Override
    public void registerWrite16Callback(int address, Class clazz, String methodName, boolean allowSubWrite) {
        realAddressSpace.registerWrite16Callback(address, clazz, methodName, allowSubWrite);
    }

    @Override
    public void registerWrite32Callback(int address, Class clazz, String methodName) {
        realAddressSpace.registerWrite32Callback(address, clazz, methodName);
    }

    @Override
    public void registerWrite32Callback(int address, Class clazz, String methodName, boolean allowSubWrite) {
        realAddressSpace.registerWrite32Callback(address, clazz, methodName, allowSubWrite);
    }

    @Override
    public void registerPoll32Callback(int address, Pollable pollable) {
        realAddressSpace.registerPoll32Callback(address, pollable);
    }

    @Override
    public ClassGen modifyClass(String classname, ClassGen original) {
        return realAddressSpace.modifyClass(classname, original);
    }

    @Override
    public void cpuResumed() {
        cpuActive = true;
        ramBreakpointsActive = ramBreakpoints.size() > 0;
    }

    @Override
    public void cpuPaused() {
        cpuActive = false;
        ramBreakpointsActive = false;
    }

    public static class Hardware {
        public static void write8(int address, int value) {
            AddressSpaceImpl.Hardware.write8(address, value);
        }

        public static void write16(int address, int value) {
            AddressSpaceImpl.Hardware.write16(address, value);
        }

        public static void write32(int address, int value) {
            AddressSpaceImpl.Hardware.write32(address, value);
        }

        public static int read8(int address) {
            return AddressSpaceImpl.Hardware.read8(address);
        }

        public static int read16(int address) {
            return AddressSpaceImpl.Hardware.read16(address);
        }

        public static int read32(int address) {
            return AddressSpaceImpl.Hardware.read32(address);
        }

        public static int defaultRead32(int address) {
            return AddressSpaceImpl.Hardware.defaultRead32(address);
        }

        public static void defaultWrite32(int address, int value) {
            AddressSpaceImpl.Hardware.defaultWrite32(address, value);
        }

        public static int defaultRead16(int address) {
            return AddressSpaceImpl.Hardware.defaultRead16(address);
        }

        public static void defaultWrite16(final int address, int value) {
            AddressSpaceImpl.Hardware.defaultWrite16(address, value);
        }

        public static int defaultRead8(int address) {
            return AddressSpaceImpl.Hardware.defaultRead8(address);
        }

        public static void defaultWrite8(final int address, int value) {
            AddressSpaceImpl.Hardware.defaultWrite8(address, value);
        }
    }
}
