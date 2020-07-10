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
import org.apache.bcel.classfile.ConstantPool;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.*;
import org.apache.log4j.Logger;
import org.jpsx.api.InvalidConfigurationException;
import org.jpsx.api.components.core.addressspace.AddressSpace;
import org.jpsx.api.components.core.cpu.CPUInstruction;
import org.jpsx.api.components.core.cpu.CompilationContext;
import org.jpsx.api.components.core.cpu.R3000;
import org.jpsx.bootstrap.util.CollectionsFactory;
import org.jpsx.runtime.components.core.CoreComponentConnections;
import org.jpsx.runtime.components.core.R3000Impl;
import org.jpsx.runtime.util.ClassUtil;
import org.jpsx.runtime.util.MiscUtil;
import org.jpsx.runtime.util.Timing;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Set;

// todo background generators need to save the state as atomically as possible (copying the instructions basically)
// todo the FlowInfo needs to be in sync too - the fact that we share that though means that we can (in the future)
// todo quickly detect differences between the actual code and the cached flow graph (without having to regen the
// todo flow graph)... stage2 generators should probably take a hash of the code from stage 1 to make sure all is kosher
public class Stage1Generator implements CompilationContext {
    public static final Logger log = Logger.getLogger("Stage1");

    protected static final String CLASS_NAME_PREFIX = "_1";
    protected static final String STATIC_METHOD = "s";
    protected static final String NORMAL_METHOD = "e";
    protected static final String UNINLINED_METHOD_PREFIX = "_";

    protected final FlowAnalyzer analyzer = new FlowAnalyzer();

    // state shared with sub-class
    protected final int[] opCodes = new int[MultiStageCompiler.Settings.maxR3000InstructionsPerUnit];
    protected final CPUInstruction[] instructions = new CPUInstruction[MultiStageCompiler.Settings.maxR3000InstructionsPerUnit];
    protected final int[] regsRead = new int[MultiStageCompiler.Settings.maxR3000InstructionsPerUnit];
    protected final int[] regsWritten = new int[MultiStageCompiler.Settings.maxR3000InstructionsPerUnit];
    protected final int[] flags = new int[MultiStageCompiler.Settings.maxR3000InstructionsPerUnit];
    protected PrintStream codeWriter;

    private final int[] simulateRegs = new int[32];
    private final InstructionList[] instructionLists = new InstructionList[MultiStageCompiler.Settings.maxR3000InstructionsPerUnit];
    private final HashMap extraInstructionLists = new HashMap();
    private final InstructionHandle[] endHandles = MultiStageCompiler.Settings.printCode ? (new InstructionHandle[MultiStageCompiler.Settings.maxR3000InstructionsPerUnit]) : null;
    private final InstructionList printCodeIL = new InstructionList();

    private String codeFilename;

    private final AddressSpace addressSpace = CoreComponentConnections.ADDRESS_SPACE.resolve();
    private final R3000 r3000 = CoreComponentConnections.R3000.resolve();

    /**
     * Generators are not thread safe, and they also can assert in pertinent places that they are being called from the correct
     * thread, as this stuff gets a bit complicated/messy.
     */
    protected final boolean intendedForExecutionThread;

    public Stage1Generator(String codeFilename, boolean intendedForExecutionThread) {
        this.codeFilename = codeFilename;
        this.intendedForExecutionThread = intendedForExecutionThread;
        if (MultiStageCompiler.Settings.printCode) {
            try {
                codeWriter = new PrintStream(new FileOutputStream(codeFilename));
            } catch (IOException e) {
                throw new InvalidConfigurationException("can't open " + codeFilename + " for output", e);
            }
        }
    }

    protected final String R3000_CLASS = r3000.getClass().getName();
    protected static final String COMPILER_CLASS = MultiStageCompiler.class.getName();
    protected final String ADDRESS_SPACE_CLASS = addressSpace.getMainStaticInterfaceClassName();
    protected final String HW_CLASS = addressSpace.getHardwareStaticInterfaceClassName();

    protected static final String EXECUTABLE_CLASS = Executable.class.getName();
    protected static final String CODEUNIT_CLASS = CodeUnit.class.getName();
    protected static final String CODEUNIT_SIGNATURE = ClassUtil.signatureOfClass(CODEUNIT_CLASS);

    protected static final int LOCAL_RETADDR = 0;
    protected static final int LOCAL_JUMP = 1;
    protected static final int LOCAL_PRIVATE_TEMP = 2;
    protected static final int LOCAL_TEMP0 = 3;
    protected static final int LOCAL_LAST = 8;

    protected static final int MINIMUM_INSTRUCTIONS_PER_METHOD = 4;

    protected static final int ALL_REGS = 0xffffffff;
    protected static final int WRITABLE_REGS = 0xfffffffe;

    protected String getClassNamePrefix(CodeUnit unit) {
        return CLASS_NAME_PREFIX;
    }

    protected int getMaxMethodInstructionCount() {
        return MultiStageCompiler.Settings.maxMethodInstructionCount;
    }

    protected boolean shouldPrintCode() {
        return true;
    }

