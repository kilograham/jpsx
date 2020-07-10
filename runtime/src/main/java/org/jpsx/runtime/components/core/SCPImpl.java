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

import org.apache.log4j.Logger;
import org.jpsx.api.components.core.ContinueExecutionException;
import org.jpsx.api.components.core.ReturnFromExceptionException;
import org.jpsx.api.components.core.addressspace.AddressSpace;
import org.jpsx.api.components.core.cpu.*;
import org.jpsx.api.components.core.irq.IRQController;
import org.jpsx.api.components.core.scheduler.Quartz;
import org.jpsx.api.components.core.scheduler.Scheduler;
import org.jpsx.runtime.SingletonJPSXComponent;
import org.jpsx.runtime.util.MiscUtil;

public class SCPImpl extends SingletonJPSXComponent implements SCP, InstructionProvider {
    private static final Logger log = Logger.getLogger("SCP");

    private static long debugNextSecond;
    private static int vsyncsTaken;

    private static final boolean debugSCP = log.isDebugEnabled();

    private static final int STATUS_CU_3 = 0x80000000;
    private static final int STATUS_CU_2 = 0x40000000;
    private static final int STATUS_CU_1 = 0x20000000;
    private static final int STATUS_CU_0 = 0x10000000;
    private static final int STATUS_RE = 0x02000000; // is this supported?
    private static final int STATUS_BEV = 0x00400000;
    private static final int STATUS_TS = 0x00200000;
    private static final int STATUS_PE = 0x00100000; // parity
    private static final int STATUS_CM = 0x00080000;
    private static final int STATUS_PZ = 0x00080000;
    private static final int STATUS_SWC = 0x00020000;
    private static final int STATUS_ISC = 0x00010000; // cache
    private static final int STATUS_IM7 = 0x00008000;
    private static final int STATUS_IM6 = 0x00004000;
    private static final int STATUS_IM5 = 0x00002000;
    private static final int STATUS_IM4 = 0x00001000;
    private static final int STATUS_IM3 = 0x00000800;
    private static final int STATUS_IM2 = 0x00000400;
    private static final int STATUS_IM1 = 0x00000200;
    private static final int STATUS_IM0 = 0x00000100;
    private static final int STATUS_KUO = 0x00000020;
    private static final int STATUS_IEO = 0x00000010;
    private static final int STATUS_KUP = 0x00000008;
    private static final int STATUS_IEP = 0x00000004;
    private static final int STATUS_KUC = 0x00000002;
    private static final int STATUS_IEC = 0x00000001;

    private static final int STATUS_IMS = STATUS_IM7 | STATUS_IM6 | STATUS_IM5 | STATUS_IM4 | STATUS_IM3 | STATUS_IM2 | STATUS_IM1 | STATUS_IM0;

    private static final int STATUS_W = STATUS_CU_3 | STATUS_CU_2 | STATUS_CU_1 | STATUS_CU_0 | STATUS_RE | STATUS_BEV |
            STATUS_TS | STATUS_PE | STATUS_CM | STATUS_PZ | STATUS_SWC | STATUS_ISC | STATUS_IMS |
            STATUS_KUO | STATUS_IEO | STATUS_KUP | STATUS_IEP | STATUS_KUC | STATUS_IEC;

    private static final int CAUSE_BD = 0x80000000;
    private static final int CAUSE_CE = 0x30000000;
    private static final int CAUSE_CE_SHIFT = 28;
    private static final int CAUSE_IP7 = 0x00008000;
    private static final int CAUSE_IP6 = 0x00004000;
    private static final int CAUSE_IP5 = 0x00002000;
    private static final int CAUSE_IP4 = 0x00001000;
    private static final int CAUSE_IP3 = 0x00000800;
    private static final int CAUSE_IP2 = 0x00000400;
    private static final int CAUSE_IP1 = 0x00000200;
    private static final int CAUSE_IP0 = 0x00000100;

    private static final int CAUSE_CODE = 0x0000003c;
    private static final int CAUSE_CODE_SHIFT = 2;

    private static final int CAUSE_W = CAUSE_IP0 | CAUSE_IP1;

