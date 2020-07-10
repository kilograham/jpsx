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
package org.jpsx.runtime.components.hardware.cd;

import org.apache.log4j.Logger;

// todo finish this
/**
 * Based on Kazzuya - Audio XA decode
 */
public class XADecoder {
    Logger log = Logger.getLogger("XADecode");
    private static int[] IK0 = new int[]{0, -60, -115, -98, -122};
    private static int[] IK1 = new int[]{0, 0, 52, 55, 60};

    //private static int[] headTable = new int[] {0,2,8,10};
    private static int[] headTable = new int[]{4, 6, 8, 10};

    private static class ChannelContext {
        public int y0;
        public int y1;

        public void reset() {
            y0 = y1 = 0;
        }
    }

    private ChannelContext leftContext = new ChannelContext();
    private ChannelContext rightContext = new ChannelContext();
    private boolean firstSector = false;
    private boolean invalid;
    private int freq;
    private int bps;
    private boolean stereo;

    private static final int BLOCK_SIZE = 28;

    public XADecoder() {
        reset();
    }

    public void reset() {
        leftContext.reset();
        rightContext.reset();
        firstSector = true;
    }

    private void decodeBlock16(ChannelContext context, int filterRange, int[] src, byte[] dest, int destOffset, int inc) {
        int filterId = (filterRange >> 4) & 0x0f;
        int range = (filterRange & 0x0f) + 12;

        filterId &= 3;

        int fy0, fy1;
        fy0 = context.y0;
        fy1 = context.y1;

        int ik0 = IK0[filterId];
        int ik1 = IK1[filterId];

        int s = 0;
        int d = destOffset;
        for (int i = BLOCK_SIZE / 4; i > 0; i--) {
            int x = src[s++];
            int x0 = (x << 28) >> range;
            x0 -= (ik0 * fy0 + ik1 * fy1) >> 6;
            if (x0 > 524072) x0 = 524072;
            else if (x0 < -525088) x0 = 525088;
            dest[d] = (byte) (x0 >> 4);
            dest[d + 1] = (byte) (x0 >> 12);
            d += inc;

            int x1 = ((x << 24) & 0xf0000000) >> range;
            x1 -= (ik0 * x0 + ik1 * fy0) >> 6;
            if (x1 > 524072) x1 = 524072;
            else if (x1 < -525088) x1 = 525088;
            dest[d] = (byte) (x1 >> 4);
            dest[d + 1] = (byte) (x1 >> 12);
            d += inc;

            int x2 = ((x << 20) & 0xf0000000) >> range;
            x2 -= (ik0 * x1 + ik1 * x0) >> 6;
            if (x0 > 524072) x2 = 524072;
            else if (x2 < -525088) x2 = 525088;
            dest[d] = (byte) (x2 >> 4);
            dest[d + 1] = (byte) (x2 >> 12);
            d += inc;

            int x3 = ((x << 16) & 0xf0000000) >> range;
            x3 -= (ik0 * x2 + ik1 * x1) >> 6;
            if (x0 > 524072) x3 = 524072;
            else if (x3 < -525088) x3 = 525088;
            dest[d] = (byte) (x3 >> 4);
            dest[d + 1] = (byte) (x3 >> 12);
            d += inc;
            fy1 = x2;
            fy0 = x3;
        }
        context.y0 = fy0;
        context.y1 = fy1;
    }

    private int encoded[] = new int[14];

