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

import org.apache.log4j.Logger;
import org.jpsx.api.components.core.addressspace.AddressSpace;
import org.jpsx.api.components.core.addressspace.AddressSpaceRegistrar;
import org.jpsx.api.components.core.addressspace.MemoryMapped;
import org.jpsx.api.components.core.dma.DMAController;
import org.jpsx.api.components.core.scheduler.Quartz;
import org.jpsx.api.components.core.scheduler.ScheduledAction;
import org.jpsx.api.components.core.scheduler.Scheduler;
import org.jpsx.api.components.hardware.cd.CDAudioSink;
import org.jpsx.runtime.SingletonJPSXComponent;
import org.jpsx.runtime.components.core.CoreComponentConnections;
import org.jpsx.runtime.components.core.DMAChannelOwnerBase;
import org.jpsx.runtime.components.hardware.HardwareComponentConnections;
import org.jpsx.runtime.util.MiscUtil;

import javax.sound.sampled.*;

// todo volume panning
// bit of a mess! needs some work.
//
// TODO - noise/reverb/fm

// todo check if getLevel is now supported
public class SPU extends SingletonJPSXComponent implements MemoryMapped, CDAudioSink {
    private static final Logger log = Logger.getLogger("SPU");

    private static final boolean noVoices = false;
    private static final boolean debugEnvelope = false;
    private static final boolean debugVoiceOnOff = false;
    private static final boolean debugDMA = false;

    private static final int ADDR_VOICES = 0x1f801c00;

    private static final int ADDR_MAIN_VOL_L = 0x1f801d80;
    private static final int ADDR_MAIN_VOL_R = 0x1f801d82;
    private static final int ADDR_REVERB_L = 0x1f801d84;
    private static final int ADDR_REVERB_R = 0x1f801d86;
    private static final int ADDR_CHANNEL_ON0 = 0x1f801d88;
    private static final int ADDR_CHANNEL_ON1 = 0x1f801d8a;
    private static final int ADDR_CHANNEL_OFF0 = 0x1f801d8c;
    private static final int ADDR_CHANNEL_OFF1 = 0x1f801d8e;
    private static final int ADDR_CHANNEL_FM0 = 0x1f801d90;
    private static final int ADDR_CHANNEL_FM1 = 0x1f801d92;
    private static final int ADDR_CHANNEL_NOISE0 = 0x1f801d94;
    private static final int ADDR_CHANNEL_NOISE1 = 0x1f801d96;
    private static final int ADDR_CHANNEL_REVERB0 = 0x1f801d98;
    private static final int ADDR_CHANNEL_REVERB1 = 0x1f801d9a;
    private static final int ADDR_CHANNEL_MUTE0 = 0x1f801d9c;
    private static final int ADDR_CHANNEL_MUTE1 = 0x1f801d9e;

    private static final int ADDR_TRANSFER_ADDR = 0x1f801da6;
    private static final int ADDR_TRANSFER_DATA = 0x1f801da8;

    private static final int ADDR_SPU_CTRL = 0x1f801daa;
    private static final int ADDR_SPU_STATUS = 0x1f801dae;

    private static final int ADDR_CD_VOL_L = 0x1f801db0;
    private static final int ADDR_CD_VOL_R = 0x1f801db2;

    private static final int VOICES = 24;

    private static final int VOICE_VOL_L = 0x0;
    private static final int VOICE_VOL_R = 0x2;
    private static final int VOICE_PITCH = 0x4;
    private static final int VOICE_START_OFFSET = 0x6;
    private static final int VOICE_ADS_LEVEL = 0x8;
    private static final int VOICE_SR_RATE = 0xa;
    private static final int VOICE_ADSR_VOL = 0xc;
    private static final int VOICE_REPEAT_OFFSET = 0xe;


    private static final int SAMPLE_RATE = 44100;
    private static final int MIN_BUFFER_SAMPLE_RATE = 16000;
    private static final int BUFFER_MS = 240;
    private static final long BUFFER_REFILL_PERIOD_NS = 30 * Quartz.MSEC;
    private static final int BUFFER_MAX_FILL_MS = BUFFER_MS;
    private static final int BUFFER_MAX_REFILL_MS = 80;
    private static final int BUFFER_SAMPLES = (SAMPLE_RATE * BUFFER_MS) / 1000;

    private static final int CD_BUFFER_SAMPLES = SAMPLE_RATE / 2;
    private static final byte[] cdAudioBuffer = new byte[2352 * 4 * 4]; // maximum we're likely to get!
    private static SourceDataLine cdline;
    private static int cdlineFreq;
    private static FloatControl cdPanControl;
    private static FloatControl cdGainControl;
    private static int cdLeftVol = 0;
    private static int cdRightVol = 0;
    private static int cdLeftVolExternal = 0x3fff;
    private static int cdRightVolExternal = 0x3fff;

    private static int m_ctrl;
    private static int m_transferOffset;
    private static int m_dataTransferWordOffset;

    private static int[] m_ram = new int[512 * 1024 / 4];
    private static short[] m_decoded = new short[1024 * 1024];
    // 4 short header bytes then 28 short samples... * 32768

    private static Voice[] voices = new Voice[VOICES];

    private static boolean cdAudio = true;
    private static boolean voiceAudio = true;
    public static final String PROPERTY_CD_AUDIO = "cdAudio";
    public static final String PROPERTY_VOICE_AUDIO = "voiceAudio";

    private static int mainLeftVol;
    private static int mainRightVol;

    private static AddressSpace addressSpace;
    private static Quartz quartz;
    private static Scheduler scheduler;

    public SPU() {
        super("JPSX JavaSound SPU");
    }

    public void init() {
        super.init();
        cdAudio = getBooleanProperty(PROPERTY_CD_AUDIO, true);
        voiceAudio = getBooleanProperty(PROPERTY_VOICE_AUDIO, true);
        log.info("Voice Audio " + (voiceAudio ? "ON" : "OFF"));
        log.info("CD Audio " + (cdAudio ? "ON" : "OFF"));
        if (cdAudio) {
            HardwareComponentConnections.CD_AUDIO_SINK.set(this);
        }
        CoreComponentConnections.ALL_MEMORY_MAPPED.add(this);
        CoreComponentConnections.DMA_CHANNEL_OWNERS.add(new SPUDMAChannel());
    }

