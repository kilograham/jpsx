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
package org.jpsx.runtime.debugcomponents.emulator.disassemblers;

import org.jpsx.api.components.core.cpu.CPUInstruction;
import org.jpsx.api.components.core.cpu.CPUInstructionDisassembler;
import org.jpsx.api.components.core.cpu.InstructionRegistrar;
import org.jpsx.api.components.core.cpu.R3000;
import org.jpsx.runtime.util.MiscUtil;

// TODO move setting of instructions elsewhere

public class R3000InstructionDisassembler extends DisassemblerComponent {
    public R3000InstructionDisassembler() {
        super("JPSX Disassembler for R3000 Instructions");
    }

    public void addInstructions(InstructionRegistrar registrar) {
        DIS_TSB disTSB = new DIS_TSB();
        DIS_SB disSB = new DIS_SB();
        DIS_TSI disTSI = new DIS_TSI();
        DIS_TSIU disTSIU = new DIS_TSIU();
        DIS_TIU disTIU = new DIS_TIU();
        DIS_TSA disTSA = new DIS_TSA();
        DIS_SAT disSAT = new DIS_SAT();
        DIS_ST disST = new DIS_ST();
        DIS_DTH disDTH = new DIS_DTH();
        DIS_DST disDST = new DIS_DST();
        DIS_DTS disDTS = new DIS_DTS();
        DIS_J disJ = new DIS_J();
        DIS_S disS = new DIS_S();
        DIS_D disD = new DIS_D();
        DIS_SD disSD = new DIS_SD();
        DIS_C disC = new DIS_C();
        DIS dis = new DIS();

        registrar.setInstructionDisassembler("bne", disTSB);
        registrar.setInstructionDisassembler("beq", disTSB);
        registrar.setInstructionDisassembler("addi", disTSI);
        registrar.setInstructionDisassembler("addiu", disTSI);
        registrar.setInstructionDisassembler("slti", disTSI);
        registrar.setInstructionDisassembler("sltiu", disTSI);
        registrar.setInstructionDisassembler("andi", disTSIU);
        registrar.setInstructionDisassembler("ori", disTSIU);
        registrar.setInstructionDisassembler("xori", disTSIU);
        registrar.setInstructionDisassembler("sll", disDTH);
        registrar.setInstructionDisassembler("srl", disDTH);
        registrar.setInstructionDisassembler("sra", disDTH);
        registrar.setInstructionDisassembler("bltz", disSB);
        registrar.setInstructionDisassembler("bgez", disSB);
        registrar.setInstructionDisassembler("blez", disSB);
        registrar.setInstructionDisassembler("bgtz", disSB);
        registrar.setInstructionDisassembler("bltzal", disSB);
        registrar.setInstructionDisassembler("bgezal", disSB);
        registrar.setInstructionDisassembler("add", disDST);
        registrar.setInstructionDisassembler("addu", disDST);
        registrar.setInstructionDisassembler("sub", disDST);
        registrar.setInstructionDisassembler("subu", disDST);
        registrar.setInstructionDisassembler("and", disDST);
        registrar.setInstructionDisassembler("or", disDST);
        registrar.setInstructionDisassembler("xor", disDST);
        registrar.setInstructionDisassembler("nor", disDST);
        registrar.setInstructionDisassembler("slt", disDST);
        registrar.setInstructionDisassembler("sltu", disDST);
        registrar.setInstructionDisassembler("lb", disTSA);
        registrar.setInstructionDisassembler("lh", disTSA);
        registrar.setInstructionDisassembler("lwl", disTSA);
        registrar.setInstructionDisassembler("lw", disTSA);
        registrar.setInstructionDisassembler("lbu", disTSA);
        registrar.setInstructionDisassembler("lhu", disTSA);
        registrar.setInstructionDisassembler("lwr", disTSA);
        registrar.setInstructionDisassembler("sb", disSAT);
        registrar.setInstructionDisassembler("sh", disSAT);
        registrar.setInstructionDisassembler("swl", disSAT);
        registrar.setInstructionDisassembler("sw", disSAT);
        registrar.setInstructionDisassembler("swr", disSAT);
        registrar.setInstructionDisassembler("lui", disTIU);
        registrar.setInstructionDisassembler("sllv", disDTS);
        registrar.setInstructionDisassembler("srlv", disDTS);
        registrar.setInstructionDisassembler("srav", disDTS);
        registrar.setInstructionDisassembler("mult", disST);
        registrar.setInstructionDisassembler("multu", disST);
        registrar.setInstructionDisassembler("div", disST);
        registrar.setInstructionDisassembler("divu", disST);
        registrar.setInstructionDisassembler("j", disJ);
        registrar.setInstructionDisassembler("jal", disJ);
        registrar.setInstructionDisassembler("jr", disS);
        registrar.setInstructionDisassembler("mthi", disS);
        registrar.setInstructionDisassembler("mtlo", disS);
        registrar.setInstructionDisassembler("mfhi", disD);
        registrar.setInstructionDisassembler("mflo", disD);
        registrar.setInstructionDisassembler("jalr", disSD);

        registrar.setInstructionDisassembler("syscall", disC);
        registrar.setInstructionDisassembler("break", disC);
        registrar.setInstructionDisassembler("eret", dis);
        registrar.setInstructionDisassembler("mtc0", new DIS_MTC0());
        registrar.setInstructionDisassembler("mfc0", new DIS_MFC0());
    }

