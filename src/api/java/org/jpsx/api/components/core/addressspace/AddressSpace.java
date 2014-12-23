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
package org.jpsx.api.components.core.addressspace;


public interface AddressSpace {
    // we use this when determining addresses as the offset from the prefix (top 16 bits)...
    // this is because the 2M of ram should be repeated 4 times... since no other offsets are close to 2M big, this should be AOK
    int OFFSET_MASK = 0x0f9fffff;
    int RAM_AND = 0x5fffffff & OFFSET_MASK; // value to and main ram address with to make it map to array index*4 (or invalid)

    int SCRATCH_XOR = 0x1f800000; // value to xor scratch address with to make it map to array index*4 (or invalid)
    int BIOS_XOR = 0xbfc00000; // value to xor bios address with to make it map to array index*4 (or invalid)

    int RAM_SIZE = 2 * 1024 * 1024;

    int SCRATCH_BASE = 0x1f800000;
    int SCRATCH_SIZE = 0x1000;
    int SCRATCH_END = SCRATCH_BASE + SCRATCH_SIZE;

    int PAR_BASE = 0x1f000000;
    int PAR_SIZE = 0x10000;
    int PAR_END = PAR_BASE + PAR_SIZE;

    int HW_BASE = 0x1f801000;
    int HW_SIZE = 0x2000;
    int HW_END = HW_BASE + HW_SIZE;

    int BIOS_BASE = 0xBFC00000;
    int BIOS_SIZE = 0x80000;
    int BIOS_END = BIOS_BASE + BIOS_SIZE;

    // Markers for the type of RAM access that an instruction at a particular addresses does
    byte TAG_RAM = 0x01;
    byte TAG_SCRATCH = 0x02;
    byte TAG_HW = 0x04;
    byte TAG_BIOS = 0x08;
    byte TAG_PAR = 0x10;
    byte TAG_POLL = 0x20; // polling detected at this address
    byte TAG_RESERVED_FOR_COMPILER = 0x40; // our compiler happens to need a bit
    byte TAG_RESERVED_FOR_COMPILER_2 = -0x80; // our compiler happens to need a bit

    public static class Util {
        public static boolean isBIOS(int address) {
            return address >= BIOS_BASE && address < BIOS_END;
        }
    }

    // read or write from RAM without any side effects (e.g. hardware IO)
    int internalRead32(int address);
    void internalWrite32(int address, int value);

    int read8(int address);
    int read16(int address);
    int read32(int address);

    void write8(int address, int value);
    void write16(int address, int value);
    void write32(int address, int value);

    /**
     * Called by the SCP to enable/disable writing to RAM
     * @param enable
     */
    void enableMemoryWrite(boolean enable);

    byte getTag(int pc);

    void orTag(int pc, byte val);

    /**
     * Return the array and index in a ResolveResult based on a given address
     * @param address
     * @param result the resolved address information or null if the address is not backed
     */
    void resolve(int address, ResolveResult result);

    /**
     * Return the array and index in a ResolveResult based on a given address,
     * ensuring that both the address & address + size fall within the same memory
     * region
     * @param address base address
     * @param size size in bytes
     * @param write true if this is for write access, false for read
     * @param result the resolved address information or null if the full address range is not backed
     */
    void resolve(int address, int size, boolean write, ResolveResult result);

    /**
     * todo we should allow the address space to return null here if it doesn't want to allow direct access
     * @return the main RAM array to allow for direct access by other components
     */
    int[] getMainRAM();

    void tagAddressAccessWrite(int pc, int address);

    void tagAddressAccessRead8(int pc, int address);

    void tagAddressAccessRead16(int pc, int address);

    void tagAddressAccessRead32(int pc, int address);

    void tagClearPollCounters();

    /**
     * Private interface of static methods for use by compiler are on this class... this is not a full level
     * abstraction, but makes it easier to replace the AddressSpaceImpl with a subclass
     *
     * This can return null if none is provided, though the MultiStageCompiler currently requires it
     */
    public String getMainStaticInterfaceClassName();


    /**
     * Private interface of static methods for use by compiler are on this class... this is not a full level
     * abstraction, but makes it easier to replace the AddressSpaceImpl with a subclass
     *
     * This can return null if none is provided, though the MultiStageCompiler currently requires it
     */
    public String getHardwareStaticInterfaceClassName();

    static class ResolveResult {
        public int address;        // the original address
        public int[] mem;
        public int offset;
        public int low2;
        public byte tag;
    }
}