    public void resolveConnections() {
        super.resolveConnections();
        addressSpace = CoreComponentConnections.ADDRESS_SPACE.resolve();
        quartz = CoreComponentConnections.QUARTZ.resolve();
        scheduler = CoreComponentConnections.SCHEDULER.resolve();
    }

    public void registerAddresses(AddressSpaceRegistrar registrar) {
        for (int i = 0; i < VOICES; i++) {
            voices[i] = new Voice(i);
            int base = ADDR_VOICES + i * 0x10;
            registrar.registerWrite16Callback(base + VOICE_VOL_L, SPU.class, "writeVolLeft");
            registrar.registerWrite16Callback(base + VOICE_VOL_R, SPU.class, "writeVolRight");
            registrar.registerWrite16Callback(base + VOICE_PITCH, SPU.class, "writePitch");
            registrar.registerWrite16Callback(base + VOICE_START_OFFSET, SPU.class, "writeStartOffset");
            registrar.registerWrite16Callback(base + VOICE_ADS_LEVEL, SPU.class, "writeADSLevel");
            registrar.registerWrite16Callback(base + VOICE_SR_RATE, SPU.class, "writeSRRate");
            registrar.registerRead16Callback(base + VOICE_ADSR_VOL, SPU.class, "readADSRVol");
            registrar.registerWrite16Callback(base + VOICE_REPEAT_OFFSET, SPU.class, "writeRepeatOffset");

            registrar.registerRead16Callback(base + VOICE_VOL_L, SPU.class, "readVolLeft");
            registrar.registerRead16Callback(base + VOICE_VOL_R, SPU.class, "readVolRight");
            registrar.registerRead16Callback(base + VOICE_PITCH, SPU.class, "readPitch");
            registrar.registerRead16Callback(base + VOICE_START_OFFSET, SPU.class, "readStartOffset");
            registrar.registerRead16Callback(base + VOICE_ADS_LEVEL, SPU.class, "readADSLevel");
            registrar.registerRead16Callback(base + VOICE_SR_RATE, SPU.class, "readSRRate");
            registrar.registerRead16Callback(base + VOICE_REPEAT_OFFSET, SPU.class, "readRepeatOffset");
        }
        registrar.registerWrite16Callback(ADDR_MAIN_VOL_L, SPU.class, "writeMainVolLeft");
        registrar.registerWrite16Callback(ADDR_MAIN_VOL_R, SPU.class, "writeMainVolRight");
        registrar.registerWrite16Callback(ADDR_REVERB_L, SPU.class, "writeReverbLeft");
        registrar.registerWrite16Callback(ADDR_REVERB_R, SPU.class, "writeReverbRight");
        registrar.registerWrite16Callback(ADDR_CHANNEL_ON0, SPU.class, "writeChannelOn0");
        registrar.registerWrite16Callback(ADDR_CHANNEL_ON1, SPU.class, "writeChannelOn1");
        registrar.registerWrite16Callback(ADDR_CHANNEL_OFF0, SPU.class, "writeChannelOff0");
        registrar.registerWrite16Callback(ADDR_CHANNEL_OFF1, SPU.class, "writeChannelOff1");
        registrar.registerWrite16Callback(ADDR_CHANNEL_FM0, SPU.class, "writeChannelFM0");
        registrar.registerWrite16Callback(ADDR_CHANNEL_FM1, SPU.class, "writeChannelFM1");
        registrar.registerWrite16Callback(ADDR_CHANNEL_NOISE0, SPU.class, "writeChannelNoise0");
        registrar.registerWrite16Callback(ADDR_CHANNEL_NOISE1, SPU.class, "writeChannelNoise1");
        registrar.registerWrite16Callback(ADDR_CHANNEL_REVERB0, SPU.class, "writeChannelReverb0");
        registrar.registerWrite16Callback(ADDR_CHANNEL_REVERB1, SPU.class, "writeChannelReverb1");
        registrar.registerWrite16Callback(ADDR_CHANNEL_MUTE0, SPU.class, "writeChannelMute0");
        registrar.registerWrite16Callback(ADDR_CHANNEL_MUTE1, SPU.class, "writeChannelMute1");

        registrar.registerWrite16Callback(ADDR_SPU_CTRL, SPU.class, "writeSPUCtrl");
        registrar.registerRead16Callback(ADDR_SPU_CTRL, SPU.class, "readSPUCtrl");

        registrar.registerWrite16Callback(ADDR_CD_VOL_L, SPU.class, "writeCDVolL");
        registrar.registerWrite16Callback(ADDR_CD_VOL_R, SPU.class, "writeCDVolR");

        registrar.registerRead16Callback(ADDR_SPU_STATUS, SPU.class, "readSPUStatus");

        registrar.registerWrite16Callback(ADDR_TRANSFER_ADDR, SPU.class, "writeTransferAddr");
        registrar.registerWrite16Callback(ADDR_TRANSFER_DATA, SPU.class, "writeTransferData");
        registrar.registerRead16Callback(ADDR_TRANSFER_ADDR, SPU.class, "readTransferAddr");

    }

    public void begin() {
        new SPUCallback().start();
    }

    public static void writeVolLeft(int address, int val) {
        int voice = (address - ADDR_VOICES) >> 4;
        voices[voice].setLeftVol(val);
    }

    public static void writeVolRight(int address, int val) {
        int voice = (address - ADDR_VOICES) >> 4;
        voices[voice].setRightVol(val);
    }

    public static void writePitch(int address, int val) {
        int voice = (address - ADDR_VOICES) >> 4;
        voices[voice].setPitch(val);
    }

    public static void writeStartOffset(int address, int val) {
        int voice = (address - ADDR_VOICES) >> 4;
        voices[voice].setStartOffset(val);
    }

