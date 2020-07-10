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

import org.jpsx.api.CPUListener;
import org.jpsx.api.components.core.scheduler.Quartz;
import org.jpsx.runtime.JPSXComponent;

/**
 * Simple JDK5 nanotime quartz
 */
public class DefaultQuartz extends JPSXComponent implements Quartz, CPUListener {
    private long base;
    private long stoppedAt;

    public void init() {
        super.init();
        base = System.nanoTime();
        cpuPaused();
        CoreComponentConnections.QUARTZ.set(this);
        CoreComponentConnections.CPU_LISTENERS.add(this);
    }

    public DefaultQuartz() {
        super("JPSX System.nanoTime() Quartz");
    }

    public synchronized long nanoTime() {
        if (stoppedAt != 0L) return stoppedAt - base;
        return System.nanoTime() - base;
    }

    public long bestGranularity() {
        // todo we need to verify this on different platforms
        return 1L;
    }

    public long nanoTime(long granularity) {
        // for now
        return nanoTime();
    }

    public synchronized void cpuResumed() {
        if (stoppedAt != 0L) {
            base += System.nanoTime() - stoppedAt;
            stoppedAt = 0L;
        }
    }

    public synchronized void cpuPaused() {
        if (stoppedAt == 0L) {
            stoppedAt = System.nanoTime();
            // would be VERY unlucky!
            if (stoppedAt == 0L) stoppedAt = 1L;
        }
    }
}
