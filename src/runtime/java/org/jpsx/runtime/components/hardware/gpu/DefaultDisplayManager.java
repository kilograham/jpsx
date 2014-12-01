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
package org.jpsx.runtime.components.hardware.gpu;

import org.apache.log4j.Logger;
import org.jpsx.api.components.core.scheduler.Quartz;
import org.jpsx.api.components.hardware.gpu.Display;
import org.jpsx.api.components.hardware.gpu.DisplayManager;
import org.jpsx.runtime.JPSXComponent;
import org.jpsx.runtime.components.core.CoreComponentConnections;
import org.jpsx.runtime.components.hardware.HardwareComponentConnections;

// todo change dirty code to only update every now and then...

// todo this should be a component
public class DefaultDisplayManager extends JPSXComponent implements DisplayManager {
    private static final Logger log = Logger.getLogger("Display");
    private long lastRefreshTime = 0;
    private boolean hadDirty = false;
    private static final long DIRTY_REFRESH_PERIOD = 100 * Quartz.MSEC;
    private static final long AUTO_REFRESH_PERIOD = 200 * Quartz.MSEC;

    private Quartz quartz;
    private Display display;

    public DefaultDisplayManager() {
        super("JPSX Default GPU Display Manager");
    }


    public void init() {
        super.init();
        HardwareComponentConnections.DISPLAY_MANAGER.set(this);
    }

    @Override
    public void resolveConnections() {
        super.resolveConnections();
        display = HardwareComponentConnections.DISPLAY.resolve();
        quartz = CoreComponentConnections.QUARTZ.resolve();
    }

    private static boolean intersects(int x0, int y0, int w0, int h0, int x1, int y1, int w1, int h1) {
        if (w0 == 0 || h0 == 0 || w1 == 0 || h1 == 0) return false;
        w0 += x0;
        w1 += x1;
        h0 += y0;
        h1 += y1;
        return (w0 > x1) && (h0 > y1) && (w1 > x0) && (h1 > y0);
    }

    private static class State {
        public int hStart;
        public int hEnd;
        public int vStart;
        public int vEnd;
        public boolean blanked;
        public boolean NTSC;
        public boolean interlaced;
        public boolean doubleY;
        public boolean rgb24;
        public int pixelDivider = 8;

        public int xOrigin;
        public int yOrigin;

        public int x0Dirty;
        public int y0Dirty;
        public int x1Dirty;
        public int y1Dirty;

        protected int getDefaultTimingLeft() {
            return NTSC ? 608 : 628;
        }

        protected int getDefaultTimingTop() {
            return NTSC ? 16 : 40;
        }

        protected int getDefaultTimingWidth() {
            return 2560;
        }

        protected int getDefaultTimingHeight() {
            return NTSC ? 240 : 256;
        }

        public int getDefaultPixelWidth() {
            return getDefaultTimingWidth() / pixelDivider;
        }

        public int getDefaultPixelHeight() {
            int rc = getDefaultTimingHeight();
            if (doubleY) {
                rc <<= 1;
            }
            return rc;
        }

        public int getPixelWidth() {
            return (hEnd - hStart) / pixelDivider;
        }

        public int getPixelHeight() {
            int rc = vEnd - vStart;
            if (doubleY) {
                rc <<= 1;
            }
            return rc;
        }

        public int getLeftMargin() {
            return hStart - getDefaultTimingLeft();
        }

        public int getLeftMarginPixels() {
            return getLeftMargin() / pixelDivider;
        }

        public int getRightMargin() {
            return getDefaultTimingLeft() + getDefaultTimingWidth() - hEnd;
        }

        public int getRightMarginPixels() {
            return getRightMargin() / pixelDivider;
        }

        public int getTopMargin() {
            return vStart - getDefaultTimingTop();
        }

        public int getTopMarginPixels() {
            int rc = getTopMargin();

            if (doubleY) {
                rc <<= 1;
            }
            return rc;
        }

        public int getBottomMargin() {
            return getDefaultTimingTop() + getDefaultTimingHeight() - vEnd;
        }

        public int getBottomMarginPixels() {
            int rc = getBottomMargin();

            if (doubleY) {
                rc <<= 1;
            }
            return rc;
        }