    public static void writeADSLevel(int address, int val) {
        int voice = (address - ADDR_VOICES) >> 4;
        voices[voice].setADSLevel(val);
    }

    public static void writeSRRate(int address, int val) {
        int voice = (address - ADDR_VOICES) >> 4;
        voices[voice].setSRRate(val);
    }

    public static int readADSRVol(int address) {
        int voice = (address - ADDR_VOICES) >> 4;
        return voices[voice].getADSRVol();
    }

    public static void writeRepeatOffset(int address, int val) {
        int voice = (address - ADDR_VOICES) >> 4;
        voices[voice].setRepeatOffset(val);
    }

    public static int readVolLeft(int address) {
        int voice = (address - ADDR_VOICES) >> 4;
        return voices[voice].getLeftVol();
    }

    public static int readVolRight(int address) {
        int voice = (address - ADDR_VOICES) >> 4;
        return voices[voice].getRightVol();
    }

    public static int readPitch(int address) {
        int voice = (address - ADDR_VOICES) >> 4;
        return voices[voice].getPitch();
    }

    public static int readStartOffset(int address) {
        int voice = (address - ADDR_VOICES) >> 4;
        return voices[voice].getStartOffset();
    }

    public static int readADSLevel(int address) {
        int voice = (address - ADDR_VOICES) >> 4;
        return voices[voice].getADSLevel();
    }

    public static int readSRRate(int address) {
        int voice = (address - ADDR_VOICES) >> 4;
        return voices[voice].getSRRate();
    }

    public static int readRepeatOffset(int address) {
        int voice = (address - ADDR_VOICES) >> 4;
        return voices[voice].getRepeatOffset();
    }

    public static void writeMainVolLeft(int address, int val) {
        mainLeftVol = decodeVolume(val);
        for (int i = 0; i < VOICES; i++) {
            voices[i].updateVolume();
        }
    }

    public static void writeMainVolRight(int address, int val) {
        mainRightVol = decodeVolume(val);
        for (int i = 0; i < VOICES; i++) {
            voices[i].updateVolume();
        }
    }

    public static void writeReverbLeft(int address, int val) {
    }

    public static void writeReverbRight(int address, int val) {
    }

    public static void writeChannelOn0(int address, int val) {
        for (int i = 0; i < 16; i++) {
            if ((val & (1 << i)) != 0) {
                voices[i].on();
            }
        }
    }

    public static void writeChannelOn1(int address, int val) {
        for (int i = 0; i < 8; i++) {
            if ((val & (1 << i)) != 0) {
                voices[i + 16].on();
            }
        }
    }

    public static void writeChannelOff0(int address, int val) {
        for (int i = 0; i < 16; i++) {
            if ((val & (1 << i)) != 0) {
                voices[i].off();
            }
        }
    }

    public static void writeChannelOff1(int address, int val) {
        for (int i = 0; i < 8; i++) {
            if ((val & (1 << i)) != 0) {
                voices[i + 16].off();
            }
        }
    }

    public static void writeChannelFM0(int address, int val) {
        for (int i = 0; i < 16; i++) {
            voices[i].setFM((val & (1 << i)) != 0);
        }
    }

    public static void writeChannelFM1(int address, int val) {
        for (int i = 0; i < 8; i++) {
            voices[i + 16].setFM((val & (1 << i)) != 0);
        }
    }

    public static void writeChannelNoise0(int address, int val) {
        for (int i = 0; i < 16; i++) {
            voices[i].setNoise((val & (1 << i)) != 0);
        }
    }

    public static void writeChannelNoise1(int address, int val) {
        for (int i = 0; i < 8; i++) {
            voices[i + 16].setNoise((val & (1 << i)) != 0);
        }
    }

    public static void writeChannelReverb0(int address, int val) {
        for (int i = 0; i < 16; i++) {
            voices[i].setReverb((val & (1 << i)) != 0);
        }
    }

    public static void writeChannelReverb1(int address, int val) {
        for (int i = 0; i < 8; i++) {
            voices[i + 16].setReverb((val & (1 << i)) != 0);
        }
    }

    public static void writeChannelMute0(int address, int val) {
        for (int i = 0; i < 16; i++) {
            voices[i].setMute((val & (1 << i)) != 0);
        }
    }

    public static void writeChannelMute1(int address, int val) {
        for (int i = 0; i < 8; i++) {
            voices[i + 16].setMute((val & (1 << i)) != 0);
        }
    }

    public static void writeSPUCtrl(int address, int val) {
        if (val == m_ctrl) return;
        m_ctrl = val;
        /*
        boolean spuOn = (val & 0x8000) != 0;
		boolean spuMuted = (val & 0x4000) == 0;
		boolean spuReverb = (val & 0x80) != 0;
		boolean spuIrq = (val & 0x40) != 0;
		boolean spuCD = (val & 0x1) != 0;
		System.out.println("SPU "+(spuOn?"on":"off")+
							   (spuMuted?" muted":"")+
							   (spuReverb?" RV":"")+
							   (spuIrq?" irq":" noirq")+
							   (spuCD?" media":" nocd"));*/
    }

    public static int readSPUCtrl(int address) {
        return m_ctrl;
    }

    public static void writeCDVolL(int address, int val) {
        cdLeftVol = decodeVolume(val);
        updateCDVolume();
    }

    public static void writeCDVolR(int address, int val) {
        cdRightVol = decodeVolume(val);
        updateCDVolume();
    }

    public static void writeTransferAddr(int address, int val) {
        m_transferOffset = val & 0xffff;
        m_dataTransferWordOffset = m_transferOffset << 2;
    }

    public static int readTransferAddr(int address) {
        return m_transferOffset;
    }

    public static void writeTransferData(int address, int val) {
        int index = m_dataTransferWordOffset >> 1;
        m_decoded[(m_dataTransferWordOffset << 2) & 0xfffffff0] = 0;
        // todo mark area as dirty.
        if (0 == (m_dataTransferWordOffset & 1)) {
            m_ram[index] = (m_ram[index] & 0xffff0000) | (val & 0xffff);
        } else {
            m_ram[index] = (m_ram[index] & 0xffff) | (val << 16);
        }
        m_dataTransferWordOffset++;
    }

