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
package org.jpsx.api.components.core.cpu;

import org.apache.bcel.generic.InstructionList;

public class CPUInstruction {
    /**
     * any branch instruction
     */
    public static final int FLAG_BRANCH = 0x00000001;

    /**
     * for use if a branch is always taken
     */
    public static final int FLAG_UNCONDITIONAL = 0x00000002;
    /**
     * for branch with an immediate relative offset
     */
    public static final int FLAG_IMM_NEAR_TARGET = 0x00000004;
    /**
     * for branch with an immediate absolute offset
     */
    public static final int FLAG_IMM_FAR_TARGET = 0x00000008;
    /**
     * for branch which links return address in R31
     */
    public static final int FLAG_LINK = 0x00000010;

    /**
     * instruction is depdendent only on immediate value and input register values,
     * and the only side effect (other than exception) is a register value change.
     * <p/>
     * This is useful in compiler because if it can prove the input values are
     * known at compile time, then it can invoke the {@link #simulate} method for the
     * input to calculate the constant output.
     */
    public static final int FLAG_SIMULATABLE = 0x00000020;

    /**
     * what the instruction references
     */
    public static final int FLAG_READS_RS = 0x00000100;
    public static final int FLAG_READS_RT = 0x00000200;
    public static final int FLAG_WRITES_RT = 0x00000400;
    public static final int FLAG_WRITES_RD = 0x00000800;

    // all addresses are assumed to be of the form rs[offset]
    public static final int FLAG_MEM8 = 0x00002000;
    public static final int FLAG_MEM16 = 0x00004000;
    public static final int FLAG_MEM32 = 0x00008000;
    public static final int FLAG_MEM = (FLAG_MEM8 | FLAG_MEM16 | FLAG_MEM32);

    // the instruction may make use of reg_pc
    public static final int FLAG_REFERENCES_PC = 0x00010000;

    // implicitly references pc
    public static final int FLAG_MAY_RESTORE_INTERPRETER_STATE = 0x00020000;
    public static final int FLAG_MAY_SIGNAL_EXCEPTION = FLAG_MAY_RESTORE_INTERPRETER_STATE;

    /**
     * Applies only to the interpreted version of the function
     */
    public static final int FLAG_REQUIRES_COMPLETE_INTERPRETER_STATE = 0x00040000;

    public static final int FLAG_INVALID = 0x00080000;

    public static final int BRANCH_NEVER = 0;
    public static final int BRANCH_SOMETIMES = 1;
    public static final int BRANCH_ALWAYS = 2;

    private String name;
    private Class interpreterClass;
    private int flags;

    public CPUInstruction(String name, Class interpreterClass, int exceptions, int flags) {
        this.interpreterClass = interpreterClass;
        this.name = name;
        this.flags = flags;
    }

    public int getBranchType(int ci) {
        if (0 != (flags & FLAG_BRANCH)) {
            if (0 != (flags & FLAG_UNCONDITIONAL))
                return BRANCH_ALWAYS;
            return BRANCH_SOMETIMES;
        }
        return BRANCH_NEVER;
    }

    public String getName() {
        return name;
    }

    public Class getInterpreterClass() {
        return interpreterClass;
    }

    public CPUInstruction subDecode(int ci) {
        return this;
    }

    public final int getFlags() {
        return flags;
    }

    public void compile(CompilationContext context, int address, int ci, InstructionList il) {
        if (0 != (getFlags() & FLAG_BRANCH)) {
            throw new IllegalStateException("default compiler may not be used for branch instructions: " + getName());
        }
        context.emitInterpretedInstruction(il, ci, interpreterClass.getName(), getInterpretMethodName());
    }

    public boolean simulate(int ci, int[] regs) {
        return false;
    }

    public String getInterpretMethodName() {
        return "interpret_" + getName();
    }
}