    protected void emitMethodHeader(InstructionList il) {
        if (R3000Impl.Settings.traceExecutionFlow) {
            il.append(new PUSH(contextCP, contextBase));
            il.append(new ILOAD(LOCAL_RETADDR));
            il.append(new ILOAD(LOCAL_JUMP));
            il.append(new INVOKESTATIC(contextCP.addMethodref(COMPILER_CLASS, "traceEnterUnit", "(IIZ)V")));
        }
        FieldGen fg = new FieldGen(Constants.ACC_PUBLIC | Constants.ACC_STATIC, Type.getType(CODEUNIT_SIGNATURE), "unit", contextCP);
        contextClassGen.addField(fg.getField());
        il.append(new GETSTATIC(contextCP.addFieldref(contextClassGen.getClassName(), "unit", CODEUNIT_SIGNATURE)));
        il.append(new GETFIELD(contextCP.addFieldref(CODEUNIT_CLASS, "useStage2", "Z")));
        IFEQ ifeq = new IFEQ(null);
        il.append(ifeq);
        il.append(new ILOAD(0));
        il.append(new ILOAD(1));
        il.append(new INVOKESTATIC(contextCP.addMethodref(getClassName(Stage2Generator.CLASS_NAME_PREFIX, contextBase), STATIC_METHOD, "(IZ)I")));
        il.append(new IRETURN());
        ifeq.setTarget(il.append(new GETSTATIC(contextCP.addFieldref(contextClassGen.getClassName(), "unit", CODEUNIT_SIGNATURE))));
        il.append(new GETFIELD(contextCP.addFieldref(CODEUNIT_CLASS, "count", "I")));
        il.append(new ISTORE(LOCAL_TEMP0));
        il.append(new ILOAD(LOCAL_TEMP0));
        IFLE ifle = new IFLE(null);
        il.append(ifle);
        il.append(new IINC(LOCAL_TEMP0, -1));
        il.append(new GETSTATIC(contextCP.addFieldref(contextClassGen.getClassName(), "unit", CODEUNIT_SIGNATURE)));
        il.append(new ILOAD(LOCAL_TEMP0));
        il.append(new PUTFIELD(contextCP.addFieldref(CODEUNIT_CLASS, "count", "I")));
        GOTO gt = new GOTO(null);
        il.append(gt);
        ifle.setTarget(il.append(new GETSTATIC(contextCP.addFieldref(contextClassGen.getClassName(), "unit", CODEUNIT_SIGNATURE))));
        il.append(new INVOKEVIRTUAL(contextCP.addMethodref(CODEUNIT_CLASS, "countComplete", "()V")));
        gt.setTarget(il.append(new NOP()));

    }

    protected void emitBlockHeader(InstructionList il) {
        contextDelaySlotEmitted = false;
    }

    protected void emitBlockFooter(InstructionList il) {
        if (MultiStageCompiler.Settings.printCode && shouldPrintCode()) {
            // make sure we got all the code
            int size = contextBlock.size;
            if (size != 0) {
                if (contextBlock.branchOut != null && contextBlock.includesDelaySlot) {
                    // don't want to count the delay slot, which will be output by the branch anyway
                    size--;
                }
                assert size > 0;
                endHandles[contextBlock.offset + size - 1] = il.getEnd();
            }
        }
        contextDelaySlotEmitted = false;
    }

    protected void initBlockStructures(FlowAnalyzer.FlowInfo flowInfo) {
        noInterruptCheck = false;
        extraInstructionLists.clear();
        for (FlowAnalyzer.BasicBlock block = flowInfo.root; block != null; block = block.next) {
            if (block.size != 0) {
                if (instructionLists[block.offset] == null) {
                    instructionLists[block.offset] = new InstructionList();
                } else {
                    // instruction list should be empty
                    assert null == instructionLists[block.offset].getStart();
                }
            }
            for (int offset = block.offset; offset < block.offset + block.size; offset++) {
                int address = flowInfo.base + offset * 4;
                int ci = addressSpace.internalRead32(address);
                opCodes[offset] = ci;
                CPUInstruction inst = r3000.decodeInstruction(ci);
                instructions[offset] = inst;
                int iFlags = inst.getFlags();
                flags[offset] = iFlags;
                regsRead[offset] = 0;
                regsWritten[offset] = 0;
                if (0 != (iFlags & CPUInstruction.FLAG_READS_RS)) {
                    regsRead[offset] |= 1 << R3000.Util.bits_rs(ci);
                }
                if (0 != (iFlags & CPUInstruction.FLAG_READS_RT)) {
                    regsRead[offset] |= 1 << R3000.Util.bits_rt(ci);
                }
                if (0 != (iFlags & CPUInstruction.FLAG_WRITES_RT)) {
                    regsWritten[offset] |= 1 << R3000.Util.bits_rt(ci);
                }
                if (0 != (iFlags & CPUInstruction.FLAG_WRITES_RD)) {
                    regsWritten[offset] |= 1 << R3000.Util.bits_rd(ci);
                }
                if (MultiStageCompiler.Settings.biosInterruptWorkaround) {
                    if (0 != (regsWritten[offset] & 0x0c000000)) {
                        noInterruptCheck = true;
                    }
                }
                if (0 != (iFlags & CPUInstruction.FLAG_REQUIRES_COMPLETE_INTERPRETER_STATE)) {
                    regsRead[offset] = ALL_REGS;
                    regsWritten[offset] |= WRITABLE_REGS;
                } else if (0 != (iFlags & CPUInstruction.FLAG_LINK)) {
                    regsRead[offset] = ALL_REGS;
                    regsWritten[offset] |= ~MultiStageCompiler.Settings.savedOnCallRegs;
                }
                regsWritten[offset] &= WRITABLE_REGS;
            }
        }
        if (MultiStageCompiler.Settings.biosInterruptWorkaround && noInterruptCheck) {
            if (log.isDebugEnabled()) {
                log.debug("*** BIOS BUG WORKAROUND FOR " + MiscUtil.toHex(flowInfo.base, 8));
            }
        }
    }

    public String getClassName(String prefix, int base) {
        return prefix + MiscUtil.toHex(base, 8);
    }

    protected void emitICodeUnitMethods() {
        contextClassGen.addInterface(EXECUTABLE_CLASS);
        InstructionList il = new InstructionList();
        MethodGen mg = new MethodGen(Constants.ACC_PUBLIC, Type.INT, new Type[]{Type.INT, Type.BOOLEAN}, new String[]{"retAddr", "jump"}, "e", contextClassGen.getClassName(), il, contextCP);

        il.append(new ILOAD(1));
        il.append(new ILOAD(2));
        il.append(new INVOKESTATIC(contextCP.addMethodref(contextClassGen.getClassName(), STATIC_METHOD, "(IZ)I")));
        il.append(new IRETURN());
        mg.setMaxLocals();
        mg.setMaxStack();
        contextClassGen.addMethod(mg.getMethod());
        il.dispose();
    }

