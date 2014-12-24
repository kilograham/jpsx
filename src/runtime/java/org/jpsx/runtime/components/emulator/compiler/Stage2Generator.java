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
package org.jpsx.runtime.components.emulator.compiler;

import org.apache.bcel.Constants;
import org.apache.bcel.generic.*;
import org.jpsx.api.components.core.addressspace.AddressSpace;
import org.jpsx.api.components.core.cpu.CPUInstruction;
import org.jpsx.api.components.core.cpu.R3000;
import org.jpsx.runtime.components.core.CoreComponentConnections;
import org.jpsx.runtime.components.core.R3000Impl;
import org.jpsx.runtime.util.MiscUtil;

import java.util.Stack;

// todo is this true now?

// note while it is tempting to omit the intervening write of reg8 here,
//
// addiu    r8, r4, r0
// lw       r8, r8[4]
//
// we cannot do so (unless we know the value of r4 at compile time), because
// a memory type misprediction of the memory read in the lw instruction would
// cause us to have to re-execute the second instruction in the interpreter, and we wouldn't have
// a good way to figure out the true value of r8.
//
// Note, that it might be possible to do this, but it would involve more complicated
// code to figure out what we had done when we compiled the code, and then simulate the instructions we skipped.
//
// In the meanwhile, we just cope with figuring out what registers are constant at the re-execute

// point, and write all of those back
public class Stage2Generator extends Stage1Generator {
    protected static String CLASS_NAME_PREFIX = "_2";

    private static final int READ_TAG_MASK = (AddressSpace.TAG_RAM |
            AddressSpace.TAG_SCRATCH |
            AddressSpace.TAG_HW |
            AddressSpace.TAG_BIOS |
            AddressSpace.TAG_PAR |
            AddressSpace.TAG_POLL) & 0xff;

    private int getRegsOffset = -1;
    private int[] regsAtOffset = new int[32];
    private int CRAtOffset = 0;
    private final AddressSpace addressSpace = CoreComponentConnections.ADDRESS_SPACE.resolve();
    private final R3000 r3000 = CoreComponentConnections.R3000.resolve();

    public Stage2Generator(String codeFilename, boolean intendedForExecutionThread) {
        super(codeFilename, intendedForExecutionThread);
    }

    protected String getClassNamePrefix(CodeUnit unit) {
        return "_" + (2 + unit.stage2Version);
    }

    protected String getNextClassNamePrefix(CodeUnit unit) {
        return "_" + (3 + unit.stage2Version);
    }

    protected void emitBreakoutCheck(InstructionList il) {
        // write back regs before the breakout check
        writeBackRegs(il, ALL_REGS);
        super.emitBreakoutCheck(il);
    }

    protected void emitMethodHeader(InstructionList il) {
        // don't emit all the junk that Compiler1 does
        FieldGen fg = new FieldGen(Constants.ACC_PUBLIC | Constants.ACC_STATIC, Type.BOOLEAN, "replaced", contextCP);
        contextClassGen.addField(fg.getField());

        il.append(new GETSTATIC(contextCP.addFieldref(contextClassGen.getClassName(), "replaced", "Z")));
        IFEQ ifeq = new IFEQ(null);
        il.append(ifeq);
        il.append(new ILOAD(0));
        il.append(new ILOAD(1));
        il.append(new INVOKESTATIC(contextCP.addMethodref(getClassName(getNextClassNamePrefix(contextUnit), contextBase), STATIC_METHOD, "(IZ)I")));
        il.append(new IRETURN());
        ifeq.setTarget(il.append(new NOP()));
        if (R3000Impl.Settings.traceExecutionFlow) {
            il.append(new PUSH(contextCP, contextBase));
            il.append(new ILOAD(LOCAL_RETADDR));
            il.append(new ILOAD(LOCAL_JUMP));
            il.append(new INVOKESTATIC(contextCP.addMethodref(COMPILER_CLASS, "traceEnterUnit", "(IIZ)V")));
        }
        if (false/*Debug.dumpRegsOnCall*/) {
            il.append(new PUSH(contextCP, contextBase));
            il.append(new INVOKESTATIC(contextCP.addMethodref(COMPILER_CLASS, "dumpState", "(I)V")));
        }
    }

    protected static final boolean debugCR = true;

    protected BlockInfo[] blockInfo = new BlockInfo[MultiStageCompiler.Settings.maxR3000InstructionsPerUnit];
    /**
     * Instructions that can safely be omitted because they have constant input and the
     * output can be determined by simulation
     */
    protected boolean[] simulated = new boolean[MultiStageCompiler.Settings.maxR3000InstructionsPerUnit];
    protected Stack<BlockInfo> dirtyBlocks = new Stack<BlockInfo>();
    protected int visitCount;

    protected class BlockInfo {
        public BlockInfo(FlowAnalyzer.BasicBlock bb) {
            this.bb = bb;
        }

        FlowAnalyzer.BasicBlock bb;
        boolean dirty;
        boolean visited;

        int ICR;
        int OCR;
        int[] incomingRegValues = new int[32];
        int[] outgoingRegValues = new int[32];

        public String toString() {
            return "visited " + visited + " dirty " + dirty + " ICR " + MiscUtil.toHex(ICR, 8) + " OCR " + MiscUtil.toHex(OCR, 8) + " " + bb;
        }

        public void updateIncoming(final int CR, final int[] regValues) {
            // incoming constant regs are ANDed with what we knew before, unless we're
            // not initialized yet (ICR==0)
            int newICR = ICR == 0 ? CR : (CR & ICR);

            int stillConstant = ICR & newICR;
            int nowConstant = (newICR ^ ICR) & newICR;

            int mask = 2;
            for (int bit = 1; bit < 32; bit++) {
                if (0 != (stillConstant & mask)) {
                    // check that the reg value isn't different for any "constant" register,
                    // meaning that it isn't constant.
                    if (incomingRegValues[bit] != regValues[bit]) {
                        newICR &= ~mask;
                    }
                } else if (0 != (nowConstant & mask)) {
                    incomingRegValues[bit] = regValues[bit];
                }
                mask <<= 1;
            }
            // note we will always be dirty at least once since ICR is initially zero
            if (newICR != ICR) {
                ICR = newICR;
                dirty = true;
                dirtyBlocks.push(this);
            }
            assert 1 == (ICR & 1);
        }