    static boolean toggle;

    public static int readSPUStatus(int address) {
        toggle = !toggle;
//        System.out.println("READ SPU STATUS!");
        return toggle ? 0x400 : 0;
    }

    private static class SPUDMAChannel extends DMAChannelOwnerBase {
        private static AddressSpace.ResolveResult rr = new AddressSpace.ResolveResult();

        public final int getDMAChannel() {
            return DMAController.DMA_SPU;
        }

        public final String getName() {
            return "SPU";
        }

        public void beginDMATransferToDevice(int base, int blocks, int blockSize, int ctrl) {
            if (debugDMA)
                System.out.println("begin DMA transfer to " + getName() + " " + MiscUtil.toHex(base, 8) + " 0x" + Integer.toHexString(blocks) + "*0x" + Integer.toHexString(blockSize) + " ctrl " + MiscUtil.toHex(ctrl, 8));
            int destIndex = m_transferOffset << 1;
            int size = blocks * blockSize;
            addressSpace.resolve(base, size * 4, true, rr);
            int srcIndex = rr.offset;
            if ((destIndex + size) > 0x20000) {
                if (debugDMA)
                    System.out.println("transfer to SPU overrun buffer by " + ((destIndex + size) - 0x20000) + " dwords");
                size = 0x20000 - destIndex;
            }
            int[] src = rr.mem;
            for (size = size - 1; size >= 0; size--) {
                // destIndex = 0->0x020000;
                // decoded   = 0->0x100000;
                m_decoded[(destIndex << 3) & 0xfffffff0] = 0;
                m_ram[destIndex++] = src[srcIndex++];
            }
            signalTransferComplete();
        }

        public void beginDMATransferFromDevice(int base, int blocks, int blockSize, int ctrl) {
            if (debugDMA)
                System.out.println("begin DMA transfer from " + getName() + " " + MiscUtil.toHex(base, 8) + " 0x" + Integer.toHexString(blocks) + "*0x" + Integer.toHexString(blockSize) + " ctrl " + MiscUtil.toHex(ctrl, 8));
            int srcIndex = m_transferOffset << 1;
            int size = blocks * blockSize;
            addressSpace.resolve(base, size * 4, false, rr);
            int destIndex = rr.offset;
            int[] dest = rr.mem;
            for (size = size - 1; size >= 0; size--) {
                dest[destIndex++] = m_ram[srcIndex++];
            }
            signalTransferComplete();
        }

        public void cancelDMATransfer(int ctrl) {
            if (debugDMA) System.out.println("cancel " + getName() + " DMA transfer");
        }
    }

    public static class SPUCallback implements ScheduledAction {
        private long next;
        private static byte[] buffer = new byte[BUFFER_SAMPLES * 2];

        public void start() {
            next = quartz.nanoTime() + (BUFFER_REFILL_PERIOD_NS << 4);
            scheduler.schedule(next, this);
        }

        public long run(long currentTime) {
            //System.out.println("invoke "+time);
            for (int i = 0; i < VOICES; i++) {
                voices[i].fill(buffer, false);
            }
            next = quartz.nanoTime() + BUFFER_REFILL_PERIOD_NS;
            return next;
        }
    }

    // for debugging
    private static byte[] debugbuffer = new byte[BUFFER_SAMPLES * 2];

    public static void fill() {
        for (int i = 0; i < VOICES; i++) {
            voices[i].fill(debugbuffer, BUFFER_SAMPLES * 2);
        }
    }

    private static class Voice {
        private int index;

        private static final int OFF = 0;
        private static final int ATTACK = 1;
        private static final int DECAY = 2;
        private static final int SUSTAIN = 3;
        private static final int RELEASE = 4;

        private int state;
        private boolean mute;
        private boolean fm;
        private boolean noise;
        private boolean reverb;
        private int leftVol;
        private int rightVol;
        private int rateA, rateD, rateS, rateR;
        private int levelS;
        private boolean expA, expS, expR;
        private boolean decS;
        private int pitch;

        private int vol;
        private int sampleDelta;
        private int startOffset;
        private int blockOffset;
        private int repeatOffset;
        private int sample;
        private int currentBlockSample;
        private boolean locked;
        private int envelope;
        private int currentMS;
        private int currentSubMS;
        private int adslevel;
        private int srrate;

        private static final int ENVELOPE_HISTORY_SIZE = 256;
        private int[] envelopeHistory = new int[ENVELOPE_HISTORY_SIZE];
        private long onTime;
        private long offTime;

        private static byte[] buffer = new byte[BUFFER_SAMPLES * 2];

        private static DataLine.Info desiredLine = new DataLine.Info(SourceDataLine.class,
                new AudioFormat[]{new AudioFormat(SAMPLE_RATE, 16, 1, true, false)},
// Guess this was ignored by the software mixer - doesn't really make much sense
//                0, BUFFER_SAMPLES * 2);
                BUFFER_SAMPLES * 2, AudioSystem.NOT_SPECIFIED);
        private SourceDataLine line;
        private FloatControl panControl;
        private FloatControl rateControl;

        private boolean slow;
        private int sampleRate;
        private int BUFFER_MAX_REFILL_SAMPLES;
        private int BUFFER_MAX_FILL_SAMPLES;

        private static int[] adsrRates = initADSR();

        private static int[] initADSR() {
            int[] rates = new int[160];
            int r, rs, rd;
            int i;

            r = 3;
            rs = 1;
            rd = 0;

            for (i = 32; i < 160; i++) {
                if (r < 0x3FFFFFFF) {
                    r += rs;
                    rd++;
                    if (rd == 5) {
                        rd = 1;
                        rs *= 2;
                    }
                }
                if (r > 0x3FFFFFFF) r = 0x3FFFFFFF;
                rates[i] = r;
            }
            return rates;
        }