    private int decode(byte[] src, int srcOffset, byte[] dest, int destOffset) {
        int sound_groupsp;
        int sound_datap;
        int sound_datap2;
        int nbits = bps == 4 ? 4 : 2;
        int sampleCount = 0;

        if (stereo) {
            for (int j = 0; j < 18; j++) {
                sound_groupsp = srcOffset + j * 128;
                sound_datap = sound_groupsp + 16;

                for (int i = 0; i < nbits; i++) {
                    int datap = 0;
                    sound_datap2 = sound_datap + i;
                    if ((bps == 8) && (freq == 37800)) { // level A
                        for (int k = 0; k < 14; k++, sound_datap2 += 8) {
                            encoded[datap++] = (((int) src[sound_datap2]) & 0xff) |
                                    ((((int) src[sound_datap2 + 4]) & 0xff) << 8);
                        }
                    } else { // level B/C
                        for (int k = 0; k < 7; k++, sound_datap2 += 16) {
                            encoded[datap++] = (((int) src[sound_datap2]) & 0xf) |
                                    ((((int) src[sound_datap2 + 4]) & 0xf) << 4) |
                                    ((((int) src[sound_datap2 + 8]) & 0xf) << 8) |
                                    ((((int) src[sound_datap2 + 12]) & 0xf) << 12);
                        }
                    }
                    decodeBlock16(leftContext, src[sound_groupsp + headTable[i]], encoded, dest, destOffset, 4);

                    datap = 0;
                    sound_datap2 = sound_datap + i;
                    if ((bps == 8) && (freq == 37800)) { // level A
                        for (int k = 0; k < 14; k++, sound_datap2 += 8) {
                            encoded[datap++] = (((int) src[sound_datap2]) & 0xff) |
                                    ((((int) src[sound_datap2 + 4]) & 0xff) << 8);
                        }
                    } else { // level B/C
                        for (int k = 0; k < 7; k++, sound_datap2 += 16) {
                            encoded[datap++] = ((((int) src[sound_datap2]) & 0xf0) |
                                    ((((int) src[sound_datap2 + 4]) & 0xf0) << 4) |
                                    ((((int) src[sound_datap2 + 8]) & 0xf0) << 8) |
                                    ((((int) src[sound_datap2 + 12]) & 0xf0) << 12)) >> 4;
                        }
                    }
                    decodeBlock16(rightContext, src[sound_groupsp + headTable[i] + 1], encoded, dest, destOffset + 2, 4);
                    destOffset += 28 * 4;
                    sampleCount += 28;
                }
            }
        } else { // mono
            for (int j = 0; j < 18; j++) {
                sound_groupsp = srcOffset + j * 128;
                sound_datap = sound_groupsp + 16;

                for (int i = 0; i < nbits; i++) {
                    int datap = 0;
                    sound_datap2 = sound_datap + i;
                    if ((bps == 8) && (freq == 37800)) { // level A
                        for (int k = 0; k < 14; k++, sound_datap2 += 8) {
                            encoded[datap++] = (((int) src[sound_datap2]) & 0xff) |
                                    ((((int) src[sound_datap2 + 4]) & 0xff) << 8);
                        }
                    } else { // level B/C
                        for (int k = 0; k < 7; k++, sound_datap2 += 16) {
                            encoded[datap++] = (((int) src[sound_datap2]) & 0xf) |
                                    ((((int) src[sound_datap2 + 4]) & 0xf) << 4) |
                                    ((((int) src[sound_datap2 + 8]) & 0xf) << 8) |
                                    ((((int) src[sound_datap2 + 12]) & 0xf) << 12);
                        }
                    }
                    decodeBlock16(leftContext, src[sound_groupsp + headTable[i]], encoded, dest, destOffset, 4);
                    destOffset += 28 * 4;
                    sampleCount += 28;

                    datap = 0;
                    sound_datap2 = sound_datap + i;
                    if ((bps == 8) && (freq == 37800)) { // level A
                        for (int k = 0; k < 14; k++, sound_datap2 += 8) {
                            encoded[datap++] = (((int) src[sound_datap2]) & 0xff) |
                                    ((((int) src[sound_datap2 + 4]) & 0xff) << 8);
                        }
                    } else { // level B/C
                        for (int k = 0; k < 7; k++, sound_datap2 += 16) {
                            encoded[datap++] = ((((int) src[sound_datap2]) & 0xf0) |
                                    ((((int) src[sound_datap2 + 4]) & 0xf0) << 4) |
                                    ((((int) src[sound_datap2 + 8]) & 0xf0) << 8) |
                                    ((((int) src[sound_datap2 + 12]) & 0xf0) << 12)) >> 4;
                        }
                    }
                    decodeBlock16(leftContext, src[sound_groupsp + headTable[i] + 1], encoded, dest, destOffset, 4);
                    destOffset += 28 * 4;
                    sampleCount += 28;
                }
            }
/*            for (j=0; j < 18; j++) {
                sound_groupsp = srcp + j * 128;		// sound groups header
                sound_datap = sound_groupsp + 16;	// sound data just after the header

                for (i=0; i < nbits; i++) {
                    datap = data;
                    sound_datap2 = sound_datap + i;
                    if ((xdp->nbits == 8) && (xdp->freq == 37800)) { // level A
                        for (k=0; k < 14; k++, sound_datap2 += 8) {
                                   *(datap++) = (U16)sound_datap2[0] |
                                                (U16)(sound_datap2[4] << 8);
                        }
                    } else { // level B/C
                        for (k=0; k < 7; k++, sound_datap2 += 16) {
                                   *(datap++) = (U16)(sound_datap2[ 0] & 0x0f) |
                                               ((U16)(sound_datap2[ 4] & 0x0f) <<  4) |
                                               ((U16)(sound_datap2[ 8] & 0x0f) <<  8) |
                                               ((U16)(sound_datap2[12] & 0x0f) << 12);
                        }
                    }
                    ADPCM_DecodeBlock16( &xdp->left,  sound_groupsp[headtable[i]+0], data,
                                               destp, 1 );

                    destp += 28;

                    datap = data;
                    sound_datap2 = sound_datap + i;
                    if ((xdp->nbits == 8) && (xdp->freq == 37800)) { // level A
                        for (k=0; k < 14; k++, sound_datap2 += 8) {
                                   *(datap++) = (U16)sound_datap2[0] |
                                                (U16)(sound_datap2[4] << 8);
                        }
                    } else { // level B/C
                        for (k=0; k < 7; k++, sound_datap2 += 16) {
                                *(datap++) = (U16)(sound_datap2[ 0] >> 4) |
                                               ((U16)(sound_datap2[ 4] >> 4) <<  4) |
                                            ((U16)(sound_datap2[ 8] >> 4) <<  8) |
                                            ((U16)(sound_datap2[12] >> 4) << 12);
                            }
                    }
                       ADPCM_DecodeBlock16( &xdp->left,  sound_groupsp[headtable[i]+1], data,
                                               destp, 1 );

                    destp += 28;
                }
            }

            */
//            throw new IllegalStateException("mono");
        }
        return sampleCount;
    }

    public int decodeXAAudioSector(byte[] sector, byte[] samples) {
        if (firstSector) {
            int coding = sector[19];
            switch ((coding >> 2) & 3) {
                case 0:
                    freq = 37800;
                    break;
                case 1:
                    freq = 18900;
                    break;
                default:
                    invalid = true;
                    break;
            }
            switch ((coding >> 4) & 3) {
                case 0:
                    bps = 4;
                    break;
                case 1:
                    bps = 8;
                    break;
                default:
                    invalid = true;
                    break;
            }
            switch (coding & 3) {
                case 0:
                    stereo = false;
                    break;
                case 1:
                    stereo = true;
                    break;
                default:
                    invalid = true;
                    break;
            }
            if (invalid) {
                log.debug("INVALID XA");
            } else {
                log.debug("XA " + freq + "x" + bps + " " + (stereo ? "stereo" : "mono"));
            }
            reset();
            firstSector = false;
        }
        if (invalid) {
            return 0;
        }
        return decode(sector, 24, samples, 0);
    }

    public int getFrequency() {
        return freq;
    }
}