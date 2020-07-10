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

import org.apache.log4j.Logger;
import org.jpsx.api.components.core.addressspace.AddressSpace;
import org.jpsx.api.components.core.addressspace.AddressSpaceRegistrar;
import org.jpsx.api.components.core.addressspace.MemoryMapped;
import org.jpsx.api.components.core.dma.DMAController;
import org.jpsx.runtime.JPSXComponent;
import org.jpsx.runtime.components.core.CoreComponentConnections;
import org.jpsx.runtime.components.core.DMAChannelOwnerBase;
import org.jpsx.runtime.util.MiscUtil;

public class MDEC extends JPSXComponent implements MemoryMapped {
    private static final Logger log = Logger.getLogger("MDEC");

    private static final boolean debugMDEC = log.isDebugEnabled();

    private static final int ADDR_MDEC_CTRL = 0x1f801820;
    private static final int ADDR_MDEC_STATUS = 0x1f801824;

    // abe mdec_in_sync waits for bit  0x20000000 to clear
    // abe mdec_out_sync waits for bit 0x01000000 to clear
    private static final int NFIFO0 = 0x80000000;
    private static final int FIFO1 = 0x40000000;
    private static final int BUSY0 = 0x20000000;
    private static final int DREQ0 = 0x10000000;
    private static final int DREQ1 = 0x08000000;
    private static final int RGB24 = 0x02000000;
    private static final int BUSY1 = 0x01000000;
    private static final int STP = 0x00800000;

//    private static final int BUSY0  = 0x80000000;
//    private static final int DREQ0  = 0x40000000;
//    private static final int DREQ1  = 0x20000000;
    //    private static final int RGB24  = 0x08000000;
    //    private static final int BUSY1  = 0x04000000;
    //    private static final int STP    = 0x02000000;
    private static final int CTRL_RGB24 = 0x08000000;
    private static final int CTRL_STP = 0x02000000;

    private static final int[] yqm = new int[64];
    private static final int[] uvqm = new int[64];

    private static final int unzig[] = new int[]{
            0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5, 12,
            19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28, 35,
            42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
            58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63
    };

    private static AddressSpace addressSpace;
    private static int ctrl;
    private static int status;
    private static int stp;
    private static AddressSpace.ResolveResult source = new AddressSpace.ResolveResult();
    private static int sourceRemaining;

    public MDEC() {
        super("JPSX Movie Decoder");
    }

    @Override
    public void init() {
        super.init();
        CoreComponentConnections.ALL_MEMORY_MAPPED.add(this);
        CoreComponentConnections.DMA_CHANNEL_OWNERS.add(new InChannel());
        CoreComponentConnections.DMA_CHANNEL_OWNERS.add(new OutChannel());
    }

    @Override
    public void resolveConnections() {
        super.resolveConnections();
        addressSpace = CoreComponentConnections.ADDRESS_SPACE.resolve();
    }

    public void registerAddresses(AddressSpaceRegistrar registrar) {
        registrar.registerWrite32Callback(ADDR_MDEC_CTRL, MDEC.class, "writeCtrl32");
        registrar.registerWrite32Callback(ADDR_MDEC_STATUS, MDEC.class, "writeStatus32");
        registrar.registerRead32Callback(ADDR_MDEC_CTRL, MDEC.class, "readCtrl32");
        registrar.registerRead32Callback(ADDR_MDEC_STATUS, MDEC.class, "readStatus32");
    }

    public static void writeStatus32(int address, int value) {
        if (debugMDEC) log.debug("MDEC status write32 " + MiscUtil.toHex(value, 8));
        if (value == 0x80000000) {
            if (debugMDEC) log.debug("MDEC turn off FIFO");
            status = value;
        } else if (value == 0x60000000) {
            if (debugMDEC) log.debug("MDEC turn on FIFO");
            status = value & ~BUSY0;
        } else {
            throw new IllegalStateException("MDEC unknown status write " + MiscUtil.toHex(value, 8));
        }
    }

    public static void writeCtrl32(int address, int value) {
        if (debugMDEC) log.debug("MDEC ctrl write32 " + MiscUtil.toHex(value, 8));

        ctrl = value;
        if (0 != (ctrl & CTRL_RGB24)) {
            status &= ~RGB24;
        } else {
            status |= RGB24;
        }
        if (0 != (ctrl & CTRL_STP)) {
            status |= STP;
            stp = 0x8000;
        } else {
            status &= ~STP;
            stp = 0;
        }
    }