        public String toString() {
            return "V" + index + " L" + MiscUtil.toHex(leftVol, 4) + " R" + MiscUtil.toHex(rightVol, 4) + " " +
                    "pitch " + MiscUtil.toHex(pitch, 4) + " " +
                    "st " + MiscUtil.toHex(startOffset, 4) + " " +
                    "rpt " + MiscUtil.toHex(repeatOffset, 4) +
                    " ar " + rateA +
                    " dr " + rateD +
                    " sl " + levelS +
                    " sr " + rateS +
                    " rr " + rateR +
                    (mute ? " muted" : "") +
                    (fm ? " FM" : "") +
                    (reverb ? " RV" : "");
        }

        public Voice(int index) {
            if (!voiceAudio) return;
            this.index = index;
            try {
                line = (SourceDataLine) AudioSystem.getLine(desiredLine);
                line.open();
                if (line.isControlSupported(FloatControl.Type.SAMPLE_RATE)) {
                    rateControl = (FloatControl) line.getControl(FloatControl.Type.SAMPLE_RATE);
                } else {
                    log.warn("Rate control is NOT supported, hacking around it for now - might sound a bit weird until fixed properly");
                }
                if (line.isControlSupported(FloatControl.Type.PAN)) {
                    panControl = (FloatControl) line.getControl(FloatControl.Type.PAN);
                } else {
                    // todo to fix this we need to make the voice stereo instead (identical channels)
                    log.warn("Pan control is NOT supported, voices will not be stereo positioned");
                }
            } catch (Throwable t) {
                throw new IllegalStateException("can't get line for voice " + index, t);
            }
            state = OFF;
        }

        public void on() {
            if (!voiceAudio) return;
            //if (index!=0) return;
            if (debugVoiceOnOff) System.out.println("voice on " + this);
            if (isActive()) {
//                System.out.println(this+" VOICE ON WHEN ACTIVE!");
            }
            state = OFF;
            line.flush();
            line.stop();
            //line.drain();

            repeatOffset = 0;
            sample = 0;
            currentBlockSample = -28;
            blockOffset = startOffset - 2;
            currentMS = 0;
            currentSubMS = 1000;

            slow = pitch < 0x1000;
            if (rateControl == null) {
                // temp hack to get around loss of RateControl is JDK7 
				// surprised this works, but as far as I recall, I must
				// have done the frequency myself for anything higher than
				// the SAMPLE_RATE, so forcing slow = false makes
				// us do it in software, which might sound bad without filtering
				// but is good enough for now
                slow = false;
            }
            sampleRate = slow ? ((SAMPLE_RATE * pitch) >> 12) : SAMPLE_RATE;
            int min = sampleRate < MIN_BUFFER_SAMPLE_RATE ? MIN_BUFFER_SAMPLE_RATE : sampleRate;
            BUFFER_MAX_REFILL_SAMPLES = (min * BUFFER_MAX_REFILL_MS) / 1000;
            BUFFER_MAX_FILL_SAMPLES = (min * BUFFER_MAX_FILL_MS) / 1000;
            //System.out.println("slow="+slow+" sample rate="+sampleRate+" max samples="+BUFFER_MAX_REFILL_SAMPLES);
            if (rateControl != null) {
                rateControl.setValue(sampleRate);
            }
            if (!noVoices) {
                line.start();
            }

            envelope = 0;
            //System.out.println("onTime "+onTime);
            onTime = quartz.nanoTime();
            offTime = Long.MAX_VALUE;
            state = ATTACK;
            if (debugEnvelope) System.out.println("voice " + index + " to ATTACK");
            fill(buffer, true);
        }

        public void off() {
            if (!voiceAudio) return;
            if (debugVoiceOnOff) System.out.println("voice off " + this);
            if (state != OFF) {
                if (debugEnvelope) System.out.println("voice " + index + " to RELEASE");
                state = RELEASE;
            }
        }

        public void setMute(boolean nMute) {
            if (mute != nMute) {
                mute = nMute;
                //System.out.println( this + " mute changed" );
            }
        }

        public boolean isActive() {
            return state != OFF;
        }

        public void setReverb(boolean nReverb) {
            reverb = nReverb;
        }

        public void setNoise(boolean nNoise) {
            noise = nNoise;
            if (noise) throw new IllegalStateException(this + " NOISE!");
        }

        public void setFM(boolean nFM) {
            fm = nFM;
            if (fm) throw new IllegalStateException(this + " FM!");
        }

        public void setLeftVol(int nVol) {
            leftVol = decodeVolume(nVol);
            updateVolume();
        }

        public void setRightVol(int nVol) {
            rightVol = decodeVolume(nVol);
            updateVolume();
        }

        public int getLeftVol() {
            return leftVol;
        }

        public int getRightVol() {
            return leftVol;
        }

        public void updateVolume() {
            if (!voiceAudio) return;
            // set volume to max of left/right, then set pan
            // assuming that:
            // lscale = (pan<0)?1:(1-pan);
            // rscale = (pan<0)?(1+pan):1;

            int l = (leftVol * mainLeftVol) >> 15;
            int r = (rightVol * mainRightVol) >> 15;
            if (panControl == null) {
				// todo fix this, for now without pan control, just average l/r volume
                vol = (l + r) / 2;
            } else {
                if (l == r) {
                    vol = l;
                    panControl.setValue(0.0f);
                } else if (l > r) {
                    vol = l;
                    panControl.setValue(((float) r) / l - 1.0f);
                } else {
                    vol = r;
                    panControl.setValue(1.0f - ((float) l) / r);
                }
            }
        }

        public void setPitch(int nPitch) {
            pitch = nPitch;
            sampleDelta = pitch >> 1;
            if (state != OFF) {
                //System.out.println("XXX SET PITCH WHILE ON: "+this+" pitch is now "+nPitch);
            }
        }

        public int getPitch() {
            return pitch;
        }

        public void setStartOffset(int nOffset) {
            startOffset = nOffset;
        }

        public int getStartOffset() {
            return startOffset;
        }

