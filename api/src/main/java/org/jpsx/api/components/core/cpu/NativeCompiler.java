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
 * Interface to be implemented by an instruction compiler.
 * <p/>
 * The compiler's job is to provide faster execution of R3000
 * code than is possible by simple instruction by instruction
 * interpretation.
 */
public interface NativeCompiler {

    /**
     * Enter compiler.
     * <p/>
     * This method is called by the interpreter for every interpreted JAL after
     * the delay slot has been executed. i.e. address is the JAL target.
     * <p/>
     * The compiler may continue execution from this point if it is able to, or
     * may ask the interpreter to continue (e.g. if methods are only compiled
     * after a certain number of invocations).
     * <p/>
     * The compiler, if it decides to enter compiled code, should continue executing
     * until the corresponding JR to the specified return address (i.e. return), or may throw a ContinueExecutionException
     * if it needs to return execution flow to the interpreter for any other reason.
     * <p/>
     * Note, that the compiler may be entered recursively; e.g. once for main
     * execution flow, and then later for an interrupt.
     *
     * @return true - if the compiler execute code until the "return"
     *         false - if the compiler did not execute any code.
     * @throws org.jpsx.api.components.core.ContinueExecutionException
     *          if the compiler wishes the interpeter to take over execution
     */
    public boolean jumpAndLink(int address, int returnAddress);

    /**
     * Method called whenever the R3000 instruction cache is flushed.
     * The compiler should assume that any instructions in RAM may have been
     * modified
     */
    public void clearCache();

    /**
     * Determine if a thrown exception is OK; some compilers
     * may allow some exceptions e.g. NullPointerException in the normal
     * course of operation, and use them when they occur to update compiled
     * code.
     * <p/>
     * Note the compiler is responsible for restoring the interpreter state
     * in either case. Note this method is also called for ReturnFromExceptionException
     * and ContinueExecutionException to allow the compiler to know
     * that it has been "thrown out of".
     *
     * @return true
     *         if the exception was handled by the compiler, and emulation should continue
     *         in the interpreter
     */
    public boolean exceptionInCompiler(Throwable t);

    /**
     * Called by the JPSX system if there is some interruption to execution
     * flow that requires the compiler to return control temporarily. This
     * includes, but is not limited to R3000 interrupts.
     * <p/>
     * The compiler should call {@link R3000#compilerInterrupted} from the execution
     * thread as soon as possible.
     * <p/>
     * Note, this method is generally not called from the execution thread, though
     * in some circumstances it could be.
     * <p/>
     * On return from {@link R3000#compilerInterrupted}, the compiler should be
     * aware that clearCache() or restoreInterpreterState() may
     * have been called in the meanwhile.
     */
    public void interrupt();

    /**
     * Adds a breakpoint at the specified address.
     * <p/>
     * If the compiler is able to handle breakpoints it should make sure that the
     * execution returns to the interpreter on or before this address
     * <p/>
     * The simplest implementation of this function would be to perform no
     * compiled execution while there are any breakpoints set.
     *
     * @return true
     *         if the compiler will be able to handle the breakpoint
     */
    public boolean addBreakpoint(int address);

    /**
     * Removes the breakpoint from the specified address
     */
    public void removeBreakpoint(int address);

    /**
     * If the compiler is keeping any local copies
     * of registers etc., it should restore them
     * in response to this call
     */
    public void restoreInterpreterState();

    public int getReg(int index);

    public void setReg(int index, int value );
}
