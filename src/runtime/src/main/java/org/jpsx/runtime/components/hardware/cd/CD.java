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
import org.jpsx.api.components.core.addressspace.AddressSpace;
import org.jpsx.api.components.core.addressspace.AddressSpaceRegistrar;
import org.jpsx.api.components.core.addressspace.MemoryMapped;
import org.jpsx.api.components.core.cpu.SCP;
import org.jpsx.api.components.core.dma.DMAController;
import org.jpsx.api.components.core.irq.IRQController;
import org.jpsx.api.components.core.scheduler.Quartz;
import org.jpsx.api.components.core.scheduler.ScheduledAction;
import org.jpsx.api.components.core.scheduler.Scheduler;
import org.jpsx.api.components.hardware.cd.CDAudioSink;
import org.jpsx.api.components.hardware.cd.CDDrive;
import org.jpsx.api.components.hardware.cd.CDMedia;
import org.jpsx.api.components.hardware.cd.MediaException;
import org.jpsx.runtime.JPSXComponent;
import org.jpsx.runtime.components.core.CoreComponentConnections;
import org.jpsx.runtime.components.core.DMAChannelOwnerBase;
import org.jpsx.runtime.components.core.IRQOwnerBase;
import org.jpsx.runtime.components.hardware.HardwareComponentConnections;
import org.jpsx.runtime.util.CDUtil;
import org.jpsx.runtime.util.MiscUtil;

// todo return the correct thing for audio/no disc
// todo filter!
// todo end of file!

// todo no need for static


// note we are two times over-clocked; we just slow stuff down if streaming XA audio
/**
 * New cd plan as follows:
 * <p/>
 * All CD action is timing based; i.e. when spinning we update head position on interrupt.
 * Data is read into buffers, Audio is passed straight along at the correct speed.
 * <p/>
 * AudioSink should bufer after a reset.
 * <p/>
 * it is up to native back end to read in bursts
 */
public class CD extends JPSXComponent implements MemoryMapped {
    private static boolean rateLimit1 = false;
    private static boolean rateLimit2 = true;

    private static final Logger log = Logger.getLogger("CD");
    /**
     * Logs information on CD commands set by the PSX
     */
    private static final Logger cmdLog = Logger.getLogger("CD.CMD");
    private static final boolean cmdLogDebug = cmdLog.isDebugEnabled();

    /**
     * The CD drive implementation
     */
    private static CDDrive drive;
    /**
     * The current CD media, or null
     */
    private static CDMedia media;

    private static XADecoder xaDecoder = new XADecoder();
    private static CDAudioSink cdAudioSink;
    private static int cdFreq = 0;

    private static long rateLimitStartTime;
    private static boolean rateLimitEnabled;

    // ratelimit1 stuff
    // number of audio bytes we've fed
    private static int actualAudioBytes;

    // ratelimit2 stuff
    private static int actualSectors;

    private static final boolean traceCD = log.isTraceEnabled();
    private static final boolean softwareCDDA = true;
    // Hardware addresses
    public static final int ADDR_CD_REG0 = 0x1f801800;
    public static final int ADDR_CD_REG1 = 0x1f801801;
    public static final int ADDR_CD_REG2 = 0x1f801802;
    public static final int ADDR_CD_REG3 = 0x1f801803;

    // 0, 1, 2, 3 as set by low 3 bits of reg0
    private static int regMode;

    // cdMode of the CD as set by CdlSetMode
    private static int cdMode = 0;

    private static int filterFile = 0;
    private static int filterChannel = 0;

    private static final int STATE_NONE = 0;
    private static final int STATE_STANDBY = 1;
    private static final int STATE_READN = 2;
    private static final int STATE_READS = 3;
    private static final int STATE_PLAY = 4;
    private static final int STATE_SEEKL = 5;
    private static final int STATE_SEEKP = 6;
    private static final int STATE_PAUSE = 7;
    private static final int STATE_STOP = 8;

    private static int substate;
    private static int state;

    private static boolean interruptEnabled;
    private static boolean seeking;
    private static boolean sectorReady;
    private static boolean sectorDismissed;
    private static boolean someDataRead;
    private static boolean waitForDataRead;
    private static int waitForDataReadCounter;

    private static boolean resultCleared;

    private static int[] currentSector = new int[2352 / 4];
    private static int currentSectorOffset;
    private static int currentSectorEnd;

    // reg0 status bits
    private static final int REG0_UNKNOWN_READY3 = 0x08;
    private static final int REG0_UNKNOWN_READY4 = 0x10;
    private static final int REG0_RESULTS_READY = 0x20;
    private static final int REG0_DATA_READY = 0x40;
//    private static final int REG0_CMDINPROGRESS  = 0x80;

    // reg3 status bits

    // not sure about error bits; sometime 0x1c is used as an error mask, sometimes 0x1d
    private static final int REG3_CDDA_PLAYING = 0x80;
    private static final int REG3_SEEKING = 0x40;
    private static final int REG3_READING_DATA = 0x20;
    private static final int REG3_SHELL_OPEN = 0x10;
    private static final int REG3_SEEK_ERROR = 0x04;
    private static final int REG3_STANDBY = 0x02; // spinning
    private static final int REG3_ERROR = 0x01;

    // cdMode values
    private static final int CD_MODE_DOUBLE_SPEED = 0x80;
    // playing XA data
    private static final int CD_MODE_XA = 0x40;

    private static final int CD_MODE_SIZE_MASK = 0x30;
    private static final int CD_MODE_SIZE_2048 = 0x00;
    private static final int CD_MODE_SIZE_2340 = 0x20;
    //private static final int CD_MODE_SIZE_2328    = 0x10;

    private static final int CD_MODE_FILTER = 0x08;
    // i guess this is supposed to send data while playing
    private static final int CD_MODE_REPORT = 0x04;
    // should pause at the end of a track
    private static final int CD_MODE_AUTO_PAUSE = 0x02;
    // playing DA data?
    private static final int CD_MODE_DA = 0x01;

    // --------------------------------------------------------------------

    private static IRQ irq;
    private static AddressSpace addressSpace;
    private static SCP scp;
    private static Quartz quartz;
    private static Scheduler scheduler;

    private static class IRQ extends IRQOwnerBase {
        private boolean set;

        private boolean inIRQCleared;
        private boolean inRaiseIRQ;

        private boolean pendingIRQ;

        public IRQ() {
            super(IRQController.IRQ_CD, "CD");
        }

        public boolean isSet() {
            return set;
        }

        // not synched, because irq already synchronized
        public void irqSet() {
            assert !set;
            if (false || traceCD) log.trace("Set CD interrupt");
            set = true;
        }

        public void irqCleared() {
            assert set;
            if (false || traceCD) log.trace("Clear CD interrupt");
            set = false;
            try {
                synchronized (this) {
                    assert !inIRQCleared;
                    inIRQCleared = true;
                    // if we're in raise IRQ, then the irq is getting set anyway,
                    // so no point in checking for another one.
                    if (inRaiseIRQ)
                        return;
                }
                checkForIRQ();
            } finally {
                synchronized (this) {
                    assert inIRQCleared;
                    inIRQCleared = false;
                    if (pendingIRQ) {
                        pendingIRQ = false;
                        if (!set) {
                            super.raiseIRQ();
                        }
                    }
                }
            }
        }

