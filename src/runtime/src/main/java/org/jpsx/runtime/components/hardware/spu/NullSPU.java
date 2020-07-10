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
package org.jpsx.runtime.components.hardware.spu;

import org.jpsx.api.components.core.addressspace.AddressSpaceRegistrar;
import org.jpsx.api.components.core.addressspace.MemoryMapped;
import org.jpsx.api.components.core.dma.DMAController;
import org.jpsx.runtime.SingletonJPSXComponent;
import org.jpsx.runtime.components.core.CoreComponentConnections;
import org.jpsx.runtime.components.core.DMAChannelOwnerBase;
import org.jpsx.runtime.util.MiscUtil;

/**
 * Empty SPU that does enough to not prevent games from working at all
 */
public class NullSPU extends SingletonJPSXComponent implements MemoryMapped {
    protected static final int ADDR_VOICES = 0x1f801c00;

    protected static final int ADDR_MAIN_VOL_L = 0x1f801d80;
    protected static final int ADDR_MAIN_VOL_R = 0x1f801d82;
    protected static final int ADDR_REVERB_L = 0x1f801d84;
    protected static final int ADDR_REVERB_R = 0x1f801d86;
    protected static final int ADDR_CHANNEL_ON0 = 0x1f801d88;
    protected static final int ADDR_CHANNEL_ON1 = 0x1f801d8a;
    protected static final int ADDR_CHANNEL_OFF0 = 0x1f801d8c;
    protected static final int ADDR_CHANNEL_OFF1 = 0x1f801d8e;
    protected static final int ADDR_CHANNEL_FM0 = 0x1f801d90;
    protected static final int ADDR_CHANNEL_FM1 = 0x1f801d92;
    protected static final int ADDR_CHANNEL_NOISE0 = 0x1f801d94;
    protected static final int ADDR_CHANNEL_NOISE1 = 0x1f801d96;
    protected static final int ADDR_CHANNEL_REVERB0 = 0x1f801d98;
    protected static final int ADDR_CHANNEL_REVERB1 = 0x1f801d9a;
    protected static final int ADDR_CHANNEL_MUTE0 = 0x1f801d9c;
    protected static final int ADDR_CHANNEL_MUTE1 = 0x1f801d9e;

    protected static final int ADDR_TRANSFER_ADDR = 0x1f801da6;
    protected static final int ADDR_TRANSFER_DATA = 0x1f801da8;

    protected static final int ADDR_SPU_CTRL = 0x1f801daa;
    protected static final int ADDR_SPU_STATUS = 0x1f801dae;

    protected static final int ADDR_CD_VOL_L = 0x1f801db0;
    protected static final int ADDR_CD_VOL_R = 0x1f801db2;

    protected static final int VOICES = 24;

    protected static final int VOICE_VOL_L = 0x0;
    protected static final int VOICE_VOL_R = 0x2;
    protected static final int VOICE_PITCH = 0x4;
    protected static final int VOICE_START_OFFSET = 0x6;
    protected static final int VOICE_ADS_LEVEL = 0x8;
    protected static final int VOICE_SR_RATE = 0xa;
    protected static final int VOICE_ADSR_VOL = 0xc;
    protected static final int VOICE_REPEAT_OFFSET = 0xe;

    protected static int m_ctrl;
    protected static int m_transferAddress;

    public NullSPU() {
        super("JPSX Null (no sound) SPU");
    }


    @Override
    public void init() {
        super.init();
        CoreComponentConnections.ALL_MEMORY_MAPPED.add(this);
        CoreComponentConnections.DMA_CHANNEL_OWNERS.add(new SPUDMAChannel());
    }

