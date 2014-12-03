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

public class GTEInstructionDisassembler extends DisassemblerComponent {
    public GTEInstructionDisassembler() {
        super("JPSX Disassembler for GTE Instructions");
    }

    public void addInstructions(InstructionRegistrar registrar) {
        DIS dis = new DIS();
        registrar.setInstructionDisassembler("eret", dis);
        registrar.setInstructionDisassembler("mtc2", new DIS_MTC2());
        registrar.setInstructionDisassembler("mfc2", new DIS_MFC2());
        registrar.setInstructionDisassembler("ctc2", new DIS_CTC2());
        registrar.setInstructionDisassembler("cfc2", new DIS_CFC2());
        registrar.setInstructionDisassembler("lwc2", new DIS_LWC2());
        registrar.setInstructionDisassembler("swc2", new DIS_SWC2());
        registrar.setInstructionDisassembler("rtpt", dis);
        registrar.setInstructionDisassembler("rtps", dis);
        registrar.setInstructionDisassembler("mvmva", dis);
        registrar.setInstructionDisassembler("op", dis);
        registrar.setInstructionDisassembler("avsz3", dis);
        registrar.setInstructionDisassembler("avsz4", dis);
        registrar.setInstructionDisassembler("nclip", dis);
        registrar.setInstructionDisassembler("ncct", dis);
        registrar.setInstructionDisassembler("gpf", dis);
        registrar.setInstructionDisassembler("dcpl", dis);
        registrar.setInstructionDisassembler("dpcs", dis);
        registrar.setInstructionDisassembler("intpl", dis);
        registrar.setInstructionDisassembler("sqr", dis);
        registrar.setInstructionDisassembler("ncs", dis);
        registrar.setInstructionDisassembler("nct", dis);
        registrar.setInstructionDisassembler("ncds", dis);
        registrar.setInstructionDisassembler("ncdt", dis);
        registrar.setInstructionDisassembler("dpct", dis);
        registrar.setInstructionDisassembler("nccs", dis);
        registrar.setInstructionDisassembler("cdp", dis);
        registrar.setInstructionDisassembler("cc", dis);
        registrar.setInstructionDisassembler("gpl", dis);
    }


    private static final String gteNames[] = new String[]{
            "vxy0", "vz0", "vxy1", "vz1", "vxy2", "vz2", "rgb", "otz",
            "ir0", "ir1", "ir2", "ir3", "sxy0", "sxy1", "sxy2", "sxyp",
            "szx", "sz0", "sz1", "sz2", "rgb0", "rgb1", "rgb2", "res1",
            "mac0", "mac1", "mac2", "mac3", "irgb", "orgb", "lzcs", "lzcr",
            "r11r12", "r13r21", "r22r23", "r31r32", "r33", "trx", "try", "trz",
            "l11l12", "l13l21", "l22l23", "l31l32", "l33", "rbk", "gbk", "bbk",
            "lr1lr2", "lr3lg1", "lg2lg3", "lb1lb2", "lb3", "rfc", "gfc", "bfc",
            "ofx", "ofy", "h", "dqa", "dqb", "zsf3", "zsf4", "flag"
    };

    private static class DIS_LWC2 implements CPUInstructionDisassembler {
        public String disassemble(CPUInstruction inst, int address, int ci) {
            int val = R3000.Util.lo(ci);
            return padString(inst.getName()) + " " + gteNames[R3000.Util.bits_rt(ci)] + ", r" + R3000.Util.bits_rs(ci) + "[0x" + MiscUtil.toHex(val, 4) + "]";
        }
    }

    private static class DIS_SWC2 implements CPUInstructionDisassembler {
        public String disassemble(CPUInstruction inst, int address, int ci) {
            int val = R3000.Util.lo(ci);
            return padString(inst.getName()) + " r" + R3000.Util.bits_rs(ci) + "[0x" + MiscUtil.toHex(val, 4) + "], " + gteNames[R3000.Util.bits_rt(ci)];
        }
    }

    private static class DIS_MTC2 implements CPUInstructionDisassembler {
        public String disassemble(CPUInstruction inst, int address, int ci) {
            int rt = R3000.Util.bits_rt(ci);
            int rd = R3000.Util.bits_rd(ci);
            return padString(inst.getName()) + " " + gteNames[rd] + ", r" + rt;
        }
    }

    private static class DIS_MFC2 implements CPUInstructionDisassembler {
        public String disassemble(CPUInstruction inst, int address, int ci) {
            int rt = R3000.Util.bits_rt(ci);
            int rd = R3000.Util.bits_rd(ci);
            return padString(inst.getName()) + " r" + rt + ", " + gteNames[rd];
        }
    }

    private static class DIS_CTC2 implements CPUInstructionDisassembler {
        public String disassemble(CPUInstruction inst, int address, int ci) {
            int rt = R3000.Util.bits_rt(ci);
            int rd = R3000.Util.bits_rd(ci);
            return padString(inst.getName()) + " " + gteNames[rd + 32] + ", r" + rt;
        }
    }

    private static class DIS_CFC2 implements CPUInstructionDisassembler {
        public String disassemble(CPUInstruction inst, int address, int ci) {
            int rt = R3000.Util.bits_rt(ci);
            int rd = R3000.Util.bits_rd(ci);
            return padString(inst.getName()) + " r" + rt + ", " + gteNames[rd + 32];
        }
    }
}
