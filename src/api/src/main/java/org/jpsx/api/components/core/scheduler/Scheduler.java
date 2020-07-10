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
package org.jpsx.api.components.core.scheduler;

/**
 * The scheduler is responsible for all real-time activity within the emulator.
 * <p/>
 * The actual implementation is also largely responsible for the threading mechanism used.
 */
public interface Scheduler {
    /**
     * Schedule an action to happen as soon as possible after the given time. For this method the definition of
     * "as soon as possible" is left to the scheduler, but in general most implementations will probably not try
     * to schedule with greater than 1ms accuracy.<p>
     * <p/>
     * No actual guarantees are made about how soon the action will be called, so periodic actions that would
     * usually re-schedule themselves every X nanoseconds, may want to take corrective action if they detect
     * that they have been called back mutch later than expected.<p>
     * <p/>
     * Actions scheduled in this manner should be as concise as possible, as all actions may be performed on
     * one thread, and indeed that thread may be (but won't necessarily be) the R3000 cpu thread.<p>
     * <p/>
     * If an action has long lasting work that it needs to do, it should delegate it to other worker threads
     *
     * @param time   the time in nanoseconds since the start of the machine
     * @param action the action to perform
     */
    void schedule(long time, ScheduledAction action);

    /**
     * Schedule an action to happen as soon as possible after the given time. For this method the definition of
     * "as soon as possible" is specified to mean try your very best to get to schedule the action to happen between time
     * and time+jitter. This method is useful if you really require <i>very</i> high frequency callbacks.<p>
     * <p/>
     * No actual guarantees are made about how soon the action will be called, so periodic actions that would
     * usually re-schedule themselves every X nanoseconds, may want to take corrective action if they detect
     * that they have been called back mutch later than expected.<p>
     * <p/>
     * Actions scheduled in this manner should be as concise as possible, as all actions may be performed on
     * one thread, and indeed that thread may be (but won't necessarily be) the R3000 cpu thread.<p>
     * <p/>
     * If an action has long lasting work that it needs to do, it should delegate it to other worker threads
     *
     * @param time   the time in nanoseconds since the start of the machine
     * @param action the action to perform
     */
    void schedule(long time, long jitter, ScheduledAction action);

    /**
     * Checks whether the action is currently scheduled.
     * <p/>
     * In this case scheduled means is either scheduled one or more times in the future, or is currently being called
     * back. This latter behavior is useful if you have a repeating action which is only used some of the time.
     * External (to the action) code can safely use this method to determine whether it should schedule the action itself
     *
     * @param action
     * @return
     */
    boolean isScheduled(ScheduledAction action);

    /**
     * This method may be called by the cpu thread to indicate that it is in a busy-wait loop, and has no further useful
     * work to do in the absence of some external force.
     * <p/>
     * This method will not return until {@link #cpuThreadNotify} is called.
     * <p/>
     * Note this is not necessarily implemented via Java monitors since the particular scheduler may be single-threaded
     */
    void cpuThreadWait();

    /**
     * This method is generally called by {@link ScheduledAction scheduled actions} to indicate that they have performed
     * some work which may potentially un-block the R3000 cpu thread.
     * <p/>
     * Note this is not necessarily implemented via Java monitors since the particular scheduler may be single-threaded
     */
    void cpuThreadNotify();
}
