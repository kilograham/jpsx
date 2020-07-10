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
 * A scheduled action is registered with the {@link Scheduler} to be called back at a certain time.
 * <p/>
 * <b>All</b> implementors must be thread-safe since they may be called from any thread, and potentially simultaneously
 * if the action is registered multiple times.
 */
public interface ScheduledAction {
    /**
     * @param currentTime
     * @return the next time the action should be scheduled or 0 for no automatic re-scheduling
     */
    long run(long currentTime);
}