        public void raiseIRQ() {
            // work around deadlock possibility:
            // thread1: owns CD lock, and tries to signal CD interrupt (needs IRQ lock)
            // thread2: clearing CD intterrupt (owns IRQ lock), waiting on CD lock
            //
            // to do this, we simply ask the irq clearing thread to signal the interrupt when it is done.
            try {
                synchronized (this) {
                    assert !inRaiseIRQ;
                    inRaiseIRQ = true;
                    // if we're in irqCleared(), then just
                    // get it to set the irq for us.
                    if (inIRQCleared) {
                        pendingIRQ = true;
                        return;
                    }
                }
                super.raiseIRQ();
            } finally {
                synchronized (this) {
                    assert inRaiseIRQ;
                    inRaiseIRQ = false;
                }
            }
        }
    }

    // --------------------------------------------------------------------

    public CD() {
        super("JPSX CD Controller");
    }

    public void init() {
        super.init();
        CoreComponentConnections.ALL_MEMORY_MAPPED.add(this);
        irq = new IRQ();
        CoreComponentConnections.IRQ_OWNERS.add(irq);
        CoreComponentConnections.DMA_CHANNEL_OWNERS.add(new CDDMAChannel());
    }

    public void resolveConnections() {
        drive = HardwareComponentConnections.CD_DRIVE.resolve();
        media = drive.getCurrentMedia();
        cdAudioSink = HardwareComponentConnections.CD_AUDIO_SINK.peek();
        addressSpace = CoreComponentConnections.ADDRESS_SPACE.resolve();
        scp = CoreComponentConnections.SCP.resolve();
        quartz = CoreComponentConnections.QUARTZ.resolve();
        scheduler = CoreComponentConnections.SCHEDULER.resolve();
    }

    private static String getModeDescription(int mode) {
        return "...";
    }

    private static synchronized void setCdMode(int newMode) {
        if (traceCD && newMode != cdMode) {
            log.trace("CD MODE: " + getModeDescription(cdMode));
        }
        if (false && 0 != (newMode & CD_MODE_AUTO_PAUSE)) {
            System.out.println("AutoPause");
        }
        if (false && 0 != (newMode & CD_MODE_REPORT)) {
            System.out.println("Report");
        }
        cdMode = newMode;
    }

    private static class CmdParameters {
        private static final int MAX_PARAMS = 8;
        private static final int params[] = new int[MAX_PARAMS];
        private static int count = 0;

        public void reset() {
            count = 0;
        }

        public void add(int b) {
            if (traceCD) log.trace("CD: param " + count + " = " + MiscUtil.toHex(b, 2));
            params[count++] = b;
        }

        public int get(int index) {
            if (index < 0 || index > count) {
                log.error("out of bounds in CD");
                throw new ArrayIndexOutOfBoundsException(index);
            }
            return params[index];
        }
    }

    ;

    private static CmdParameters params = new CmdParameters();

    private static class CmdResult {
        private static final int MAX_RESULTS = 8;

        public static final int DATAREADY = 1;
        public static final int COMPLETE = 2;
        public static final int ACKNOWLEDGE = 3;
        public static final int DATAEND = 4;
        public static final int DISKERROR = 5;

        public int type;
        public int[] results = new int[MAX_RESULTS];
        public int resultCount;
        public int resultPos;

        public void add(int b) {
            results[resultCount++] = b;
        }

        public boolean remaining() {
            return resultPos != resultCount;
        }

        public int read() {
            if (resultPos < resultCount) {
                return results[resultPos++];
            } else {
                throw new IllegalStateException("CD: read more results than available!");
            }
        }

        public String toString() {
            StringBuilder rc = new StringBuilder();
            for (int i = 0; i < resultCount; i++) {
                rc.append(' ');
                rc.append(MiscUtil.toHex(results[i], 2));
            }
            return rc.toString();
        }
    }

    private static CmdResult nullResult = new CmdResult();
    /**
     * current result
     */
    private static CmdResult currentResult = nullResult;

    // protected by CD.class monitor
    private static CmdResult[] resultQueue = new CmdResult[16];
    // read==write implies empty;
    private static int resultQueueNextRead = 0;
    private static int resultQueueNextWrite = 0;

    private static class HeadLocation {
        private int m, s, f;

        public HeadLocation() {
            init(0);
        }

        public void init(HeadLocation h) {
            m = h.m;
            s = h.s;
            f = h.f;
        }

        public void init(int m, int s, int f) {
            this.m = m;
            this.s = s;
            this.f = f;
        }

        public void init(int sec) {
            sec += 150;
            f = sec % 75;
            sec /= 75;
            s = sec % 60;
            m = sec / 60;
        }

        public int getM() {
            return m;
        }

        public int getS() {
            return s;
        }

        public int getF() {
            return f;
        }

        public int getSector() {
            return CDUtil.toSector(m, s, f);
        }

        /**
         * simulate spinning of the CD in place
         */
        public void nextSpin() {
            f++;
            if (f == 75)
                f = 0;
        }

        public void nextSequential() {
            f++;
            if (f == 75) {
                f = 0;
                s++;
                if (s == 60) {
                    s = 0;
                    m++;
                }
            }
        }

        public String toString() {
            return MiscUtil.toHex(CDUtil.toBCD(m), 2) + "," + MiscUtil.toHex(CDUtil.toBCD(s), 2) + "," + MiscUtil.toHex(CDUtil.toBCD(f), 2);
        }
    }

    private static SectorThread sectorThread;

    private static HeadLocation lastSetLocation = new HeadLocation();
    private static boolean setLocDone;
    private static HeadLocation currentLocation = new HeadLocation();

    // commands
    private static final int CdlNop = 1;
    private static final int CdlSetLoc = 2;
    private static final int CdlPlay = 3;
    private static final int CdlFastForward = 4;
    private static final int CdlRewind = 5;
    private static final int CdlReadN = 6;
    private static final int CdlStandby = 7;
    private static final int CdlStop = 8;
    private static final int CdlPause = 9;
    private static final int CdlReset = 10;
    private static final int CdlMute = 11;
    private static final int CdlDemute = 12;
    private static final int CdlSetFilter = 13;
    private static final int CdlSetMode = 14;
    private static final int CdlGetLocL = 16;
    private static final int CdlGetLocP = 17;
    private static final int CdlGetTN = 19;
    private static final int CdlGetTD = 20;
    private static final int CdlSeekL = 21;
    private static final int CdlSeekP = 22;
    private static final int CdlTest = 25;
    private static final int CdlCheckID = 26;
    private static final int CdlReadS = 27;
    private static final int CdlHardReset = 28;
    private static final int CdlReadTOC = 30;

    private static class CDDMAChannel extends DMAChannelOwnerBase {
        public final int getDMAChannel() {
            return DMAController.DMA_CD;
        }

        static boolean first = true;