    private static final int REG_INDEX = 0;
    private static final int REG_RAND = 1;
    private static final int REG_TLBLO = 2;
    private static final int REG_BPC = 3;
    private static final int REG_CTXT = 4;
    private static final int REG_BPA = 5;
    private static final int REG_PIDMASK = 6;
    private static final int REG_DGIC = 7;
    private static final int REG_BADVADDR = 8;
    private static final int REG_BDAM = 9;
    private static final int REG_TLBHI = 10;
    private static final int REG_BPCM = 11;
    private static final int REG_STATUS = 12;
    private static final int REG_CAUSE = 13;
    private static final int REG_EPC = 14;
    private static final int REG_PRID = 15; // ID - unimplemented

    private static int status = 0;
    private static int cause = 0;

    private static int errorEPC = 0;
    private static int EPC = 0;

    private static boolean causeIV;
    private static boolean causeBD;
    private static int causeCE;
    private static int causeIPS;

    private static AddressSpace addressSpace;
    private static R3000 r3000;
    private static Quartz quartz;
    private static Scheduler scheduler;
    private static IRQController irqController;
    private static int[] interpreterRegs;

    public SCPImpl() {
        super("JPSX System Control Processor");
    }

    public void begin() {
        signalResetException();
    }

    public void resolveConnections() {
        super.resolveConnections();
        addressSpace = CoreComponentConnections.ADDRESS_SPACE.resolve();
        r3000 = CoreComponentConnections.R3000.resolve();
        interpreterRegs = r3000.getInterpreterRegs();
        irqController = CoreComponentConnections.IRQ_CONTROLLER.resolve();
        quartz = CoreComponentConnections.QUARTZ.resolve();
        scheduler = CoreComponentConnections.SCHEDULER.resolve();
    }

    public void addInstructions(InstructionRegistrar registrar) {
        log.info("Adding COP0 instructions...");
        final CPUInstruction i_eret = new CPUInstruction("eret", SCPImpl.class, 0, CPUInstruction.FLAG_MAY_RESTORE_INTERPRETER_STATE);
        final CPUInstruction i_mfc0 = new CPUInstruction("mfc0", SCPImpl.class, 0, CPUInstruction.FLAG_WRITES_RT);
        // note this needs references_pc because it can cause a return from exception exception
        final CPUInstruction i_mtc0 = new CPUInstruction("mtc0", SCPImpl.class, 0, CPUInstruction.FLAG_READS_RT | CPUInstruction.FLAG_REFERENCES_PC);

        CPUInstruction i_cop0 = new CPUInstruction("cop0", SCPImpl.class, 0, 0) {
            public CPUInstruction subDecode(int ci) {
                switch (R3000.Util.bits_rs(ci)) {
                    case 0:
                        return i_mfc0;
                    case 4:
                        return i_mtc0;
                    case 16:
                        return i_eret;
                }
                return r3000.getInvalidInstruction();
            }
        };
        registrar.setInstruction(16, i_cop0);
    }

    public static void interpret_cop0(final int ci) {
        switch (R3000.Util.bits_rs(ci)) {
            case 0:
                interpret_mfc0(ci);
                return;
            case 4:
                interpret_mtc0(ci);
                return;
            case 16:
                interpret_eret(ci);
                return;
        }
        _signalReservedInstructionException();
    }

    public static void interpret_mtc0(final int ci) {
        int rt = R3000.Util.bits_rt(ci);
        int rd = R3000.Util.bits_rd(ci);
        int sel = ci & 0x7;
        int value = interpreterRegs[rt];

        writeRegister(rd, sel, value);
    }

    public static void interpret_mfc0(final int ci) {
        int rt = R3000.Util.bits_rt(ci);
        int rd = R3000.Util.bits_rd(ci);
        int sel = ci & 0x7;
        int value = readRegister(rd, sel);
        if (rt != 0)
            interpreterRegs[rt] = value;
    }

