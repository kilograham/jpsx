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
package org.jpsx.runtime.components.core;

import org.apache.bcel.generic.*;
import org.apache.log4j.Logger;
import org.jpsx.api.components.core.addressspace.AddressSpace;
import org.jpsx.api.components.core.addressspace.AddressSpaceListener;
import org.jpsx.api.components.core.addressspace.AddressSpaceRegistrar;
import org.jpsx.api.components.core.addressspace.Pollable;
import org.jpsx.api.components.core.scheduler.Scheduler;
import org.jpsx.bootstrap.classloader.ClassModifier;
import org.jpsx.bootstrap.classloader.JPSXClassLoader;
import org.jpsx.bootstrap.connection.SimpleConnection;
import org.jpsx.bootstrap.util.CollectionsFactory;
import org.jpsx.runtime.JPSXMachine;
import org.jpsx.runtime.RuntimeConnections;
import org.jpsx.runtime.SingletonJPSXComponent;
import org.jpsx.runtime.util.ByteCodeUtil;
import org.jpsx.runtime.util.ClassUtil;
import org.jpsx.runtime.util.MiscUtil;

import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

// todo disallow multiple reg at the same address
public final class AddressSpaceImpl extends SingletonJPSXComponent implements ClassModifier, AddressSpace, AddressSpaceRegistrar {
    private static final Logger log = Logger.getLogger("AddressSpace");
    private static final Logger logPoll = Logger.getLogger("AddressSpace.poll");
    private static final Logger logUnknown = Logger.getLogger("AddressSpace.unknown");

    private static final String HARDWARE_CLASS = ClassUtil.innerClassName(AddressSpaceImpl.class, "Hardware");

    private static final boolean logUnknownDebug = logUnknown.isDebugEnabled();
    private static final boolean logPollDebug = logPoll.isDebugEnabled();
    private static final boolean logPollTrace = logPoll.isTraceEnabled();

    public static class Settings {
        public static final boolean tagAddressAccess = true;
        public static final boolean checkAlignment = false;
        public static final boolean debugPoll = false;
        public static final boolean assertOnUnknownAddress = true;
        public static final boolean checkHWOverlap = false;
    }

    public static final int[] ram;
    public static int[] ramD;
    public static final int[] scratch;
    public static final int[] bios;
    public static final int[] hw;
    public static final int[] par;

    private static int[] ramDummy;
    private static byte[] ramTags;
    private static byte[] biosTags;
    private static int lastPoll32Address = 0;
    private static int lastPoll32Count = 0;
    private static boolean writeEnabled = true;

    private static final int SCRATCH_MASK = SCRATCH_SIZE - 1;
    private static final int PAR_MASK = PAR_SIZE - 1;
    private static final int BIOS_MASK = BIOS_SIZE - 1;
    private static final int HW_MASK = HW_SIZE - 1;

    private static int readPC0;
    private static int readPC1;
    private static int readPC2;
    private static int readPCHW; // PC of last hw read
    private static int readAddress0;
    private static int readAddress1;
    private static int readAddress2;
    private static int readAddressHW;
    private static int readsSinceHW; // count of reads since last hw read
    private static final int MAX_READS_SINCE_HW = 16;

    private static AddressSpaceListener addressSpaceListeners;
    private static Scheduler scheduler;

    static {
        ram = new int[RAM_SIZE >> 2];
        // not much writing usually done when ram writing disabled
        ramDummy = new int[0x1000 >> 2];
        ramD = ram;
        ramTags = new byte[RAM_SIZE >> 2];
        biosTags = new byte[BIOS_SIZE >> 2];
        scratch = new int[SCRATCH_SIZE >> 2];
        bios = new int[BIOS_SIZE >> 2];
        hw = new int[HW_SIZE >> 2];
        par = new int[PAR_SIZE >> 2];
    }

    public AddressSpaceImpl() {
        super("JPSX Address Space");
    }

    @Override
    public void resolveConnections() {
        super.resolveConnections();
        addressSpaceListeners = CoreComponentConnections.ADDRESS_SPACE_LISTENERS.resolve();
//        r3000 = CoreComponentConnections.R3000.resolve();
        scheduler = CoreComponentConnections.SCHEDULER.resolve();
    }

    public void init() {
        super.init();
        JPSXClassLoader.registerClassModifier(HARDWARE_CLASS, this);
        JPSXMachine machine = RuntimeConnections.MACHINE.resolve();
        CoreComponentConnections.ADDRESS_SPACE.set(this);
        machine.addInitializer(JPSXMachine.PRIORITY_REGISTER_ADDRESSES, new Runnable() {
            public void run() {
                log.info("Registering memory mapped addresses...");

                // make a connection wrapper around the registrar, so that we can close it afterwards
                SimpleConnection<AddressSpaceRegistrar> connection = SimpleConnection.create("AddressSpaceRegistrar", AddressSpaceRegistrar.class);
                connection.set(AddressSpaceImpl.this);
                CoreComponentConnections.ALL_MEMORY_MAPPED.resolve().registerAddresses(connection.resolve());
                CoreComponentConnections.ALL_MEMORY_MAPPED.close();
                connection.close();
            }
        });
        machine.addInitializer(JPSXMachine.PRIORITY_POPULATE_MEMORY, new Runnable() {
            public void run() {
                log.info("Populating memory...");
                CoreComponentConnections.ALL_POPULATORS.resolve().run();
                CoreComponentConnections.ALL_POPULATORS.close();
            }
        });
    }

    private void rewriteHWMethod(ClassGen cgen, String name, String signature, SortedMap<Integer, InstructionList> cases, InstructionList suffix, int resolution) {
        ConstantPoolGen cp = cgen.getConstantPool();
        org.apache.bcel.classfile.Method m = cgen.containsMethod(name, signature);
        MethodGen mg = JPSXClassLoader.emptyMethod(cgen, m);
        InstructionList il = mg.getInstructionList();

        ByteCodeUtil.emitSwitch(cp, il, 0, cases, resolution, HW_BASE, HW_END - resolution);
        il.append(suffix);

        mg.setMaxLocals();
        mg.setMaxStack();
        cgen.replaceMethod(m, mg.getMethod());
        il.dispose();
    }