    protected void emitMethods(FlowAnalyzer.FlowInfo flowInfo) {
        emitICodeUnitMethods();
        emitStaticExecuteMethod(flowInfo);
    }

    protected void emitStaticExecuteMethod(FlowAnalyzer.FlowInfo flowInfo) {
        InstructionList methodIL = new InstructionList();
        MethodGen mg = new MethodGen(Constants.ACC_STATIC | Constants.ACC_PUBLIC, Type.INT, new Type[]{Type.INT, Type.BOOLEAN}, new String[]{"retAddr", "jump"}, STATIC_METHOD, contextClassGen.getClassName(), methodIL, contextCP);

        if (R3000Impl.Settings.skipShell && !R3000Impl.shellHit && contextBase == 0x80030000) {
            R3000Impl.shellHit = true;
            methodIL.append(new ILOAD(LOCAL_RETADDR));
            methodIL.append(new IRETURN());
        } else {
            emitDebugs(methodIL);

            emitMethodHeader(methodIL);

            Set<FlowAnalyzer.BasicBlock> methodBlocks = CollectionsFactory.newHashSet();

            int maxCount = getMaxMethodInstructionCount();
            if (flowInfo.instructionCount > maxCount) {
                if (log.isDebugEnabled()) {
                    log.debug("Too many instructions in " + Integer.toHexString(flowInfo.base));
                }
                // too many instructions, we want to sort basic blocks
                // by size, and choose to inline the code up to the branch
                // from each one
                //System.out.println("sorting for "+flowInfo.instructionCount);
                FlowAnalyzer.BasicBlock sizedBlocks[] = new FlowAnalyzer.BasicBlock[flowInfo.blockCount];
                int index = 0;
                for (FlowAnalyzer.BasicBlock block = flowInfo.root; block != null; block = block.next) {
                    sizedBlocks[index++] = block;
                }
                Arrays.sort(sizedBlocks, new Comparator<FlowAnalyzer.BasicBlock>() {
                    public int compare(FlowAnalyzer.BasicBlock b1, FlowAnalyzer.BasicBlock b2) {
                        return getSizeWithoutBranch(b2) - getSizeWithoutBranch(b1);
                    }
                });
                int instructionCount = flowInfo.instructionCount;
                for (int i = 0; i < sizedBlocks.length && instructionCount > maxCount; i++) {
                    int size = getSizeWithoutBranch(sizedBlocks[i]);
                    if (size < MINIMUM_INSTRUCTIONS_PER_METHOD) {
                        break;
                    }
                    //System.out.println("collapsing block "+(i+1)+"/"+sizedBlocks.length+" size "+sizedBlocks[i].size);
                    instructionCount -= size;
                    methodBlocks.add(sizedBlocks[i]);
                    if (log.isDebugEnabled()) {
                        log.debug("  must call to "+sizedBlocks[i]+" size "+getSizeWithoutBranch(sizedBlocks[i]));
                    }
                }
                //System.out.println("final instruction count "+instructionCount);
            }

            contextMethodGen = mg;
            for (FlowAnalyzer.BasicBlock block = flowInfo.root; block != null; block = block.next) {
                contextBlock = block;
                if (MultiStageCompiler.Settings.printRare && block.branchOut != null && !block.includesDelaySlot) {
                    System.out.println(block);
                }

                InstructionList blockIL = getInstructionList(block);
                emitBlockHeader(blockIL);
                if (block.type == FlowAnalyzer.BasicBlock.NORMAL) {
                    int size = block.size;
                    if (block.branchOut != null && block.includesDelaySlot) {
                        // don't want to count the delay slot, which will be output by the branch anyway
                        size--;
                    }
                    if (methodBlocks.contains(block)) {
                        String methodname = UNINLINED_METHOD_PREFIX + block.offset;
                        InstructionList innerMethodIL = new InstructionList();
                        MethodGen mgInner = new MethodGen(Constants.ACC_STATIC | Constants.ACC_PUBLIC, Type.VOID, new Type[]{}, new String[]{}, methodname, contextClassGen.getClassName(), innerMethodIL, contextCP);
                        // emit head of block
                        int headSize = getSizeWithoutBranch(block);
                        contextMethodGen = mgInner;
                        emitCode(innerMethodIL, 0, headSize);
                        innerMethodIL.append(new RETURN());
                        contextMethodGen = mg;
                        addMethod(mgInner);
                        blockIL.append(new INVOKESTATIC(contextCP.addMethodref(contextClassGen.getClassName(), methodname, "()V")));
                        if (headSize != size) {
                            // emit branch part
                            emitCode(blockIL, headSize, size);
                        }
                    } else {
                        // emit whole block
                        emitCode(blockIL, 0, size);
                    }
                    if (block.branchOut != null && !block.includesDelaySlot && block.flowOut != null) {
                        // if we are missing our delay slot, and flow into the next block, it
                        // should be a single instruction which just flows into the next block
                        // (i.e. a delay slot instruction)
                        assert (block.flowOut.size == 1);
                        assert (block.flowOut.branchOut == null);
                        assert (block.flowOut.flowOut != null);
                        // we want to skip it, since we've already executed the delay slot
                        blockIL.append(new GOTO(getStartHandle(block.flowOut.flowOut)));
                    }
                } else if (block.type == FlowAnalyzer.BasicBlock.JUMP_WRAPPER) {
                    emitJump(blockIL, contextBase + (block.offset << 2));
                }
                emitBlockFooter(blockIL);
            }

            // block instructions are only appended after all code is generated, since append removes
            // the instructions from the block instruction lists
            for (FlowAnalyzer.BasicBlock block = flowInfo.root; block != null; block = block.next) {
                methodIL.append(getInstructionList(block));
            }
            methodIL.append(new ILOAD(LOCAL_RETADDR));
            methodIL.append(new IRETURN());

        }

        addMethod(mg);
    }