    public static class DIS_TSB implements CPUInstructionDisassembler {
        public String disassemble(CPUInstruction inst, int address, int ci) {
            return padString(inst.getName()) + " r" + R3000.Util.bits_rt(ci) + ", r" + R3000.Util.bits_rs(ci) + " 0x" + MiscUtil.toHex(address + 4 + R3000.Util.signed_branch_delta(ci), 8);
        }
    }

    public static class DIS_SB implements CPUInstructionDisassembler {
        public String disassemble(CPUInstruction inst, int address, int ci) {
            return padString(inst.getName()) + " r" + R3000.Util.bits_rs(ci) + " 0x" + MiscUtil.toHex(address + 4 + R3000.Util.signed_branch_delta(ci), 8);
        }
    }

    public static class DIS_TSI implements CPUInstructionDisassembler {
        public String disassemble(CPUInstruction inst, int address, int ci) {
            int val = R3000.Util.sign_extend(ci);
            String rc = padString(inst.getName()) + " r" + R3000.Util.bits_rt(ci) + ", r" + R3000.Util.bits_rs(ci) + ", 0x" + MiscUtil.toHex(val, 4);
            return padString(rc, 32) + " ;" + val;
        }
    }

    public static class DIS_TSIU implements CPUInstructionDisassembler {
        public String disassemble(CPUInstruction inst, int address, int ci) {
            int val = R3000.Util.lo(ci);
            String rc = padString(inst.getName()) + " r" + R3000.Util.bits_rt(ci) + ", r" + R3000.Util.bits_rs(ci) + ", 0x" + MiscUtil.toHex(val, 4);
            return padString(rc, 32) + " ;" + val;
        }
    }

    public static class DIS_TIU implements CPUInstructionDisassembler {
        public String disassemble(CPUInstruction inst, int address, int ci) {
            int val = R3000.Util.lo(ci);
            String rc = padString(inst.getName()) + " r" + R3000.Util.bits_rt(ci) + ", 0x" + MiscUtil.toHex(val, 4);
            return padString(rc, 32) + " ;" + val;
        }
    }

    public static class DIS_TSA implements CPUInstructionDisassembler {
        public String disassemble(CPUInstruction inst, int address, int ci) {
            int val = R3000.Util.lo(ci);
            return padString(inst.getName()) + " r" + R3000.Util.bits_rt(ci) + ", r" + R3000.Util.bits_rs(ci) + "[0x" + MiscUtil.toHex(val, 4) + "]";
        }
    }

    public static class DIS_SAT implements CPUInstructionDisassembler {
        public String disassemble(CPUInstruction inst, int address, int ci) {
            int val = R3000.Util.lo(ci);
            return padString(inst.getName()) + " r" + R3000.Util.bits_rs(ci) + "[0x" + MiscUtil.toHex(val, 4) + "], r" + R3000.Util.bits_rt(ci);
        }
    }

    public static class DIS_ST implements CPUInstructionDisassembler {
        public String disassemble(CPUInstruction inst, int address, int ci) {
            return padString(inst.getName()) + " r" + R3000.Util.bits_rs(ci) + ", r" + R3000.Util.bits_rt(ci);
        }
    }

