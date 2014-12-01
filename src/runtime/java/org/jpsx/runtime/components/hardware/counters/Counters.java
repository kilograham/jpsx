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
package org.jpsx.runtime.components.hardware.counters;

import org.apache.log4j.Logger;
import org.jpsx.api.components.core.addressspace.AddressSpace;
import org.jpsx.api.components.core.addressspace.AddressSpaceRegistrar;
import org.jpsx.api.components.core.addressspace.MemoryMapped;
import org.jpsx.api.components.core.irq.IRQController;
import org.jpsx.api.components.core.scheduler.Quartz;
import org.jpsx.api.components.core.scheduler.ScheduledAction;
import org.jpsx.api.components.core.scheduler.Scheduler;
import org.jpsx.api.components.hardware.cd.CDDrive;
import org.jpsx.api.components.hardware.cd.CDMedia;
import org.jpsx.runtime.SingletonJPSXComponent;
import org.jpsx.runtime.components.core.CoreComponentConnections;
import org.jpsx.runtime.components.core.IRQOwnerBase;
import org.jpsx.runtime.components.hardware.HardwareComponentConnections;
import org.jpsx.runtime.util.CDUtil;
import org.jpsx.runtime.util.MiscUtil;

// todo pick NTSC or PAL other than by media.

// todo IRQs for root counters
public class Counters extends SingletonJPSXComponent implements MemoryMapped {
    public static final String CATEGORY = "Counters";
    public static final Logger log = Logger.getLogger(CATEGORY);

    public static final int ADDR_HARD_COUNTER0_COUNT = 0x1f801100;
    public static final int ADDR_HARD_COUNTER1_COUNT = 0x1f801110;
    public static final int ADDR_HARD_COUNTER2_COUNT = 0x1f801120;
    public static final int ADDR_HARD_COUNTER0_MODE = 0x1f801104;
    public static final int ADDR_HARD_COUNTER1_MODE = 0x1f801114;
    public static final int ADDR_HARD_COUNTER2_MODE = 0x1f801124;
    public static final int ADDR_HARD_COUNTER0_TARGET = 0x1f801108;
    public static final int ADDR_HARD_COUNTER1_TARGET = 0x1f801118;
    public static final int ADDR_HARD_COUNTER2_TARGET = 0x1f801128;

    private static final long CLOCK_FREQ = 33868800L;

    private static final int NTSC_VSYNC_FREQ = 60;
    private static final int PAL_VSYNC_FREQ = 50;
    private static long VSYNC_FREQ;
    private static long HSYNC_FREQ;
    private static long PIXEL_FREQ;

    private static long VSYNC_PERIOD;

    private static Counter[] counters;

    private static boolean bandicootUS;
    private static IRQController irqController;
    private static Quartz quartz;
    private static Scheduler scheduler;

    public Counters() {
        super("JPSX Hardware Counters");
    }

    public void init() {
        super.init();    //To change body of overridden methods use File | Settings | File Templates.
        CoreComponentConnections.ALL_MEMORY_MAPPED.add(this);
        // default to something
        setNTSC(true);
        bandicootUS = getBooleanProperty("bandicootUS", false);
        log.info("Bandicoot = " + bandicootUS);
        counters = new Counter[3];
        counters[0] = new Counter(0);
        counters[1] = new Counter(1);
        counters[2] = new Counter(2);
        for (Counter counter : counters) {
            CoreComponentConnections.IRQ_OWNERS.add(counter.getIrq());
        }
    }

    @Override
    public void resolveConnections() {
        super.resolveConnections();
        irqController = CoreComponentConnections.IRQ_CONTROLLER.resolve();
        quartz = CoreComponentConnections.QUARTZ.resolve();
        scheduler = CoreComponentConnections.SCHEDULER.resolve();
    }

    public void begin() {
        for (Counter counter : counters) {
            counter.init();
        }
        CDDrive drive = HardwareComponentConnections.CD_DRIVE.resolve();
        CDMedia media = drive == null ? null : drive.getCurrentMedia();

        if (0 != (CDUtil.getMediaType(media) & CDUtil.MEDIA_REGION_EUROPE)) {
            log.info("Picking PAL based on current media");
            setNTSC(false);
        } else {
            log.info("Picking NTSC based on current media");
            setNTSC(true);
        }
        new VSyncAction().start();
    }