    public ClassGen modifyClass(String classname, ClassGen cgen) {
        ConstantPoolGen cp = cgen.getConstantPool();
        InstructionList suffix = new InstructionList();
        InstructionList il = new InstructionList();
        TreeMap<Integer, InstructionList> cases;
        Iterator<Integer> addresses;
        Integer address;
        Method m;

        // read 32
        cases = new TreeMap<Integer, InstructionList>();
        for (addresses = read32Callbacks.keySet().iterator(); addresses.hasNext();) {
            address = (Integer) addresses.next();
            m = (Method) read32Callbacks.get(address);
            il = new InstructionList();
            il.append(new ILOAD(0));
            il.append(new INVOKESTATIC(cp.addMethodref(m.getDeclaringClass().getName(), m.getName(), "(I)I")));
            il.append(new IRETURN());
            cases.put(address, il);
        }
        for (addresses = subRead32Callbacks.keySet().iterator(); addresses.hasNext();) {
            address = (Integer) addresses.next();
            assert null == cases.get(address) : MiscUtil.toHex(address.intValue(), 8);

            m = (Method) subRead32Callbacks.get(address);
            il = new InstructionList();
            il.append(new ILOAD(0));
            il.append(new INVOKESTATIC(cp.addMethodref(m.getDeclaringClass().getName(), m.getName(), "(I)I")));
            il.append(new IRETURN());
            cases.put(address, il);
        }
        suffix.append(new ILOAD(0));
        suffix.append(new INVOKESTATIC(cp.addMethodref(classname, "defaultRead32", "(I)I")));
        suffix.append(new IRETURN());
        rewriteHWMethod(cgen, "read32", "(I)I", cases, suffix, 4);

        // write 32
        cases.clear();
        for (addresses = write32Callbacks.keySet().iterator(); addresses.hasNext();) {
            address = (Integer) addresses.next();
            m = (Method) write32Callbacks.get(address);
            il = new InstructionList();
            il.append(new ILOAD(0));
            il.append(new ILOAD(1));
            il.append(new INVOKESTATIC(cp.addMethodref(m.getDeclaringClass().getName(), m.getName(), "(II)V")));
            il.append(new RETURN());
            cases.put(address, il);
        }
        for (addresses = subWrite32Callbacks.keySet().iterator(); addresses.hasNext();) {
            address = (Integer) addresses.next();
            assert null == cases.get(address) : MiscUtil.toHex(address.intValue(), 8);

            m = (Method) subWrite32Callbacks.get(address);
            il = new InstructionList();
            il.append(new ILOAD(0));
            il.append(new ILOAD(1));
            il.append(new PUSH(cp, -1));
            il.append(new INVOKESTATIC(cp.addMethodref(m.getDeclaringClass().getName(), m.getName(), "(III)V")));
            il.append(new RETURN());
            cases.put(address, il);
        }
        suffix.append(new ILOAD(0));
        suffix.append(new ILOAD(1));
        suffix.append(new INVOKESTATIC(cp.addMethodref(classname, "defaultWrite32", "(II)V")));
        suffix.append(new RETURN());
        rewriteHWMethod(cgen, "write32", "(II)V", cases, suffix, 4);

        // read 16
        cases.clear();
        for (addresses = read16Callbacks.keySet().iterator(); addresses.hasNext();) {
            address = (Integer) addresses.next();
            m = (Method) read16Callbacks.get(address);
            il = new InstructionList();
            il.append(new ILOAD(0));
            il.append(new INVOKESTATIC(cp.addMethodref(m.getDeclaringClass().getName(), m.getName(), "(I)I")));
            il.append(new PUSH(cp, 0xffff));
            il.append(new IAND());
            il.append(new IRETURN());
            cases.put(address, il);
        }
        for (addresses = subRead16Callbacks.keySet().iterator(); addresses.hasNext();) {
            address = (Integer) addresses.next();
            assert null == cases.get(address) : MiscUtil.toHex(address.intValue(), 8);

            m = (Method) subRead16Callbacks.get(address);
            il = new InstructionList();
            il.append(new ILOAD(0));
            il.append(new INVOKESTATIC(cp.addMethodref(m.getDeclaringClass().getName(), m.getName(), "(I)I")));
            il.append(new PUSH(cp, 0xffff));
            il.append(new IAND());
            il.append(new IRETURN());
            cases.put(address, il);
        }
        for (addresses = subRead32Callbacks.keySet().iterator(); addresses.hasNext();) {
            address = (Integer) addresses.next();
            int iAddress = address.intValue() & ~3;
            assert null == cases.get(address) : MiscUtil.toHex(address.intValue(), 8);

            m = (Method) subRead32Callbacks.get(address);

            il = new InstructionList();
            il.append(new ILOAD(0));
            il.append(new INVOKESTATIC(cp.addMethodref(m.getDeclaringClass().getName(), m.getName(), "(I)I")));
            il.append(new PUSH(cp, 0xffff));
            il.append(new IAND());
            il.append(new IRETURN());
            cases.put(address, il);

            il = new InstructionList();
            il.append(new PUSH(cp, iAddress));
            il.append(new INVOKESTATIC(cp.addMethodref(m.getDeclaringClass().getName(), m.getName(), "(I)I")));
            il.append(new PUSH(cp, 16));
            il.append(new ISHR());
            il.append(new IRETURN());
            cases.put(iAddress + 2, il);
        }

        suffix.append(new ILOAD(0));
        suffix.append(new INVOKESTATIC(cp.addMethodref(classname, "defaultRead16", "(I)I")));
        suffix.append(new IRETURN());
        rewriteHWMethod(cgen, "read16", "(I)I", cases, suffix, 2);

        // write 16
        cases.clear();
        for (addresses = write16Callbacks.keySet().iterator(); addresses.hasNext();) {
            address = (Integer) addresses.next();
            m = (Method) write16Callbacks.get(address);
            il = new InstructionList();
            il.append(new ILOAD(0));
            il.append(new ILOAD(1));
            il.append(new INVOKESTATIC(cp.addMethodref(m.getDeclaringClass().getName(), m.getName(), "(II)V")));
            il.append(new RETURN());
            cases.put(address, il);
        }
        for (addresses = subWrite16Callbacks.keySet().iterator(); addresses.hasNext();) {
            address = (Integer) addresses.next();
            assert null == cases.get(address) : MiscUtil.toHex(address.intValue(), 8);

            m = (Method) subWrite16Callbacks.get(address);
            il = new InstructionList();
            il.append(new ILOAD(0));
            il.append(new ILOAD(1));
            il.append(new PUSH(cp, 0xffff));
            il.append(new INVOKESTATIC(cp.addMethodref(m.getDeclaringClass().getName(), m.getName(), "(III)V")));
            il.append(new RETURN());
            cases.put(address, il);
        }
        for (addresses = subWrite32Callbacks.keySet().iterator(); addresses.hasNext();) {
            address = (Integer) addresses.next();
            int iAddress = address.intValue() & ~3;
            assert null == cases.get(address) : MiscUtil.toHex(address.intValue(), 8);

            m = (Method) subWrite32Callbacks.get(address);
            il = new InstructionList();
            il.append(new ILOAD(0));
            il.append(new ILOAD(1));
            il.append(new PUSH(cp, 0xffff));
            il.append(new IAND());
            il.append(new PUSH(cp, 0xffff));
            il.append(new INVOKESTATIC(cp.addMethodref(m.getDeclaringClass().getName(), m.getName(), "(III)V")));
            il.append(new RETURN());
            cases.put(address, il);

            m = (Method) subWrite32Callbacks.get(address);
            il = new InstructionList();
            il.append(new PUSH(cp, iAddress));
            il.append(new ILOAD(1));
            il.append(new PUSH(cp, 16));
            il.append(new ISHL());
            il.append(new PUSH(cp, 0xffff0000));
            il.append(new INVOKESTATIC(cp.addMethodref(m.getDeclaringClass().getName(), m.getName(), "(III)V")));
            il.append(new RETURN());
            cases.put(iAddress + 2, il);
        }
        suffix.append(new ILOAD(0));
        suffix.append(new ILOAD(1));
        suffix.append(new INVOKESTATIC(cp.addMethodref(classname, "defaultWrite16", "(II)V")));
        suffix.append(new RETURN());
        rewriteHWMethod(cgen, "write16", "(II)V", cases, suffix, 2);

        // read 8
        cases.clear();
        for (addresses = read8Callbacks.keySet().iterator(); addresses.hasNext();) {
            address = (Integer) addresses.next();
            m = (Method) read8Callbacks.get(address);
            il = new InstructionList();
            il.append(new ILOAD(0));
            il.append(new INVOKESTATIC(cp.addMethodref(m.getDeclaringClass().getName(), m.getName(), "(I)I")));
            il.append(new PUSH(cp, 0xff));
            il.append(new IAND());
            il.append(new IRETURN());
            cases.put(address, il);
        }
        for (addresses = subRead16Callbacks.keySet().iterator(); addresses.hasNext();) {
            address = (Integer) addresses.next();
            int iAddress = address.intValue() & ~1;
            assert null == cases.get(address) : MiscUtil.toHex(address.intValue(), 8);

            m = (Method) subRead16Callbacks.get(address);
            il = new InstructionList();
            il.append(new ILOAD(0));
            il.append(new INVOKESTATIC(cp.addMethodref(m.getDeclaringClass().getName(), m.getName(), "(I)I")));
            il.append(new PUSH(cp, 0xff));
            il.append(new IAND());
            il.append(new IRETURN());
            cases.put(address, il);

            il = new InstructionList();
            il.append(new PUSH(cp, iAddress));
            il.append(new INVOKESTATIC(cp.addMethodref(m.getDeclaringClass().getName(), m.getName(), "(I)I")));
            il.append(new PUSH(cp, 8));
            il.append(new ISHR());
            il.append(new PUSH(cp, 0xff));
            il.append(new IAND());
            il.append(new IRETURN());
            cases.put(iAddress + 1, il);
        }
        for (addresses = subRead32Callbacks.keySet().iterator(); addresses.hasNext();) {
            address = (Integer) addresses.next();
            int iAddress = address.intValue() & ~3;
            assert null == cases.get(address) : MiscUtil.toHex(address.intValue(), 8);

            m = (Method) subRead32Callbacks.get(address);
            il = new InstructionList();
            il.append(new ILOAD(0));
            il.append(new INVOKESTATIC(cp.addMethodref(m.getDeclaringClass().getName(), m.getName(), "(I)I")));
            il.append(new PUSH(cp, 0xff));
            il.append(new IAND());
            il.append(new IRETURN());
            cases.put(address, il);

            il = new InstructionList();
            il.append(new PUSH(cp, iAddress));
            il.append(new INVOKESTATIC(cp.addMethodref(m.getDeclaringClass().getName(), m.getName(), "(I)I")));
            il.append(new PUSH(cp, 8));
            il.append(new ISHR());
            il.append(new PUSH(cp, 0xff));
            il.append(new IAND());
            il.append(new IRETURN());
            cases.put(iAddress + 1, il);

            il = new InstructionList();
            il.append(new PUSH(cp, iAddress));
            il.append(new INVOKESTATIC(cp.addMethodref(m.getDeclaringClass().getName(), m.getName(), "(I)I")));
            il.append(new PUSH(cp, 16));
            il.append(new ISHR());
            il.append(new PUSH(cp, 0xff));
            il.append(new IAND());
            il.append(new IRETURN());
            cases.put(iAddress + 2, il);

            il = new InstructionList();
            il.append(new PUSH(cp, iAddress));
            il.append(new INVOKESTATIC(cp.addMethodref(m.getDeclaringClass().getName(), m.getName(), "(I)I")));
            il.append(new PUSH(cp, 24));
            il.append(new ISHR());
            il.append(new IRETURN());
            cases.put(iAddress + 3, il);
        }

        suffix.append(new ILOAD(0));
        suffix.append(new INVOKESTATIC(cp.addMethodref(classname, "defaultRead8", "(I)I")));
        suffix.append(new IRETURN());
        rewriteHWMethod(cgen, "read8", "(I)I", cases, suffix, 1);

        // write 8
        cases.clear();
        for (addresses = write8Callbacks.keySet().iterator(); addresses.hasNext();) {
            address = (Integer) addresses.next();
            m = (Method) write8Callbacks.get(address);
            il = new InstructionList();
            il.append(new ILOAD(0));
            il.append(new ILOAD(1));
            il.append(new INVOKESTATIC(cp.addMethodref(m.getDeclaringClass().getName(), m.getName(), "(II)V")));
            il.append(new RETURN());
            cases.put(address, il);
        }
        for (addresses = subWrite16Callbacks.keySet().iterator(); addresses.hasNext();) {
            address = (Integer) addresses.next();
            int iAddress = address.intValue() & ~1;
            assert null == cases.get(address) : MiscUtil.toHex(address.intValue(), 8);

            m = (Method) subWrite16Callbacks.get(address);
            il = new InstructionList();
            il.append(new ILOAD(0));
            il.append(new ILOAD(1));
            il.append(new PUSH(cp, 0xff));
            il.append(new IAND());
            il.append(new PUSH(cp, 0xff));
            il.append(new INVOKESTATIC(cp.addMethodref(m.getDeclaringClass().getName(), m.getName(), "(III)V")));
            il.append(new RETURN());
            cases.put(address, il);

            m = (Method) subWrite32Callbacks.get(address);
            il = new InstructionList();
            il.append(new PUSH(cp, iAddress));
            il.append(new ILOAD(1));
            il.append(new PUSH(cp, 8));
            il.append(new ISHL());
            il.append(new PUSH(cp, 0xff00));
            il.append(new INVOKESTATIC(cp.addMethodref(m.getDeclaringClass().getName(), m.getName(), "(III)V")));
            il.append(new RETURN());
            cases.put(iAddress + 1, il);
        }
        for (addresses = subWrite32Callbacks.keySet().iterator(); addresses.hasNext();) {
            address = (Integer) addresses.next();
            int iAddress = address.intValue() & ~3;
            assert null == cases.get(address) : MiscUtil.toHex(address.intValue(), 8);

            m = (Method) subWrite32Callbacks.get(address);
            il = new InstructionList();
            il.append(new ILOAD(0));
            il.append(new ILOAD(1));
            il.append(new PUSH(cp, 0xff));
            il.append(new IAND());
            il.append(new PUSH(cp, 0xff));
            il.append(new INVOKESTATIC(cp.addMethodref(m.getDeclaringClass().getName(), m.getName(), "(III)V")));
            il.append(new RETURN());
            cases.put(address, il);

            m = (Method) subWrite32Callbacks.get(address);
            il = new InstructionList();
            il.append(new PUSH(cp, iAddress));
            il.append(new ILOAD(1));
            il.append(new PUSH(cp, 0xff));
            il.append(new IAND());
            il.append(new PUSH(cp, 8));
            il.append(new ISHL());
            il.append(new PUSH(cp, 0x0000ff00));
            il.append(new INVOKESTATIC(cp.addMethodref(m.getDeclaringClass().getName(), m.getName(), "(III)V")));
            il.append(new RETURN());
            cases.put(iAddress + 1, il);

            m = (Method) subWrite32Callbacks.get(address);
            il = new InstructionList();
            il.append(new PUSH(cp, iAddress));
            il.append(new ILOAD(1));
            il.append(new PUSH(cp, 0xff));
            il.append(new IAND());
            il.append(new PUSH(cp, 16));
            il.append(new ISHL());
            il.append(new PUSH(cp, 0x00ff0000));
            il.append(new INVOKESTATIC(cp.addMethodref(m.getDeclaringClass().getName(), m.getName(), "(III)V")));
            il.append(new RETURN());
            cases.put(iAddress + 2, il);

            m = (Method) subWrite32Callbacks.get(address);
            il = new InstructionList();
            il.append(new PUSH(cp, iAddress));
            il.append(new ILOAD(1));
            il.append(new PUSH(cp, 24));
            il.append(new ISHL());
            il.append(new PUSH(cp, 0xff000000));
            il.append(new INVOKESTATIC(cp.addMethodref(m.getDeclaringClass().getName(), m.getName(), "(III)V")));
            il.append(new RETURN());
            cases.put(iAddress + 3, il);
        }

        suffix.append(new ILOAD(0));
        suffix.append(new ILOAD(1));
        suffix.append(new INVOKESTATIC(cp.addMethodref(classname, "defaultWrite8", "(II)V")));
        suffix.append(new RETURN());
        rewriteHWMethod(cgen, "write8", "(II)V", cases, suffix, 1);

        return cgen;
    }