    public static class DIS_DTH implements CPUInstructionDisassembler {
        public String disassemble(CPUInstruction inst, int address, int ci) {
            if (ci == 0)
                return padString("nop");
            else
                return padString(inst.getName()) + " r" + R3000.Util.bits_rd(ci) + ", r" + R3000.Util.bits_rt(ci) + ", " + R3000.Util.bits_sa(ci);
        }
    }

    public static class DIS_DST implements CPUInstructionDisassembler {
        public String disassemble(CPUInstruction inst, int address, int ci) {
            return padString(inst.getName()) + " r" + R3000.Util.bits_rd(ci) + ", r" + R3000.Util.bits_rs(ci) + ", r" + R3000.Util.bits_rt(ci);
        }
    }

    public static class DIS_DTS implements CPUInstructionDisassembler {
        public String disassemble(CPUInstruction inst, int address, int ci) {
            return padString(inst.getName()) + " r" + R3000.Util.bits_rd(ci) + ", r" + R3000.Util.bits_rt(ci) + ", r" + R3000.Util.bits_rs(ci);
        }
    }

    public static class DIS_J implements CPUInstructionDisassembler {
        public String disassemble(CPUInstruction inst, int address, int ci) {
            return padString(inst.getName()) + " 0x" + MiscUtil.toHex(((address + 4) & 0xf0000000) + ((ci & 0x3ffffff) << 2), 8);
        }
    }

    public static class DIS_S implements CPUInstructionDisassembler {
        public String disassemble(CPUInstruction inst, int address, int ci) {
            return padString(inst.getName()) + " r" + R3000.Util.bits_rs(ci);
        }
    }

    public static class DIS_D implements CPUInstructionDisassembler {
        public String disassemble(CPUInstruction inst, int address, int ci) {
            return padString(inst.getName()) + " r" + R3000.Util.bits_rd(ci);
        }
    }

    public static class DIS_C implements CPUInstructionDisassembler {
        public String disassemble(CPUInstruction inst, int address, int ci) {
            return padString(inst.getName()) + " 0x" + Integer.toHexString(R3000.Util.bits25_6(ci));
        }
    }

    public static class DIS_SD implements CPUInstructionDisassembler {
        public String disassemble(CPUInstruction inst, int address, int ci) {
            return padString(inst.getName()) + " r" + R3000.Util.bits_rs(ci) + ", r" + R3000.Util.bits_rd(ci);
        }
    }

    protected static String getCOP0Name(int reg, int sel) {

        switch ((sel << 16) + reg) {
            case 0:
                return "index";
            case 1:
                return "random";
            case 2:
                return "entryLow0";
            case 3:
                return "entryLow1";
            case 4:
                return "context";
            case 5:
                return "pageMask";
            case 6:
                return "wired";
            case 8:
                return "badVAddr";
            case 9:
                return "count";
            case 10:
                return "entryHi";
            case 11:
                return "compare";
            case 12:
                return "status";
            case 13:
                return "cause";
            case 14:
                return "epc";
            case 15:
                return "progId";
            case 16:
                return "config";
            case 0x10010:
                return "config1";
            case 0x20010:
                return "config2";
            case 0x30010:
                return "config3";
            case 30:
                return "errorEPC";
            default:
                return reg + ":" + sel;
        }
    }

    protected static class DIS_MTC0 implements CPUInstructionDisassembler {
        public String disassemble(CPUInstruction inst, int address, int ci) {
            int rt = R3000.Util.bits_rt(ci);
            int rd = R3000.Util.bits_rd(ci);
            int sel = ci & 0x7;
            return padString(inst.getName()) + " " + getCOP0Name(rd, sel) + ", r" + rt;
        }
    }

    protected static class DIS_MFC0 implements CPUInstructionDisassembler {
        public String disassemble(CPUInstruction inst, int address, int ci) {
            int rt = R3000.Util.bits_rt(ci);
            int rd = R3000.Util.bits_rd(ci);
            int sel = ci & 0x7;
            return padString(inst.getName()) + " r" + rt + ", " + getCOP0Name(rd, sel);
        }
    }
}