    // todo, one shot?
    static class Counter {
        // psx
        private int index; // counter num
        private int mode;
        private int target;

        // counting
        private long freq; // incs per second
        private int baseValue;
        private long baseTime;
        private long lastElapsed;
        private int delta;
        private boolean toggle;

        // irqs
        private boolean irqsEnabled;
        private long irqPeriod; // 1/4096 ms

        private IRQ irq;

        private class IRQ extends IRQOwnerBase {
            public IRQ() {
                super(4 + index, "COUNTER " + index);
            }
        }

        public IRQ getIrq() {
            return irq;
        }

        public Counter(int index) {
            this.index = index;
            irq = new IRQ();
        }

        public void init() {
            setValue(0);
            setTarget(0);
        }

        public int getMode() {
            return mode;
        }

        public void setMode(int mode) {
            if (log.isDebugEnabled()) {
                log.debug("COUNTER " + index + " mode " + MiscUtil.toHex(mode, 8));
            }
            this.mode = mode;
            update();
        }

        public void setValue(int value) {
            baseTime = quartz.nanoTime();
            lastElapsed = -1;
            delta = 0;
            baseValue = value;
            //System.out.println("Set value "+value);
            //System.out.println("COUNTER "+index+" setvalue = "+value+" pc="+MiscUtil.toHex(r3000.getPC(),8));
        }

        public int getValue() {
            long elapsed = quartz.nanoTime() - baseTime;
            int newValue = baseValue + (int) (elapsed * freq / Quartz.SEC);
            /* - this should be irrelevent now we have nanoTime
            if (elapsed == lastElapsed) {
                // count up to the amount we'll reach on the next
                // millisecond tick
                if (toggle) {
                    toggle = !toggle;
                } else {
                    if (delta < ((inc >> 12) - 1)) {
                        delta++;
                    }
                    newValue += delta;
                }
            } else {
                lastElapsed = elapsed;
                delta = 0;
            } */
            // todo this looks wrong; since newValue may roll over.
            int rc = newValue % (target + 1);
            if (log.isTraceEnabled()) {
                log.trace("read counter " + index + " " + rc + " " + MiscUtil.toHex(CoreComponentConnections.R3000.resolve().getPC(), 8));
            }
            //System.out.println("counter "+index+" "+rc);
            //System.out.println("COUNTER "+index+" getvalue = "+rc+" pc="+MiscUtil.toHex(r000.getPC(),8));
            return rc;
        }

        public void setTarget(int target) {
            target = target & 0xffff;
            if (target == 0) {
                target = 0x10000;
            }
            this.target = target;
            update();
        }

        public int getTarget() {
            return target & 0xffff;
        }

        public void setTime(int previous) {
            assert irqsEnabled;
            //callbackTime = previous + irqPeriod;
        }

        private synchronized void update() {
            long newFreq = -1L;
            switch (mode & 0x300) {
                case 0:
                    newFreq = CLOCK_FREQ;
                    break;
                case 0x100:
                    if (index == 0) {
                        // pixel
                        newFreq = PIXEL_FREQ;
                    } else if (index == 1) {
                        // horiz
                        newFreq = HSYNC_FREQ;
                    }
                    break;
                case 0x200:
                    if (index == 2) {
                        // 1/8th system scheduler
                        newFreq = CLOCK_FREQ / 8;
                    } else {
                        newFreq = CLOCK_FREQ;
                    }
                    break;
                case 0x300:
                    break;
            }
            if (newFreq == -1) {
                log.warn("COUNTER " + index + " unknown clock rate; mode " + MiscUtil.toHex(mode, 4));
                newFreq = CLOCK_FREQ;
            }
            // note round up; for example Wipeout counts hsyncs, and does an extra
            // vsync if we come in at 623 for two vsync rather than 624
            //freq = ((newFreq << 12) + 999) / 1000;
            freq = newFreq + 1;
            if (log.isDebugEnabled()) {
                log.debug("COUNTER " + index + " frequency " + newFreq);
            }
            if ((mode & 0x50) == 0x50 && (index != 2 || (mode & 1) == 0)) {
                long ips = newFreq / target; // Hz
                log.info("REQUIRE COUNTER IRQ COUNTER " + index + " FREQ " + newFreq + " TARGET " + target + " IPS " + ips);
                //irqPeriod = 4096000L/freq // microseconds

                if (!irqsEnabled) {
                    irqsEnabled = true;
                }
                //baseTime = MTScheduler.getTime();
                //setTime( baseTime);
                //MTScheduler.addCallback( this);
            } else {
                irqsEnabled = false;
            }
        }
    }