        public void beginDMATransferFromDevice(int base, int blocks, int blockSize, int ctrl) {
            if (false || traceCD)
                log.trace("begin DMA transfer from " + getName() + " " + MiscUtil.toHex(base, 8) + " 0x" + Integer.toHexString(blocks) + "*0x" + Integer.toHexString(blockSize) + " ctrl " + MiscUtil.toHex(ctrl, 8));
            if (blocks == 0) blocks = 1; // ??
            int size = blocks * blockSize;
            if (false || traceCD)
                log.trace("size = " + (size * 4) + " remaining = " + (currentSectorEnd - currentSectorOffset));
            AddressSpace.ResolveResult rr = new AddressSpace.ResolveResult();
            addressSpace.resolve(base, size * 4, true, rr);
            if (0 != (base & 3) || 0 != (currentSectorOffset & 3)) {
                throw new IllegalStateException("doh unaligned dma!");
            }
            if (currentSectorOffset + (size * 4) > currentSectorEnd) {
                throw new IllegalStateException("read off end of dma sector buffer");
            }

            int off = currentSectorOffset >> 2;
            if (false && size == 8) {
                log.trace(MiscUtil.toHex(currentSector[off], 8) + " " +
                        MiscUtil.toHex(currentSector[off + 1], 8) + " " +
                        MiscUtil.toHex(currentSector[off + 2], 8) + " " +
                        MiscUtil.toHex(currentSector[off + 3], 8) + " " +
                        MiscUtil.toHex(currentSector[off + 4], 8) + " " +
                        MiscUtil.toHex(currentSector[off + 5], 8) + " " +
                        MiscUtil.toHex(currentSector[off + 6], 8) + " " +
                        MiscUtil.toHex(currentSector[off + 7], 8));
            }
            for (int i = 0; i < size; i++) {
                rr.mem[rr.offset + i] = currentSector[off + i];
            }
            currentSectorOffset += size << 2;
            signalTransferComplete();
            setSomeDataRead();
        }

        public final String getName() {
            return "CD";
        }
    }

    public void registerAddresses(AddressSpaceRegistrar registration) {
        registration.registerWrite8Callback(ADDR_CD_REG0, CD.class, "cdReg0Write");
        registration.registerWrite8Callback(ADDR_CD_REG1, CD.class, "cdReg1Write");
        registration.registerWrite8Callback(ADDR_CD_REG2, CD.class, "cdReg2Write");
        registration.registerWrite8Callback(ADDR_CD_REG3, CD.class, "cdReg3Write");
        registration.registerRead8Callback(ADDR_CD_REG0, CD.class, "cdReg0Read");
        registration.registerRead8Callback(ADDR_CD_REG1, CD.class, "cdReg1Read");
        registration.registerRead8Callback(ADDR_CD_REG2, CD.class, "cdReg2Read");
        registration.registerRead8Callback(ADDR_CD_REG3, CD.class, "cdReg3Read");
    }

    public void begin() {
        sectorThread = new SectorThread();
    }

    // h/w access -----------------------------------------------------------------------------------

    public static void cdReg0Write(int address, int value) {
        if (traceCD) log.trace("CD_REG0_WRITE " + MiscUtil.toHex(value, 2));

        regMode = (value & 3);

        //if (regMode==0) {
        //    if (traceCD) log.info("cdMode set to 0; reseting result ptr");
        //    // reset the results pointer
        //    cdCurrentResultPos = 0;
        //}

        if (0 != (value & 0xfc)) {
            throw new IllegalStateException("CD: unknown reg0 write " + MiscUtil.toHex(value, 2));
        }
    }

    public static int cdReg0Read(int address) {

        int rc = regMode;

        if (currentResult.remaining()) {
            rc |= REG0_RESULTS_READY;
        }

        if (currentSectorOffset != currentSectorEnd) {
            rc |= REG0_DATA_READY;
        }

        // todo, check this
        rc |= REG0_UNKNOWN_READY3; // shell barfs early without this
        rc |= REG0_UNKNOWN_READY4; // bios cannot read sector without this...

        if (traceCD) log.trace("CD_REG0_READ " + MiscUtil.toHex(rc, 2));
        return rc;
    }

    public static void cdReg1Write(int address, int value) {
        if (traceCD) log.trace("CD_REG1_WRITE " + MiscUtil.toHex(value, 2));

        switch (regMode) {
            case 0:
                startCmd(value);
                break;
            case 3:
                log.trace("CD set right volume " + MiscUtil.toHex(value, 2));
                if (cdAudioSink != null) {
                    cdAudioSink.setExternalCDAudioVolumeRight(value << 8);
                }
                break;
            default:
                throw new IllegalStateException("CD: unknown reg1 write: cdMode " + regMode + " " + MiscUtil.toHex(value, 2));
        }
    }

    public static int cdReg1Read(int address) {
        int rc = 0;

        switch (regMode) {
            case 1:
                rc = currentResult.read();
                break;
            default:
                throw new IllegalStateException("CD: unknown reg1 read: cdMode " + regMode);
        }
        if (traceCD) log.trace("CD_REG1_READ " + MiscUtil.toHex(rc, 2));
        return rc;
    }

    public static void cdReg2Write(int address, int value) {
        if (traceCD) log.trace("CD_REG2_WRITE " + MiscUtil.toHex(value, 2));

        switch (regMode) {
            case 0:
                // writing command parameter
                params.add(value);
                break;
            case 1:
                // i think now that low bits are for IRQ (i.e. I know that 0x1f and 0x18 enable/disable
                // interrupts, however I think the 0x18 do other stuff.. used mainly by bios/shell
                // which are keen on 0x18 in all kinds of things :-)
                if (0 == (value & 7)) {
                    if (traceCD) log.trace("CD: disabling interrupts");
                    interruptEnabled = false;
                } else if (7 == (value & 7)) {
                    if (traceCD) log.trace("CD: enabling interrupts");
                    interruptEnabled = true;
                } else {
                    throw new IllegalStateException("CD: unknown reg2 write: cdMode " + regMode + " " + MiscUtil.toHex(value, 2));
                }

                if (0 == (value & 0xf8)) {
                } else if (0x18 == (value & 0xf8)) {
                } else {
                    throw new IllegalStateException("CD: unknown reg2 write: cdMode " + regMode + " " + MiscUtil.toHex(value, 2));
                }
                break;
            case 2:
                log.trace("CD set left volume " + MiscUtil.toHex(value, 2));
                if (cdAudioSink != null) {
                    cdAudioSink.setExternalCDAudioVolumeLeft(value << 8);
                }
                break;
            default:
                log.trace("CD ?set left volume low bits" + MiscUtil.toHex(value, 2));
                break;
        }
    }

    public static int cdReg2Read(int address) {
        if (currentSectorOffset < currentSectorEnd) {
            int val = currentSector[currentSectorOffset >> 2];
            switch (currentSectorOffset & 3) {
                case 0:
                    val = val & 0xff;
                    break;
                case 1:
                    val = (val >> 8) & 0xff;
                    break;
                case 2:
                    val = (val >> 16) & 0xff;
                    break;
                default:
                    val = (val >> 24) & 0xff;
                    break;
            }
            if (false || traceCD) log.trace("Read sector byte " + MiscUtil.toHex(val, 8));
            currentSectorOffset++;
            setSomeDataRead();
            return val;
        } else {
            throw new IllegalStateException("read off end of sector buffer via reg2");
        }
    }