    public static int readStatus32(int address) {
        int rc = status;
        if (debugMDEC) log.debug("MDEC status read32 " + MiscUtil.toHex(rc, 8));
        return rc;
    }

    public static int readCtrl32(int address) {
        //int rc = m_ctrl;
        //if (debugMDEC) log.debug("MDEC ctrl read32 "+MiscUtil.toHex( rc, 8));
        //return rc;
        throw new IllegalStateException("wahhh?");
    }

    private static class InChannel extends DMAChannelOwnerBase {
        public final int getDMAChannel() {
            return DMAController.DMA_MDEC_IN;
        }

        public final String getName() {
            return "MDEC-In";
        }

        public void beginDMATransferToDevice(int base, int blocks, int blockSize, int ctrl) {
            if (debugMDEC)
                log.debug("begin DMA transfer to " + getName() + " " + MiscUtil.toHex(base, 8) + " 0x" + Integer.toHexString(blocks) + "*0x" + Integer.toHexString(blockSize) + " ctrl " + MiscUtil.toHex(ctrl, 8));
            int size = blocks * blockSize;

            if (MDEC.ctrl == 0x60000000) {
                if (debugMDEC) log.debug("INIT COSINE TABLE size=" + MiscUtil.toHex(size, 4));
            } else if (MDEC.ctrl == 0x40000001) {
                if (debugMDEC) log.debug("INIT QTABLE size=" + MiscUtil.toHex(size, 4));
                for (int i = 0; i < 64; i++) {
                    yqm[i] = addressSpace.read8(base + i);
                }
                for (int i = 0; i < 64; i++) {
                    uvqm[i] = addressSpace.read8(base + 64 + i);
                }
                // todo figure these commands out
//            } else if (0!=(m_ctrl & BUSY0) && (0!=(m_ctrl&DREQ0)) && 0==(m_ctrl & NFIFO0)) {
            } else {
                //log.debug("MDEC cmd "+MiscUtil.toHex( m_ctrl, 8));
                if (debugMDEC)
                    log.debug("INCOMING DATA FROM " + MiscUtil.toHex(base, 8) + " SIZE = " + MiscUtil.toHex(size, 8) + " low16 = " + MiscUtil.toHex(MDEC.ctrl & 0xffff, 4));

                //m_status |= BUSY0;
//                m_status &= ~DREG0;
//                m_status &= ~NFIFO0;

                addressSpace.resolve(base, size * 4, true, source);
                sourceRemaining = size * 4;
//            } else {
//                throw new IllegalStateException("unknown MDEC ctrl = "+MiscUtil.toHex( m_ctrl, 8));
            }
            signalTransferComplete();
        }

        public void beginDMATransferFromDevice(int base, int blocks, int blockSize, int ctrl) {
            throw new IllegalStateException("unknown mdec dma from channel 0");
            //if (debugMDEC) log.debug( "begin DMA transfer from "+getName()+" "+MiscUtil.toHex( base, 8)+" 0x"+Integer.toHexString(blocks)+"*0x"+Integer.toHexString( blockSize)+" ctrl "+MiscUtil.toHex( ctrl, 8));
            //signalTransferComplete();
        }

        public void cancelDMATransfer(int ctrl) {
            if (debugMDEC) log.debug("cancel " + getName() + " DMA transfer");
        }
    }

    //private static final int SIGNED10BITS(x)
    //{
    //    return (x<<22)>>22;
    //}

    private static class OutChannel extends DMAChannelOwnerBase {
        private final int[] ycoeffs = new int[64];
        private final int[] ucoeffs = new int[64];
        private final int[] vcoeffs = new int[64];

        public final int getDMAChannel() {
            return DMAController.DMA_MDEC_OUT;
        }

        public final String getName() {
            return "MDEC-Out";
        }

        public void beginDMATransferToDevice(int base, int blocks, int blockSize, int ctrl) {
            throw new IllegalStateException("unknown mdec dma to channel 1");
//            if (debugMDEC) log.debug( "begin DMA transfer to "+getName()+" "+MiscUtil.toHex( base, 8)+" 0x"+Integer.toHexString(blocks)+"*0x"+Integer.toHexString( blockSize)+" ctrl "+MiscUtil.toHex( ctrl, 8));
//            signalTransferComplete();
        }