    protected InstructionList getInstructionList(FlowAnalyzer.BasicBlock block) {
        if (block.size != 0) {
            return instructionLists[block.offset];
        } else {
            InstructionList rc = (InstructionList) extraInstructionLists.get(block);
            if (rc == null) {
                rc = new InstructionList();
                extraInstructionLists.put(block, rc);
            }
            return rc;
        }
    }

    protected void disassemble(int index) {
        int address = contextBase + (index << 2);
        String prefix = "";
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

    protected void addMethod(MethodGen mg) {
        mg.setMaxLocals(LOCAL_LAST);
        mg.setMaxStack();
        Method m = mg.getMethod();
        if (m.getCode().getCode().length > 8000) {
            if (log.isDebugEnabled()) {
                log.debug(contextClassGen.getClassName() + "." + m.getName() + " has " + (m.getCode().getCode().length) + " bytes bytcode");
            }
        }
        contextClassGen.addMethod(m);
        if (MultiStageCompiler.Settings.printCode && shouldPrintCode()) {
            printCodeIL.append(mg.getInstructionList());
        } else {
            mg.getInstructionList().dispose();
        }
    }

    protected void emitConstructor() {
        contextClassGen.addEmptyConstructor(Constants.ACC_PUBLIC);
    }

    // PERF:addr:blocks:instructions:flow:initBlock:emitMethods:getJavaClass:createClass

    // can return null if not on execution thread for garbage looking code
    public JavaClass createJavaClass(CodeUnit unit, boolean executionThread) {
        return createJavaClass(unit, getClassName(getClassNamePrefix(unit), unit.getBase()), executionThread);
    }

    // can return null if not on execution thread for garbage looking code
    public JavaClass createJavaClass(CodeUnit unit, String classname, boolean executionThread) {
        assert executionThread == this.intendedForExecutionThread;
        contextUnit = unit;
        contextBase = unit.getBase();
        contextClassGen = new ClassGen(classname, "java.lang.Object",
                "<jpsx>", Constants.ACC_PUBLIC | Constants.ACC_SUPER, null);
        contextCP = contextClassGen.getConstantPool();

        emitConstructor();
        long t0 = 0;
        StringBuilder perf = null;
        if (MultiStageCompiler.Settings.statistics) {
            perf = new StringBuilder();
            t0 = Timing.nanos();
        }
        FlowAnalyzer.FlowInfo flowInfo = unit.getFlowInfo(analyzer, executionThread);//analyzer.buildFlowGraph( contextBase, getMaxMethodInstructionCount());
        if (flowInfo == null) {
            return null;
        }
        contextUnitIsGarbage = false;
        if (MultiStageCompiler.Settings.statistics) {
            long t1 = Timing.nanos() - t0;
            perf.append("PERF:");
            perf.append(MiscUtil.toHex(unit.getBase(), 8));
            perf.append(':');
            perf.append(flowInfo.blockCount);
            perf.append(':');
            perf.append(flowInfo.instructionCount);
            perf.append(':');
            perf.append(t1);
            t0 = Timing.nanos();
        }
        initBlockStructures(flowInfo);
        if (MultiStageCompiler.Settings.statistics) {
            long t1 = Timing.nanos() - t0;
            perf.append(':');
            perf.append(t1);
            t0 = Timing.nanos();
        }
        emitMethods(flowInfo);
        if (contextUnitIsGarbage) {
            return null;
        }
        if (MultiStageCompiler.Settings.statistics) {
            long t1 = Timing.nanos() - t0;
            perf.append(':');
            perf.append(t1);
            t0 = Timing.nanos();
        }
        JavaClass jclass = contextClassGen.getJavaClass();
        if (MultiStageCompiler.Settings.statistics) {
            long t1 = Timing.nanos() - t0;
            perf.append(':');
            perf.append(t1);
        }
        if (MultiStageCompiler.Settings.printCode && shouldPrintCode()) {
            ConstantPool cp = jclass.getConstantPool();
            codeWriter.println(getClassNamePrefix(unit) + " unit at " + MiscUtil.toHex(contextBase, 8));
            InstructionHandle h = printCodeIL.getStart();
            InstructionHandle hLast = null;
            for (FlowAnalyzer.BasicBlock block = flowInfo.root; block != null; block = block.next) {
                codeWriter.println(" " + block);

                int size = block.size;
                if (block.branchOut != null && block.includesDelaySlot) {
                    // don't want to count the delay slot, which will be output by the branch anyway
                    size--;
                }
                for (int index = block.offset; index < block.offset + size; index++) {
                    disassemble(index);
                    if (0 != (flags[index] & CPUInstruction.FLAG_BRANCH)) {
                        // disassemble delay slot
                        disassemble(index + 1);
                    }
                    InstructionHandle hEnd = endHandles[index];
                    assert (hEnd != null);
                    if (hEnd != hLast) {
                        while (h != null) {
                            String bo = "" + h.getPosition();
                            while (bo.length() < 5) {
                                bo = " " + bo;
                            }
                            codeWriter.println(bo + " " + h.getInstruction().toString(cp));
                            hLast = h;
                            h = h.getNext();
                            if (hLast == hEnd)
                                break;
                        }
                    }
                    if (0 != (flags[index] & CPUInstruction.FLAG_BRANCH)) {
                        // skip delay slot
                        index++;
                    }
                }
            }
            printCodeIL.dispose();
        }

        if (MultiStageCompiler.Settings.saveClasses) {
            try {
                jclass.dump(codeFilename + classname + ".class");
            } catch (IOException ioe) {
            }
        }
        return jclass;
    }

    protected int getSizeWithoutBranch(FlowAnalyzer.BasicBlock b) {
        if (b.branchOut == null) {
            return b.size;
        } else {
            return b.branchOffset - b.offset;
        }
    }

    protected void emitCode(InstructionList il, int startOffset, int endOffset) {
        startOffset += contextBlock.offset;
        endOffset += contextBlock.offset;
        for (contextOffset = startOffset; contextOffset < endOffset && !contextUnitIsGarbage; contextOffset++) {
            // skip the instruction after an instruction which emitted a delay slot
            if (contextDelaySlotEmitted) {
                contextDelaySlotEmitted = false;
            } else {
                emitContextInstruction(il);
            }
        }
    }

    protected void emitDebugs(InstructionList il) {
        if (contextBase == 0xbfc018e0) {
            il.append(new INVOKESTATIC(contextCP.addMethodref(COMPILER_CLASS, MultiStageCompiler.COMPILER_TO_INTERPRETER_METHOD, "()V")));
            il.append(new INVOKESTATIC(contextCP.addMethodref(R3000_CLASS, "debugPrintf", "()V")));
            il.append(new INVOKESTATIC(contextCP.addMethodref(COMPILER_CLASS, MultiStageCompiler.INTERPRETER_TO_COMPILER_METHOD, "()V")));
        }

        if (!R3000Impl.Settings.debugBIOS) return;

        // todo, register functions s.t. the compiler can insert these also!
        if (contextBase == 0x00001b44) {
            il.append(new INVOKESTATIC(contextCP.addMethodref(COMPILER_CLASS, MultiStageCompiler.COMPILER_TO_INTERPRETER_METHOD, "()V")));
            il.append(new INVOKESTATIC(contextCP.addMethodref(R3000_CLASS, "debugDeliverEvent", "()V")));
            il.append(new INVOKESTATIC(contextCP.addMethodref(COMPILER_CLASS, MultiStageCompiler.INTERPRETER_TO_COMPILER_METHOD, "()V")));
        }

        if (contextBase == 0xa0) {
            il.append(new INVOKESTATIC(contextCP.addMethodref(COMPILER_CLASS, MultiStageCompiler.COMPILER_TO_INTERPRETER_METHOD, "()V")));
            il.append(new INVOKESTATIC(contextCP.addMethodref(R3000_CLASS, "debugA0", "()V")));
            il.append(new INVOKESTATIC(contextCP.addMethodref(COMPILER_CLASS, MultiStageCompiler.INTERPRETER_TO_COMPILER_METHOD, "()V")));
        }

        if (contextBase == 0xb0) {
            il.append(new INVOKESTATIC(contextCP.addMethodref(COMPILER_CLASS, MultiStageCompiler.COMPILER_TO_INTERPRETER_METHOD, "()V")));
            il.append(new INVOKESTATIC(contextCP.addMethodref(R3000_CLASS, "debugB0", "()V")));
            il.append(new INVOKESTATIC(contextCP.addMethodref(COMPILER_CLASS, MultiStageCompiler.INTERPRETER_TO_COMPILER_METHOD, "()V")));
        }

        if (contextBase == 0xc0) {
            il.append(new INVOKESTATIC(contextCP.addMethodref(COMPILER_CLASS, MultiStageCompiler.COMPILER_TO_INTERPRETER_METHOD, "()V")));
            il.append(new INVOKESTATIC(contextCP.addMethodref(R3000_CLASS, "debugC0", "()V")));
            il.append(new INVOKESTATIC(contextCP.addMethodref(COMPILER_CLASS, MultiStageCompiler.INTERPRETER_TO_COMPILER_METHOD, "()V")));
        }
    }

    public void emitContextInstruction(InstructionList il) {
        contextAddress = contextBase + (contextOffset << 2);

        InstructionHandle endHandle = null;

        if (MultiStageCompiler.Settings.addLineNumbers) {
            endHandle = il.getEnd();
        }

        if (MultiStageCompiler.Settings.profiling && contextOffset == contextBlock.offset) {
            il.append(new PUSH(contextCP, contextAddress));
            il.append(new INVOKESTATIC(contextCP.addMethodref("jsx.core.NativeCompiler", "blockUsed", "(I)V")));
        }

        if (MultiStageCompiler.Settings.megaTrace) {
            il.append(new PUSH(contextCP, contextAddress));
            il.append(new INVOKESTATIC(contextCP.addMethodref(COMPILER_CLASS, "dumpState", "(I)V")));
        }

        if (contextBlock.branchOut != null && contextBlock.branchOffset == contextOffset) {
            assert 0 != (flags[contextOffset] & CPUInstruction.FLAG_BRANCH) : contextBlock + " " + MiscUtil.toHex(contextAddress, 8);
            if (contextBlock.branchOut.offset <= contextBlock.offset) {
                emitBreakoutCheck(il);
            }
        }

        // certain instructions require us to set the PC correctly
        // todo check for delay slot!
        if (MultiStageCompiler.Settings.debugPC || 0 != (flags[contextOffset] & (CPUInstruction.FLAG_REFERENCES_PC |
                CPUInstruction.FLAG_MAY_RESTORE_INTERPRETER_STATE |
                CPUInstruction.FLAG_REQUIRES_COMPLETE_INTERPRETER_STATE))) {
            il.append(new PUSH(contextCP, contextAddress));
            il.append(new PUTSTATIC(contextCP.addFieldref(R3000_CLASS, "reg_pc", "I")));
        }

        emitContextInstructionGuts(il);

        if (0 != (flags[contextOffset] & CPUInstruction.FLAG_MAY_RESTORE_INTERPRETER_STATE)) {
            il.append(new INVOKESTATIC(contextCP.addMethodref(COMPILER_CLASS, MultiStageCompiler.INTERPRETER_TO_COMPILER_METHOD, "()V")));
        }

        if (MultiStageCompiler.Settings.addLineNumbers) {
            // no point if we didn't add any code
            if (endHandle != il.getEnd()) {
                InstructionHandle first = endHandle == null ? il.getStart() : endHandle.getNext();
                // note the TAG_DELAY_SLOT will allow the exception handling code that uses the line numbers
                // to know to re-execute from the previous instruction, however that doesn't take care of the
                // case of code that branches directly to a delay slot.
                // todo we don't handle that well today, but should be making separate basic blocks, and
                // todo we could mark the code for that case with a special line number (e.g. very big)
                // todo to tell the exception handling code not to step back
                contextMethodGen.addLineNumber(first, contextOffset);
            }
        }

        if (contextIsDelaySlot) {
            addressSpace.orTag(contextAddress, MultiStageCompiler.TAG_DELAY_SLOT);
        }

        if (MultiStageCompiler.Settings.printCode && shouldPrintCode()) {
            endHandle = il.getEnd();
            if (endHandle == null) endHandle = il.append(new NOP());
            endHandles[contextOffset] = endHandle;
        }
    }

    protected void emitBreakoutCheck(InstructionList il) {
        if (!noInterruptCheck) {
            // todo delay slot
            il.append(new GETSTATIC(contextCP.addFieldref(COMPILER_CLASS, "isInterrupted", "Z")));
            IFEQ ieq = new IFEQ(null);
            il.append(ieq);
            il.append(new PUSH(contextCP, contextAddress));
            il.append(new INVOKESTATIC(contextCP.addMethodref(COMPILER_CLASS, MultiStageCompiler.INTERRUPTED_METHOD, "(I)V")));
            ieq.setTarget(il.append(new NOP()));
        }
    }

    protected void emitContextInstructionGuts(InstructionList il) {
        // should simulate instructions which just read r0
        if (regsRead[contextOffset] == 1 && 0 != (flags[contextOffset] & CPUInstruction.FLAG_SIMULATABLE) && instructions[contextOffset].simulate(opCodes[contextOffset], simulateRegs)) {
            for (int i = 1; i < 32; i++) {
                if ((regsWritten[contextOffset] & (1 << i)) != 0) {
                    il.append(new PUSH(contextCP, simulateRegs[i]));
                    emitSetReg(il, i);
                }
            }
        } else {
            instructions[contextOffset].compile(this, contextAddress, opCodes[contextOffset], il);
        }
    }

    public InstructionHandle getStartHandle(FlowAnalyzer.BasicBlock block) {
        InstructionHandle rc = getInstructionList(block).getStart();
        if (rc == null) {
            rc = getInstructionList(block).append(new NOP());
        }
        return rc;
    }
    // ------------- ICompilationContext

    // todo remove this, and pass to compiler

    public ConstantPoolGen getConstantPoolGen() {
        return contextCP;
    }

    protected boolean noInterruptCheck;
    protected FlowAnalyzer.BasicBlock contextBlock;
    protected int contextBase;
    protected int contextOffset;
    protected int contextAddress;
    protected MethodGen contextMethodGen;
    protected ConstantPoolGen contextCP;
    protected ClassGen contextClassGen;
    protected AddressSpace.ResolveResult rr = new AddressSpace.ResolveResult();
    protected boolean contextDelaySlotEmitted;
    protected boolean contextIsDelaySlot;
    protected CodeUnit contextUnit;
    protected boolean contextUnitIsGarbage;

    protected void invokeStaticMethod(java.lang.reflect.Method method) {

    }

    public void emitInterpretedInstruction(InstructionList il, int ci, String clazz, String method) {

        if (0 != (flags[contextOffset] & CPUInstruction.FLAG_REQUIRES_COMPLETE_INTERPRETER_STATE)) {
            il.append(new INVOKESTATIC(contextCP.addMethodref(COMPILER_CLASS, MultiStageCompiler.COMPILER_TO_INTERPRETER_METHOD, "()V")));
        } else {
            int readsReg = getReadsReg();
            for (int r = 1; r < 32; r++) {
                if (0 != (readsReg & (1 << r))) {
                    // R3000Impl.regs[r] = CompiledR3000.reg_r;
                    il.append(new GETSTATIC(contextCP.addFieldref(R3000_CLASS, "regs", "[I")));

                    il.append(new PUSH(contextCP, r));
                    if (0 != (getConstantRegs() & (1 << r))) {
                        il.append(new PUSH(contextCP, getRegValue(r)));
                    } else {
                        emitGetReg(il, r);
                    }
                    il.append(new IASTORE());
                }
            }
        }

        il.append(new PUSH(contextCP, ci));
        il.append(new INVOKESTATIC(contextCP.addMethodref(clazz, method, "(I)V")));

        if (0 != (flags[contextOffset] & (CPUInstruction.FLAG_REQUIRES_COMPLETE_INTERPRETER_STATE))) {
            il.append(new INVOKESTATIC(contextCP.addMethodref(COMPILER_CLASS, MultiStageCompiler.INTERPRETER_TO_COMPILER_METHOD, "()V")));
        } else {
            int writesReg = getWritesReg();
            for (int r = 1; r < 32; r++) {
                if (0 != (writesReg & (1 << r))) {
                    // CompiledR3000.reg_r = R3000Impl.reg[r];
                    il.append(new GETSTATIC(contextCP.addFieldref(R3000_CLASS, "regs", "[I")));
                    il.append(new PUSH(contextCP, r));
                    il.append(new IALOAD());
                    emitSetReg(il, r);
                }
            }
        }
    }

    public void emitGetReg(InstructionList il, int reg) {
        il.append(new GETSTATIC(contextCP.addFieldref(COMPILER_CLASS, "reg_" + reg, "I")));
    }

    public void emitSetReg(InstructionList il, int reg) {
        assert reg != 0;
        il.append(new PUTSTATIC(contextCP.addFieldref(COMPILER_CLASS, "reg_" + reg, "I")));
    }

    public int getRegValue(int reg) {
        assert reg == 0;
        return 0;
    }

    public int getConstantRegs() {
        return 1;
    }

    public int getWritesReg() {
        return regsWritten[contextOffset];
    }

    public int getReadsReg() {
        return regsRead[contextOffset];
    }

    public void emitJump(InstructionList il, int address) {
        il.append(new PUSH(contextCP, address));
        emitJump(il);
    }

    public void emitJump(InstructionList il) {
        // this is decompilable (uses local var target)
        il.append(new ISTORE(LOCAL_PRIVATE_TEMP));
        InstructionHandle loop = il.append(new ILOAD(LOCAL_PRIVATE_TEMP));
        il.append(new ILOAD(LOCAL_RETADDR));
        if (R3000Impl.Settings.traceExecutionFlow) {
            il.append(new ILOAD(LOCAL_PRIVATE_TEMP));
            il.append(new ILOAD(LOCAL_RETADDR));
            il.append(new INVOKESTATIC(contextCP.addMethodref(COMPILER_CLASS, "traceCheckJumpTarget", "(II)V")));
        }
        IF_ICMPEQ icmpeq = new IF_ICMPEQ(null);
        il.append(icmpeq);

        il.append(new ILOAD(LOCAL_JUMP));
        IFEQ ieq = new IFEQ(null);
        il.append(ieq);

        icmpeq.setTarget(il.append(new ILOAD(LOCAL_PRIVATE_TEMP)));
        if (R3000Impl.Settings.traceExecutionFlow) {
            il.append(new PUSH(contextCP, contextBase));
            il.append(new INVOKESTATIC(contextCP.addMethodref(COMPILER_CLASS, "traceLeaveUnit", "(I)V")));
        }
        il.append(new IRETURN());
        ieq.setTarget(il.append(new ILOAD(LOCAL_PRIVATE_TEMP)));
        il.append(new ILOAD(LOCAL_RETADDR));
        il.append(new INVOKESTATIC(contextCP.addMethodref(COMPILER_CLASS, MultiStageCompiler.JUMP_METHOD, "(II)I")));
        il.append(new ISTORE(LOCAL_PRIVATE_TEMP));
        il.append(new GOTO(loop));
    }

    public InstructionHandle getBranchTarget(int address) {
        if (!intendedForExecutionThread && contextBlock.branchOut == null) {
            contextUnitIsGarbage = true;
            // a branch to ourselves
            return getStartHandle(contextBlock);
        }
        assert contextBlock.branchOut != null : "null branch target - wanted " + MiscUtil.toHex(address, 8) + " base = " +  MiscUtil.toHex(contextBlock.base, 8);
        assert contextBase + (contextBlock.branchOut.offset << 2) == address :
                contextBlock + ": Expected branch target " + MiscUtil.toHex(contextBase + (contextBlock.branchOut.offset << 2), 8) + " != actual " + MiscUtil.toHex(address, 8);

        if (MultiStageCompiler.Settings.printRare && address == contextAddress) {
            System.out.println("Branch to self at " + MiscUtil.toHex(address, 8));
        }
        return getStartHandle(contextBlock.branchOut);
    }

    public void emitCall(InstructionList il, int address, int retAddr) {
        if (R3000Impl.Settings.dumpRegsOnCall && R3000Impl.shouldDumpRegs(address)) {
            il.append(new PUSH(contextCP, address));
            il.append(new PUTSTATIC(contextCP.addFieldref(R3000_CLASS, "reg_pc", "I")));
            il.append(new INVOKESTATIC(contextCP.addMethodref(COMPILER_CLASS, MultiStageCompiler.COMPILER_TO_INTERPRETER_METHOD, "()V")));
            il.append(new INVOKESTATIC(contextCP.addMethodref(R3000_CLASS, "dumpRegs", "()V")));
            il.append(new INVOKESTATIC(contextCP.addMethodref(COMPILER_CLASS, MultiStageCompiler.INTERPRETER_TO_COMPILER_METHOD, "()V")));
        }
        if (R3000Impl.Settings.traceExecutionFlow) {
            il.append(new PUSH(contextCP, address));
            il.append(new PUSH(contextCP, retAddr));
            il.append(new INVOKESTATIC(contextCP.addMethodref(COMPILER_CLASS, "traceDirectCall", "(II)V")));
        }

        il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_tagClearPollCounters", "()V")));

        if (AddressSpace.Util.isBIOS(contextAddress) && !AddressSpace.Util.isBIOS(address)) {
            // calling from bios to ram must be indirect... note this is probably rare.
            // todo i've seen the BIOS classloader have ram classes; I wonder why
            //System.out.println( "Emitting bios to ram call" );
            il.append(new PUSH(contextCP, address));
            il.append(new PUSH(contextCP, retAddr));
            il.append(new INVOKESTATIC(contextCP.addMethodref(COMPILER_CLASS, MultiStageCompiler.CALL_METHOD, "(II)V")));
        } else {
            il.append(new PUSH(contextCP, retAddr));
            il.append(new PUSH(contextCP, 0));
            il.append(new INVOKESTATIC(contextCP.addMethodref(getClassName(CLASS_NAME_PREFIX, address), STATIC_METHOD, "(IZ)I")));
            il.append(new POP());
        }
    }

    /**
     * Used for JALR etc where the address to call is at the top
     * of the JVM stack
     */
    public void emitCall(InstructionList il, int retAddr) {
/*		if (R3000Impl.Settings.dumpRegsOnCall) {
			il.append( new DUP());
			il.append( new PUTSTATIC( contextCP.addFieldref( R3000_CLASS, "reg_pc", "I" ) ) );
			il.append( new INVOKESTATIC( contextCP.addMethodref( COMPILER_CLASS, MultiStageCompiler.COMPILER_TO_INTERPRETER_METHOD, "()V" ) ) );
			il.append( new INVOKESTATIC( contextCP.addMethodref( R3000_CLASS, "dumpRegs", "()V" ) ) );
			il.append( new INVOKESTATIC( contextCP.addMethodref( COMPILER_CLASS, MultiStageCompiler.INTERPRETER_TO_COMPILER_METHOD, "()V" ) ) );
		}*/
        il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_tagClearPollCounters", "()V")));
        il.append(new PUSH(contextCP, retAddr));
        il.append(new INVOKESTATIC(contextCP.addMethodref(COMPILER_CLASS, MultiStageCompiler.CALL_METHOD, "(II)V")));
    }

    public void emitReadMem8(InstructionList il, int address, boolean signed) {
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
        emitGetReg(il, reg);
        if (offset != 0) {
            il.append(new PUSH(contextCP, offset));
            il.append(new IADD());
        }
        if (0 == (MultiStageCompiler.Settings.usuallyRAMRegs & (1 << reg))) {
            il.append(new ISTORE(LOCAL_PRIVATE_TEMP));
            il.append(new PUSH(contextCP, contextAddress));
            il.append(new ILOAD(LOCAL_PRIVATE_TEMP));
            il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_tagAddressAccessRead8", "(II)V")));
            il.append(new ILOAD(LOCAL_PRIVATE_TEMP));
        }
        il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_read8", "(I)I")));
    }

    public void emitReadMem16(InstructionList il, int address, boolean signed) {
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
        emitGetReg(il, reg);
        if (offset != 0) {
            il.append(new PUSH(contextCP, offset));
            il.append(new IADD());
        }
        if (0 == (MultiStageCompiler.Settings.usuallyRAMRegs & (1 << reg))) {
            il.append(new ISTORE(LOCAL_PRIVATE_TEMP));
            il.append(new PUSH(contextCP, contextAddress));
            il.append(new ILOAD(LOCAL_PRIVATE_TEMP));
            il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_tagAddressAccessRead16", "(II)V")));
            il.append(new ILOAD(LOCAL_PRIVATE_TEMP));
        }
        il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_read16", "(I)I")));
    }

    public void emitReadMem32(InstructionList il, int address, boolean forceAlign) {
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
                il.append(new INVOKESTATIC(contextCP.addMethodref(HW_CLASS, "_read32", "(I)I")));
            } else {
                il.append(new PUSH(contextCP, address));
                il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_read32", "(I)I")));
            }
        } else {
            il.append(new PUSH(contextCP, address));
            il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "read32", "(I)I")));
        }
    }

    public void emitReadMem32(InstructionList il, int reg, int offset, boolean forceAlign) {
        emitGetReg(il, reg);
        if (offset != 0) {
            il.append(new PUSH(contextCP, offset));
            il.append(new IADD());
        }
        if (forceAlign) {
            il.append(new PUSH(contextCP, 0xfffffffc));
            il.append(new IAND());
        }
        if (0 == (MultiStageCompiler.Settings.usuallyRAMRegs & (1 << reg))) {
            il.append(new ISTORE(LOCAL_PRIVATE_TEMP));
            il.append(new PUSH(contextCP, contextAddress));
            il.append(new ILOAD(LOCAL_PRIVATE_TEMP));
            il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_tagAddressAccessRead32", "(II)V")));
            il.append(new ILOAD(LOCAL_PRIVATE_TEMP));
        }
        il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_read32", "(I)I")));
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
        emitGetReg(il, reg);
        if (offset != 0) {
            il.append(new PUSH(contextCP, offset));
            il.append(new IADD());
        }
        if (0 == (MultiStageCompiler.Settings.usuallyRAMRegs & (1 << reg))) {
            il.append(new ISTORE(LOCAL_PRIVATE_TEMP));
            il.append(new PUSH(contextCP, contextAddress));
            il.append(new ILOAD(LOCAL_PRIVATE_TEMP));
            il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_tagAddressAccessWrite", "(II)V")));
            il.append(new ILOAD(LOCAL_PRIVATE_TEMP));
        }
        il.append(new SWAP());
        il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_write8", "(II)V")));
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
        emitGetReg(il, reg);
        if (offset != 0) {
            il.append(new PUSH(contextCP, offset));
            il.append(new IADD());
        }
        if (0 == (MultiStageCompiler.Settings.usuallyRAMRegs & (1 << reg))) {
            il.append(new ISTORE(LOCAL_PRIVATE_TEMP));
            il.append(new PUSH(contextCP, contextAddress));
            il.append(new ILOAD(LOCAL_PRIVATE_TEMP));
            il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_tagAddressAccessWrite", "(II)V")));
            il.append(new ILOAD(LOCAL_PRIVATE_TEMP));
        }
        il.append(new SWAP());
        il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_write16", "(II)V")));
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
                /*
                    il.append( new GETSTATIC( contextCP.addFieldref( ADDRESS_SPACE_CLASS, "bios", "[I")));
                    il.append( new PUSH( contextCP, rr.offset));
                    il.append( il2);
                    il.append( new IASTORE());*/
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
        emitGetReg(il, reg);
        if (offset != 0) {
            il.append(new PUSH(contextCP, offset));
            il.append(new IADD());
        }
        if (forceAlign) {
            il.append(new PUSH(contextCP, 0xfffffffc));
            il.append(new IAND());
        }
        if (0 == (MultiStageCompiler.Settings.usuallyRAMRegs & (1 << reg))) {
            il.append(new ISTORE(LOCAL_PRIVATE_TEMP));
            il.append(new PUSH(contextCP, contextAddress));
            il.append(new ILOAD(LOCAL_PRIVATE_TEMP));
            il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_tagAddressAccessWrite", "(II)V")));
            il.append(new ILOAD(LOCAL_PRIVATE_TEMP));
        }
        il.append(il2);
        il.append(new INVOKESTATIC(contextCP.addMethodref(ADDRESS_SPACE_CLASS, "_write32", "(II)V")));
    }

    public void emitDelaySlot(InstructionList il) {
        contextOffset++;
        contextIsDelaySlot = true;
        emitContextInstruction(il);
        contextIsDelaySlot = false;
        contextDelaySlotEmitted = true;
        contextOffset--;
        //assert 0!=(flags[contextOffset]&Instruction.FLAG_BRANCH);
        //if (delaySlot==null) {
        //    delaySlot = new CompilationContext1( mg, contextCP);
        //}
        //delaySlot.compile( contextAddress+4, il);
        //delaySlotEmitted = true;
    }

    public final int getTempLocal(int index) {
        int rc = LOCAL_TEMP0 + index;
        if (rc >= LOCAL_LAST)
            throw new IllegalStateException("too many locals!");
        return rc;
    }

    // ----
}