        public void visit() {
            if (dirty) {
                if (debugCR) {
                    visitCount++;
                }
                //System.out.println( "visit "+this);
                if (!visited) {
                    for (int i = 1; i < 32; i++) {
                        outgoingRegValues[i] = incomingRegValues[i];
                    }
                }
                int oldOCR = OCR;
                OCR = ICR;
                simulate(!visited);
                // after the first time through OCR should always be a subset of oldOCR
                assert !visited || 0 == (OCR & ~oldOCR);
                assert 1 == (OCR & 1);
                dirty = false;
                if (!visited || OCR != oldOCR) {
                    visited = true;
                    BlockInfo bbFlow = null;
                    BlockInfo bbBranch = null;
                    int scoreFlow = -1;
                    int scoreBranch = -1;

                    if (bb.flowOut != null && bb.flowOut.type == FlowAnalyzer.BasicBlock.NORMAL) {
                        bbFlow = blockInfo[bb.flowOut.offset];
                        // todo score
                    }
                    if (bb.branchOut != null && bb.branchOut.type == FlowAnalyzer.BasicBlock.NORMAL) {
                        bbBranch = blockInfo[bb.branchOut.offset];
                        // todo score
                    }


                    if (scoreBranch > scoreFlow) {
                        bbBranch.updateIncoming(OCR, outgoingRegValues);
                        if (bbFlow != null) {
                            bbFlow.updateIncoming(OCR, outgoingRegValues);
                        }
                    } else if (scoreFlow > scoreBranch) {
                        bbFlow.updateIncoming(OCR, outgoingRegValues);
                        if (bbBranch != null) {
                            bbBranch.updateIncoming(OCR, outgoingRegValues);
                        }
                    } else {
                        if (bbBranch != null) {
                            bbBranch.updateIncoming(OCR, outgoingRegValues);
                        }
                        if (bbFlow != null) {
                            bbFlow.updateIncoming(OCR, outgoingRegValues);
                        }
                    }
                }
            }
        }

        protected void simulateCalculate(int offset) {
            if (0 != (flags[offset] & CPUInstruction.FLAG_SIMULATABLE) &&
                    regsRead[offset] == (regsRead[offset] & OCR) &&
                    instructions[offset].simulate(opCodes[offset], outgoingRegValues)) {
                simulated[offset] = true;
                OCR |= regsWritten[offset];
            } else {
                OCR &= ~regsWritten[offset];
                simulated[offset] = false;
            }
        }

        protected void simulateNoCalculate(int offset) {
            if (simulated[offset]) {
                if (regsRead[offset] == (regsRead[offset] & OCR)) {
                    OCR |= regsWritten[offset];
                } else {
                    OCR &= ~regsWritten[offset];
                    simulated[offset] = false;
                }
            } else {
                OCR &= ~regsWritten[offset];
            }
        }

        protected void simulate(boolean calculateValues) {
            if (calculateValues) {
                for (int offset = bb.offset; offset < bb.offset + bb.size; offset++) {
                    // special case to figure out constant regs at a particular location
                    if (offset == getRegsOffset) {
                        for (int i = 0; i < 32; i++) regsAtOffset[i] = outgoingRegValues[i];
                        CRAtOffset = OCR;
                    }
                    if (0 != (flags[offset] & CPUInstruction.FLAG_BRANCH)) {
                        // re-order so that
                        simulateCalculate(offset + 1);
                        simulateCalculate(offset);
                        offset++;
                    } else {
                        simulateCalculate(offset);
                    }
                }
            } else {
                for (int offset = bb.offset; offset < bb.offset + bb.size; offset++) {
                    // special case to figure out constant regs at a particular location
                    if (offset == getRegsOffset) {
                        for (int i = 0; i < 32; i++) regsAtOffset[i] = outgoingRegValues[i];
                        CRAtOffset = OCR;
                    }
                    if (0 != (flags[offset] & CPUInstruction.FLAG_BRANCH)) {
                        // re-order so that
                        simulateNoCalculate(offset + 1);
                        simulateNoCalculate(offset);
                        offset++;
                    } else {
                        simulateNoCalculate(offset);
                    }
                }
            }
        }
    }


    protected void initBlockStructures(FlowAnalyzer.FlowInfo flowInfo) {
        super.initBlockStructures(flowInfo);
        for (FlowAnalyzer.BasicBlock block = flowInfo.root; block != null; block = block.next) {
            if (block.type == FlowAnalyzer.BasicBlock.NORMAL) {
                blockInfo[block.offset] = new BlockInfo(block);
            }
        }
        if (debugCR) {
            visitCount = 0;
        }
        assert (dirtyBlocks.isEmpty());
        blockInfo[0].updateIncoming(1, new int[32]);
        if (MultiStageCompiler.Settings.printCode && shouldPrintCode()) {
            codeWriter.println("...");
        }
        while (!dirtyBlocks.isEmpty()) {
            ((BlockInfo) dirtyBlocks.pop()).visit();
        }
        if (MultiStageCompiler.Settings.printCode && shouldPrintCode()) {
            if (debugCR) {
                codeWriter.println("CR took " + visitCount + " iterations for " + flowInfo.blockCount + " blocks: " + (visitCount / (double) flowInfo.blockCount));
                for (FlowAnalyzer.BasicBlock block = flowInfo.root; block != null; block = block.next) {
                    if (block.type == FlowAnalyzer.BasicBlock.NORMAL) {
                        BlockInfo info = blockInfo[block.offset];
                        codeWriter.println("--- " + info);
                        for (int offset = block.offset; offset < block.offset + block.size; offset++) {
                            int address = flowInfo.base + offset * 4;
                            int ci = addressSpace.internalRead32(address);
                            String debug = simulated[offset] ? "* " : "  ";
                            debug += MiscUtil.toHex(address, 8) + " " + r3000.disassemble(address, ci);
                            codeWriter.println(debug);
                        }
                    }
                }
            }
        }
    }

    protected void emitBlockHeader(InstructionList il) {
        if (contextBlock.type == FlowAnalyzer.BasicBlock.NORMAL) {
            BlockInfo blockInfo = this.blockInfo[contextBlock.offset];
            contextRegValues = blockInfo.incomingRegValues;
            contextCR = blockInfo.ICR;
            // ICR is initialized to zero, so code that is never reached needs to have r0 set
            if (contextCR == 0 && !blockInfo.visited) {
                contextCR = 1;
            }
            assert 1 == (contextCR & 1);
            contextUnwrittenRegs = 0;
        }
        super.emitBlockHeader(il);
    }