    public static void cdReg3Write(int address, int value) {
        if (traceCD) log.trace("CD_REG3_WRITE " + MiscUtil.toHex(value, 2));

        switch (regMode) {
            case 0:
                if (value == 0x00) {
                    if (false || traceCD) {
                        log.trace("CD: dismiss sector");
                    }
                    dismissSector();
                } else if (false || value == 0x80) {
                    if (false || traceCD) {
                        log.trace("CD: prepare sector results");
                    }
                    prepareSector();
                } else {
                    throw new IllegalStateException("CD: unknown reg3 write: cdMode " + regMode + " " + MiscUtil.toHex(value, 2));
                }
                break;
            case 1:
                if (7 == (value & 7)) {
                    if (traceCD) log.trace("CD: dismiss result");
                    clearResult();
                }
                if (0 != (value & 0x40)) {
                    // not sure what this does...
                    if (traceCD) log.trace("CD: 0140");
                }
                if (0 != (value & 0xb8) && 0x18 != (value & 0xb8)) {
                    throw new IllegalStateException("CD: unknown reg3 write: cdMode " + regMode + " " + MiscUtil.toHex(value, 2));
                }
                break;
            case 2:
                log.trace("CD ?set right volume low bits" + MiscUtil.toHex(value, 2));
                break;
            default:
                log.trace("CD ?commit volume");
                break;
        }
    }

    public static int cdReg3Read(int address) {
        int rc = 0;
        switch (regMode) {
            case 0:
                // todo figure out what this is
                break;
            case 1:
                // to work around timing issues, we only make the result current when this register
                // is read...
                nextResult();
                rc = currentResult.type;
                break;
            default:
                throw new IllegalStateException("CD: unknown reg3 read: cdMode " + regMode);
        }
        if (traceCD) log.trace("CD_REG3_READ " + MiscUtil.toHex(rc, 2));
        return rc;
    }

    private static synchronized boolean resultQueueIsEmpty() {
        return resultQueueNextWrite == resultQueueNextRead;
    }

    private static synchronized void queueResult(CmdResult result) {
        int write = resultQueueNextWrite;

        if (traceCD) log.trace("CD: queuing result " + result);
        resultQueueNextWrite = (resultQueueNextWrite + 1) % resultQueue.length;
        if (resultQueueNextWrite == resultQueueNextRead) {
            throw new IllegalStateException("result queue full");
        }

        resultQueue[write] = result;
        checkForIRQ();
    }

    private static synchronized void checkForIRQ() {
        if (interruptEnabled && resultQueueNextRead != resultQueueNextWrite) {
            if (false || traceCD) log.trace("CD: a result available; raising IRQ");
            irq.raiseIRQ();
            if (false || traceCD) log.trace("CD: done raising IRQ");
        }
    }

    private static synchronized void clearResult() {
        if (false || traceCD) log.trace("CD: clear result");
        resultCleared = true;
        checkForIRQ();
    }

    private static synchronized void nextResult() {
        if (!resultCleared) {
            if (false || traceCD) log.trace("CD: nextResult: previous not reset, so not shifting");
            return;
        }
        if (resultQueueNextRead == resultQueueNextWrite) {
            if (false || traceCD) log.trace("CD: nextResult: none");
            currentResult = nullResult;
            resultCleared = true;
        } else {
            if (currentResult.type == CmdResult.DATAREADY && resultQueue[resultQueueNextRead].type == CmdResult.DATAREADY && irq.isSet()) {
                if (false || traceCD) log.trace("CD: deferring sequential dataready with irq set");
                currentResult = nullResult;
                resultCleared = true;
            } else {
                currentResult = resultQueue[resultQueueNextRead];
                resultQueue[resultQueueNextRead] = null;
                resultQueueNextRead = (resultQueueNextRead + 1) % resultQueue.length;
                resultCleared = false;
                if (currentResult.type == CmdResult.DATAREADY) {
                    sectorReady = true;
                }
                if (false || traceCD) log.trace("CD: nextResult: " + currentResult);
            }
        }
    }

    private static synchronized void clearResults() {
        resultQueueNextRead = resultQueueNextWrite;
        currentResult = nullResult;
    }

    private static CmdResult newAckStatusResult() {
        CmdResult res = new CmdResult();
        res.type = CmdResult.ACKNOWLEDGE;
        res.add(getStatus());
        return res;
    }

    private static CmdResult newDataReadyStatusResult() {
        CmdResult res = new CmdResult();
        res.type = CmdResult.DATAREADY;
        res.add(getStatus());
        return res;
    }

    private static CmdResult newCompleteStatusResult() {
        CmdResult res = new CmdResult();
        res.type = CmdResult.COMPLETE;
        res.add(getStatus());
        return res;
    }

