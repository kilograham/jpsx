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
import org.jpsx.api.components.hardware.gpu.Display;
import org.jpsx.api.components.hardware.gpu.DisplayManager;
import org.jpsx.runtime.JPSXComponent;
import org.jpsx.runtime.RuntimeConnections;
import org.jpsx.runtime.util.Timing;
import org.jpsx.runtime.components.hardware.HardwareComponentConnections;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.*;

/**
 * Basic AWT Display implementation. There are other ways to do this, but this seems to work pretty at least as well
 * as any other java2d method on most JDKs.
 */
public class AWTDisplay extends JPSXComponent implements Display, KeyListener {
    private static final Logger log = Logger.getLogger("Display");

    public static final String LOCATION_X_PROPERTY = "x";
    public static final String LOCATION_Y_PROPERTY = "y";

    private Frame frame;

    private int[] ram; // video RAM
    private BufferedImage bufferedImage; // image containing video RAM
    private DisplayManager displayManager;
    private int sourceWidth, sourceHeight; // the size of the source to be stretched to be displayed

    private boolean showBlitTime;
    private boolean antiAlias;

    private boolean displayVRAM;

    private boolean funkyfudge;

    private ImageInfo[] imageInfoCache = new ImageInfo[]{
            new ImageInfo(),
            new ImageInfo(),
            new ImageInfo(),
            new ImageInfo()
    };

    private int cacheReplaceIndex;
    private VolatileImage volatileImage;

    private int MAX_X = 960;
    private int MAX_Y = 512;

    private final int xres[] = new int[]{320, 640, 800, 960, 1024, 1280};
    private final int yres[] = new int[]{256, 512, 600, 768, 768, 1024};

    private static final int BLIT_TIME_COUNT = 100;
    private long blitTimeTotal;
    private int blitTimeCount;
    private boolean noStretch;

    int resindex = 1;

    public AWTDisplay() {
        super("JPSX Default AWT Frame Display");
    }

    @Override
    public void init() {
        super.init();
        HardwareComponentConnections.DISPLAY.set(this);
        RuntimeConnections.KEY_LISTENERS.add(this);
        showBlitTime = Boolean.valueOf(getProperty("showBlitTime","true"));
        antiAlias = Boolean.valueOf(getProperty("antiAlias","true"));
        noStretch = Boolean.valueOf(getProperty("noStretch","false"));
    }

    @Override
    public void resolveConnections() {
        super.resolveConnections();
        displayManager = HardwareComponentConnections.DISPLAY_MANAGER.resolve();
    }

    public void initDisplay() {

        // todo, make a new initialization state for this
        frame = new Frame("JPSX") {
            public void paint(Graphics g) {
                stretchBlit(g);
            }
        };

//        DirectColorModel model = new DirectColorModel( 24, 0x0000ff, 0x00ff00, 0xff0000);
        DirectColorModel model = new DirectColorModel(24, 0xff0000, 0x00ff00, 0x0000ff);
        WritableRaster raster = model.createCompatibleWritableRaster(1024, 513);
        DataBufferInt db = (DataBufferInt) raster.getDataBuffer();
        ram = db.getData();
        bufferedImage = new BufferedImage(model, raster, true, null);
        int w = getIntProperty(LOCATION_X_PROPERTY, -1);
        int h = getIntProperty(LOCATION_Y_PROPERTY, -1);
        if (w != -1 && h != -1) frame.setLocation(w, h);
        frame.show();
        frame.setResizable(false);
        sizeframe();
        volatileImage = frame.createVolatileImage(MAX_X, MAX_Y);
        frame.addKeyListener(RuntimeConnections.KEY_LISTENERS.resolve());
        frame.addWindowListener(new Closer());
    }

    protected class ImageInfo {
        protected int x, y, w, h;
        protected Image image;

        public boolean matches(int x, int y, int w, int h) {
            return (this.x == x && this.y == y && this.w == w && this.h == h);
        }

        public Image update(int x, int y, int w, int h) {
            if (image != null) image.flush();
            image = bufferedImage.getSubimage(x, y, w, h);
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            return image;
        }

        public Image getImage() {
            return image;
        }
    }

//    public void setDisplayManager(DisplayManager manager) {
//        displayManager = manager;
//        refresh();
//    }

    public Image getImage(int x, int y, int w, int h) {
        for (int i = 0; i < imageInfoCache.length; i++) {
            if (imageInfoCache[i].matches(x, y, w, h)) {
                return imageInfoCache[i].getImage();
            }
        }
        cacheReplaceIndex = (cacheReplaceIndex + 1) % imageInfoCache.length;

        // todo fix this - caused presumably by not displaying whole image!
        if ((x + w) > 1024) {
            w = 1024 - x;
        }
        if ((y + h) > 512) {
            h = 512 - y;
        }
        if (log.isDebugEnabled()) {
            log.debug("Creating new image at slot " + cacheReplaceIndex + " " + x + "," + y + " " + w + "," + h);
        }
        return imageInfoCache[cacheReplaceIndex].update(x, y, w, h);
    }


    protected void sizeframe() {
        if (displayVRAM)
            frame.setSize(new Dimension(1024 + frame.getInsets().left + frame.getInsets().right, 512 + frame.getInsets().top + frame.getInsets().bottom));
        else
            frame.setSize(new Dimension(xres[resindex] + frame.getInsets().left + frame.getInsets().right, yres[resindex] + frame.getInsets().top + frame.getInsets().bottom));
    }