    protected void emitBlockFooter(InstructionList il) {
        writeBackRegs(il, ALL_REGS);
        super.emitBlockFooter(il);
    }

    protected void emitContextInstructionGuts(InstructionList il) {
        if (0 != (flags[contextOffset] & (CPUInstruction.FLAG_MAY_RESTORE_INTERPRETER_STATE))) {
            writeBackRegs(il, ALL_REGS);
        }
        // note this function would be pretty broken if branch instructions were simulatable!
        if (simulated[contextOffset]) {
            instructions[contextOffset].simulate(opCodes[contextOffset], contextRegValues);
/*			if (printCode && shouldPrintCode()) {
				InstructionHandle endHandle = il.getEnd();
				if (endHandle == null) {
					endHandle = il.append( new NOP() );
				}
				endHandles[contextOffset] = endHandle;
			}*/
            contextUnwrittenRegs |= regsWritten[contextOffset];
            contextCR |= regsWritten[contextOffset];
        } else {
            // todo fix this; we only need to write back regs which aren't constant (unless the instruction
            // doesn't observe constant regs)
            writeBackRegs(il, regsRead[contextOffset]);
            if (contextUnwrittenRegs != 0) {
                // record the fact that we have unwritten regs
                addressSpace.orTag(contextAddress, MultiStageCompiler.TAG_UNWRITTEN_REGS);
            }
            instructions[contextOffset].compile(this, contextAddress, opCodes[contextOffset], il);
            contextUnwrittenRegs &= ~regsWritten[contextOffset];
            assert 0 == (regsWritten[contextOffset] & 1);
            contextCR &= ~regsWritten[contextOffset];
        }
        // write back registers before call/branch
        if (contextIsDelaySlot) {
            writeBackRegs(il, ALL_REGS);
        }
    }

    protected void writeBackRegs(InstructionList il, int regs) {
        int toWrite = contextUnwrittenRegs & regs;
        if (toWrite != 0) {
            for (int reg = 1; reg < 32; reg++) {
                if (0 != (toWrite & (1 << reg))) {
                    il.append(new PUSH(contextCP, contextRegValues[reg]));
                    emitSetReg(il, reg);
                }
            }
            contextUnwrittenRegs &= ~regs;
        }
    }

    protected int[] contextRegValues;
    protected int contextCR;
    protected int contextUnwrittenRegs;

    public int getRegValue(int reg) {
        assert 0 != (contextCR & (1 << reg));
        return contextRegValues[reg];
    }

    public int getConstantRegs() {
        assert (1 == (contextCR & 1));
        return contextCR;
    }

    protected void disassemble(int index) {
        int address = contextBase + (index << 2);
        String prefix = "";
        if (simulated[index]) {
            prefix = "X";
        } else {
            prefix = " ";
        }
        // graham 12/23/14 commented this out as it makes no sense contextCR is not necessarily the constant registers for this instruction
//        if (regsRead[index] == (contextCR & regsRead[index])) {
//            prefix += "C";
//        } else {
//            prefix += " ";
//        }
        if (0 != (addressSpace.getTag(address) & MultiStageCompiler.TAG_UNWRITTEN_REGS)) {
            prefix += ">";
        } else {
            prefix += " ";
        }
        if (0 != (addressSpace.getTag(address) & MultiStageCompiler.TAG_DELAY_SLOT)) {
            prefix += "D";
        } else {
            prefix += " ";
        }
        String prefix1 = "";
        String suffix = "";
        int ci = opCodes[index];
        String dis = r3000.disassemble(address, ci);
        codeWriter.println("                                                                   " + prefix + " " + prefix1 + " " + MiscUtil.toHex(address, 8) + ": " + MiscUtil.toHex(ci, 8) + " " + dis + suffix);
    }

    public void emitCall(InstructionList il, int address, int retAddr) {
        if (R3000Impl.Settings.traceExecutionFlow) {
            il.append(new PUSH(contextCP, address));
            il.append(new PUSH(contextCP, retAddr));
            il.append(new INVOKESTATIC(contextCP.addMethodref(COMPILER_CLASS, "traceDirectCall", "(II)V")));
        }

        if (AddressSpace.Util.isBIOS(contextAddress) && !AddressSpace.Util.isBIOS(address)) {
            // calling from bios to ram must be indirect... note this is probably rare.
            System.out.println("Emitting bios to ram call");
            il.append(new PUSH(contextCP, address));
            il.append(new PUSH(contextCP, retAddr));
            il.append(new INVOKESTATIC(contextCP.addMethodref(COMPILER_CLASS, MultiStageCompiler.CALL_METHOD, "(II)V")));
        } else {
            il.append(new PUSH(contextCP, retAddr));
            il.append(new PUSH(contextCP, 0));
            il.append(new INVOKESTATIC(contextCP.addMethodref(getClassName(Stage1Generator.CLASS_NAME_PREFIX, address), STATIC_METHOD, "(IZ)I")));
            il.append(new POP());
        }
    }

    /**
     * Used for JALR etc where the address to call is at the top
     * of the JVM stack
     */
    public void emitCall(InstructionList il, int retAddr) {
        il.append(new PUSH(contextCP, retAddr));
        il.append(new INVOKESTATIC(contextCP.addMethodref(COMPILER_CLASS, MultiStageCompiler.CALL_METHOD, "(II)V")));
    }

