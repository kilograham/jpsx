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
package org.jpsx.api.components.core.cpu;

/**
 * A {@link CPUInstructionDisassembler} is capable of providing a string representation of an opcode
 * for the purpose of disassembly.
 * <p/>
 * Implementors of this interface are registered during registration via
 * {@link InstructionRegistrar#setInstructionDisassembler(String,CPUInstructionDisassembler)}
 */
public interface CPUInstructionDisassembler {
    /**
     * Return a string representing the disassembly of the instruction
     *
     * @param inst    the decoded instruction resulting from the opcode ci
     * @param address the address of the instruction
     * @param ci      the 32 bit opcode itself
     * @return The String representation of the opcode, which by convention has the instruction name padded with spaces to 8 characters
     *         followed by any instruction arguments
     */
    public String disassemble(CPUInstruction inst, int address, int ci);
}