    protected void switchsize() {
        resindex++;
        if (resindex == xres.length) resindex = 0;
        sizeframe();
    }

    // the display buffer should be at least 1024*512 + 192 (+192 due to current texture page problem in GPU).
    public int[] acquireDisplayBuffer() {
        return ram;
    }

    public void releaseDisplayBuffer() {
    }

    protected synchronized void stretchBlit(Graphics g) {
        int l = frame.getInsets().left;
        int t = frame.getInsets().top;
        long timeBasis = 0;
        if (showBlitTime) {
            timeBasis = Timing.nanos();
        }
        if (displayVRAM) {
            if (antiAlias) ((Graphics2D)g).setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(bufferedImage, l, t, null);
        } else {
            if (volatileImage != null && !volatileImage.contentsLost()) {
                if (antiAlias) ((Graphics2D)g).setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                if (noStretch) {
                    g.drawImage(volatileImage, l, t, l + sourceWidth, t + sourceHeight,
                            0, 0, sourceWidth, sourceHeight, null);
                } else {
                    g.drawImage(volatileImage, l, t, l + xres[resindex], t + yres[resindex],
                            0, 0, sourceWidth, sourceHeight, null);
                }
            }
        }
        if (showBlitTime) {
            blitTimeTotal += Timing.nanos()-timeBasis;
            if (BLIT_TIME_COUNT == ++blitTimeCount) {
                double blitTimeMSRounded = (blitTimeTotal/(BLIT_TIME_COUNT*100000))/10.0;
                frame.setTitle("JPSX awt - " + ((blitTimeMSRounded >= 1) ? "!SLOW! blit=" : "blit=") + blitTimeMSRounded + "ms");
                blitTimeCount = 0;
                blitTimeTotal = 0;
            }
        }
    }

    public synchronized void refresh() {
        boolean rgb24 = displayManager.getRGB24();
        if (funkyfudge) {
            GPU.setVRAMFormat(!rgb24);
            funkyfudge = false;
        }
        GPU.setVRAMFormat(rgb24);
        // for now we use default display size
        sourceWidth = displayManager.getDefaultPixelWidth();
        if (rgb24) sourceWidth = (sourceWidth * 3) / 2;
        sourceHeight = displayManager.getDefaultPixelHeight();
        if (volatileImage.contentsLost()) {
            volatileImage.validate(null);
        }
        if (displayManager.getBlanked()) {
            Graphics2D g2 = volatileImage.createGraphics();
            try {
                g2.setColor(Color.BLACK);
                g2.fillRect(0, 0, sourceWidth, sourceHeight);
            } finally {
                g2.dispose();
            }
        } else {
            Graphics2D g2 = volatileImage.createGraphics();
            int marginLeft = displayManager.getLeftMarginPixels();
            if (rgb24) marginLeft = (marginLeft * 3) / 2;
            int marginTop = displayManager.getTopMarginPixels();
            int pixelWidth = displayManager.getPixelWidth();
            if (rgb24) pixelWidth = (pixelWidth * 3) / 2;
            int pixelHeight = displayManager.getPixelHeight();
            int marginRight = sourceWidth - pixelWidth - marginLeft;
            int marginBottom = sourceHeight - pixelHeight - marginTop;
            try {
                if (pixelWidth > 0 && pixelHeight > 0) {
                    g2.drawImage(getImage(displayManager.getXOrigin(), displayManager.getYOrigin(), pixelWidth, pixelHeight),
                            marginLeft, marginTop, null);
                }
                g2.setColor(Color.BLACK);
                if (marginLeft > 0) {
                    // fill left
                    g2.fillRect(0, 0, marginLeft, sourceHeight);
                }
                if (marginRight > 0) {
                    // fill left
                    g2.fillRect(sourceWidth - marginRight, 0, marginRight, sourceHeight);
                }
                if (marginTop > 0) {
                    // fill top
                    g2.fillRect(0, 0, sourceWidth, marginTop);
                }
                if (marginBottom > 0) {
                    // fill top
                    g2.fillRect(0, sourceHeight - marginBottom, sourceWidth, marginBottom);
                }
            } finally {
                g2.dispose();
            }
        }
        Graphics graphics = frame.getGraphics();
        if (graphics != null) {
            try {
                stretchBlit(graphics);
            } finally {
                graphics.dispose();
            }
        }
    }

    public void keyTyped(KeyEvent e) {
    }

    public void keyPressed(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_F12) {
            switchsize();
        }
        if (e.getKeyCode() == KeyEvent.VK_F11) {
            refresh();
        }
        if (e.getKeyCode() == KeyEvent.VK_F10) {
            funkyfudge = true;
        }
        if (e.getKeyCode() == KeyEvent.VK_F9) {
            displayVRAM = !displayVRAM;
            sizeframe();
            refresh();
        }
        if (e.getKeyCode() == KeyEvent.VK_F8) {
            for (int i = 0; i < 1024 * 512; i++) {
                ram[i] = ram[i] & 0x01ffffff;
            }
        }
        if (e.getKeyCode() == KeyEvent.VK_F7) {
            for (int i = 0; i < 1024 * 512; i++) {
                ram[i] = ram[i] | 0xfe000000;
            }
        }
    }

    public void keyReleased(KeyEvent e) {
    }

    private static class Closer extends WindowAdapter {
        public void windowClosing(WindowEvent e) {
            RuntimeConnections.MACHINE.resolve().close();
        }
    }
}