    public void emitReadMem8(InstructionList il, int address, boolean signed) {
        int tag = addressSpace.getTag(contextAddress) & READ_TAG_MASK;
        // we must do tag==0 since this means we haven't executed this statement yet
        if (tag == 0 || 0 != (tag & AddressSpace.TAG_POLL)) {
            il.append(new PUSH(contextCP, address));
            // todo note this only detects REALLY tight polling; we'd be better off waiting to get a tag somehow (de-optimize perhaps)
            il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_checkPoll8", "(I)V")));
        }
        rr.mem = null;
        addressSpace.resolve(address, rr);
        if (rr.tag == AddressSpace.TAG_RAM) {
            il.append(new GETSTATIC(contextCP.addFieldref(ADDRESS_SPACE_CLASS, "ramD", "[I")));
            il.append(new PUSH(contextCP, rr.offset));
            il.append(new IALOAD());
        } else if (rr.tag == AddressSpace.TAG_SCRATCH) {
            il.append(new GETSTATIC(contextCP.addFieldref(ADDRESS_SPACE_CLASS, "scratch", "[I")));
            il.append(new PUSH(contextCP, rr.offset));
            il.append(new IALOAD());
        } else if (rr.tag == AddressSpace.TAG_BIOS) {
            il.append(new GETSTATIC(contextCP.addFieldref(ADDRESS_SPACE_CLASS, "bios", "[I")));
            il.append(new PUSH(contextCP, rr.offset));
            il.append(new IALOAD());
        } else if (rr.tag == AddressSpace.TAG_HW) {
            il.append(new PUSH(contextCP, address));
            il.append(new INVOKESTATIC(contextCP.addMethodref(HW_CLASS, "read8", "(I)I")));
            return;
        } else {
            il.append(new PUSH(contextCP, address));
            il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_read8", "(I)I")));
        }
        if (signed) {
            switch (address & 3) {
                case 2:
                    il.append(new PUSH(contextCP, 8));
                    il.append(new ISHL());
                    break;
                case 1:
                    il.append(new PUSH(contextCP, 16));
                    il.append(new ISHL());
                    break;
                case 0:
                    il.append(new PUSH(contextCP, 24));
                    il.append(new ISHL());
                    break;
            }
            il.append(new PUSH(contextCP, 24));
            il.append(new ISHR());
        } else {
            switch (address & 3) {
                case 3:
                    il.append(new PUSH(contextCP, 24));
                    il.append(new ISHR());
                    break;
                case 2:
                    il.append(new PUSH(contextCP, 16));
                    il.append(new ISHR());
                    break;
                case 1:
                    il.append(new PUSH(contextCP, 8));
                    il.append(new ISHR());
                    break;
            }
            il.append(new PUSH(contextCP, 0xff));
            il.append(new IAND());
        }
    }

    public void emitReadMem8(InstructionList il, int reg, int offset) {
        int tag = addressSpace.getTag(contextAddress) & READ_TAG_MASK;
        // todo note that right now we don't tag usuallyRAMRegs, but we could in the future so this tag == 0 check is there for that
        if (tag == 0 && (0 != ((1 << reg) & MultiStageCompiler.Settings.usuallyRAMRegs))) {
            tag = AddressSpace.TAG_RAM;
        }
        if (0 == tag || 0 != (tag & AddressSpace.TAG_POLL)) {
            emitGetReg(il, reg);
            if (offset != 0) {
                il.append(new PUSH(contextCP, offset));
                il.append(new IADD());
            }
            // todo note this only detects REALLY tight polling; we'd be better off waiting to get a tag somehow (de-optimize perhaps)
            il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_checkPoll8", "(I)V")));
            tag &= ~AddressSpace.TAG_POLL;
        }
        switch (tag) {
            case AddressSpace.TAG_RAM:
                emitGetReg(il, reg);
                if (offset != 0) {
                    il.append(new PUSH(contextCP, offset));
                    il.append(new IADD());
                }
                il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_read8Ram", "(I)I")));
                break;
            case AddressSpace.TAG_SCRATCH:
                emitGetReg(il, reg);
                if (offset != 0) {
                    il.append(new PUSH(contextCP, offset));
                    il.append(new IADD());
                }
                il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_read8Scratch", "(I)I")));
                break;
            case AddressSpace.TAG_BIOS:
                emitGetReg(il, reg);
                if (offset != 0) {
                    il.append(new PUSH(contextCP, offset));
                    il.append(new IADD());
                }
                il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_read8Bios", "(I)I")));
                break;
            case AddressSpace.TAG_HW:
                emitGetReg(il, reg);
                if (offset != 0) {
                    il.append(new PUSH(contextCP, offset));
                    il.append(new IADD());
                }
                il.append(new INVOKESTATIC(contextCP.addMethodref(HW_CLASS, "read8", "(I)I")));
                break;
            default:
                emitGetReg(il, reg);
                if (offset != 0) {
                    il.append(new PUSH(contextCP, offset));
                    il.append(new IADD());
                }
                il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_read8", "(I)I")));
        }
    }

    public void emitReadMem16(InstructionList il, int address, boolean signed) {
        int tag = addressSpace.getTag(contextAddress) & READ_TAG_MASK;
        // we must do tag==0 since this means we haven't executed this statement yet
        if (tag == 0 || 0 != (tag & AddressSpace.TAG_POLL)) {
            il.append(new PUSH(contextCP, address));
            // todo note this only detects REALLY tight polling; we'd be better off waiting to get a tag somehow (de-optimize perhaps)
            il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_checkPoll16", "(I)V")));
        }
        rr.mem = null;
        if (0 == (address & 1)) {
            addressSpace.resolve(address, rr);
            if (rr.tag == AddressSpace.TAG_RAM) {
                il.append(new GETSTATIC(contextCP.addFieldref(ADDRESS_SPACE_CLASS, "ramD", "[I")));
                il.append(new PUSH(contextCP, rr.offset));
                il.append(new IALOAD());
            } else if (rr.tag == AddressSpace.TAG_SCRATCH) {
                il.append(new GETSTATIC(contextCP.addFieldref(ADDRESS_SPACE_CLASS, "scratch", "[I")));
                il.append(new PUSH(contextCP, rr.offset));
                il.append(new IALOAD());
            } else if (rr.tag == AddressSpace.TAG_BIOS) {
                il.append(new GETSTATIC(contextCP.addFieldref(ADDRESS_SPACE_CLASS, "bios", "[I")));
                il.append(new PUSH(contextCP, rr.offset));
                il.append(new IALOAD());
            } else if (rr.tag == AddressSpace.TAG_HW) {
                il.append(new PUSH(contextCP, address));
                il.append(new INVOKESTATIC(contextCP.addMethodref(HW_CLASS, "read16", "(I)I")));
                return;
            } else {
                il.append(new PUSH(contextCP, address));
                il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_read16", "(I)I")));
            }
        } else {
            il.append(new PUSH(contextCP, address));
            il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_read16", "(I)I")));
        }
        if (signed) {
            if (0 == (address & 2)) {
                il.append(new PUSH(contextCP, 16));
                il.append(new ISHL());
            }
            il.append(new PUSH(contextCP, 16));
            il.append(new ISHR());
        } else {
            if (0 != (address & 2)) {
                il.append(new PUSH(contextCP, 16));
                il.append(new ISHR());
            }
            il.append(new PUSH(contextCP, 0xffff));
            il.append(new IAND());
        }
    }

