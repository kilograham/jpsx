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

import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.*;
import org.apache.log4j.Logger;
import org.jpsx.api.CPUControl;
import org.jpsx.api.CPUListener;
import org.jpsx.api.InvalidConfigurationException;
import org.jpsx.api.components.core.ContinueExecutionException;
import org.jpsx.api.components.core.ImmediateBreakoutException;
import org.jpsx.api.components.core.ReturnFromExceptionException;
import org.jpsx.api.components.core.addressspace.AddressSpace;
import org.jpsx.api.components.core.addressspace.AddressSpaceListener;
import org.jpsx.api.components.core.cpu.*;
import org.jpsx.api.components.core.scheduler.Scheduler;
import org.jpsx.bootstrap.classloader.ClassModifier;
import org.jpsx.bootstrap.classloader.JPSXClassLoader;
import org.jpsx.bootstrap.connection.SimpleConnection;
import org.jpsx.bootstrap.util.CollectionsFactory;
import org.jpsx.runtime.*;
import org.jpsx.runtime.util.ClassUtil;
import org.jpsx.runtime.util.MiscUtil;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;

public final class R3000Impl extends SingletonJPSXComponent implements ClassModifier, R3000, CPUControl, InstructionRegistrar {
    private static final Logger logPrintf = Logger.getLogger("PRINTF");
    private static final Logger logCache = Logger.getLogger("Cache");
    private static final Logger log = Logger.getLogger("R3000");

    private static final String CLASS = R3000Impl.class.getName();
    private static final String UTIL_CLASS = R3000.Util.class.getName();
    private static final String DECODER_CLASS = ClassUtil.innerClassName(R3000Impl.class, "Decoder");

    private static final String INTERPRET_SIGNATURE = "(I)V";
    private static final Class[] INTERPRET_ARGS = {int.class};

    public static class Settings extends FinalComponentSettings {
        public static final boolean debugBIOS = false;
        public static final boolean traceExecutionFlow = false;
        public static final boolean skipShell = false; // todo move this elsewhere
        public static final boolean dumpRegsOnCall = false;
    }

    private static class Refs extends FinalResolvedConnectionCache {
        public static final AddressSpace addressSpace = resolve(CoreComponentConnections.ADDRESS_SPACE);
        public static final SCP scp = resolve(CoreComponentConnections.SCP);
        public static final Scheduler scheduler = resolve(CoreComponentConnections.SCHEDULER);
    }

    // todo figure out how to do this generically
    public static boolean shellHit;

    private static final int MAX_EXECUTION_DEPTH = 4;
    private static final boolean[] inCompiler = new boolean[MAX_EXECUTION_DEPTH];
    private static int executionDepth = -1;
    private static Map<String, CPUInstructionDisassembler> instructionDisassemblers = CollectionsFactory.newHashMap();

    public static final int[] regs = new int[32];
    public static int reg_lo;
    public static int reg_hi;
    public static int reg_pc; // this is the PC if in BIOS etc, or the low 28 bits if in 0/8/A RAM.
    //public static int reg_pc_upper; // value to OR with reg_pc to get true PC value.. this changes when jumping between ram banks).

    private static int delayedPCDelta = 4;
    private static int interpretedJumpAndLinkTarget = -1;
    private static int interpretedJumpAndLinkRetAddr = -1;

    private static int interpretedJumpTarget = -1;
    private static int currentPCDelta;

    // todo revisit this; execution control interface
    private static final Object cpuControlSemaphore = new Object();
    private static volatile boolean cpuCmdPending; // cpu should pause and wait for command
    private static boolean cpuReadyForCommand;
    private static int cpuCmd;
    private static final int CMD_NOP = 0;
    private static final int CMD_STEP = 1;
    private static final int CMD_RUN = 2;
    private static final int CMD_UPDATE_BREAKPOINTS = 3;

    private static CPUInstruction[] decoding;
    private static CPUInstruction[] decodingSPECIAL;
    private static CPUInstruction[] decodingREGIMM;

    private static List<Integer> breakpoints = CollectionsFactory.newArrayList();