    // figure out RAM
    public void registerAddresses(AddressSpaceRegistrar registrar) {

        for (int i = 0; i < VOICES; i++) {
            int base = ADDR_VOICES + i * 0x10;
            registrar.registerWrite16Callback(base + VOICE_VOL_L, NullSPU.class, "writeVolLeft");
            registrar.registerWrite16Callback(base + VOICE_VOL_R, NullSPU.class, "writeVolRight");
            registrar.registerWrite16Callback(base + VOICE_PITCH, NullSPU.class, "writePitch");
            registrar.registerWrite16Callback(base + VOICE_START_OFFSET, NullSPU.class, "writeStartOffset");
            registrar.registerWrite16Callback(base + VOICE_ADS_LEVEL, NullSPU.class, "writeADSLevel");
            registrar.registerWrite16Callback(base + VOICE_SR_RATE, NullSPU.class, "writeSRRate");
            registrar.registerWrite16Callback(base + VOICE_ADSR_VOL, NullSPU.class, "writeADSRVol");
            registrar.registerWrite16Callback(base + VOICE_REPEAT_OFFSET, NullSPU.class, "writeRepeatOffset");
        }
        registrar.registerWrite16Callback(ADDR_MAIN_VOL_L, NullSPU.class, "writeMainVolLeft");
        registrar.registerWrite16Callback(ADDR_MAIN_VOL_R, NullSPU.class, "writeMainVolRight");
        registrar.registerWrite16Callback(ADDR_REVERB_L, NullSPU.class, "writeReverbLeft");
        registrar.registerWrite16Callback(ADDR_REVERB_R, NullSPU.class, "writeReverbRight");
        registrar.registerWrite16Callback(ADDR_CHANNEL_ON0, NullSPU.class, "writeChannelOn0");
        registrar.registerWrite16Callback(ADDR_CHANNEL_ON1, NullSPU.class, "writeChannelOn1");
        registrar.registerWrite16Callback(ADDR_CHANNEL_OFF0, NullSPU.class, "writeChannelOff0");
        registrar.registerWrite16Callback(ADDR_CHANNEL_OFF1, NullSPU.class, "writeChannelOff1");
        registrar.registerWrite16Callback(ADDR_CHANNEL_FM0, NullSPU.class, "writeChannelFM0");
        registrar.registerWrite16Callback(ADDR_CHANNEL_FM1, NullSPU.class, "writeChannelFM1");
        registrar.registerWrite16Callback(ADDR_CHANNEL_NOISE0, NullSPU.class, "writeChannelNoise0");
        registrar.registerWrite16Callback(ADDR_CHANNEL_NOISE1, NullSPU.class, "writeChannelNoise1");
        registrar.registerWrite16Callback(ADDR_CHANNEL_REVERB0, NullSPU.class, "writeChannelReverb0");
        registrar.registerWrite16Callback(ADDR_CHANNEL_REVERB1, NullSPU.class, "writeChannelReverb1");
        registrar.registerWrite16Callback(ADDR_CHANNEL_MUTE0, NullSPU.class, "writeChannelMute0");
        registrar.registerWrite16Callback(ADDR_CHANNEL_MUTE1, NullSPU.class, "writeChannelMute1");

        registrar.registerWrite16Callback(ADDR_SPU_CTRL, NullSPU.class, "writeSPUCtrl");
        registrar.registerRead16Callback(ADDR_SPU_CTRL, NullSPU.class, "readSPUCtrl");

        registrar.registerWrite16Callback(ADDR_CD_VOL_L, NullSPU.class, "writeCDVolL");
        registrar.registerWrite16Callback(ADDR_CD_VOL_R, NullSPU.class, "writeCDVolR");

        registrar.registerRead16Callback(ADDR_SPU_STATUS, NullSPU.class, "readSPUStatus");

        registrar.registerWrite16Callback(ADDR_TRANSFER_ADDR, NullSPU.class, "writeTransferAddr");
        registrar.registerWrite16Callback(ADDR_TRANSFER_DATA, NullSPU.class, "writeTransferData");
        registrar.registerRead16Callback(ADDR_TRANSFER_ADDR, NullSPU.class, "readTransferAddr");
    }

    public static void writeVolLeft(int address, int val) {
        int voice = (address - ADDR_VOICES) >> 4;
    }

    public static void writeVolRight(int address, int val) {
        int voice = (address - ADDR_VOICES) >> 4;
    }

    public static void writePitch(int address, int val) {
        int voice = (address - ADDR_VOICES) >> 4;
    }