    private static void writeRegister(int reg, int sel, int value) {
        switch ((sel << 16) + reg) {
            case REG_STATUS:
                status = (status & ~STATUS_W) | (value & STATUS_W);
                addressSpace.enableMemoryWrite((status & STATUS_ISC) == 0);
                break;
            case REG_CAUSE:
                cause = (cause & ~CAUSE_W) | (value & CAUSE_W);
                break;
            default:
                if (log.isDebugEnabled())
                    log.debug("SCP: write " + reg + ":" + sel + " " + MiscUtil.toHex(value, 8));
        }
    }

    private static int readRegister(int reg, int sel) {
        int rc = 0;
        switch ((sel << 16) + reg) {
            case REG_STATUS:
                rc = status;
                break;
            case REG_CAUSE:
                rc = cause;
                break;
            case REG_EPC:
                rc = EPC;
                break;
            default:
                if (log.isDebugEnabled()) {
                    log.debug("SCP: read " + reg + ":" + sel);
                }
        }
        return rc;
    }

    public static void interpret_eret(final int ci) {
        // fudge, because PSX BIOS seems to do
        //
        // jr new_pc
        // eret
        //
        // which is invalid according to r3000 docs; still we can check
        // for a JR.
        //
        // this is a bit of a hack, it doesn't take care of branching directly
        // to the ERET, however this would only be bad if the ERET was
        // in a delay slot and that was branched to, which seems unlikely - all
        // in all I'm very unhappy about this; perhaps revisit when we
        // support BD correctly!

        // make sure the regs are written back in case we came from the compiler
        r3000.restoreInterpreterState();
        int prevci = addressSpace.internalRead32(r3000.getPC() - 4);
        if ((prevci & 0xfc1fffff) == 8) {
            EPC = interpreterRegs[R3000.Util.bits_rs(prevci)];
        } else {
            throw new IllegalStateException("Expected ERET preceeded by JR");
        }
        returnFromException();
    }

    public void init() {
        super.init();
        status = 0;
        cause = 0;
        CoreComponentConnections.SCP.set(this);
        CoreComponentConnections.INSTRUCTION_PROVIDERS.add(this);
    }

    public void signalResetException() {
        status = STATUS_BEV;
        r3000.setPC(0xbfc00000);
    }

    private static boolean getStatusBEV() {
        return 0 != (status & STATUS_BEV);
    }

    private static void setCauseCE(int val) {
        cause = (cause & ~CAUSE_CE) | ((val << CAUSE_CE_SHIFT) & CAUSE_CE);
    }

    private static void setCauseExcCode(int val) {
        cause = (cause & ~CAUSE_CODE) | ((val << CAUSE_CODE_SHIFT) & CAUSE_CODE);
    }

    private static int getCauseExcCode() {
        return (cause & CAUSE_CODE) >> CAUSE_CODE_SHIFT;
    }

    // todo fix these
    private static int getCauseIPS() {
        return 0;
    }

    private static int getStatusIMS() {
        return 0;
    }

    public void signalIntegerOverflowException() {
        throw new IllegalStateException("INT OVERFLOW");
    }

    public void signalBreakException() {
        throw new IllegalStateException("BREAK");
    }

    public void signalReservedInstructionException() {
        _signalReservedInstructionException();
    }

    public static void _signalReservedInstructionException() {
        throw new IllegalStateException("RESERVED INSTRUCTION");
    }

    public void signalSyscallException() {
        if (debugSCP) {
            r3000.restoreInterpreterState();
            log.debug("SYSCALL " + interpreterRegs[4]);
        }
        signalExceptionHelper(EXCEPT_SYSCALL, 0, true);
    }

    public boolean shouldInterrupt() {
        return _shouldInterrupt();
    }

    private static boolean _shouldInterrupt() {
        return (0 != (cause & status & 0xff00)) && (0 != (status & STATUS_IEC));
    }

    public void signalInterruptException() {
        if (debugSCP) log.debug("interrupt!");
        if (false) {
            if (0 != (1 & irqController.getIRQRequest() & irqController.getIRQMask())) {
                vsyncsTaken++;
            }
            long time = quartz.nanoTime();
            if (time > debugNextSecond) {
                log.info("VSYNC/SEC " + vsyncsTaken);
                debugNextSecond = time + Quartz.SEC;
                vsyncsTaken = 0;
            }
        }
        signalExceptionHelper(EXCEPT_INTERRUPT, 0, false);
    }