    // this function called from r3000 thread;
    private static void startCmd(int cmd) {
        CmdResult res;

        switch (cmd) {
            case CdlNop:
                if (cmdLogDebug) cmdLog.debug("CdlNop");
                queueResult(newAckStatusResult());
                break;

            case CdlReset:
                if (cmdLogDebug) cmdLog.debug("CdlReset");
                clearResults();
                // standby will abort any read etc.
                setState(STATE_STANDBY);
                setCdMode(0);

                queueResult(newAckStatusResult());
                queueResult(newCompleteStatusResult());
                break;

            case CdlSetLoc:
                // todo, check what to do if we're reading..
                lastSetLocation.init(CDUtil.fromBCD(params.get(0)), CDUtil.fromBCD(params.get(1)), CDUtil.fromBCD(params.get(2)));
                setLocDone = true;
                if (cmdLogDebug) cmdLog.debug("CdlSetLoc " + lastSetLocation);
                queueResult(newAckStatusResult());
                break;

            case CdlTest:
                switch (params.get(0)) {
                    case 0x20:
                        if (cmdLogDebug) cmdLog.debug("CdlTest: getVersion");
                        res = new CmdResult();
                        res.type = CmdResult.ACKNOWLEDGE;
                        res.add(0x98);
                        res.add(0x06);
                        res.add(0x10);
                        res.add(0xc3);
                        queueResult(res);
                        break;
                    default:
                        throw new IllegalStateException("Unknown CdlTest command 0x" + MiscUtil.toHex(params.get(0), 2));
                }
                break;

            case CdlReadN:
                if (cmdLogDebug) cmdLog.debug("CdlReadN");
                resetAudio();
                setState(STATE_READN);
                queueResult(newAckStatusResult());
                break;

            case CdlStop:
                if (cmdLogDebug) cmdLog.debug("CdlStop");
                setState(STATE_STOP);
                clearResults();
                resetAudio();
                queueResult(newAckStatusResult());
                queueResult(newCompleteStatusResult());
                break;

            case CdlStandby:
                if (cmdLogDebug) cmdLog.debug("CdlStandby");
                setState(STATE_STANDBY);
                clearResults();
                queueResult(newAckStatusResult());
                queueResult(newCompleteStatusResult());
                break;

            case CdlPause:
                if (cmdLogDebug) cmdLog.debug("CdlPause");
                setState(STATE_PAUSE);
                clearResults();
                queueResult(newAckStatusResult());
                queueResult(newCompleteStatusResult());
                break;

            case CdlSetMode:
                if (cmdLogDebug) cmdLog.debug("CdlSetMode " + MiscUtil.toHex(params.get(0), 2));
                setCdMode(params.get(0));
                queueResult(newAckStatusResult());
                //if (true) throw new IllegalStateException("What?");
                break;

            case CdlCheckID:
                // TODO, do this correctly; currently just saying we have a psx CD present
                if (cmdLogDebug) cmdLog.debug("CdlCheckID");

                queueResult(newAckStatusResult());

                res = new CmdResult();
                res.type = CmdResult.ACKNOWLEDGE;

                int type = CDUtil.getMediaType(media);

                if (0 != (type & CDUtil.MEDIA_PRESENT)) {
                    boolean hasDataTracks = 0 != (type & CDUtil.MEDIA_HAS_DATA);
                    boolean hasAudioTracks = 0 != (type & CDUtil.MEDIA_HAS_AUDIO);

                    boolean isPlayStationDisc = hasDataTracks; // duh clearly wrong
                    boolean genuinePlayStationDisc = isPlayStationDisc;
                    boolean isAudioDisk = hasAudioTracks && !hasDataTracks;
                    // todo figure out what happens if you put a copied PSX disc with audio in; should you be
                    // able to play the audio tracks?

                    // bios always check [1] == 0x80... if so it'll try and boot as a playstation disc
                    // audio flag is only honored of [0] & 0x08
                    //   other flag of 0x40 means something else - perhaps disc with audio?
                    res.add(isAudioDisk ? 0x08 : 0x00);
                    res.add((isAudioDisk ? 0x10 : 0x00) | (genuinePlayStationDisc ? 0x00 : 0x80));
                    res.add(0x00);
                    res.add(0x00);
                    res.type = CmdResult.COMPLETE;
                } else {
                    res.type = CmdResult.DISKERROR;
                    // todo. check these
                    res.add(0);
                    res.add(0);
                    res.add(0);
                    res.add(0);
                }
                res.add('J');
                res.add('P');
                res.add('S');
                res.add('X');
                queueResult(res);
                break;

            case CdlGetTN:
                cmdLog.debug("CdlGetTN");

                res = new CmdResult();
                // made up check
                if (drive != null) {
                    res.type = CmdResult.ACKNOWLEDGE;
                    res.add(getStatus());
                    res.add(CDUtil.toBCD(media.getFirstTrack()));
                    res.add(CDUtil.toBCD(media.getLastTrack()));
                } else {
                    res.type = CmdResult.ACKNOWLEDGE;
                }
                queueResult(res);
                break;

            case CdlGetTD:
                int track = CDUtil.fromBCD(params.get(0));

                if (traceCD) cmdLog.debug("CdlGetTD " + track);

                int msf = media.getTrackMSF(track);

                int m = (msf & 0xff0000) >> 16;
                int s = (msf & 0xff00) >> 8;
                int f = msf & 0xff;

                res = new CmdResult();
                res.type = CmdResult.ACKNOWLEDGE;
                res.add(getStatus());
                res.add(m);
                res.add(s);
                res.add(f);
                queueResult(res);
                break;

            case CdlSeekL:
                if (traceCD) cmdLog.debug("CdlSeekL");
                // what happens if we're playing
                if (state == STATE_READN || state == STATE_READS) {
                    throw new IllegalStateException("SeekL while reading etc.");
                    //log.trace("seekl while reading!!!");
                }

                // i've seen seekl while playing; it.d have to close it.
                setState(STATE_SEEKL);
                queueResult(newAckStatusResult());
                break;

            case CdlSeekP:
                if (traceCD) cmdLog.debug("CdlSeekP");
                // what happens if we're playing
                if (false && state == STATE_PLAY || state == STATE_READN || state == STATE_READS)
                    throw new IllegalStateException("SeekP while playing/reading etc.");
                setState(STATE_SEEKP);
                queueResult(newAckStatusResult());
                queueResult(newCompleteStatusResult());
                break;

            case CdlMute:
                // todo implement
                if (cmdLogDebug) cmdLog.debug("CdlMute");
                queueResult(newAckStatusResult());
                break;

            case CdlDemute:
                // todo implement
                if (cmdLogDebug) cmdLog.debug("CdlDemute");
                queueResult(newAckStatusResult());
                break;

            case CdlSetFilter:
                filterFile = params.get(0);
                filterChannel = params.get(1);
                if (cmdLogDebug) cmdLog.debug("CdlSetFilter " + filterFile + " " + filterChannel);
                queueResult(newAckStatusResult());
                break;

            case CdlPlay:
                // todo check if this is always there
                int tr = params.get(0);
                if (cmdLogDebug) cmdLog.debug("CdlPlay " + tr);
                if (media != null && tr >= media.getFirstTrack() && tr <= media.getLastTrack()) {
                    int playMSF = media.getTrackMSF(tr);
                    lastSetLocation.init(CDUtil.fromBCD((playMSF >> 16) & 0xff), CDUtil.fromBCD((playMSF >> 8) & 0xff), CDUtil.fromBCD(playMSF & 0xff));
                    setLocDone = true;
                }
                resetAudio();
                setState(STATE_PLAY);
                queueResult(newAckStatusResult());
                break;
            case CdlFastForward:
                // todo implement; note play needed to undo
                if (cmdLogDebug) cmdLog.debug("CdlFastForward");
                queueResult(newAckStatusResult());
                break;
            case CdlRewind:
                // todo implement; note play needed to undo
                if (cmdLogDebug) cmdLog.debug("CdlRewind");
                queueResult(newAckStatusResult());
                break;
            case CdlGetLocL:
                if (cmdLogDebug) cmdLog.debug("CdlGetLocL");
                queueResult(newAckStatusResult());
                // todo add 8 bytes of last read sector
                break;

            case CdlGetLocP:
                if (cmdLogDebug) cmdLog.debug("CdlGetLocP");
                //System.out.println("getlocl "+currentLocation);
                // todo check this response; tombraider cares
                // 5 bytes; tn, playing1/0 ?, m, s, f
                res = new CmdResult();
                res.type = CmdResult.ACKNOWLEDGE;
                track = getTrack(currentLocation.getM(), currentLocation.getS(), currentLocation.getF());
                res.add(CDUtil.toBCD(track));
                res.add(state == STATE_PLAY ? 1 : 0);
                int trackMSF = track == 0 ? 0 : media.getTrackMSF(track);
                int trackSector = CDUtil.toSector(CDUtil.fromBCD((trackMSF>>16)&0xff), CDUtil.fromBCD(((trackMSF)>>8)&0xff), CDUtil.fromBCD(trackMSF&0xff));
                int relativeSector = currentLocation.getSector() - trackSector;
                res.add(relativeSector / (60*75));
                res.add((relativeSector / 75)%60);
                res.add(relativeSector % 75);
                res.add(CDUtil.toBCD(currentLocation.getM()));
                res.add(CDUtil.toBCD(currentLocation.getS()));
                res.add(CDUtil.toBCD(currentLocation.getF()));
                queueResult(res);
                //System.out.println(res);
                break;
            case CdlReadS:
//            traceCD = true;
                if (cmdLogDebug) cmdLog.debug("CdlReadS");
                resetAudio();
                setState(STATE_READS);
                queueResult(newAckStatusResult());
                break;
            case CdlReadTOC:
                if (cmdLogDebug) cmdLog.debug("CdlReadTOC");
                drive.refreshMedia();
                media = drive.getCurrentMedia();
                queueResult(newAckStatusResult());
                queueResult(newCompleteStatusResult());
                break;
            default:
                throw new IllegalStateException("Unknown Cdl command " + cmd);
        }
        // doing this here for now... seems fair enough
        params.reset();
    }