    public void tagClearPollCounters() {
        _tagClearPollCounters();
    }

    public static void _tagClearPollCounters() {
        readPC0 = readPC1 = readPC2 = -1;
    }


    public void tagAddressAccessRead8(int pc, int address) {
        _tagAddressAccessRead8(pc, address);
    }

    /**
     * Note this method requires that pc falls either in RAM or
     * BIOS
     */
    public static void _tagAddressAccessRead8(final int pc, final int address) {
        byte[] tags;
        if (pc < BIOS_BASE || pc >= BIOS_END) {
            tags = ramTags;
        } else {
            tags = biosTags;
        }

        int value;
        byte tag = 0;//TAG_INVALID; ?? todo why was this here
        if (Settings.tagAddressAccess) {
            int prefix = address >> 28;
            int offset = address & OFFSET_MASK;
            if (prefix == -8 && offset < RAM_SIZE) {
                tag = TAG_RAM;
            } else if (address < SCRATCH_END && address >= SCRATCH_BASE) {
                tag = TAG_SCRATCH;
            } else if (prefix == 0 && offset < RAM_SIZE) {
                tag = TAG_RAM;
            } else if (prefix == -6 && offset < RAM_SIZE) {
                tag = TAG_RAM;
            } else if (address < HW_END && address >= HW_BASE) {
                tag = TAG_HW;
            } else if (address < PAR_END && address >= PAR_BASE) {
                tag = TAG_PAR;
            } else if (address >= BIOS_BASE && address < BIOS_END) {
                tag = TAG_BIOS;
            }
        }

        int index = (pc & 0x1fffff) >> 2;
        int oldTag = tags[index];
        if (0 != (oldTag & TAG_POLL)) {
            _checkPoll8(address);
        } else {
            if ((pc == readPC0 && address == readAddress0) || (pc == readPC1 && address == readAddress1) || (pc == readPC2 && address == readAddress2)) {
                tag |= TAG_POLL;
                if (logPollDebug) {
                    logPoll.debug("possible poll at " + MiscUtil.toHex(pc, 8) + " of " + MiscUtil.toHex(address, 8));
                }
            } else if (pc == readPCHW && address == readAddressHW && readsSinceHW < MAX_READS_SINCE_HW) {
                tag |= TAG_POLL;
                if (logPollDebug) {
                    logPoll.debug("possible HW specific poll8 at " + MiscUtil.toHex(pc, 8) + " of " + MiscUtil.toHex(address, 8));
                }
            }
        }
        readPC0 = readPC1;
        readPC1 = readPC2;
        readPC2 = pc;
        readAddress0 = readAddress1;
        readAddress1 = readAddress2;
        readAddress2 = address;
        if (address < HW_END && address >= HW_BASE) {
            readAddressHW = address;
            readPCHW = pc;
            readsSinceHW = 0;
        } else {
            readsSinceHW++;
        }

        tags[index] |= tag;
    }

    public void tagAddressAccessRead16(final int pc, final int address) {
        _tagAddressAccessRead16(pc, address);
    }

