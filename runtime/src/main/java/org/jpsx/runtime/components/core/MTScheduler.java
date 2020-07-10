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
package org.jpsx.runtime.components.core;

import org.apache.log4j.Logger;
import org.jpsx.api.components.core.cpu.PollBlockListener;
import org.jpsx.api.components.core.cpu.R3000;
import org.jpsx.api.components.core.scheduler.Quartz;
import org.jpsx.api.components.core.scheduler.ScheduledAction;
import org.jpsx.api.components.core.scheduler.Scheduler;
import org.jpsx.runtime.SingletonJPSXComponent;

public class MTScheduler extends SingletonJPSXComponent implements Scheduler {
    private static final Logger log = Logger.getLogger("Scheduler");
    private static final boolean logTraceEnabled = log.isTraceEnabled();

    public MTScheduler() {
        super("JPSX Multi-threaded Scheduler");
    }

    private Quartz quartz;
    private R3000 r3000;
    private PollBlockListener pollBlockListeners;

    private static TickGeneratorThread tickThread;
    private static ActionThread actionThread;

    private static final Object cpuControlMonitor = new Object();
    private static volatile int cpuResumeCount;

    public void init() {
        super.init();
        tickThread = new TickGeneratorThread();
        actionThread = new ActionThread();
        CoreComponentConnections.SCHEDULER.set(this);
    }

    public void resolveConnections() {
        super.resolveConnections();
        quartz = CoreComponentConnections.QUARTZ.resolve();
        r3000 = CoreComponentConnections.R3000.resolve();
        pollBlockListeners = CoreComponentConnections.POLL_BLOCK_LISTENERS.resolve();
    }

    public void begin() {
        tickThread.start();
        actionThread.start();
    }

    public void schedule(long time, ScheduledAction action) {
        schedule(time, Quartz.MSEC, action);
    }

    public void schedule(long time, long jitter, ScheduledAction action) {
        if (jitter < Quartz.MSEC) {
            // todo fix this via CPU
            throw new IllegalStateException("jitter <1 msec not yet supported");
        }
        actionThread.schedule(time, action);
    }

    public boolean isScheduled(ScheduledAction action) {
        // todo cope with non-action thread actions when we have them
        return actionThread.isScheduled(action);
    }

    public void cpuThreadWait() {
        assert r3000.isExecutionThread();
        pollBlockListeners.aboutToBlock();
        int count = cpuResumeCount;
        synchronized (cpuControlMonitor) {
            // if we've had an interruption in the meanwhile, then we don't bother to wait
            if (count == cpuResumeCount) {
                try {
                    cpuControlMonitor.wait();
                } catch (InterruptedException e) {
                }
            }
        }
    }

    public void cpuThreadNotify() {
        synchronized (cpuControlMonitor) {
            cpuResumeCount++;
            cpuControlMonitor.notify();
        }
    }


    /**
     * high priority thread which does tight sleep(1) loop. On Win32 this keeps
     * pretty good time
     */
    private static class TickGeneratorThread extends Thread {
        private TickGeneratorThread() {
            super("JPSX Scheduler tick generator thread");
            setPriority(MAX_PRIORITY);
            setDaemon(true);
        }

        public void run() {
            log.info("Tick generator thread starts");
            try {
                for (; ;) {
                    actionThread.tick();
                    try {
                        sleep(1);
                    } catch (InterruptedException e) {
                    }
                }
            } finally {
                log.info("Tick generator thread ends");
            }
        }
    }

    private class ActionThread extends Thread {
        private static final int MAX_ACTIONS = 64;
        /**
         * List of actions, not kept sorted since it is generally small; indeed we limit the size
         * the list does not auto-compact instead null entries are left in the middle
         */
        private ScheduledAction[] actions = new ScheduledAction[MAX_ACTIONS];
        /**
         * List of scheduled times, not kept sorted since it is generally small; indeed we limit the size
         * the list does not auto-compact instead null entries are left in the middle marked by 0L time.
         */
        private long[] times = new long[MAX_ACTIONS];
        private int length = 0; // length of above arrays

        private volatile long wakeupTime = Long.MAX_VALUE;

        public ActionThread() {
            super("JPSX Scheduler action thread");
            setPriority(NORM_PRIORITY + 2);
            setDaemon(true);
        }

        public void tick() {
            // called by the tick generator thread, should do any signalling and return ASAP
            long time = quartz.nanoTime();
            if (time < wakeupTime) return;
            synchronized (this) {
                // wake our potentially blocked thread up
                notify();
            }
        }

        public void run() {
            log.info("ScheduledAction thread starts");
            for (; ;) {
                long now = sleepUntilWakeupTime();
                if (logTraceEnabled) {
                    log.trace("Wakeup " + traceTime(now));
                }
                // make one pass over the array, providing synchronized access to our data structures
                // but not holding a lock during a callback
                int i = 0;
                for (; ;) {
                    ScheduledAction toRun = null;
                    synchronized (this) {
                        if (i >= length) break;
                        long t = times[i];
                        if (t != 0L && t <= now) {
                            toRun = actions[i];
                            assert toRun != null;
                        }
                    }
                    if (toRun != null) {
                        if (logTraceEnabled) {
                            log.trace("Run " + toRun);
                        }
                        long rescheduleTime = toRun.run(now);
                        if (logTraceEnabled) {
                            log.trace("Reschedule = " + traceTime(rescheduleTime));
                        }
                        synchronized (this) {
                            times[i] = rescheduleTime;
                            if (rescheduleTime == 0L) {
                                actions[i] = null;
                                if (i == length) length--;
                            }
                        }
                    }
                    i++;
                }
                updateWakeupTime();
            }
        }

        private synchronized void updateWakeupTime() {
            long next = Long.MAX_VALUE;
            if (logTraceEnabled) {
                log.trace("New actions: ");
                for (int i = 0; i < length; i++) {
                    long t = times[i];
                    if (t != 0L) {
                        log.trace(traceTime(t) + " " + actions[i]);
                    }
                }
            }
            for (int i = 0; i < length; i++) {
                long t = times[i];
                if (t != 0L && t < next) next = t;
            }
            wakeupTime = next;
        }

        private long sleepUntilWakeupTime() {
            for (; ;) {
                long t = quartz.nanoTime();
                if (t >= wakeupTime) return t;
                synchronized (this) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        // we interrupt ourselves in {@link #schedule} if an action
                        // is inserted before the current wakeup time.
                    }
                }
            }
        }

        public synchronized void schedule(long time, ScheduledAction action) {
            if (logTraceEnabled) {
                log.trace("Adding " + traceTime(time) + " " + action);
            }
            boolean added = false;
            for (int i = 0; i < length; i++) {
                if (actions[i] == null) {
                    actions[i] = action;
                    times[i] = time;
                    added = true;
                }
            }
            if (!added) {
                if (length == MAX_ACTIONS) throw new IllegalStateException("too many actions");
                actions[length] = action;
                times[length++] = time;
            }
            if (time < wakeupTime) {
                wakeupTime = time;
                // interrupt the action thread, unless we are the action thread!
                if (Thread.currentThread() != this) {
                    actionThread.interrupt();
                }
            }
        }

        public synchronized boolean isScheduled(ScheduledAction action) {
            for (int i = 0; i < length; i++) {
                if (actions[i] == action) return true;
            }
            return false;
        }
    }

    private static String traceTime(long time) {
        return String.valueOf(time / Quartz.MSEC);
    }
}
