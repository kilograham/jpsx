/*
Copyright (C) 2007 graham sanderson

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation; either version 2
of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
*/
package org.jpsx.api.components.core.cpu;

/**
 * Connection interface to be implemented by components which care about poll-blocking
 * <p/>
 * Much PSX code sits in busy-wait loops waiting for memory or memory mapped IO locations to change
 * as a result of asynchronous (perhaps interrupt) events.<p>
 * <p/>
 * The core implementation of the emulator detects these busy waits, and will block instead on a java
 * monitor.<p>
 * <p/>
 * This interface enables components to know when this is about to happen
 */
public interface PollBlockListener {
    /**
     * Called when CPU busy-wait has been detected, and the processing thread is about to block.
     * <p/>
     * This is an indication that the CPU is effectively idle, and the component may choose
     * to perform some idle processing.
     */
    public void aboutToBlock();
}