        public void setADSLevel(int val) {
            adslevel = val;
            expA = (val & 0x8000) != 0;
            rateA = (val >> 8) & 0x7f;
            rateA ^= 0x7f;
            rateD = (val >> 4) & 0xf;
            // todo - is this really correct?
            rateD ^= 0x1f;
            levelS = val & 0xf;
        }

        public int getADSLevel() {
            return adslevel;
        }

        public void setSRRate(int val) {
            srrate = val;
            expS = (val & 0x8000) != 0;
            decS = (val & 0x4000) != 0;
            rateS = (val >> 6) & 0x7f;
            rateS ^= 0x7f;
            expR = (val & 0x20) != 0;
            rateR = val & 0x1f;
            rateR ^= 0x1f;
        }

        public int getSRRate() {
            return srrate;
        }

        public int getADSRVol() {
            if (!voiceAudio) return 0;
            long time = quartz.nanoTime();
            if (state == OFF && time >= offTime) {
                return 0;
            }
            int ms = (int) ((time - onTime) / Quartz.MSEC);
            if (ms > currentMS)
                return 0;
            int delta = (currentMS - ms) >> 2;
            // if we're too far behind, then pick something back towards the beginning of the buffer
            //
            // note, we can get further behind because our ticks are potentially slower than real time
            // due to gc/compilation etc.
            if (delta >= ENVELOPE_HISTORY_SIZE)
                ms = currentMS - ((ENVELOPE_HISTORY_SIZE - 4) << 2);
//            System.out.println( this+" "+(envelopeHistory[(ms>>2)%ENVELOPE_HISTORY_SIZE]>>16));
            return envelopeHistory[(ms >> 2) % ENVELOPE_HISTORY_SIZE] >> 16;
        }

        public void setRepeatOffset(int nRepeat) {
            repeatOffset = nRepeat;
        }

        public int getRepeatOffset() {
            return repeatOffset;
        }

        public synchronized boolean lock() {
            if (locked)
                return false;
            locked = true;
            return true;
        }

        public synchronized void unlock() {
            locked = false;
        }

        private static final int[] expIndex = new int[]{
                0, 4, 6, 8, 9, 10, 11, 12
        };

        private final void updateADSR() {
            //assert(0==(envelope&0x80000000));

            switch (state) {
                case ATTACK:
                    if (!expA || envelope < 0x60000000) {
                        envelope += adsrRates[rateA + 32 - 0x10];
                    } else {
                        envelope += adsrRates[rateA + 32 - 0x18];
                    }
                    if (envelope < 0) {
                        envelope = 0x7fffffff;
                        if (debugEnvelope) System.out.println("voice " + index + " to DECAY");
                        state = DECAY;
                    }
                    return;
                case DECAY:
                    envelope -= adsrRates[4 * rateD + 32 - 0x18 + expIndex[envelope >> 28]];
                    if (envelope > 0) {
                        if (levelS >= (envelope >> 27)) {
                            state = SUSTAIN;
                            if (debugEnvelope) System.out.println("voice " + index + " to SUSTAIN");
                        }
                    } else {
                        envelope = 0;
                        state = SUSTAIN;
                        if (debugEnvelope) System.out.println("voice " + index + " to SUSTAIN");
                    }
                    return;
                case SUSTAIN:
                    if (decS) {
                        // decrementing
                        if (expS) {
                            envelope -= adsrRates[rateS + 32 - 0x1b + expIndex[envelope >> 28]];
                        } else {
                            envelope -= adsrRates[rateS + 32 - 0x0f];
                        }
                        if (envelope < 0) {
                            envelope = 0;
                        }
                    } else {
                        // incrementing
                        if (!expS || envelope < 0x60000000) {
                            envelope += adsrRates[rateS + 32 - 0x10];
                        } else {
                            envelope += adsrRates[rateS + 32 - 0x18];
                        }
                        if (envelope < 0) {
                            envelope = 0x7fffffff;
                        }
                    }
                    return;
                case RELEASE:
                    if (expR) {
                        envelope -= adsrRates[4 * rateR + 32 - 0x18 + expIndex[envelope >> 28]];
                    } else {
                        envelope -= adsrRates[4 * rateR + 32 - 0x0c];
                    }
                    if (envelope < 0) {
                        envelope = 0;
                    }
                    return;
            }
        }

        public void fill(byte[] buffer, boolean unlimited) {
            int max = unlimited ? BUFFER_MAX_FILL_SAMPLES : BUFFER_MAX_REFILL_SAMPLES;
            fill(buffer, max);
        }