    /**
     * Note this method requires that pc falls either in RAM or
     * BIOS
     */
    public static void _tagAddressAccessRead16(final int pc, final int address) {
        byte[] tags;
        if (pc < BIOS_BASE || pc >= BIOS_END) {
            tags = ramTags;
        } else {
            tags = biosTags;
        }

        byte tag = 0;//TAG_INVALID; ?? todo why was this here
        if (Settings.tagAddressAccess) {
            int prefix = address >> 28;
            int offset = address & OFFSET_MASK;
            if (prefix == -8 && offset < RAM_SIZE) {
                tag = TAG_RAM;
            } else if (address < SCRATCH_END && address >= SCRATCH_BASE) {
                tag = TAG_SCRATCH;
            } else if (prefix == 0 && offset < RAM_SIZE) {
                tag = TAG_RAM;
            } else if (prefix == -6 && offset < RAM_SIZE) {
                tag = TAG_RAM;
            } else if (address < HW_END && address >= HW_BASE) {
                tag = TAG_HW;
            } else if (address < PAR_END && address >= PAR_BASE) {
                tag = TAG_PAR;
            } else if (address >= BIOS_BASE && address < BIOS_END) {
                tag = TAG_BIOS;
            }
        }

        int index = (pc & 0x1fffff) >> 2;
        int oldTag = tags[index];
        if (0 != (oldTag & TAG_POLL)) {
            _checkPoll16(address);
        } else {
            if ((pc == readPC0 && address == readAddress0) || (pc == readPC1 && address == readAddress1) || (pc == readPC2 && address == readAddress2)) {
                tag |= TAG_POLL;
                if (logPollDebug) {
                    logPoll.debug("possible poll at " + MiscUtil.toHex(pc, 8) + " of " + MiscUtil.toHex(address, 8));
                }
            } else if (pc == readPCHW && address == readAddressHW && readsSinceHW < MAX_READS_SINCE_HW) {
                tag |= TAG_POLL;
                if (logPollDebug) {
                    logPoll.debug("possible HW specific poll16 at " + MiscUtil.toHex(pc, 8) + " of " + MiscUtil.toHex(address, 8));
                }
            }
        }
        readPC0 = readPC1;
        readPC1 = readPC2;
        readPC2 = pc;
        readAddress0 = readAddress1;
        readAddress1 = readAddress2;
        readAddress2 = address;
        if (address < HW_END && address >= HW_BASE) {
            readAddressHW = address;
            readPCHW = pc;
            readsSinceHW = 0;
        } else {
            readsSinceHW++;
        }
        tags[index] |= tag;
    }

    public void tagAddressAccessRead32(int pc, int address) {
        _tagAddressAccessRead32(pc, address);
    }

    /**
     * Note this method requires that pc falls either in RAM or
     * BIOS
     */
    public static void _tagAddressAccessRead32(final int pc, final int address) {
        byte[] tags;
        if (pc < BIOS_BASE || pc >= BIOS_END) {
            tags = ramTags;
        } else {
            tags = biosTags;
        }

        byte tag = 0;//TAG_INVALID; ?? todo why was this here

        if (Settings.tagAddressAccess) {
            int prefix = address >> 28;
            int offset = address & OFFSET_MASK;
            if (prefix == -8 && offset < RAM_SIZE) {
                tag = TAG_RAM;
            } else if (address < SCRATCH_END && address >= SCRATCH_BASE) {
                tag = TAG_SCRATCH;
            } else if (prefix == 0 && offset < RAM_SIZE) {
                tag = TAG_RAM;
            } else if (prefix == -6 && offset < RAM_SIZE) {
                tag = TAG_RAM;
            } else if (address < HW_END && address >= HW_BASE) {
                tag = TAG_HW;
            } else if (address < PAR_END && address >= PAR_BASE) {
                tag = TAG_PAR;
            } else if (address >= BIOS_BASE && address < BIOS_END) {
                tag = TAG_BIOS;
            }
        }

        int index = (pc & 0x1fffff) >> 2;
        int oldTag = tags[index];
        if (0 != (oldTag & TAG_POLL)) {
            _checkPoll32(address);
        } else {
            // Simple check of last 3 reads
            if ((pc == readPC0 && address == readAddress0) || (pc == readPC1 && address == readAddress1) || (pc == readPC2 && address == readAddress2)) {
                tag |= TAG_POLL;
                if (logPollDebug) {
                    logPoll.debug("possible poll32 at " + MiscUtil.toHex(pc, 8) + " of " + MiscUtil.toHex(address, 8));
                }
            } else if (pc == readPCHW && address == readAddressHW && readsSinceHW < MAX_READS_SINCE_HW) {
                // Tony Hawk fails to catch poll of 0x1f801814 for vsync in interlaced mode, because the polling is spread
                // across several functions... in this hardware read case, lets look back a little further
                tag |= TAG_POLL;
                if (logPollDebug) {
                    logPoll.debug("possible HW specific poll32 at " + MiscUtil.toHex(pc, 8) + " of " + MiscUtil.toHex(address, 8));
                }
            }
        }
        readPC0 = readPC1;
        readPC1 = readPC2;
        readPC2 = pc;
        readAddress0 = readAddress1;
        readAddress1 = readAddress2;
        readAddress2 = address;
        if (address < HW_END && address >= HW_BASE) {
            readAddressHW = address;
            readPCHW = pc;
            readsSinceHW = 0;
        } else {
            readsSinceHW++;
        }
        tags[index] |= tag;
    }

    public void tagAddressAccessWrite(final int pc, final int address) {
        _tagAddressAccessWrite(pc, address);
    }

    /**
     * Note this method requires that pc falls either in RAM or
     * BIOS
     */
    public static void _tagAddressAccessWrite(final int pc, final int address) {
        if (Settings.tagAddressAccess) {
            byte[] tags;
            if (pc < BIOS_BASE || pc >= BIOS_END) {
                tags = ramTags;
            } else {
                tags = biosTags;
            }

            int prefix = address >> 28;
            int offset = address & OFFSET_MASK;
            byte tag = 0; // why is this there? TAG_INVALID;
            if (prefix == -8 && offset < RAM_SIZE) {
                tag = TAG_RAM;
            } else if (address < SCRATCH_END && address >= SCRATCH_BASE) {
                tag = TAG_SCRATCH;
            } else if (prefix == 0 && offset < RAM_SIZE) {
                tag = TAG_RAM;
            } else if (prefix == -6 && offset < RAM_SIZE) {
                tag = TAG_RAM;
            } else if (address < HW_END && address >= HW_BASE) {
                tag = TAG_HW;
            } else if (address < PAR_END && address >= PAR_BASE) {
                tag = TAG_PAR;
            } else if (address >= BIOS_BASE && address < BIOS_END) {
//				System.out.println("------------ "+MiscUtil.toHex( pc, 8)+" : "+MiscUtil.toHex( address, 8));
                tag = TAG_BIOS;
            }

            tags[(pc & 0x1fffff) >> 2] |= tag;
        }
    }

    public byte getTag(int pc) {
        byte[] tags;
        if (pc < BIOS_BASE || pc >= BIOS_END) {
            tags = ramTags;
        } else {
            tags = biosTags;
        }
        int index = (pc & 0x1fffff) >> 2;
        return tags[index];
    }

    public void orTag(final int pc, byte val) {
        byte[] tags;
        if (pc < BIOS_BASE || pc >= BIOS_END) {
            tags = ramTags;
        } else {
            tags = biosTags;
        }
        int index = (pc & 0x1fffff) >> 2;
        tags[index] |= val;
    }

    public int read8(int address) {
        return _read8(address);
    }

    public static int _read8(final int address) {
        int prefix = address >> 28;
        int offset = address & OFFSET_MASK;
        int value;
        if (prefix == -8 && offset < RAM_SIZE) {
            value = ramD[offset >> 2];
        } else if (address < SCRATCH_END && address >= SCRATCH_BASE) {
            value = scratch[(offset & SCRATCH_MASK) >> 2];
        } else if (prefix == 0 && offset < RAM_SIZE) {
            value = ramD[offset >> 2];
        } else if (prefix == -6 && offset < RAM_SIZE) {
            value = ramD[offset >> 2];
        } else if (address < HW_END && address >= HW_BASE) {
            return Hardware.read8(address);
        } else if (address < PAR_END && address >= PAR_BASE) {
            return _parRead8(address);
        } else if (address >= BIOS_BASE && address < BIOS_END) {
            value = bios[(offset & BIOS_MASK) >> 2];
        } else {
            if (Settings.assertOnUnknownAddress)
                throw new IllegalStateException("ACK unknown address " + MiscUtil.toHex(address, 8));
            value = 0;
        }
        switch (address & 3) {
            case 3:
                return (value >> 24) & 0xff;
            case 2:
                return (value >> 16) & 0xff;
            case 1:
                return (value >> 8) & 0xff;
            default:
                return value & 0xff;
        }
    }

    public static int _read8Ram(final int address) {
        int value = ramD[(address & RAM_AND) >> 2];

        switch (address & 3) {
            case 3:
                return (value >> 24) & 0xff;
            case 2:
                return (value >> 16) & 0xff;
            case 1:
                return (value >> 8) & 0xff;
            default:
                return value & 0xff;
        }
    }

