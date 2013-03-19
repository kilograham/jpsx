/*
Copyright (C) 2007 graham sanderson

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation; either version 2
of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
*/
package org.jpsx.api;

/**
 * This interface is used to control (asynchronously) the thread which processes R3000 instructions
 */
public interface CPUControl {
    /**
     * Cause the CPU to run until a breakpoint is hit, or {@link #step()} or {@link #pause()} are called
     */
    void go();

    /**
     * Execute one CPU instruction and then pause
     */
    void step();

    /**
     * Stop executing CPU instructions, until {@link #go()} or {@link #step()} are called
     */
    void pause();

    /**
     * Add a breakpoint at the specified address in the PSX address space
     *
     * @param address
     */
    void addBreakpoint(int address);

    /**
     * Remove a breakpoint at the specified address in the PSX address space
     *
     * @param address
     */
    void removeBreakpoint(int address);

    /**
     * Return all the currently defined breakpoint addresses
     *
     * @return
     */
    int[] getBreakpoints();
}