        public boolean isDirty() {
            if (x0Dirty == x1Dirty) return false;
            //System.out.println("CHECKING DIRTY: disp "+xOrigin+","+yOrigin+" x "+getPixelWidth()+","+getPixelHeight()+"\n"+
            //                   "dirty "+x0Dirty+","+y0Dirty+" by "+(x1Dirty-x0Dirty)+","+(y1Dirty-y0Dirty));
            boolean rc = intersects(xOrigin, yOrigin, getPixelWidth(), getPixelHeight(),
                    x0Dirty, y0Dirty, x1Dirty - x0Dirty, y1Dirty - y0Dirty);
            //if (rc) System.out.println("  INTERSECT DIRTY!");
            return rc;
        }

        public void dirtyRectangle(int x, int y, int w, int h) {
            int x1 = x + w;
            int y1 = y + h;
            if (x0Dirty == x1Dirty) {
                x0Dirty = x;
                y0Dirty = y;
                x1Dirty = x1;
                y1Dirty = y1;
            } else {
                if (x < x0Dirty) x0Dirty = x;
                if (x1 > x1Dirty) x1Dirty = x1;
                if (y < y0Dirty) y0Dirty = y;
                if (y1 > y1Dirty) y1Dirty = y1;
            }
//            System.out.println("SET DIRTY: disp "+xOrigin+","+yOrigin+" x "+getPixelWidth()+","+getPixelHeight()+"\n"+
//                               "dirty "+x0Dirty+","+y0Dirty+" by "+(x1Dirty-x0Dirty)+","+(y1Dirty-y0Dirty));
        }

        public void reset(State base) {
            hStart = base.hStart;
            hEnd = base.hEnd;
            vStart = base.vStart;
            vEnd = base.vEnd;
            xOrigin = base.xOrigin;
            yOrigin = base.yOrigin;
            blanked = base.blanked;
            NTSC = base.NTSC;
            interlaced = base.interlaced;
            rgb24 = base.rgb24;
            pixelDivider = base.pixelDivider;
            doubleY = base.doubleY;

            x0Dirty = x1Dirty; // empty dirty region
        }

        public boolean matches(State other) {
            return xOrigin == other.xOrigin &&
                    yOrigin == other.yOrigin &&
                    hStart == other.hStart &&
                    hEnd == other.hEnd &&
                    vStart == other.vStart &&
                    vEnd == other.vEnd &&
                    blanked == other.blanked &&
                    NTSC == other.NTSC &&
                    interlaced == other.interlaced &&
                    doubleY == other.doubleY &&
                    pixelDivider == other.pixelDivider &&
                    rgb24 == other.rgb24;
        }

        public String toString() {
            String rc = getDefaultPixelWidth() + "x" + getDefaultPixelHeight() + "x" + (rgb24 ? "24" : "15");
            rc += " displayed: " + getPixelWidth() + "x" + getPixelHeight() + "x" + (rgb24 ? "24" : "15");
            rc += " offset: " + hStart + "," + vStart;
            if (blanked) {
                rc += " blanked";
            }
            rc += NTSC ? " (NTSC)" : " (PAL)";
            rc += " origin " + xOrigin + "," + yOrigin;
            return rc;
        }
    }

    protected State states[] = new State[]{new State(), new State()};
    protected int currentState = 0;
    protected boolean interlaceField;
    protected boolean forceUpdate;

    public void preAsync() {
        updateState();
    }

    // called if a vsync has passed to make sure display is updated if necessary
    // (e.g. drawing to display)... usually this does nothing, because setOrigin
    // updates display if necessary
    public void vsync() {
        updateState();
    }

    public void setOrigin(int x, int y) {
        // setting the origin takes effect immediately; we take
        // any pending display changes
        State s = getNextState();
        s.xOrigin = x;
        s.yOrigin = y;
        updateState();
    }

    protected State getState() {
        return states[currentState];
    }

    protected State getNextState() {
        return states[currentState ^ 1];
    }

