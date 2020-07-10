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

// not thread safe

public interface R3000 {
    /**
     * Stack pointer register constant
     */
    public static final int R_SP = 29;
    /**
     * Return address register constant
     */
    public static final int R_RETADDR = 31;

    // todo document what it means to read a compiler reg here
    int getReg(int index);

    // todo document what it means to write a compiler register here
    void setReg(int index, int value);

    int getPC();

    int getLO();

    int getHI();

    void setPC(int pc);

    void setLO(int lo);

    void setHI(int hi);

    /**
     * Decode the given opcode into an emulator instruction instance
     *
     * @param ci the 32 bit opcode
     * @return the instruction specified by the opcode. If the op code is invalid, then this method returns an instruction representing
     *         and invalid instruction rather than null
     */
    CPUInstruction decodeInstruction(int ci);

    String disassemble(int address, int ci);

    /**
     * Determine if the current thread is the CPU execution thread
     */
    boolean isExecutionThread();

    /**
     * Called from a non execution thread to request a controlled break out to the main interpreter/control loop as soon as possible.
     * Currently used for R3000 interrupt handling and external pausing/stepping etc. of the CPU
     */
    void requestBreakout();

    /**
     * Called from the execution thread to request an immediate return to the interpreter/control loop - this can be called mid
     * instruction and may have undesirable side effects (i.e. half executed instruction)
     */
    void immediateBreakout();

    void restoreInterpreterState();

    void interpreterBranch(int relativeToDelay);

    void interpreterJump(int relativeToDelay, int target);

    void interpreterJumpAndLink(int relativeToDelay, int target, int retAddr);

    // todo should this be elsewhere
    void compilerInterrupted();

    /**
     * @return the array of registers used during intrepreted code execution; this value should be immutable
     */
    int[] getInterpreterRegs();

    CPUInstruction getInvalidInstruction();

    void executeFromPC();

    public static class Util {

        public static int bits_rs(final int ci) {
            return (ci >> 21) & 0x1f;
        }

        public static int bits_rt(final int ci) {
            return (ci >> 16) & 0x1f;
        }

        public static int bits_rd(final int ci) {
            return (ci >> 11) & 0x1f;
        }

        public static int bits_sa(final int ci) {
            return (ci >> 6) & 0x1f;
        }

        public static int bits25_6(int x) {
            return (x >> 6) & 0xfffff;
        }

        public static int sign_extend(int x) {
            return ((x) << 16) >> 16;
        }

        public static int signed_branch_delta(int x) {
            return sign_extend(x) << 2;
        }

        public static int lo(int x) {
            return x & 0xffff;
        }

        public static long longFromUnsigned(int x) {
            return ((long) x) & 0xffffffffL;
        }
    }
}