    private static int getStatus() {
        int rc = 0;

        if (state != STATE_NONE) {
            rc |= REG3_STANDBY;
        }

        if (seeking) {
            rc |= REG3_SEEKING;
        } else {
            if (state == STATE_PLAY) {
                rc |= REG3_CDDA_PLAYING;
            }
            if (state == STATE_READN || state == STATE_READS) {
                rc |= REG3_READING_DATA;
            }
        }

        //private static final int REG3_SHELL_OPEN    = 0x10;
        //private static final int REG3_SEEK_ERROR    = 0x04;
        //private static final int REG3_ERROR         = 0x01;
        return rc;
    }

    private static synchronized void setState(int newState) {
        log.trace("SET STATE " + newState);
        state = newState;
        substate = 0;

        if (setLocDone) {
            currentLocation.init(lastSetLocation);
            setLocDone = false;
        }
        sectorThread.newState();
        sectorReady = false;
        sectorDismissed = true;
        waitForDataReadCounter = 20;
        someDataRead = true;
        waitForDataRead = false;
        // reset dma availability.
        currentSectorOffset = currentSectorEnd = 0;
        updateCallback(quartz.nanoTime());
        CD.class.notifyAll();
    }

    //private static int threes = 0;

    private static synchronized long updateCallback(long baseTime) {
        long nextTime = 0L;
        if (state == STATE_READN || state == STATE_READS || (state == STATE_PLAY && softwareCDDA) || state == STATE_SEEKL || state == STATE_SEEKP) {
            long period;
            // note we
            if (0 == (cdMode & CD_MODE_DOUBLE_SPEED)) {
                period = Quartz.SEC / 150;
                //if (threes != 0) period++;
            } else {
                period = Quartz.SEC / 300;
                //if (threes == 2) period++;
            }
            //threes = threes++;
            //if (threes == 3) threes = 0;
            //if (0 != (cdMode & CD_MODE_XA)) period = ((period << 8) - period) >> 8;

            //if (state==STATE_READS)
            //    period = period * 300;
            if (traceCD) log.info("adding clock callback");
            nextTime = baseTime + period;
            if (!scheduler.isScheduled(sectorTick)) {
                scheduler.schedule(baseTime + period, sectorTick);
            } else {
                if (traceCD) log.debug("clock callback already exists; skipping add");
            }
        }
        return nextTime;
    }

    static int foobarCount = 0;

    static boolean toggle;

    private static synchronized long handleSectorTick(long currentTime) {
        if (traceCD) log.info("enter callback; state=" + state);
        switch (state) {
            case STATE_SEEKL:
                if (substate == 0) {
                    if (sectorThread.sectorsReady(true)) {
                        queueResult(newCompleteStatusResult());
                        substate = 1;
                    }
                }
                break;
            case STATE_SEEKP:
                currentLocation.nextSpin();
                break;
            case STATE_PLAY:
                if (sectorThread.sectorsReady(substate == 0)) {
                    substate = 1;
                    if (sectorThread.filterDASector()) {
                        // todo maybe this should be result queue is not full
                        if (0 != (cdMode & CD_MODE_REPORT) && resultQueueIsEmpty()) {
                            CmdResult res = new CmdResult();

                            int track = getTrack(currentLocation.getM(), currentLocation.getS(), currentLocation.getF());
                            int remainingM = 0;
                            int remainingS = 0;
                            if (media != null) {
                                if (track >= media.getFirstTrack() && track <= media.getLastTrack()) {
                                    int endMSF = media.getTrackMSF(track == media.getLastTrack() ? 0 : track + 1);
                                    int endM = CDUtil.fromBCD((endMSF >> 16) & 0xff);
                                    int endS = CDUtil.fromBCD((endMSF >> 8) & 0xff);
                                    int endF = CDUtil.fromBCD(endMSF & 0xff);
                                    int remainingSectors = ((endM * 60) + endS) * 75 + endF - 150;
                                    remainingSectors -= currentLocation.getSector();
                                    remainingSectors /= 75;
                                    remainingS = remainingSectors % 60;
                                    remainingM = remainingSectors / 60;
                                }
                            }

                            // made up check
                            res.type = CmdResult.DATAREADY;
                            res.add(1); // ?
                            res.add(track); // track number
                            res.add(0);
                            toggle = !toggle;
                            if (toggle) {
                                res.add(CDUtil.toBCD(remainingM));
                                res.add(CDUtil.toBCD(remainingS)); // ?
                            } else {
                                res.add(CDUtil.toBCD(currentLocation.getM()));
                                res.add(CDUtil.toBCD(currentLocation.getS()) | 0x80);
                            }
                            res.add(0);
                            res.add(0);
                            res.add(0);
                            queueResult(res);
                        }
                    }
                }
                break;
            case STATE_READN:
                // todo; end of file (or is dataend the end of the track?)

                // todo this can cause us to slow audio down, but that isn't our problem right now
                if (sectorThread.sectorsReady(substate == 0)) {
                    substate = 1;
                    if (sectorThread.filterDataSector()) {
                        if (sectorDismissed) {
                            if (!checkSomeDataRead()) break;
                            if (rateLimit2Exceeded()) break;
                            if (rateLimit2) actualSectors++;
                            someDataRead = false;
                            sectorDismissed = false;
                            if (false || traceCD) log.trace("ah.. setting data ready");
                            queueResult(newDataReadyStatusResult());
                        }
                    }
                }
                break;
            case STATE_READS:
                // todo; end of file (or is dataend the end of the track?)
                // todo, figure out what to do about sectorRequested...
                if (sectorThread.sectorsReady(substate == 0)) {
                    substate = 1;
                    if (sectorThread.filterDataSector()) {
                        if (sectorDismissed) {
                            if (!checkSomeDataRead()) break;
                            if (rateLimit2Exceeded()) break;
                            if (rateLimit2) actualSectors++;
                            someDataRead = false;
                            sectorDismissed = false;
                            if (false || traceCD) log.trace("ah.. setting data ready");
                            queueResult(newDataReadyStatusResult());
                        } else {
//                        foobarCount++;
                            //                      if (0==(foobarCount&15))
                            //                        log.trace("skipping sector ready for reads");
                        }
                    }
                }
                break;
        }
        if (traceCD) log.trace("leave callback; state=" + state);
        return updateCallback(currentTime);
    }

    private static boolean checkSomeDataRead() {
        if (waitForDataReadCounter > 0) {
            waitForDataReadCounter--;
            if (waitForDataReadCounter == 0) {
                log.trace("DATA READ CHECK TIMEOUT!!!!!");
            }
            return waitForDataReadCounter == 19;
        }
        return !(waitForDataRead && !someDataRead);
    }