    protected void updateState() {
        currentState = currentState ^ 1;
        State s = getState();
        boolean refresh = (forceUpdate || !states[0].matches(states[1]));
        if (!refresh && s.isDirty()) {
            hadDirty = true;
        }
        long time = quartz.nanoTime();
        if (!refresh && hadDirty && DIRTY_REFRESH_PERIOD < (time - lastRefreshTime)) {
            if (log.isDebugEnabled()) {
                log.debug("UPDATING DISPLAY BECAUSE DIRTY");
            }
            refresh = true;
            hadDirty = false;
        }
        if (!refresh && AUTO_REFRESH_PERIOD < (time - lastRefreshTime)) {
            if (log.isDebugEnabled()) {
                log.debug("UPDATING DISPLAY BECAUSE HAVEN'T HAD ONE");
            }
            refresh = true;
        }
        if (refresh) {
            lastRefreshTime = time;
            //System.out.println("UPDATING DISPLAY: "+s.toString());
            forceUpdate = false;
            display.refresh();
        } else {
//            System.out.println("NOT UPDATING DISPLAY: "+s.toString());
        }
        // next state should configure off as we are
        getNextState().reset(s);
    }

    public void toggleInterlaceField() {
        interlaceField = !interlaceField;
        forceUpdate = true;
    }

    public boolean getInterlaceField() {
        return interlaceField;
    }

    // mark a display area as changed... if it intersects the next
    // display region, then we will get an update at the next vsync, or
    // display origin change
    public void dirtyRectangle(int x, int y, int w, int h) {
        // we only care about the next display state...
        getNextState().dirtyRectangle(x, y, w, h);
    }

    public void setPixelDivider(int divider) {
        getNextState().pixelDivider = divider;
    }

    public void setNTSC(boolean NTSC) {
        getNextState().NTSC = NTSC;
    }

    public boolean getNTSC() {
        return getState().NTSC;
    }

    public void setInterlaced(boolean interlaced) {
        getNextState().interlaced = interlaced;
    }

    public boolean getInterlaced() {
        return getState().interlaced;
    }

    public void setDoubleY(boolean doubleY) {
        getNextState().doubleY = doubleY;
    }

    public boolean getDoubleY() {
        return getState().doubleY;
    }

    public void setRGB24(boolean rgb24) {
        getNextState().rgb24 = rgb24;
    }

    public boolean getRGB24() {
        return getState().rgb24;
    }

    public void setBlanked(boolean blanked) {
        getNextState().blanked = blanked;
    }

    public boolean getBlanked() {
        return getState().blanked;
    }

    public void setHorizontalTiming(int hStart, int hEnd) {
        State s = getNextState();
        s.hStart = hStart;
        s.hEnd = hEnd;
    }

    public void setVerticalTiming(int vStart, int vEnd) {
        State s = getNextState();
        s.vStart = vStart;
        s.vEnd = vEnd;
    }

    public int getDefaultTimingWidth() {
        return getState().getDefaultTimingWidth();
    }

    public int getDefaultTimingHeight() {
        return getState().getDefaultTimingHeight();
    }

    public int getDefaultPixelWidth() {
        return getState().getDefaultPixelWidth();
    }

    public int getDefaultPixelHeight() {
        return getState().getDefaultPixelHeight();
    }

    public int getPixelWidth() {
        return getState().getPixelWidth();
    }

    public int getPixelHeight() {
        return getState().getPixelHeight();
    }

    public int getXOrigin() {
        return getState().xOrigin;
    }

    public int getYOrigin() {
        return getState().yOrigin;
    }

    public int getLeftMargin() {
        return getState().getLeftMargin();
    }

    public int getLeftMarginPixels() {
        return getState().getLeftMarginPixels();
    }

    public int getRightMargin() {
        return getState().getRightMargin();
    }

    public int getRightMarginPixels() {
        return getState().getRightMarginPixels();
    }

    public int getTopMargin() {
        return getState().getTopMargin();
    }

    public int getTopMarginPixels() {
        return getState().getTopMarginPixels();
    }

    public int getBottomMargin() {
        return getState().getBottomMargin();
    }

    public int getBottomMarginPixels() {
        return getState().getBottomMarginPixels();
    }
}