    private static NativeCompiler compiler;
    private static CPUListener executionListeners;

    private static int breakpointAdd;
    private static int breakpointRemove;

    public R3000Impl() {
        super("JPSX Main Processor");
    }

    @Override
    public void init() {
        super.init();
        RuntimeConnections.CPU_CONTROL.set(this);
        CoreComponentConnections.R3000.set(this);
        JPSXClassLoader.registerClassModifier(DECODER_CLASS, this);
        JPSXMachine machine = RuntimeConnections.MACHINE.resolve();
        machine.addInitializer(JPSXMachine.PRIORITY_ADD_INSTRUCTIONS, new Runnable() {
            public void run() {
                addInstructions();
            }
        });
        CoreComponentConnections.ADDRESS_SPACE_LISTENERS.add(new AddressSpaceListener() {
            public void cacheCleared() {
                R3000Impl.cacheCleared();
            }
        });
    }

    protected void addInstructions() {
        i_invalid = new CPUInstruction("invalid", R3000Impl.class, 0, CPUInstruction.FLAG_MAY_SIGNAL_EXCEPTION | CPUInstruction.FLAG_INVALID);
        decoding = new CPUInstruction[64];
        for (int i = 0; i < 64; i++) {
            decoding[i] = i_invalid;
        }
        decodingSPECIAL = new CPUInstruction[64];
        for (int i = 0; i < 64; i++) {
            decodingSPECIAL[i] = i_invalid;
        }
        decodingREGIMM = new CPUInstruction[32];
        for (int i = 0; i < 32; i++) {
            decodingREGIMM[i] = i_invalid;
        }
        // create connection wrapper so we can close it
        SimpleConnection<InstructionRegistrar> connection = SimpleConnection.create("InstructionRegistrar", InstructionRegistrar.class);
        connection.set(this);
        CoreComponentConnections.INSTRUCTION_PROVIDERS.resolve().addInstructions(connection.resolve());
        CoreComponentConnections.INSTRUCTION_PROVIDERS.close();
        connection.close();
    }

//    public void registerAddresses()
//	{
//		// todo move this somewhere else
//        AddressSpaceImpl.registerWrite8Callback( 0x1f802041, CLASS, "bootStatus");
    //   }

    private static CPUInstruction i_invalid;

    public CPUInstruction getInvalidInstruction() {
        return i_invalid;
    }

    @Override
    public void resolveConnections() {
        super.resolveConnections();
        compiler = CoreComponentConnections.NATIVE_COMPILER.peek();
    }

    public static void bootStatus(int address, int value) {
        //System.out.println( "BIOS: BOOT STATUS " + value );
    }

    public CPUInstruction decodeInstruction(int ci) {
        int index = ci >> 26;
        CPUInstruction rc;
        switch (index) {
            case 0:
                rc = decodingSPECIAL[ci & 0x3f];
                break;
            case 1:
                rc = decodingREGIMM[Util.bits_rt(ci)];
                break;
            default:
                rc = decoding[index & 0x3f];
                break;
        }
        return rc.subDecode(ci);
    }

    public void setInstructionDisassembler(String name, CPUInstructionDisassembler disassembler) {
        instructionDisassemblers.put(name, disassembler);
    }

    public void setInstruction(int index, CPUInstruction inst) {
        decoding[index] = inst;
    }

    public void setSPECIALInstruction(int index, CPUInstruction inst) {
        decodingSPECIAL[index] = inst;
    }

    public void setREGIMMInstruction(int index, CPUInstruction inst) {
        decodingREGIMM[index] = inst;
    }

    private static void checkInstruction(CPUInstruction inst) {
        // todo decide whether to put this back; needs a flag for those instructions with subdecode
        try {
            java.lang.reflect.Method method = inst.getInterpreterClass().getDeclaredMethod(inst.getInterpretMethodName(), INTERPRET_ARGS);
            if (!Modifier.isStatic(method.getModifiers())) {
                throw new InvalidConfigurationException("Interpreter method " + inst.getInterpretMethodName() + " on class " + inst.getInterpreterClass().getName() + " is not static");
            }
        } catch (Exception e) {
            throw new InvalidConfigurationException("Missing correct interpreter method " + inst.getInterpretMethodName() + " on class " + inst.getInterpreterClass().getName(), e);
        }
    }

