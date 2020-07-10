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

import org.jpsx.api.components.core.addressspace.AddressSpace;
import org.jpsx.api.components.core.cpu.CPUInstruction;
import org.jpsx.api.components.core.cpu.R3000;
import org.jpsx.runtime.components.core.CoreComponentConnections;
import org.jpsx.runtime.util.MiscUtil;

import java.util.Stack;

// todo cope with method > MAX_R3000_METHOD_INSTRUCTIONS
// todo remove maxBlockSize stuff

public class FlowAnalyzer {
    private static final boolean debugFlow = false;
    /**
     * contains a block value if the corresponding timestamp is correct,
     * however, only guaranteed to be the correct BasicBlock if the
     * offset is a branch target. Note, we can have
     * dangling references to BasicBlocks here, however the number
     * is limited, so we don't care.
     */
    private final BasicBlock[] blocks = new BasicBlock[MultiStageCompiler.Settings.maxR3000InstructionsPerUnit];
    private final BasicBlock[] branchTargets = new BasicBlock[MultiStageCompiler.Settings.maxR3000InstructionsPerUnit];

    // if timeStamps correct, then block/flags are valid
    private final int[] timeStamps = new int[MultiStageCompiler.Settings.maxR3000InstructionsPerUnit];
    private final byte[] flags = new byte[MultiStageCompiler.Settings.maxR3000InstructionsPerUnit];

    private static final byte VISITED = 1;
    private static final byte BLOCK_START = 2;
    private static final byte DELAY_SLOT = 4;
    private static final byte CONDITIONAL_BRANCH = 8;
    private static final byte UNCONDITIONAL_BRANCH = 16;

    private int currentTimeStamp;

    private int currentBase;
    private int blockCount;
    private Stack<Integer> pendingPaths = new Stack<Integer>();
    private static final Integer i0 = 0;

    private final AddressSpace addressSpace = CoreComponentConnections.ADDRESS_SPACE.resolve();
    private final R3000 r3000 = CoreComponentConnections.R3000.resolve();

    // note high offset so it isn't seen as a back branch
    public final BasicBlock UNKNOWN_BRANCH_TARGET = new BasicBlock(0, 0x40000000, BasicBlock.UNKNOWN_TARGET);

    /**
     * Traces execution flow from an entry point.
     * <p/>
     * BasicBlocks are constructed for all reachable code paths
     * starting at the base address, subject to the limit
     * {@link MultiStageCompiler.Settings#maxR3000InstructionsPerUnit}.<br>
     * <p/>
     * In the case that this limit is exceeded,
     * special blocks will be emitted to handle branching
     * out of the block.<br>
     * <p/>
     * As a side of this function, the following
     * array members are populated for use by the caller
     *
     * @param base the entry point
     * @return the entry-point {@link BasicBlock} or null if the method appears to be garbage (only for intendedForExecutionThread flow analyzers)
     */
    public FlowInfo buildFlowGraph(int base, boolean executionThread) {
        return buildFlowGraph(base, 800, executionThread);
    }