    private static void setSomeDataRead() {
        if (waitForDataReadCounter > 0) {
            if (SCP.EXCEPT_INTERRUPT == scp.currentExceptionType()) {
                log.trace("DATA READ FROM IRQ - don't need hack");
            } else {
                log.trace("DATA READ FROM non-IRQ - NEED HACK");
                waitForDataRead = true;
            }
            waitForDataReadCounter = 0;
        }
        someDataRead = true;
    }

    private static void dismissSector() {
        if (sectorDismissed || !sectorReady)
            return;

        sectorThread.copySector(currentSector);
        switch (cdMode & 0x30) {
            case 0x00:
                currentSectorOffset = 24;
                currentSectorEnd = 24 + 2048;
                break;
            case 0x10:
                currentSectorOffset = 12;
                currentSectorEnd = 12 + 2328;
                break;
            case 0x20:
                currentSectorOffset = 12;
                currentSectorEnd = 12 + 2340;
                break;
            case 0x30:
                currentSectorOffset = 24;
                currentSectorEnd = 24 + 2328;
                break;
        }
        sectorDismissed = true;
        sectorReady = false;
    }

    private static void prepareSector() {
        if (currentSectorOffset == currentSectorEnd) {
            dismissSector();
        }
    }

    private static SectorTick sectorTick = new SectorTick();

    private static class SectorTick implements ScheduledAction {
        private int time;

        public void setTime(int t) {
            time = t;
        }

        public int getTime() {
            return time;
        }

        public long run(long currentTime) {
            //  long now = System.nanoTime();
            //  double delta = (now-base)/1000000.0;
            //  log.info("CD time "+delta);
            return handleSectorTick(currentTime);
        }
    }

    private static class SectorThread extends Thread {
        private static final int SECTOR_BUFFERS = 32;
        private static final int BUFFERS_BEFORE_READ = 8;
        private static final int MIN_SECTOR_RUN = 8; // should always read this many sectors sequentially.
        private HeadLocation[] sectorLocations = new HeadLocation[SECTOR_BUFFERS];
        private byte[][] sectorBuffers = new byte[SECTOR_BUFFERS][];
        private int sectorBufferNextRead;
        private int sectorBufferNextWrite;
        private HeadLocation readLocation = new HeadLocation();
        private boolean stateChanged;
        private boolean locationChanged;

        public SectorThread() {
            super("Sector read thread");
            for (int i = 0; i < SECTOR_BUFFERS; i++) {
                sectorLocations[i] = new HeadLocation();
                sectorBuffers[i] = new byte[2352];
            }

            setPriority(NORM_PRIORITY + 2);
            setDaemon(true);
            start();
        }

        public void newState() {
            assert Thread.holdsLock(CD.class);
            stateChanged = true;
            if (sectorBufferNextRead != sectorBufferNextWrite && sectorLocations[sectorBufferNextRead].getSector() == currentLocation.getSector()) {
                if (traceCD) log.trace("CD: updateLocation is identical to old one, so nop");
            } else {
                locationChanged = true;
                readLocation.init(currentLocation);
                // for now, clear buffer
                sectorBufferNextRead = sectorBufferNextWrite;
            }
            seeking = state == STATE_READN || state == STATE_READS || (state == STATE_PLAY && softwareCDDA) || state == STATE_SEEKL || state == STATE_SEEKP;
        }

        public boolean filterDataSector() {
            assert Thread.holdsLock(CD.class);
            // todo filter!
            assert sectorBufferNextRead != sectorBufferNextWrite;

            byte[] sector = sectorBuffers[sectorBufferNextRead];
            // xa audio submode == 0x64
//                log.trace( MiscUtil.toHex( (sector[4]>>16)&0xff, 8));
            //if (0!=(cdMode&CD_MODE_XA)) System.out.println(MiscUtil.toHex(sector[16],2)+" "+MiscUtil.toHex(sector[18],2));
            if (0x64 == sector[18]) {
                if (0 != (cdMode & CD_MODE_XA)) {
                    if (0 == (cdMode & CD_MODE_FILTER) || (filterFile == (sector[16]) && filterChannel == (sector[17]))) {
                        if (!handleXASector(sector))
                            return false;
                    } else {
                        // need to track that we've had a sector
                        if (rateLimit2) actualSectors++;
                        //log.trace("Skipping unfiltered sector "+(sector[4]&0xff)+" "+((sector[4]>>8)&0xff));
                    }
                }
                currentLocation.init(sectorLocations[sectorBufferNextRead]);
                //if (traceCD) log.info( "skipping XA audio sector");
                sectorBufferNextRead = (sectorBufferNextRead + 1) % SECTOR_BUFFERS;
                int sectors = (sectorBufferNextWrite + SECTOR_BUFFERS - sectorBufferNextRead) % SECTOR_BUFFERS;
                if (sectors < SECTOR_BUFFERS - MIN_SECTOR_RUN - 2) {
                    CD.class.notify();
                }
                return false;
            }
            return true;
        }

        public boolean filterDASector() {
            assert Thread.holdsLock(CD.class);
            // todo filter!
            assert sectorBufferNextRead != sectorBufferNextWrite;

            byte[] sector = sectorBuffers[sectorBufferNextRead];

            if (handleDASector(sector)) {
                currentLocation.init(sectorLocations[sectorBufferNextRead]);
                sectorBufferNextRead = (sectorBufferNextRead + 1) % SECTOR_BUFFERS;
                int sectors = (sectorBufferNextWrite + SECTOR_BUFFERS - sectorBufferNextRead) % SECTOR_BUFFERS;
                if (sectors < SECTOR_BUFFERS - MIN_SECTOR_RUN - 2) {
                    CD.class.notify();
                }
                return true;
            }
            return false;
        }

        public boolean sectorsReady(boolean seek) {
            assert Thread.holdsLock(CD.class);
            int sectors = (sectorBufferNextWrite + SECTOR_BUFFERS - sectorBufferNextRead) % SECTOR_BUFFERS;
            boolean rc = seek ? (sectors > BUFFERS_BEFORE_READ) : (sectors > 0);
            if (traceCD) log.trace("sectors now: " + sectors);
            seeking = !rc;
            return rc;
        }

        public void copySector(int[] target) {
            synchronized (CD.class) {
                byte[] byteBuf = sectorBuffers[sectorBufferNextRead];

                for (int i = 0; i < 2352 / 4; i++) {
                    currentSector[i] = ((((int) byteBuf[i * 4 + 3]) & 0xff) << 24) |
                            ((((int) byteBuf[i * 4 + 2]) & 0xff) << 16) |
                            ((((int) byteBuf[i * 4 + 1]) & 0xff) << 8) |
                            ((((int) byteBuf[i * 4]) & 0xff));
                }
                currentLocation.init(sectorLocations[sectorBufferNextRead]);

                if (traceCD) log.trace("COPY SECTOR " + MiscUtil.toHex(target[3], 6) + " " + currentLocation);

                // todo - this is only valid for data/xa sectors
//				assert CDUtil.fromBCD( target[3] & 0xff ) == currentLocation.getM() : "sectors don't match " + MiscUtil.toHex( target[3], 6 ) + " " + currentLocation;
//				assert CDUtil.fromBCD( (target[3] >> 8) & 0xff ) == currentLocation.getS() : "sectors don't match " + MiscUtil.toHex( target[3], 6 ) + " " + currentLocation;
//				assert CDUtil.fromBCD( (target[3] >> 16) & 0xff ) == currentLocation.getF() : "sectors don't match " + MiscUtil.toHex( target[3], 6 ) + " " + currentLocation;
                sectorBufferNextRead = (sectorBufferNextRead + 1) % SECTOR_BUFFERS;
                int sectors = (sectorBufferNextWrite + SECTOR_BUFFERS - sectorBufferNextRead) % SECTOR_BUFFERS;
                if (sectors < SECTOR_BUFFERS - MIN_SECTOR_RUN - 2) {
                    CD.class.notify();
                }
            }
        }