    public void emitReadMem16(InstructionList il, int reg, int offset) {
        int tag = addressSpace.getTag(contextAddress) & READ_TAG_MASK;
        // todo note that right now we don't tag usuallyRAMRegs, but we could in the future so this tag == 0 check is there for that
        if (tag == 0 && (0 != ((1 << reg) & MultiStageCompiler.Settings.usuallyRAMRegs))) {
            tag = AddressSpace.TAG_RAM;
        }
        if (0 == tag || 0 != (tag & AddressSpace.TAG_POLL)) {
            emitGetReg(il, reg);
            if (offset != 0) {
                il.append(new PUSH(contextCP, offset));
                il.append(new IADD());
            }
            // todo note this only detects REALLY tight polling; we'd be better off waiting to get a tag somehow (de-optimize perhaps)
            il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_checkPoll16", "(I)V")));
            tag &= ~AddressSpace.TAG_POLL;
        }
        switch (tag) {
            case AddressSpace.TAG_RAM:
                emitGetReg(il, reg);
                if (offset != 0) {
                    il.append(new PUSH(contextCP, offset));
                    il.append(new IADD());
                }
                il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_read16Ram", "(I)I")));
                break;
            case AddressSpace.TAG_SCRATCH:
                emitGetReg(il, reg);
                if (offset != 0) {
                    il.append(new PUSH(contextCP, offset));
                    il.append(new IADD());
                }
                il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_read16Scratch", "(I)I")));
                break;
            case AddressSpace.TAG_BIOS:
                emitGetReg(il, reg);
                if (offset != 0) {
                    il.append(new PUSH(contextCP, offset));
                    il.append(new IADD());
                }
                il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_read16Bios", "(I)I")));
                break;
            case AddressSpace.TAG_HW:
                emitGetReg(il, reg);
                if (offset != 0) {
                    il.append(new PUSH(contextCP, offset));
                    il.append(new IADD());
                }
                il.append(new INVOKESTATIC(contextCP.addMethodref(HW_CLASS, "read16", "(I)I")));
                break;
            default:
                emitGetReg(il, reg);
                if (offset != 0) {
                    il.append(new PUSH(contextCP, offset));
                    il.append(new IADD());
                }
                il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_read16", "(I)I")));
        }
    }

    public void emitReadMem32(InstructionList il, int address, boolean forceAlign) {
        int tag = addressSpace.getTag(contextAddress) & READ_TAG_MASK;
        // we must do tag==0 since this means we haven't executed this statement yet
        if (tag == 0) {
            // this only calls _checkPoll32 sometimes
            il.append(new PUSH(contextCP, contextAddress));
            il.append(new PUSH(contextCP, address));
            il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_tagAddressAccessRead32", "(II)V")));
        } else if (0 != (tag & AddressSpace.TAG_POLL)) {
            il.append(new PUSH(contextCP, address));
            // todo note this only detects REALLY tight polling; we'd be better off waiting to get a tag somehow (de-optimize perhaps)
            il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_checkPoll32", "(I)V")));
        }
        rr.mem = null;
        if (forceAlign) {
            address &= ~3;
        }
        if (0 == (address & 3)) {
            addressSpace.resolve(address, rr);
            if (rr.tag == AddressSpace.TAG_RAM) {
                il.append(new GETSTATIC(contextCP.addFieldref(ADDRESS_SPACE_CLASS, "ramD", "[I")));
                il.append(new PUSH(contextCP, rr.offset));
                il.append(new IALOAD());
            } else if (rr.tag == AddressSpace.TAG_SCRATCH) {
                il.append(new GETSTATIC(contextCP.addFieldref(ADDRESS_SPACE_CLASS, "scratch", "[I")));
                il.append(new PUSH(contextCP, rr.offset));
                il.append(new IALOAD());
            } else if (rr.tag == AddressSpace.TAG_BIOS) {
                il.append(new GETSTATIC(contextCP.addFieldref(ADDRESS_SPACE_CLASS, "bios", "[I")));
                il.append(new PUSH(contextCP, rr.offset));
                il.append(new IALOAD());
            } else if (rr.tag == AddressSpace.TAG_HW) {
                il.append(new PUSH(contextCP, address));
                il.append(new INVOKESTATIC(contextCP.addMethodref(HW_CLASS, "read32", "(I)I")));
            } else {
                il.append(new PUSH(contextCP, address));
                il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_read32", "(I)I")));
            }
        } else {
            il.append(new PUSH(contextCP, address));
            il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_read32", "(I)I")));
        }
    }

