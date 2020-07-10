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
package org.jpsx.api.components.hardware.gpu;

public interface DisplayManager {
    boolean getInterlaceField();

    boolean getNTSC();

    boolean getInterlaced();

    boolean getDoubleY();

    boolean getRGB24();

    boolean getBlanked();

    int getDefaultPixelWidth();

    int getDefaultPixelHeight();

    int getPixelWidth();

    int getPixelHeight();

    int getXOrigin();

    int getYOrigin();

    int getLeftMarginPixels();

    int getRightMarginPixels();

    int getTopMarginPixels();

    int getBottomMarginPixels();

    void preAsync();

    // called if a vsync has passed to make sure display is updated if necessary
    // (e.g. drawing to display)... usually this does nothing, because setOrigin
    // updates display if necessary
    void vsync();

    void setOrigin(int x, int y);

    void toggleInterlaceField();// mark a display area as changed... if it intersects the next

    // display region, then we will get an update at the next vsync, or
    // display origin change
    void dirtyRectangle(int x, int y, int w, int h);

    void setPixelDivider(int divider);

    void setNTSC(boolean NTSC);

    void setInterlaced(boolean interlaced);

    void setDoubleY(boolean doubleY);

    void setRGB24(boolean rgb24);

    void setBlanked(boolean blanked);

    void setHorizontalTiming(int hStart, int hEnd);

    void setVerticalTiming(int vStart, int vEnd);

    int getDefaultTimingWidth();

    int getDefaultTimingHeight();

    int getLeftMargin();

    int getRightMargin();

    int getTopMargin();

    int getBottomMargin();

}
