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
package org.jpsx.api.components.core.addressspace;


public interface AddressSpace {
    public static final int RAM_AND = 0x5fffffff; // value to and main ram address with to make it map to array index*4 (or invalid)
    public static final int SCRATCH_XOR = 0x1f800000; // value to xor scratch address with to make it map to array index*4 (or invalid)
    public static final int BIOS_XOR = 0xbfc00000; // value to xor bios address with to make it map to array index*4 (or invalid)

    public static final int RAM_SIZE = 2 * 1024 * 1024;

    public static final int SCRATCH_BASE = 0x1f800000;
    public static final int SCRATCH_SIZE = 0x1000;
    public static final int SCRATCH_END = SCRATCH_BASE + SCRATCH_SIZE;

    public static final int PAR_BASE = 0x1f000000;
    public static final int PAR_SIZE = 0x10000;
    public static final int PAR_END = PAR_BASE + PAR_SIZE;

    public static final int HW_BASE = 0x1f801000;
    public static final int HW_SIZE = 0x2000;
    public static final int HW_END = HW_BASE + HW_SIZE;

    public static final int BIOS_BASE = 0xBFC00000;
    public static final int BIOS_SIZE = 0x80000;
    public static final int BIOS_END = BIOS_BASE + BIOS_SIZE;

    byte TAG_RAM = 0x01;
    byte TAG_SCRATCH = 0x02;
    byte TAG_HW = 0x04;
    byte TAG_BIOS = 0x08;
    byte TAG_PAR = 0x10;
    byte TAG_POLL = 0x20;
    byte TAG_RESERVED_FOR_COMPILER = 0x40;

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

    void enableMemoryWrite(boolean enable);

    byte getTag(int pc);

    void orTag(int pc, byte val);

    void resolve(int address, ResolveResult result);

    void resolve(int address, int size, ResolveResult result);

    int[] getMainRAM();

    void tagAddressAccessWrite(int pc, int address);

    void tagAddressAccessRead8(int pc, int address);

    void tagAddressAccessRead16(int pc, int address);

    void tagAddressAccessRead32(int pc, int address);

    void tagClearPollCounters();

    static class ResolveResult {
        public int address;        // the original address
        public int[] mem;
        public int offset;
        public int low2;
        public byte tag;
    }
}