    public FlowInfo buildFlowGraph(int base, int maxBlockSize, boolean executionThread) {
        int instructionCount = 0;

        currentBase = base;
        if (debugFlow) System.out.println("buildFlowGraph " + MiscUtil.toHex(base, 8));
        assert 0 == (base & 3);

        currentTimeStamp++;
        blockCount = 0;

        BasicBlock firstExtraBlock = null;
        BasicBlock lastExtraBlock = null;

        pendingPaths.push(i0);


        // We might be speculatively analyzing empty memory, so we OR all the instructions together
        int ciOR = 0;

        int max = 0;
        while (!pendingPaths.isEmpty()) {
            int offset = pendingPaths.pop();

            if (timeStamps[offset] == currentTimeStamp && 0 != (flags[offset] & VISITED)) {
                if (0 != (flags[offset] & DELAY_SLOT)) {
                    // todo graham 12/23/14 - this doesn't make much sense...
                    // todo it seems like we should be caring if it was already a BLOCK_START

                    // nasty case of branching to delay slot which has already been
                    // visited; however the flow might previously have stopped exactly
                    // at the delay slot. since the delay slot may not be a branch instruction
                    // (or if it was would still have another delay slot following),
                    // we try the next instruction also in case it has never been visited
                    if (debugFlow) {
                        System.out.println("RARE: Checking code beyond branched-to delay-slot at " + MiscUtil.toHex(base + offset * 4, 8));
                    }
                    // note this has the side effect of always making the delay slot a single instruction block;
                    offset++;
                } else {
                    // starting a block which has already been visited, so nothing to do
                    continue;
                }
            }

            // side effect is to create block if necessary
            getBlock(offset);

            boolean flowOut = false;

            // todo we don't actually handle the case where the method is bigger than this
            int end = MultiStageCompiler.Settings.maxR3000InstructionsPerUnit - 1; // -1 in case we need delay slot
            int blockLimit = offset + maxBlockSize;
            byte nextFlags = 0;

            for (; offset < end; offset++) {
                if (offset >= blockLimit && 0 == nextFlags) {
                    // not delay slot or anything else of interest...
                    // end our block, but continue where we left off.
                    pendingPaths.push(offset);
                    break;
                }
                if (timeStamps[offset] == currentTimeStamp) {
                    // run into something we've seen before, so just update the flags
                    flags[offset] |= nextFlags;
                    // if we've already actually been here, then quit;
                    // otherwise we're flowing into a branch target
                    // we haven't yet visited
                    if (0 != (flags[offset] & VISITED)) {
                        break;
                    }
                    flags[offset] |= VISITED;
                } else {
                    timeStamps[offset] = currentTimeStamp;
                    flags[offset] = (byte) (nextFlags | VISITED);
                }
                nextFlags = 0;
                int address = base + (offset << 2);
                int ci = addressSpace.internalRead32(address);
                ciOR |= ci;
                CPUInstruction inst = r3000.decodeInstruction(ci);

                int iFlags = inst.getFlags();

                if (!executionThread && 0 != (iFlags & CPUInstruction.FLAG_INVALID)) {
                    // don't like invalid instructions when working in a background thread
                    // because we might be analyzing garbage.
                    if (debugFlow) {
                        System.out.println("Probable garbage at " + MiscUtil.toHex(base, 8) + " because of invalid instruction");
                    }
                    return null;
                }
                if (0 != (iFlags & CPUInstruction.FLAG_BRANCH)) {
                    int branchType = inst.getBranchType(ci);
                    if (0 == (iFlags & CPUInstruction.FLAG_LINK)) {
                        // note we are a non-linking instruction, so there
                        // we cannot have both conditional branch and
                        // register target... which would be bad.
                        if (0 != (iFlags & (CPUInstruction.FLAG_IMM_NEAR_TARGET | CPUInstruction.FLAG_IMM_FAR_TARGET))) {
                            int target;
                            if (0 != (iFlags & CPUInstruction.FLAG_IMM_NEAR_TARGET)) {
                                target = address + 4 + R3000.Util.signed_branch_delta(ci);
                            } else {
                                // todo assert Far target
                                target = ((address + 4) & 0xf0000000) | ((ci & 0x3fffff) << 2);
                            }
                            int targetOffset = (target - base) >> 2;
                            if (targetOffset < 0 || targetOffset >= MultiStageCompiler.Settings.maxR3000InstructionsPerUnit) {
                                branchTargets[offset] = new BasicBlock(currentBase, targetOffset, BasicBlock.JUMP_WRAPPER);
                                if (firstExtraBlock == null) {
                                    firstExtraBlock = lastExtraBlock = branchTargets[offset];
                                } else {
                                    lastExtraBlock.next = branchTargets[offset];
                                    lastExtraBlock = branchTargets[offset];
                                }
                                blockCount++;
                            } else {
                                branchTargets[offset] = getBlock(targetOffset);
                                //flags[targetOffset] |= BLOCK_START;
                                pendingPaths.push(targetOffset);
                            }
                        } else {
                            branchTargets[offset] = UNKNOWN_BRANCH_TARGET;
                        }

                        if (branchType == CPUInstruction.BRANCH_ALWAYS) {
                            flags[offset] |= UNCONDITIONAL_BRANCH;
                        } else {
                            flags[offset] |= CONDITIONAL_BRANCH;
                            flowOut = true;
                        }

                        // done after the next instruction (delay slot)
                        end = offset + 2;
                        nextFlags = DELAY_SLOT;
                    }
                }
            }
            if (offset > max) {
                max = offset;
            }
            if (flowOut) {
                // want to move straight on to next block
                pendingPaths.push(offset);
            }
        }
        if (!executionThread && ciOR == 0) {
            // all NOPs and then we fell off the end
            if (debugFlow) {
                System.out.println("Garbage empty-ness at " + MiscUtil.toHex(base, 8));
            }
            return null;
        }

        BasicBlock block = null;
        BasicBlock flowingInBlock = null;

        for (int offset = 0; offset < max; offset++) {
            if (timeStamps[offset] == currentTimeStamp) {
                assert (0 != (flags[offset] & VISITED));

                if (0 != (flags[offset] & BLOCK_START)) {
                    if (block != null) {
                        block.next = blocks[offset];
                    }
                    block = blocks[offset];
                    if (flowingInBlock != null) {
                        flowingInBlock.flowOut = block;
                    }
                    flowingInBlock = block;
                }
                if (0 != (flags[offset] & DELAY_SLOT)) {
                    block.includesDelaySlot = true;
                }
                block.size++;
                instructionCount++;
                if (0 != (flags[offset] & (CONDITIONAL_BRANCH | UNCONDITIONAL_BRANCH))) {
                    block.branchOffset = offset;
                    block.branchOut = branchTargets[offset];
                    if (0 != (flags[offset] & UNCONDITIONAL_BRANCH)) {
                        flowingInBlock = null;
                    }
                }
            }
        }

        if (debugFlow) {
            for (int offset = 0; offset < max; offset++) {
                if (timeStamps[offset] == currentTimeStamp) {
                    int address = base + (offset << 2);
                    String debug = MiscUtil.toHex(address, 8) + " ";
                    if (0 != (flags[offset] & BLOCK_START)) {
                        System.out.println("--- " + blocks[offset]);
                        debug += "*";
                    } else {
                        debug += " ";
                    }
                    if (0 != (flags[offset] & UNCONDITIONAL_BRANCH)) {
                        if (branchTargets[offset] == null) {
                            debug += "UBR";
                        } else {
                            debug += "UB" + branchTargets[offset].type;
                        }
                    } else if (0 != (flags[offset] & CONDITIONAL_BRANCH)) {
                        debug += "CB" + branchTargets[offset].type;
                    } else {
                        debug += "   ";
                    }
                    if (0 != (flags[offset] & DELAY_SLOT)) {
                        debug += "D";
                    } else {
                        debug += " ";
                    }

                    int ci = addressSpace.internalRead32(address);
                    debug += " " + r3000.disassemble(address, ci);
                    System.out.println(debug);
                }
            }
        }
        assert (block.next == null);
        block.next = firstExtraBlock;

        FlowInfo rc = new FlowInfo();
        rc.root = blocks[0];
        rc.instructionCount = instructionCount;
        rc.blockCount = blockCount;
        rc.base = base;
        rc.end = base + max * 4;
        return rc;
    }