    public void registerAddresses(AddressSpaceRegistrar registrar) {
        for (int i = 0; i <= 0x20; i += 0x10) {
            registrar.registerRead32Callback(ADDR_HARD_COUNTER0_COUNT + i, Counters.class, "countRead32", true);
            registrar.registerWrite32Callback(ADDR_HARD_COUNTER0_COUNT + i, Counters.class, "countWrite32", true);
            registrar.registerRead32Callback(ADDR_HARD_COUNTER0_MODE + i, Counters.class, "modeRead32", true);
            registrar.registerWrite32Callback(ADDR_HARD_COUNTER0_MODE + i, Counters.class, "modeWrite32", true);
            registrar.registerRead32Callback(ADDR_HARD_COUNTER0_TARGET + i, Counters.class, "targetRead32", true);
            registrar.registerWrite32Callback(ADDR_HARD_COUNTER0_TARGET + i, Counters.class, "targetWrite32", true);
        }
    }

    private static Counter getCounter(int address) {
        return counters[(address & 0x30) >> 4];
    }

    public static int countRead32(int address) {
        return getCounter(address).getValue();
    }

    public static void countWrite32(int address, int value, int mask) {
        getCounter(address).setValue(value & 0xffff);
    }

    public static int modeRead32(int address) {
        return getCounter(address).getMode();
    }

    public static void modeWrite32(int address, int value, int mask) {
        Counter c = getCounter(address);
        c.setMode((c.getMode() & ~mask) | (value & mask));
    }

    public static int targetRead32(int address) {
        return getCounter(address).getTarget();
    }

    public static void targetWrite32(int address, int value, int mask) {
        Counter c = getCounter(address);
        mask &= 0xffff;
        c.setTarget((c.getTarget() & ~mask) | (value & mask));
    }

    public static void setNTSC(boolean NTSC) {
        VSYNC_FREQ = NTSC ? NTSC_VSYNC_FREQ : PAL_VSYNC_FREQ;
        VSYNC_PERIOD = Quartz.SEC / VSYNC_FREQ;
        int scans = NTSC ? 525 : 625;
        HSYNC_FREQ = VSYNC_FREQ * scans / 2;
        // todo; does this change with the video mode?
        // todo is this correct
        PIXEL_FREQ = 3840L * HSYNC_FREQ;
        if (log.isDebugEnabled()) {
            log.debug("HSYNC FREQ: " + HSYNC_FREQ);
            log.debug("PIXEL FREQ: " + PIXEL_FREQ);
        }
    }

    private static class VSyncAction implements ScheduledAction {
        private long nextTime;

        public void start() {
            nextTime = quartz.nanoTime() + VSYNC_PERIOD;
            scheduler.schedule(nextTime, this);
        }

        public long run(long currentTime) {
            irqController.raiseIRQ(IRQController.IRQ_VSYNC);
            // if we missed some vsyncs, then skip them
            while (currentTime >= nextTime) {
                if (bandicootUS && currentTime > Quartz.SEC * 22) {
                    try {
                        // bandicoot US
                        AddressSpace addressSpace = CoreComponentConnections.ADDRESS_SPACE.resolve();
                        addressSpace.write32(0x80034520, addressSpace.read32(0x80034520) + 17);
                    } catch (Throwable t) {
                    }
                }
                nextTime = nextTime + VSYNC_PERIOD;
            }
            return nextTime;
        }
    }
}

