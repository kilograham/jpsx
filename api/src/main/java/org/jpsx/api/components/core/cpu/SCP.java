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

/**
 * Interface used by the core implementation to represent actions against the System Control Processor
 */
public interface SCP {
    // R3000 exception constants; very few are actually used currently
    public static final int EXCEPT_INTERRUPT = 0;
    public static final int EXCEPT_TLB_MOD = 1;
    public static final int EXCEPT_TLB_REFILL_L = 2;
    public static final int EXCEPT_TLB_REFILL_S = 3;
    public static final int EXCEPT_ADDRESS_ERROR_L = 4; // load/fetch
    public static final int EXCEPT_ADDRESS_ERROR_S = 5; // store
    public static final int EXCEPT_BUS_ERROR_I = 6;
    public static final int EXCEPT_BUS_ERROR_D = 7;
    public static final int EXCEPT_SYSCALL = 8;
    public static final int EXCEPT_BREAKPOINT = 9;
    public static final int EXCEPT_RESERVED_INSTRUCTION = 10;
    public static final int EXCEPT_COPROCESSOR_UNUSABLE = 11;
    public static final int EXCEPT_OVERFLOW = 12;

    void setInterruptLine(int line, boolean raised);

    void signalReservedInstructionException();

    void signalIntegerOverflowException();

    void signalBreakException();

    void signalSyscallException();

    void signalInterruptException();

    boolean shouldInterrupt();

    int currentExceptionType();
}
