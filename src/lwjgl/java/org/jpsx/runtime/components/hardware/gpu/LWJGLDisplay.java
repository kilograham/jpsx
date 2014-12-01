/*
 * Copyright (C) 2007, 2014 Graham Sanderson
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
import org.jpsx.api.InvalidConfigurationException;
import org.jpsx.runtime.JPSXComponent;
import org.jpsx.runtime.RuntimeConnections;
import org.jpsx.runtime.util.Timing;
import org.jpsx.runtime.components.hardware.HardwareComponentConnections;
import org.lwjgl.opengl.AWTGLCanvas;
import org.lwjgl.opengl.PixelFormat;
import org.lwjgl.opengl.GL11;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import org.lwjgl.LWJGLException;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.image.*;
import java.nio.IntBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class LWJGLDisplay extends JPSXComponent implements Display, KeyListener {
    private static final Logger log = Logger.getLogger("Display");

    public static final String LOCATION_X_PROPERTY = "x";
    public static final String LOCATION_Y_PROPERTY = "y";

    private Frame frame;

    // todo see if this is slower - we can fix GPU instead!
    private static final int SOURCE_FORMAT = GL_BGRA;
    /**
     * Used to transfer pixel data to open GL texture
     */
    private ByteBuffer transferBuffer;
    /**
     * Used to copy int[] pixel data into the transfer buffer
     */
    private IntBuffer transferBufferIntView;

    private boolean blanked = true;

    private int[] ram; // video RAM
    private DisplayManager displayManager;

    /**
     * protected data
     */
    private int sourceWidth, sourceHeight; // the size of the source to be stretched to be displayed

    private boolean showBlitTime;
    private boolean antiAlias;

    private boolean displayVRAM;

    private boolean funkyfudge;

    private int MAX_X = 960;
    private int MAX_Y = 512;

    private final int xres[] = new int[]{320, 640, 800, 960, 1024, 1280};
    private final int yres[] = new int[]{256, 512, 600, 768, 768, 1024};

    private static final int BLIT_TIME_COUNT = 100;
    private long blitTimeTotal;
    private int blitTimeCount;
    private long refreshTimeTotal;
    private int refreshTimeCount;