    public void emitReadMem32(InstructionList il, int reg, int offset, boolean forceAlign) {
        int tag = addressSpace.getTag(contextAddress) & READ_TAG_MASK;
        // todo note that right now we don't tag usuallyRAMRegs, but we could in the future so this tag == 0 check is there for that
        if (tag == 0 && (0 != ((1 << reg) & MultiStageCompiler.Settings.usuallyRAMRegs))) {
            tag = AddressSpace.TAG_RAM;
        }
        // we must do tag==0 since this means we haven't executed this statement yet
        if (tag == 0) {
            il.append(new PUSH(contextCP, contextAddress));
            emitGetReg(il, reg);
            if (offset != 0) {
                il.append(new PUSH(contextCP, offset));
                il.append(new IADD());
            }
            // this only calls _checkPoll32 sometimes
            il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_tagAddressAccessRead32", "(II)V")));
        } else if (0 != (tag & AddressSpace.TAG_POLL)) {
            emitGetReg(il, reg);
            if (offset != 0) {
                il.append(new PUSH(contextCP, offset));
                il.append(new IADD());
            }
            // todo note this only detects REALLY tight polling; we'd be better off waiting to get a tag somehow (de-optimize perhaps)
            il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_checkPoll32", "(I)V")));
            tag &= ~AddressSpace.TAG_POLL;
        }
        switch (tag) {
            case AddressSpace.TAG_RAM:
                il.append(new GETSTATIC(contextCP.addFieldref(ADDRESS_SPACE_CLASS, "ramD", "[I")));
                emitGetReg(il, reg);
                if (offset != 0) {
                    il.append(new PUSH(contextCP, offset));
                    il.append(new IADD());
                }
                il.append(new PUSH(contextCP, AddressSpace.RAM_AND));
                il.append(new IAND());
                il.append(new PUSH(contextCP, 2));
                il.append(new ISHR());
                il.append(new IALOAD());
                break;
            case AddressSpace.TAG_SCRATCH:
                il.append(new GETSTATIC(contextCP.addFieldref(ADDRESS_SPACE_CLASS, "scratch", "[I")));
                emitGetReg(il, reg);
                if (offset != 0) {
                    il.append(new PUSH(contextCP, offset));
                    il.append(new IADD());
                }
                il.append(new PUSH(contextCP, AddressSpace.SCRATCH_XOR));
                il.append(new IXOR());
                il.append(new PUSH(contextCP, 2));
                il.append(new ISHR());
                il.append(new IALOAD());
                break;
            case AddressSpace.TAG_BIOS:
                il.append(new GETSTATIC(contextCP.addFieldref(ADDRESS_SPACE_CLASS, "bios", "[I")));
                emitGetReg(il, reg);
                if (offset != 0) {
                    il.append(new PUSH(contextCP, offset));
                    il.append(new IADD());
                }
                il.append(new PUSH(contextCP, AddressSpace.BIOS_XOR));
                il.append(new IXOR());
                il.append(new PUSH(contextCP, 2));
                il.append(new ISHR());
                il.append(new IALOAD());
                break;
            case AddressSpace.TAG_HW:
                emitGetReg(il, reg);
                if (offset != 0) {
                    il.append(new PUSH(contextCP, offset));
                    il.append(new IADD());
                }
                if (forceAlign) {
                    il.append(new PUSH(contextCP, 0xfffffffc));
                    il.append(new IAND());
                }
                il.append(new INVOKESTATIC(contextCP.addMethodref(HW_CLASS, "read32", "(I)I")));
                break;
            default:
                emitGetReg(il, reg);
                if (offset != 0) {
                    il.append(new PUSH(contextCP, offset));
                    il.append(new IADD());
                }
                if (forceAlign) {
                    il.append(new PUSH(contextCP, 0xfffffffc));
                    il.append(new IAND());
                }
                il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_read32", "(I)I")));
        }
    }

    public void emitWriteMem8(InstructionList il, int address, InstructionList il2) {
        InstructionList il3 = new InstructionList();
        emitReadMem32(il3, address, true);
        switch (address & 3) {
            case 0:
                il3.append(new PUSH(contextCP, 0xffffff00));
                il3.append(new IAND());
                il3.append(il2);
                il3.append(new PUSH(contextCP, 0xff));
                il3.append(new IAND());
                il3.append(new IOR());
                break;
            case 1:
                il3.append(new PUSH(contextCP, 0xffff00ff));
                il3.append(new IAND());
                il3.append(il2);
                il3.append(new PUSH(contextCP, 0xff));
                il3.append(new IAND());
                il3.append(new PUSH(contextCP, 8));
                il3.append(new ISHL());
                il3.append(new IOR());
                break;
            case 2:
                il3.append(new PUSH(contextCP, 0xff00ffff));
                il3.append(new IAND());
                il3.append(il2);
                il3.append(new PUSH(contextCP, 0xff));
                il3.append(new IAND());
                il3.append(new PUSH(contextCP, 16));
                il3.append(new ISHL());
                il3.append(new IOR());
                break;
            default:
                il3.append(new PUSH(contextCP, 0xffffff));
                il3.append(new IAND());
                il3.append(il2);
                il3.append(new PUSH(contextCP, 24));
                il3.append(new ISHL());
                il3.append(new IOR());
        }
        emitWriteMem32(il, address, il3, true);
        il3.dispose();
    }

    public void emitWriteMem8(InstructionList il, int reg, int offset) {
        int tag = addressSpace.getTag(contextAddress) & READ_TAG_MASK;
        // todo note that right now we don't tag usuallyRAMRegs, but we could in the future so this tag == 0 check is there for that
        if (tag == 0 && (0 != ((1 << reg) & MultiStageCompiler.Settings.usuallyRAMRegs))) {
            tag = AddressSpace.TAG_RAM;
        }
        switch (tag) {
            case AddressSpace.TAG_RAM:
                emitGetReg(il, reg);
                if (offset != 0) {
                    il.append(new PUSH(contextCP, offset));
                    il.append(new IADD());
                }
                il.append(new SWAP());
                il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_write8Ram", "(II)V")));
                break;
            case AddressSpace.TAG_SCRATCH:
                emitGetReg(il, reg);
                if (offset != 0) {
                    il.append(new PUSH(contextCP, offset));
                    il.append(new IADD());
                }
                il.append(new SWAP());
                il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_write8Scratch", "(II)V")));
                break;
            case AddressSpace.TAG_BIOS:
                emitGetReg(il, reg);
                if (offset != 0) {
                    il.append(new PUSH(contextCP, offset));
                    il.append(new IADD());
                }
                il.append(new SWAP());
                il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_write8Bios", "(II)V")));
                break;
            case AddressSpace.TAG_HW:
                emitGetReg(il, reg);
                if (offset != 0) {
                    il.append(new PUSH(contextCP, offset));
                    il.append(new IADD());
                }
                il.append(new SWAP());
                il.append(new INVOKESTATIC(contextCP.addMethodref(HW_CLASS, "write8", "(II)V")));
                break;
            default:
                emitGetReg(il, reg);
                if (offset != 0) {
                    il.append(new PUSH(contextCP, offset));
                    il.append(new IADD());
                }
                il.append(new SWAP());
                il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_write8", "(II)V")));
        }
    }