        private static int rgb15(int y, int v, int u) {
            int r = y + ((0x0000059B * u) >> 10);
            int g = y + ((-0x15F * v) >> 10) + ((-0x2DB * u) >> 10);
            int b = y + ((0x00000716 * v) >> 10);
            if (r < 0) r = 0;
            if (r > 255) r = 255;
            if (g < 0) g = 0;
            if (g > 255) g = 255;
            if (b < 0) b = 0;
            if (b > 255) b = 255;
            return stp | ((b >> 3) << 10) + ((g >> 3) << 5) + (r >> 3);
        }

        private static int rgb24(int y, int v, int u) {
            int r = y + ((0x0000059B * u) >> 10);
            int g = y + ((-0x15F * v) >> 10) + ((-0x2DB * u) >> 10);
            int b = y + ((0x00000716 * v) >> 10);
            if (r < 0) r = 0;
            if (r > 255) r = 255;
            if (g < 0) g = 0;
            if (g > 255) g = 255;
            if (b < 0) b = 0;
            if (b > 255) b = 255;
            return (r << 16) | (g << 8) | b;
        }

        public void beginDMATransferFromDevice(int base, int blocks, int blockSize, int ctrl) {
            if (debugMDEC)
                log.debug("begin DMA transfer from " + getName() + " " + MiscUtil.toHex(base, 8) + " 0x" + Integer.toHexString(blocks) + "*0x" + Integer.toHexString(blockSize) + " ctrl " + MiscUtil.toHex(ctrl, 8));

            //assert 0!=(m_status&FIFO1);
            //          if (0==(m_status&FIFO1)) {
            //            m_status|=NFIFO0;
            //          signalTransferComplete();
            //    }
            //int baseTime = MTScheduler.getTime();
            //log.debug("configure mdec out time = "+baseTime);
            int size = blocks * blockSize * 4;

            boolean rgb24 = 0 != (status & RGB24);
            int mbsize = 256 * (rgb24 ? 3 : 2);
            int mbcount = size / mbsize;

            AddressSpace.ResolveResult target = new AddressSpace.ResolveResult();
            addressSpace.resolve(base, size, false, target);

            int dword = source.mem[source.offset];

            outer:
            for (; mbcount > 0; mbcount--) {
//                // make IRQ keep happening for sake of CD streaming
//                r3000.checkAndHandleBreakout();
                if (debugMDEC) log.debug("mbcount " + mbcount + " remaining " + sourceRemaining);
                for (int mb = 0; mb < 6; mb++) {
                    int word;
                    if (sourceRemaining <= 0) break outer;
                    if (0 == (sourceRemaining & 2)) {
                        dword = source.mem[source.offset];
                        word = dword & 0xffff;
                    } else {
                        source.offset++;
                        word = (dword >> 16) & 0xffff;
                    }
                    sourceRemaining -= 2;

                    int dc = (word & 0x3ff) ^ 0x200;
                    int qf = word >> 10;

                    //if (mbcount==(size/mbsize)) dc = 0;

                    int[] coeffs;
                    int[] qm;
                    if (mb == 0) {
                        coeffs = vcoeffs;
                        qm = uvqm;
                    } else if (mb == 1) {
                        coeffs = ucoeffs;
                        qm = uvqm;
                    } else {
                        coeffs = ycoeffs;
                        qm = yqm;
                    }

                    for (int i = 1; i < 64; i++) coeffs[i] = 0;
                    coeffs[0] = dc * qm[0];

                    int index = 0;
                    do {
                        if (sourceRemaining <= 0) break outer;
                        if (0 == (sourceRemaining & 2)) {
                            dword = source.mem[source.offset];
                            word = dword & 0xffff;
                        } else {
                            source.offset++;
                            word = (dword >> 16) & 0xffff;
                        }
                        sourceRemaining -= 2;
                        if (word == 0xfe00)
                            break;
                        index += (word >> 10) + 1;
                        coeffs[unzig[index]] = (qf * ((word << 22) >> 22) * qm[index]) >> 3;
                    } while (true);

                    IDCT.transform(coeffs);

                    final int[] mem = target.mem;
                    if (rgb24) {
                        if (mb >= 2) {
                            int uvoffset = 0;
                            int which = mb - 2;
                            int offset = target.offset;
                            if (0 != (which & 1)) {
                                offset += 6;
                                uvoffset += 4;
                            }
                            if (0 != (which & 2)) {
                                offset += 96;
                                uvoffset += 32;
                            }
                            for (int i = 0; i < 64; i += 4) {
                                int u0 = ucoeffs[uvoffset] - 128;
                                int u1 = ucoeffs[uvoffset + 1] - 128;
                                int v0 = vcoeffs[uvoffset] - 128;
                                int v1 = vcoeffs[uvoffset + 1] - 128;

                                int p0 = coeffs[i];
                                int p1 = coeffs[i + 1];
                                int p2 = coeffs[i + 2];
                                int p3 = coeffs[i + 3];
                                int q0 = coeffs[i + 8];
                                int q1 = coeffs[i + 9];
                                int q2 = coeffs[i + 10];
                                int q3 = coeffs[i + 11];

                                p0 = rgb24(p0, u0, v0);
                                p1 = rgb24(p1, u0, v0);
                                p2 = rgb24(p2, u1, v1);
                                p3 = rgb24(p3, u1, v1);

                                mem[offset] = p0 | (p1 << 24);                 // b1r0g0b0
                                mem[offset + 1] = ((p1 >> 8) & 0xffff) | (p2 << 16); // g2b2r1g1
                                mem[offset + 2] = ((p2 >> 16) & 0xff) | (p3 << 8);   // r3g3b3r2

                                q0 = rgb24(q0, u0, v0);
                                q1 = rgb24(q1, u0, v0);
                                q2 = rgb24(q2, u1, v1);
                                q3 = rgb24(q3, u1, v1);

                                mem[offset + 12] = q0 | (q1 << 24);
                                mem[offset + 13] = ((q1 >> 8) & 0xffff) | (q2 << 16);
                                mem[offset + 14] = ((q2 >> 16) & 0xff) | (q3 << 8);

                                offset += 3;
                                uvoffset += 2;
                                if (4 == (i & 7)) {
                                    i += 8;
                                    offset += 18;
                                    uvoffset += 4;
                                }
                            }
                        }
                    } else {
                        if (mb >= 2) {
                            int uvoffset = 0;
                            int which = mb - 2;
                            int offset = target.offset;
                            if (0 != (which & 1)) {
                                offset += 4;
                                uvoffset += 4;
                            }
                            if (0 != (which & 2)) {
                                offset += 64;
                                uvoffset += 32;
                            }
                            for (int i = 0; i < 64; i += 4) {
                                int u0 = ucoeffs[uvoffset] - 128;
                                int u1 = ucoeffs[uvoffset + 1] - 128;
                                int v0 = vcoeffs[uvoffset] - 128;
                                int v1 = vcoeffs[uvoffset + 1] - 128;

                                int p0 = coeffs[i];
                                int p1 = coeffs[i + 1];
                                int p2 = coeffs[i + 2];
                                int p3 = coeffs[i + 3];
                                int q0 = coeffs[i + 8];
                                int q1 = coeffs[i + 9];
                                int q2 = coeffs[i + 10];
                                int q3 = coeffs[i + 11];

                                p0 = rgb15(p0, u0, v0);
                                p1 = rgb15(p1, u0, v0);
                                p2 = rgb15(p2, u1, v1);
                                p3 = rgb15(p3, u1, v1);

                                mem[offset] = p0 | (p1 << 16);
                                mem[offset + 1] = p2 | (p3 << 16);

                                q0 = rgb15(q0, u0, v0);
                                q1 = rgb15(q1, u0, v0);
                                q2 = rgb15(q2, u1, v1);
                                q3 = rgb15(q3, u1, v1);

                                mem[offset + 8] = q0 | (q1 << 16);
                                mem[offset + 9] = q2 | (q3 << 16);

                                offset += 2;
                                uvoffset += 2;
                                if (4 == (i & 7)) {
                                    i += 8;
                                    offset += 12;
                                    uvoffset += 4;
                                }
                            }
                        }
                    }
                }
                target.offset += mbsize >> 2;
            }


            if (sourceRemaining == 0) {
                if (debugMDEC) log.debug("COMPLETE!!");

                status &= ~BUSY0;
                status &= ~BUSY1;
            }

            signalTransferComplete();
            //log.debug("end mdec out dtime = "+(MTScheduler.getTime()-baseTime));
        }

        public void cancelDMATransfer(int ctrl) {
            if (debugMDEC) log.debug("cancel " + getName() + " DMA transfer");
        }
    }
}