    protected BasicBlock getBlock(int offset) {
        if (timeStamps[offset] != currentTimeStamp) {
            timeStamps[offset] = currentTimeStamp;
            flags[offset] = 0;
        }
        if (0 == (flags[offset] & BLOCK_START)) {
            flags[offset] |= BLOCK_START;
            blocks[offset] = new BasicBlock(currentBase, offset);
            blockCount++;
        }
        return blocks[offset];
    }

    // note this class should be immutable with accessors... it may be
    // shared by more than one thread
    public static class BasicBlock {
        /**
         * block this flows into, or null if it always branches (e.g. ends with jr)
         * <p/>
         * Note that more than one block may flow into another block
         * because we need to create a dummy block when someone
         * branches to a delay slot
         */
        public BasicBlock flowOut;
        /**
         * block this branches to, or null if it doesn't end with a branch
         */
        public BasicBlock branchOut;

        /**
         * linked list of blocks in ascending offset order
         */
        public BasicBlock next;

        /**
         * configure instruction offset within enclsoing method
         */
        public int offset;

        /**
         * offset of branch instruction
         */
        public int branchOffset;

        public int base;
        /**
         * number of instructions in the method
         */
        public int size;

        public boolean includesDelaySlot;

        public int type;

        public static final int NORMAL = 0;
        public static final int JUMP_WRAPPER = 1;
        public static final int UNKNOWN_TARGET = 2;

        public BasicBlock(int base, int offset) {
            this.base = base;
            this.offset = offset;
        }

        public BasicBlock(int base, int offset, int type) {
            this.base = base;
            this.offset = offset;
            this.type = type;
        }

        public String toString() {
            String rc;
            if (size == 0) {
                rc = "unvisited block " + MiscUtil.toHex(base + offset * 4, 8);
            } else {
                rc = "block " + MiscUtil.toHex(base + offset * 4, 8) + "->" + MiscUtil.toHex(base + offset * 4 + size * 4, 8);
                if (flowOut != null) {
                    rc += " flow " + MiscUtil.toHex(base + flowOut.offset * 4, 8);
                }
                if (branchOut != null) {
                    rc += " branch " + MiscUtil.toHex(base + branchOut.offset * 4, 8);
                    if (!includesDelaySlot) {
                        rc += " missing-delay-slot";
                    }
                }
            }
            return rc;
        }
    }

    public static class FlowInfo {
        public BasicBlock root;
        public int instructionCount;
        public int base;
        public int end;
        public int blockCount;

        public String toString() {
            return "FlowInfo@" + Integer.toHexString(hashCode()) + " ic=" + instructionCount + " base=" + MiscUtil.toHex( base, 8 ) + " bc=" + blockCount;
		}
	}
}