    public static int _read8Scratch(final int address) {
        int value = scratch[(address ^ SCRATCH_XOR) >> 2];

        switch (address & 3) {
            case 3:
                return (value >> 24) & 0xff;
            case 2:
                return (value >> 16) & 0xff;
            case 1:
                return (value >> 8) & 0xff;
            default:
                return value & 0xff;
        }
    }

    public static int _read8Bios(final int address) {
        int value = bios[(address ^ BIOS_XOR) >> 2];

        switch (address & 3) {
            case 3:
                return (value >> 24) & 0xff;
            case 2:
                return (value >> 16) & 0xff;
            case 1:
                return (value >> 8) & 0xff;
            default:
                return value & 0xff;
        }
    }

    public static void _write8Bios(final int address, int value) {
        int dummy = bios[(address ^ BIOS_XOR) >> 2];
    }

    public static void _write16Bios(final int address, int value) {
        int dummy = bios[(address ^ BIOS_XOR) >> 2];
    }

    public static void _write32Bios(final int address, int value) {
        int dummy = bios[(address ^ BIOS_XOR) >> 2];
    }

    public int read16(int address) {
        return _read16(address);
    }

    public static int _read16(final int address) {
        if (Settings.checkAlignment) {
            if ((address & 1) != 0) {
                throw new IllegalStateException("misaligned");
            }
        }
        int prefix = address >> 28;
        int offset = address & OFFSET_MASK;
        int value;
        if (prefix == -8 && offset < RAM_SIZE) {
            value = ramD[offset >> 2];
        } else if (address < SCRATCH_END && address >= SCRATCH_BASE) {
            value = scratch[(offset & SCRATCH_MASK) >> 2];
        } else if (prefix == 0 && offset < RAM_SIZE) {
            value = ramD[offset >> 2];
        } else if (prefix == -6 && offset < RAM_SIZE) {
            value = ramD[offset >> 2];
        } else if (address < HW_END && address >= HW_BASE) {
            return Hardware.read16(address);
        } else if (address < PAR_END && address >= PAR_BASE) {
            return _parRead16(address);
        } else if (address >= BIOS_BASE && address < BIOS_END) {
            value = bios[(offset & BIOS_MASK) >> 2];
        } else {
            if (Settings.assertOnUnknownAddress)
                throw new IllegalStateException("ACK unknown address " + MiscUtil.toHex(address, 8));
            value = 0;
        }
        switch (address & 2) {
            case 2:
                return (value >> 16) & 0xffff;
            default:
                return value & 0xffff;
        }
    }

    public static int _read16Ram(final int address) {
        if (Settings.checkAlignment) {
            if ((address & 1) != 0) {
                throw new IllegalStateException("misaligned");
            }
        }
        int value = ramD[(address & RAM_AND) >> 2];
        switch (address & 2) {
            case 2:
                return (value >> 16) & 0xffff;
            default:
                return value & 0xffff;
        }
    }

    public static int _read16Scratch(final int address) {
        if (Settings.checkAlignment) {
            if ((address & 1) != 0) {
                throw new IllegalStateException("misaligned");
            }
        }
        int value = scratch[(address ^ SCRATCH_XOR) >> 2];
        switch (address & 2) {
            case 2:
                return (value >> 16) & 0xffff;
            default:
                return value & 0xffff;
        }
    }

    public static int _read16Bios(final int address) {
        if (Settings.checkAlignment) {
            if ((address & 1) != 0) {
                throw new IllegalStateException("misaligned");
            }
        }
        int value = bios[(address ^ BIOS_XOR) >> 2];
        switch (address & 2) {
            case 2:
                return (value >> 16) & 0xffff;
            default:
                return value & 0xffff;
        }
    }

    public int read32(int address) {
        return _read32(address);
    }

    public static int _read32(final int address) {
        if (Settings.checkAlignment) {
            if ((address & 3) != 0) {
                throw new IllegalStateException("misaligned");
            }
        }
        int prefix = address >> 28;
        int offset = address & OFFSET_MASK;
        if (prefix == -8 && offset < RAM_SIZE) {
            return ramD[offset >> 2];
        } else if (address < SCRATCH_END && address >= SCRATCH_BASE) {
            return scratch[(offset & SCRATCH_MASK) >> 2];
        } else if (prefix == -6 && offset < RAM_SIZE) {
            return ramD[offset >> 2];
        } else if (prefix == 0 && offset < RAM_SIZE) {
            return ramD[offset >> 2];
        } else if (address < HW_END && address >= HW_BASE) {
            return Hardware.read32(address);
        } else if (address < PAR_END && address >= PAR_BASE) {
            return _parRead32(address);
        } else if (address >= BIOS_BASE && address < BIOS_END) {
            return bios[(offset & BIOS_MASK) >> 2];
        }
        if (Settings.assertOnUnknownAddress)
            throw new IllegalStateException("ACK unknown address " + MiscUtil.toHex(address, 8));
        return 0;
    }


    public void write8(int address, int value) {
        _write8(address, value);
    }

    public static void _write8(final int address, final int value) {
        int prefix = address >> 28;
        int offset = address & OFFSET_MASK;

        int mask;
        int nvalue;
        switch (address & 3) {
            case 0:
                mask = 0xffffff00;
                nvalue = (value & 0xff);
                break;
            case 1:
                mask = 0xffff00ff;
                nvalue = (value & 0xff) << 8;
                break;
            case 2:
                mask = 0xff00ffff;
                nvalue = (value & 0xff) << 16;
                break;
            default:
                mask = 0x00ffffff;
                nvalue = value << 24;
                break;
        }
        if (prefix == -8 && offset < RAM_SIZE) {
            ramD[offset >> 2] = (ramD[offset >> 2] & mask) | nvalue;
        } else if (address < SCRATCH_END && address >= SCRATCH_BASE) {
            scratch[(offset & SCRATCH_MASK) >> 2] = (scratch[(offset & SCRATCH_MASK) >> 2] & mask) | nvalue;
        } else if (prefix == 0 && offset < RAM_SIZE) {
            ramD[offset >> 2] = (ramD[offset >> 2] & mask) | nvalue;
        } else if (prefix == -6 && offset < RAM_SIZE) {
            ramD[offset >> 2] = (ramD[offset >> 2] & mask) | nvalue;
        } else if (address < HW_END && address >= HW_BASE) {
            Hardware.write8(address, value);
        } else if (address < PAR_END && address >= PAR_BASE) {
            if (!_parWrite8(address, value)) {
                //System.out.println("write8 HW "+MiscUtil.toHex( address, 8));
                par[(offset & PAR_MASK) >> 2] = (par[(offset & PAR_MASK) >> 2] & mask) | nvalue;
            }
        } else if (address >= BIOS_BASE && address < BIOS_END) {
        } else {
            if (Settings.assertOnUnknownAddress)
                throw new IllegalStateException("ACK unknown address " + MiscUtil.toHex(address, 8));
        }
        return;
    }

    public static void _write8Ram(int address, final int value) {
        int mask;
        int nvalue;
        switch (address & 3) {
            case 0:
                mask = 0xffffff00;
                nvalue = (value & 0xff);
                break;
            case 1:
                mask = 0xffff00ff;
                nvalue = (value & 0xff) << 8;
                break;
            case 2:
                mask = 0xff00ffff;
                nvalue = (value & 0xff) << 16;
                break;
            default:
                mask = 0x00ffffff;
                nvalue = value << 24;
                break;
        }
        address = (address & RAM_AND) >> 2;
        ramD[address] = (ramD[address] & mask) | nvalue;
        return;
    }

    public static void _write8Scratch(int address, final int value) {
        int mask;
        int nvalue;
        switch (address & 3) {
            case 0:
                mask = 0xffffff00;
                nvalue = (value & 0xff);
                break;
            case 1:
                mask = 0xffff00ff;
                nvalue = (value & 0xff) << 8;
                break;
            case 2:
                mask = 0xff00ffff;
                nvalue = (value & 0xff) << 16;
                break;
            default:
                mask = 0x00ffffff;
                nvalue = value << 24;
                break;
        }
        address = (address ^ SCRATCH_XOR) >> 2;
        scratch[address] = (scratch[address] & mask) | nvalue;
        return;
    }

    public void write16(int address, int value) {
        _write16(address, value);
    }

