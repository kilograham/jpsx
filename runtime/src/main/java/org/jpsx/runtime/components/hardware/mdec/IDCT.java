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
package org.jpsx.runtime.components.hardware.mdec;

public class IDCT {
    private static final int W1 = 2841; // 2048*sqrt(2)*cos(1*pi/16)
    private static final int W2 = 2676; // 2048*sqrt(2)*cos(2*pi/16)
    private static final int W3 = 2408; // 2048*sqrt(2)*cos(3*pi/16)
    private static final int W5 = 1609; // 2048*sqrt(2)*cos(5*pi/16)
    private static final int W6 = 1108; // 2048*sqrt(2)*cos(6*pi/16)
    private static final int W7 = 565; // 2048*sqrt(2)*cos(7*pi/16)

    private static final int clamp[] = new int[2048];

    private static void idctrow(int[] blk, int base) {
        int x0, x1, x2, x3, x4, x5, x6, x7, x8;

        if (0 == ((x1 = blk[base + 4] << 11) | (x2 = blk[base + 6]) | (x3 = blk[base + 2]) |
                (x4 = blk[base + 1]) | (x5 = blk[base + 7]) | (x6 = blk[base + 5]) | (x7 = blk[base + 3]))) {
            blk[base + 0] = blk[base + 1] = blk[base + 2] = blk[base + 3] = blk[base + 4] = blk[base + 5] = blk[base + 6] = blk[base + 7] = blk[base + 0] << 3;
            return;
        }

        x0 = (blk[base + 0] << 11) + 128;

        x8 = W7 * (x4 + x5);
        x4 = x8 + (W1 - W7) * x4;
        x5 = x8 - (W1 + W7) * x5;
        x8 = W3 * (x6 + x7);
        x6 = x8 - (W3 - W5) * x6;
        x7 = x8 - (W3 + W5) * x7;

        x8 = x0 + x1;
        x0 -= x1;
        x1 = W6 * (x3 + x2);
        x2 = x1 - (W2 + W6) * x2;
        x3 = x1 + (W2 - W6) * x3;
        x1 = x4 + x6;
        x4 -= x6;
        x6 = x5 + x7;
        x5 -= x7;

        x7 = x8 + x3;
        x8 -= x3;
        x3 = x0 + x2;
        x0 -= x2;
        x2 = (181 * (x4 + x5) + 128) >> 8;
        x4 = (181 * (x4 - x5) + 128) >> 8;

        blk[base + 0] = (x7 + x1) >> 8;
        blk[base + 1] = (x3 + x2) >> 8;
        blk[base + 2] = (x0 + x4) >> 8;
        blk[base + 3] = (x8 + x6) >> 8;
        blk[base + 4] = (x8 - x6) >> 8;
        blk[base + 5] = (x0 - x4) >> 8;
        blk[base + 6] = (x3 - x2) >> 8;
        blk[base + 7] = (x7 - x1) >> 8;
    }

    private static void idctcol(int[] blk, int base) {
        int x0, x1, x2, x3, x4, x5, x6, x7, x8;

        if (0 == ((x1 = (blk[base + 8 * 4] << 8)) | (x2 = blk[base + 8 * 6]) | (x3 = blk[base + 8 * 2]) |
                (x4 = blk[base + 8 * 1]) | (x5 = blk[base + 8 * 7]) | (x6 = blk[base + 8 * 5]) | (x7 = blk[base + 8 * 3]))) {
            blk[base + 8 * 0] = blk[base + 8 * 1] = blk[base + 8 * 2] = blk[base + 8 * 3] = blk[base + 8 * 4] = blk[base + 8 * 5] = blk[base + 8 * 6] = blk[base + 8 * 7] =
                    clamp[1024 + ((blk[base + 8 * 0] + 32) >> 6)];
            return;
        }

        x0 = (blk[base + 8 * 0] << 8) + 8192;

        x8 = W7 * (x4 + x5) + 4;
        x4 = (x8 + (W1 - W7) * x4) >> 3;
        x5 = (x8 - (W1 + W7) * x5) >> 3;
        x8 = W3 * (x6 + x7) + 4;
        x6 = (x8 - (W3 - W5) * x6) >> 3;
        x7 = (x8 - (W3 + W5) * x7) >> 3;

        x8 = x0 + x1;
        x0 -= x1;
        x1 = W6 * (x3 + x2) + 4;
        x2 = (x1 - (W2 + W6) * x2) >> 3;
        x3 = (x1 + (W2 - W6) * x3) >> 3;
        x1 = x4 + x6;
        x4 -= x6;
        x6 = x5 + x7;
        x5 -= x7;

        x7 = x8 + x3;
        x8 -= x3;
        x3 = x0 + x2;
        x0 -= x2;
        x2 = (181 * (x4 + x5) + 128) >> 8;
        x4 = (181 * (x4 - x5) + 128) >> 8;

        blk[base + 8 * 0] = clamp[1024 + ((x7 + x1) >> 14)];
        blk[base + 8 * 1] = clamp[1024 + ((x3 + x2) >> 14)];
        blk[base + 8 * 2] = clamp[1024 + ((x0 + x4) >> 14)];
        blk[base + 8 * 3] = clamp[1024 + ((x8 + x6) >> 14)];
        blk[base + 8 * 4] = clamp[1024 + ((x8 - x6) >> 14)];
        blk[base + 8 * 5] = clamp[1024 + ((x0 - x4) >> 14)];
        blk[base + 8 * 6] = clamp[1024 + ((x3 - x2) >> 14)];
        blk[base + 8 * 7] = clamp[1024 + ((x7 - x1) >> 14)];
    }

    public static void transform(int[] block) {
        for (int i = 0; i < 64; i += 8)
            idctrow(block, i);

        for (int i = 0; i < 8; i++)
            idctcol(block, i);
    }

    static {
        for (int i = 0; i < 2048; i++) {
            if (i < 1024) {
                clamp[i] = 0;
            } else if (i < (1024 + 256)) {
                clamp[i] = i - 1024;
            } else {
                clamp[i] = 255;
            }
        }
    }
}