    /**
     * The methods in this class are replaced by the morph method
     * above at runtime
     */
    private static class Decoder {
        public static void invokeSpecial(final int ci) {
            // this method is runtime generated
            throw new EmulationException("should have been generated");
        }

        public static void invokeRegImm(final int ci) {
            // this method is runtime generated
            throw new EmulationException("should have been generated");
        }

        public static void invoke(final int ci) {
            // this method is runtime generated
            throw new EmulationException("should have been generated");
        }
    }

    private static Thread executionThread;

    public boolean isExecutionThread() {
        return Thread.currentThread() == executionThread;
    }

    public void begin() {
        executionListeners = CoreComponentConnections.CPU_LISTENERS.resolve();
        executionThread = new R3000Thread();
        cpuCmdPending = true; // thread should configure and wait for a cmd
        executionThread.start();
    }

    public static void shutdown() {
        //pause();
        //m_cpuLaunch = false;
        //synchronized (m_cpuControlSemaphore) {
        //    m_cpuControlSemaphore.notify();
        //}
    }

    /**
     * If not called from the execution thread, then this blocks until the execution is paused;
     * otherwise it
     */
    public void pause() {
        sendCmd(CMD_NOP);
    }

    /**
     * Sends a command to the execution thread. Generally this is synchronous, and waits for the command to be acknowledged;
     * however if called from the execution thread itself, then this method will throw an exception to return to safely
     * return control to the command handling code. PC will be at the currently executing instruction, but side effects
     * may already have happened.
     *
     * @param cmd
     */
    private static void sendCmd(int cmd) {
        //System.out.println("main: sendCmd "+cmd);
        synchronized (cpuControlSemaphore) {
            cpuCmdPending = true;
            if (Thread.currentThread() != executionThread) {
                // normally fine just to have the execution thread make itself ready for a command as soon as possible
                CoreComponentConnections.R3000.resolve().requestBreakout();
                if (!cpuReadyForCommand) {
                    //System.out.println("main: waiting for cpu to be ready");
                    try {
                        cpuControlSemaphore.wait();
                    } catch (InterruptedException e) {
                    }
                }
                cpuCmd = cmd;
                //System.out.println("main: informing cpu of cmd");
                cpuControlSemaphore.notify();
                //System.out.println("main: waiting for cpu to acknowledge cmd");
                try {
                    cpuControlSemaphore.wait();
                } catch (InterruptedException e) {
                }
            } else {
                // by definition the execution thread is not ready for a command
                assert !cpuReadyForCommand;
                cpuCmd = cmd;
                // This won't return, as we are the execution thread, and this must throw
                // an exception to achieve its goals
                CoreComponentConnections.R3000.resolve().immediateBreakout();
            }
        }
    }

    public void step() {
        sendCmd(CMD_STEP);
        // wait for step to complete by sending another cmd
        sendCmd(CMD_NOP);
    }

    public void go() {
        sendCmd(CMD_RUN);
    }

    private static void cpuWaitForCmd() {
        executionListeners.cpuPaused();
        synchronized (cpuControlSemaphore) {
            boolean done = false;
            while (!done) {
                //System.out.println("cpu: signalling read for cmd");
                // notify that we're ready for a command
                cpuReadyForCommand = true;
                cpuControlSemaphore.notify();

                //System.out.println("cpu: waiting for cmd");
                try {
                    cpuControlSemaphore.wait();
                } catch (InterruptedException e) {
                }
                switch (cpuCmd) {
                    case CMD_NOP:
                        cpuCmdPending = false;
                        break;
                    case CMD_RUN:
                        done = true;
                        cpuCmdPending = false;
                        executionListeners.cpuResumed();
                        break;
                    case CMD_STEP:
                        done = true;
                        cpuCmdPending = true;
                        break;
                    case CMD_UPDATE_BREAKPOINTS:
                        updateBreakpoints();
                        cpuCmdPending = false;
                        break;
                }
                cpuReadyForCommand = false;
                cpuControlSemaphore.notify();
                //System.out.println("cpu: acknowledging cmd");
            }
        }
    }