        public void fill(byte[] buffer, int max) {
            if (noVoices) {
                return;
            }
            if (lock()) {
                if (state == OFF || sampleDelta == 0) {
                    //if (line.isActive() && line.available()==line.getBufferSize()) {
                    //    System.out.println(this+" line is drained and off, stopping!");
                    //    line.close();
                    //}
                    unlock();
                    return;
                }
                //System.out.println( Thread.currentThread().getName()+" thread fills");
                int sampleCount = line.available() >> 1;
                if (sampleCount == 0) {
                    unlock();
                    return;
                }
                if (sampleCount > max) {
                    sampleCount = max;
                }
                int bufferBytes = 0;
                if (slow) {
                    // todo removed this because it keeps failing!
                    //assert sampleDelta <= 0x800;
                    //System.out.println( this + " filling "+sampleCount+" slow samples");
                    for (int count = 0; count < sampleCount; count++) {
                        int s = sample >> 11;
                        int sold = s;
                        do {
                            updateADSR();
                            sample += sampleDelta;
                            s = sample >> 11;
                        } while (s == sold);
                        //updateADSR();
                        int decodeIndex = blockOffset << 4;
                        if (s >= currentBlockSample + 28) {
                            currentBlockSample += 28;
                            blockOffset += 2;
                            decodeIndex += 32;

                            int srcIndex = blockOffset << 1;
                            int code = (m_ram[srcIndex] >> 8) & 0xff;
                            if (0 != (code & 1)) {
                                state = OFF;
                                if (debugEnvelope) System.out.println("voice " + index + " to OFF (sample ended)");
                                offTime = onTime + currentMS * Quartz.MSEC;
                                //System.out.println("offTime "+offTime+" ("+((offTime-onTime)>>4)+")");
                                break;
                            }
                            if (m_decoded[decodeIndex] == 0) {
                                //System.out.g(this+" decode block at "+MiscUtil.toHex( nextBlockOffset, 4)+" code "+code);
                                //    System.out.println("  decompress");
                                decompressBlock(blockOffset);
                            }
                            // note maximum sample step is 16 samples, so we can't skip a block
                        }
                        int val = m_decoded[decodeIndex + 4 + s - currentBlockSample];
                        val = (val * vol) >> 14;
                        val = (val * (envelope >> 16)) >> 15;
                        //if (index==0) System.out.println(index+" "+state+" "+MiscUtil.toHex( envelope, 8)+" "+MiscUtil.toHex( val, 4));
                        buffer[bufferBytes++] = (byte) val;
                        buffer[bufferBytes++] = (byte) (val >> 8);
                        currentSubMS -= 1000;
                        if (currentSubMS <= 0) {
                            envelopeHistory[(currentMS >> 2) % ENVELOPE_HISTORY_SIZE] = envelope;
                            currentMS += 4;
                            currentSubMS += sampleRate * 4;
                            //if (index==0) System.out.println(currentMS+" "+MiscUtil.toHex(envelope>>16,4));
                        }
                    }
                } else {
                    //System.out.println( this + " filling "+sampleCount+" fast samples");
                    for (int count = 0; count < sampleCount; count++) {
                        updateADSR();
                        int s = (sample >> 11);
                        int decodeIndex = blockOffset << 4;
                        if (s >= currentBlockSample + 28) {
                            currentBlockSample += 28;
                            blockOffset += 2;
                            decodeIndex += 32;

                            int srcIndex = blockOffset << 1;
                            int code = (m_ram[srcIndex] >> 8) & 0xff;
                            if (0 != (code & 1)) {
                                state = OFF;
                                offTime = onTime + (currentMS << 4);
                                if (debugEnvelope) System.out.println("voice " + index + " to OFF (sample ended)");
                                //System.out.println("offTime "+offTime+" ("+((offTime-onTime)>>4)+")");
                                break;
                            }
                            if (m_decoded[decodeIndex] == 0) {
                                //System.out.println(this+" decode block at "+MiscUtil.toHex( nextBlockOffset, 4)+" code "+code);
                                //    System.out.println("  decompress");
                                decompressBlock(blockOffset);
                            }
                            // note maximum sample step is 16 samples, so we can't skip a block
                        }
                        int val = m_decoded[decodeIndex + 4 + s - currentBlockSample];
                        val = (val * vol) >> 14;
                        val = (val * (envelope >> 16)) >> 15;
                        //if (index==0) System.out.println(index+" "+state+" "+MiscUtil.toHex( envelope, 8)+" "+MiscUtil.toHex( val, 4));
                        buffer[bufferBytes++] = (byte) val;
                        buffer[bufferBytes++] = (byte) (val >> 8);
                        sample += sampleDelta;
                        currentSubMS -= 1000;
                        if (currentSubMS <= 0) {
                            envelopeHistory[(currentMS >> 2) % ENVELOPE_HISTORY_SIZE] = envelope;
                            currentMS += 4;
                            currentSubMS += SAMPLE_RATE * 4;
                            //System.out.println(currentMS+" "+MiscUtil.toHex(envelope>>16,4));
                        }
                    }
                }
                line.write(buffer, 0, bufferBytes);
                unlock();
            }
        }
    }

    private static final int[] predict1 = new int[]{
            0, 60, 115, 98, 122
    };

    private static final int[] predict2 = new int[]{
            0, 0, -52, -55, -60
    };

    // blockOffset is in multiples of 8 bytes in src ram.
    private static void decompressBlock(int blockOffset) {
        int decodeIndex = blockOffset << 4;
        int srcIndex = blockOffset << 1;

        int s_1, s_2;
        if (false) {
            if (decodeIndex < 32) {
                s_1 = 0;
                s_2 = 0;
            } else {
                s_1 = m_decoded[decodeIndex - 31];
                s_2 = m_decoded[decodeIndex - 30];
            }

            int dword = m_ram[srcIndex];
            int predictIndex = (dword >> 4) & 0xf;
            int shift = dword & 0xf;

            for (int i = 4; i < 32; i++) {
                int i7 = i & 7;
                int s = (dword >> (i7 << 2)) & 0xf;
                //s <<= 12;
                //if ((s&0x8000)!=0) s|= 0xffff0000;
                //s >>= shift;
                //s = (((s<<6) + s_1*predict1[predictIndex] + s_2*predict2[predictIndex])+32)>>6;

                // note this isn't quite the same as above; we include sub bits if shift >12
                s = ((((s << 28) >> (shift + 10)) + s_1 * predict1[predictIndex] + s_2 * predict2[predictIndex]) + 32) >> 6;


                m_decoded[decodeIndex + i] = (short) s;
                s_2 = s_1;
                s_1 = s;
                if (7 == (i7)) {
                    dword = m_ram[srcIndex + ((i + 1) >> 3)];
                }
                //System.out.print(MiscUtil.toHex(s,4)+" ");
            }
            m_decoded[decodeIndex] = 1;
            m_decoded[decodeIndex + 1] = (short) s_1;
            m_decoded[decodeIndex + 2] = (short) s_2;
        } else {
            if (decodeIndex < 32) {
                s_1 = 0;
                s_2 = 0;
            } else {
                s_1 = ((int) m_decoded[decodeIndex - 31] & 0xffff) + (((int) m_decoded[decodeIndex - 29] & 0xff) << 16);
                s_2 = ((int) m_decoded[decodeIndex - 30] & 0xffff) + (((int) m_decoded[decodeIndex - 29] & 0xff00) << 8);
                s_1 = (s_1 << 8) >> 8;
                s_2 = (s_2 << 8) >> 8;
            }

            int dword = m_ram[srcIndex];
            int predictIndex = (dword >> 4) & 0xf;
            int shift = (dword & 0xf) + 12;

            int ik0 = -predict1[predictIndex];
            int ik1 = -predict2[predictIndex];
            for (int i = 4; i < 32; i++) {
                int i7 = i & 7;
                int x0 = (dword >> (i7 << 2)) & 0xf;
                x0 = (x0 << 28) >> shift;
                x0 -= (ik0 * s_1 + ik1 * s_2) >> 6;
                m_decoded[decodeIndex + i] = (short) (x0 >> 4);
                s_2 = s_1;
                s_1 = x0;
                if (7 == (i7)) {
                    dword = m_ram[srcIndex + ((i + 1) >> 3)];
                }
                //System.out.print(MiscUtil.toHex(s,4)+" ");
            }
            m_decoded[decodeIndex] = 1;
            m_decoded[decodeIndex + 1] = (short) s_1;
            m_decoded[decodeIndex + 2] = (short) s_2;
            m_decoded[decodeIndex + 3] = (short) (((s_1 >> 16) & 0xff) | ((s_2 >> 8) & 0xff00));
        }
        //System.out.println();
    }