    public void emitWriteMem16(InstructionList il, int address, InstructionList il2) {
        if ((address & 1) != 0) {
            il.append(new PUSH(contextCP, address));
            il.append(il2);
            il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_write16", "(II)V")));
        } else {
            InstructionList il3 = new InstructionList();
            // il3.stack(0) = oldvalue
            emitReadMem32(il3, address, true);
            switch (address & 3) {
                case 0:
                    // il3.stack(1) = mask;
                    il3.append(new PUSH(contextCP, 0xffff0000));
                    il3.append(new IAND());
                    // il3.stack(0) = maskedOldValue;
                    il3.append(il2);
                    // il3.stack(1) = newvalue;
                    il3.append(new PUSH(contextCP, 0xffff));
                    // il3.stack(2)  = mask;
                    il3.append(new IAND());
                    // il3.stack(1) = maskedNewValue;
                    il3.append(new IOR());
                    // il3.stack(0) = newMemValue
                    break;
                default:
                    il3.append(new PUSH(contextCP, 0xffff));
                    il3.append(new IAND());
                    il3.append(il2);
                    il3.append(new PUSH(contextCP, 16));
                    il3.append(new ISHL());
                    il3.append(new IOR());
            }
            emitWriteMem32(il, address, il3, true);
            il3.dispose();
        }
    }

    public void emitWriteMem16(InstructionList il, int reg, int offset) {
        int tag = addressSpace.getTag(contextAddress) & READ_TAG_MASK;
        // todo note that right now we don't tag usuallyRAMRegs, but we could in the future so this tag == 0 check is there for that
        if (tag == 0 && (0 != ((1 << reg) & MultiStageCompiler.Settings.usuallyRAMRegs))) {
            tag = AddressSpace.TAG_RAM;
        }
        switch (tag) {
            case AddressSpace.TAG_RAM:
                emitGetReg(il, reg);
                if (offset != 0) {
                    il.append(new PUSH(contextCP, offset));
                    il.append(new IADD());
                }
                il.append(new SWAP());
                il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_write16Ram", "(II)V")));
                break;
            case AddressSpace.TAG_SCRATCH:
                emitGetReg(il, reg);
                if (offset != 0) {
                    il.append(new PUSH(contextCP, offset));
                    il.append(new IADD());
                }
                il.append(new SWAP());
                il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_write16Scratch", "(II)V")));
                break;
            case AddressSpace.TAG_BIOS:
                emitGetReg(il, reg);
                if (offset != 0) {
                    il.append(new PUSH(contextCP, offset));
                    il.append(new IADD());
                }
                il.append(new SWAP());
                il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_write16Bios", "(II)V")));
                break;
            case AddressSpace.TAG_HW:
                emitGetReg(il, reg);
                if (offset != 0) {
                    il.append(new PUSH(contextCP, offset));
                    il.append(new IADD());
                }
                il.append(new SWAP());
                il.append(new INVOKESTATIC(contextCP.addMethodref(HW_CLASS, "write16", "(II)V")));
                break;
            default:
                emitGetReg(il, reg);
                if (offset != 0) {
                    il.append(new PUSH(contextCP, offset));
                    il.append(new IADD());
                }
                il.append(new SWAP());
                il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_write16", "(II)V")));
        }
    }

    public void emitWriteMem32(InstructionList il, int address, InstructionList il2, boolean forceAlign) {
        rr.mem = null;
        if (forceAlign) {
            address &= ~3;
        }
        if (0 == (address & 3)) {
            addressSpace.resolve(address, rr);
            if (rr.tag == AddressSpace.TAG_RAM) {
                il.append(new GETSTATIC(contextCP.addFieldref(ADDRESS_SPACE_CLASS, "ramD", "[I")));
                il.append(new PUSH(contextCP, rr.offset));
                il.append(il2);
                il.append(new IASTORE());
            } else if (rr.tag == AddressSpace.TAG_SCRATCH) {
                il.append(new GETSTATIC(contextCP.addFieldref(ADDRESS_SPACE_CLASS, "scratch", "[I")));
                il.append(new PUSH(contextCP, rr.offset));
                il.append(il2);
                il.append(new IASTORE());
            } else if (rr.tag == AddressSpace.TAG_BIOS) {
                // just read the address to make sure TAG is correct
                il.append(new GETSTATIC(contextCP.addFieldref(ADDRESS_SPACE_CLASS, "bios", "[I")));
                il.append(new PUSH(contextCP, rr.offset));
                il.append(il2);
                il.append(new IALOAD());
                il.append(new POP());
            } else if (rr.tag == AddressSpace.TAG_HW) {
                il.append(new PUSH(contextCP, address));
                il.append(il2);
                il.append(new INVOKESTATIC(contextCP.addMethodref(HW_CLASS, "write32", "(II)V")));
            } else {
                il.append(new PUSH(contextCP, address));
                il.append(il2);
                il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_write32", "(II)V")));
            }
        } else {
            il.append(new PUSH(contextCP, address));
            il.append(il2);
            il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_write32", "(II)V")));
        }
    }

    public void emitWriteMem32(InstructionList il, int reg, int offset, InstructionList il2, boolean forceAlign) {
        int tag = addressSpace.getTag(contextAddress) & READ_TAG_MASK;
        // todo note that right now we don't tag usuallyRAMRegs, but we could in the future so this tag == 0 check is there for that
        if (tag == 0 && (0 != ((1 << reg) & MultiStageCompiler.Settings.usuallyRAMRegs))) {
            tag = AddressSpace.TAG_RAM;
        }
        switch (tag) {
            case AddressSpace.TAG_RAM:
                il.append(new GETSTATIC(contextCP.addFieldref(ADDRESS_SPACE_CLASS, "ramD", "[I")));
                emitGetReg(il, reg);
                if (offset != 0) {
                    il.append(new PUSH(contextCP, offset));
                    il.append(new IADD());
                }
                il.append(new PUSH(contextCP, AddressSpace.RAM_AND));
                il.append(new IAND());
                il.append(new PUSH(contextCP, 2));
                il.append(new ISHR());
                il.append(il2);
                il.append(new IASTORE());
                break;
            case AddressSpace.TAG_SCRATCH:
                il.append(new GETSTATIC(contextCP.addFieldref(ADDRESS_SPACE_CLASS, "scratch", "[I")));
                emitGetReg(il, reg);
                if (offset != 0) {
                    il.append(new PUSH(contextCP, offset));
                    il.append(new IADD());
                }
                il.append(new PUSH(contextCP, AddressSpace.SCRATCH_XOR));
                il.append(new IXOR());
                il.append(new PUSH(contextCP, 2));
                il.append(new ISHR());
                il.append(il2);
                il.append(new IASTORE());
                break;
            case AddressSpace.TAG_BIOS:
                il.append(new GETSTATIC(contextCP.addFieldref(ADDRESS_SPACE_CLASS, "bios", "[I")));
                emitGetReg(il, reg);
                if (offset != 0) {
                    il.append(new PUSH(contextCP, offset));
                    il.append(new IADD());
                }
                il.append(new PUSH(contextCP, AddressSpace.BIOS_XOR));
                il.append(new IXOR());
                il.append(new PUSH(contextCP, 2));
                il.append(new ISHR());
                il.append(il2);
                // just read to check TAG_ is correct
                il.append(new IALOAD());
                il.append(new POP());
                break;
            case AddressSpace.TAG_HW:
                emitGetReg(il, reg);
                if (offset != 0) {
                    il.append(new PUSH(contextCP, offset));
                    il.append(new IADD());
                }
                if (forceAlign) {
                    il.append(new PUSH(contextCP, 0xfffffffc));
                    il.append(new IAND());
                }
                il.append(il2);
                il.append(new INVOKESTATIC(contextCP.addMethodref(HW_CLASS, "write32", "(II)V")));
                break;
            default:
                emitGetReg(il, reg);
                if (offset != 0) {
                    il.append(new PUSH(contextCP, offset));
                    il.append(new IADD());
                }
                if (forceAlign) {
                    il.append(new PUSH(contextCP, 0xfffffffc));
                    il.append(new IAND());
                }
                il.append(il2);
                il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_write32", "(II)V")));
        }
    }