    // todo revisit this
    private class R3000Thread extends Thread {
        public R3000Thread() {
            super("Execution thread");
            synchronized (cpuControlSemaphore) {
                cpuReadyForCommand = false;
            }
        }

        public void run() {
            log.info("Processor thread starts");
            executeFromPC();
            log.info("Processor thread ends");
        }
    }

    public final void executeFromPC() {
        executionDepth++;
        assert executionDepth < MAX_EXECUTION_DEPTH;

        delayedPCDelta = currentPCDelta = 4;

        while (true) {
            try {
                interpreterLoop();
            } catch (ContinueExecutionException e) {
                if (inCompiler[executionDepth]) {
                    compiler.exceptionInCompiler(e);
                    inCompiler[executionDepth] = false;
                }
                if (e.skipCurrentInstruction()) {
                    reg_pc += 4;
                }
                // System.out.println("... broke out to execute loop");
                // continue from where we were!
                delayedPCDelta = currentPCDelta = 4;
            } catch (ReturnFromExceptionException rfe) {
                if (inCompiler[executionDepth]) {
                    compiler.exceptionInCompiler(rfe);
                    inCompiler[executionDepth] = false;
                }
                //System.out.println("... returned from exception");
                assert delayedPCDelta == 4 : "rfe delayedPCDelta should be 4";
                currentPCDelta = 4;
                // on return from exception we exit loop
                break;
            } catch (Throwable t) {
                boolean ok = false;
                if (inCompiler[executionDepth]) {
                    ok = compiler.exceptionInCompiler(t);
                    inCompiler[executionDepth] = false;
                }
                if (!ok) {
                    t.printStackTrace();
                    System.out.println("Execution paused due to Java exception!");
                    cpuWaitForCmd();
                }
            }
        }
        executionDepth--;
    }

    // should ready this only
    public static boolean breakout;

    public void requestBreakout() {
        breakout = true;
        if (compiler != null) {
            compiler.interrupt();
        }
    }

    private void handleInterpreterBreakout() {
        assert isExecutionThread();
        // all breakouts should cause another breakout if they wish to have another one
        breakout = false;
        if (Refs.scp.shouldInterrupt()) {
            restoreInterpreterState();
            Refs.scp.signalInterruptException();
        }
    }

    public final void compilerInterrupted() {
        handleInterpreterBreakout();
        if (cpuCmdPending) {
            // need to return to CPU loop
            immediateBreakout();
        }
    }

    @Override
    public final void immediateBreakout() {
        assert isExecutionThread();
        restoreInterpreterState();
        throw new ImmediateBreakoutException();
    }