    public int getCDAudioLatency() {
        return 0;
    }

    private static boolean reBuffer = false;

    public void newCDAudio() {
        sectorsSinceReset = 0;
        base = System.nanoTime();
        if (cdline != null) {
            cdline.flush();
            cdline.stop();
        }
    }

    // we can get up to 8 sectors at a time; make slightly bigger for safety
    private static final int SECTORS_TO_BUFFER = 10;
    private static final int SECTORS_TO_DELAY = 4;
    int sectorsSinceReset;
    int bytesPerSector;

    public synchronized void setCDAudioRate(int hz) {
        if (cdAudio) {
            if (hz != cdlineFreq) {
                newCDAudio();
                cdline = null;
                bytesPerSector = (hz / 75) * 4;
                int bytesToBuffer = bytesPerSector * SECTORS_TO_BUFFER;

                DataLine.Info desiredCdLine = new DataLine.Info(SourceDataLine.class,
                        new AudioFormat[]{new AudioFormat(hz, 16, 2, true, false)},
                        bytesToBuffer * 4, bytesToBuffer * 4);
                try {
                    cdline = (SourceDataLine) AudioSystem.getLine(desiredCdLine);
                    cdline.open();
                    //System.out.println(cdline);
                    reBuffer = true;
                    cdPanControl = (FloatControl) cdline.getControl(FloatControl.Type.PAN);
                    cdGainControl = (FloatControl) cdline.getControl(FloatControl.Type.MASTER_GAIN);
                    updateCDVolume();
                } catch (Throwable t) {
                    throw new IllegalStateException("can't get line for media audio", t);
                }
                cdlineFreq = hz;
            }
        }
    }

    static long base;

    public boolean cdAudioData(byte[] data, int offset, int length) {
        long now = System.nanoTime();
        double delta = (now - base) / 1000000.0;
        if (log.isDebugEnabled()) {
            log.debug("AUDIO AT " + delta + " buffer space " + cdline.available() + " length " + length + " in sectors = " + (length / (1.0 * bytesPerSector)));
        }
        assert (bytesPerSector > 0);
        while (length > 0) {
            // write one sectors worth at a time
            int toWrite = length > bytesPerSector ? bytesPerSector : length;
            cdline.write(data, offset, toWrite);
            offset += toWrite;
            length -= toWrite;
            sectorsSinceReset++;
            if (sectorsSinceReset == SECTORS_TO_DELAY) {
                cdline.start();
            }
        }
        return sectorsSinceReset >= SECTORS_TO_DELAY;
    }

    public void setExternalCDAudioVolumeLeft(int vol) {
        vol /= 2;
        if (vol > 0x3fff) vol = 0x3fff;
        cdLeftVolExternal = vol;
        updateCDVolume();
    }

    public void setExternalCDAudioVolumeRight(int vol) {
        vol /= 2;
        if (vol > 0x3fff) vol = 0x3fff;
        cdRightVolExternal = vol;
        updateCDVolume();
    }

    private static int volMul(int v1, int v2) {
        if (v1 == 0x3fff) v1++;
        if (v2 == 0x3fff) v2++;
        return (v1 * v2) >> 14;
    }

    private static synchronized void updateCDVolume() {
        if (cdline != null) {
            int l = volMul(mainLeftVol, volMul(cdLeftVol, cdLeftVolExternal));
            int r = volMul(mainRightVol, volMul(cdRightVol, cdRightVolExternal));
            if (log.isDebugEnabled()) {
                log.debug("CD Volume L=" + MiscUtil.toHex(mainLeftVol, 4) + "," + MiscUtil.toHex(cdLeftVol, 4) + "," + MiscUtil.toHex(cdLeftVolExternal, 4) + " R=" + MiscUtil.toHex(r, 4));
            }
            int vol;
            if (l == r) {
                vol = l;
                cdPanControl.setValue(0.0f);
            } else if (l > r) {
                vol = l;
                cdPanControl.setValue(((float) r) / l - 1.0f);
            } else {
                vol = r;
                cdPanControl.setValue(1.0f - ((float) l) / r);
            }
            // todo fix this; it is just made up
            float db = -10 + (vol - 16384) / 512f;
            if (db < -80) db = -80;
            cdGainControl.setValue(db);
        }
    }

    public boolean isCDAudible() {
        return cdline != null;
    }

    private static int decodeVolume(int vol) {
        int rc;
        if (0 == (vol & 0x8000)) {
            // non sweep
            rc = vol & 0x3fff;
        } else {
            // sweep not supported, just go straight to min/max
            if ((vol & 0x2000) == 0) {
                // increase
                rc = 0x3fff;
            } else {
                // decrease
                rc = 0;
            }
        }
        // phase not yet supported
        return rc;
    }
}