    public static void _write16(final int address, final int value) {
        if (Settings.checkAlignment) {
            if ((address & 1) != 0) {
                throw new IllegalStateException("misaligned");
            }
        }
        int prefix = address >> 28;
        int offset = address & OFFSET_MASK;

        // todo reverse sense of mask
        int mask;
        int nvalue;
        switch (address & 3) {
            case 0:
                mask = 0xffff0000;
                nvalue = (value & 0xffff);
                break;
            default:
                mask = 0x0000ffff;
                nvalue = value << 16;
        }
        if (prefix == -8 && offset < RAM_SIZE) {
            ramD[offset >> 2] = (ramD[offset >> 2] & mask) | nvalue;
        } else if (address < SCRATCH_END && address >= SCRATCH_BASE) {
            scratch[(offset & SCRATCH_MASK) >> 2] = (scratch[(offset & SCRATCH_MASK) >> 2] & mask) | nvalue;
        } else if (prefix == 0 && offset < RAM_SIZE) {
            ramD[offset >> 2] = (ramD[offset >> 2] & mask) | nvalue;
        } else if (prefix == -6 && offset < RAM_SIZE) {
            ramD[offset >> 2] = (ramD[offset >> 2] & mask) | nvalue;
        } else if (address < HW_END && address >= HW_BASE) {
            Hardware.write16(address, value);
        } else if (address < PAR_END && address >= PAR_BASE) {
            if (!_parWrite16(address, value)) {
                par[(offset & PAR_MASK) >> 2] = (par[(offset & PAR_MASK) >> 2] & mask) | nvalue;
            }
        } else if (address >= BIOS_BASE && address < BIOS_END) {
        } else {
            if (Settings.assertOnUnknownAddress)
                throw new IllegalStateException("ACK unknown address " + MiscUtil.toHex(address, 8));
        }
        return;
    }

    public static void _write16Ram(int address, final int value) {
        if (Settings.checkAlignment) {
            if ((address & 1) != 0) {
                throw new IllegalStateException("misaligned");
            }
        }

        int mask;
        int nvalue;
        switch (address & 3) {
            case 0:
                mask = 0xffff0000;
                nvalue = (value & 0xffff);
                break;
            default:
                mask = 0x0000ffff;
                nvalue = value << 16;
        }
        address = (address & RAM_AND) >> 2;
        ramD[address] = (ramD[address] & mask) | nvalue;
        return;
    }

    public static void _write16Scratch(int address, final int value) {
        if (Settings.checkAlignment) {
            if ((address & 1) != 0) {
                throw new IllegalStateException("misaligned");
            }
        }

        int mask;
        int nvalue;
        switch (address & 3) {
            case 0:
                mask = 0xffff0000;
                nvalue = (value & 0xffff);
                break;
            default:
                mask = 0x0000ffff;
                nvalue = value << 16;
        }
        address = (address ^ SCRATCH_XOR) >> 2;
        scratch[address] = (scratch[address] & mask) | nvalue;
        return;
    }

    public void write32(int address, int value) {
        _write32(address, value);
    }

    public static void _write32(final int address, final int value) {
        if (Settings.checkAlignment) {
            if ((address & 3) != 0) {
                throw new IllegalStateException("misaligned");
            }
        }
        int prefix = address >> 28;
        int offset = address & OFFSET_MASK;
        if (prefix == -8 && offset < RAM_SIZE) {
            ramD[offset >> 2] = value;
        } else if (address < SCRATCH_END && address >= SCRATCH_BASE) {
            scratch[(offset & SCRATCH_MASK) >> 2] = value;
        } else if (prefix == 0 && offset < RAM_SIZE) {
            ramD[offset >> 2] = value;
        } else if (prefix == -6 && offset < RAM_SIZE) {
            ramD[offset >> 2] = value;
        } else if (address < HW_END && address >= HW_BASE) {
            Hardware.write32(address, value);
        } else if (address < PAR_END && address >= PAR_BASE) {
            if (!_parWrite32(address, value)) {
                par[(offset & PAR_MASK) >> 2] = value;
            }
        } else if (address >= BIOS_BASE && address < BIOS_END) {
        } else if (address == 0xfffe0130) {
            //    System.out.println( "write 0xfffe0130 "+MiscUtil.toHex( value, 8));
        } else {
            if (Settings.assertOnUnknownAddress)
                throw new IllegalStateException("ACK unknown address " + MiscUtil.toHex(address, 8));
        }
    }

    public int internalRead32(final int address) {
        return _internalRead32(address);
    }

    public static int _internalRead32(final int address) {
        // todo check misaligned address?
        int prefix = address >> 28;
        int offset = address & OFFSET_MASK;
        if (prefix == -8 && offset < RAM_SIZE) {
            return ram[offset >> 2];
        } else if (address < SCRATCH_END && address >= SCRATCH_BASE) {
            return scratch[(offset & SCRATCH_MASK) >> 2];
        } else if (address >= BIOS_BASE && address < BIOS_END) {
            return bios[(offset & BIOS_MASK) >> 2];
        } else if (address < HW_END && address >= HW_BASE) {
            return hw[(offset & HW_MASK) >> 2];
        } else if (address < PAR_END && address >= PAR_BASE) {
            return par[(offset & PAR_MASK) >> 2];
        } else if (prefix == 0 && offset < RAM_SIZE) {
            return ram[offset >> 2];
        } else if (prefix == -6 && offset < RAM_SIZE) {
            return ram[offset >> 2];
        }

        if (Settings.assertOnUnknownAddress)
            throw new IllegalStateException("ACK unknown address " + MiscUtil.toHex(address, 8));
        return 0;
    }

    public void internalWrite32(int address, int value) {
        _internalWrite32(address, value);
    }

    public static void _internalWrite32(final int address, final int value) {
        // todo check misaligned address?
        int prefix = address >> 28;
        int offset = address & OFFSET_MASK;
        if (prefix == -8 && offset < RAM_SIZE) {
            ram[offset >> 2] = value;
        } else if (address < SCRATCH_END && address >= SCRATCH_BASE) {
            scratch[(offset & SCRATCH_MASK) >> 2] = value;
        } else if (address < HW_END && address >= HW_BASE) {
            hw[(offset & HW_MASK) >> 2] = value;
        } else if (address < PAR_END && address >= PAR_BASE) {
            par[(offset & PAR_MASK) >> 2] = value;
        } else if (address >= BIOS_BASE && address < BIOS_END) {
            bios[(offset & BIOS_MASK) >> 2] = value;
        } else if (prefix == 0 && offset < RAM_SIZE) {
            ram[offset >> 2] = value;
        } else if (prefix == -6 && offset < RAM_SIZE) {
            ram[offset >> 2] = value;
        }
    }

    private static Map<Integer, Method> read8Callbacks = CollectionsFactory.newHashMap();
    private static Map<Integer, Method> read16Callbacks = CollectionsFactory.newHashMap();
    private static Map<Integer, Method> subRead16Callbacks = CollectionsFactory.newHashMap();
    private static Map<Integer, Method> read32Callbacks = CollectionsFactory.newHashMap();
    private static Map<Integer, Method> subRead32Callbacks = CollectionsFactory.newHashMap();
    private static Map<Integer, Method> write8Callbacks = CollectionsFactory.newHashMap();
    private static Map<Integer, Method> write16Callbacks = CollectionsFactory.newHashMap();
    private static Map<Integer, Method> subWrite16Callbacks = CollectionsFactory.newHashMap();
    private static Map<Integer, Method> write32Callbacks = CollectionsFactory.newHashMap();
    private static Map<Integer, Method> subWrite32Callbacks = CollectionsFactory.newHashMap();

    private static Map<Integer, Pollable> poll8Callbacks = CollectionsFactory.newHashMap();
    private static Map<Integer, Pollable> poll16Callbacks = CollectionsFactory.newHashMap();
    private static Map<Integer, Pollable> poll32Callbacks = CollectionsFactory.newHashMap();

    private static final Class[] READ_CALLBACK_PARAMS = new Class[]{int.class};
    private static final Class[] WRITE_CALLBACK_PARAMS = new Class[]{int.class, int.class};
    private static final Class[] SUBWRITE_CALLBACK_PARAMS = new Class[]{int.class, int.class, int.class};

    private void registerCallback(Map<Integer, Method> map, int address, Class clazz, String methodName, Class[] params) {
        //System.out.println("  register address "+MiscUtil.toHex( address, 8));
        try {
            Method m = clazz.getMethod(methodName, params);
            map.put(address, m);
        } catch (Throwable t) {
            StringBuilder desc = new StringBuilder();
            desc.append("Cannot find method ");
            desc.append(clazz.getName());
            desc.append(".");
            desc.append(methodName);
            desc.append("(");
            for (int i = 0; i < params.length; i++) {
                if (i != 0) desc.append(",");
                desc.append(params[i].getName());
            }
            desc.append(")");
            throw new IllegalStateException(desc.toString());
        }
    }

    public void registerRead8Callback(int address, Class clazz, String methodName) {
        registerCallback(read8Callbacks, address, clazz, methodName, READ_CALLBACK_PARAMS);
    }