    public final void interpreterLoop() {
        if (Settings.traceExecutionFlow) {
            log.trace("Enter interpreter loop at " + MiscUtil.toHex(reg_pc, 8));
        }
        do {
            boolean checkBreakout = (currentPCDelta != 4) && (delayedPCDelta == 4); // only check interrupt on non linear flow

            currentPCDelta = delayedPCDelta;
            delayedPCDelta = 4;

            boolean shouldWait = cpuCmdPending;

            // not using for each since we want to avoid iterator allocation
            for (int i = 0; i < breakpoints.size(); i++) {
                if (reg_pc == breakpoints.get(i)) {
                    shouldWait = true;
                    break;
                }
            }

            if (shouldWait) {
                cpuWaitForCmd();
            }


            if (checkBreakout && breakout) {
                // not sure if this is the right place for this check; basically we don't want to take interrupts while
                // we're stepping
                if (!cpuCmdPending) {
                    handleInterpreterBreakout();
                }
            }

            if (reg_pc == interpretedJumpAndLinkTarget) {
                if (Settings.skipShell) {
                    if (!shellHit && reg_pc == 0x80030000) {
                        shellHit = true;
                        reg_pc = regs[31];
                    }
                }
                if (Settings.dumpRegsOnCall && shouldDumpRegs(reg_pc)) {
                    dumpRegs();
                }
                // interpreted jalr/jal got us to this PC
                if (false && 0 != Refs.scp.currentExceptionType()) {
                    log.trace("Call " + MiscUtil.toHex(reg_pc, 8) + " from " + MiscUtil.toHex(regs[31] - 8, 8));
                }

                interpretedJumpAndLinkTarget = -1;

                if (!shouldWait && compiler != null) {

                    if (Settings.traceExecutionFlow) {
                        log.trace("calling compiler for " + MiscUtil.toHex(reg_pc, 8));
                    }

                    inCompiler[executionDepth] = true;
                    if (compiler.jumpAndLink(reg_pc, interpretedJumpAndLinkRetAddr)) {
                        // we expect the registers are already restored
                        inCompiler[executionDepth] = false;

                        if (Settings.traceExecutionFlow) {
                            log.trace("returning from level0 unit");
                        }

                        // on returning from a call, it must have been a JR, so we can't be in a delay slot
                        delayedPCDelta = 4;
                        continue;
                    }
                }
                if (Settings.traceExecutionFlow) {
                    System.out.println("staying in interpreter for " + MiscUtil.toHex(reg_pc, 8));
                }
                // todo move this
                if (Settings.debugBIOS) {
                    // todo, register functions s.t. the compiler can insert these also!
                    if (reg_pc == 0x00001b44) {
                        debugDeliverEvent();
                    }

                    if (reg_pc == 0xa0) {
                        debugA0();
                    }

                    if (reg_pc == 0xb0) {
                        debugB0();
                    }

                    if (reg_pc == 0xc0) {
                        debugC0();
                    }

                }

                // todo move this
                if (reg_pc == 0xbfc018e0) {
                    debugPrintf();
                }
            }

            if (reg_pc == interpretedJumpTarget && reg_pc == regs[31]) {
                if (false && 0 != Refs.scp.currentExceptionType()) {
                    System.out.println("return to " + MiscUtil.toHex(regs[31], 8));
                }
                if (Settings.traceExecutionFlow) {
                    System.out.println("r31 return to " + MiscUtil.toHex(reg_pc, 8));
                }
            }

            // the virtual invokation here isn't much of a problem compared to the speed of the interpreter
            int ci = Refs.addressSpace.internalRead32(reg_pc);

            Decoder.invoke(ci);

            assert regs[0] == 0 : "instruction changed r0";

            reg_pc += currentPCDelta;
        } while (true);
    }

    public static boolean shouldDumpRegs(int addr) {
        if (Settings.dumpRegsOnCall) {
            //return addr==0x80072c1c || addr==0x800729ac || addr==0x8001a504 || addr==0x80019988 || addr==0x800198d8 || addr==0x8003c05c || addr==0x8003a62c;
        }
        return false;
    }

    public static void tempHackForException() {
        currentPCDelta = 0;
        delayedPCDelta = 4;
    }

    public void addBreakpoint(int address) {
        // todo fix this shit
        breakpointAdd = address;
        breakpointRemove = -1;
        sendCmd(CMD_UPDATE_BREAKPOINTS);
    }


    public void removeBreakpoint(int address) {
        // todo fix this shit
        breakpointAdd = -1;
        breakpointRemove = address;
        sendCmd(CMD_UPDATE_BREAKPOINTS);
    }

    private static void updateBreakpoints() {
        if (breakpointAdd != -1) {
            if (!breakpoints.contains(breakpointAdd)) {
                breakpoints.add(0, breakpointAdd);
                if (compiler != null) {
                    compiler.addBreakpoint(breakpointAdd);
                }
            }
            breakpointAdd = -1;
        }
        if (breakpointRemove != -1) {
            if (breakpointRemove >= 0 && breakpointRemove < breakpoints.size()) {
                int address = breakpoints.remove(breakpointRemove);
                if (compiler != null) {
                    compiler.removeBreakpoint(address);
                }
            } else {
                int index = breakpoints.indexOf(breakpointRemove);
                if (index != -1) {
                    breakpoints.remove(index);
                    if (compiler != null) {
                        compiler.removeBreakpoint(breakpointRemove);
                    }
                }
            }
            breakpointRemove = -1;
        }
    }