    public static void writeStartOffset(int address, int val) {
        int voice = (address - ADDR_VOICES) >> 4;
    }

    public static void writeADSLevel(int address, int val) {
        int voice = (address - ADDR_VOICES) >> 4;
    }

    public static void writeSRRate(int address, int val) {
        int voice = (address - ADDR_VOICES) >> 4;
    }

    public static void writeADSRVol(int address, int val) {
        int voice = (address - ADDR_VOICES) >> 4;
    }

    public static void writeRepeatOffset(int address, int val) {
        int voice = (address - ADDR_VOICES) >> 4;
    }

    public static void writeMainVolLeft(int address, int val) {
    }

    public static void writeMainVolRight(int address, int val) {
    }

    public static void writeReverbLeft(int address, int val) {
    }

    public static void writeReverbRight(int address, int val) {
    }

    public static void writeChannelOn0(int address, int val) {
        for (int i = 0; i < 16; i++) {
            if (0 != (val & (1 << i))) {
                //System.out.println("SPU Voice "+i+" on");
            }
        }
    }

    public static void writeChannelOn1(int address, int val) {
        for (int i = 0; i < 8; i++) {
            if (0 != (val & (1 << i))) {
                //System.out.println("SPU Voice "+(i+16)+" on");
            }
        }
    }

    public static void writeChannelOff0(int address, int val) {
        for (int i = 0; i < 16; i++) {
            if (0 != (val & (1 << i))) {
                //System.out.println("SPU Voice "+i+" off");
            }
        }
    }

    public static void writeChannelOff1(int address, int val) {
        for (int i = 0; i < 8; i++) {
            if (0 != (val & (1 << i))) {
                //System.out.println("SPU Voice "+(i+16)+" off");
            }
        }
    }

    public static void writeChannelFM0(int address, int val) {
    }

    public static void writeChannelFM1(int address, int val) {
    }

    public static void writeChannelNoise0(int address, int val) {
    }

    public static void writeChannelNoise1(int address, int val) {
    }

    public static void writeChannelReverb0(int address, int val) {
    }

    public static void writeChannelReverb1(int address, int val) {
    }

    public static void writeChannelMute0(int address, int val) {
    }

    public static void writeChannelMute1(int address, int val) {
    }

    public static void writeSPUCtrl(int address, int val) {
        m_ctrl = val;
    }

    public static int readSPUCtrl(int address) {
        return m_ctrl;
    }

    public static void writeCDVolL(int address, int val) {
    }

    public static void writeCDVolR(int address, int val) {
    }

    public static void writeTransferAddr(int address, int val) {
        m_transferAddress = val & 0xffff;
    }

    public static int readTransferAddr(int address) {
        return m_transferAddress;
    }

    public static void writeTransferData(int address, int val) {
    }

    static boolean toggle;

    public static int readSPUStatus(int address) {
        toggle = !toggle;
//        System.out.println("READ SPU STATUS!");
        return toggle ? 0x400 : 0;
    }

    protected static class SPUDMAChannel extends DMAChannelOwnerBase {
        public final int getDMAChannel() {
            return DMAController.DMA_SPU;
        }

        public final String getName() {
            return "SPU";
        }

        public void beginDMATransferToDevice(int base, int blocks, int blockSize, int ctrl) {
            System.out.println("begin DMA transfer to " + getName() + " " + MiscUtil.toHex(base, 8) + " 0x" + Integer.toHexString(blocks) + "*0x" + Integer.toHexString(blockSize) + " ctrl " + MiscUtil.toHex(ctrl, 8));
            signalTransferComplete();
        }

        public void beginDMATransferFromDevice(int base, int blocks, int blockSize, int ctrl) {
            System.out.println("begin DMA transfer from " + getName() + " " + MiscUtil.toHex(base, 8) + " 0x" + Integer.toHexString(blocks) + "*0x" + Integer.toHexString(blockSize) + " ctrl " + MiscUtil.toHex(ctrl, 8));
            signalTransferComplete();
        }

        public void cancelDMATransfer(int ctrl) {
            System.out.println("cancel " + getName() + " DMA transfer");
        }
    }

}