    public void registerRead16Callback(int address, Class clazz, String methodName) {
        registerRead16Callback(address, clazz, methodName, false);
    }

    public void registerRead16Callback(int address, Class clazz, String methodName, boolean allowSubRead) {
        assert 0 == (address & 1);
        if (allowSubRead) {
            registerCallback(subRead16Callbacks, address, clazz, methodName, READ_CALLBACK_PARAMS);
        } else {
            registerCallback(read16Callbacks, address, clazz, methodName, READ_CALLBACK_PARAMS);
        }
    }

    public void registerRead32Callback(int address, Class clazz, String methodName, boolean allowSubRead) {
        assert 0 == (address & 3);
        if (allowSubRead) {
            registerCallback(subRead32Callbacks, address, clazz, methodName, READ_CALLBACK_PARAMS);
        } else {
            registerCallback(read32Callbacks, address, clazz, methodName, READ_CALLBACK_PARAMS);
        }
    }

    public void registerPoll32Callback(int address, Pollable pollable) {
        poll32Callbacks.put(address, pollable);
    }

    public void registerRead32Callback(int address, Class clazz, String methodName) {
        registerRead32Callback(address, clazz, methodName, false);
    }

    public void registerWrite8Callback(int address, Class clazz, String methodName) {
        registerCallback(write8Callbacks, address, clazz, methodName, WRITE_CALLBACK_PARAMS);
    }

    public void registerWrite16Callback(int address, Class clazz, String methodName) {
        registerWrite16Callback(address, clazz, methodName, false);
    }

    public void registerWrite16Callback(int address, Class clazz, String methodName, boolean allowSubWrite) {
        assert 0 == (address & 1);
        if (allowSubWrite) {
            registerCallback(subWrite16Callbacks, address, clazz, methodName, SUBWRITE_CALLBACK_PARAMS);
        } else {
            registerCallback(write16Callbacks, address, clazz, methodName, WRITE_CALLBACK_PARAMS);
        }
    }

    public void registerWrite32Callback(int address, Class clazz, String methodName) {
        registerWrite32Callback(address, clazz, methodName, false);
    }

    public void registerWrite32Callback(int address, Class clazz, String methodName, boolean allowSubWrite) {
        assert 0 == (address & 3);
        if (allowSubWrite) {
            registerCallback(subWrite32Callbacks, address, clazz, methodName, SUBWRITE_CALLBACK_PARAMS);
        } else {
            registerCallback(write32Callbacks, address, clazz, methodName, WRITE_CALLBACK_PARAMS);
        }
    }

    public void resolve(int address, ResolveResult result) {
        result.address = address;
        result.low2 = address & 3;

        int prefix = address >> 28;
        int offset = address & OFFSET_MASK;
        if ((prefix == 0 || prefix == -8 || prefix == -6) && offset < RAM_SIZE) {
            result.mem = ram;
            result.offset = offset >> 2;
            result.tag = TAG_RAM;
            return;
        } else if (address < SCRATCH_END && address >= SCRATCH_BASE) {
            result.mem = scratch;
            result.offset = (offset & SCRATCH_MASK) >> 2;
            result.tag = TAG_SCRATCH;
            return;
        } else if (address < HW_END && address >= HW_BASE) {
            result.mem = hw;
            result.offset = (offset & HW_MASK) >> 2;
            result.tag = TAG_HW;
            return;
        } else if (address >= BIOS_BASE && address < BIOS_END) {
            result.mem = bios;
            result.offset = (offset & BIOS_MASK) >> 2;
            result.tag = TAG_BIOS;
            return;
        }
        result.mem = null;
        //result.tag = TAG_INVALID;
        return;
    }

    public void resolve(int address, int size, boolean write, ResolveResult result) {
        result.address = address;
        result.low2 = address & 3;

        // assert end>=address

        int end = size == 0 ? address : (address + size - 1);
        int prefix = address >> 28;
        int prefix2 = end >> 28;
        if (prefix == prefix2) {
            int offset = address & OFFSET_MASK;
            int offset2 = end & OFFSET_MASK;
            if ((prefix == 0 || prefix == -8 || prefix == -6) && offset < RAM_SIZE && offset2 < RAM_SIZE) {
                result.mem = ram;
                result.offset = offset >> 2;
                result.tag = TAG_RAM;
                return;
            } else if (address < SCRATCH_END && address >= SCRATCH_BASE && end < SCRATCH_END) {
                result.mem = scratch;
                result.offset = (offset & SCRATCH_MASK) >> 2;
                result.tag = TAG_SCRATCH;
                return;
            } else if (address < HW_END && address >= HW_BASE && end < HW_END) {
                result.mem = hw;
                result.offset = (offset & HW_MASK) >> 2;
                result.tag = TAG_HW;
                return;
            } else if (address >= BIOS_BASE && address < BIOS_END && end < BIOS_END) {
                result.mem = bios;
                result.offset = (offset & BIOS_MASK) >> 2;
                result.tag = TAG_BIOS;
                return;
            }
        }
        result.mem = null;
        //result.tag = TAG_INVALID;
    }

    public static int _parRead8(final int address) {
        //System.out.println( "PAR READ 8: " + MiscUtil.toHex( address, 8 ) );
        return 0;
    }

    public static int _parRead16(final int address) {
        //System.out.println( "PAR READ 16: " + MiscUtil.toHex( address, 8 ) );
        return 0;
    }

    public static int _parRead32(final int address) {
        //System.out.println( "PAR READ 32: " + MiscUtil.toHex( address, 8 ) );
        return 0;
    }

    public static boolean _parWrite8(final int address, int value) {
        //System.out.println( "PAR WRITE 8: " + MiscUtil.toHex( address, 8 ) + " " + MiscUtil.toHex( value, 2 ) );
        return false;
    }

    public static boolean _parWrite16(final int address, int value) {
        //System.out.println( "PAR WRITE 16: " + MiscUtil.toHex( address, 8 ) + " " + MiscUtil.toHex( value, 4 ) );
        return false;
    }

    public static boolean _parWrite32(final int address, int value) {
        //System.out.println( "PAR WRITE 32: " + MiscUtil.toHex( address, 8 ) + " " + MiscUtil.toHex( value, 8 ) );
        return false;
    }

    public void enableMemoryWrite(boolean enableWrite) {
        ramD = enableWrite ? ram : ramDummy;
        if (!enableWrite && writeEnabled) {
            // clear all the tags
            for (int i = 0; i < RAM_SIZE / 4; i++) {
                ramTags[i] = 0;
            }
            addressSpaceListeners.cacheCleared();
        }
        writeEnabled = enableWrite;
    }

    public static void _checkPoll8(int address) {
        if (Settings.debugPoll && ((address & 0x50000000) == 0x00000000))
            System.out.println("Checking poll8: " + MiscUtil.toHex(address, 8));
        //+" "+MiscUtil.toHex( AddressSpaceImpl.read8( address), 2));
    }

    public static void _checkPoll16(int address) {
        // todo check this!
        if (Settings.debugPoll && ((address & 0x50000000) == 0x00000000))
            System.out.println("Checking poll16: " + MiscUtil.toHex(address, 8));
        //+" "+MiscUtil.toHex( AddressSpaceImpl.read16( address), 4));
    }

    // todo make this an N-stack
    public static void _checkPoll32(int address) {
        if (Settings.debugPoll && ((address & 0x50000000) == 0x00000000))
            System.out.println("Checking poll32: " + MiscUtil.toHex(address, 8));
        if (address == lastPoll32Address) {
            lastPoll32Count++;
            if (lastPoll32Count == 256) {
                if (logPollTrace) {
                    logPoll.trace("Poll of " + MiscUtil.toHex(address, 8));
                }

                if ((address & 0x50000000) == 0x00000000) {
                    // we assume that poll of RAM is broken out of by interrupt
                    scheduler.cpuThreadWait();
                } else {
                    Pollable p = poll32Callbacks.get(address);
                    if (p != null)
                        p.poll(address, 4);
                }
                lastPoll32Count = 0;
            }
        } else {
            lastPoll32Address = address;
            lastPoll32Count = 0;
        }
    }

    public static class Hardware {
        public static void write8(int address, int value) {
            // should be rewritten;
            assert false;
        }

        public static void write16(int address, int value) {
            // should be rewritten;
            assert false;
        }

        public static void write32(int address, int value) {
            // should be rewritten;
            assert false;
        }

        public static int read8(int address) {
            // should be rewritten;
            assert false;
            return 0;
        }

        public static int read16(int address) {
            // should be rewritten;
            assert false;
            return 0;
        }

        public static int read32(int address) {
            // should be rewritten;
            assert false;
            return 0;
        }