    public int[] getBreakpoints() {
        int[] rc = new int[breakpoints.size()];
        for (int i = 0; i < rc.length; i++) {
            rc[i] = breakpoints.get(i);
        }
        return rc;
    }

    // move this elswehere
    private static int getParam(int index) {
        if (index < 4) {
            return regs[4 + index];
        }
        return Refs.addressSpace.read32(regs[R_SP] + 0x10 + 4 * (index - 4));
    }

    // move this elsewhere
    public static void debugPrintf() {
        if (logPrintf.isInfoEnabled()) {
            String fmt = readString(regs[4]);
            StringBuilder s = new StringBuilder();

            int param = 1;
            int perc = fmt.indexOf('%');
            while (perc != -1) {
                s.append(fmt.substring(0, perc));

                boolean leadingZero = false;
                boolean firstDigit = false;
                int digits = 0;

                outer:
                do {
                    int c = fmt.charAt(++perc);
                    switch (c) {
                        case's':
                            s.append(readString(getParam(param++)));
                            break outer;
                        case'x':
                            if (digits != 0) {
                                if (leadingZero) {
                                    s.append(MiscUtil.toHex(getParam(param++), digits));
                                } else {
                                    s.append(MiscUtil.toHex(getParam(param++), digits));
                                }
                            } else {
                                s.append(Integer.toHexString(getParam(param++)));
                            }
                            break outer;
                        case'd':
                            s.append(getParam(param++));
                            break outer;
                        case'.':
                        case'-':
                        case'h':
                            break;
                        default:
                            if (c >= '0' && c <= '9') {
                                if (firstDigit) {
                                    leadingZero = c == '0';
                                    firstDigit = false;
                                }
                                digits = digits * 10 + (int) (c - '0');
                            } else {
                                break outer;
                            }
                            break;
                    }
                } while (true);

                fmt = fmt.substring(perc + 1);
                perc = fmt.indexOf('%');
            }
            s.append(fmt);
            String str = s.toString();
            while (str.endsWith("\n") || str.endsWith("\r")) {
                str = str.substring(0, str.length() - 1);
            }
            logPrintf.info(str);
        }
    }

    private static String readString(int address) {
        String rc = "";
        do {
            int val = Refs.addressSpace.read8(address++);
            if (val != 0) {
                rc += (char) val;
            } else {
                break;
            }
        } while (true);
        return rc;
    }

    public static void debugA0() {
        switch (regs[9]) {
            case 0x15:
            case 0x16:
            case 0x17:
            case 0x18:
            case 0x19:
            case 0x1a:
            case 0x1b:
                // boring c library
                break;
            case 0x3f: // printf
                // handled elsewhere
                break;
            default:
                System.out.println(MiscUtil.toHex(regs[31], 8) + " BIOS: A0 " + MiscUtil.toHex(regs[9], 2));
        }
    }

    public static void debugB0() {
        switch (regs[9]) {
            case 0x3d: // putchar
                // part of printf (too verbose)
                break;
            default:
                System.out.println(MiscUtil.toHex(regs[31], 8) + " BIOS: B0 " + MiscUtil.toHex(regs[9], 2));
        }
    }

    public static void debugC0() {
        switch (regs[9]) {
            default:
                System.out.println(MiscUtil.toHex(regs[31], 8) + " BIOS: C0 " + MiscUtil.toHex(regs[9], 2));
        }
    }

    public static void debugDeliverEvent() {
        System.out.println(MiscUtil.toHex(regs[31], 8) + "BIOS: DeliverEvent " + MiscUtil.toHex(regs[4], 8) + ", " + MiscUtil.toHex(regs[5], 8) + ", " + MiscUtil.toHex(regs[6], 8));
    }

    private static int totalBreakouts;