    public void setInterruptLine(int line, boolean raised) {
        // todo assert range
        if (raised) {
            cause |= 1 << (line + 8);
        } else {
            cause &= ~(1 << (line + 8));
        }
        checkBreakout();
        // wake up the cpu thread if it is blocked
        scheduler.cpuThreadNotify();
    }

    protected static void checkBreakout() {
        if (_shouldInterrupt()) {
            // need to cause an interrupt
            r3000.requestBreakout();
        }
    }

    private static void signalExceptionHelper(int type, int faultingCoprocessor, boolean expectSkip) {
        // we expect PC to be correct already
        r3000.restoreInterpreterState();
        if (debugSCP) log.debug("Exception " + type);
        int vectorOffset = 0;
        // todo, currently recoverable exceptions aren't
        // currently unsupported in the delay slot
        if (false/* && r3000.executingDelaySlot()*/) {
            cause |= CAUSE_BD;
            EPC = r3000.getPC() - 4;
        } else {
            cause &= ~CAUSE_BD;
            EPC = r3000.getPC();
        }
//		if (type==EXCEPT_TLB_REFILL_L ||  type==EXCEPT_TLB_REFILL_S)
//			vectorOffset = 0;
//		else if (type==EXCEPT_INTERRUPT && getCauseIV())
//			vectorOffset = 0x200;
//		else
//			vectorOffset = 0x180;
        vectorOffset = 0x80;
        setCauseCE(faultingCoprocessor);
        setCauseExcCode(type);

        status = (status & 0xffffffc0) | ((status << 2) & 0x3c);

        int pc;
        if (getStatusBEV()) {
            // not sure about this
            pc = 0xbfc00200 + vectorOffset;
        } else {
            pc = 0x80000080;
        }

        //System.out.println("about to take exception vector at "+MiscUtil.toHex(pc,8)+" pc was "+MiscUtil.toHex(m_EPC,8));
        executeException(pc, expectSkip);
    }

    public int currentExceptionType() {
        if (0 != (status & 3)) {
            return -1;
        }
        return getCauseExcCode();
    }

    public static void executeException(int pc, boolean expectSkip) {
        int exppc = r3000.getPC();
        int expsp = interpreterRegs[R3000.R_SP];

        if (debugSCP) {
            log.debug("take exception r_pc " + MiscUtil.toHex(r3000.getPC(), 8) + " sp " + MiscUtil.toHex(interpreterRegs[R3000.R_SP], 8) + " go to " + MiscUtil.toHex(pc, 8));
        }

        // quick hack for now
        if (expectSkip) exppc += 4;

        r3000.setPC(pc);
        r3000.executeFromPC();

        //System.out.println("return from exception r_pc "+MiscUtil.toHex( r3000.getPC(), 8)+" sp "+MiscUtil.toHex( interpreterRegs[R3000.R_SP], 8));
        // todo check what happened if we were in delay slot
        if (r3000.getPC() != exppc || interpreterRegs[R3000.R_SP] != expsp) {
            log.debug("unexpected execution flow: pc " + MiscUtil.toHex(r3000.getPC(), 8) + "," + MiscUtil.toHex(exppc, 8) + " sp " + MiscUtil.toHex(interpreterRegs[R3000.R_SP], 8) + "," + MiscUtil.toHex(expsp, 8));
            throw ContinueExecutionException.DONT_SKIP_CURRENT;
        } else {
            // todo if expect skip, then we can also just return, because the next instruction will be invoked
            // we want to reset m_currentPCDelta to 0, and m_delayedPCDelta to 4
            // so that we continue there.
            //if (expectSkip) r3000.tempHackForException();
            if (expectSkip) {
                r3000.setPC(r3000.getPC() - 4);
            }
        }
    }

    public static void returnFromException() {
        status = (status & 0xffffffc0) | ((status >> 2) & 0xf);
        //System.out.println("return from execption to pc "+MiscUtil.toHex( pc, 8));
        // todo cope with delay slot!
        checkBreakout();
        r3000.setPC(EPC);
        throw ReturnFromExceptionException.INSTANCE;
    }
}
