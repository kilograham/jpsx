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
package org.jpsx.runtime.components.hardware.sio;

public abstract class StandardController extends BasicPad {
    protected int padState = 0xffff;

    protected StandardController(String description) {
        super(description);
    }

    public static final int PADLup = (1 << 12);
    public static final int PADLdown = (1 << 14);
    public static final int PADLleft = (1 << 15);
    public static final int PADLright = (1 << 13);
    public static final int PADRup = (1 << 4);
    public static final int PADRdown = (1 << 6);
    public static final int PADRleft = (1 << 7);
    public static final int PADRright = (1 << 5);

    public static final int PADi = (1 << 9);
    public static final int PADj = (1 << 10);
    public static final int PADk = (1 << 8);
    public static final int PADl = (1 << 3);
    public static final int PADm = (1 << 1);
    public static final int PADn = (1 << 2);
    public static final int PADo = (1 << 0);
    public static final int PADh = (1 << 11);

    public static final int PADL1 = PADn;
    public static final int PADL2 = PADo;
    public static final int PADR1 = PADl;
    public static final int PADR2 = PADm;
    public static final int PADstart = PADh;
    public static final int PADselect = PADk;

    public int getType() {
        return 0x41;
    }

    public void getState(byte[] state) {
        state[0] = (byte) ((padState >> 8) & 0xff);
        state[1] = (byte) (padState & 0xff);
    }

    public void pressed(int mask) {
        padState &= ~mask;
//      System.out.println("Controller state now: "+MiscUtil.toHex( padState, 4));
    }

    public void released(int mask) {
        padState |= mask;
//      System.out.println("Controller state now: "+MiscUtil.toHex( padState, 4));
    }
}