    /**
     * called when the instruction cache is flushed
     */
    private static void cacheCleared() {
        // remove any RAM based code units
/*        for( Iterator i = codeUnits.keySet().iterator(); i.hasNext(); ) {
            Integer a = (Integer)i.next();
            int address = a.intValue();
            if (!AddressSpaceImpl.isBIOS( address)) {
                i.remove();
            }
        }    */
        totalBreakouts++;
        if (compiler != null) {
            compiler.clearCache();
            if (inCompiler[executionDepth]) {
                ContinueExecutionException e = ContinueExecutionException.SKIP_CURRENT;
                if (logCache.isDebugEnabled()) {
                    logCache.debug("cacheCleared in compiler depth=" + executionDepth + "; throwing ContinueExecutionException...");
                }
                throw e;
            } else {
                if (logCache.isDebugEnabled()) {
                    logCache.debug("cacheCleared in interpreter depth=" + executionDepth + "; continuing interpreting...");
                }
            }
        }
    }

    public ClassGen modifyClass(String classname, ClassGen cgen) {
        ConstantPoolGen cp = cgen.getConstantPool();

        // regular decode

        Method m = cgen.containsMethod("invoke", "(I)V");
        MethodGen mg = JPSXClassLoader.emptyMethod(cgen, m);

        InstructionList il = mg.getInstructionList();
        il.append(new ILOAD(0));
        il.append(new BIPUSH((byte) 26));
        il.append(new ISHR());

        InstructionHandle[] handles = new InstructionHandle[64];
        int[] matches = new int[64];
        for (int i = -32; i < 32; i++) {
            int j = i;
            if (j < 0) j = j + 64;
            matches[i + 32] = i;
            CPUInstruction inst = decoding[j];
            handles[i + 32] = il.append(new ILOAD(0));
            switch (j) {
                case 0:
                    il.append(new INVOKESTATIC(cp.addMethodref(DECODER_CLASS, "invokeSpecial", "(I)V")));
                    break;
                case 1:
                    il.append(new INVOKESTATIC(cp.addMethodref(DECODER_CLASS, "invokeRegImm", "(I)V")));
                    break;
                default:
                    checkInstruction(inst);
                    il.append(new INVOKESTATIC(cp.addMethodref(inst.getInterpreterClass().getName(), inst.getInterpretMethodName(), INTERPRET_SIGNATURE)));
                    break;
            }
            il.append(new RETURN());
        }
        InstructionHandle def = il.append(new ILOAD(0));
        il.append(new INVOKESTATIC(cp.addMethodref(CLASS, "interpret_invalid", INTERPRET_SIGNATURE)));
        il.append(new RETURN());
        il.insert(handles[0], new TABLESWITCH(matches, handles, def));
        mg.setMaxLocals();
        mg.setMaxStack();
        cgen.replaceMethod(m, mg.getMethod());
        il.dispose();

        // special decode

        m = cgen.containsMethod("invokeSpecial", "(I)V");
        mg = JPSXClassLoader.emptyMethod(cgen, m);

        il = mg.getInstructionList();
        il.append(new ILOAD(0));
        il.append(new BIPUSH((byte) 63));
        il.append(new IAND());

        handles = new InstructionHandle[64];
        matches = new int[64];
        for (int i = 0; i < 64; i++) {
            matches[i] = i;
            CPUInstruction inst = decodingSPECIAL[i];
            handles[i] = il.append(new ILOAD(0));
            checkInstruction(inst);
            il.append(new INVOKESTATIC(cp.addMethodref(inst.getInterpreterClass().getName(), inst.getInterpretMethodName(), INTERPRET_SIGNATURE)));
            il.append(new RETURN());
        }
        def = il.append(new ILOAD(0));
        il.append(new INVOKESTATIC(cp.addMethodref(CLASS, "interpret_invalid", INTERPRET_SIGNATURE)));
        il.append(new RETURN());
        il.insert(handles[0], new TABLESWITCH(matches, handles, def));
        mg.setMaxLocals();
        mg.setMaxStack();
        cgen.replaceMethod(m, mg.getMethod());
        il.dispose();

        // regimm decode

        m = cgen.containsMethod("invokeRegImm", INTERPRET_SIGNATURE);
        mg = JPSXClassLoader.emptyMethod(cgen, m);

        il = mg.getInstructionList();
        il.append(new ILOAD(0));
        il.append(new INVOKESTATIC(cp.addMethodref(UTIL_CLASS, "bits_rt", "(I)I")));

        handles = new InstructionHandle[32];
        matches = new int[32];
        for (int i = 0; i < 32; i++) {
            matches[i] = i;
            CPUInstruction inst = decodingREGIMM[i];
            handles[i] = il.append(new ILOAD(0));
            checkInstruction(inst);
            il.append(new INVOKESTATIC(cp.addMethodref(inst.getInterpreterClass().getName(), inst.getInterpretMethodName(), INTERPRET_SIGNATURE)));
            il.append(new RETURN());
        }
        def = il.append(new ILOAD(0));
        il.append(new INVOKESTATIC(cp.addMethodref(CLASS, "interpret_invalid", INTERPRET_SIGNATURE)));
        il.append(new RETURN());
        il.insert(handles[0], new TABLESWITCH(matches, handles, def));
        mg.setMaxLocals();
        mg.setMaxStack();
        cgen.replaceMethod(m, mg.getMethod());
        il.dispose();
        return cgen;
    }

