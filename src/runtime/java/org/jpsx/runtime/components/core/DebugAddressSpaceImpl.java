package org.jpsx.runtime.components.core;

import org.apache.bcel.generic.ClassGen;
import org.apache.log4j.Logger;
import org.jpsx.api.components.core.addressspace.AddressSpace;
import org.jpsx.api.components.core.addressspace.AddressSpaceRegistrar;
import org.jpsx.api.components.core.addressspace.Pollable;
import org.jpsx.bootstrap.classloader.ClassModifier;
import org.jpsx.runtime.FinalComponentSettings;
import org.jpsx.runtime.SingletonJPSXComponent;
import org.jpsx.runtime.components.emulator.compiler.MultiStageCompiler;
import org.jpsx.runtime.util.ClassUtil;

import java.lang.reflect.Method;

/**
 * A debug implementation of address space that gives you the ability to set RAM breakpoints and other goodies.
 */
public final class DebugAddressSpaceImpl  extends SingletonJPSXComponent implements ClassModifier, AddressSpace, AddressSpaceRegistrar {
    private static final Logger logger = Logger.getLogger(DebugAddressSpaceImpl.class);
    private final AddressSpaceImpl realAddressSpace = new AddressSpaceImpl();
    private static final String HARDWARE_CLASS = ClassUtil.innerClassName(DebugAddressSpaceImpl.class, "Hardware");

    public DebugAddressSpaceImpl() {
        super("Debug JPSX Address Space");
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
    public void resolve(int address, int size, ResolveResult result) {
        realAddressSpace.resolve(address, size, result);
    }

    @Override
    public int[] getMainRAM() {
        // TODO: Support RAM breakpoints for DMA!
        return realAddressSpace.getMainRAM();
    }

    @Override
    public void tagAddressAccessWrite(int pc, int address) {
        realAddressSpace.tagAddressAccessWrite(pc, address);
    }

    public static void _tagAddressAccessWrite(int pc, int address) {
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