        public void run() {
            log.info("SectorThread starts");
            try {
                while (true) {
                    boolean read;
                    synchronized (CD.class) {
                        read = state == STATE_READN || state == STATE_READS || (state == STATE_PLAY && softwareCDDA) || state == STATE_SEEKL || state == STATE_SEEKP;
                        int sectors = (sectorBufferNextWrite + SECTOR_BUFFERS - sectorBufferNextRead) % SECTOR_BUFFERS;
                        if (read) {
                            if (sectors != SECTOR_BUFFERS - 2) {
                                sectorLocations[sectorBufferNextWrite].init(readLocation);
                                read = true;
                            } else {
                                read = false;
                            }
                        }
                        locationChanged = false;
                        stateChanged = false;
                    }

                    if (read) {
                        if (traceCD) log.trace("reading sector " + readLocation);
                        try {
                            media.readSector(readLocation.getSector(), sectorBuffers[sectorBufferNextWrite]);
                        } catch (MediaException e) {
                        }
                        if (traceCD)
                            log.trace("read sector complete " + MiscUtil.toHex(sectorBuffers[sectorBufferNextWrite][3], 6) + " " + readLocation);
                    }

                    synchronized (CD.class) {
                        // only want to update stuff if we didn't change location in the interim
                        if (read && !locationChanged) {
                            sectorBufferNextWrite = (sectorBufferNextWrite + 1) % SECTOR_BUFFERS;
                            readLocation.nextSequential();
                        }
                        // sleep if we had nothing to do, and noone issued a new command in the interim
                        if (!read && !stateChanged) {
                            try {
                                if (traceCD) log.trace("SectorThread sleeps");
                                CD.class.wait();
                                if (traceCD) log.trace("SectorThread wakes");
                            } catch (InterruptedException e) {
                            }
                        }
                    }
                }
            } finally {
                log.info("SectorThread ends");
            }
        }
    }

    private static void resetAudio() {
        xaDecoder.reset();
        cdFreq = 0;
        rateLimitEnabled = false;
    }

    private static final byte[] audioBuffer = new byte[4032 * 4];

    private static boolean handleXASector(byte[] sectorBuffer) {
        int samples = 0;
        if (cdFreq == 0) {
            if (cdAudioSink != null) {
                cdAudioSink.newCDAudio();
            }
            // need to decode to find the frequency
            samples = xaDecoder.decodeXAAudioSector(sectorBuffer, audioBuffer);
            if (cdFreq != xaDecoder.getFrequency()) {
                cdFreq = xaDecoder.getFrequency();
                if (cdAudioSink != null) {
                    cdAudioSink.setCDAudioRate(cdFreq);
                }
            }
        }

        if (rateLimit1Exceeded()) return false;
        if (rateLimit2Exceeded()) return false;

        if (cdAudioSink != null && cdAudioSink.isCDAudible()) {
            if (samples == 0) samples = xaDecoder.decodeXAAudioSector(sectorBuffer, audioBuffer);
            actualAudioBytes += samples * 4;
            //samples = Resample.resample(audioBuffer,xaDecoder.getFrequency(),samples,audioBuffer2,44100);
            //cdAudioSink.cdAudioData(audioBuffer2,0,samples*4);
            // todo we might want to send data anyway?
            boolean started = cdAudioSink.cdAudioData(audioBuffer, 0, samples * 4);
            if (rateLimit1 || rateLimit2) {
                if (started) {
                    if (!rateLimitEnabled) {
                        rateLimitEnabled = true;
                        if (rateLimit1) actualAudioBytes = 0;
                        if (rateLimit2) actualSectors = 0;
                        rateLimitStartTime = quartz.nanoTime();
                        // todo should we rate limit anyway?
                        rateLimitEnabled = true;
                    } else {
                        if (rateLimit2) actualSectors++;
                    }
                }
            }
        }
        return true;
    }

    private static boolean handleDASector(byte[] sectorBuffer) {
        if (cdFreq == 0) {
            cdFreq = 44100;
            if (cdAudioSink != null) {
                cdAudioSink.newCDAudio();
                cdAudioSink.setCDAudioRate(cdFreq);
            }
        }

        if (rateLimit1Exceeded()) return false;
        if (rateLimit2Exceeded()) return false;

        if (cdAudioSink != null && cdAudioSink.isCDAudible()) {
            boolean started = cdAudioSink.cdAudioData(sectorBuffer, 0, 2352);
            if (rateLimit1 || rateLimit2) {
                if (started) {
                    if (!rateLimitEnabled) {
                        rateLimitEnabled = true;
                        if (rateLimit1) actualAudioBytes = 0;
                        if (rateLimit2) actualSectors = 0;
                        rateLimitStartTime = quartz.nanoTime();
                        // todo should we rate limit anyway?
                        rateLimitEnabled = true;
                    } else {
                        if (rateLimit2) actualSectors++;
                    }
                }
            }
        }
        return true;
    }

    private static boolean rateLimit1Exceeded() {
        if (!(rateLimitEnabled && rateLimit1)) return false;
        long time = quartz.nanoTime();
        assert false;
        int expectedAudioBytes = (int) ((cdFreq * (time - rateLimitStartTime)) / 4000L);
        if (log.isDebugEnabled()) {
            log.debug(expectedAudioBytes + " " + actualAudioBytes);
        }

        return actualAudioBytes > expectedAudioBytes;
    }

    private static boolean rateLimit2Exceeded() {
        if (!(rateLimitEnabled && rateLimit2)) return false;
        long time = quartz.nanoTime();
        int sps = (cdMode & CD_MODE_DOUBLE_SPEED) != 0 ? 150 : 75;

        int expectedSectors = (int) ((sps * (time - rateLimitStartTime)) / Quartz.SEC);
        if (log.isDebugEnabled()) {
//            log.debug(expectedSectors+" "+actualSectors);
        }

        return actualSectors > expectedSectors;
    }

    /**
     * @param m
     * @param s
     * @param f
     * @return
     */
    private static int getTrack(int m, int s, int f) {
        int MSF = CDUtil.toMSF(m, s, f);
        if (media != null) {
            int rc = 0;
            for (int i = media.getFirstTrack(); i <= media.getLastTrack(); i++) {
                int trackMSF = media.getTrackMSF(i);
                if (MSF < trackMSF) return rc;
                rc++;
            }
            if (MSF < media.getTrackMSF(0)) {
                return rc;
            }
        }
        return 0;
    }
}

