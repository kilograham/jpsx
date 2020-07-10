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
 * A Quartz implementation is the basic internal timer in the JPSX system.
 * <p/>
 * Different implementations of {@link Quartz} may provide different granularities. Various
 * components may require a certain level of granularity.
 * <p/>
 * This interface contains the methods not only to determine the current time value, but for components
 * to negociate granularity, or indeed fail if the {@link Quartz} implementation does not provide
 * the required level of granularity
 */
public interface Quartz {
    /**
     * The number of nano-seconds in a second
     */
    public static final long SEC = 1000000000L;
    /**
     * The number of nano-seconds in a millisecond
     */
    public static final long MSEC = 1000000L;

    /**
     * Return the current value a nano counter which counts up from 0 when the machine starts (but only while the machine is not paused)
     * <p/>
     * The resolution of the nano time is undefined but should be accurate +/- the value from {@link #bestGranularity()}
     */
    long nanoTime();

    /**
     * @return the estimated granularity of the time changes visible via {@link #nanoTime()}
     */
    long bestGranularity();

    /**
     * Return the current value an arbitrary nano counter which counts up from 0 when the machine starts (but only while the machine is not paused)
     * <p/>
     * The resolution of the nano time should be accurate +/- the specified granularity value.
     * <p/>
     * The passed granularity should be greater than the value returned from {@link #bestGranularity()}
     * <p/>
     * This method is provided for use by callers who don't need insane accuracy in the hope that the implementation
     * may be able to perform a less costly time calculation
     *
     * @param granularity the requested granularity
     * @throw IllegalArgumentException if the granularity is more fine than that returned by {@link #bestGranularity}
     */
    long nanoTime(long granularity);
}
