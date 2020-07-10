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

import org.apache.bcel.generic.ConstantPoolGen;
import org.apache.bcel.generic.InstructionHandle;
import org.apache.bcel.generic.InstructionList;

public interface CompilationContext {
    public int getRegValue(int reg);

    public int getConstantRegs();

    public int getWritesReg();

    public int getReadsReg();

    public ConstantPoolGen getConstantPoolGen();

    /**
     * Call into the interpreted version of the instruction... the compiler
     * is responsible for any marshalling of state on entry/exit
     */
    public void emitInterpretedInstruction(InstructionList il, int ci, String clazz, String method);

    public void emitGetReg(InstructionList il, int reg);

    public void emitSetReg(InstructionList il, int reg);

    public void emitCall(InstructionList il, int address, int expectedReturnAddress);

    public void emitCall(InstructionList il, int expectedReturnAddress);

    public void emitJump(InstructionList il);

    public void emitJump(InstructionList il, int address);

    /**
     * read from specified constant address
     */
    public void emitReadMem8(InstructionList il, int address, boolean signed);

    /**
     * read from base reg + offset
     */
    public void emitReadMem8(InstructionList il, int reg, int offset);

    /**
     * read from specified constant address
     */
    public void emitReadMem16(InstructionList il, int address, boolean signed);

    /**
     * read from base reg + offset
     */
    public void emitReadMem16(InstructionList il, int reg, int offset);

    /**
     * read from specified constant address
     */
    public void emitReadMem32(InstructionList il, int address, boolean forceAlign);

    /**
     * read from base reg + offset
     */
    public void emitReadMem32(InstructionList il, int reg, int offset, boolean forceAlign);

    /**
     * write value from stack(0) to specified address
     */
    public void emitWriteMem8(InstructionList il, int address, InstructionList valueList);

    /**
     * write value from stack(0) to base reg + offset
     */
    public void emitWriteMem8(InstructionList il, int reg, int offset);

    /**
     * write value from stack(0) to specified address
     */
    public void emitWriteMem16(InstructionList il, int address, InstructionList valueList);

    /**
     * write value from stack(0) to base reg + offset
     */
    public void emitWriteMem16(InstructionList il, int reg, int offset);

    /**
     * write value from stack(0) from address at stack(1)
     */
    public void emitWriteMem32(InstructionList il, int address, InstructionList valueList, boolean forceAlign);

    /**
     * write value from stack(0) to base reg + offset
     */
    public void emitWriteMem32(InstructionList il, int reg, int offset, InstructionList valueList, boolean forceAlign);

    /**
     * if this is a branch instruction then this would be the instructions
     * for the delay slot
     */
    public void emitDelaySlot(InstructionList il);

    public int getTempLocal(int index);

    public InstructionHandle getBranchTarget(int address);
}