    public void restoreInterpreterState() {
        if (inCompiler[executionDepth]) {
            compiler.restoreInterpreterState();
        }
    }

    // todo threading?
    public final int getReg(int reg) {
        if (inCompiler[executionDepth]) {
            return compiler.getReg(reg);
        } else {
            return regs[reg];
        }
    }

    public final void setReg(int reg, int val) {
        if (inCompiler[executionDepth]) {
            compiler.setReg(reg, val);
        } else {
            regs[reg] = val;
        }
    }

    // todo recheck this; this is a hack to avoid problems with certain versions of HotSpot (perhaps 1.4?)
    private static boolean breakHotspot;

    public static final void interpret_invalid(final int ci) {
        breakHotspot = false;
        Refs.scp.signalReservedInstructionException();
    }

    public String disassemble(int address, int ci) {
        CPUInstruction inst = decodeInstruction(ci);
        CPUInstructionDisassembler disassembler = instructionDisassemblers.get(inst.getName());
        if (disassembler != null)
            return disassembler.disassemble(inst, address, ci);
        else
            return "(" + inst.getName() + ")";
    }

    /**
     * Method should be called from any code which
     * is returning from blah
     */
    public static void safeReturn() {
    }

    // ------------- utility functions

    public static void dumpRegs() {
        String s = MiscUtil.toHex(reg_pc, 8);
        for (int i = 0; i < 32; i++) {
            s += " " + MiscUtil.toHex(regs[i], 8);
        }
        System.out.println(s);
    }

    /**
     * Control flow instruction for use by R3000 instruction implementation
     *
     * @param relativeToDelay target address relative to the delay slot
     */
    public void interpreterBranch(int relativeToDelay) {
        delayedPCDelta = relativeToDelay;
    }

    /**
     * Control flow instruction for use by R3000 instruction implementation
     *
     * @param relativeToDelay target address relative to the delay slot
     * @param target          the target address
     */
    public void interpreterJump(int relativeToDelay, int target) {
        delayedPCDelta = relativeToDelay;
        interpretedJumpTarget = target;
    }

    /**
     * Control flow instruction for use by R3000 instruction implementation
     *
     * @param relativeToDelay target address relative to the delay slot
     * @param target          the target address
     * @param retAddr         the return address
     */
    public void interpreterJumpAndLink(int relativeToDelay, int target, int retAddr) {
        delayedPCDelta = relativeToDelay;
        interpretedJumpAndLinkTarget = target;
        interpretedJumpAndLinkRetAddr = retAddr;
    }


    public int[] getInterpreterRegs() {
        return regs;
    }

    public final int getPC() {
        return reg_pc;
    }

    public final int getLO() {
        return reg_lo;
    }

    public final int getHI() {
        return reg_hi;
    }

    public final void setPC(int pc) {
        reg_pc = pc;
    }

    public final void setLO(int lo) {
        reg_lo = lo;
    }

    public final void setHI(int hi) {
        reg_hi = hi;
    }
}
