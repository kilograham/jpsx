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
package org.jpsx.api.components.hardware.gpu;

public interface Display {
    /**
     * initialize the display
     */
    public void initDisplay();

    // the display buffer should be at least 1024*512 + 192 (+192 due to current texture page problem in GPU);
    public int[] acquireDisplayBuffer();

    public void releaseDisplayBuffer();

    public void refresh();
}