    protected boolean shouldPrintCode() {
        return true;
    }

    public void fixupUnwrittenRegs(CodeUnit unit, int pc) {
        // todo we could keep the info we needed here about in soft reference I guess; even so we need this code
        assert r3000.isExecutionThread();
        assert intendedForExecutionThread;
        FlowAnalyzer.FlowInfo flowInfo = unit.getFlowInfo(analyzer, true);
        getRegsOffset = (pc - unit.base) >> 2;
        initBlockStructures(flowInfo);
        if (0 != (CRAtOffset & 0x00000002)) {
            MultiStageCompiler.reg_1 = regsAtOffset[1];
        }
        if (0 != (CRAtOffset & 0x00000004)) {
            MultiStageCompiler.reg_2 = regsAtOffset[2];
        }
        if (0 != (CRAtOffset & 0x00000008)) {
            MultiStageCompiler.reg_3 = regsAtOffset[3];
        }
        if (0 != (CRAtOffset & 0x00000010)) {
            MultiStageCompiler.reg_4 = regsAtOffset[4];
        }
        if (0 != (CRAtOffset & 0x00000020)) {
            MultiStageCompiler.reg_5 = regsAtOffset[5];
        }
        if (0 != (CRAtOffset & 0x00000040)) {
            MultiStageCompiler.reg_6 = regsAtOffset[6];
        }
        if (0 != (CRAtOffset & 0x00000080)) {
            MultiStageCompiler.reg_7 = regsAtOffset[7];
        }
        if (0 != (CRAtOffset & 0x00000100)) {
            MultiStageCompiler.reg_8 = regsAtOffset[8];
        }
        if (0 != (CRAtOffset & 0x00000200)) {
            MultiStageCompiler.reg_9 = regsAtOffset[9];
        }
        if (0 != (CRAtOffset & 0x00000400)) {
            MultiStageCompiler.reg_10 = regsAtOffset[10];
        }
        if (0 != (CRAtOffset & 0x00000800)) {
            MultiStageCompiler.reg_11 = regsAtOffset[11];
        }
        if (0 != (CRAtOffset & 0x00001000)) {
            MultiStageCompiler.reg_12 = regsAtOffset[12];
        }
        if (0 != (CRAtOffset & 0x00002000)) {
            MultiStageCompiler.reg_12 = regsAtOffset[13];
        }
        if (0 != (CRAtOffset & 0x00004000)) {
            MultiStageCompiler.reg_14 = regsAtOffset[14];
        }
        if (0 != (CRAtOffset & 0x00008000)) {
            MultiStageCompiler.reg_15 = regsAtOffset[15];
        }
        if (0 != (CRAtOffset & 0x00010000)) {
            MultiStageCompiler.reg_16 = regsAtOffset[16];
        }
        if (0 != (CRAtOffset & 0x00020000)) {
            MultiStageCompiler.reg_17 = regsAtOffset[17];
        }
        if (0 != (CRAtOffset & 0x00040000)) {
            MultiStageCompiler.reg_18 = regsAtOffset[18];
        }
        if (0 != (CRAtOffset & 0x00080000)) {
            MultiStageCompiler.reg_19 = regsAtOffset[19];
        }
        if (0 != (CRAtOffset & 0x00100000)) {
            MultiStageCompiler.reg_20 = regsAtOffset[20];
        }
        if (0 != (CRAtOffset & 0x00200000)) {
            MultiStageCompiler.reg_21 = regsAtOffset[21];
        }
        if (0 != (CRAtOffset & 0x00400000)) {
            MultiStageCompiler.reg_22 = regsAtOffset[22];
        }
        if (0 != (CRAtOffset & 0x00800000)) {
            MultiStageCompiler.reg_23 = regsAtOffset[23];
        }
        if (0 != (CRAtOffset & 0x01000000)) {
            MultiStageCompiler.reg_24 = regsAtOffset[24];
        }
        if (0 != (CRAtOffset & 0x02000000)) {
            MultiStageCompiler.reg_25 = regsAtOffset[25];
        }
        if (0 != (CRAtOffset & 0x04000000)) {
            MultiStageCompiler.reg_26 = regsAtOffset[26];
        }
        if (0 != (CRAtOffset & 0x08000000)) {
            MultiStageCompiler.reg_27 = regsAtOffset[27];
        }
        if (0 != (CRAtOffset & 0x10000000)) {
            MultiStageCompiler.reg_28 = regsAtOffset[28];
        }
        if (0 != (CRAtOffset & 0x20000000)) {
            MultiStageCompiler.reg_29 = regsAtOffset[29];
        }
        if (0 != (CRAtOffset & 0x40000000)) {
            MultiStageCompiler.reg_30 = regsAtOffset[30];
        }
        if (0 != (CRAtOffset & 0x80000000)) {
            MultiStageCompiler.reg_31 = regsAtOffset[31];
        }
    }
}