        public static int defaultRead32(int address) {
            if (Settings.checkHWOverlap) {
                if (null != read8Callbacks.get(address)) {
                    throw new AssertionError("32 bit hw read at " + MiscUtil.toHex(address, 8) + " overlaps 8 bit hw");
                }
                if (null != read8Callbacks.get(address + 1)) {
                    throw new AssertionError("32 bit hw read at " + MiscUtil.toHex(address, 8) + " overlaps 8 bit hw");
                }
                if (null != read8Callbacks.get(address + 2)) {
                    throw new AssertionError("32 bit hw read at " + MiscUtil.toHex(address, 8) + " overlaps 8 bit hw");
                }
                if (null != read8Callbacks.get(address + 3)) {
                    throw new AssertionError("32 bit hw read at " + MiscUtil.toHex(address, 8) + " overlaps 8 bit hw");
                }
                if (null != read16Callbacks.get(address)) {
                    throw new AssertionError("32 bit hw read at " + MiscUtil.toHex(address, 8) + " overlaps 16 bit hw");
                }
                if (null != subRead16Callbacks.get(address)) {
                    throw new AssertionError("32 bit hw read at " + MiscUtil.toHex(address, 8) + " overlaps 16 bit hw");
                }
                if (null != read16Callbacks.get(address + 2)) {
                    throw new AssertionError("32 bit hw read at " + MiscUtil.toHex(address, 8) + " overlaps 16 bit hw");
                }
                if (null != subRead16Callbacks.get(address + 2)) {
                    throw new AssertionError("32 bit hw read at " + MiscUtil.toHex(address, 8) + " overlaps 16 bit hw");
                }
            }
            int rc = hw[(address & HW_MASK) >> 2];
            if (logUnknownDebug) {
                logUnknown.debug("unknown hwRead32 " + MiscUtil.toHex(address, 8) + " " + MiscUtil.toHex(rc, 8));
            }
            return rc;
        }

        public static void defaultWrite32(int address, int value) {
            if (Settings.checkHWOverlap && null != write8Callbacks.get(address)) {
                throw new AssertionError("32 bit hw write at " + MiscUtil.toHex(address, 8) + " overlaps 8 bit hw");
            }
            if (Settings.checkHWOverlap && null != write8Callbacks.get(address + 1)) {
                throw new AssertionError("32 bit hw write at " + MiscUtil.toHex(address, 8) + " overlaps 8 bit hw");
            }
            if (Settings.checkHWOverlap && null != write8Callbacks.get(address + 2)) {
                throw new AssertionError("32 bit hw write at " + MiscUtil.toHex(address, 8) + " overlaps 8 bit hw");
            }
            if (Settings.checkHWOverlap && null != write8Callbacks.get(address + 3)) {
                throw new AssertionError("32 bit hw write at " + MiscUtil.toHex(address, 8) + " overlaps 8 bit hw");
            }
            if (Settings.checkHWOverlap && null != write16Callbacks.get(address)) {
                throw new AssertionError("32 bit hw write at " + MiscUtil.toHex(address, 8) + " overlaps 16 bit hw");
            }
            if (Settings.checkHWOverlap && null != subWrite16Callbacks.get(address)) {
                throw new AssertionError("32 bit hw write at " + MiscUtil.toHex(address, 8) + " overlaps 16 bit hw");
            }
            if (Settings.checkHWOverlap && null != write16Callbacks.get(address + 2)) {
                throw new AssertionError("32 bit hw write at " + MiscUtil.toHex(address, 8) + " overlaps 16 bit hw");
            }
            if (Settings.checkHWOverlap && null != subWrite16Callbacks.get(address + 2)) {
                throw new AssertionError("32 bit hw write at " + MiscUtil.toHex(address, 8) + " overlaps 16 bit hw");
            }
            if (logUnknownDebug) {
                logUnknown.debug("unknown hwWrite32 " + MiscUtil.toHex(address, 8) + " " + MiscUtil.toHex(value, 8));
            }
            hw[(address & HW_MASK) >> 2] = value;
        }

        public static int defaultRead16(int address) {
            if (Settings.checkHWOverlap && null != read32Callbacks.get(address & ~3)) {
                throw new AssertionError("16 bit hw read at " + MiscUtil.toHex(address, 8) + " overlaps 32 bit hw");
            }
            if (Settings.checkHWOverlap && null != read8Callbacks.get(address)) {
                throw new AssertionError("16 bit hw read at " + MiscUtil.toHex(address, 8) + " overlaps 8 bit hw");
            }
            if (Settings.checkHWOverlap && null != read8Callbacks.get(address + 1)) {
                throw new AssertionError("16 bit hw read at " + MiscUtil.toHex(address, 8) + " overlaps 8 bit hw");
            }
            int rc = hw[(address & HW_MASK) >> 2];
            if (logUnknownDebug) {
                logUnknown.debug("unknown hwRead16 " + MiscUtil.toHex(address, 8) + " " + MiscUtil.toHex(rc, 4));
            }
            if (0 != (address & 2)) {
                rc >>= 16;
            }
            return rc & 0xffff;
        }

        public static void defaultWrite16(final int address, int value) {
            if (Settings.checkHWOverlap && null != write32Callbacks.get(address & ~3)) {
                throw new AssertionError("16 bit hw write at " + MiscUtil.toHex(address, 8) + " overlaps 32 bit hw");
            }
            if (Settings.checkHWOverlap && null != write8Callbacks.get(address)) {
                throw new AssertionError("16 bit hw write at " + MiscUtil.toHex(address, 8) + " overlaps 8 bit hw");
            }
            if (Settings.checkHWOverlap && null != write8Callbacks.get(address + 1)) {
                throw new AssertionError("16 bit hw write at " + MiscUtil.toHex(address, 8) + " overlaps 8 bit hw");
            }
            int mask;
            int nvalue;
            switch (address & 2) {
                case 0:
                    mask = 0xffff0000;
                    nvalue = (value & 0xffff);
                    break;
                default:
                    mask = 0x0000ffff;
                    nvalue = value << 16;
            }
            if (logUnknownDebug) {
                logUnknown.debug("unknown hwWrite16 " + MiscUtil.toHex(address, 8) + " " + MiscUtil.toHex(value, 4));
            }
            hw[(address & HW_MASK) >> 2] = (hw[(address & HW_MASK) >> 2] & mask) | nvalue;
        }

        public static int defaultRead8(int address) {
            if (Settings.checkHWOverlap && null != read16Callbacks.get(address & ~1)) {
                throw new AssertionError("8 bit hw read at " + MiscUtil.toHex(address, 8) + " overlaps 16 bit hw");
            }
            if (Settings.checkHWOverlap && null != read32Callbacks.get(address & ~3)) {
                throw new AssertionError("8 bit hw read at " + MiscUtil.toHex(address, 8) + " overlaps 32 bit hw");
            }
            int rc = hw[(address & HW_MASK) >> 2];
            if (logUnknownDebug) {
                logUnknown.debug("unknown hwRead8 " + MiscUtil.toHex(address, 8) + " " + MiscUtil.toHex(rc, 2));
            }
            rc >>= ((address & 3) << 3);
            return rc & 0xff;
        }

        public static void defaultWrite8(final int address, int value) {
            if (Settings.checkHWOverlap && null != write16Callbacks.get(address & ~1)) {
                throw new AssertionError("8 bit hw write at " + MiscUtil.toHex(address, 8) + " overlaps 16 bit hw");
            }
            if (Settings.checkHWOverlap && null != write32Callbacks.get(address & ~3)) {
                throw new AssertionError("8 bit hw write at " + MiscUtil.toHex(address, 8) + " overlaps 32 bit hw");
            }
            int mask;
            int nvalue;
            switch (address & 3) {
                case 0:
                    mask = 0xffffff00;
                    nvalue = (value & 0xff);
                    break;
                case 1:
                    mask = 0xffff00ff;
                    nvalue = (value & 0xff) << 8;
                    break;
                case 2:
                    mask = 0xff00ffff;
                    nvalue = (value & 0xff) << 16;
                    break;
                default:
                    mask = 0x00ffffff;
                    nvalue = value << 24;
                    break;
            }
            if (logUnknownDebug) {
                logUnknown.debug("unknown hwWrite8 " + MiscUtil.toHex(address, 8) + " " + MiscUtil.toHex(value, 8));
            }
            hw[(address & HW_MASK) >> 2] = (hw[(address & HW_MASK) >> 2] & mask) | nvalue;
        }
    }

    public int[] getMainRAM() {
        return ram;
    }

    @Override
    public String getMainStaticInterfaceClassName() {
        // Defaults to name of us, so override to push static methods to subclass
        return AddressSpaceImpl.class.getName();
    }

    @Override
    public String getHardwareStaticInterfaceClassName() {
        // Defaults to name of us, so override to push static methods to subclass
        // Note we don't dereference the class since it is generated (potentially later)
        return HARDWARE_CLASS;
    }
}