//    private boolean noStretch;
    private int textureHandle;

    private AWTGLCanvas canvas;
    int resindex = 1;


    public LWJGLDisplay() {
        super("JPSX LWJGL AWT Display");
    }

    @Override
    public void init() {
        super.init();
        HardwareComponentConnections.DISPLAY.set(this);
        RuntimeConnections.KEY_LISTENERS.add(this);
        transferBuffer = ByteBuffer.allocateDirect(4*1024*512+1); // todo check + 1, we may need to be one off for BGRA, but we should
                                                                // just force the GPU to render in the correct format
        // don't want any conversions going on
        transferBuffer.order(ByteOrder.nativeOrder());
        transferBufferIntView = transferBuffer.asIntBuffer();

        showBlitTime = Boolean.valueOf(getProperty("showBlitTime","true"));
        antiAlias = Boolean.valueOf(getProperty("antiAlias","true"));
//        noStretch = Boolean.valueOf(getProperty("noStretch","false"));
    }

    @Override
    public void resolveConnections() {
        super.resolveConnections();
        displayManager = HardwareComponentConnections.DISPLAY_MANAGER.resolve();
    }

    public static IntBuffer allocInts(int howmany) {
        return ByteBuffer.allocateDirect(howmany * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
    }

    public void initDisplay() {

        // todo, make a new initialization state for this
        frame = new Frame("JPSX");

        try {
            canvas = new AWTGLCanvas(new PixelFormat()) {
                protected void initGL() {
                    IntBuffer textureHandleB = allocInts(1);
                    GL11.glGenTextures(textureHandleB);
                    textureHandle = textureHandleB.get(0);
                    // 'select' the new texture by it's handle
                    GL11.glDisable(GL_LIGHTING);
                    GL11.glDisable(GL_DEPTH_TEST);
                    GL11.glEnable(GL_TEXTURE_2D);
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureHandle);
                    // set texture parameters
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, antiAlias?GL11.GL_LINEAR:GL11.GL_NEAREST); //GL11.GL_NEAREST);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST); //GL11.GL_NEAREST);

                    // Create the texture from pixels
                    GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, 1024, 512, 0, SOURCE_FORMAT, GL11.GL_UNSIGNED_BYTE, transferBuffer);
                }

                protected void paintGL() {
                    stretchBlit();
                    try {
                        swapBuffers();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

            };

            frame.setLayout(new LayoutManager() {
                public void addLayoutComponent(String name, Component comp) {
                }

                public void removeLayoutComponent(Component comp) {
                }

                public Dimension preferredLayoutSize(Container parent) {
                    Insets insets = parent.getInsets();
                    Dimension dim = getContentSize();
                    dim.width += insets.left + insets.right;
                    dim.height += insets.top + insets.bottom;
                    return dim;
                }

                public Dimension minimumLayoutSize(Container parent) {
                    return preferredLayoutSize(parent);
                }

                public void layoutContainer(Container parent) {
                    Dimension dim = getContentSize();
                    Insets insets = parent.getInsets();
                    for(Component component : parent.getComponents()) {
                        component.setBounds(insets.left, insets.top, dim.width, dim.height);
                    }
                }
            });
            frame.add(canvas);
            canvas.repaint();
        } catch (LWJGLException e) {
            throw new InvalidConfigurationException("Failed to initialize lwjgl", e);
        }
//        DirectColorModel model = new DirectColorModel( 24, 0x0000ff, 0x00ff00, 0xff0000);
        DirectColorModel model = new DirectColorModel(24, 0xff0000, 0x00ff00, 0x0000ff);
        WritableRaster raster = model.createCompatibleWritableRaster(1024, 513);
        DataBufferInt db = (DataBufferInt) raster.getDataBuffer();
        ram = db.getData();
        int w = getIntProperty(LOCATION_X_PROPERTY, -1);
        int h = getIntProperty(LOCATION_Y_PROPERTY, -1);
        if (w != -1 && h != -1) frame.setLocation(w, h);
        frame.setResizable(false);
        frame.show();
        sizeframe();
        canvas.addKeyListener(RuntimeConnections.KEY_LISTENERS.resolve());
        frame.addWindowListener(new Closer());
        frame.addComponentListener(new ComponentAdapter() {
            public void componentResized(ComponentEvent e) {
                frame.doLayout();
            }
        });
    }

    protected void sizeframe() {
        if (displayVRAM)
            frame.setSize(new Dimension(1024 + frame.getInsets().left + frame.getInsets().right, 512 + frame.getInsets().top + frame.getInsets().bottom));
        else
            frame.setSize(new Dimension(xres[resindex] + frame.getInsets().left + frame.getInsets().right, yres[resindex] + frame.getInsets().top + frame.getInsets().bottom));
    }

    // todo cache these
    protected Dimension getContentSize() {
        if (displayVRAM)
            return new Dimension(1024, 512);
        else
            return new Dimension(xres[resindex], yres[resindex]);
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

    /**
     * needs to protect drawing state
     */
    private Object stateLock = new Object();

    protected void stretchBlit() {
        int l = frame.getInsets().left;
        int t = frame.getInsets().top;
        long timeBasis = 0;
        if (showBlitTime) {
            timeBasis = Timing.nanos();
        }
        // todo don't recreate
        Dimension dim = getContentSize();
        GL11.glViewport(0,0,dim.width, dim.height);
        GL11.glMatrixMode(GL_PROJECTION);
        GL11.glLoadIdentity();
        GL11.glOrtho(0,dim.width,0,dim.height,-1,1);
        GL11.glMatrixMode(GL_MODELVIEW);
        GL11.glLoadIdentity();
        // todo sync on that too
        if (displayVRAM) {
            synchronized (stateLock) {
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, 1024, 512, 0, SOURCE_FORMAT, GL11.GL_UNSIGNED_BYTE, transferBuffer);
            }
            GL11.glBegin(GL_QUADS);
            GL11.glTexCoord2f(0f,1f);
            GL11.glVertex2f(0f,0f);
            GL11.glTexCoord2f(1f,1f);
            GL11.glVertex2f(1024f,0f);
            GL11.glTexCoord2f(1f,0f);
            GL11.glVertex2f(1024f,512f);
            GL11.glTexCoord2f(0f,0f);
            GL11.glVertex2f(0f,512f);
            GL11.glEnd();
        } else {
            if (blanked) {
                GL11.glClear(GL_COLOR_BUFFER_BIT);
            } else {
                float tw, th;
                synchronized (stateLock) {
                    GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, sourceWidth, sourceHeight, SOURCE_FORMAT, GL11.GL_UNSIGNED_BYTE, transferBuffer);
                    // todo coords are wrong because of blanking - refactor along with other stuff
                    tw = sourceWidth/1024f;
                    th = sourceHeight/512f;
                }
                GL11.glBegin(GL_QUADS);
                GL11.glTexCoord2f(0f,0f);
                GL11.glVertex2f(0f,0f);
                GL11.glTexCoord2f(tw,0f);
                GL11.glVertex2f(dim.width,0f);
                GL11.glTexCoord2f(tw,th);
                GL11.glVertex2f(dim.width,dim.height);
                GL11.glTexCoord2f(0f,th);
                GL11.glVertex2f(0f,dim.height);
                GL11.glEnd();
//                    g.drawImage(volatileImage, l, t, l + xres[resindex], t + yres[resindex],
//                            0, 0, sourceWidth, sourceHeight, null);

            }
//            if (volatileImage != null && !volatileImage.contentsLost()) {
//                if (antiAlias) ((Graphics2D)g).setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
//                if (noStretch) {
//                    g.drawImage(volatileImage, l, t, l + sourceWidth, t + sourceHeight,
//                            0, 0, sourceWidth, sourceHeight, null);
//                } else {
//                    g.drawImage(volatileImage, l, t, l + xres[resindex], t + yres[resindex],
//                            0, 0, sourceWidth, sourceHeight, null);
//                }
//            }
        }
        if (showBlitTime) {
            blitTimeTotal += Timing.nanos()-timeBasis;
            if (BLIT_TIME_COUNT == ++blitTimeCount) {
                String refreshString = "";
                if (refreshTimeCount!=0) {
                    refreshString = " refresh="+((refreshTimeTotal/(refreshTimeCount*100000))/10.0)+"ms";
                    refreshTimeTotal = refreshTimeCount = 0;
                }
                double blitTimeMSRounded = (blitTimeTotal/(BLIT_TIME_COUNT*100000))/10.0;
                frame.setTitle("JPSX lwjgl - " + ((blitTimeMSRounded >= 1) ? "!SLOW! blit=" : "blit=") + blitTimeMSRounded + "ms" + refreshString);
                blitTimeCount = 0;
                blitTimeTotal = 0;
            }
        }
    }

    // todo fix sync
    public void refresh() {
        boolean rgb24 = displayManager.getRGB24();
        if (funkyfudge) {
            GPU.setVRAMFormat(!rgb24);
            funkyfudge = false;
        }
        GPU.setVRAMFormat(rgb24);
        // for now we use default display size
        long timeBasis = 0;
        if (showBlitTime) {
            timeBasis = Timing.nanos();
        }
        synchronized (stateLock) {
            if (displayManager.getBlanked()) {
                blanked = true;
            } else {
                blanked = false;
                if (displayVRAM) {
                    // copy all of vram
                    transferBufferIntView.clear();
                    transferBufferIntView.put(ram, 0, 1024*512);
                    transferBufferIntView.flip();
                } else {
                    sourceWidth = displayManager.getDefaultPixelWidth();
                    if (rgb24) sourceWidth = (sourceWidth * 3) / 2;
                    sourceHeight = displayManager.getDefaultPixelHeight();
                    int marginLeft = displayManager.getLeftMarginPixels();
                    if (rgb24) marginLeft = (marginLeft * 3) / 2;
                    int marginTop = displayManager.getTopMarginPixels();
                    int pixelWidth = displayManager.getPixelWidth();
                    if (rgb24) pixelWidth = (pixelWidth * 3) / 2;
                    int pixelHeight = displayManager.getPixelHeight();
                    int marginRight = sourceWidth - pixelWidth - marginLeft;
                    int marginBottom = sourceHeight - pixelHeight - marginTop;
                    if (pixelWidth > 0 && pixelHeight > 0) {
                        transferBufferIntView.clear();
                        // from left-right and bottom to top, we copy the pixels into our transfer buffer
                        int offset = displayManager.getXOrigin() + (displayManager.getYOrigin() + pixelHeight - 1) * 1024;
                        for(int i=0; i<pixelHeight; i++) {
                            if (offset<1024*512*4) {
                                transferBufferIntView.put(ram, offset, pixelWidth);
                            }
                            offset -= 1024;
                        }
                        transferBufferIntView.flip();
//                        g2.drawImage(getImage(displayManager.getXOrigin(), displayManager.getYOrigin(), pixelWidth, pixelHeight),
//                                marginLeft, marginTop, null);
                    }
//                    g2.setColor(Color.BLACK);
//                    if (marginLeft > 0) {
//                        // fill left
//                        g2.fillRect(0, 0, marginLeft, sourceHeight);
//                    }
//                    if (marginRight > 0) {
//                        // fill left
//                        g2.fillRect(sourceWidth - marginRight, 0, marginRight, sourceHeight);
//                    }
//                    if (marginTop > 0) {
//                        // fill top
//                        g2.fillRect(0, 0, sourceWidth, marginTop);
//                    }
//                    if (marginBottom > 0) {
//                        // fill top
//                        g2.fillRect(0, sourceHeight - marginBottom, sourceWidth, marginBottom);
//                    }
                }
            }
        }
        if (showBlitTime) {
            refreshTimeTotal += Timing.nanos()-timeBasis;
            refreshTimeCount++;
        }
        canvas.repaint();
        //stretchBlit(graphics);
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
