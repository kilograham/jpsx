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

import org.apache.bcel.classfile.ClassParser;
import org.apache.bcel.classfile.ConstantUtf8;
import org.apache.bcel.classfile.Field;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.generic.ClassGen;
import org.apache.bcel.generic.ConstantPoolGen;
import org.apache.bcel.generic.FieldGen;
import org.jpsx.api.components.core.addressspace.AddressSpace;
import org.jpsx.api.components.core.addressspace.AddressSpaceRegistrar;
import org.jpsx.api.components.core.addressspace.MemoryMapped;
import org.jpsx.api.components.core.addressspace.Pollable;
import org.jpsx.api.components.core.cpu.PollBlockListener;
import org.jpsx.api.components.core.dma.DMAController;
import org.jpsx.api.components.hardware.gpu.Display;
import org.jpsx.api.components.hardware.gpu.DisplayManager;
import org.jpsx.bootstrap.classloader.ClassGenerator;
import org.jpsx.bootstrap.classloader.JPSXClassLoader;
import org.jpsx.runtime.SingletonJPSXComponent;
import org.jpsx.runtime.components.core.CoreComponentConnections;
import org.jpsx.runtime.components.core.DMAChannelOwnerBase;
import org.jpsx.runtime.components.hardware.HardwareComponentConnections;
import org.jpsx.runtime.util.MiscUtil;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

// todo fix bit flags for 15 bit dma

// todo convert subrange back to 16 bit.

public class GPU extends SingletonJPSXComponent implements ClassGenerator, MemoryMapped, PollBlockListener, Pollable {
    private static final boolean ignoreGPU = false;
    private static final boolean dumpGPUD = false;
    private static final boolean debugTransfers = false;
    private static final boolean debugTexturePage = false;
    private static final boolean rgb24conversion = true;
    private static final boolean supportTextureWindow = true;

    private static final int ADDR_GPU_DATA = 0x1f801810;
    private static final int ADDR_GPU_CTRLSTATUS = 0x1f801814;

    private static final int GPUD_CMD_NONE = 0;
    private static final int GPUD_CMD_FILLING = 1;
    private static final int GPUD_CMD_EXTRA = 2;

    private static final int DRAWMODE_SEMI_5P5 = 0x0000;
    private static final int DRAWMODE_SEMI_10P10 = 0x0020;
    private static final int DRAWMODE_SEMI_10M10 = 0x0040;
    private static final int DRAWMODE_SEMI_10P25 = 0x0060;

    private static final int SEMI_NONE = 0;
    private static final int SEMI_5P5 = 1;
    private static final int SEMI_10P10 = 2;
    private static final int SEMI_10M10 = 3;
    private static final int SEMI_10P25 = 4;

    private static final int DRAWMODE_TEXTURE_4BIT = 0x0000;
    private static final int DRAWMODE_TEXTURE_8BIT = 0x0080;
    private static final int DRAWMODE_TEXTURE_16BIT = 0x0100;
    // fake.. returned by getTextureMode() if a texture window is in effect
    private static final int DRAWMODE_TEXTURE_4BITW = 0x8000;
    private static final int DRAWMODE_TEXTURE_8BITW = 0x8080;
    private static final int DRAWMODE_TEXTURE_16BITW = 0x8100;

    private static final int DRAWMODE_SET_MASK = 0x0800;
    private static final int DRAWMODE_CHECK_MASK = 0x1000;

    private static final int TEXTURE_NONE = 0;
    private static final int TEXTURE_4BIT = 1;
    private static final int TEXTURE_8BIT = 2;
    private static final int TEXTURE_16BIT = 3;
    private static final int TEXTURE_4BITW = 4;
    private static final int TEXTURE_8BITW = 5;
    private static final int TEXTURE_16BITW = 6;

    //private static final int PIXEL_BREG_CLUT          = 0x40000000;
    private static final int PIXEL_SOLID_CLUT = 0x20000000;
    private static final int PIXEL_SOLID_CLUT_CHECKED = 0x10000000;
    private static final int PIXEL_RGB24 = 0x08000000;

    private static final int PIXEL_DMA_A = 0x02000000;
    private static final int PIXEL_DMA_B = 0x04000000;
    private static final int PIXEL_DMA_C = 0x06000000;


    private static final int GPU_RGBXX_X_MASK = PIXEL_RGB24 | PIXEL_DMA_A | PIXEL_DMA_B | PIXEL_DMA_C;

    private static final int GPU_RGB24_A = PIXEL_RGB24 | PIXEL_DMA_A;
    private static final int GPU_RGB24_B = PIXEL_RGB24 | PIXEL_DMA_B;
    private static final int GPU_RGB24_C = PIXEL_RGB24 | PIXEL_DMA_C;

    private static final int GPU_RGB15_A = PIXEL_DMA_A;
    private static final int GPU_RGB15_B = PIXEL_DMA_B;
    private static final int GPU_RGB15_C = PIXEL_DMA_C;

    private static Display display;

    private static DisplayManager manager;

    private static final int[] dma16flags;

    private static AddressSpace addressSpace;

    public GPU() {
        super("JPSX Software GPU");
    }

    @Override
    public void resolveConnections() {
        super.resolveConnections();
        addressSpace = CoreComponentConnections.ADDRESS_SPACE.resolve();
        display = HardwareComponentConnections.DISPLAY.resolve();
        manager = HardwareComponentConnections.DISPLAY_MANAGER.resolve();
    }


    public void begin() {
        display.initDisplay();
        gpusReset(0);
    }

    static {
        if (rgb24conversion) {
            dma16flags = new int[]{GPU_RGB15_A, GPU_RGB15_B, GPU_RGB15_C, GPU_RGB15_A};
        } else {
            dma16flags = new int[]{0, 0, 0, 0};
        }
    }

    private static final int getTexturePage() {
        return drawMode & 0x1f;
    }

    private static final int getMaskModes() {
        return drawMode & (DRAWMODE_SET_MASK | DRAWMODE_CHECK_MASK);
    }

    /**
     * accessor function for texture mode from m_drawMode
     */
    private static final int getTextureMode() {
        int rc = drawMode & 0x0180;
        if (noTextureWindow) return rc;
        return rc | 0x8000;
    }

    /**
     * accessor function for semi-transparency mode from m_drawMode
     */
    private static final int getSemiMode() {
        return drawMode & 0x0060;
    }

    private static int displayMode;
    private static int dmaMode;
    private static int maskMode;
    private static int drawMode;

    private static int cmdBufferUsed;
    private static int cmdBufferTarget;
    private static int m_gpudState;

    private static int m_drawOffsetX;
    private static int m_drawOffsetY;

    private static int m_dmaRGB24Index;
    private static int m_dmaRGB24LastPixel;
    private static int m_dmaRGB24LastDWord;
    private static int m_dmaX;
    private static int m_dmaY;
    private static int m_dmaOriginX;
    private static int m_dmaOriginY;
    private static int m_dmaW;
    private static int m_dmaH;
    private static int m_dmaDWordsRemaining;
    private static int m_dmaWordsRemaining;

    private static boolean noTextureWindow = true;

    private static final int[] twuLookup = new int[32];
    private static final int[] twvLookup = new int[32];

    private static boolean rgb24;

    private static int m_gpudCommand;

    //private static int[] m_extraCmdBuffer;
    //private static int m_extraCmdOffset;
    //private static int m_extraCmdSize;

    // clip
    // for now non-zero
//    private static int m_clipLeft=0;
//    private static int m_clipRight=320;
    //    private static int m_clipTop=0;
    //    private static int m_clipBottom=512;
    private static int m_clipLeft;
    private static int m_clipRight;
    private static int m_clipTop;
    private static int m_clipBottom;

    private static final int CMD_BUFFER_SIZE = 16;

    private static final int[] m_cmdBuffer = new int[CMD_BUFFER_SIZE];

    private static int[] videoRAM;

    private static final byte[][] _4bitTexturePages = new byte[32][];
    private static final byte[][] _8bitTexturePages = new byte[32][];

    public void init() {
        super.init();
        JPSXClassLoader.registerClassGenerator("org.jpsx.runtime.components.hardware.gpu.GPUGenerated$_T", this);
        JPSXClassLoader.registerClassGenerator("org.jpsx.runtime.components.hardware.gpu.GPUGenerated$_L", this);
        JPSXClassLoader.registerClassGenerator("org.jpsx.runtime.components.hardware.gpu.GPUGenerated$_S", this);
        JPSXClassLoader.registerClassGenerator("org.jpsx.runtime.components.hardware.gpu.GPUGenerated$_Q", this);
        JPSXClassLoader.registerClassGenerator("org.jpsx.runtime.components.hardware.gpu.GPUGenerated$_R", this);
        CoreComponentConnections.ALL_MEMORY_MAPPED.add(this);
        CoreComponentConnections.POLL_BLOCK_LISTENERS.add(this);
        CoreComponentConnections.DMA_CHANNEL_OWNERS.add(new GPUDMAChannel());
        CoreComponentConnections.DMA_CHANNEL_OWNERS.add(new OTCDMAChannel());
    }

    private static int m_gpudFunctionArgumentCount[] = {
            1, // 00
            1, // 01
            3, // 02
            1, // 03
            1, // 04
            1, // 05
            1, // 06
            1, // 07
            1, // 08
            1, // 09
            1, // 0a
            1, // 0b
            1, // 0c
            1, // 0d
            1, // 0e
            1, // 0f
            1, // 10
            1, // 11
            1, // 12
            1, // 13
            1, // 14
            1, // 15
            1, // 16
            1, // 17
            1, // 18
            1, // 19
            1, // 1a
            1, // 1b
            1, // 1c
            1, // 1d
            1, // 1e
            1, // 1f
            4, // 20
            4, // 21
            4, // 22
            4, // 23
            7, // 24
            7, // 25
            7, // 26
            7, // 27
            5, // 28
            5, // 29
            5, // 2a
            5, // 2b
            9, // 2c
            9, // 2d
            9, // 2e
            9, // 2f
            6, // 30
            6, // 31
            6, // 32
            6, // 33
            9, // 34
            9, // 35
            9, // 36
            9, // 37
            8, // 38
            8, // 39
            8, // 3a
            8, // 3b
            12, // 3c
            12, // 3d
            12, // 3e
            12, // 3f
            3, // 40
            1, // 41
            3, // 42
            1, // 43
            1, // 44
            1, // 45
            1, // 46
            1, // 47
            3, // 48
            1, // 49
            3, // 4a
            1, // 4b
            1, // 4c
            1, // 4d
            1, // 4e
            1, // 4f
            4, // 50
            1,
            4,
            1,
            1, // 54
            1, // 55
            1, // 56
            1, // 57
            4, // 58
            1,
            4,
            1,
            1, // 5c
            1, // 5d
            1, // 5e
            1, // 5f
            3, // 60
            3,
            3,
            3,
            4, // 64
            4,
            4,
            4,
            2,
            2,
            2,
            2,
            1, // 6c
            1, // 6d
            1, // 6e
            1, // 6f
            2, // 70
            2,
            2,
            2,
            3, // 74
            3,
            3,
            3,
            2, // 78
            2,
            2,
            2,
            3, // 7c
            3,
            3,
            3,
            4, // 80
            1, // 81
            1, // 82
            1, // 83
            1, // 84
            1, // 85
            1, // 86
            1, // 87
            1, // 88
            1, // 89
            1, // 8a
            1, // 8b
            1, // 8c
            1, // 8d
            1, // 8e
            1, // 8f
            1, // 90
            1, // 91
            1, // 92
            1, // 93
            1, // 94
            1, // 95
            1, // 96
            1, // 97
            1, // 98
            1, // 99
            1, // 9a
            1, // 9b
            1, // 9c
            1, // 9d
            1, // 9e
            1, // 9f
            3, // a0
            1, // a1
            1, // a2
            1, // a3
            1, // a4
            1, // a5
            1, // a6
            1, // a7
            1, // a8
            1, // a9
            1, // aa
            1, // ab
            1, // ac
            1, // ad
            1, // ae
            1, // af
            1, // b0
            1, // b1
            1, // b2
            1, // b3
            1, // b4
            1, // b5
            1, // b6
            1, // b7
            1, // b8
            1, // b9
            1, // ba
            1, // bb
            1, // bc
            1, // bd
            1, // be
            1, // bf
            3, // c0
            1, // c1
            1, // c2
            1, // c3
            1, // c4
            1, // c5
            1, // c6
            1, // c7
            1, // c8
            1, // c9
            1, // ca
            1, // cb
            1, // cc
            1, // cd
            1, // ce
            1, // cf
            1, // d0
            1, // d1
            1, // d2
            1, // d3
            1, // d4
            1, // d5
            1, // d6
            1, // d7
            1, // d8
            1, // d9
            1, // da
            1, // db
            1, // dc
            1, // dd
            1, // de
            1, // df
            1, // e0
            1, // e1
            1, // e2
            1, // e3
            1, // e4
            1, // e5
            1, // e6
            1, // e7
            1, // e8
            1, // e9
            1, // ea
            1, // eb
            1, // ec
            1, // ed
            1, // ee
            1, // ef
            1, // f0
            1, // f1
            1, // f2
            1, // f3
            1, // f4
            1, // f5
            1, // f6
            1, // f7
            1, // f8
            1, // f9
            1, // fa
            1, // fb
            1, // fc
            1, // fd
            1, // fe
            1, // ff
    };

    private static boolean m_displayEnabled;


    public void registerAddresses(AddressSpaceRegistrar registrar) {
        registrar.registerWrite32Callback(ADDR_GPU_CTRLSTATUS, GPU.class, "gpuCtrlWrite32");
        registrar.registerWrite32Callback(ADDR_GPU_DATA, GPU.class, "gpuDataWrite32");
        registrar.registerRead32Callback(ADDR_GPU_CTRLSTATUS, GPU.class, "gpuStatusRead32");
        registrar.registerRead32Callback(ADDR_GPU_DATA, GPU.class, "gpuDataRead32");

        registrar.registerPoll32Callback(ADDR_GPU_CTRLSTATUS, this);
    }

    static long baseTime = System.currentTimeMillis();
    static int counter = 0;

    private static void debuggo() {
        System.out.println(counter + " " + (System.currentTimeMillis() - baseTime));
        counter++;
    }

    public static void gpuCtrlWrite32(int address, int val) {
        if (ignoreGPU) {
            debuggo();
            return;
        }
//        ASSERT( SANITY_CHECK, address==ADDR_GPU_CTRLSTATUS, "");
        switch (val >> 24) {
            case 0:
                gpusReset(val);
                break;
            case 1:
                gpusCmdReset(val);
                break;
            case 2:
                gpusIRQReset(val);
                break;
            case 3:
                gpusSetDispEnable(val);
                break;
            case 4:
                gpusSetDataTransferMode(val);
                break;
            case 5:
                gpusSetDisplayOrigin(val);
                break;
            case 6:
                gpusSetMonitorLeftRight(val);
                break;
            case 7:
                gpusSetMonitorTopBottom(val);
                break;
            case 8:
                gpusSetDisplayMode(val);
                break;
            case 0x10:
                gpusRequestInfo(val);
                break;
        }
    }

    public static void gpuDataWrite32(int address, int val) {
        if (ignoreGPU) {
            debuggo();
            return;
        }
        videoRAM = display.acquireDisplayBuffer();
        try {
            switch (m_gpudState) {
                case GPUD_CMD_NONE:
                    m_gpudCommand = (val >> 24) & 0xff;
                    m_cmdBuffer[0] = val;
                    cmdBufferUsed = 1;
                    cmdBufferTarget = m_gpudFunctionArgumentCount[m_gpudCommand];
                    m_gpudState = GPUD_CMD_FILLING;
                    break;
                case GPUD_CMD_FILLING:
                    m_cmdBuffer[cmdBufferUsed++] = val;
                    break;
                case GPUD_CMD_EXTRA:
                    m_cmdBuffer[0] = val;
                    int dwordsUsed = GPUDRouter.invoke(m_cmdBuffer, 0, 1);
                    assert (dwordsUsed == 1);
                    return;
            }
            assert (m_gpudState == GPUD_CMD_FILLING);
            if (cmdBufferUsed == cmdBufferTarget) {
                m_gpudState = GPUD_CMD_NONE;
                GPUDRouter.invoke(m_cmdBuffer, 0, cmdBufferUsed);
            }
        } finally {
            display.releaseDisplayBuffer();
            videoRAM = null;
        }
    }

    private static class TemplateQuadRenderer {
        public static void render(PolygonRenderInfo info, Vertex v0, Vertex v1, Vertex v2, Vertex v3) {
            if ((v3.x == v2.x && v0.x == v1.x && v0.y == v2.y && v1.y == v3.y) ||
                    (v0.x == v2.x && v1.x == v3.x && v0.y == v1.y && v2.y == v3.y)) {
                // this returns false if it detects non-linear gradients across the rectangle;
                if (TemplateRectangleRenderer.render(info, v0, v1, v2, v3))
                    return;
            }
            TemplateTriangleRenderer.render(info, v0, v1, v2);
            TemplateTriangleRenderer.render(info, v2, v1, v3);
        }
    }

    private static byte[] get4BitTexturePage() {
        int page = getTexturePage() & 0x1f;
        byte[] rc = _4bitTexturePages[page];
        if (rc == null) {
            assert !rgb24;
            if (debugTexturePage) System.out.println("Generating 4 bit texture page");
            rc = new byte[0x10000];
            int offset = (page & 15) * 64 + (page & 0x10) * 1024 * 16;
            int destOffset = 0;
            for (int y = 0; y < 256; y++) {
                for (int x = 0; x < 64; x++) {
                    int val = unmakePixel(videoRAM[offset++]);

                    rc[destOffset++] = (byte) (val & 0xf);
                    rc[destOffset++] = (byte) ((val & 0xf0) >> 4);
                    rc[destOffset++] = (byte) ((val & 0xf00) >> 8);
                    rc[destOffset++] = (byte) ((val & 0xf000) >> 12);
                }
                offset += 1024 - 64;
            }
            _4bitTexturePages[page] = rc;
        }
        return rc;
    }

    private static byte[] get8BitTexturePage() {
        int page = getTexturePage() & 0x1f;
        byte[] rc = _8bitTexturePages[page];
        if (rc == null) {
            assert !rgb24;
            if (debugTexturePage) System.out.println("Generating 8 bit texture page");
            rc = new byte[0x10000];
            int offset = (page & 15) * 64 + (page & 0x10) * 1024 * 16;
            int destOffset = 0;
            for (int y = 0; y < 256; y++) {
                for (int x = 0; x < 128; x++) {
                    int val = unmakePixel(videoRAM[offset++]);

                    rc[destOffset++] = (byte) (val & 0xff);
                    rc[destOffset++] = (byte) ((val & 0xff00) >> 8);
                }
                offset += 1024 - 128;
            }
            _8bitTexturePages[page] = rc;
        }
        return rc;
    }

    public static class Vertex {
        public int x, y, u, v, r, g, b;

        public final void init(Vertex vert) {
            x = vert.x;
            y = vert.y;
            u = vert.u;
            v = vert.v;
            r = vert.r;
            g = vert.g;
            b = vert.b;
        }

        public String toString() {
            return "(" + x + "," + y + ")";
        }
    }

    public static class Edge {
        Vertex current = new Vertex();
        int dx, du, dv, dr, dg, db;
        int endy;
    }

    public static class LineRenderInfo {
        int r0, g0, b0;
        int r1, g1, b1;
        int color;
    }

    public static class PolygonRenderInfo {
        int r, g, b;
        int u, v;
        int[] clut;
        int clutOffset;
    }



    private static class TemplateTriangleRenderer {
        private static Vertex[] verts = new Vertex[3];
        private static Edge[] edges = new Edge[]{new Edge(), new Edge(), new Edge()};

        // when we load the particular renderer class we make these final and
        // give them a value... if we make them final at compile time,
        // then the java compiler does optimizations on them...
        private static int _renderSemiType;
        private static int _renderTextureType;
        private static boolean _renderBReg;
        private static boolean _renderGouraud;
        private static boolean _renderCheckMask;
        private static boolean _renderSetMask;
        private static boolean _renderSolid;

        public static void render(PolygonRenderInfo info, Vertex v0, Vertex v1, Vertex v2) {
            // following is for spans
            int color = 0;
            int spanStart; // in m_ram
            int count; // length
            int u = 0, v = 0;
            int du = 0, dv = 0;
            int r = 0, g = 0, b = 0;
            int dr = 0, dg = 0, db = 0;
            int[] clut = null;
            int clutOffset = 0;
            byte[] byteTexturePage = null;
            int tpOffset = 0;

            int src = 0;

            Edge e1, e2, e3;

            // TODO - loop? second for not writing to display?
            // note most setup only done first time

            // want to work relative to draw origin
            int clipLeft = m_clipLeft - m_drawOffsetX;
            int clipRight = m_clipRight - m_drawOffsetX;
            int clipTop = m_clipTop - m_drawOffsetY;
            int clipBottom = m_clipBottom - m_drawOffsetY;

            boolean clippedX = false;
            if (v0.x < clipLeft && v1.x < clipLeft && v2.x < clipLeft)
                return;
            if (v0.x >= clipRight && v1.x >= clipRight && v2.x >= clipRight)
                return;
            if (v0.y < clipTop && v1.y < clipTop && v2.y < clipTop)
                return;
            if (v0.y >= clipBottom && v1.y >= clipBottom && v2.y >= clipBottom)
                return;
            if (v0.x < clipLeft || v0.x >= clipRight ||
                    v1.x < clipLeft || v1.x >= clipRight ||
                    v2.x < clipLeft || v2.x >= clipRight)
                clippedX = true;

            // y sort into vertex array
            if (v0.y > v1.y) {
                verts[0] = v1;
                verts[1] = v0;
            } else {
                verts[0] = v0;
                verts[1] = v1;
            }
            if (v2.y < verts[0].y) {
                verts[2] = verts[1];
                verts[1] = verts[0];
                verts[0] = v2;
                //return;
            } else if (v2.y < verts[1].y) {
                verts[2] = verts[1];
                verts[1] = v2;
            } else {
                verts[2] = v2;
            }

            //ASSERT( SANITY_CHECK, verts[1].y>verts[0].y, "");
            //ASSERT( SANITY_CHECK, verts[2].y>verts[1].y, "");

            // check for zero total height
            int dy1 = verts[2].y - verts[0].y;
            if (dy1 == 0) return;

            if (_renderTextureType != TEXTURE_NONE) {
                // for 4 bit
                if (_renderTextureType == TEXTURE_4BIT || _renderTextureType == TEXTURE_4BITW) {
                    byteTexturePage = get4BitTexturePage();
                } else if (_renderTextureType == TEXTURE_8BIT || _renderTextureType == TEXTURE_8BITW) {
                    byteTexturePage = get8BitTexturePage();
                } else if (_renderTextureType == TEXTURE_16BIT || _renderTextureType == TEXTURE_16BITW) {
                    int page = GPU.getTexturePage();
                    tpOffset = (page & 15) * 64 + (page & 0x10) * 1024 * 16;
                }
                clut = info.clut;
                clutOffset = info.clutOffset;
            } else {
                if (!_renderGouraud) {
                    // need color
                    color = info.b | (info.g << 8) | (info.r << 16);
                }
            }

            e1 = edges[0];

            // e1 is long edge from top to bottom
            e1.current.init(verts[0]);
            e1.dx = ((verts[2].x - verts[0].x) << 16) / dy1;
            if (_renderTextureType != TEXTURE_NONE) {
                e1.du = ((verts[2].u - verts[0].u) << 16) / dy1;
                e1.dv = ((verts[2].v - verts[0].v) << 16) / dy1;
            }
            if (_renderGouraud) {
                e1.dr = ((verts[2].r - verts[0].r) << 16) / dy1;
                e1.dg = ((verts[2].g - verts[0].g) << 16) / dy1;
                e1.db = ((verts[2].b - verts[0].b) << 16) / dy1;
            }

            // e2 is short edge at top, unless flat topped
            int dy2 = verts[1].y - verts[0].y;
            if (dy2 != 0) {
                e2 = edges[1];
                e2.current.init(verts[0]);
                e2.dx = ((verts[1].x - verts[0].x) << 16) / dy2;
                if (_renderTextureType != TEXTURE_NONE) {
                    e2.du = ((verts[1].u - verts[0].u) << 16) / dy2;
                    e2.dv = ((verts[1].v - verts[0].v) << 16) / dy2;
                }
                if (_renderGouraud) {
                    e2.dr = ((verts[1].r - verts[0].r) << 16) / dy2;
                    e2.dg = ((verts[1].g - verts[0].g) << 16) / dy2;
                    e2.db = ((verts[1].b - verts[0].b) << 16) / dy2;
                }
                e2.endy = verts[1].y;
            } else {
                e2 = null;
            }

            // e2 is short edge at bottom, unless flat bottomed
            int dy3 = verts[2].y - verts[1].y;
            if (dy3 != 0) {
                e3 = edges[2];
                e3.current.init(verts[1]);
                e3.dx = ((verts[2].x - verts[1].x) << 16) / dy3;
                e3.endy = verts[2].y;
                if (_renderTextureType != TEXTURE_NONE) {
                    e3.du = ((verts[2].u - verts[1].u) << 16) / dy3;
                    e3.dv = ((verts[2].v - verts[1].v) << 16) / dy3;
                }
                if (_renderGouraud) {
                    e3.dr = ((verts[2].r - verts[1].r) << 16) / dy3;
                    e3.dg = ((verts[2].g - verts[1].g) << 16) / dy3;
                    e3.db = ((verts[2].b - verts[1].b) << 16) / dy3;
                }
                if (e2 == null) {
                    // in the case of flat-topped, we replace
                    // e2 with e3
                    e2 = e3;
                    e3 = null;
                }
            } else {
                e3 = null;
            }

            int times = 2;
            e1.current.x <<= 16;
            e2.current.x <<= 16;

            int crossz = (verts[2].x - verts[0].x) * dy2 - (verts[1].x - verts[0].x) * dy1;
            if (crossz == 0)
                return;

            if (_renderTextureType != TEXTURE_NONE) {
                e1.current.u <<= 16;
                e1.current.v <<= 16;
                e2.current.u <<= 16;
                e2.current.v <<= 16;
                long crosszu = (verts[2].u - verts[0].u) * (long) dy2 - (verts[1].u - verts[0].u) * dy1;
                du = (int) ((crosszu << 16) / crossz);
                long crosszv = (verts[2].v - verts[0].v) * (long) dy2 - (verts[1].v - verts[0].v) * dy1;
                dv = (int) ((crosszv << 16) / crossz);
            }
            if (_renderGouraud) {
                e1.current.r <<= 16;
                e1.current.g <<= 16;
                e1.current.b <<= 16;
                e2.current.r <<= 16;
                e2.current.g <<= 16;
                e2.current.b <<= 16;
                long crosszr = (verts[2].r - verts[0].r) * (long) dy2 - (verts[1].r - verts[0].r) * dy1;
                dr = (int) ((crosszr << 16) / crossz);
                long crosszg = (verts[2].g - verts[0].g) * (long) dy2 - (verts[1].g - verts[0].g) * dy1;
                dg = (int) ((crosszg << 16) / crossz);
                long crosszb = (verts[2].b - verts[0].b) * (long) dy2 - (verts[1].b - verts[0].b) * dy1;
                db = (int) ((crosszb << 16) / crossz);
            }
            if (e3 != null) {
                e3.current.x <<= 16;
                if (_renderTextureType != TEXTURE_NONE) {
                    e3.current.u <<= 16;
                    e3.current.v <<= 16;
                }
                if (_renderGouraud) {
                    e3.current.r <<= 16;
                    e3.current.g <<= 16;
                    e3.current.b <<= 16;
                }
            }


            do {
                Edge el, er;
                if (e1.current.x < e2.current.x) {
                    el = e1;
                    er = e2;
                } else if (e1.current.x > e2.current.x) {
                    er = e1;
                    el = e2;
                } else if ((e1.current.x + e1.dx) < (e2.current.x + e2.dx)) {
                    el = e1;
                    er = e2;
                } else {
                    er = e1;
                    el = e2;
                }

                if (e2.endy > clipBottom) {
                    e2.endy = clipBottom;
                }

                if (e2.current.y < clipTop) {
                    int skipY;
                    if (e2.endy < clipTop)
                        skipY = e2.endy - e2.current.y;
                    else
                        skipY = clipTop - e2.current.y;
                    e1.current.x += e1.dx * skipY;
                    e2.current.x += e2.dx * skipY;
                    e2.current.y += skipY;
                    if (_renderTextureType != TEXTURE_NONE) {
                        e1.current.u += e1.du * skipY;
                        e1.current.v += e1.dv * skipY;
                        e2.current.u += e2.du * skipY;
                        e2.current.v += e2.dv * skipY;
                    }
                    if (_renderGouraud) {
                        e1.current.r += e1.dr * skipY;
                        e1.current.g += e1.dg * skipY;
                        e1.current.b += e1.db * skipY;
                        e2.current.r += e2.dr * skipY;
                        e2.current.g += e2.dg * skipY;
                        e2.current.b += e2.db * skipY;
                    }
                }
                int base = m_drawOffsetX + (e2.current.y + m_drawOffsetY) * 1024;
                if (!clippedX) {
                    while (e2.current.y < e2.endy) {
                        int left = el.current.x >> 16;
                        int right = er.current.x >> 16;

                        count = right - left;
                        if (count > 0) {
                            spanStart = base + left;
                            if (_renderTextureType != TEXTURE_NONE) {
                                u = el.current.u;
                                v = el.current.v;
                            }
                            if (_renderGouraud) {
                                r = el.current.r;
                                g = el.current.g;
                                b = el.current.b;
                            }

                            // --- configure span

                            //if (_renderTextureType == TEXTURE_NONE && !_renderGouraud) {
                            //	color = color;
                            //}

                            int target = spanStart + count;
                            int i = spanStart;

                            for (; i < target; i++) {
                                // get source pixel
                                if (_renderTextureType == TEXTURE_NONE) {
                                    if (!_renderGouraud) {
                                        src = color;
                                    } else {
                                        src = (b >> 16) | ((g >> 8) & 0xff00) | (r & 0xff0000);
                                    }
                                } else if (_renderTextureType == TEXTURE_4BIT) {
                                    int offset = ((u >> 16) & 0xff) + ((v >> 8) & 0xff00);
                                    src = clut[clutOffset + byteTexturePage[offset]];
                                } else if (_renderTextureType == TEXTURE_8BIT) {
                                    int offset = ((u >> 16) & 0xff) + ((v >> 8) & 0xff00);
                                    src = clut[clutOffset + (((int) byteTexturePage[offset]) & 0xff)];
                                } else if (_renderTextureType == TEXTURE_16BIT) {
                                    int offset = ((u >> 16) & 0xff) + (((v >> 16) & 0xff) << 10);
                                    src = videoRAM[tpOffset + offset];
                                } else if (_renderTextureType == TEXTURE_4BITW) {
                                    int offset = ((u >> 16) & 0x07) | ((v >> 8) & 0x0700) | twuLookup[(u >> 19) & 0x1f] | twvLookup[(v >> 19) & 0x1f];
                                    src = clut[clutOffset + byteTexturePage[offset]];
                                } else if (_renderTextureType == TEXTURE_8BITW) {
                                    int offset = ((u >> 16) & 0x07) | ((v >> 8) & 0x0700) | twuLookup[(u >> 19) & 0x1f] | twvLookup[(v >> 19) & 0x1f];
                                    src = clut[clutOffset + (((int) byteTexturePage[offset]) & 0xff)];
                                } else if (_renderTextureType == TEXTURE_16BITW) {
                                    int offset = ((u >> 16) & 0x07) | twuLookup[(u >> 19) & 0x1f] | ((((v >> 8) & 0x0700) | twvLookup[(v >> 19) & 0x1f]) << 2);
                                    src = videoRAM[tpOffset + offset];
                                }

                                if (_renderBReg) {
                                    int bb = (src & 0xff);
                                    int gg = ((src >> 8) & 0xff);
                                    int rr = ((src >> 16) & 0xff);
                                    int mask = ((src & 0xffffff) == 0) ? 0 : 1;
                                    rr = (rr * (r >> 16)) >> 7;
                                    if (rr > 255) rr = 255;
                                    gg = (gg * (g >> 16)) >> 7;
                                    if (gg > 255) gg = 255;
                                    bb = (bb * (b >> 16)) >> 7;
                                    if (bb > 255) bb = 255;
                                    src = (src & 0x01000000) | (rr << 16) | (gg << 8) | bb | mask;
                                    //src = (src&0x01000000)|((b>>16)<<16)|((g>>16)<<8)|(r>>16)|mask;
                                }

                                if (_renderTextureType == TEXTURE_NONE || _renderSolid || (src & 0x1ffffff) != 0) {
                                    if (_renderCheckMask && (videoRAM[i] & 0x01000000) != 0) {
                                    } else {
                                        if (_renderSemiType != SEMI_NONE && (_renderTextureType == TEXTURE_NONE || (src & 0x01000000) != 0)) {
                                            // semi transparency
                                            int dest = videoRAM[i];
                                            int destB = dest & 0xff;
                                            int destG = (dest >> 8) & 0xff;
                                            int destR = (dest >> 16) & 0xff;
                                            int srcB = src & 0xff;
                                            int srcG = (src >> 8) & 0xff;
                                            int srcR = (src >> 16) & 0xff;
                                            int tmpR, tmpG, tmpB;

                                            if (_renderSemiType == SEMI_5P5) {
                                                tmpR = (srcR + destR) >> 1;
                                                tmpG = (srcG + destG) >> 1;
                                                tmpB = (srcB + destB) >> 1;
                                                src = (tmpR << 16) | (tmpG << 8) | tmpB;
                                            } else if (_renderSemiType == SEMI_10P10) {
                                                tmpR = srcR + destR;
                                                tmpG = srcG + destG;
                                                tmpB = srcB + destB;
                                                if (tmpR > 255) tmpR = 255;
                                                if (tmpG > 255) tmpG = 255;
                                                if (tmpB > 255) tmpB = 255;
                                                src = (tmpR << 16) | (tmpG << 8) | tmpB;
                                            } else if (_renderSemiType == SEMI_10M10) {
                                                tmpR = destR - srcR;
                                                tmpG = destG - srcG;
                                                tmpB = destB - srcB;
                                                if (tmpR < 0) tmpR = 0;
                                                if (tmpG < 0) tmpG = 0;
                                                if (tmpB < 0) tmpB = 0;
                                                src = (tmpR << 16) | (tmpG << 8) | tmpB;
                                            } else if (_renderSemiType == SEMI_10P25) {
                                                tmpR = destR + (srcR >> 2);
                                                tmpG = destG + (srcG >> 2);
                                                tmpB = destB + (srcB >> 2);
                                                if (tmpR > 255) tmpR = 255;
                                                if (tmpG > 255) tmpG = 255;
                                                if (tmpB > 255) tmpB = 255;
                                                src = (tmpR << 16) | (tmpG << 8) | tmpB;
                                            }
                                        }

                                        // mask set
                                        if (_renderSetMask) {
                                            src |= 0x01000000;
                                        }
                                        videoRAM[i] = src;
                                    }
                                }
                                if (_renderTextureType != TEXTURE_NONE) {
                                    u += du;
                                    v += dv;
                                }
                                if (_renderGouraud) {
                                    r += dr;
                                    g += dg;
                                    b += db;
                                }
                            }

                            // --- end span
                        }
                        e1.current.x += e1.dx;
                        e2.current.x += e2.dx;
                        if (_renderTextureType != TEXTURE_NONE) {
                            e1.current.u += e1.du;
                            e1.current.v += e1.dv;
                            e2.current.u += e2.du;
                            e2.current.v += e2.dv;
                        }
                        if (_renderGouraud) {
                            e1.current.r += e1.dr;
                            e1.current.g += e1.dg;
                            e1.current.b += e1.db;
                            e2.current.r += e2.dr;
                            e2.current.g += e2.dg;
                            e2.current.b += e2.db;
                        }
                        e2.current.y++;
                        base += 1024;
                    }
                } else {
                    while (e2.current.y < e2.endy) {
                        int left = el.current.x >> 16;
                        int right = er.current.x >> 16;

                        if (right > clipRight)
                            right = clipRight;
                        if (_renderTextureType != TEXTURE_NONE) {
                            u = el.current.u;
                            v = el.current.v;
                        }
                        if (_renderGouraud) {
                            r = el.current.r;
                            g = el.current.g;
                            b = el.current.b;
                        }
                        if (left < clipLeft) {
                            int skipX = clipLeft - left;
                            left = clipLeft;
                            if (_renderTextureType != TEXTURE_NONE) {
                                u += du * skipX;
                                v += dv * skipX;
                            }
                            if (_renderGouraud) {
                                r += dr * skipX;
                                g += dg * skipX;
                                b += db * skipX;
                            }
                        }
                        count = right - left;
                        if (count > 0) {
                            spanStart = base + left;

                            // --- configure span

                            //if (_renderTextureType == TEXTURE_NONE && !_renderGouraud) {
                            //	color = color;
                            //}

                            int target = spanStart + count;
                            int i = spanStart;

                            for (; i < target; i++) {
                                // get source pixel
                                if (_renderTextureType == TEXTURE_NONE) {
                                    if (!_renderGouraud) {
                                        src = color;
                                    } else {
                                        src = (b >> 16) | ((g >> 8) & 0xff00) | (r & 0xff0000);
                                    }
                                } else if (_renderTextureType == TEXTURE_4BIT) {
                                    int offset = ((u >> 16) & 0xff) + ((v >> 8) & 0xff00);
                                    src = clut[clutOffset + byteTexturePage[offset]];
                                } else if (_renderTextureType == TEXTURE_8BIT) {
                                    int offset = ((u >> 16) & 0xff) + ((v >> 8) & 0xff00);
                                    src = clut[clutOffset + (((int) byteTexturePage[offset]) & 0xff)];
                                } else if (_renderTextureType == TEXTURE_16BIT) {
                                    int offset = ((u >> 16) & 0xff) + (((v >> 16) & 0xff) << 10);
                                    src = videoRAM[tpOffset + offset];
                                } else if (_renderTextureType == TEXTURE_4BITW) {
                                    int offset = ((u >> 16) & 0x07) | ((v >> 8) & 0x0700) | twuLookup[(u >> 19) & 0x1f] | twvLookup[(v >> 19) & 0x1f];
                                    src = clut[clutOffset + byteTexturePage[offset]];
                                } else if (_renderTextureType == TEXTURE_8BITW) {
                                    int offset = ((u >> 16) & 0x07) | ((v >> 8) & 0x0700) | twuLookup[(u >> 19) & 0x1f] | twvLookup[(v >> 19) & 0x1f];
                                    src = clut[clutOffset + (((int) byteTexturePage[offset]) & 0xff)];
                                } else if (_renderTextureType == TEXTURE_16BITW) {
                                    int offset = ((u >> 16) & 0x07) | twuLookup[(u >> 19) & 0x1f] | ((((v >> 8) & 0x0700) | twvLookup[(v >> 19) & 0x1f]) << 2);
                                    src = videoRAM[tpOffset + offset];
                                }

                                if (_renderBReg) {
                                    int bb = (src & 0xff);
                                    int gg = ((src >> 8) & 0xff);
                                    int rr = ((src >> 16) & 0xff);
                                    int mask = ((src & 0xffffff) == 0) ? 0 : 1;
                                    rr = (rr * (r >> 16)) >> 7;
                                    if (rr > 255) rr = 255;
                                    gg = (gg * (g >> 16)) >> 7;
                                    if (gg > 255) gg = 255;
                                    bb = (bb * (b >> 16)) >> 7;
                                    if (bb > 255) bb = 255;
                                    src = (src & 0x01000000) | (rr << 16) | (gg << 8) | bb | mask;
                                }

                                if (_renderTextureType == TEXTURE_NONE || _renderSolid || (src & 0x1ffffff) != 0) {
                                    if (_renderCheckMask && (videoRAM[i] & 0x01000000) != 0) {
                                    } else {
                                        if (_renderSemiType != SEMI_NONE && (_renderTextureType == TEXTURE_NONE || (src & 0x01000000) != 0)) {
                                            // semi transparency
                                            int dest = videoRAM[i];
                                            int destB = dest & 0xff;
                                            int destG = (dest >> 8) & 0xff;
                                            int destR = (dest >> 16) & 0xff;
                                            int srcB = src & 0xff;
                                            int srcG = (src >> 8) & 0xff;
                                            int srcR = (src >> 16) & 0xff;
                                            int tmpR, tmpG, tmpB;

                                            if (_renderSemiType == SEMI_5P5) {
                                                tmpR = (srcR + destR) >> 1;
                                                tmpG = (srcG + destG) >> 1;
                                                tmpB = (srcB + destB) >> 1;
                                                src = (tmpR << 16) | (tmpG << 8) | tmpB;
                                            } else if (_renderSemiType == SEMI_10P10) {
                                                tmpR = srcR + destR;
                                                tmpG = srcG + destG;
                                                tmpB = srcB + destB;
                                                if (tmpR > 255) tmpR = 255;
                                                if (tmpG > 255) tmpG = 255;
                                                if (tmpB > 255) tmpB = 255;
                                                src = (tmpR << 16) | (tmpG << 8) | tmpB;
                                            } else if (_renderSemiType == SEMI_10M10) {
                                                tmpR = destR - srcR;
                                                tmpG = destG - srcG;
                                                tmpB = destB - srcB;
                                                if (tmpR < 0) tmpR = 0;
                                                if (tmpG < 0) tmpG = 0;
                                                if (tmpB < 0) tmpB = 0;
                                                src = (tmpR << 16) | (tmpG << 8) | tmpB;
                                            } else if (_renderSemiType == SEMI_10P25) {
                                                tmpR = destR + (srcR >> 2);
                                                tmpG = destG + (srcG >> 2);
                                                tmpB = destB + (srcB >> 2);
                                                if (tmpR > 255) tmpR = 255;
                                                if (tmpG > 255) tmpG = 255;
                                                if (tmpB > 255) tmpB = 255;
                                                src = (tmpR << 16) | (tmpG << 8) | tmpB;
                                            }
                                        }

                                        // mask set
                                        if (_renderSetMask) {
                                            src |= 0x01000000;
                                        }
                                        videoRAM[i] = src;
                                    }
                                }
                                if (_renderTextureType != TEXTURE_NONE) {
                                    u += du;
                                    v += dv;
                                }
                                if (_renderGouraud) {
                                    r += dr;
                                    g += dg;
                                    b += db;
                                }
                            }

                            // --- end span

                        }
                        e1.current.x += e1.dx;
                        e2.current.x += e2.dx;
                        if (_renderTextureType != TEXTURE_NONE) {
                            e1.current.u += e1.du;
                            e1.current.v += e1.dv;
                            e2.current.u += e2.du;
                            e2.current.v += e2.dv;
                        }
                        if (_renderGouraud) {
                            e1.current.r += e1.dr;
                            e1.current.g += e1.dg;
                            e1.current.b += e1.db;
                            e2.current.r += e2.dr;
                            e2.current.g += e2.dg;
                            e2.current.b += e2.db;
                        }
                        e2.current.y++;
                        base += 1024;
                    }
                    ;
                }

                e2 = e3;
                times--;
            } while (times != 0 && e2 != null);
        }
    }

    private static class TemplateRectangleRenderer {
        private static Vertex[] verts = new Vertex[4];

        // when we load the particular renderer class we make these final and
        // give them a value... if we make them final at compile time,
        // then the java compiler does optimizations on them...
        private static int _renderSemiType;
        private static int _renderTextureType;
        private static boolean _renderBReg;
        private static boolean _renderGouraud;
        private static boolean _renderCheckMask;
        private static boolean _renderSetMask;
        private static boolean _renderSolid;

        public static boolean render(PolygonRenderInfo info, Vertex ve0, Vertex ve1, Vertex ve2, Vertex ve3) {
            // following is for spans
            int color = 0;
            int spanStart; // in m_ram
            int count; // length

            int u = 0, v = 0;
            int u0 = 0, v0 = 0;
            int du = 0, dv = 0;
            int du0 = 0, dv0 = 0;

            int r = 0, g = 0, b = 0;
            int r0 = 0, g0 = 0, b0 = 0;
            int dr = 0, dg = 0, db = 0;
            int dr0 = 0, dg0 = 0, db0 = 0;

            int[] clut = null;
            int clutOffset = 0;
            byte[] byteTexturePage = null;
            int tpOffset = 0;

            int src = 0;

            verts[0] = ve0;
            verts[1] = ve1;
            verts[2] = ve2;
            verts[3] = ve3;

            Vertex tmp;

            // sort to 0 - 1
            //         |   |
            //         2 - 3
            if (verts[0].x == verts[1].x) {
                tmp = verts[1];
                verts[1] = verts[2];
                verts[2] = tmp;
            }
            if (verts[0].x > verts[1].x) {
                tmp = verts[1];
                verts[1] = verts[0];
                verts[0] = tmp;
            }
            if (verts[2].x > verts[3].x) {
                tmp = verts[2];
                verts[2] = verts[3];
                verts[3] = tmp;
            }
            if (verts[0].y > verts[2].y) {
                tmp = verts[0];
                verts[0] = verts[2];
                verts[2] = tmp;
            }
            if (verts[1].y > verts[3].y) {
                tmp = verts[1];
                verts[1] = verts[3];
                verts[3] = tmp;
            }

            int x = verts[0].x;
            int y = verts[0].y;
            int ww = verts[1].x - x;
            int hh = verts[2].y - y;

            if (ww <= 0 || hh <= 0)
                return true;

            int w = ww;
            int h = hh;

            // TODO - loop? second for not writing to display?
            // note most setup only done first time

            // want to work relative to draw origin
            int clipLeft = m_clipLeft - m_drawOffsetX;
            int clipRight = m_clipRight - m_drawOffsetX;
            int clipTop = m_clipTop - m_drawOffsetY;
            int clipBottom = m_clipBottom - m_drawOffsetY;

            if ((x + w) >= clipRight)
                w = clipRight - x;
            if ((y + h) >= clipBottom)
                h = clipBottom - y;

            int skipX = 0;
            int skipY = 0;

            if (x < clipLeft) {
                skipX = clipLeft - x;
                x = clipLeft;
                w -= skipX;
            }
            if (y < clipTop) {
                skipY = clipTop - y;
                y = clipTop;
                h -= skipY;
            }

            if (w <= 0 || h <= 0)
                return true;

            if (_renderTextureType != TEXTURE_NONE) {
                // check for non-linear
                if ((verts[1].u - verts[0].u) != (verts[3].u - verts[2].u) ||
                        (verts[1].v - verts[0].v) != (verts[3].v - verts[2].v) ||
                        (verts[2].u - verts[0].u) != (verts[3].u - verts[1].u) ||
                        (verts[2].v - verts[0].v) != (verts[3].v - verts[1].v)) {
                    //System.out.println("non-linear quad texture!");
                    return false;
                }
                u0 = verts[0].u << 16;
                v0 = verts[0].v << 16;
                du = ((verts[1].u << 16) - u0) / ww;
                dv = ((verts[1].v << 16) - v0) / ww;
                du0 = ((verts[2].u << 16) - u0) / hh;
                dv0 = ((verts[2].v << 16) - v0) / hh;

                if (skipX != 0) {
                    u0 += skipX * du;
                    v0 += skipX + dv;
                }
                if (skipY != 0) {
                    u0 += skipY * du0;
                    v0 += skipY * dv0;
                }

                // for 4 bit
                if (_renderTextureType == TEXTURE_4BIT || _renderTextureType == TEXTURE_4BITW) {
                    byteTexturePage = get4BitTexturePage();
                } else if (_renderTextureType == TEXTURE_8BIT || _renderTextureType == TEXTURE_8BITW) {
                    byteTexturePage = get8BitTexturePage();
                } else if (_renderTextureType == TEXTURE_16BIT || _renderTextureType == TEXTURE_16BITW) {
                    int page = GPU.getTexturePage();
                    tpOffset = (page & 15) * 64 + (page & 0x10) * 1024 * 16;
                }
                clut = info.clut;
                clutOffset = info.clutOffset;
                if (_renderBReg) {
                    r = info.r;
                    g = info.g;
                    b = info.b;
                }
            } else {
                color = info.b | (info.g << 8) | (info.r << 16);
            }

            if (_renderGouraud) {
                // check for non-linear
                if ((verts[1].r - verts[0].r) != (verts[3].r - verts[2].r) ||
                        (verts[1].g - verts[0].g) != (verts[3].g - verts[2].g) ||
                        (verts[1].b - verts[0].b) != (verts[3].b - verts[2].b) ||
                        (verts[2].r - verts[0].r) != (verts[3].r - verts[1].r) ||
                        (verts[2].g - verts[0].g) != (verts[3].g - verts[1].g) ||
                        (verts[2].b - verts[0].b) != (verts[3].b - verts[1].b)) {
                    //System.out.println("non-linear quad gouraud!");
                    return false;
                }
                r0 = verts[0].r << 16;
                g0 = verts[0].g << 16;
                b0 = verts[0].b << 16;

                dr = ((verts[1].r << 16) - r0) / ww;
                dg = ((verts[1].g << 16) - g0) / ww;
                db = ((verts[1].b << 16) - b0) / ww;
                dr0 = ((verts[2].r << 16) - r0) / hh;
                dg0 = ((verts[2].g << 16) - g0) / hh;
                db0 = ((verts[2].b << 16) - b0) / hh;

                if (skipX != 0) {
                    r0 += skipX * dr;
                    g0 += skipX + dg;
                    b0 += skipX + db;
                }
                if (skipY != 0) {
                    r0 += skipY * dr0;
                    g0 += skipY * dg0;
                    b0 += skipY * db0;
                }
            }

            int base = m_drawOffsetX + x + (y + m_drawOffsetY) * 1024;
            outertmp:
            for (; h > 0; h--) {
                if (_renderTextureType != TEXTURE_NONE) {
                    u = u0;
                    v = v0;
                }
                if (_renderGouraud) {
                    r = r0;
                    g = g0;
                    b = b0;
                }
                for (int i = base; i < (base + w); i++) {
                    // get source pixel
                    if (_renderTextureType == TEXTURE_NONE) {
                        if (!_renderGouraud) {
                            src = color;
                        } else {
                            src = (b >> 16) | ((g >> 8) & 0xff00) | (r & 0xff0000);
                        }
                    } else if (_renderTextureType == TEXTURE_4BIT) {
                        int offset = ((u >> 16) & 0xff) + ((v >> 8) & 0xff00);
                        src = clut[clutOffset + byteTexturePage[offset]];
                    } else if (_renderTextureType == TEXTURE_8BIT) {
                        int offset = ((u >> 16) & 0xff) + ((v >> 8) & 0xff00);
                        src = clut[clutOffset + (((int) byteTexturePage[offset]) & 0xff)];
                    } else if (_renderTextureType == TEXTURE_16BIT) {
                        int offset = ((u >> 16) & 0xff) + (((v >> 16) & 0xff) << 10);
                        src = videoRAM[tpOffset + offset];
                    } else if (_renderTextureType == TEXTURE_4BITW) {
                        int offset = ((u >> 16) & 0x07) | ((v >> 8) & 0x0700) | twuLookup[(u >> 19) & 0x1f] | twvLookup[(v >> 19) & 0x1f];
                        src = clut[clutOffset + byteTexturePage[offset]];
                    } else if (_renderTextureType == TEXTURE_8BITW) {
                        int offset = ((u >> 16) & 0x07) | ((v >> 8) & 0x0700) | twuLookup[(u >> 19) & 0x1f] | twvLookup[(v >> 19) & 0x1f];
                        src = clut[clutOffset + (((int) byteTexturePage[offset]) & 0xff)];
                    } else if (_renderTextureType == TEXTURE_16BITW) {
                        int offset = ((u >> 16) & 0x07) | twuLookup[(u >> 19) & 0x1f] | ((((v >> 8) & 0x0700) | twvLookup[(v >> 19) & 0x1f]) << 2);
                        src = videoRAM[tpOffset + offset];
                    }

                    if (_renderBReg) {
                        int bb = (src & 0xff);
                        int gg = ((src >> 8) & 0xff);
                        int rr = ((src >> 16) & 0xff);
                        int mask = ((src & 0xffffff) == 0) ? 0 : 1;
                        rr = (rr * (r >> 16)) >> 7;
                        if (rr > 255) rr = 255;
                        gg = (gg * (g >> 16)) >> 7;
                        if (gg > 255) gg = 255;
                        bb = (bb * (b >> 16)) >> 7;
                        if (bb > 255) bb = 255;
                        src = (src & 0x01000000) | (rr << 16) | (gg << 8) | bb | mask;
                    }

                    if (_renderTextureType == TEXTURE_NONE || _renderSolid || (src & 0x1ffffff) != 0) {
                        if (_renderCheckMask && (videoRAM[i] & 0x01000000) != 0) {
                        } else {
                            if (_renderSemiType != SEMI_NONE && (_renderTextureType == TEXTURE_NONE || (src & 0x01000000) != 0)) {
                                // semi transparency
                                int dest = videoRAM[i];
                                int destB = dest & 0xff;
                                int destG = (dest >> 8) & 0xff;
                                int destR = (dest >> 16) & 0xff;
                                int srcB = src & 0xff;
                                int srcG = (src >> 8) & 0xff;
                                int srcR = (src >> 16) & 0xff;
                                int tmpR, tmpG, tmpB;

                                if (_renderSemiType == SEMI_5P5) {
                                    tmpR = (srcR + destR) >> 1;
                                    tmpG = (srcG + destG) >> 1;
                                    tmpB = (srcB + destB) >> 1;
                                    src = (tmpR << 16) | (tmpG << 8) | tmpB;
                                } else if (_renderSemiType == SEMI_10P10) {
                                    tmpR = srcR + destR;
                                    tmpG = srcG + destG;
                                    tmpB = srcB + destB;
                                    if (tmpR > 255) tmpR = 255;
                                    if (tmpG > 255) tmpG = 255;
                                    if (tmpB > 255) tmpB = 255;
                                    src = (tmpR << 16) | (tmpG << 8) | tmpB;
                                } else if (_renderSemiType == SEMI_10M10) {
                                    tmpR = destR - srcR;
                                    tmpG = destG - srcG;
                                    tmpB = destB - srcB;
                                    if (tmpR < 0) tmpR = 0;
                                    if (tmpG < 0) tmpG = 0;
                                    if (tmpB < 0) tmpB = 0;
                                    src = (tmpR << 16) | (tmpG << 8) | tmpB;
                                } else if (_renderSemiType == SEMI_10P25) {
                                    tmpR = destR + (srcR >> 2);
                                    tmpG = destG + (srcG >> 2);
                                    tmpB = destB + (srcB >> 2);
                                    if (tmpR > 255) tmpR = 255;
                                    if (tmpG > 255) tmpG = 255;
                                    if (tmpB > 255) tmpB = 255;
                                    src = (tmpR << 16) | (tmpG << 8) | tmpB;
                                }
                            }

                            // mask set
                            if (_renderSetMask) {
                                src |= 0x01000000;
                            }
                            videoRAM[i] = src;
                        }
                    }
                    if (_renderTextureType != TEXTURE_NONE) {
                        u += du;
                        v += dv;
                    }
                    if (_renderGouraud) {
                        r += dr;
                        g += dg;
                        b += db;
                    }
                }
                base += 1024;
                if (_renderTextureType != TEXTURE_NONE) {
                    u0 += du0;
                    v0 += dv0;
                }
                if (_renderGouraud) {
                    r0 += dr0;
                    g0 += dg0;
                    b0 += db0;
                }
            }
            return true;
        }
    }

    private static class TemplateSpriteRenderer {
        // when we load the particular renderer class we make these final and
        // give them a value... if we make them final at compile time,
        // then the java compiler does optimizations on them...
        private static int _renderSemiType;
        private static int _renderTextureType;
        private static boolean _renderBReg;
        private static boolean _renderGouraud;
        private static boolean _renderCheckMask;
        private static boolean _renderSetMask;
        private static boolean _renderSolid;


        public static void render(PolygonRenderInfo info, int x, int y, int w, int h) {
//            System.out.println("Sprite renderer "+x+","+y+" "+w+","+h);
            // following is for spans
            int color = 0;
            int u0 = 0, v = 0;
            int r = 0, g = 0, b = 0;
            int[] clut = null;
            int clutOffset = 0;
            byte[] byteTexturePage = null;
            int tpOffset = 0;

            int src = 0;

            // TODO - loop? second for not writing to display?
            // note most setup only done first time

            // want to work relative to draw origin
            int clipLeft = m_clipLeft - m_drawOffsetX;
            int clipRight = m_clipRight - m_drawOffsetX;
            int clipTop = m_clipTop - m_drawOffsetY;
            int clipBottom = m_clipBottom - m_drawOffsetY;

            if ((x + w) >= clipRight)
                w = clipRight - x;
            if ((y + h) >= clipBottom)
                h = clipBottom - y;

            int skipX = 0;
            int skipY = 0;

            if (x < clipLeft) {
                skipX = clipLeft - x;
                x = clipLeft;
                w -= skipX;
            }
            if (y < clipTop) {
                skipY = clipTop - y;
                y = clipTop;
                h -= skipY;
            }

            if (w <= 0 || h <= 0)
                return;

            if (_renderTextureType != TEXTURE_NONE) {
                // shifted to avoid shifts in loop
                u0 = info.u + skipX;
                v = (info.v + skipY) << 8;
                // for 4 bit
                if (_renderTextureType == TEXTURE_4BIT || _renderTextureType == TEXTURE_4BITW) {
                    byteTexturePage = get4BitTexturePage();
                } else if (_renderTextureType == TEXTURE_8BIT || _renderTextureType == TEXTURE_8BITW) {
                    byteTexturePage = get8BitTexturePage();
                } else if (_renderTextureType == TEXTURE_16BIT || _renderTextureType == TEXTURE_16BITW) {
                    int page = GPU.getTexturePage();
                    tpOffset = (page & 15) * 64 + (page & 0x10) * 1024 * 16;
                }
                clut = info.clut;
                clutOffset = info.clutOffset;
                if (_renderBReg) {
                    r = info.r;
                    g = info.g;
                    b = info.b;
                }
            } else {
                color = info.b | (info.g << 8) | (info.r << 16);
            }

            int base = m_drawOffsetX + x + (y + m_drawOffsetY) * 1024;
            for (; h > 0; h--) {
                int u = u0;
                for (int i = base; i < (base + w); i++) {
                    //System.out.println(MiscUtil.toHex(u,4)+" "+MiscUtil.toHex(v,4));
                    // get source pixel
                    if (_renderTextureType == TEXTURE_NONE) {
                        src = color;
                    } else if (_renderTextureType == TEXTURE_4BIT) {
                        int offset = (u & 0xff) | (v & 0xff00);
                        src = clut[clutOffset + byteTexturePage[offset]];
                    } else if (_renderTextureType == TEXTURE_8BIT) {
                        int offset = (u & 0xff) | (v & 0xff00);
                        src = clut[clutOffset + (((int) byteTexturePage[offset]) & 0xff)];
                    } else if (_renderTextureType == TEXTURE_16BIT) {
                        int offset = (u & 0xff) | ((v & 0xff00) << 2);
                        src = videoRAM[tpOffset + offset];
                    } else if (_renderTextureType == TEXTURE_4BITW) {
                        int offset = (u & 0x07) | (v & 0x0700) | twuLookup[(u >> 3) & 0x1f] | twvLookup[(v >> 11) & 0x1f];
                        src = clut[clutOffset + byteTexturePage[offset]];
                    } else if (_renderTextureType == TEXTURE_8BITW) {
                        int offset = (u & 0x07) | (v & 0x0700) | twuLookup[(u >> 3) & 0x1f] | twvLookup[(v >> 11) & 0x1f];
                        src = clut[clutOffset + (((int) byteTexturePage[offset]) & 0xff)];
                    } else if (_renderTextureType == TEXTURE_16BITW) {
                        int offset = (u & 0x07) | twuLookup[(u >> 3) & 0x1f] | (((v & 0x0700) | twvLookup[(v >> 11) & 0x1f]) << 2);
                        src = videoRAM[tpOffset + offset];
                    }

                    if (_renderBReg) {
                        int bb = (src & 0xff);
                        int gg = ((src >> 8) & 0xff);
                        int rr = ((src >> 16) & 0xff);
                        int mask = ((src & 0xffffff) == 0) ? 0 : 1;
                        rr = (rr * (r >> 16)) >> 7;
                        if (rr > 255) rr = 255;
                        gg = (gg * (g >> 16)) >> 7;
                        if (gg > 255) gg = 255;
                        bb = (bb * (b >> 16)) >> 7;
                        if (bb > 255) bb = 255;
                        src = (src & 0x01000000) | (rr << 16) | (gg << 8) | bb | mask;
                        //src = (src&0x01000000)|((b>>16)<<16)|((g>>16)<<8)|(r>>16)|mask;
                    }
                    if (_renderTextureType == TEXTURE_NONE || _renderSolid || (src & 0x1ffffff) != 0) {
                        if (_renderCheckMask && (videoRAM[i] & 0x01000000) != 0) {
                        } else {
                            if (_renderSemiType != SEMI_NONE && (_renderTextureType == TEXTURE_NONE || (src & 0x01000000) != 0)) {
                                // semi transparency
                                int dest = videoRAM[i];
                                int destB = dest & 0xff;
                                int destG = (dest >> 8) & 0xff;
                                int destR = (dest >> 16) & 0xff;
                                int srcB = src & 0xff;
                                int srcG = (src >> 8) & 0xff;
                                int srcR = (src >> 16) & 0xff;
                                int tmpR, tmpG, tmpB;

                                if (_renderSemiType == SEMI_5P5) {
                                    tmpR = (srcR + destR) >> 1;
                                    tmpG = (srcG + destG) >> 1;
                                    tmpB = (srcB + destB) >> 1;
                                    src = (tmpR << 16) | (tmpG << 8) | tmpB;
                                } else if (_renderSemiType == SEMI_10P10) {
                                    tmpR = srcR + destR;
                                    tmpG = srcG + destG;
                                    tmpB = srcB + destB;
                                    if (tmpR > 255) tmpR = 255;
                                    if (tmpG > 255) tmpG = 255;
                                    if (tmpB > 255) tmpB = 255;
                                    src = (tmpR << 16) | (tmpG << 8) | tmpB;
                                } else if (_renderSemiType == SEMI_10M10) {
                                    tmpR = destR - srcR;
                                    tmpG = destG - srcG;
                                    tmpB = destB - srcB;
                                    if (tmpR < 0) tmpR = 0;
                                    if (tmpG < 0) tmpG = 0;
                                    if (tmpB < 0) tmpB = 0;
                                    src = (tmpR << 16) | (tmpG << 8) | tmpB;
                                } else if (_renderSemiType == SEMI_10P25) {
                                    tmpR = destR + (srcR >> 2);
                                    tmpG = destG + (srcG >> 2);
                                    tmpB = destB + (srcB >> 2);
                                    if (tmpR > 255) tmpR = 255;
                                    if (tmpG > 255) tmpG = 255;
                                    if (tmpB > 255) tmpB = 255;
                                    src = (tmpR << 16) | (tmpG << 8) | tmpB;
                                }
                            }

                            // mask set
                            if (_renderSetMask) {
                                src |= 0x01000000;
                            }
                            videoRAM[i] = src;
                        }
                    }
                    if (_renderTextureType != TEXTURE_NONE) {
                        u++;
                    }
                }
                base += 1024;
                if (_renderTextureType != TEXTURE_NONE) {
                    v += 256;
                }
            }
        }
    }

    private static class TemplateLineRenderer {
        // when we load the particular renderer class we make these final and
        // give them a value... if we make them final at compile time,
        // then the java compiler does optimizations on them...
        private static int _renderSemiType;
        private static int _renderTextureType;
        private static boolean _renderBReg;
        private static boolean _renderGouraud;
        private static boolean _renderCheckMask;
        private static boolean _renderSetMask;
        private static boolean _renderSolid;

        public static void render(LineRenderInfo info, Vertex v0, Vertex v1) {
            int clipLeft = m_clipLeft - m_drawOffsetX;
            int clipRight = m_clipRight - m_drawOffsetX;
            int clipTop = m_clipTop - m_drawOffsetY;
            int clipBottom = m_clipBottom - m_drawOffsetY;

            int p0codes = 0;
            int p1codes = 0;

            if (v0.x < clipLeft) p0codes |= 1;
            if (v0.x >= clipRight) p0codes |= 2;
            if (v0.y < clipTop) p0codes |= 4;
            if (v0.y >= clipBottom) p0codes |= 8;

            if (v1.x < clipLeft) p1codes |= 1;
            if (v1.x >= clipRight) p1codes |= 2;
            if (v1.y < clipTop) p1codes |= 4;
            if (v1.y >= clipBottom) p1codes |= 8;

            // trivial reject
            if (0 != (p0codes & p1codes))
                return;

            // @@@ TMP
            if (0 != (p0codes | p1codes))
                return;

            // TODO other code checks

            int color = 0;
            int r = 0;
            int g = 0;
            int b = 0;

            if (!_renderGouraud) {
                color = info.color;
            } else {
                r = info.r0 << 16;
                g = info.g0 << 16;
                b = info.b0 << 16;
            }

            // todo clip
            int dx = v1.x - v0.x;
            int adx = dx > 0 ? dx : -dx;
            int dy = v1.y - v0.y;
            int ady = dy > 0 ? dy : -dy;

            if (adx == 0 && ady == 0)
                return;

            int count;
            int minor;
            int dpMajor;
            int dpMinor;
            int dr = 0;
            int dg = 0;
            int db = 0;
            if (adx > ady) {
                // x is major
                count = adx;//+1; // check the +1
                minor = ady;
                dpMajor = dx > 0 ? 1 : -1;
                dpMinor = dy > 0 ? 1024 : -1024;
                if (_renderGouraud) {
                    dr = ((info.r1 - info.r0) << 16) / adx;
                    dg = ((info.g1 - info.g0) << 16) / adx;
                    db = ((info.b1 - info.b0) << 16) / adx;
                }
            } else {
                // y is major
                count = ady;//+1; // check the +1
                minor = adx;
                dpMajor = dy > 0 ? 1024 : -1024;
                dpMinor = dx > 0 ? 1 : -1;
                if (_renderGouraud) {
                    dr = ((info.r1 - info.r0) << 16) / ady;
                    dg = ((info.g1 - info.g0) << 16) / ady;
                    db = ((info.b1 - info.b0) << 16) / ady;
                }
            }

            // --- configure span

            int i = m_drawOffsetX + v0.x + (v0.y + m_drawOffsetY) * 1024;
            int src = color;
            int line = count / 2;
            int j = count;

            for (; j > 0; j--) {
                if (_renderGouraud) {
                    src = (b >> 16) | ((g >> 8) & 0xff00) | (r & 0xff0000);
                }
                if (_renderCheckMask && (videoRAM[i] & 0x01000000) != 0) {
                } else {
                    if (_renderSemiType != SEMI_NONE) {
                        // semi transparency
                        int dest = videoRAM[i];
                        int destB = dest & 0xff;
                        int destG = (dest >> 8) & 0xff;
                        int destR = (dest >> 16) & 0xff;
                        int srcB = src & 0xff;
                        int srcG = (src >> 8) & 0xff;
                        int srcR = (src >> 16) & 0xff;
                        int tmpR, tmpG, tmpB;

                        if (_renderSemiType == SEMI_5P5) {
                            tmpR = (srcR + destR) >> 1;
                            tmpG = (srcG + destG) >> 1;
                            tmpB = (srcB + destB) >> 1;
                            src = (tmpR << 16) | (tmpG << 8) | tmpB;
                        } else if (_renderSemiType == SEMI_10P10) {
                            tmpR = srcR + destR;
                            tmpG = srcG + destG;
                            tmpB = srcB + destB;
                            if (tmpR > 255) tmpR = 255;
                            if (tmpG > 255) tmpG = 255;
                            if (tmpB > 255) tmpB = 255;
                            src = (tmpR << 16) | (tmpG << 8) | tmpB;
                        } else if (_renderSemiType == SEMI_10M10) {
                            tmpR = destR - srcR;
                            tmpG = destG - srcG;
                            tmpB = destB - srcB;
                            if (tmpR < 0) tmpR = 0;
                            if (tmpG < 0) tmpG = 0;
                            if (tmpB < 0) tmpB = 0;
                            src = (tmpR << 16) | (tmpG << 8) | tmpB;
                        } else if (_renderSemiType == SEMI_10P25) {
                            tmpR = destR + (srcR >> 2);
                            tmpG = destG + (srcG >> 2);
                            tmpB = destB + (srcB >> 2);
                            if (tmpR > 255) tmpR = 255;
                            if (tmpG > 255) tmpG = 255;
                            if (tmpB > 255) tmpB = 255;
                            src = (tmpR << 16) | (tmpG << 8) | tmpB;
                        }
                    }

                    // mask set
                    if (_renderSetMask) {
                        src |= 0x01000000;
                    }
                    videoRAM[i] = src;
                }
                i += dpMajor;
                line -= minor;
                if (line < 0) {
                    line += count;
                    i += dpMinor;
                }

                if (_renderGouraud) {
                    r += dr;
                    g += dg;
                    b += db;
                }

            }

            // --- end span

        }
    }

    private static class GPUDRouter {
        public static int invoke(int[] data, int offset, int size) {
            if (dumpGPUD) {
                //System.out.println("GPUD Invoke "+MiscUtil.toHex(m_gpudCommand,8));
            }

            if (m_gpudCommand > 2 && m_gpudCommand < 0x80) {
                // todo; dirty based on clip rect
            }

            switch (m_gpudCommand & 0xe0) {
                case 0x20:
                    // texture or semi; we should make sure were 16bit
                    if (0 != (m_gpudCommand & 6)) setVRAMFormat(false);
                    switch (m_gpudCommand) {
                        case 0x20:
                            return gpud3PointFlat(data, offset, size);
                        case 0x21:
                            return gpud3PointFlat(data, offset, size);
                        case 0x22:
                            return gpud3PointFlatSemi(data, offset, size);
                        case 0x23:
                            return gpud3PointFlatSemi(data, offset, size);

                        case 0x24:
                            return gpud3PointTexture(data, offset, size);
                        case 0x25:
                            return gpud3PointTexture(data, offset, size);
                        case 0x26:
                            return gpud3PointTextureSemi(data, offset, size);
                        case 0x27:
                            return gpud3PointTextureSemi(data, offset, size);

                        case 0x28:
                            return gpud4PointFlat(data, offset, size);
                        case 0x29:
                            return gpud4PointFlat(data, offset, size);
                        case 0x2a:
                            return gpud4PointFlatSemi(data, offset, size);
                        case 0x2b:
                            return gpud4PointFlatSemi(data, offset, size);

                        case 0x2c:
                            return gpud4PointTexture(data, offset, size);
                        case 0x2d:
                            return gpud4PointTexture(data, offset, size);
                        case 0x2e:
                            return gpud4PointTextureSemi(data, offset, size);
                        case 0x2f:
                            return gpud4PointTextureSemi(data, offset, size);

                        case 0x30:
                            return gpud3PointGouraud(data, offset, size);
                        case 0x31:
                            return gpud3PointGouraud(data, offset, size);
                        case 0x32:
                            return gpud3PointGouraudSemi(data, offset, size);
                        case 0x33:
                            return gpud3PointGouraudSemi(data, offset, size);

                        case 0x34:
                            return gpud3PointTextureGouraud(data, offset, size);
                        case 0x35:
                            return gpud3PointTextureGouraud(data, offset, size);
                        case 0x36:
                            return gpud3PointTextureGouraudSemi(data, offset, size);
                        case 0x37:
                            return gpud3PointTextureGouraudSemi(data, offset, size);

                        case 0x38:
                            return gpud4PointGouraud(data, offset, size);
                        case 0x39:
                            return gpud4PointGouraud(data, offset, size);
                        case 0x3a:
                            return gpud4PointGouraudSemi(data, offset, size);
                        case 0x3b:
                            return gpud4PointGouraudSemi(data, offset, size);

                        case 0x3c:
                            return gpud4PointTextureGouraud(data, offset, size);
                        case 0x3d:
                            return gpud4PointTextureGouraud(data, offset, size);
                        case 0x3e:
                            return gpud4PointTextureGouraudSemi(data, offset, size);
                        case 0x3f:
                            return gpud4PointTextureGouraudSemi(data, offset, size);
                    }
                    break;
                case 0x40:
                    if (0 == (m_gpudCommand & 2)) {
                        // nom-semi
                        if (0 == (m_gpudCommand & 8)) {
                            // non-poly
                            if (0 == (m_gpudCommand & 0x10)) {
                                // non-gouraud
                                return gpudLine(data, offset, size);
                            } else {
                                // gouraud
                                return gpudLineGouraud(data, offset, size);
                            }
                        } else {
                            // poly
                            if (0 == (m_gpudCommand & 0x10)) {
                                // non-gouraud
                                return gpudPolyLine(data, offset, size);
                            } else {
                                // gouraud
                                return gpudPolyLineGouraud(data, offset, size);
                            }
                        }
                    } else {
                        // semi
                        setVRAMFormat(false);
                        if (0 == (m_gpudCommand & 8)) {
                            // non-poly
                            if (0 == (m_gpudCommand & 0x10)) {
                                // non-gouraud
                                return gpudLineSemi(data, offset, size);
                            } else {
                                // gouraud
                                return gpudLineGouraudSemi(data, offset, size);
                            }
                        } else {
                            // poly
                            if (0 == (m_gpudCommand & 0x10)) {
                                // non-gouraud
                                return gpudPolyLineSemi(data, offset, size);
                            } else {
                                // gouraud
                                return gpudPolyLineGouraudSemi(data, offset, size);
                            }
                        }
                    }
                case 0x60:
                    // texture or semi; we should make sure were 16bit
                    if (0 != (m_gpudCommand & 6)) setVRAMFormat(false);
                    switch (m_gpudCommand) {
                        case 0x60:
                            return gpudRectangle(data, offset, size);
                        case 0x61:
                            return gpudRectangle(data, offset, size);
                        case 0x62:
                            return gpudRectangleSemi(data, offset, size);
                        case 0x63:
                            return gpudRectangleSemi(data, offset, size);

                        case 0x64:
                            return gpudSprite(data, offset, size);
                        case 0x65:
                            return gpudSprite(data, offset, size);
                        case 0x66:
                            return gpudSpriteSemi(data, offset, size);
                        case 0x67:
                            return gpudSpriteSemi(data, offset, size);

                        case 0x68:
                            return gpudRectangle1x1(data, offset, size);
                        case 0x69:
                            return gpudRectangle1x1(data, offset, size);
                        case 0x6a:
                            return gpudRectangle1x1Semi(data, offset, size);
                        case 0x6b:
                            return gpudRectangle1x1Semi(data, offset, size);

                        case 0x6c:
                            return gpudSprite1x1(data, offset, size);
                        case 0x6d:
                            return gpudSprite1x1(data, offset, size);
                        case 0x6e:
                            return gpudSprite1x1Semi(data, offset, size);
                        case 0x6f:
                            return gpudSprite1x1Semi(data, offset, size);

                        case 0x70:
                            return gpudRectangle8x8(data, offset, size);
                        case 0x71:
                            return gpudRectangle8x8(data, offset, size);
                        case 0x72:
                            return gpudRectangle8x8Semi(data, offset, size);
                        case 0x73:
                            return gpudRectangle8x8Semi(data, offset, size);

                        case 0x74:
                            return gpudSprite8x8(data, offset, size);
                        case 0x75:
                            return gpudSprite8x8(data, offset, size);
                        case 0x76:
                            return gpudSprite8x8Semi(data, offset, size);
                        case 0x77:
                            return gpudSprite8x8Semi(data, offset, size);

                        case 0x78:
                            return gpudRectangle16x16(data, offset, size);
                        case 0x79:
                            return gpudRectangle16x16(data, offset, size);
                        case 0x7a:
                            return gpudRectangle16x16Semi(data, offset, size);
                        case 0x7b:
                            return gpudRectangle16x16Semi(data, offset, size);

                        case 0x7c:
                            return gpudSprite16x16(data, offset, size);
                        case 0x7d:
                            return gpudSprite16x16(data, offset, size);
                        case 0x7e:
                            return gpudSprite16x16Semi(data, offset, size);
                        case 0x7f:
                            return gpudSprite16x16Semi(data, offset, size);
                    }
                    break;
                default:
                    switch (m_gpudCommand) {
                        case 0x01:
                            return gpudCacheFlush(data, offset, size);
                        case 0x02:
                            return gpudClear(data, offset, size);
                        case 0x80:
                            return gpudVRAMtoVRAM(data, offset, size);
                        case 0xa0:
                            return gpudMemToVRAM(data, offset, size);
                        case 0xc0:
                            setVRAMFormat(false);
                            return gpudVRAMToMem(data, offset, size);
                        case 0xe1:
                            return gpudSetDrawMode(data, offset, size);
                        case 0xe2:
                            return gpudSetTextureWindow(data, offset, size);
                        case 0xe3:
                            return gpudSetClipTopLeft(data, offset, size);
                        case 0xe4:
                            return gpudSetClipBottomRight(data, offset, size);
                        case 0xe5:
                            return gpudSetDrawingOffset(data, offset, size);
                        case 0xe6:
                            return gpudSetMaskMode(data, offset, size);
                    }
                    break;
            }
            return 1;
        }
    }

    public static int gpudCacheFlush(int[] data, int offset, int size) {
        //System.out.println("GPUD CacheFlush");
        return 0;
    }

    public static int gpudClear(int[] data, int offset, int size) {

        int x = (data[offset + 1] << 20) >> 20;
        int y = (data[offset + 1] << 4) >> 20;
        int w = data[offset + 2] & 0xffff;
        int h = data[offset + 2] >> 16;
        int r = data[offset] & 0xff;
        int g = (data[offset] >> 8) & 0xff;
        int b = (data[offset] >> 16) & 0xff;

        //LOG7P( GPU_COMMAND, "gpudClear (%d,%d,%u,%u), color (%02x,%02x,%02x)\n",x, y, w, h, r, g, b);
        if (dumpGPUD) System.out.println("GPUD clear " + x + "," + y + " " + w + "," + h + " " + colorString(r, g, b));

        if (x < 0) {
            w += x;
            x = 0;
        }
        if (y < 0) {
            h += y;
            y = 0;
        }
        if ((x + w) > 1024) {
            w = 1024 - x;
        }
        if ((y + h) > 512) {
            h = 512 - y;
        }

        int base = x + y * 1024;
        int color = b | (g << 8) | (r << 16);
        for (; h > 0; h--) {
            for (int i = base; i < base + w; i++) {
                videoRAM[i] = color;
            }
            base += 1024;
        }
        return 0;
    }

    public static int gpud3PointFlat(int[] data, int offset, int size) {
        Vertex v0 = m_v0;
        Vertex v1 = m_v1;
        Vertex v2 = m_v2;

        v0.x = (data[offset + 1] << 20) >> 20;
        v0.y = (data[offset + 1] << 4) >> 20;

        v1.x = (data[offset + 2] << 20) >> 20;
        v1.y = (data[offset + 2] << 4) >> 20;

        v2.x = (data[offset + 3] << 20) >> 20;
        v2.y = (data[offset + 3] << 4) >> 20;

        m_polygonInfo.r = data[offset] & 0xff;
        m_polygonInfo.g = (data[offset] >> 8) & 0xff;
        m_polygonInfo.b = (data[offset] >> 16) & 0xff;

        if (dumpGPUD) {
            System.out.println("gpud3PointFlat");
        }

        switch (getMaskModes()) {
            case 0:
                GPUGenerated._T000000.render(m_polygonInfo, v0, v1, v2);
                break;
            case DRAWMODE_SET_MASK:
                GPUGenerated._T000100.render(m_polygonInfo, v0, v1, v2);
                break;
            case DRAWMODE_CHECK_MASK:
                GPUGenerated._T000010.render(m_polygonInfo, v0, v1, v2);
                break;
            default:
                GPUGenerated._T000110.render(m_polygonInfo, v0, v1, v2);
                break;
        }
        return 0;
    }

    public static int gpud3PointFlatSemi(int[] data, int offset, int size) {
        Vertex v0 = m_v0;
        Vertex v1 = m_v1;
        Vertex v2 = m_v2;

        v0.x = (data[offset + 1] << 20) >> 20;
        v0.y = (data[offset + 1] << 4) >> 20;

        v1.x = (data[offset + 2] << 20) >> 20;
        v1.y = (data[offset + 2] << 4) >> 20;

        v2.x = (data[offset + 3] << 20) >> 20;
        v2.y = (data[offset + 3] << 4) >> 20;

        m_polygonInfo.r = data[offset] & 0xff;
        m_polygonInfo.g = (data[offset] >> 8) & 0xff;
        m_polygonInfo.b = (data[offset] >> 16) & 0xff;

        if (dumpGPUD) {
            System.out.println("gpud3PointFlatSemi");
        }
        switch (getMaskModes()) {
            case 0:
                switch (getSemiMode()) {
                    case DRAWMODE_SEMI_5P5:
                        GPUGenerated._T001000.render(m_polygonInfo, v0, v1, v2);
                        break;
                    case DRAWMODE_SEMI_10P10:
                        GPUGenerated._T002000.render(m_polygonInfo, v0, v1, v2);
                        break;
                    case DRAWMODE_SEMI_10M10:
                        GPUGenerated._T003000.render(m_polygonInfo, v0, v1, v2);
                        break;
                    case DRAWMODE_SEMI_10P25:
                        GPUGenerated._T004000.render(m_polygonInfo, v0, v1, v2);
                        break;
                }
                break;
            case DRAWMODE_SET_MASK:
                switch (getSemiMode()) {
                    case DRAWMODE_SEMI_5P5:
                        GPUGenerated._T001100.render(m_polygonInfo, v0, v1, v2);
                        break;
                    case DRAWMODE_SEMI_10P10:
                        GPUGenerated._T002100.render(m_polygonInfo, v0, v1, v2);
                        break;
                    case DRAWMODE_SEMI_10M10:
                        GPUGenerated._T003100.render(m_polygonInfo, v0, v1, v2);
                        break;
                    case DRAWMODE_SEMI_10P25:
                        GPUGenerated._T004100.render(m_polygonInfo, v0, v1, v2);
                        break;
                }
                break;
            case DRAWMODE_CHECK_MASK:
                switch (getSemiMode()) {
                    case DRAWMODE_SEMI_5P5:
                        GPUGenerated._T001010.render(m_polygonInfo, v0, v1, v2);
                        break;
                    case DRAWMODE_SEMI_10P10:
                        GPUGenerated._T002010.render(m_polygonInfo, v0, v1, v2);
                        break;
                    case DRAWMODE_SEMI_10M10:
                        GPUGenerated._T003010.render(m_polygonInfo, v0, v1, v2);
                        break;
                    case DRAWMODE_SEMI_10P25:
                        GPUGenerated._T004010.render(m_polygonInfo, v0, v1, v2);
                        break;
                }
                break;
            default:
                switch (getSemiMode()) {
                    case DRAWMODE_SEMI_5P5:
                        GPUGenerated._T001110.render(m_polygonInfo, v0, v1, v2);
                        break;
                    case DRAWMODE_SEMI_10P10:
                        GPUGenerated._T002110.render(m_polygonInfo, v0, v1, v2);
                        break;
                    case DRAWMODE_SEMI_10M10:
                        GPUGenerated._T003110.render(m_polygonInfo, v0, v1, v2);
                        break;
                    case DRAWMODE_SEMI_10P25:
                        GPUGenerated._T004110.render(m_polygonInfo, v0, v1, v2);
                        break;
                }
                break;
        }
        return 0;
    }

    private static Vertex m_v0 = new Vertex();
    private static Vertex m_v1 = new Vertex();
    private static Vertex m_v2 = new Vertex();
    private static Vertex m_v3 = new Vertex();

    private static void drawModePacket(int packet) {
        drawMode = (drawMode & ~0x1ff) | ((packet >> 16) & 0x1ff);
    }

    private static PolygonRenderInfo m_polygonInfo = new PolygonRenderInfo();
    private static LineRenderInfo m_lineInfo = new LineRenderInfo();

    public static int gpud3PointTexture(int[] data, int offset, int size) {
        Vertex v0 = m_v0;
        Vertex v1 = m_v1;
        Vertex v2 = m_v2;

        v0.x = (data[offset + 1] << 20) >> 20;
        v0.y = (data[offset + 1] << 4) >> 20;
        v0.u = data[offset + 2] & 0xff;
        v0.v = (data[offset + 2] >> 8) & 0xff;

        int cly = (data[offset + 2] >> 22) & 0x1ff;
        int clx = (data[offset + 2] & 0x3f0000) >> 12;
        m_polygonInfo.clut = videoRAM;
        m_polygonInfo.clutOffset = cly * 1024 + clx;


        v1.x = (data[offset + 3] << 20) >> 20;
        v1.y = (data[offset + 3] << 4) >> 20;
        v1.u = data[offset + 4] & 0xff;
        v1.v = (data[offset + 4] >> 8) & 0xff;

        drawModePacket(data[offset + 4]);

        v2.x = (data[offset + 5] << 20) >> 20;
        v2.y = (data[offset + 5] << 4) >> 20;
        v2.u = data[offset + 6] & 0xff;
        v2.v = (data[offset + 6] >> 8) & 0xff;

        if (dumpGPUD) {
            System.out.println("gpud3PointTexture");
        }
        // TODO texturepage
        switch (getTextureMode()) {
            case DRAWMODE_TEXTURE_4BIT:
                if (getPalette4(data[offset])) {
                    switch (getMaskModes()) {
                        case 0:
                            GPUGenerated._T400001.render(m_polygonInfo, v0, v1, v2);
                            break;
                        case DRAWMODE_SET_MASK:
                            GPUGenerated._T400101.render(m_polygonInfo, v0, v1, v2);
                            break;
                        case DRAWMODE_CHECK_MASK:
                            GPUGenerated._T400011.render(m_polygonInfo, v0, v1, v2);
                            break;
                        default:
                            GPUGenerated._T400111.render(m_polygonInfo, v0, v1, v2);
                            break;
                    }
                } else {
                    switch (getMaskModes()) {
                        case 0:
                            GPUGenerated._T400000.render(m_polygonInfo, v0, v1, v2);
                            break;
                        case DRAWMODE_SET_MASK:
                            GPUGenerated._T400100.render(m_polygonInfo, v0, v1, v2);
                            break;
                        case DRAWMODE_CHECK_MASK:
                            GPUGenerated._T400010.render(m_polygonInfo, v0, v1, v2);
                            break;
                        default:
                            GPUGenerated._T400110.render(m_polygonInfo, v0, v1, v2);
                            break;
                    }
                }
                break;
            case DRAWMODE_TEXTURE_8BIT:
                if (getPalette8(data[offset])) {
                    switch (getMaskModes()) {
                        case 0:
                            GPUGenerated._T800001.render(m_polygonInfo, v0, v1, v2);
                            break;
                        case DRAWMODE_SET_MASK:
                            GPUGenerated._T800101.render(m_polygonInfo, v0, v1, v2);
                            break;
                        case DRAWMODE_CHECK_MASK:
                            GPUGenerated._T800011.render(m_polygonInfo, v0, v1, v2);
                            break;
                        default:
                            GPUGenerated._T800111.render(m_polygonInfo, v0, v1, v2);
                            break;
                    }
                } else {
                    switch (getMaskModes()) {
                        case 0:
                            GPUGenerated._T800000.render(m_polygonInfo, v0, v1, v2);
                            break;
                        case DRAWMODE_SET_MASK:
                            GPUGenerated._T800100.render(m_polygonInfo, v0, v1, v2);
                            break;
                        case DRAWMODE_CHECK_MASK:
                            GPUGenerated._T800010.render(m_polygonInfo, v0, v1, v2);
                            break;
                        default:
                            GPUGenerated._T800110.render(m_polygonInfo, v0, v1, v2);
                            break;
                    }
                }
                break;
            case DRAWMODE_TEXTURE_16BIT: {
                boolean nobreg = false;
                if (((data[offset] & 0x01000000) != 0) || ((data[offset] & 0xffffff) == 0x808080)) {
                    nobreg = true;
                } else {
                    missing("16 bit breg gpud3PointTexture");
                }
                // todo breg
                switch (getMaskModes()) {
                    case 0:
                        GPUGenerated._T600000.render(m_polygonInfo, v0, v1, v2);
                        break;
                    case DRAWMODE_SET_MASK:
                        GPUGenerated._T600100.render(m_polygonInfo, v0, v1, v2);
                        break;
                    case DRAWMODE_CHECK_MASK:
                        GPUGenerated._T600010.render(m_polygonInfo, v0, v1, v2);
                        break;
                    default:
                        GPUGenerated._T600110.render(m_polygonInfo, v0, v1, v2);
                        break;
                }
                break;
            }
            case DRAWMODE_TEXTURE_4BITW:
                if (getPalette4(data[offset])) {
                    switch (getMaskModes()) {
                        case 0:
                            GPUGenerated._T500001.render(m_polygonInfo, v0, v1, v2);
                            break;
                        case DRAWMODE_SET_MASK:
                            GPUGenerated._T500101.render(m_polygonInfo, v0, v1, v2);
                            break;
                        case DRAWMODE_CHECK_MASK:
                            GPUGenerated._T500011.render(m_polygonInfo, v0, v1, v2);
                            break;
                        default:
                            GPUGenerated._T500111.render(m_polygonInfo, v0, v1, v2);
                            break;
                    }
                } else {
                    switch (getMaskModes()) {
                        case 0:
                            GPUGenerated._T500000.render(m_polygonInfo, v0, v1, v2);
                            break;
                        case DRAWMODE_SET_MASK:
                            GPUGenerated._T500100.render(m_polygonInfo, v0, v1, v2);
                            break;
                        case DRAWMODE_CHECK_MASK:
                            GPUGenerated._T500010.render(m_polygonInfo, v0, v1, v2);
                            break;
                        default:
                            GPUGenerated._T500110.render(m_polygonInfo, v0, v1, v2);
                            break;
                    }
                }
                break;
            case DRAWMODE_TEXTURE_8BITW:
                if (getPalette8(data[offset])) {
                    switch (getMaskModes()) {
                        case 0:
                            GPUGenerated._T900001.render(m_polygonInfo, v0, v1, v2);
                            break;
                        case DRAWMODE_SET_MASK:
                            GPUGenerated._T900101.render(m_polygonInfo, v0, v1, v2);
                            break;
                        case DRAWMODE_CHECK_MASK:
                            GPUGenerated._T900011.render(m_polygonInfo, v0, v1, v2);
                            break;
                        default:
                            GPUGenerated._T900111.render(m_polygonInfo, v0, v1, v2);
                            break;
                    }
                } else {
                    switch (getMaskModes()) {
                        case 0:
                            GPUGenerated._T900000.render(m_polygonInfo, v0, v1, v2);
                            break;
                        case DRAWMODE_SET_MASK:
                            GPUGenerated._T900100.render(m_polygonInfo, v0, v1, v2);
                            break;
                        case DRAWMODE_CHECK_MASK:
                            GPUGenerated._T900010.render(m_polygonInfo, v0, v1, v2);
                            break;
                        default:
                            GPUGenerated._T900110.render(m_polygonInfo, v0, v1, v2);
                            break;
                    }
                }
                break;
            case DRAWMODE_TEXTURE_16BITW: {
                boolean nobreg = false;
                if (((data[offset] & 0x01000000) != 0) || ((data[offset] & 0xffffff) == 0x808080)) {
                    nobreg = true;
                } else {
                    missing("16 bit breg gpud3PointTexture");
                }
                // todo breg
                switch (getMaskModes()) {
                    case 0:
                        GPUGenerated._T700000.render(m_polygonInfo, v0, v1, v2);
                        break;
                    case DRAWMODE_SET_MASK:
                        GPUGenerated._T700100.render(m_polygonInfo, v0, v1, v2);
                        break;
                    case DRAWMODE_CHECK_MASK:
                        GPUGenerated._T700010.render(m_polygonInfo, v0, v1, v2);
                        break;
                    default:
                        GPUGenerated._T700110.render(m_polygonInfo, v0, v1, v2);
                        break;
                }
            }
        }
        return 0;
    }

    static int[] m_TempPalette = new int[256];

    // todo check for cached palette
    public static boolean getPalette4(int val) {
        int[] src = m_polygonInfo.clut;
        int srcIndex = m_polygonInfo.clutOffset;
        int first = src[srcIndex];

        // no breg required
        if ((val & 0x01000000) != 0 || (val & 0xffffff) == 0x808080) {
            if (0 != (first & PIXEL_SOLID_CLUT_CHECKED)) {
                return (first & PIXEL_SOLID_CLUT) != 0;
            }
            boolean solid = true;
            for (int i = 0; i < 16; i++) {
                int current = src[srcIndex + i];
                if (0 == (current & 0x1ffffff)) {
                    solid = false;
                    break;
                }
            }
//            System.out.println("checking solid 16 for "+MiscUtil.toHex( srcIndex, 5)+" "+solid);
            if (solid) {
                src[srcIndex] |= PIXEL_SOLID_CLUT | PIXEL_SOLID_CLUT_CHECKED;
            } else {
                src[srcIndex] = (src[srcIndex] & ~PIXEL_SOLID_CLUT) | PIXEL_SOLID_CLUT_CHECKED;
            }
            return solid;
        }
        //if (0==(first&PIXEL_BREG_CLUT)) {
        //    System.out.println("BREG CLUT 16 "+MiscUtil.toHex( srcIndex, 5));
        //    src[srcIndex]|=PIXEL_BREG_CLUT;
        //}

        int rmul = (val & 0xff);
        int gmul = ((val >> 8) & 0xff);
        int bmul = ((val >> 16) & 0xff);
        int[] dest = m_TempPalette;
        boolean solid = true;
        for (int i = 0; i < 16; i++) {
            int current = src[srcIndex + i];
            int b = (current & 0xff);
            int g = ((current >> 8) & 0xff);
            int r = ((current >> 16) & 0xff);
            // we mustn't change pixel to a zero unless it was zero to configure with
            int mask = ((current & 0xffffff) == 0) ? 0 : 1;
            r = (r * rmul) >> 7;
            if (r > 255) r = 255;
            g = (g * gmul) >> 7;
            if (g > 255) g = 255;
            b = (b * bmul) >> 7;
            if (b > 255) b = 255;
            dest[i] = (current & 0x01000000) | (r << 16) | (g << 8) | b | mask;
            if (dest[i] == 0) {
                solid = false;
            }
        }
        m_polygonInfo.clut = dest;
        m_polygonInfo.clutOffset = 0;
        return solid;
    }

    // todo check for cached palette
    public static boolean getPalette8(int val) {
        int[] src = m_polygonInfo.clut;
        int srcIndex = m_polygonInfo.clutOffset;
        int first = src[srcIndex];
        // no breg required
        if ((val & 0x01000000) != 0 || (val & 0xffffff) == 0x808080) {
            if (0 != (first & PIXEL_SOLID_CLUT_CHECKED)) {
                return (first & PIXEL_SOLID_CLUT) != 0;
            }
            boolean solid = true;
            for (int i = 0; i < 256; i++) {
                int current = src[srcIndex + i];
                if (0 == (current & 0x1ffffff)) {
                    solid = false;
                    break;
                }
            }
//            System.out.println("checking solid 256 for "+MiscUtil.toHex( srcIndex, 5)+" "+solid);
            if (solid) {
                src[srcIndex] |= PIXEL_SOLID_CLUT | PIXEL_SOLID_CLUT_CHECKED;
            } else {
                src[srcIndex] = (src[srcIndex] & ~PIXEL_SOLID_CLUT) | PIXEL_SOLID_CLUT_CHECKED;
            }
            return solid;
        }
        //if (0==(first&PIXEL_BREG_CLUT)) {
        //    System.out.println("BREG CLUT 256 "+MiscUtil.toHex( srcIndex, 5));
        //    src[srcIndex]|=PIXEL_BREG_CLUT;
        //}

        // todo caching here!
        boolean solid = true;
        int rmul = (val & 0xff);
        int gmul = ((val >> 8) & 0xff);
        int bmul = ((val >> 16) & 0xff);
        int[] dest = m_TempPalette;
        for (int i = 0; i < 256; i++) {
            int current = src[srcIndex + i];
            int b = (current & 0xff);
            int g = ((current >> 8) & 0xff);
            int r = ((current >> 16) & 0xff);
            // we mustn't change pixel to a zero unless it was zero to start with
            int mask = ((current & 0xffffff) == 0) ? 0 : 1;
            r = (r * rmul) >> 7;
            if (r > 255) r = 255;
            g = (g * gmul) >> 7;
            if (g > 255) g = 255;
            b = (b * bmul) >> 7;
            if (b > 255) b = 255;
            dest[i] = (current & 0x01000000) | (r << 16) | (g << 8) | b | mask;
            if (dest[i] == 0) {
                solid = false;
            }
        }
        m_polygonInfo.clut = dest;
        m_polygonInfo.clutOffset = 0;
        return solid;
    }

    public static int gpud3PointTextureSemi(int[] data, int offset, int size) {
        Vertex v0 = m_v0;
        Vertex v1 = m_v1;
        Vertex v2 = m_v2;
        Vertex v3 = m_v3;

        v0.x = (data[offset + 1] << 20) >> 20;
        v0.y = (data[offset + 1] << 4) >> 20;
        v0.u = data[offset + 2] & 0xff;
        v0.v = (data[offset + 2] >> 8) & 0xff;

        int cly = (data[offset + 2] >> 22) & 0x1ff;
        int clx = (data[offset + 2] & 0x3f0000) >> 12;
        m_polygonInfo.clut = videoRAM;
        m_polygonInfo.clutOffset = cly * 1024 + clx;

        v1.x = (data[offset + 3] << 20) >> 20;
        v1.y = (data[offset + 3] << 4) >> 20;
        v1.u = data[offset + 4] & 0xff;
        v1.v = (data[offset + 4] >> 8) & 0xff;

        drawModePacket(data[offset + 4]);

        v2.x = (data[offset + 5] << 20) >> 20;
        v2.y = (data[offset + 5] << 4) >> 20;
        v2.u = data[offset + 6] & 0xff;
        v2.v = (data[offset + 6] >> 8) & 0xff;

        if (dumpGPUD) {
            System.out.println("gpud3PointTextureSemi");
        }
        // TODO texturepage
        switch (getTextureMode()) {
            case DRAWMODE_TEXTURE_4BIT:
                if (getPalette4(data[offset])) {
                    switch (getMaskModes()) {
                        case 0:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T401001.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T402001.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T403001.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T404001.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        case DRAWMODE_SET_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T401101.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T402101.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T403101.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T404101.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        case DRAWMODE_CHECK_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T401011.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T402011.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T403011.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T404011.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        default:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T401111.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T402111.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T403111.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T404111.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                    }
                } else {
                    switch (getMaskModes()) {
                        case 0:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T401000.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T402000.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T403000.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T404000.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        case DRAWMODE_SET_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T401100.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T402100.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T403100.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T404100.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        case DRAWMODE_CHECK_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T401010.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T402010.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T403010.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T404010.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        default:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T401110.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T402110.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T403110.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T404110.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                    }
                }
                break;
            case DRAWMODE_TEXTURE_8BIT:
                if (getPalette8(data[offset])) {
                    switch (getMaskModes()) {
                        case 0:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T801001.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T802001.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T803001.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T804001.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        case DRAWMODE_SET_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T801101.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T802101.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T803101.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T804101.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        case DRAWMODE_CHECK_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T801011.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T802011.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T803011.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T804011.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        default:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T801111.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T802111.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T803111.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T804111.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                    }
                } else {
                    switch (getMaskModes()) {
                        case 0:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T801000.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T802000.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T803000.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T804000.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        case DRAWMODE_SET_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T801100.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T802100.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T803100.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T804100.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        case DRAWMODE_CHECK_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T801010.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T802010.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T803010.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T804010.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        default:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T801110.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T802110.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T803110.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T804110.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                    }
                }
                break;
            case DRAWMODE_TEXTURE_16BIT: {
                boolean nobreg = false;
                if (((data[offset] & 0x01000000) != 0) || ((data[offset] & 0xffffff) == 0x808080)) {
                    nobreg = true;
                } else {
                    missing("16 bit breg gpud3PointTextureSemi");
                }
                // todo breg
                switch (getMaskModes()) {
                    case 0:
                        switch (getSemiMode()) {
                            case DRAWMODE_SEMI_5P5:
                                GPUGenerated._T601000.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10P10:
                                GPUGenerated._T602000.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10M10:
                                GPUGenerated._T603000.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10P25:
                                GPUGenerated._T604000.render(m_polygonInfo, v0, v1, v2);
                                break;
                        }
                        break;
                    case DRAWMODE_SET_MASK:
                        switch (getSemiMode()) {
                            case DRAWMODE_SEMI_5P5:
                                GPUGenerated._T601100.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10P10:
                                GPUGenerated._T602100.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10M10:
                                GPUGenerated._T603100.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10P25:
                                GPUGenerated._T604100.render(m_polygonInfo, v0, v1, v2);
                                break;
                        }
                        break;
                    case DRAWMODE_CHECK_MASK:
                        switch (getSemiMode()) {
                            case DRAWMODE_SEMI_5P5:
                                GPUGenerated._T601010.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10P10:
                                GPUGenerated._T602010.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10M10:
                                GPUGenerated._T603010.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10P25:
                                GPUGenerated._T604010.render(m_polygonInfo, v0, v1, v2);
                                break;
                        }
                        break;
                    default:
                        switch (getSemiMode()) {
                            case DRAWMODE_SEMI_5P5:
                                GPUGenerated._T601110.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10P10:
                                GPUGenerated._T602110.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10M10:
                                GPUGenerated._T603110.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10P25:
                                GPUGenerated._T604110.render(m_polygonInfo, v0, v1, v2);
                                break;
                        }
                        break;
                }
                break;
            }
            case DRAWMODE_TEXTURE_4BITW:
                if (getPalette4(data[offset])) {
                    switch (getMaskModes()) {
                        case 0:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T501001.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T502001.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T503001.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T504001.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        case DRAWMODE_SET_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T501101.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T502101.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T503101.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T504101.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        case DRAWMODE_CHECK_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T501011.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T502011.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T503011.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T504011.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        default:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T501111.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T502111.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T503111.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T504111.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                    }
                } else {
                    switch (getMaskModes()) {
                        case 0:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T501000.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T502000.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T503000.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T504000.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        case DRAWMODE_SET_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T501100.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T502100.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T503100.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T504100.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        case DRAWMODE_CHECK_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T501010.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T502010.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T503010.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T504010.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        default:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T501110.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T502110.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T503110.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T504110.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                    }
                }
                break;
            case DRAWMODE_TEXTURE_8BITW:
                if (getPalette8(data[offset])) {
                    switch (getMaskModes()) {
                        case 0:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T901001.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T902001.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T903001.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T904001.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        case DRAWMODE_SET_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T901101.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T902101.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T903101.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T904101.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        case DRAWMODE_CHECK_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T901011.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T902011.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T903011.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T904011.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        default:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T901111.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T902111.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T903111.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T904111.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                    }
                } else {
                    switch (getMaskModes()) {
                        case 0:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T901000.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T902000.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T903000.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T904000.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        case DRAWMODE_SET_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T901100.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T902100.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T903100.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T904100.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        case DRAWMODE_CHECK_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T901010.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T902010.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T903010.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T904010.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        default:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T901110.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T902110.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T903110.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T904110.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                    }
                }
                break;
            case DRAWMODE_TEXTURE_16BITW: {
                boolean nobreg = false;
                if (((data[offset] & 0x01000000) != 0) || ((data[offset] & 0xffffff) == 0x808080)) {
                    nobreg = true;
                } else {
                    missing("16 bit breg gpud3PointTextureSemi");
                }
                // todo breg
                switch (getMaskModes()) {
                    case 0:
                        switch (getSemiMode()) {
                            case DRAWMODE_SEMI_5P5:
                                GPUGenerated._T701000.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10P10:
                                GPUGenerated._T702000.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10M10:
                                GPUGenerated._T703000.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10P25:
                                GPUGenerated._T704000.render(m_polygonInfo, v0, v1, v2);
                                break;
                        }
                        break;
                    case DRAWMODE_SET_MASK:
                        switch (getSemiMode()) {
                            case DRAWMODE_SEMI_5P5:
                                GPUGenerated._T701100.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10P10:
                                GPUGenerated._T702100.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10M10:
                                GPUGenerated._T703100.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10P25:
                                GPUGenerated._T704100.render(m_polygonInfo, v0, v1, v2);
                                break;
                        }
                        break;
                    case DRAWMODE_CHECK_MASK:
                        switch (getSemiMode()) {
                            case DRAWMODE_SEMI_5P5:
                                GPUGenerated._T701010.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10P10:
                                GPUGenerated._T702010.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10M10:
                                GPUGenerated._T703010.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10P25:
                                GPUGenerated._T704010.render(m_polygonInfo, v0, v1, v2);
                                break;
                        }
                        break;
                    default:
                        switch (getSemiMode()) {
                            case DRAWMODE_SEMI_5P5:
                                GPUGenerated._T701110.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10P10:
                                GPUGenerated._T702110.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10M10:
                                GPUGenerated._T703110.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10P25:
                                GPUGenerated._T704110.render(m_polygonInfo, v0, v1, v2);
                                break;
                        }
                        break;
                }
            }
        }
        return 0;
    }

    private static String colorString(int r, int g, int b) {
        return "(" + MiscUtil.toHex(r, 2) + "," + MiscUtil.toHex(g, 2) + "," + MiscUtil.toHex(b, 2) + ")";
    }

    public static int gpud4PointFlat(int[] data, int offset, int size) {
        Vertex v0 = m_v0;
        Vertex v1 = m_v1;
        Vertex v2 = m_v2;
        Vertex v3 = m_v3;

        v0.x = (data[offset + 1] << 20) >> 20;
        v0.y = (data[offset + 1] << 4) >> 20;

        v1.x = (data[offset + 2] << 20) >> 20;
        v1.y = (data[offset + 2] << 4) >> 20;

        v2.x = (data[offset + 3] << 20) >> 20;
        v2.y = (data[offset + 3] << 4) >> 20;

        v3.x = (data[offset + 4] << 20) >> 20;
        v3.y = (data[offset + 4] << 4) >> 20;

        m_polygonInfo.r = data[offset] & 0xff;
        m_polygonInfo.g = (data[offset] >> 8) & 0xff;
        m_polygonInfo.b = (data[offset] >> 16) & 0xff;

        if (dumpGPUD) {
            System.out.println("gpud4PointFlat " + v0.x + "," + v0.y + " " + v1.x + "," + v1.y + " " + v2.x + "," + v2.y + " " + v3.x + "," + v3.y + " " +
                    colorString(m_polygonInfo.r, m_polygonInfo.g, m_polygonInfo.b));
        }

        switch (getMaskModes()) {
            case 0:
                GPUGenerated._Q000000.render(m_polygonInfo, v0, v1, v2, v3);
                break;
            case DRAWMODE_SET_MASK:
                GPUGenerated._Q000100.render(m_polygonInfo, v0, v1, v2, v3);
                break;
            case DRAWMODE_CHECK_MASK:
                GPUGenerated._Q000010.render(m_polygonInfo, v0, v1, v2, v3);
                break;
            default:
                GPUGenerated._Q000110.render(m_polygonInfo, v0, v1, v2, v3);
                break;
        }
        return 0;
    }

    public static int gpud4PointFlatSemi(int[] data, int offset, int size) {
        Vertex v0 = m_v0;
        Vertex v1 = m_v1;
        Vertex v2 = m_v2;
        Vertex v3 = m_v3;

        v0.x = (data[offset + 1] << 20) >> 20;
        v0.y = (data[offset + 1] << 4) >> 20;

        v1.x = (data[offset + 2] << 20) >> 20;
        v1.y = (data[offset + 2] << 4) >> 20;

        v2.x = (data[offset + 3] << 20) >> 20;
        v2.y = (data[offset + 3] << 4) >> 20;

        v3.x = (data[offset + 4] << 20) >> 20;
        v3.y = (data[offset + 4] << 4) >> 20;

        m_polygonInfo.r = data[offset] & 0xff;
        m_polygonInfo.g = (data[offset] >> 8) & 0xff;
        m_polygonInfo.b = (data[offset] >> 16) & 0xff;

        if (dumpGPUD) {
            System.out.println("gpud4PointFlatSemi");
        }
        switch (getMaskModes()) {
            case 0:
                switch (getSemiMode()) {
                    case DRAWMODE_SEMI_5P5:
                        GPUGenerated._Q001000.render(m_polygonInfo, v0, v1, v2, v3);
                        break;
                    case DRAWMODE_SEMI_10P10:
                        GPUGenerated._Q002000.render(m_polygonInfo, v0, v1, v2, v3);
                        break;
                    case DRAWMODE_SEMI_10M10:
                        GPUGenerated._Q003000.render(m_polygonInfo, v0, v1, v2, v3);
                        break;
                    case DRAWMODE_SEMI_10P25:
                        GPUGenerated._Q004000.render(m_polygonInfo, v0, v1, v2, v3);
                        break;
                }
                break;
            case DRAWMODE_SET_MASK:
                switch (getSemiMode()) {
                    case DRAWMODE_SEMI_5P5:
                        GPUGenerated._Q001100.render(m_polygonInfo, v0, v1, v2, v3);
                        break;
                    case DRAWMODE_SEMI_10P10:
                        GPUGenerated._Q002100.render(m_polygonInfo, v0, v1, v2, v3);
                        break;
                    case DRAWMODE_SEMI_10M10:
                        GPUGenerated._Q003100.render(m_polygonInfo, v0, v1, v2, v3);
                        break;
                    case DRAWMODE_SEMI_10P25:
                        GPUGenerated._Q004100.render(m_polygonInfo, v0, v1, v2, v3);
                        break;
                }
                break;
            case DRAWMODE_CHECK_MASK:
                switch (getSemiMode()) {
                    case DRAWMODE_SEMI_5P5:
                        GPUGenerated._Q001010.render(m_polygonInfo, v0, v1, v2, v3);
                        break;
                    case DRAWMODE_SEMI_10P10:
                        GPUGenerated._Q002010.render(m_polygonInfo, v0, v1, v2, v3);
                        break;
                    case DRAWMODE_SEMI_10M10:
                        GPUGenerated._Q003010.render(m_polygonInfo, v0, v1, v2, v3);
                        break;
                    case DRAWMODE_SEMI_10P25:
                        GPUGenerated._Q004010.render(m_polygonInfo, v0, v1, v2, v3);
                        break;
                }
                break;
            default:
                switch (getSemiMode()) {
                    case DRAWMODE_SEMI_5P5:
                        GPUGenerated._Q001110.render(m_polygonInfo, v0, v1, v2, v3);
                        break;
                    case DRAWMODE_SEMI_10P10:
                        GPUGenerated._Q002110.render(m_polygonInfo, v0, v1, v2, v3);
                        break;
                    case DRAWMODE_SEMI_10M10:
                        GPUGenerated._Q003110.render(m_polygonInfo, v0, v1, v2, v3);
                        break;
                    case DRAWMODE_SEMI_10P25:
                        GPUGenerated._Q004110.render(m_polygonInfo, v0, v1, v2, v3);
                        break;
                }
                break;
        }
        return 0;
    }

    public static int gpud4PointTexture(int[] data, int offset, int size) {
        Vertex v0 = m_v0;
        Vertex v1 = m_v1;
        Vertex v2 = m_v2;
        Vertex v3 = m_v3;

        v0.x = (data[offset + 1] << 20) >> 20;
        v0.y = (data[offset + 1] << 4) >> 20;
        v0.u = data[offset + 2] & 0xff;
        v0.v = (data[offset + 2] >> 8) & 0xff;

        int cly = (data[offset + 2] >> 22) & 0x1ff;
        int clx = (data[offset + 2] & 0x3f0000) >> 12;
        m_polygonInfo.clut = videoRAM;
        m_polygonInfo.clutOffset = cly * 1024 + clx;

        v1.x = (data[offset + 3] << 20) >> 20;
        v1.y = (data[offset + 3] << 4) >> 20;
        v1.u = data[offset + 4] & 0xff;
        v1.v = (data[offset + 4] >> 8) & 0xff;

        drawModePacket(data[offset + 4]);

        v2.x = (data[offset + 5] << 20) >> 20;
        v2.y = (data[offset + 5] << 4) >> 20;
        v2.u = data[offset + 6] & 0xff;
        v2.v = (data[offset + 6] >> 8) & 0xff;

        v3.x = (data[offset + 7] << 20) >> 20;
        v3.y = (data[offset + 7] << 4) >> 20;
        v3.u = data[offset + 8] & 0xff;
        v3.v = (data[offset + 8] >> 8) & 0xff;

        if (dumpGPUD) {
            System.out.println("gpud4PointTexture " + v0.x + "," + v0.y + " " + v1.x + "," + v1.y + " " + v2.x + "," + v2.y + " " + v3.x + "," + v3.y + " clut " + clx + "," + cly);
        }
        // TODO texturepage
        switch (getTextureMode()) {
            case DRAWMODE_TEXTURE_4BIT:
                if (getPalette4(data[offset])) {
                    switch (getMaskModes()) {
                        case 0:
                            GPUGenerated._Q400001.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        case DRAWMODE_SET_MASK:
                            GPUGenerated._Q400101.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        case DRAWMODE_CHECK_MASK:
                            GPUGenerated._Q400011.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        default:
                            GPUGenerated._Q400111.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                    }
                } else {
                    switch (getMaskModes()) {
                        case 0:
                            GPUGenerated._Q400000.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        case DRAWMODE_SET_MASK:
                            GPUGenerated._Q400100.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        case DRAWMODE_CHECK_MASK:
                            GPUGenerated._Q400010.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        default:
                            GPUGenerated._Q400110.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                    }
                }
                break;
            case DRAWMODE_TEXTURE_8BIT:
                if (getPalette8(data[offset])) {
                    switch (getMaskModes()) {
                        case 0:
                            GPUGenerated._Q800001.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        case DRAWMODE_SET_MASK:
                            GPUGenerated._Q800101.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        case DRAWMODE_CHECK_MASK:
                            GPUGenerated._Q800011.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        default:
                            GPUGenerated._Q800111.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                    }
                } else {
                    switch (getMaskModes()) {
                        case 0:
                            GPUGenerated._Q800000.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        case DRAWMODE_SET_MASK:
                            GPUGenerated._Q800100.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        case DRAWMODE_CHECK_MASK:
                            GPUGenerated._Q800010.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        default:
                            GPUGenerated._Q800110.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                    }
                }
                break;
            case DRAWMODE_TEXTURE_16BIT: {
                boolean nobreg = false;
                if (((data[offset] & 0x01000000) != 0) || ((data[offset] & 0xffffff) == 0x808080)) {
                    nobreg = true;
                } else {
                    missing("16 bit breg gpud4PointTexture");
                }
                // todo breg
                switch (getMaskModes()) {
                    case 0:
                        GPUGenerated._Q600000.render(m_polygonInfo, v0, v1, v2, v3);
                        break;
                    case DRAWMODE_SET_MASK:
                        GPUGenerated._Q600100.render(m_polygonInfo, v0, v1, v2, v3);
                        break;
                    case DRAWMODE_CHECK_MASK:
                        GPUGenerated._Q600010.render(m_polygonInfo, v0, v1, v2, v3);
                        break;
                    default:
                        GPUGenerated._Q600110.render(m_polygonInfo, v0, v1, v2, v3);
                        break;
                }
                break;
            }
            case DRAWMODE_TEXTURE_4BITW:
                if (getPalette4(data[offset])) {
                    switch (getMaskModes()) {
                        case 0:
                            GPUGenerated._Q500001.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        case DRAWMODE_SET_MASK:
                            GPUGenerated._Q500101.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        case DRAWMODE_CHECK_MASK:
                            GPUGenerated._Q500011.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        default:
                            GPUGenerated._Q500111.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                    }
                } else {
                    switch (getMaskModes()) {
                        case 0:
                            GPUGenerated._Q500000.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        case DRAWMODE_SET_MASK:
                            GPUGenerated._Q500100.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        case DRAWMODE_CHECK_MASK:
                            GPUGenerated._Q500010.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        default:
                            GPUGenerated._Q500110.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                    }
                }
                break;
            case DRAWMODE_TEXTURE_8BITW:
                if (getPalette8(data[offset])) {
                    switch (getMaskModes()) {
                        case 0:
                            GPUGenerated._Q900001.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        case DRAWMODE_SET_MASK:
                            GPUGenerated._Q900101.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        case DRAWMODE_CHECK_MASK:
                            GPUGenerated._Q900011.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        default:
                            GPUGenerated._Q900111.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                    }
                } else {
                    switch (getMaskModes()) {
                        case 0:
                            GPUGenerated._Q900000.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        case DRAWMODE_SET_MASK:
                            GPUGenerated._Q900100.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        case DRAWMODE_CHECK_MASK:
                            GPUGenerated._Q900010.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        default:
                            GPUGenerated._Q900110.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                    }
                }
                break;
            case DRAWMODE_TEXTURE_16BITW: {
                boolean nobreg = false;
                if (((data[offset] & 0x01000000) != 0) || ((data[offset] & 0xffffff) == 0x808080)) {
                    nobreg = true;
                } else {
                    missing("16 bit breg gpud4PointTexture");
                }
                // todo breg
                switch (getMaskModes()) {
                    case 0:
                        GPUGenerated._Q700000.render(m_polygonInfo, v0, v1, v2, v3);
                        break;
                    case DRAWMODE_SET_MASK:
                        GPUGenerated._Q700100.render(m_polygonInfo, v0, v1, v2, v3);
                        break;
                    case DRAWMODE_CHECK_MASK:
                        GPUGenerated._Q700010.render(m_polygonInfo, v0, v1, v2, v3);
                        break;
                    default:
                        GPUGenerated._Q700110.render(m_polygonInfo, v0, v1, v2, v3);
                        break;
                }
            }
        }
        return 0;
    }

    public static int gpud4PointTextureSemi(int[] data, int offset, int size) {
        Vertex v0 = m_v0;
        Vertex v1 = m_v1;
        Vertex v2 = m_v2;
        Vertex v3 = m_v3;

        v0.x = (data[offset + 1] << 20) >> 20;
        v0.y = (data[offset + 1] << 4) >> 20;
        v0.u = data[offset + 2] & 0xff;
        v0.v = (data[offset + 2] >> 8) & 0xff;

        int cly = (data[offset + 2] >> 22) & 0x1ff;
        int clx = (data[offset + 2] & 0x3f0000) >> 12;
        m_polygonInfo.clut = videoRAM;
        m_polygonInfo.clutOffset = cly * 1024 + clx;

        v1.x = (data[offset + 3] << 20) >> 20;
        v1.y = (data[offset + 3] << 4) >> 20;
        v1.u = data[offset + 4] & 0xff;
        v1.v = (data[offset + 4] >> 8) & 0xff;

        drawModePacket(data[offset + 4]);

        v2.x = (data[offset + 5] << 20) >> 20;
        v2.y = (data[offset + 5] << 4) >> 20;
        v2.u = data[offset + 6] & 0xff;
        v2.v = (data[offset + 6] >> 8) & 0xff;

        v3.x = (data[offset + 7] << 20) >> 20;
        v3.y = (data[offset + 7] << 4) >> 20;
        v3.u = data[offset + 8] & 0xff;
        v3.v = (data[offset + 8] >> 8) & 0xff;

        if (dumpGPUD) {
            System.out.println("gpud4PointTextureSemi");
        }
        // TODO texturepage
        switch (getTextureMode()) {
            case DRAWMODE_TEXTURE_4BIT:
                if (getPalette4(data[offset])) {
                    switch (getMaskModes()) {
                        case 0:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q401001.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q402001.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q403001.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q404001.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        case DRAWMODE_SET_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q401101.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q402101.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q403101.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q404101.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        case DRAWMODE_CHECK_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q401011.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q402011.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q403011.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q404011.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        default:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q401111.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q402111.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q403111.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q404111.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                    }
                } else {
                    switch (getMaskModes()) {
                        case 0:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q401000.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q402000.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q403000.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q404000.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        case DRAWMODE_SET_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q401100.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q402100.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q403100.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q404100.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        case DRAWMODE_CHECK_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q401010.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q402010.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q403010.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q404010.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        default:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q401110.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q402110.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q403110.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q404110.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                    }
                }
                break;
            case DRAWMODE_TEXTURE_8BIT:
                if (getPalette8(data[offset])) {
                    switch (getMaskModes()) {
                        case 0:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q801001.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q802001.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q803001.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q804001.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        case DRAWMODE_SET_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q801101.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q802101.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q803101.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q804101.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        case DRAWMODE_CHECK_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q801011.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q802011.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q803011.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q804011.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        default:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q801111.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q802111.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q803111.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q804111.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                    }
                } else {
                    switch (getMaskModes()) {
                        case 0:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q801000.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q802000.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q803000.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q804000.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        case DRAWMODE_SET_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q801100.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q802100.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q803100.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q804100.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        case DRAWMODE_CHECK_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q801010.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q802010.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q803010.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q804010.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        default:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q801110.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q802110.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q803110.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q804110.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                    }
                }
                break;
            case DRAWMODE_TEXTURE_16BIT: {
                boolean nobreg = false;
                if (((data[offset] & 0x01000000) != 0) || ((data[offset] & 0xffffff) == 0x808080)) {
                    nobreg = true;
                } else {
                    missing("16 bit breg gpud4PointTextureSemi");
                }
                // todo breg
                switch (getMaskModes()) {
                    case 0:
                        switch (getSemiMode()) {
                            case DRAWMODE_SEMI_5P5:
                                GPUGenerated._Q601000.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10P10:
                                GPUGenerated._Q602000.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10M10:
                                GPUGenerated._Q603000.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10P25:
                                GPUGenerated._Q604000.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                        }
                        break;
                    case DRAWMODE_SET_MASK:
                        switch (getSemiMode()) {
                            case DRAWMODE_SEMI_5P5:
                                GPUGenerated._Q601100.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10P10:
                                GPUGenerated._Q602100.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10M10:
                                GPUGenerated._Q603100.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10P25:
                                GPUGenerated._Q604100.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                        }
                        break;
                    case DRAWMODE_CHECK_MASK:
                        switch (getSemiMode()) {
                            case DRAWMODE_SEMI_5P5:
                                GPUGenerated._Q601010.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10P10:
                                GPUGenerated._Q602010.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10M10:
                                GPUGenerated._Q603010.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10P25:
                                GPUGenerated._Q604010.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                        }
                        break;
                    default:
                        switch (getSemiMode()) {
                            case DRAWMODE_SEMI_5P5:
                                GPUGenerated._Q601110.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10P10:
                                GPUGenerated._Q602110.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10M10:
                                GPUGenerated._Q603110.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10P25:
                                GPUGenerated._Q604110.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                        }
                        break;
                }
                break;
            }
            case DRAWMODE_TEXTURE_4BITW:
                if (getPalette4(data[offset])) {
                    switch (getMaskModes()) {
                        case 0:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q501001.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q502001.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q503001.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q504001.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        case DRAWMODE_SET_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q501101.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q502101.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q503101.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q504101.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        case DRAWMODE_CHECK_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q501011.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q502011.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q503011.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q504011.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        default:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q501111.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q502111.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q503111.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q504111.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                    }
                } else {
                    switch (getMaskModes()) {
                        case 0:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q501000.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q502000.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q503000.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q504000.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        case DRAWMODE_SET_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q501100.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q502100.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q503100.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q504100.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        case DRAWMODE_CHECK_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q501010.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q502010.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q503010.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q504010.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        default:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q501110.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q502110.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q503110.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q504110.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                    }
                }
                break;
            case DRAWMODE_TEXTURE_8BITW:
                if (getPalette8(data[offset])) {
                    switch (getMaskModes()) {
                        case 0:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q901001.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q902001.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q903001.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q904001.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        case DRAWMODE_SET_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q901101.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q902101.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q903101.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q904101.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        case DRAWMODE_CHECK_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q901011.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q902011.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q903011.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q904011.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        default:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q901111.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q902111.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q903111.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q904111.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                    }
                } else {
                    switch (getMaskModes()) {
                        case 0:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q901000.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q902000.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q903000.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q904000.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        case DRAWMODE_SET_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q901100.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q902100.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q903100.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q904100.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        case DRAWMODE_CHECK_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q901010.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q902010.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q903010.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q904010.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        default:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q901110.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q902110.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q903110.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q904110.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                    }
                }
                break;
            case DRAWMODE_TEXTURE_16BITW: {
                boolean nobreg = false;
                if (((data[offset] & 0x01000000) != 0) || ((data[offset] & 0xffffff) == 0x808080)) {
                    nobreg = true;
                } else {
                    missing("16 bit breg gpud4PointTextureSemi");
                }
                // todo breg
                switch (getMaskModes()) {
                    case 0:
                        switch (getSemiMode()) {
                            case DRAWMODE_SEMI_5P5:
                                GPUGenerated._Q701000.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10P10:
                                GPUGenerated._Q702000.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10M10:
                                GPUGenerated._Q703000.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10P25:
                                GPUGenerated._Q704000.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                        }
                        break;
                    case DRAWMODE_SET_MASK:
                        switch (getSemiMode()) {
                            case DRAWMODE_SEMI_5P5:
                                GPUGenerated._Q701100.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10P10:
                                GPUGenerated._Q702100.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10M10:
                                GPUGenerated._Q703100.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10P25:
                                GPUGenerated._Q704100.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                        }
                        break;
                    case DRAWMODE_CHECK_MASK:
                        switch (getSemiMode()) {
                            case DRAWMODE_SEMI_5P5:
                                GPUGenerated._Q701010.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10P10:
                                GPUGenerated._Q702010.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10M10:
                                GPUGenerated._Q703010.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10P25:
                                GPUGenerated._Q704010.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                        }
                        break;
                    default:
                        switch (getSemiMode()) {
                            case DRAWMODE_SEMI_5P5:
                                GPUGenerated._Q701110.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10P10:
                                GPUGenerated._Q702110.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10M10:
                                GPUGenerated._Q703110.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10P25:
                                GPUGenerated._Q704110.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                        }
                        break;
                }
            }
        }
        return 0;
    }

    public static int gpud3PointGouraud(int[] data, int offset, int size) {
        Vertex v0 = m_v0;
        Vertex v1 = m_v1;
        Vertex v2 = m_v2;

        v0.x = (data[offset + 1] << 20) >> 20;
        v0.y = (data[offset + 1] << 4) >> 20;

        v0.r = data[offset] & 0xff;
        v0.g = (data[offset] >> 8) & 0xff;
        v0.b = (data[offset] >> 16) & 0xff;

        v1.x = (data[offset + 3] << 20) >> 20;
        v1.y = (data[offset + 3] << 4) >> 20;

        v1.r = data[offset + 2] & 0xff;
        v1.g = (data[offset + 2] >> 8) & 0xff;
        v1.b = (data[offset + 2] >> 16) & 0xff;

        v2.x = (data[offset + 5] << 20) >> 20;
        v2.y = (data[offset + 5] << 4) >> 20;

        v2.r = data[offset + 4] & 0xff;
        v2.g = (data[offset + 4] >> 8) & 0xff;
        v2.b = (data[offset + 4] >> 16) & 0xff;

        //System.out.println("("+v0.r+","+v0.g+","+v0.b+") ("+v1.r+","+v1.g+","+v1.b+") ("+v2.r+","+v2.g+","+v2.b+")");
        if (dumpGPUD) {
            System.out.println("gpud3PointGouraud");
        }
        switch (getMaskModes()) {
            case 0:
                GPUGenerated._T010001.render(m_polygonInfo, v0, v1, v2);
                break;
            case DRAWMODE_SET_MASK:
                GPUGenerated._T010101.render(m_polygonInfo, v0, v1, v2);
                break;
            case DRAWMODE_CHECK_MASK:
                GPUGenerated._T010011.render(m_polygonInfo, v0, v1, v2);
                break;
            default:
                GPUGenerated._T010111.render(m_polygonInfo, v0, v1, v2);
                break;
        }
        return 0;
    }

    public static int gpud3PointGouraudSemi(int[] data, int offset, int size) {
        Vertex v0 = m_v0;
        Vertex v1 = m_v1;
        Vertex v2 = m_v2;

        v0.r = data[offset] & 0xff;
        v0.g = (data[offset] >> 8) & 0xff;
        v0.b = (data[offset] >> 16) & 0xff;
        v0.x = (data[offset + 1] << 20) >> 20;
        v0.y = (data[offset + 1] << 4) >> 20;

        v1.r = data[offset + 2] & 0xff;
        v1.g = (data[offset + 2] >> 8) & 0xff;
        v1.b = (data[offset + 2] >> 16) & 0xff;
        v1.x = (data[offset + 3] << 20) >> 20;
        v1.y = (data[offset + 3] << 4) >> 20;

        v2.r = data[offset + 4] & 0xff;
        v2.g = (data[offset + 4] >> 8) & 0xff;
        v2.b = (data[offset + 4] >> 16) & 0xff;
        v2.x = (data[offset + 5] << 20) >> 20;
        v2.y = (data[offset + 5] << 4) >> 20;

        if (dumpGPUD) {
            System.out.println("gpud3PointGouraudSemi");
        }
        // todo lots
        switch (getSemiMode()) {
            case DRAWMODE_SEMI_5P5:
                GPUGenerated._T011000.render(m_polygonInfo, v0, v1, v2);
                break;
            case DRAWMODE_SEMI_10P10:
                GPUGenerated._T012000.render(m_polygonInfo, v0, v1, v2);
                break;
            case DRAWMODE_SEMI_10M10:
                GPUGenerated._T013000.render(m_polygonInfo, v0, v1, v2);
                break;
            case DRAWMODE_SEMI_10P25:
                GPUGenerated._T014000.render(m_polygonInfo, v0, v1, v2);
                break;
        }
        return 0;
    }

    public static int gpud3PointTextureGouraud(int[] data, int offset, int size) {
        Vertex v0 = m_v0;
        Vertex v1 = m_v1;
        Vertex v2 = m_v2;

        v0.x = (data[offset + 1] << 20) >> 20;
        v0.y = (data[offset + 1] << 4) >> 20;
        v0.u = data[offset + 2] & 0xff;
        v0.v = (data[offset + 2] >> 8) & 0xff;
        v0.r = data[offset] & 0xff;
        v0.g = (data[offset] >> 8) & 0xff;
        v0.b = (data[offset] >> 16) & 0xff;

        int cly = (data[offset + 2] >> 22) & 0x1ff;
        int clx = (data[offset + 2] & 0x3f0000) >> 12;
        m_polygonInfo.clut = videoRAM;
        m_polygonInfo.clutOffset = cly * 1024 + clx;

        v1.x = (data[offset + 4] << 20) >> 20;
        v1.y = (data[offset + 4] << 4) >> 20;
        v1.u = data[offset + 5] & 0xff;
        v1.v = (data[offset + 5] >> 8) & 0xff;
        v1.r = data[offset + 3] & 0xff;
        v1.g = (data[offset + 3] >> 8) & 0xff;
        v1.b = (data[offset + 3] >> 16) & 0xff;

        drawModePacket(data[offset + 5]);

        v2.x = (data[offset + 7] << 20) >> 20;
        v2.y = (data[offset + 7] << 4) >> 20;
        v2.u = data[offset + 8] & 0xff;
        v2.v = (data[offset + 8] >> 8) & 0xff;
        v2.r = data[offset + 6] & 0xff;
        v2.g = (data[offset + 6] >> 8) & 0xff;
        v2.b = (data[offset + 6] >> 16) & 0xff;

        if (dumpGPUD) {
            System.out.println("gpud3PointTextureGouraud");
        }
        // TODO texturepage
        switch (getTextureMode()) {
            case DRAWMODE_TEXTURE_4BIT:
                if (getPalette4(data[offset] | 0x01000000)) {
                    switch (getMaskModes()) {
                        case 0:
                            GPUGenerated._T410001.render(m_polygonInfo, v0, v1, v2);
                            break;
                        case DRAWMODE_SET_MASK:
                            GPUGenerated._T410101.render(m_polygonInfo, v0, v1, v2);
                            break;
                        case DRAWMODE_CHECK_MASK:
                            GPUGenerated._T410011.render(m_polygonInfo, v0, v1, v2);
                            break;
                        default:
                            GPUGenerated._T410111.render(m_polygonInfo, v0, v1, v2);
                            break;
                    }
                } else {
                    switch (getMaskModes()) {
                        case 0:
                            GPUGenerated._T410000.render(m_polygonInfo, v0, v1, v2);
                            break;
                        case DRAWMODE_SET_MASK:
                            GPUGenerated._T410100.render(m_polygonInfo, v0, v1, v2);
                            break;
                        case DRAWMODE_CHECK_MASK:
                            GPUGenerated._T410010.render(m_polygonInfo, v0, v1, v2);
                            break;
                        default:
                            GPUGenerated._T410110.render(m_polygonInfo, v0, v1, v2);
                            break;
                    }
                }
                break;
            case DRAWMODE_TEXTURE_8BIT:
                if (getPalette8(data[offset] | 0x01000000)) {
                    switch (getMaskModes()) {
                        case 0:
                            GPUGenerated._T810001.render(m_polygonInfo, v0, v1, v2);
                            break;
                        case DRAWMODE_SET_MASK:
                            GPUGenerated._T810101.render(m_polygonInfo, v0, v1, v2);
                            break;
                        case DRAWMODE_CHECK_MASK:
                            GPUGenerated._T810011.render(m_polygonInfo, v0, v1, v2);
                            break;
                        default:
                            GPUGenerated._T810111.render(m_polygonInfo, v0, v1, v2);
                            break;
                    }
                } else {
                    switch (getMaskModes()) {
                        case 0:
                            GPUGenerated._T810000.render(m_polygonInfo, v0, v1, v2);
                            break;
                        case DRAWMODE_SET_MASK:
                            GPUGenerated._T810100.render(m_polygonInfo, v0, v1, v2);
                            break;
                        case DRAWMODE_CHECK_MASK:
                            GPUGenerated._T810010.render(m_polygonInfo, v0, v1, v2);
                            break;
                        default:
                            GPUGenerated._T810110.render(m_polygonInfo, v0, v1, v2);
                            break;
                    }
                }
                break;
            case DRAWMODE_TEXTURE_16BIT:
                switch (getMaskModes()) {
                    case 0:
                        GPUGenerated._T610000.render(m_polygonInfo, v0, v1, v2);
                        break;
                    case DRAWMODE_SET_MASK:
                        GPUGenerated._T610100.render(m_polygonInfo, v0, v1, v2);
                        break;
                    case DRAWMODE_CHECK_MASK:
                        GPUGenerated._T610010.render(m_polygonInfo, v0, v1, v2);
                        break;
                    default:
                        GPUGenerated._T610110.render(m_polygonInfo, v0, v1, v2);
                        break;
                }
                break;
            case DRAWMODE_TEXTURE_4BITW:
                if (getPalette4(data[offset] | 0x01000000)) {
                    switch (getMaskModes()) {
                        case 0:
                            GPUGenerated._T510001.render(m_polygonInfo, v0, v1, v2);
                            break;
                        case DRAWMODE_SET_MASK:
                            GPUGenerated._T510101.render(m_polygonInfo, v0, v1, v2);
                            break;
                        case DRAWMODE_CHECK_MASK:
                            GPUGenerated._T510011.render(m_polygonInfo, v0, v1, v2);
                            break;
                        default:
                            GPUGenerated._T510111.render(m_polygonInfo, v0, v1, v2);
                            break;
                    }
                } else {
                    switch (getMaskModes()) {
                        case 0:
                            GPUGenerated._T510000.render(m_polygonInfo, v0, v1, v2);
                            break;
                        case DRAWMODE_SET_MASK:
                            GPUGenerated._T510100.render(m_polygonInfo, v0, v1, v2);
                            break;
                        case DRAWMODE_CHECK_MASK:
                            GPUGenerated._T510010.render(m_polygonInfo, v0, v1, v2);
                            break;
                        default:
                            GPUGenerated._T510110.render(m_polygonInfo, v0, v1, v2);
                            break;
                    }
                }
                break;
            case DRAWMODE_TEXTURE_8BITW:
                if (getPalette8(data[offset] | 0x01000000)) {
                    switch (getMaskModes()) {
                        case 0:
                            GPUGenerated._T910001.render(m_polygonInfo, v0, v1, v2);
                            break;
                        case DRAWMODE_SET_MASK:
                            GPUGenerated._T910101.render(m_polygonInfo, v0, v1, v2);
                            break;
                        case DRAWMODE_CHECK_MASK:
                            GPUGenerated._T910011.render(m_polygonInfo, v0, v1, v2);
                            break;
                        default:
                            GPUGenerated._T910111.render(m_polygonInfo, v0, v1, v2);
                            break;
                    }
                } else {
                    switch (getMaskModes()) {
                        case 0:
                            GPUGenerated._T910000.render(m_polygonInfo, v0, v1, v2);
                            break;
                        case DRAWMODE_SET_MASK:
                            GPUGenerated._T910100.render(m_polygonInfo, v0, v1, v2);
                            break;
                        case DRAWMODE_CHECK_MASK:
                            GPUGenerated._T910010.render(m_polygonInfo, v0, v1, v2);
                            break;
                        default:
                            GPUGenerated._T910110.render(m_polygonInfo, v0, v1, v2);
                            break;
                    }
                }
                break;
            case DRAWMODE_TEXTURE_16BITW:
                switch (getMaskModes()) {
                    case 0:
                        GPUGenerated._T710000.render(m_polygonInfo, v0, v1, v2);
                        break;
                    case DRAWMODE_SET_MASK:
                        GPUGenerated._T710100.render(m_polygonInfo, v0, v1, v2);
                        break;
                    case DRAWMODE_CHECK_MASK:
                        GPUGenerated._T710010.render(m_polygonInfo, v0, v1, v2);
                        break;
                    default:
                        GPUGenerated._T710110.render(m_polygonInfo, v0, v1, v2);
                        break;
                }
        }
        return 0;
    }

    public static int gpud3PointTextureGouraudSemi(int[] data, int offset, int size) {
        Vertex v0 = m_v0;
        Vertex v1 = m_v1;
        Vertex v2 = m_v2;

        v0.x = (data[offset + 1] << 20) >> 20;
        v0.y = (data[offset + 1] << 4) >> 20;
        v0.u = data[offset + 2] & 0xff;
        v0.v = (data[offset + 2] >> 8) & 0xff;
        v0.r = data[offset] & 0xff;
        v0.g = (data[offset] >> 8) & 0xff;
        v0.b = (data[offset] >> 16) & 0xff;

        int cly = (data[offset + 2] >> 22) & 0x1ff;
        int clx = (data[offset + 2] & 0x3f0000) >> 12;
        m_polygonInfo.clut = videoRAM;
        m_polygonInfo.clutOffset = cly * 1024 + clx;

        v1.x = (data[offset + 4] << 20) >> 20;
        v1.y = (data[offset + 4] << 4) >> 20;
        v1.u = data[offset + 5] & 0xff;
        v1.v = (data[offset + 5] >> 8) & 0xff;
        v1.r = data[offset + 3] & 0xff;
        v1.g = (data[offset + 3] >> 8) & 0xff;
        v1.b = (data[offset + 3] >> 16) & 0xff;

        drawModePacket(data[offset + 5]);

        v2.x = (data[offset + 7] << 20) >> 20;
        v2.y = (data[offset + 7] << 4) >> 20;
        v2.u = data[offset + 8] & 0xff;
        v2.v = (data[offset + 8] >> 8) & 0xff;
        v2.r = data[offset + 6] & 0xff;
        v2.g = (data[offset + 6] >> 8) & 0xff;
        v2.b = (data[offset + 6] >> 16) & 0xff;

        if (dumpGPUD) {
            System.out.println("gpud3PointTextureGouraudSemi");
        }
        // TODO texturepage
        switch (getTextureMode()) {
            case DRAWMODE_TEXTURE_4BIT:
                if (getPalette4(data[offset] | 0x01000000)) {
                    switch (getMaskModes()) {
                        case 0:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T411001.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T412001.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T413001.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T414001.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        case DRAWMODE_SET_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T411101.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T412101.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T413101.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T414101.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        case DRAWMODE_CHECK_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T411011.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T412011.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T413011.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T414011.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        default:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T411111.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T412111.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T413111.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T414111.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                    }
                } else {
                    switch (getMaskModes()) {
                        case 0:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T411000.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T412000.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T413000.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T414000.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        case DRAWMODE_SET_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T411100.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T412100.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T413100.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T414100.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        case DRAWMODE_CHECK_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T411010.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T412010.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T413010.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T414010.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        default:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T411110.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T412110.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T413110.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T414110.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                    }
                }
                break;
            case DRAWMODE_TEXTURE_8BIT:
                if (getPalette8(data[offset] | 0x01000000)) {
                    switch (getMaskModes()) {
                        case 0:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T811001.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T812001.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T813001.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T814001.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        case DRAWMODE_SET_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T811101.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T812101.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T813101.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T814101.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        case DRAWMODE_CHECK_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T811011.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T812011.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T813011.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T814011.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        default:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T811111.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T812111.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T813111.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T814111.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                    }
                } else {
                    switch (getMaskModes()) {
                        case 0:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T811000.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T812000.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T813000.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T814000.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        case DRAWMODE_SET_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T811100.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T812100.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T813100.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T814100.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        case DRAWMODE_CHECK_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T811010.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T812010.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T813010.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T814010.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        default:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T811110.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T812110.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T813110.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T814110.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                    }
                }
                break;
            case DRAWMODE_TEXTURE_16BIT:
                switch (getMaskModes()) {
                    case 0:
                        switch (getSemiMode()) {
                            case DRAWMODE_SEMI_5P5:
                                GPUGenerated._T611000.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10P10:
                                GPUGenerated._T612000.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10M10:
                                GPUGenerated._T613000.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10P25:
                                GPUGenerated._T614000.render(m_polygonInfo, v0, v1, v2);
                                break;
                        }
                        break;
                    case DRAWMODE_SET_MASK:
                        switch (getSemiMode()) {
                            case DRAWMODE_SEMI_5P5:
                                GPUGenerated._T611100.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10P10:
                                GPUGenerated._T612100.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10M10:
                                GPUGenerated._T613100.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10P25:
                                GPUGenerated._T614100.render(m_polygonInfo, v0, v1, v2);
                                break;
                        }
                        break;
                    case DRAWMODE_CHECK_MASK:
                        switch (getSemiMode()) {
                            case DRAWMODE_SEMI_5P5:
                                GPUGenerated._T611010.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10P10:
                                GPUGenerated._T612010.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10M10:
                                GPUGenerated._T613010.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10P25:
                                GPUGenerated._T614010.render(m_polygonInfo, v0, v1, v2);
                                break;
                        }
                        break;
                    default:
                        switch (getSemiMode()) {
                            case DRAWMODE_SEMI_5P5:
                                GPUGenerated._T611110.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10P10:
                                GPUGenerated._T612110.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10M10:
                                GPUGenerated._T613110.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10P25:
                                GPUGenerated._T614110.render(m_polygonInfo, v0, v1, v2);
                                break;
                        }
                        break;
                }
                break;
            case DRAWMODE_TEXTURE_4BITW:
                if (getPalette4(data[offset] | 0x01000000)) {
                    switch (getMaskModes()) {
                        case 0:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T511001.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T512001.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T513001.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T514001.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        case DRAWMODE_SET_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T511101.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T512101.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T513101.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T514101.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        case DRAWMODE_CHECK_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T511011.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T512011.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T513011.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T514011.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        default:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T511111.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T512111.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T513111.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T514111.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                    }
                } else {
                    switch (getMaskModes()) {
                        case 0:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T511000.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T512000.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T513000.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T514000.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        case DRAWMODE_SET_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T511100.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T512100.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T513100.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T514100.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        case DRAWMODE_CHECK_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T511010.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T512010.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T513010.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T514010.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        default:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T511110.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T512110.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T513110.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T514110.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                    }
                }
                break;
            case DRAWMODE_TEXTURE_8BITW:
                if (getPalette8(data[offset] | 0x01000000)) {
                    switch (getMaskModes()) {
                        case 0:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T911001.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T912001.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T913001.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T914001.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        case DRAWMODE_SET_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T911101.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T912101.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T913101.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T914101.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        case DRAWMODE_CHECK_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T911011.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T912011.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T913011.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T914011.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        default:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T911111.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T912111.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T913111.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T914111.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                    }
                } else {
                    switch (getMaskModes()) {
                        case 0:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T911000.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T912000.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T913000.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T914000.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        case DRAWMODE_SET_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T911100.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T912100.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T913100.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T914100.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        case DRAWMODE_CHECK_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T911010.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T912010.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T913010.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T914010.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                        default:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._T911110.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._T912110.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._T913110.render(m_polygonInfo, v0, v1, v2);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._T914110.render(m_polygonInfo, v0, v1, v2);
                                    break;
                            }
                            break;
                    }
                }
                break;
            case DRAWMODE_TEXTURE_16BITW:
                switch (getMaskModes()) {
                    case 0:
                        switch (getSemiMode()) {
                            case DRAWMODE_SEMI_5P5:
                                GPUGenerated._T711000.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10P10:
                                GPUGenerated._T712000.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10M10:
                                GPUGenerated._T713000.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10P25:
                                GPUGenerated._T714000.render(m_polygonInfo, v0, v1, v2);
                                break;
                        }
                        break;
                    case DRAWMODE_SET_MASK:
                        switch (getSemiMode()) {
                            case DRAWMODE_SEMI_5P5:
                                GPUGenerated._T711100.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10P10:
                                GPUGenerated._T712100.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10M10:
                                GPUGenerated._T713100.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10P25:
                                GPUGenerated._T714100.render(m_polygonInfo, v0, v1, v2);
                                break;
                        }
                        break;
                    case DRAWMODE_CHECK_MASK:
                        switch (getSemiMode()) {
                            case DRAWMODE_SEMI_5P5:
                                GPUGenerated._T711010.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10P10:
                                GPUGenerated._T712010.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10M10:
                                GPUGenerated._T713010.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10P25:
                                GPUGenerated._T714010.render(m_polygonInfo, v0, v1, v2);
                                break;
                        }
                        break;
                    default:
                        switch (getSemiMode()) {
                            case DRAWMODE_SEMI_5P5:
                                GPUGenerated._T711110.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10P10:
                                GPUGenerated._T712110.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10M10:
                                GPUGenerated._T713110.render(m_polygonInfo, v0, v1, v2);
                                break;
                            case DRAWMODE_SEMI_10P25:
                                GPUGenerated._T714110.render(m_polygonInfo, v0, v1, v2);
                                break;
                        }
                        break;
                }
        }
        return 0;
    }

    public static int gpud4PointGouraud(int[] data, int offset, int size) {
        Vertex v0 = m_v0;
        Vertex v1 = m_v1;
        Vertex v2 = m_v2;
        Vertex v3 = m_v3;

        v0.r = data[offset] & 0xff;
        v0.g = (data[offset] >> 8) & 0xff;
        v0.b = (data[offset] >> 16) & 0xff;
        v0.x = (data[offset + 1] << 20) >> 20;
        v0.y = (data[offset + 1] << 4) >> 20;

        v1.r = data[offset + 2] & 0xff;
        v1.g = (data[offset + 2] >> 8) & 0xff;
        v1.b = (data[offset + 2] >> 16) & 0xff;
        v1.x = (data[offset + 3] << 20) >> 20;
        v1.y = (data[offset + 3] << 4) >> 20;

        v2.r = data[offset + 4] & 0xff;
        v2.g = (data[offset + 4] >> 8) & 0xff;
        v2.b = (data[offset + 4] >> 16) & 0xff;
        v2.x = (data[offset + 5] << 20) >> 20;
        v2.y = (data[offset + 5] << 4) >> 20;

        v3.r = data[offset + 6] & 0xff;
        v3.g = (data[offset + 6] >> 8) & 0xff;
        v3.b = (data[offset + 6] >> 16) & 0xff;
        v3.x = (data[offset + 7] << 20) >> 20;
        v3.y = (data[offset + 7] << 4) >> 20;

        if (dumpGPUD) {
            System.out.println("gpud4PointGouraud");
        }
        GPUGenerated._Q010000.render(m_polygonInfo, v0, v1, v2, v3);
        return 0;
    }

    public static int gpud4PointGouraudSemi(int[] data, int offset, int size) {
        Vertex v0 = m_v0;
        Vertex v1 = m_v1;
        Vertex v2 = m_v2;
        Vertex v3 = m_v3;

        v0.r = data[offset] & 0xff;
        v0.g = (data[offset] >> 8) & 0xff;
        v0.b = (data[offset] >> 16) & 0xff;
        v0.x = (data[offset + 1] << 20) >> 20;
        v0.y = (data[offset + 1] << 4) >> 20;

        v1.r = data[offset + 2] & 0xff;
        v1.g = (data[offset + 2] >> 8) & 0xff;
        v1.b = (data[offset + 2] >> 16) & 0xff;
        v1.x = (data[offset + 3] << 20) >> 20;
        v1.y = (data[offset + 3] << 4) >> 20;

        v2.r = data[offset + 4] & 0xff;
        v2.g = (data[offset + 4] >> 8) & 0xff;
        v2.b = (data[offset + 4] >> 16) & 0xff;
        v2.x = (data[offset + 5] << 20) >> 20;
        v2.y = (data[offset + 5] << 4) >> 20;

        v3.r = data[offset + 6] & 0xff;
        v3.g = (data[offset + 6] >> 8) & 0xff;
        v3.b = (data[offset + 6] >> 16) & 0xff;
        v3.x = (data[offset + 7] << 20) >> 20;
        v3.y = (data[offset + 7] << 4) >> 20;

        if (dumpGPUD) {
            System.out.println("gpud4PointGouraudSemi");
        }
        // todo lots
        switch (getSemiMode()) {
            case DRAWMODE_SEMI_5P5:
                GPUGenerated._Q011000.render(m_polygonInfo, v0, v1, v2, v3);
                break;
            case DRAWMODE_SEMI_10P10:
                GPUGenerated._Q012000.render(m_polygonInfo, v0, v1, v2, v3);
                break;
            case DRAWMODE_SEMI_10M10:
                GPUGenerated._Q013000.render(m_polygonInfo, v0, v1, v2, v3);
                break;
            case DRAWMODE_SEMI_10P25:
                GPUGenerated._Q014000.render(m_polygonInfo, v0, v1, v2, v3);
                break;
        }
        return 0;
    }

    public static int gpud4PointTextureGouraud(int[] data, int offset, int size) {
        Vertex v0 = m_v0;
        Vertex v1 = m_v1;
        Vertex v2 = m_v2;
        Vertex v3 = m_v3;

        v0.x = (data[offset + 1] << 20) >> 20;
        v0.y = (data[offset + 1] << 4) >> 20;
        v0.u = data[offset + 2] & 0xff;
        v0.v = (data[offset + 2] >> 8) & 0xff;
        v0.r = data[offset] & 0xff;
        v0.g = (data[offset] >> 8) & 0xff;
        v0.b = (data[offset] >> 16) & 0xff;

        int cly = (data[offset + 2] >> 22) & 0x1ff;
        int clx = (data[offset + 2] & 0x3f0000) >> 12;
        m_polygonInfo.clut = videoRAM;
        m_polygonInfo.clutOffset = cly * 1024 + clx;

        v1.x = (data[offset + 4] << 20) >> 20;
        v1.y = (data[offset + 4] << 4) >> 20;
        v1.u = data[offset + 5] & 0xff;
        v1.v = (data[offset + 5] >> 8) & 0xff;
        v1.r = data[offset + 3] & 0xff;
        v1.g = (data[offset + 3] >> 8) & 0xff;
        v1.b = (data[offset + 3] >> 16) & 0xff;

        drawModePacket(data[offset + 5]);

        v2.x = (data[offset + 7] << 20) >> 20;
        v2.y = (data[offset + 7] << 4) >> 20;
        v2.u = data[offset + 8] & 0xff;
        v2.v = (data[offset + 8] >> 8) & 0xff;
        v2.r = data[offset + 6] & 0xff;
        v2.g = (data[offset + 6] >> 8) & 0xff;
        v2.b = (data[offset + 6] >> 16) & 0xff;

        v3.x = (data[offset + 10] << 20) >> 20;
        v3.y = (data[offset + 10] << 4) >> 20;
        v3.u = data[offset + 11] & 0xff;
        v3.v = (data[offset + 11] >> 8) & 0xff;
        v3.r = data[offset + 9] & 0xff;
        v3.g = (data[offset + 9] >> 8) & 0xff;
        v3.b = (data[offset + 9] >> 16) & 0xff;

        if (dumpGPUD) {
            System.out.println("gpud4PointTextureGouraud");
        }
        // TODO texturepage
        switch (getTextureMode()) {
            case DRAWMODE_TEXTURE_4BIT:
                if (getPalette4(data[offset] | 0x01000000)) {
                    switch (getMaskModes()) {
                        case 0:
                            GPUGenerated._Q410001.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        case DRAWMODE_SET_MASK:
                            GPUGenerated._Q410101.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        case DRAWMODE_CHECK_MASK:
                            GPUGenerated._Q410011.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        default:
                            GPUGenerated._Q410111.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                    }
                } else {
                    switch (getMaskModes()) {
                        case 0:
                            GPUGenerated._Q410000.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        case DRAWMODE_SET_MASK:
                            GPUGenerated._Q410100.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        case DRAWMODE_CHECK_MASK:
                            GPUGenerated._Q410010.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        default:
                            GPUGenerated._Q410110.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                    }
                }
                break;
            case DRAWMODE_TEXTURE_8BIT:
                if (getPalette8(data[offset] | 0x01000000)) {
                    switch (getMaskModes()) {
                        case 0:
                            GPUGenerated._Q810001.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        case DRAWMODE_SET_MASK:
                            GPUGenerated._Q810101.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        case DRAWMODE_CHECK_MASK:
                            GPUGenerated._Q810011.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        default:
                            GPUGenerated._Q810111.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                    }
                } else {
                    switch (getMaskModes()) {
                        case 0:
                            GPUGenerated._Q810000.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        case DRAWMODE_SET_MASK:
                            GPUGenerated._Q810100.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        case DRAWMODE_CHECK_MASK:
                            GPUGenerated._Q810010.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        default:
                            GPUGenerated._Q810110.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                    }
                }
                break;
            case DRAWMODE_TEXTURE_16BIT:
                switch (getMaskModes()) {
                    case 0:
                        GPUGenerated._Q610000.render(m_polygonInfo, v0, v1, v2, v3);
                        break;
                    case DRAWMODE_SET_MASK:
                        GPUGenerated._Q610100.render(m_polygonInfo, v0, v1, v2, v3);
                        break;
                    case DRAWMODE_CHECK_MASK:
                        GPUGenerated._Q610010.render(m_polygonInfo, v0, v1, v2, v3);
                        break;
                    default:
                        GPUGenerated._Q610110.render(m_polygonInfo, v0, v1, v2, v3);
                        break;
                }
                break;
            case DRAWMODE_TEXTURE_4BITW:
                if (getPalette4(data[offset] | 0x01000000)) {
                    switch (getMaskModes()) {
                        case 0:
                            GPUGenerated._Q510001.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        case DRAWMODE_SET_MASK:
                            GPUGenerated._Q510101.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        case DRAWMODE_CHECK_MASK:
                            GPUGenerated._Q510011.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        default:
                            GPUGenerated._Q510111.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                    }
                } else {
                    switch (getMaskModes()) {
                        case 0:
                            GPUGenerated._Q510000.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        case DRAWMODE_SET_MASK:
                            GPUGenerated._Q510100.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        case DRAWMODE_CHECK_MASK:
                            GPUGenerated._Q510010.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        default:
                            GPUGenerated._Q510110.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                    }
                }
                break;
            case DRAWMODE_TEXTURE_8BITW:
                if (getPalette8(data[offset] | 0x01000000)) {
                    switch (getMaskModes()) {
                        case 0:
                            GPUGenerated._Q910001.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        case DRAWMODE_SET_MASK:
                            GPUGenerated._Q910101.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        case DRAWMODE_CHECK_MASK:
                            GPUGenerated._Q910011.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        default:
                            GPUGenerated._Q910111.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                    }
                } else {
                    switch (getMaskModes()) {
                        case 0:
                            GPUGenerated._Q910000.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        case DRAWMODE_SET_MASK:
                            GPUGenerated._Q910100.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        case DRAWMODE_CHECK_MASK:
                            GPUGenerated._Q910010.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                        default:
                            GPUGenerated._Q910110.render(m_polygonInfo, v0, v1, v2, v3);
                            break;
                    }
                }
                break;
            case DRAWMODE_TEXTURE_16BITW:
                switch (getMaskModes()) {
                    case 0:
                        GPUGenerated._Q710000.render(m_polygonInfo, v0, v1, v2, v3);
                        break;
                    case DRAWMODE_SET_MASK:
                        GPUGenerated._Q710100.render(m_polygonInfo, v0, v1, v2, v3);
                        break;
                    case DRAWMODE_CHECK_MASK:
                        GPUGenerated._Q710010.render(m_polygonInfo, v0, v1, v2, v3);
                        break;
                    default:
                        GPUGenerated._Q710110.render(m_polygonInfo, v0, v1, v2, v3);
                        break;
                }
        }
        return 0;
    }

    public static int gpud4PointTextureGouraudSemi(int[] data, int offset, int size) {
        Vertex v0 = m_v0;
        Vertex v1 = m_v1;
        Vertex v2 = m_v2;
        Vertex v3 = m_v3;

        v0.x = (data[offset + 1] << 20) >> 20;
        v0.y = (data[offset + 1] << 4) >> 20;
        v0.u = data[offset + 2] & 0xff;
        v0.v = (data[offset + 2] >> 8) & 0xff;
        v0.r = data[offset] & 0xff;
        v0.g = (data[offset] >> 8) & 0xff;
        v0.b = (data[offset] >> 16) & 0xff;

        int cly = (data[offset + 2] >> 22) & 0x1ff;
        int clx = (data[offset + 2] & 0x3f0000) >> 12;
        m_polygonInfo.clut = videoRAM;
        m_polygonInfo.clutOffset = cly * 1024 + clx;

        v1.x = (data[offset + 4] << 20) >> 20;
        v1.y = (data[offset + 4] << 4) >> 20;
        v1.u = data[offset + 5] & 0xff;
        v1.v = (data[offset + 5] >> 8) & 0xff;
        v1.r = data[offset + 3] & 0xff;
        v1.g = (data[offset + 3] >> 8) & 0xff;
        v1.b = (data[offset + 3] >> 16) & 0xff;

        drawModePacket(data[offset + 5]);

        v2.x = (data[offset + 7] << 20) >> 20;
        v2.y = (data[offset + 7] << 4) >> 20;
        v2.u = data[offset + 8] & 0xff;
        v2.v = (data[offset + 8] >> 8) & 0xff;
        v2.r = data[offset + 6] & 0xff;
        v2.g = (data[offset + 6] >> 8) & 0xff;
        v2.b = (data[offset + 6] >> 16) & 0xff;

        v3.x = (data[offset + 10] << 20) >> 20;
        v3.y = (data[offset + 10] << 4) >> 20;
        v3.u = data[offset + 11] & 0xff;
        v3.v = (data[offset + 11] >> 8) & 0xff;
        v3.r = data[offset + 9] & 0xff;
        v3.g = (data[offset + 9] >> 8) & 0xff;
        v3.b = (data[offset + 9] >> 16) & 0xff;

        if (dumpGPUD) {
            System.out.println("gpud4PointTextureGouraudSemi");
        }
        // TODO texturepage
        switch (getTextureMode()) {
            case DRAWMODE_TEXTURE_4BIT:
                if (getPalette4(data[offset] | 0x01000000)) {
                    switch (getMaskModes()) {
                        case 0:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q411001.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q412001.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q413001.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q414001.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        case DRAWMODE_SET_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q411101.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q412101.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q413101.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q414101.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        case DRAWMODE_CHECK_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q411011.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q412011.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q413011.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q414011.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        default:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q411111.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q412111.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q413111.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q414111.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                    }
                } else {
                    switch (getMaskModes()) {
                        case 0:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q411000.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q412000.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q413000.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q414000.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        case DRAWMODE_SET_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q411100.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q412100.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q413100.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q414100.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        case DRAWMODE_CHECK_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q411010.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q412010.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q413010.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q414010.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        default:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q411110.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q412110.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q413110.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q414110.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                    }
                }
                break;
            case DRAWMODE_TEXTURE_8BIT:
                if (getPalette8(data[offset] | 0x01000000)) {
                    switch (getMaskModes()) {
                        case 0:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q811001.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q812001.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q813001.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q814001.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        case DRAWMODE_SET_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q811101.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q812101.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q813101.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q814101.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        case DRAWMODE_CHECK_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q811011.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q812011.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q813011.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q814011.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        default:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q811111.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q812111.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q813111.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q814111.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                    }
                } else {
                    switch (getMaskModes()) {
                        case 0:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q811000.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q812000.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q813000.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q814000.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        case DRAWMODE_SET_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q811100.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q812100.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q813100.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q814100.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        case DRAWMODE_CHECK_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q811010.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q812010.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q813010.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q814010.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        default:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q811110.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q812110.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q813110.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q814110.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                    }
                }
                break;
            case DRAWMODE_TEXTURE_16BIT:
                switch (getMaskModes()) {
                    case 0:
                        switch (getSemiMode()) {
                            case DRAWMODE_SEMI_5P5:
                                GPUGenerated._Q611000.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10P10:
                                GPUGenerated._Q612000.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10M10:
                                GPUGenerated._Q613000.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10P25:
                                GPUGenerated._Q614000.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                        }
                        break;
                    case DRAWMODE_SET_MASK:
                        switch (getSemiMode()) {
                            case DRAWMODE_SEMI_5P5:
                                GPUGenerated._Q611100.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10P10:
                                GPUGenerated._Q612100.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10M10:
                                GPUGenerated._Q613100.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10P25:
                                GPUGenerated._Q614100.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                        }
                        break;
                    case DRAWMODE_CHECK_MASK:
                        switch (getSemiMode()) {
                            case DRAWMODE_SEMI_5P5:
                                GPUGenerated._Q611010.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10P10:
                                GPUGenerated._Q612010.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10M10:
                                GPUGenerated._Q613010.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10P25:
                                GPUGenerated._Q614010.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                        }
                        break;
                    default:
                        switch (getSemiMode()) {
                            case DRAWMODE_SEMI_5P5:
                                GPUGenerated._Q611110.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10P10:
                                GPUGenerated._Q612110.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10M10:
                                GPUGenerated._Q613110.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10P25:
                                GPUGenerated._Q614110.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                        }
                        break;
                }
                break;
            case DRAWMODE_TEXTURE_4BITW:
                if (getPalette4(data[offset] | 0x01000000)) {
                    switch (getMaskModes()) {
                        case 0:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q511001.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q512001.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q513001.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q514001.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        case DRAWMODE_SET_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q511101.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q512101.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q513101.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q514101.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        case DRAWMODE_CHECK_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q511011.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q512011.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q513011.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q514011.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        default:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q511111.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q512111.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q513111.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q514111.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                    }
                } else {
                    switch (getMaskModes()) {
                        case 0:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q511000.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q512000.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q513000.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q514000.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        case DRAWMODE_SET_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q511100.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q512100.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q513100.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q514100.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        case DRAWMODE_CHECK_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q511010.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q512010.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q513010.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q514010.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        default:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q511110.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q512110.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q513110.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q514110.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                    }
                }
                break;
            case DRAWMODE_TEXTURE_8BITW:
                if (getPalette8(data[offset] | 0x01000000)) {
                    switch (getMaskModes()) {
                        case 0:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q911001.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q912001.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q913001.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q914001.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        case DRAWMODE_SET_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q911101.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q912101.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q913101.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q914101.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        case DRAWMODE_CHECK_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q911011.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q912011.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q913011.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q914011.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        default:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q911111.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q912111.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q913111.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q914111.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                    }
                } else {
                    switch (getMaskModes()) {
                        case 0:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q911000.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q912000.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q913000.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q914000.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        case DRAWMODE_SET_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q911100.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q912100.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q913100.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q914100.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        case DRAWMODE_CHECK_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q911010.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q912010.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q913010.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q914010.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                        default:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._Q911110.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._Q912110.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._Q913110.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._Q914110.render(m_polygonInfo, v0, v1, v2, v3);
                                    break;
                            }
                            break;
                    }
                }
                break;
            case DRAWMODE_TEXTURE_16BITW:
                switch (getMaskModes()) {
                    case 0:
                        switch (getSemiMode()) {
                            case DRAWMODE_SEMI_5P5:
                                GPUGenerated._Q711000.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10P10:
                                GPUGenerated._Q712000.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10M10:
                                GPUGenerated._Q713000.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10P25:
                                GPUGenerated._Q714000.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                        }
                        break;
                    case DRAWMODE_SET_MASK:
                        switch (getSemiMode()) {
                            case DRAWMODE_SEMI_5P5:
                                GPUGenerated._Q711100.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10P10:
                                GPUGenerated._Q712100.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10M10:
                                GPUGenerated._Q713100.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10P25:
                                GPUGenerated._Q714100.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                        }
                        break;
                    case DRAWMODE_CHECK_MASK:
                        switch (getSemiMode()) {
                            case DRAWMODE_SEMI_5P5:
                                GPUGenerated._Q711010.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10P10:
                                GPUGenerated._Q712010.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10M10:
                                GPUGenerated._Q713010.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10P25:
                                GPUGenerated._Q714010.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                        }
                        break;
                    default:
                        switch (getSemiMode()) {
                            case DRAWMODE_SEMI_5P5:
                                GPUGenerated._Q711110.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10P10:
                                GPUGenerated._Q712110.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10M10:
                                GPUGenerated._Q713110.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                            case DRAWMODE_SEMI_10P25:
                                GPUGenerated._Q714110.render(m_polygonInfo, v0, v1, v2, v3);
                                break;
                        }
                        break;
                }
        }
        return 0;
    }

    public static int gpudLine(int[] data, int offset, int size) {
        Vertex v0 = m_v0;
        Vertex v1 = m_v1;
        v0.x = (data[offset + 1] << 20) >> 20;
        v0.y = (data[offset + 1] << 4) >> 20;
        v1.x = (data[offset + 2] << 20) >> 20;
        v1.y = (data[offset + 2] << 4) >> 20;
        m_lineInfo.color = makePixel(data[offset] & 0xff, (data[offset] >> 8) & 0xff, (data[offset] >> 16) & 0xff, 0);

        if (dumpGPUD) {
            System.out.println("gpudLine (" + v0.x + "," + v0.y + ") -> (" + v1.x + "," + v1.y + ")");
        }
        switch (getMaskModes()) {
            case 0:
                GPUGenerated._L000000.render(m_lineInfo, v0, v1);
                break;
            case DRAWMODE_SET_MASK:
                GPUGenerated._L000100.render(m_lineInfo, v0, v1);
                break;
            case DRAWMODE_CHECK_MASK:
                GPUGenerated._L000010.render(m_lineInfo, v0, v1);
                break;
            default:
                GPUGenerated._L000110.render(m_lineInfo, v0, v1);
                break;
        }
        return 0;
    }

    public static int gpudLineSemi(int[] data, int offset, int size) {
        Vertex v0 = m_v0;
        Vertex v1 = m_v1;
        v0.x = (data[offset + 1] << 20) >> 20;
        v0.y = (data[offset + 1] << 4) >> 20;
        v1.x = (data[offset + 2] << 20) >> 20;
        v1.y = (data[offset + 2] << 4) >> 20;
        m_lineInfo.color = makePixel(data[offset] & 0xff, (data[offset] >> 8) & 0xff, (data[offset] >> 16) & 0xff, 0);

        if (dumpGPUD) {
            System.out.println("gpudLineSemi");
        }
        switch (getMaskModes()) {
            case 0:
                switch (getSemiMode()) {
                    case DRAWMODE_SEMI_5P5:
                        GPUGenerated._L001000.render(m_lineInfo, v0, v1);
                        break;
                    case DRAWMODE_SEMI_10P10:
                        GPUGenerated._L002000.render(m_lineInfo, v0, v1);
                        break;
                    case DRAWMODE_SEMI_10M10:
                        GPUGenerated._L003000.render(m_lineInfo, v0, v1);
                        break;
                    case DRAWMODE_SEMI_10P25:
                        GPUGenerated._L004000.render(m_lineInfo, v0, v1);
                        break;
                }
                break;
            case DRAWMODE_SET_MASK:
                switch (getSemiMode()) {
                    case DRAWMODE_SEMI_5P5:
                        GPUGenerated._L001100.render(m_lineInfo, v0, v1);
                        break;
                    case DRAWMODE_SEMI_10P10:
                        GPUGenerated._L002100.render(m_lineInfo, v0, v1);
                        break;
                    case DRAWMODE_SEMI_10M10:
                        GPUGenerated._L003100.render(m_lineInfo, v0, v1);
                        break;
                    case DRAWMODE_SEMI_10P25:
                        GPUGenerated._L004100.render(m_lineInfo, v0, v1);
                        break;
                }
                break;
            case DRAWMODE_CHECK_MASK:
                switch (getSemiMode()) {
                    case DRAWMODE_SEMI_5P5:
                        GPUGenerated._L001010.render(m_lineInfo, v0, v1);
                        break;
                    case DRAWMODE_SEMI_10P10:
                        GPUGenerated._L002010.render(m_lineInfo, v0, v1);
                        break;
                    case DRAWMODE_SEMI_10M10:
                        GPUGenerated._L003010.render(m_lineInfo, v0, v1);
                        break;
                    case DRAWMODE_SEMI_10P25:
                        GPUGenerated._L004010.render(m_lineInfo, v0, v1);
                        break;
                }
                break;
            default:
                switch (getSemiMode()) {
                    case DRAWMODE_SEMI_5P5:
                        GPUGenerated._L001110.render(m_lineInfo, v0, v1);
                        break;
                    case DRAWMODE_SEMI_10P10:
                        GPUGenerated._L002110.render(m_lineInfo, v0, v1);
                        break;
                    case DRAWMODE_SEMI_10M10:
                        GPUGenerated._L003110.render(m_lineInfo, v0, v1);
                        break;
                    case DRAWMODE_SEMI_10P25:
                        GPUGenerated._L004110.render(m_lineInfo, v0, v1);
                        break;
                }
                break;
        }
        return 0;
    }

    public static int gpudPolyLine(int[] data, int offset, int size) {
        if (m_gpudState != GPUD_CMD_EXTRA) {
            // Draw the first line segment.
            gpudLine(data, offset, 3);
            polyLineCmdBuffer[0] = data[offset]; // Color for whole polyline.
            polyLineCmdBuffer[1] = data[offset + 2]; // v1 for next line segment.
            m_gpudState = GPUD_CMD_EXTRA;
            return 0;
        } else {
            // todo; do this more efficiently!
            // NOTE: While most games use 0x55555555, Wild Arms uses 0x50005000.
            // http://problemkaputt.de/psx-spx.htm#gpurenderlinecommands
            if ((data[offset] & 0xf000f000 ) == 0x50005000) {
                // End of the polyline!
                m_gpudState = GPUD_CMD_NONE;
            } else {
                // Draw the next line segment.
                polyLineCmdBuffer[2] = data[offset];
                gpudLine(polyLineCmdBuffer, 0, 3);
                // Buffer entry 0 [color data] doesn't need to be rewritten.
                polyLineCmdBuffer[1] = data[offset]; // v1 for next line segment.
            }
            return 1;
        }
    }

    public static int gpudPolyLineSemi(int[] data, int offset, int size) {
        System.out.println("GPUD PolyLineSemi");
        if (true) throw new IllegalStateException();
        return 0;
    }

    public static int gpudLineGouraudSemi(int[] data, int offset, int size) {
        Vertex v0 = m_v0;
        Vertex v1 = m_v1;
        v0.x = (data[offset + 1] << 20) >> 20;
        v0.y = (data[offset + 1] << 4) >> 20;
        v1.x = (data[offset + 3] << 20) >> 20;
        v1.y = (data[offset + 3] << 4) >> 20;
        m_lineInfo.r0 = data[offset] & 0xff;
        m_lineInfo.g0 = (data[offset] >> 8) & 0xff;
        m_lineInfo.b0 = (data[offset] >> 16) & 0xff;
        m_lineInfo.r1 = data[offset + 2] & 0xff;
        m_lineInfo.g1 = (data[offset + 2] >> 8) & 0xff;
        m_lineInfo.b1 = (data[offset + 2] >> 16) & 0xff;

        if (dumpGPUD) {
            System.out.println("gpudLineGouraudSemi");
        }

        switch (getMaskModes()) {
            case 0:
                switch (getSemiMode()) {
                    case DRAWMODE_SEMI_5P5:
                        GPUGenerated._L011000.render(m_lineInfo, v0, v1);
                        break;
                    case DRAWMODE_SEMI_10P10:
                        GPUGenerated._L012000.render(m_lineInfo, v0, v1);
                        break;
                    case DRAWMODE_SEMI_10M10:
                        GPUGenerated._L013000.render(m_lineInfo, v0, v1);
                        break;
                    case DRAWMODE_SEMI_10P25:
                        GPUGenerated._L014000.render(m_lineInfo, v0, v1);
                        break;
                }
                break;
            case DRAWMODE_SET_MASK:
                switch (getSemiMode()) {
                    case DRAWMODE_SEMI_5P5:
                        GPUGenerated._L011100.render(m_lineInfo, v0, v1);
                        break;
                    case DRAWMODE_SEMI_10P10:
                        GPUGenerated._L012100.render(m_lineInfo, v0, v1);
                        break;
                    case DRAWMODE_SEMI_10M10:
                        GPUGenerated._L013100.render(m_lineInfo, v0, v1);
                        break;
                    case DRAWMODE_SEMI_10P25:
                        GPUGenerated._L014100.render(m_lineInfo, v0, v1);
                        break;
                }
                break;
            case DRAWMODE_CHECK_MASK:
                switch (getSemiMode()) {
                    case DRAWMODE_SEMI_5P5:
                        GPUGenerated._L011010.render(m_lineInfo, v0, v1);
                        break;
                    case DRAWMODE_SEMI_10P10:
                        GPUGenerated._L012010.render(m_lineInfo, v0, v1);
                        break;
                    case DRAWMODE_SEMI_10M10:
                        GPUGenerated._L013010.render(m_lineInfo, v0, v1);
                        break;
                    case DRAWMODE_SEMI_10P25:
                        GPUGenerated._L014010.render(m_lineInfo, v0, v1);
                        break;
                }
                break;
            default:
                switch (getSemiMode()) {
                    case DRAWMODE_SEMI_5P5:
                        GPUGenerated._L011110.render(m_lineInfo, v0, v1);
                        break;
                    case DRAWMODE_SEMI_10P10:
                        GPUGenerated._L012110.render(m_lineInfo, v0, v1);
                        break;
                    case DRAWMODE_SEMI_10M10:
                        GPUGenerated._L013110.render(m_lineInfo, v0, v1);
                        break;
                    case DRAWMODE_SEMI_10P25:
                        GPUGenerated._L014110.render(m_lineInfo, v0, v1);
                        break;
                }
                break;
        }
        return 0;
    }

    public static int gpudLineGouraud(int[] data, int offset, int size) {
        Vertex v0 = m_v0;
        Vertex v1 = m_v1;
        v0.x = (data[offset + 1] << 20) >> 20;
        v0.y = (data[offset + 1] << 4) >> 20;
        v1.x = (data[offset + 3] << 20) >> 20;
        v1.y = (data[offset + 3] << 4) >> 20;
        m_lineInfo.r0 = data[offset] & 0xff;
        m_lineInfo.g0 = (data[offset] >> 8) & 0xff;
        m_lineInfo.b0 = (data[offset] >> 16) & 0xff;
        m_lineInfo.r1 = data[offset + 2] & 0xff;
        m_lineInfo.g1 = (data[offset + 2] >> 8) & 0xff;
        m_lineInfo.b1 = (data[offset + 2] >> 16) & 0xff;

        if (dumpGPUD) {
            System.out.println("gpudLineGouraud");
        }
        switch (getMaskModes()) {
            case 0:
                GPUGenerated._L010000.render(m_lineInfo, v0, v1);
                break;
            case DRAWMODE_SET_MASK:
                GPUGenerated._L010100.render(m_lineInfo, v0, v1);
                break;
            case DRAWMODE_CHECK_MASK:
                GPUGenerated._L010010.render(m_lineInfo, v0, v1);
                break;
            default:
                GPUGenerated._L010110.render(m_lineInfo, v0, v1);
                break;
        }
        return 0;
    }

    public static int gpudPolyLineGouraud(int[] data, int offset, int size) {
        System.out.println("GPUD PolyLineGouraud");
        if (true) throw new IllegalStateException();
        return 0;
    }

    private static int[] polyLineCmdBuffer = new int[4];

    public static int gpudPolyLineGouraudSemi(int[] data, int offset, int size) {
        if (m_gpudState != GPUD_CMD_EXTRA) {
            gpudLineGouraudSemi(data, offset, 4);
            polyLineCmdBuffer[0] = data[offset + 2]; // color1
            polyLineCmdBuffer[1] = data[offset + 3]; // v1
            polyLineCmdBuffer[2] = 0x55555555;
            m_gpudState = GPUD_CMD_EXTRA;
            return 0;
        } else {
            // todo; do this more efficiently!
            // NOTE: While most games use 0x55555555, Wild Arms uses 0x50005000.
            // http://problemkaputt.de/psx-spx.htm#gpurenderlinecommands
            if ((data[offset] & 0xf000f000 ) == 0x50005000) {
                m_gpudState = GPUD_CMD_NONE;
                return 1;
            }
            if (polyLineCmdBuffer[2] == 0x55555555) {
                polyLineCmdBuffer[2] = data[offset];
            } else {
                polyLineCmdBuffer[3] = data[offset + 1];
                gpudLineGouraudSemi(polyLineCmdBuffer, 0, 4);
                polyLineCmdBuffer[0] = polyLineCmdBuffer[2];
                polyLineCmdBuffer[1] = polyLineCmdBuffer[3];
                polyLineCmdBuffer[2] = 0x55555555;
            }
            return 1;
        }
    }

    public static int gpudRectangle(int[] data, int offset, int size) {

        m_polygonInfo.r = (data[offset] & 0xff);
        m_polygonInfo.g = (data[offset] >> 8) & 0xff;
        m_polygonInfo.b = (data[offset] >> 16) & 0xff;

        int x = (data[offset + 1] << 20) >> 20;
        int y = (data[offset + 1] << 4) >> 20;

        int w = data[offset + 2] & 0xffff;
        int h = data[offset + 2] >> 16;

        if (dumpGPUD) {
            System.out.println("gpudRectangle " + x + "," + y + " " + w + "," + h);
        }
        switch (getMaskModes()) {
            case 0:
                GPUGenerated._S000000.render(m_polygonInfo, x, y, w, h);
                break;
            case DRAWMODE_SET_MASK:
                GPUGenerated._S000100.render(m_polygonInfo, x, y, w, h);
                break;
            case DRAWMODE_CHECK_MASK:
                GPUGenerated._S000010.render(m_polygonInfo, x, y, w, h);
                break;
            default:
                GPUGenerated._S000110.render(m_polygonInfo, x, y, w, h);
                break;
        }
        return 0;
    }

    public static int gpudRectangleSemi(int[] data, int offset, int size) {

        m_polygonInfo.r = (data[offset] & 0xff);
        m_polygonInfo.g = (data[offset] >> 8) & 0xff;
        m_polygonInfo.b = (data[offset] >> 16) & 0xff;

        int x = (data[offset + 1] << 20) >> 20;
        int y = (data[offset + 1] << 4) >> 20;

        int w = data[offset + 2] & 0xffff;
        int h = data[offset + 2] >> 16;

        if (dumpGPUD) {
            System.out.println("gpudRectangleSemi " + x + "," + y + " " + w + "," + h);
        }
        switch (getMaskModes()) {
            case 0:
                switch (getSemiMode()) {
                    case DRAWMODE_SEMI_5P5:
                        GPUGenerated._S001000.render(m_polygonInfo, x, y, w, h);
                        break;
                    case DRAWMODE_SEMI_10P10:
                        GPUGenerated._S002000.render(m_polygonInfo, x, y, w, h);
                        break;
                    case DRAWMODE_SEMI_10M10:
                        GPUGenerated._S003000.render(m_polygonInfo, x, y, w, h);
                        break;
                    case DRAWMODE_SEMI_10P25:
                        GPUGenerated._S004000.render(m_polygonInfo, x, y, w, h);
                        break;
                }
                break;
            case DRAWMODE_SET_MASK:
                switch (getSemiMode()) {
                    case DRAWMODE_SEMI_5P5:
                        GPUGenerated._S001100.render(m_polygonInfo, x, y, w, h);
                        break;
                    case DRAWMODE_SEMI_10P10:
                        GPUGenerated._S002100.render(m_polygonInfo, x, y, w, h);
                        break;
                    case DRAWMODE_SEMI_10M10:
                        GPUGenerated._S003100.render(m_polygonInfo, x, y, w, h);
                        break;
                    case DRAWMODE_SEMI_10P25:
                        GPUGenerated._S004100.render(m_polygonInfo, x, y, w, h);
                        break;
                }
                break;
            case DRAWMODE_CHECK_MASK:
                switch (getSemiMode()) {
                    case DRAWMODE_SEMI_5P5:
                        GPUGenerated._S001010.render(m_polygonInfo, x, y, w, h);
                        break;
                    case DRAWMODE_SEMI_10P10:
                        GPUGenerated._S002010.render(m_polygonInfo, x, y, w, h);
                        break;
                    case DRAWMODE_SEMI_10M10:
                        GPUGenerated._S003010.render(m_polygonInfo, x, y, w, h);
                        break;
                    case DRAWMODE_SEMI_10P25:
                        GPUGenerated._S004010.render(m_polygonInfo, x, y, w, h);
                        break;
                }
                break;
            default:
                switch (getSemiMode()) {
                    case DRAWMODE_SEMI_5P5:
                        GPUGenerated._S001110.render(m_polygonInfo, x, y, w, h);
                        break;
                    case DRAWMODE_SEMI_10P10:
                        GPUGenerated._S002110.render(m_polygonInfo, x, y, w, h);
                        break;
                    case DRAWMODE_SEMI_10M10:
                        GPUGenerated._S003110.render(m_polygonInfo, x, y, w, h);
                        break;
                    case DRAWMODE_SEMI_10P25:
                        GPUGenerated._S004110.render(m_polygonInfo, x, y, w, h);
                        break;
                }
                break;
        }
        return 0;
    }

    public static int gpudSprite(int[] data, int offset, int size) {


        int x = (data[offset + 1] << 20) >> 20;
        int y = (data[offset + 1] << 4) >> 20;

        int cly = (data[offset + 2] >> 22) & 0x1ff;
        int clx = (data[offset + 2] & 0x3f0000) >> 12;
        m_polygonInfo.u = data[offset + 2] & 0xff;
        m_polygonInfo.v = (data[offset + 2] >> 8) & 0xff;
        m_polygonInfo.clut = videoRAM;
        m_polygonInfo.clutOffset = cly * 1024 + clx;

        int w = data[offset + 3] & 0x1ff;
        int h = data[offset + 3] >> 16;

        if (dumpGPUD) {
            System.out.println("gpudSprite " + x + "," + y + " " + w + "," + h + " tm " + getTextureMode() + " mm " + getMaskModes());
        }
        if (false && x == -48 && y == 96 && w == 16 && h == 16) {
            try {
                java.io.FileOutputStream fos = new java.io.FileOutputStream("vram.dat");
                for (int i = 0; i < 1024 * 512; i++) {
                    int b = videoRAM[i];
                    fos.write(b & 0xff);
                    fos.write((b >> 8) & 0xff);
                    fos.write((b >> 16) & 0xff);
                    fos.write((b >> 24) & 0x01);
                }
            } catch (Throwable t) {

            }
            System.exit(0);
        }
        switch (getTextureMode()) {
            case DRAWMODE_TEXTURE_4BIT:
                if (getPalette4(data[offset])) {
                    switch (getMaskModes()) {
                        case 0:
                            GPUGenerated._S400001.render(m_polygonInfo, x, y, w, h);
                            break;
                        case DRAWMODE_SET_MASK:
                            GPUGenerated._S400101.render(m_polygonInfo, x, y, w, h);
                            break;
                        case DRAWMODE_CHECK_MASK:
                            GPUGenerated._S400011.render(m_polygonInfo, x, y, w, h);
                            break;
                        default:
                            GPUGenerated._S400111.render(m_polygonInfo, x, y, w, h);
                            break;
                    }
                } else {
                    switch (getMaskModes()) {
                        case 0:
                            GPUGenerated._S400000.render(m_polygonInfo, x, y, w, h);
                            break;
                        case DRAWMODE_SET_MASK:
                            GPUGenerated._S400100.render(m_polygonInfo, x, y, w, h);
                            break;
                        case DRAWMODE_CHECK_MASK:
                            GPUGenerated._S400010.render(m_polygonInfo, x, y, w, h);
                            break;
                        default:
                            GPUGenerated._S400110.render(m_polygonInfo, x, y, w, h);
                            break;
                    }
                }
                break;
            case DRAWMODE_TEXTURE_8BIT:
                if (getPalette8(data[offset])) {
                    switch (getMaskModes()) {
                        case 0:
                            GPUGenerated._S800001.render(m_polygonInfo, x, y, w, h);
                            break;
                        case DRAWMODE_SET_MASK:
                            GPUGenerated._S800101.render(m_polygonInfo, x, y, w, h);
                            break;
                        case DRAWMODE_CHECK_MASK:
                            GPUGenerated._S800011.render(m_polygonInfo, x, y, w, h);
                            break;
                        default:
                            GPUGenerated._S800111.render(m_polygonInfo, x, y, w, h);
                            break;
                    }
                } else {
                    switch (getMaskModes()) {
                        case 0:
                            GPUGenerated._S800000.render(m_polygonInfo, x, y, w, h);
                            break;
                        case DRAWMODE_SET_MASK:
                            GPUGenerated._S800100.render(m_polygonInfo, x, y, w, h);
                            break;
                        case DRAWMODE_CHECK_MASK:
                            GPUGenerated._S800010.render(m_polygonInfo, x, y, w, h);
                            break;
                        default:
                            GPUGenerated._S800110.render(m_polygonInfo, x, y, w, h);
                            break;
                    }
                }
                break;
            case DRAWMODE_TEXTURE_16BIT: {
                boolean nobreg = false;
                if (((data[offset] & 0x01000000) != 0) || ((data[offset] & 0xffffff) == 0x808080)) {
                    nobreg = true;
                } else {
                    missing("16 bit breg gpudSprite");
                }
                // todo breg
                switch (getMaskModes()) {
                    case 0:
                        GPUGenerated._S600000.render(m_polygonInfo, x, y, w, h);
                        break;
                    case DRAWMODE_SET_MASK:
                        GPUGenerated._S600100.render(m_polygonInfo, x, y, w, h);
                        break;
                    case DRAWMODE_CHECK_MASK:
                        GPUGenerated._S600010.render(m_polygonInfo, x, y, w, h);
                        break;
                    default:
                        GPUGenerated._S600110.render(m_polygonInfo, x, y, w, h);
                        break;
                }
                break;
            }
            case DRAWMODE_TEXTURE_4BITW:
                if (getPalette4(data[offset])) {
                    switch (getMaskModes()) {
                        case 0:
                            GPUGenerated._S500001.render(m_polygonInfo, x, y, w, h);
                            break;
                        case DRAWMODE_SET_MASK:
                            GPUGenerated._S500101.render(m_polygonInfo, x, y, w, h);
                            break;
                        case DRAWMODE_CHECK_MASK:
                            GPUGenerated._S500011.render(m_polygonInfo, x, y, w, h);
                            break;
                        default:
                            GPUGenerated._S500111.render(m_polygonInfo, x, y, w, h);
                            break;
                    }
                } else {
                    switch (getMaskModes()) {
                        case 0:
                            GPUGenerated._S500000.render(m_polygonInfo, x, y, w, h);
                            break;
                        case DRAWMODE_SET_MASK:
                            GPUGenerated._S500100.render(m_polygonInfo, x, y, w, h);
                            break;
                        case DRAWMODE_CHECK_MASK:
                            GPUGenerated._S500010.render(m_polygonInfo, x, y, w, h);
                            break;
                        default:
                            GPUGenerated._S500110.render(m_polygonInfo, x, y, w, h);
                            break;
                    }
                }
                break;
            case DRAWMODE_TEXTURE_8BITW:
                if (getPalette8(data[offset])) {
                    switch (getMaskModes()) {
                        case 0:
                            GPUGenerated._S900001.render(m_polygonInfo, x, y, w, h);
                            break;
                        case DRAWMODE_SET_MASK:
                            GPUGenerated._S900101.render(m_polygonInfo, x, y, w, h);
                            break;
                        case DRAWMODE_CHECK_MASK:
                            GPUGenerated._S900011.render(m_polygonInfo, x, y, w, h);
                            break;
                        default:
                            GPUGenerated._S900111.render(m_polygonInfo, x, y, w, h);
                            break;
                    }
                } else {
                    switch (getMaskModes()) {
                        case 0:
                            GPUGenerated._S900000.render(m_polygonInfo, x, y, w, h);
                            break;
                        case DRAWMODE_SET_MASK:
                            GPUGenerated._S900100.render(m_polygonInfo, x, y, w, h);
                            break;
                        case DRAWMODE_CHECK_MASK:
                            GPUGenerated._S900010.render(m_polygonInfo, x, y, w, h);
                            break;
                        default:
                            GPUGenerated._S900110.render(m_polygonInfo, x, y, w, h);
                            break;
                    }
                }
                break;
            case DRAWMODE_TEXTURE_16BITW: {
                boolean nobreg = false;
                if (((data[offset] & 0x01000000) != 0) || ((data[offset] & 0xffffff) == 0x808080)) {
                    nobreg = true;
                } else {
                    missing("16 bit breg gpudSprite");
                }
                // todo breg
                switch (getMaskModes()) {
                    case 0:
                        GPUGenerated._S700000.render(m_polygonInfo, x, y, w, h);
                        break;
                    case DRAWMODE_SET_MASK:
                        GPUGenerated._S700100.render(m_polygonInfo, x, y, w, h);
                        break;
                    case DRAWMODE_CHECK_MASK:
                        GPUGenerated._S700010.render(m_polygonInfo, x, y, w, h);
                        break;
                    default:
                        GPUGenerated._S700110.render(m_polygonInfo, x, y, w, h);
                        break;
                }
            }
        }
        return 0;


    }

    public static int gpudSpriteSemi(int[] data, int offset, int size) {

        int x = (data[offset + 1] << 20) >> 20;
        int y = (data[offset + 1] << 4) >> 20;

        int cly = (data[offset + 2] >> 22) & 0x1ff;
        int clx = (data[offset + 2] & 0x3f0000) >> 12;
        m_polygonInfo.u = data[offset + 2] & 0xff;
        m_polygonInfo.v = (data[offset + 2] >> 8) & 0xff;
        m_polygonInfo.clut = videoRAM;
        m_polygonInfo.clutOffset = cly * 1024 + clx;

        int w = data[offset + 3] & 0x1ff;
        int h = data[offset + 3] >> 16;

        if (dumpGPUD) {
            System.out.println("gpudSpriteSemi " + x + "," + y + " " + w + "," + h);
        }
        switch (getTextureMode()) {
            case DRAWMODE_TEXTURE_4BIT:
                if (getPalette4(data[offset])) {
                    switch (getMaskModes()) {
                        case 0:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._S401001.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._S402001.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._S403001.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._S404001.render(m_polygonInfo, x, y, w, h);
                                    break;
                            }
                            break;
                        case DRAWMODE_SET_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._S401101.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._S402101.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._S403101.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._S404101.render(m_polygonInfo, x, y, w, h);
                                    break;
                            }
                            break;
                        case DRAWMODE_CHECK_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._S401011.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._S402011.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._S403011.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._S404011.render(m_polygonInfo, x, y, w, h);
                                    break;
                            }
                            break;
                        default:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._S401111.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._S402111.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._S403111.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._S404111.render(m_polygonInfo, x, y, w, h);
                                    break;
                            }
                            break;
                    }
                } else {
                    switch (getMaskModes()) {
                        case 0:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._S401000.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._S402000.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._S403000.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._S404000.render(m_polygonInfo, x, y, w, h);
                                    break;
                            }
                            break;
                        case DRAWMODE_SET_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._S401100.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._S402100.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._S403100.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._S404100.render(m_polygonInfo, x, y, w, h);
                                    break;
                            }
                            break;
                        case DRAWMODE_CHECK_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._S401010.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._S402010.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._S403010.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._S404010.render(m_polygonInfo, x, y, w, h);
                                    break;
                            }
                            break;
                        default:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._S401110.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._S402110.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._S403110.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._S404110.render(m_polygonInfo, x, y, w, h);
                                    break;
                            }
                            break;
                    }
                }
                break;
            case DRAWMODE_TEXTURE_8BIT:
                if (getPalette8(data[offset])) {
                    switch (getMaskModes()) {
                        case 0:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._S801001.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._S802001.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._S803001.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._S804001.render(m_polygonInfo, x, y, w, h);
                                    break;
                            }
                            break;
                        case DRAWMODE_SET_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._S801101.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._S802101.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._S803101.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._S804101.render(m_polygonInfo, x, y, w, h);
                                    break;
                            }
                            break;
                        case DRAWMODE_CHECK_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._S801011.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._S802011.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._S803011.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._S804011.render(m_polygonInfo, x, y, w, h);
                                    break;
                            }
                            break;
                        default:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._S801111.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._S802111.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._S803111.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._S804111.render(m_polygonInfo, x, y, w, h);
                                    break;
                            }
                            break;
                    }
                } else {
                    switch (getMaskModes()) {
                        case 0:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._S801000.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._S802000.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._S803000.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._S804000.render(m_polygonInfo, x, y, w, h);
                                    break;
                            }
                            break;
                        case DRAWMODE_SET_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._S801100.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._S802100.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._S803100.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._S804100.render(m_polygonInfo, x, y, w, h);
                                    break;
                            }
                            break;
                        case DRAWMODE_CHECK_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._S801010.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._S802010.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._S803010.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._S804010.render(m_polygonInfo, x, y, w, h);
                                    break;
                            }
                            break;
                        default:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._S801110.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._S802110.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._S803110.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._S804110.render(m_polygonInfo, x, y, w, h);
                                    break;
                            }
                            break;
                    }
                }
                break;
            case DRAWMODE_TEXTURE_16BIT: {
                boolean nobreg = false;
                if (((data[offset] & 0x01000000) != 0) || ((data[offset] & 0xffffff) == 0x808080)) {
                    nobreg = true;
                } else {
                    missing("16 bit breg gpudSpriteSemi");
                }
                // todo breg
                switch (getMaskModes()) {
                    case 0:
                        switch (getSemiMode()) {
                            case DRAWMODE_SEMI_5P5:
                                GPUGenerated._S601000.render(m_polygonInfo, x, y, w, h);
                                break;
                            case DRAWMODE_SEMI_10P10:
                                GPUGenerated._S602000.render(m_polygonInfo, x, y, w, h);
                                break;
                            case DRAWMODE_SEMI_10M10:
                                GPUGenerated._S603000.render(m_polygonInfo, x, y, w, h);
                                break;
                            case DRAWMODE_SEMI_10P25:
                                GPUGenerated._S604000.render(m_polygonInfo, x, y, w, h);
                                break;
                        }
                        break;
                    case DRAWMODE_SET_MASK:
                        switch (getSemiMode()) {
                            case DRAWMODE_SEMI_5P5:
                                GPUGenerated._S601100.render(m_polygonInfo, x, y, w, h);
                                break;
                            case DRAWMODE_SEMI_10P10:
                                GPUGenerated._S602100.render(m_polygonInfo, x, y, w, h);
                                break;
                            case DRAWMODE_SEMI_10M10:
                                GPUGenerated._S603100.render(m_polygonInfo, x, y, w, h);
                                break;
                            case DRAWMODE_SEMI_10P25:
                                GPUGenerated._S604100.render(m_polygonInfo, x, y, w, h);
                                break;
                        }
                        break;
                    case DRAWMODE_CHECK_MASK:
                        switch (getSemiMode()) {
                            case DRAWMODE_SEMI_5P5:
                                GPUGenerated._S601010.render(m_polygonInfo, x, y, w, h);
                                break;
                            case DRAWMODE_SEMI_10P10:
                                GPUGenerated._S602010.render(m_polygonInfo, x, y, w, h);
                                break;
                            case DRAWMODE_SEMI_10M10:
                                GPUGenerated._S603010.render(m_polygonInfo, x, y, w, h);
                                break;
                            case DRAWMODE_SEMI_10P25:
                                GPUGenerated._S604010.render(m_polygonInfo, x, y, w, h);
                                break;
                        }
                        break;
                    default:
                        switch (getSemiMode()) {
                            case DRAWMODE_SEMI_5P5:
                                GPUGenerated._S601110.render(m_polygonInfo, x, y, w, h);
                                break;
                            case DRAWMODE_SEMI_10P10:
                                GPUGenerated._S602110.render(m_polygonInfo, x, y, w, h);
                                break;
                            case DRAWMODE_SEMI_10M10:
                                GPUGenerated._S603110.render(m_polygonInfo, x, y, w, h);
                                break;
                            case DRAWMODE_SEMI_10P25:
                                GPUGenerated._S604110.render(m_polygonInfo, x, y, w, h);
                                break;
                        }
                        break;
                }
                break;
            }
            case DRAWMODE_TEXTURE_4BITW:
                if (getPalette4(data[offset])) {
                    switch (getMaskModes()) {
                        case 0:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._S501001.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._S502001.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._S503001.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._S504001.render(m_polygonInfo, x, y, w, h);
                                    break;
                            }
                            break;
                        case DRAWMODE_SET_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._S501101.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._S502101.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._S503101.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._S504101.render(m_polygonInfo, x, y, w, h);
                                    break;
                            }
                            break;
                        case DRAWMODE_CHECK_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._S501011.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._S502011.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._S503011.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._S504011.render(m_polygonInfo, x, y, w, h);
                                    break;
                            }
                            break;
                        default:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._S501111.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._S502111.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._S503111.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._S504111.render(m_polygonInfo, x, y, w, h);
                                    break;
                            }
                            break;
                    }
                } else {
                    switch (getMaskModes()) {
                        case 0:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._S501000.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._S502000.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._S503000.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._S504000.render(m_polygonInfo, x, y, w, h);
                                    break;
                            }
                            break;
                        case DRAWMODE_SET_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._S501100.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._S502100.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._S503100.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._S504100.render(m_polygonInfo, x, y, w, h);
                                    break;
                            }
                            break;
                        case DRAWMODE_CHECK_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._S501010.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._S502010.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._S503010.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._S504010.render(m_polygonInfo, x, y, w, h);
                                    break;
                            }
                            break;
                        default:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._S501110.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._S502110.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._S503110.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._S504110.render(m_polygonInfo, x, y, w, h);
                                    break;
                            }
                            break;
                    }
                }
                break;
            case DRAWMODE_TEXTURE_8BITW:
                if (getPalette8(data[offset])) {
                    switch (getMaskModes()) {
                        case 0:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._S901001.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._S902001.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._S903001.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._S904001.render(m_polygonInfo, x, y, w, h);
                                    break;
                            }
                            break;
                        case DRAWMODE_SET_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._S901101.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._S902101.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._S903101.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._S904101.render(m_polygonInfo, x, y, w, h);
                                    break;
                            }
                            break;
                        case DRAWMODE_CHECK_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._S901011.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._S902011.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._S903011.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._S904011.render(m_polygonInfo, x, y, w, h);
                                    break;
                            }
                            break;
                        default:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._S901111.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._S902111.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._S903111.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._S904111.render(m_polygonInfo, x, y, w, h);
                                    break;
                            }
                            break;
                    }
                } else {
                    switch (getMaskModes()) {
                        case 0:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._S901000.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._S902000.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._S903000.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._S904000.render(m_polygonInfo, x, y, w, h);
                                    break;
                            }
                            break;
                        case DRAWMODE_SET_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._S901100.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._S902100.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._S903100.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._S904100.render(m_polygonInfo, x, y, w, h);
                                    break;
                            }
                            break;
                        case DRAWMODE_CHECK_MASK:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._S901010.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._S902010.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._S903010.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._S904010.render(m_polygonInfo, x, y, w, h);
                                    break;
                            }
                            break;
                        default:
                            switch (getSemiMode()) {
                                case DRAWMODE_SEMI_5P5:
                                    GPUGenerated._S901110.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P10:
                                    GPUGenerated._S902110.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10M10:
                                    GPUGenerated._S903110.render(m_polygonInfo, x, y, w, h);
                                    break;
                                case DRAWMODE_SEMI_10P25:
                                    GPUGenerated._S904110.render(m_polygonInfo, x, y, w, h);
                                    break;
                            }
                            break;
                    }
                }
                break;
            case DRAWMODE_TEXTURE_16BITW: {
                boolean nobreg = false;
                if (((data[offset] & 0x01000000) != 0) || ((data[offset] & 0xffffff) == 0x808080)) {
                    nobreg = true;
                } else {
                    missing("16 bit breg gpudSpriteSemi");
                }
                // todo breg
                switch (getMaskModes()) {
                    case 0:
                        switch (getSemiMode()) {
                            case DRAWMODE_SEMI_5P5:
                                GPUGenerated._S701000.render(m_polygonInfo, x, y, w, h);
                                break;
                            case DRAWMODE_SEMI_10P10:
                                GPUGenerated._S702000.render(m_polygonInfo, x, y, w, h);
                                break;
                            case DRAWMODE_SEMI_10M10:
                                GPUGenerated._S703000.render(m_polygonInfo, x, y, w, h);
                                break;
                            case DRAWMODE_SEMI_10P25:
                                GPUGenerated._S704000.render(m_polygonInfo, x, y, w, h);
                                break;
                        }
                        break;
                    case DRAWMODE_SET_MASK:
                        switch (getSemiMode()) {
                            case DRAWMODE_SEMI_5P5:
                                GPUGenerated._S701100.render(m_polygonInfo, x, y, w, h);
                                break;
                            case DRAWMODE_SEMI_10P10:
                                GPUGenerated._S702100.render(m_polygonInfo, x, y, w, h);
                                break;
                            case DRAWMODE_SEMI_10M10:
                                GPUGenerated._S703100.render(m_polygonInfo, x, y, w, h);
                                break;
                            case DRAWMODE_SEMI_10P25:
                                GPUGenerated._S704100.render(m_polygonInfo, x, y, w, h);
                                break;
                        }
                        break;
                    case DRAWMODE_CHECK_MASK:
                        switch (getSemiMode()) {
                            case DRAWMODE_SEMI_5P5:
                                GPUGenerated._S701010.render(m_polygonInfo, x, y, w, h);
                                break;
                            case DRAWMODE_SEMI_10P10:
                                GPUGenerated._S702010.render(m_polygonInfo, x, y, w, h);
                                break;
                            case DRAWMODE_SEMI_10M10:
                                GPUGenerated._S703010.render(m_polygonInfo, x, y, w, h);
                                break;
                            case DRAWMODE_SEMI_10P25:
                                GPUGenerated._S704010.render(m_polygonInfo, x, y, w, h);
                                break;
                        }
                        break;
                    default:
                        switch (getSemiMode()) {
                            case DRAWMODE_SEMI_5P5:
                                GPUGenerated._S701110.render(m_polygonInfo, x, y, w, h);
                                break;
                            case DRAWMODE_SEMI_10P10:
                                GPUGenerated._S702110.render(m_polygonInfo, x, y, w, h);
                                break;
                            case DRAWMODE_SEMI_10M10:
                                GPUGenerated._S703110.render(m_polygonInfo, x, y, w, h);
                                break;
                            case DRAWMODE_SEMI_10P25:
                                GPUGenerated._S704110.render(m_polygonInfo, x, y, w, h);
                                break;
                        }
                        break;
                }
            }
        }
        return 0;
    }

    public static int gpudRectangle1x1(int[] data, int offset, int size) {
        int tmp = data[offset + 2];
        data[offset + 2] = 0x00010001;
        int rc = gpudRectangle(data, offset, size);
        data[offset + 2] = tmp;
        return rc;
    }

    public static int gpudRectangle1x1Semi(int[] data, int offset, int size) {
        int tmp = data[offset + 2];
        data[offset + 2] = 0x00010001;
        int rc = gpudRectangleSemi(data, offset, size);
        data[offset + 2] = tmp;
        return rc;
    }

    public static int gpudSprite1x1(int[] data, int offset, int size) {
        int tmp = data[offset + 3];
        data[offset + 3] = 0x00010001;
        int rc = gpudSprite(data, offset, size);
        data[offset + 3] = tmp;
        return rc;
    }

    public static int gpudSprite1x1Semi(int[] data, int offset, int size) {
        int tmp = data[offset + 3];
        data[offset + 3] = 0x00010001;
        int rc = gpudSpriteSemi(data, offset, size);
        data[offset + 3] = tmp;
        return rc;
    }

    public static int gpudRectangle8x8(int[] data, int offset, int size) {
        int tmp = data[offset + 2];
        data[offset + 2] = 0x00080008;
        int rc = gpudRectangle(data, offset, size);
        data[offset + 2] = tmp;
        return rc;
    }

    public static int gpudRectangle8x8Semi(int[] data, int offset, int size) {
        int tmp = data[offset + 2];
        data[offset + 2] = 0x00080008;
        int rc = gpudRectangleSemi(data, offset, size);
        data[offset + 2] = tmp;
        return rc;
    }

    public static int gpudSprite8x8(int[] data, int offset, int size) {
        int tmp = data[offset + 3];
        data[offset + 3] = 0x00080008;
        int rc = gpudSprite(data, offset, size);
        data[offset + 3] = tmp;
        return rc;
    }

    public static int gpudSprite8x8Semi(int[] data, int offset, int size) {
        int tmp = data[offset + 3];
        data[offset + 3] = 0x00080008;
        int rc = gpudSpriteSemi(data, offset, size);
        data[offset + 3] = tmp;
        return rc;
    }

    public static int gpudRectangle16x16(int[] data, int offset, int size) {
        int tmp = data[offset + 2];
        data[offset + 2] = 0x00100010;
        int rc = gpudRectangle(data, offset, size);
        data[offset + 2] = tmp;
        return rc;
    }

    public static int gpudRectangle16x16Semi(int[] data, int offset, int size) {
        int tmp = data[offset + 2];
        data[offset + 2] = 0x00100010;
        int rc = gpudRectangleSemi(data, offset, size);
        data[offset + 2] = tmp;
        return rc;
    }

    public static int gpudSprite16x16(int[] data, int offset, int size) {
        int tmp = data[offset + 3];
        data[offset + 3] = 0x00100010;
        int rc = gpudSprite(data, offset, size);
        data[offset + 3] = tmp;
        return rc;
    }

    public static int gpudSprite16x16Semi(int[] data, int offset, int size) {
        int tmp = data[offset + 3];
        data[offset + 3] = 0x00100010;
        int rc = gpudSpriteSemi(data, offset, size);
        data[offset + 3] = tmp;
        return rc;
    }

    public static int gpudVRAMtoVRAM(int[] data, int offset, int size) {
        // todo check sizes
        int sx = data[offset + 1] & 0x3ff;
        int sy = (data[offset + 1] >> 16) & 0x1ff;
        int dx = data[offset + 2] & 0x3ff;
        int dy = (data[offset + 2] >> 16) & 0x1ff;

        if (sx == dx && sy == dy) {
            if (false)
                System.out.println("??? " + MiscUtil.toHex(data[offset], 8) + " " +
                        MiscUtil.toHex(data[offset + 1], 8) + " " +
                        MiscUtil.toHex(data[offset + 2], 8) + " " +
                        MiscUtil.toHex(data[offset + 3], 8));
            return 0;
        }
        int w = data[offset + 3] & 0x3ff;
        int h = (data[offset + 3] >> 16) & 0x1ff;
        // todo is this slow?
        if (dumpGPUD)
            System.out.println("GPUD VRAMToVRAM " + sx + "," + sy + " " + w + "," + h + " -> " + dx + "," + dy);

        if (w > 0) {
            int src = sx + sy * 1024;
            int dest = dx + dy * 1024;
            // system.arraycopy?
            if (dest < src) {
                for (; h > 0; h--) {
                    for (int x = 0; x < w; x++) {
                        videoRAM[dest + x] = videoRAM[src + x];
                    }
                    src += 1024;
                    dest += 1024;
                }
            } else {
                src += (h - 1) * 1024;
                dest += (h - 1) * 1024;
                for (; h > 0; h--) {
                    for (int x = w - 1; x >= 0; x--) {
                        videoRAM[dest + x] = videoRAM[src + x];
                    }
                    src -= 1024;
                    dest -= 1024;
                }
            }
        }
        manager.dirtyRectangle(dx, dy, w, h);
        return 0;
    }

    public static int gpudMemToVRAM(int[] data, int offset, int size) {
        if (m_gpudState != GPUD_CMD_EXTRA) {
            int x = data[offset + 1] & 0x3ff;
            int y = (data[offset + 1] >> 16) & 0x1ff;
            int w = data[offset + 2] & 0x7ff;
            int h = (data[offset + 2] >> 16) & 0x3ff;
            int dwordSize = (w * h + 1) >> 1;

            m_dmaRGB24Index = 0;
            m_dmaX = x;
            m_dmaY = y;
            m_dmaOriginX = x;
            m_dmaOriginY = y;
            m_dmaW = w;
            m_dmaH = h;
            if (dumpGPUD) System.out.println("GPUD MemToVRAM " + x + "," + y + " " + w + "," + h);
            if (dwordSize > 0) {
                m_gpudState = GPUD_CMD_EXTRA;
                m_dmaDWordsRemaining = dwordSize;
            }
            return 0;
        } else {
            //System.out.println("Avail: "+count);
            //System.out.println("MemToVRAM remaining: "+m_dmaDWordsRemaining);
            if (size > m_dmaDWordsRemaining) {
                size = m_dmaDWordsRemaining;
            }
            int rc = size;
            m_dmaDWordsRemaining -= size;

            size *= 2;

            int[] ram;

            try {
                ram = display.acquireDisplayBuffer();

                // TODO this is woefully inefficient :-)
                if (rgb24conversion && rgb24 && (m_dmaW % 6) == 0) {
                    // todo graham 12/21/14 - I realized while debugging the intro screen of spyro
                    // that I actually thought the B and the R were the other way around on the PSX
                    // curiously that works for other things. Frankly at this point I don't think
                    // the work of doing the conversion to a separate 24 bit display buffer at display time
                    // is that bad and will gain us some simplification - probably worth a fork of the GPU/Display though

                    // we convert dwords from:
                    //    B1R0G0B0            G2B2R1G1  R3G3B3R2
                    // to
                    //    A_R0G0B0  B_R0G0B1  C_R1G1B1  A_R2G2B2  B_R2G2B3  C_R3G3B3

                    // consider words, we pick the internal representation, such that
                    // we can convert a single pixel without knowing where it came from
                    // i.e. A_ pixels do one thing, B_ another, and C_ a third.

                    //        G0B0      B1R0      R1G1      G2B2      B3R2      R3G3
                    // to:
                    //    A_R0G0B0  B_R0G0B1  C_R1G1B1  A_R2G2B2  B_R2G2B3  C_R3G3B3
                    // to:
                    //        G0B0      B1R0      R1G1      G2B2      B3R2      R3G3

                    while (size > 0) {
                        int dword = data[offset++];
                        switch (m_dmaRGB24Index) {
                            case 0:
                                m_dmaRGB24LastPixel = (dword & 0xffffff);
                                ram[m_dmaX + m_dmaY * 1024] = GPU_RGB24_A | m_dmaRGB24LastPixel;
                                m_dmaRGB24LastDWord = dword;
                                m_dmaX++;
                                m_dmaRGB24Index = 1;
                                break;
                            case 1:
                                ram[m_dmaX + m_dmaY * 1024] = GPU_RGB24_B | (m_dmaRGB24LastPixel & 0xffff00) | (m_dmaRGB24LastDWord >>> 24);
                                m_dmaX++;
                                ram[m_dmaX + m_dmaY * 1024] = GPU_RGB24_C | (m_dmaRGB24LastDWord >>> 24) | ((dword << 8) & 0xffff00);
                                m_dmaRGB24LastDWord = dword;
                                m_dmaX++;
                                m_dmaRGB24Index = 2;
                                break;
                            case 2:
                                m_dmaRGB24LastPixel = (m_dmaRGB24LastDWord >>> 16) | ((dword & 0xff) << 16);
                                ram[m_dmaX + m_dmaY * 1024] = GPU_RGB24_A | m_dmaRGB24LastPixel;
                                m_dmaX++;
                                ram[m_dmaX + m_dmaY * 1024] = GPU_RGB24_B | (m_dmaRGB24LastPixel & 0xffff00) | ((dword >> 8) & 0xff);
                                m_dmaX++;
                                ram[m_dmaX + m_dmaY * 1024] = GPU_RGB24_C | ((dword >> 8) & 0xffffff);
                                m_dmaX++;
                                m_dmaRGB24Index = 0;
                                break;
                        }
                        if (m_dmaX == (m_dmaOriginX + m_dmaW)) {
                            m_dmaX = m_dmaOriginX;
                            m_dmaY++;
                            m_dmaRGB24Index = 0;
                            if (m_dmaY == (m_dmaOriginY + m_dmaH)) {
                                break;
                            }
                        }
                        size -= 2;
                    }
                } else {
                    // 16 bit
                    while (size > 0) {
                        int dword = data[offset++];
                        int val = dword >>> 16;
                        ram[m_dmaX + m_dmaY * 1024] = dma16flags[m_dmaRGB24Index] | makePixel((dword & 0x1f) << 3, (dword & 0x3e0) >> 2, (dword & 0x7c00) >> 7, (dword & 0x8000) >> 15);
                        m_dmaX++;
                        m_dmaRGB24Index++;
                        if (m_dmaX == (m_dmaOriginX + m_dmaW)) {
                            m_dmaX = m_dmaOriginX;
                            m_dmaY++;
                            if (m_dmaY == (m_dmaOriginY + m_dmaH)) {
                                break;
                            }
                            m_dmaRGB24Index = 0;
                        }
                        size--;

                        ram[m_dmaX + m_dmaY * 1024] = dma16flags[m_dmaRGB24Index] | makePixel((val & 0x1f) << 3, (val & 0x3e0) >> 2, (val & 0x7c00) >> 7, (val & 0x8000) >> 15);
                        m_dmaX++;
                        m_dmaRGB24Index++;
                        if (m_dmaX == (m_dmaOriginX + m_dmaW)) {
                            m_dmaX = m_dmaOriginX;
                            m_dmaY++;
                            if (m_dmaY == (m_dmaOriginY + m_dmaH)) {
                                break;
                            }
                            m_dmaRGB24Index = 0;
                        }
                        size--;
                        if (m_dmaRGB24Index >= 3) m_dmaRGB24Index -= 3;
                    }
                }
            } finally {
                display.releaseDisplayBuffer();
            }

            if (m_dmaDWordsRemaining == 0) {
                int x = m_dmaOriginX;
                int y = m_dmaOriginY;
                int w = m_dmaW;
                int h = m_dmaH;
                // todo clear the cache better
                for (int page = 0; page < 0x20; page++) {
                    int px = (page & 0x0f) * 64;
                    int py = (page & 0x10) * 16;
                    if (intersects(x, y, w, h, px, py, 64, 256)) {
                        if (dumpGPUD) {
                            if (_4bitTexturePages[page] != null)
                                System.out.println("Zapping 4bit tpage " + page);
                        }
                        _4bitTexturePages[page] = null;
                    }
                    if (intersects(x, y, w, h, px, py, 128, 256)) {
                        if (dumpGPUD) {
                            if (_8bitTexturePages[page] != null)
                                System.out.println("Zapping 8bit tpage " + page);
                        }
                        _8bitTexturePages[page] = null;
                    }
                }
                manager.dirtyRectangle(x, y, w, h);
                m_gpudState = GPUD_CMD_NONE;
            }
            return rc;
        }
    }

    private static boolean intersects(int x0, int y0, int w0, int h0, int x1, int y1, int w1, int h1) {
        if (w0 == 0 || h0 == 0 || w1 == 0 || h1 == 0) return false;
        w0 += x0;
        w1 += x1;
        h0 += y0;
        h1 += y1;
        return (w0 > x1) && (h0 > y1) && (w1 > x0) && (h1 > y0);
    }

/*    private static boolean intersectsDisplay( int x, int y, int w, int h)
    {
        int dx = m_displayX;
        int dy = m_displayY;
        int dw = m_displayWidth;
        int dh = m_displayHeight;

        // todo display configure/end

        if (0!=(m_displayMode&0x0040) && h==1) {
            if ((!interlace && 0!=((dy+y)&1)) ||
                (interlace && 0==((dy+y)&1))) {
                //System.out.println("Detected non-intersect in interlaced mode");
                return false;
            }
        }
        return intersects( x, y, w, h, dx, dy, dw, dh);
    }*/

    public static int gpudVRAMToMem(int[] data, int offset, int size) {
        int x = data[offset + 1] & 0x3ff;
        int y = (data[offset + 1] >> 16) & 0x1ff;
        int w = data[offset + 2] & 0x7ff;
        int h = (data[offset + 2] >> 16) & 0x3ff;

        m_dmaX = x;
        m_dmaY = y;
        m_dmaOriginX = x;
        m_dmaOriginY = y;
        m_dmaW = w;
        m_dmaH = h;
        m_dmaWordsRemaining = w * h;
        if (dumpGPUD) System.out.println("GPUD VRAMtoMem " + x + "," + y + " " + w + "," + h);
        assert !rgb24;
        return 0;
    }

    private static int readPixel() {
        if (m_dmaWordsRemaining > 0) {
            m_dmaWordsRemaining--;
            int rc = unmakePixel(videoRAM[m_dmaX + m_dmaY * 1024]);
            m_dmaX++;
            if (m_dmaX == m_dmaOriginX + m_dmaW) {
                m_dmaX = m_dmaOriginX;
                m_dmaY++;
            }
            return rc;
        }
        return 0;
    }

    public static int gpudSetDrawMode(int[] data, int offset, int size) {
        drawMode = data[offset] & 0x7ff;
        return 0;
    }

    public static int gpudSetTextureWindow(int[] data, int offset, int size) {
        if (!supportTextureWindow) {
            noTextureWindow = true;
            return 0;
        }
        noTextureWindow = 0 == (data[offset] & 0xfffff);
        if (!noTextureWindow) {
            int twy = (data[offset] >> 15) & 0x1f;
            int twx = (data[offset] >> 10) & 0x1f;
            int twh = 32 - ((data[offset] >> 5) & 0x1f);
            int tww = 32 - ((data[offset]) & 0x1f);

            // todo cache recent?
            int val = twx << 3;
            int w = 0;
            for (int i = twx; i < 32 + twx; i++) {
                twuLookup[i & 0x1f] = val;
                val += 1 << 3;
                w++;
                if (w == tww) {
                    w = 0;
                    val = twx << 3;
                }
            }

            val = twy << 11;
            int h = 0;
            for (int i = twy; i < 32 + twy; i++) {
                twvLookup[i & 0x1f] = val;
                val += 1 << 11;
                h++;
                if (h == twh) {
                    h = 0;
                    val = twy << 11;
                }
            }

            if (dumpGPUD) {
                System.out.println("GPUD SetTextureWindow " + (twx * 8) + "," + (twy * 8) + " " + (tww * 8) + "," + (twh * 8));
            }
        }
        return 0;
    }

    public static int gpudSetClipTopLeft(int[] data, int offset, int size) {
        m_clipLeft = data[offset] & 0x3ff;
        m_clipTop = (data[offset] >> 10) & 0x3ff;
        //    System.out.println("GPUD SetClipTopLeft "+m_clipLeft+","+m_clipTop);
        return 0;
    }

    public static int gpudSetClipBottomRight(int[] data, int offset, int size) {
        // note addition of (1,1) to co-ords, since we do non-inclusive bottom-right,
        // but PSX doesn't.
        m_clipRight = (data[offset] & 0x3ff) + 1;
        m_clipBottom = ((data[offset] >> 10) & 0x3ff) + 1;
        //    System.out.println("GPUD SetClipBottomRight "+m_clipRight+","+m_clipBottom);
        return 0;
    }

    public static int gpudSetDrawingOffset(int[] data, int offset, int size) {
        m_drawOffsetX = ((data[offset] & 0x7ff) << 21) >> 21;
        m_drawOffsetY = ((data[offset] & 0x3ff800) << 10) >> 21;
        //    System.out.println("GPUD SetDrawingOfffset "+m_drawOffsetX+","+m_drawOffsetY);
        return 0;
    }

    public static int gpudSetMaskMode(int[] data, int offset, int size) {
        // store the mask settings where they go

        // TODO ? more efficient to keep in low bits because of switch
        drawMode = (drawMode & ~(DRAWMODE_SET_MASK | DRAWMODE_CHECK_MASK)) | ((data[offset] & 0x1800) >> 11);
        return 0;
    }

    public static int gpuStatusRead32(int address) {
//    ASSERT( SANITY_CHECK, address==ADDR_GPU_CTRLSTATUS, "");
        int rc = 0;

        // -----------------------------------------------------------------------------
        // |1f |1e 1d|1c |1b |1a  |19 18|17 |16     |15     |14   |13    |12 11 |10    |
        // |lcf|dma  |com|img|busy| ?  ?|den|isinter|isrgb24|Video|Height|Width0|Width1|
        // -----------------------------------------------------------------------------

        // ----------------------------------------------------
        // |0f 0e 0d|0c|0b|0a  |09 |08 07|06 05|04|03 02 01 00|
        // | ?  ?  ?|me|md|dfe |dtd|tp   |abr  |ty|tx         |
        // ----------------------------------------------------

        // 0000 0000 0000 0000 0000 0111 1111 1111
//        rc |= getRenderer()->getDrawMode();
        rc |= drawMode;

        // TODO don't need this since it is part of draw mode ???
        // 0000 0000 0000 0000 0001 1000 0000 0000
        //rc |= m_maskMode<<11;

        // 0000 0000 0000 0000 1110 0000 0000 0000
        // 0000 0000 0111 1111 0000 0000 0000 0000
        rc |= displayMode << 16;

        // 0000 0000 1000 0000 0000 0000 0000 0000
        if (!m_displayEnabled)
            rc |= 0x00800000;

        // 0000 0011 0000 0000 0000 0000 0000 0000
        // 0000 0100 0000 0000 0000 0000 0000 0000
        rc |= 0x04000000; // gpu idle

        // TODO turn this off at some point
        // 0000 1000 0000 0000 0000 0000 0000 0000
        rc |= 0x08000000; // ready to receive img

        // 0001 0000 0000 0000 0000 0000 0000 0000
        rc |= 0x10000000; // ready to receive cmds

        // 0110 0000 0000 0000 0000 0000 0000 0000
        rc |= dmaMode << 29;

        // 1000 0000 0000 0000 0000 0000 0000 0000

        if (manager.getInterlaceField())
            rc |= 0x80000000; //for interlace

        // The poll detection code does not always work, so as a temporary workaround, add another backstop here to catch
        // vsync timeouts that never end
        if  (0 != (displayMode & 0x0040)) {
            if (++pollHackStatusReadCount >= 1000) {
                _poll(ADDR_GPU_CTRLSTATUS,4);
            }
        } else {
            if (++pollHackStatusReadCount >= 1000000) {
                // TonyHawk is still rarely (possibly race) waiting for this to change even in non interlace mode.
                // If something is waiting forever, this will at least wake it up
                manager.toggleInterlaceField();
                pollHackStatusReadCount = 0;
            }
        }

        return rc;
    }

    static int pollHackStatusReadCount;

    public void aboutToBlock() {
        manager.preAsync();
    }

    public void poll(int address, int size) {
        _poll(address, size);
    }

    protected static void _poll(int address, int size) {
        pollHackStatusReadCount = 0;
        assert address == ADDR_GPU_CTRLSTATUS;
        if (0 != (displayMode & 0x0040)) {
            //System.out.println("flicking interlace because of 1814 poll in interlaced mode");
            manager.toggleInterlaceField();
        }
        // note, this may not actually update anything... it will update anything if the mode has
        // changed, or someone has dirtied the visible display
        manager.vsync();
    }

    public static int gpuDataRead32(int address) {
        videoRAM = display.acquireDisplayBuffer();
        try {
            // used if we're doing vram to mem... these will
            // return 0 if we're not currently doing a vram to mem
            return readPixel() + (readPixel() << 16);
//            return (readPixel()<<16)+readPixel();
        } finally {
            display.releaseDisplayBuffer();
            videoRAM = null;
        }
    }

    private static class GPUDMAChannel extends DMAChannelOwnerBase {
        public final int getDMAChannel() {
            return DMAController.DMA_GPU;
        }

        public final String getName() {
            return "GPU";
        }

        public void beginDMATransferFromDevice(int base, int blocks, int blockSize, int ctrl) {
            if ((ctrl & 0x200) != 0) {
                int size = blocks * blockSize; // in dwords
                if (debugTransfers) System.out.println("*** LINEAR GPU DMA FROM VRAM *** size=" + (size * 4));
                try {
                    addressSpace.resolve(base, size * 4, true, m_resolveResult);
                    if (m_resolveResult.mem != null) {
                        videoRAM = display.acquireDisplayBuffer();
                        try {
                            // todo, do this more efficiently
                            int end = m_resolveResult.offset + size;
                            for (int i = m_resolveResult.offset; i < end; i++) {
                                //m_resolveResult.mem[i] = (readPixel()<<16)+readPixel();
                                m_resolveResult.mem[i] = readPixel() + (readPixel() << 16);
                            }
                        } finally {
                            display.releaseDisplayBuffer();
                            videoRAM = null;
                        }
                    }
                } finally {
                    signalTransferComplete();
                }
            } else {
                throw new IllegalStateException("not implemented");
            }
        }

        public void beginDMATransferToDevice(int base, int blocks, int blockSize, int ctrl) {
            try {
                if (ignoreGPU) {
                    debuggo();
                    return;
                }
                if ((ctrl & 0x200) != 0) {
                    if (debugTransfers) System.out.println("*** LINEAR GPU DMA ***");
                    // linear DMA
                    //LOG3P( GPU_COMMAND, "Linear DMA %08x %04x * %04x\n", base, blocks, blockSize);
                    //uint32 size = blocks*blockSize;
                    //uint32 *addr = (uint32 *)m_gpu->getAddressSpace()->resolve( base, size*4);
                    // TODO check alignment
                    //if (addr) {
                    //    m_gpu->handleGPUData( addr, size);
                    //}
                    int size = blocks * blockSize;
                    addressSpace.resolve(base, size, false, m_resolveResult);
                    if (m_resolveResult.mem != null) {
                        videoRAM = display.acquireDisplayBuffer();
                        try {
                            handleGPUData(m_resolveResult.mem, m_resolveResult.offset, size);
                        } finally {
                            display.releaseDisplayBuffer();
                            videoRAM = null;
                        }
                    }
                } else if ((ctrl & 0x400) != 0) {
                    if (debugTransfers) System.out.println("*** LINKED LIST GPU DMA ***");
                    // linked list DMA
                    // TODO check alignment
                    //int timeBefore = MTScheduler.getTime();
                    videoRAM = display.acquireDisplayBuffer();
                    try {
                        handleGPUDataChain(base);
                    } finally {
                        display.releaseDisplayBuffer();
                        videoRAM = null;
                    }
                    //System.out.println("chain time "+((MTScheduler.getTime()-timeBefore)>>4));
                }
                // TODO for now only
            } finally {
                signalTransferComplete();
            }
        }
    }

    private static class OTCDMAChannel extends DMAChannelOwnerBase {
        public final int getDMAChannel() {
            return DMAController.DMA_GPU_OTC;
        }

        public void beginDMATransferFromDevice(int base, int blocks, int blockSize, int ctrl) {
            if (ctrl == 0x11000002) {
                int count = blockSize;

                // we know that linked list must be in main RAM,
                // since addresses are only 24 bits.

                // todo clamp this to ram size?
                base &= 0xffffff;
                int end = base >> 2;
                int addr = end - count + 1;
                int[] mainRAM = addressSpace.getMainRAM();
                mainRAM[addr] = 0x00ffffff;
                while (addr < end) {
                    int tmp = addr;
                    mainRAM[++addr] = (tmp << 2);
                }

            }
            signalTransferComplete();
        }


        public final String getName() {
            return "GPU OTClear";
        }
    }

    public static void gpusReset(int val) {
        //System.out.println("GPUS RESET");
        gpusSetDispEnable(1);
        gpusSetDisplayMode(0);
        gpusSetDisplayOrigin(0);
        maskMode = 0;
        m_gpudState = GPUD_CMD_NONE;
        // todo, check this sets status to 14802000
    }

    private static void gpusCmdReset(int val) {
        //System.out.println("GPUS CMD RESET");
        m_gpudState = GPUD_CMD_NONE;
    }

    private static void gpusIRQReset(int val) {
        //System.out.println("GPUS IRQ RESET");
    }

    private static void gpusSetDispEnable(int val) {
        m_displayEnabled = ((val & 1) == 0) ? true : false;
        manager.setBlanked(!m_displayEnabled);
        //System.out.println("GPUS SET DISP ENABLE "+m_displayEnabled);
    }

    private static void gpusSetDataTransferMode(int val) {
        dmaMode = val & 3;
        //System.out.println("GPUS SET DATA TRANSFER MODE "+m_dmaMode);
    }

    private static void gpusSetDisplayOrigin(int val) {
        int originX = val & 0x3ff;
        int originY = (val >> 10) & 0x1ff;
        manager.setOrigin(originX, originY);
    }

    private static void gpusSetMonitorLeftRight(int val) {
        int l = val & 0xfff;
        int r = (val >> 12) & 0xfff;
        manager.setHorizontalTiming(l, r);
    }

    private static void gpusSetMonitorTopBottom(int val) {
        int t = val & 0x3ff;
        int b = (val >> 10) & 0x3ff;
        manager.setVerticalTiming(t, b);
    }

    private static void gpusSetDisplayMode(int val) {
        displayMode = ((val & 0x3f) << 1) | ((val & 0x40 >> 6));

        boolean doubleY = 0 != (val & 0x4);
        boolean pal = 0 != (val & 8);
        boolean rgb24 = 0 != (val & 0x10);
        boolean interlace = 0 != (val & 0x20);
        int divider = 8;

        // int isMystery = !!(data&0x80);
        switch (val & 0x43) {
            case 0:
                divider = 10;
                break;
            case 1:
                divider = 8;
                break;
            case 2:
                divider = 5;
                break;
            case 3:
                divider = 4;
                break;
            case 0x40:
                divider = 7;
                break;
        }
        if (dumpGPUD) {
            System.out.println("begin set display mode: div="+divider+" depth="+((rgb24)?24:16)+" intl="+interlace+" dbly="+doubleY+" pal="+pal);
        }
        manager.setPixelDivider(divider);
        manager.setRGB24(rgb24);
        manager.setInterlaced(interlace);
        manager.setDoubleY(doubleY);
        manager.setNTSC(!pal);
        if (dumpGPUD) {
            System.out.println("end set display mode");
        }
    }

    private static void gpusRequestInfo(int val) {
        val &= 0xffffffL;
        switch (val) {
            case 3:
                //LOG( GPU_COMMAND, "gpusRequestInfo - 3 old top left\n");
                //_gpu_data = 0;
                break;
            case 4:
                //LOG( GPU_COMMAND, "gpusRequestInfo - 4 old bottom right\n");
                //_gpu_data = 0xffffffff;
                break;
            case 5:
                //LOG( GPU_COMMAND, "gpusRequestInfo - 5 draw offset\n");
                //_gpu_data = 0x0;
                break;
            case 7:
                //System.out.println( "GET CPU VERSION" );
                addressSpace.internalWrite32(ADDR_GPU_DATA, 2);
                break;
            default:
                //LOG1P( GPU_COMMAND, "gpusRequestInfo - %d (unknown)\n", data);
                break;
        }
    }

/*
    private static void handleGPUData( int[] mem, int offset, int size)
    {
        while (size>0) {
            if (m_gpudState == GPUD_EXTRA_COMMAND_DATA) {
//                ASSERT( SANITY_CHECK, m_currentGPUDFunction!=0, "");
                int dwordsUsed = handleGPUDFunction( mem, offset, size);
//                ASSERT( SANITY_CHECK, dwordsUsed<=size, "");
                offset+=dwordsUsed;
                size-=dwordsUsed;
            } else {
                // TODO do better than this later
                gpuDataWrite32Internal( ADDR_GPU_DATA, mem[offset]);
                offset++;
                size--;
            }
        }
    }
    */

    private static void handleGPUData(int[] mem, int offset, int size) {
        int origOffset = offset;
        if (debugTransfers) System.out.println("HandleGPUData " + size + " dwords");
        while (size > 0) {
            switch (m_gpudState) {
                case GPUD_CMD_NONE:
                    m_gpudCommand = (mem[offset] >> 24) & 0xff;
                    if (debugTransfers)
                        System.out.println("New command at " + (offset - origOffset) + ": " + MiscUtil.toHex(m_gpudCommand, 2));
                    int count = m_gpudFunctionArgumentCount[m_gpudCommand];
                    if (size >= count) {
                        GPUDRouter.invoke(mem, offset, count);
                        offset += count;
                        size -= count;
                    } else {
                        // use cmdBuffer for filling
                        for (int i = 0; i < size; i++) {
                            m_cmdBuffer[i++] = mem[offset++];
                        }
                        cmdBufferUsed = size;
                        cmdBufferTarget = count;
                        m_gpudState = GPUD_CMD_EXTRA;
                        size = 0;
                    }
                    break;
                case GPUD_CMD_FILLING:
                    if (debugTransfers)
                        System.out.println("Next byte main command data at " + (offset - origOffset) + ": " + MiscUtil.toHex(m_gpudCommand, 2));
                    m_cmdBuffer[cmdBufferUsed++] = mem[offset++];
                    size--;
                    if (cmdBufferUsed == cmdBufferTarget) {
                        m_gpudState = GPUD_CMD_NONE;
                        GPUDRouter.invoke(m_cmdBuffer, 0, cmdBufferUsed);
                    }
                    break;
                case GPUD_CMD_EXTRA:
                    if (debugTransfers)
                        System.out.println("extra command data at " + (offset - origOffset) + ": " + MiscUtil.toHex(m_gpudCommand, 2));
                    int dwordsUsed = GPUDRouter.invoke(mem, offset, size);
                    assert (dwordsUsed <= size);
                    if (debugTransfers) System.out.println("consumed " + dwordsUsed);
                    size -= dwordsUsed;
                    offset += dwordsUsed;
                    break;
            }
        }
    }

    private static AddressSpace.ResolveResult m_resolveResult = new AddressSpace.ResolveResult();

    private static void handleGPUDataChain(int address) {
        address &= 0xffffff;
        int[] mainRAM = addressSpace.getMainRAM();
        while (address < AddressSpace.RAM_SIZE) {
            int head = mainRAM[address >> 2];
            //System.out.println("HEAD "+MiscUtil.toHex( head, 8));
            int count = (head >> 24) & 0xff;
            if (count > 0) {
                handleGPUData(mainRAM, (address >> 2) + 1, count);
            }
            address = head & 0xffffff;
            // TODO: maybe a temp thing
            if ((address & 0xffffff) == 0)
                break;
        }
    }

    public static final int makePixel(int val) {
        // todo inline this
        return makePixel((val & 0x1f) << 3, (val & 0x3e0) >> 2, (val & 0x7c00) >> 7, (val & 0x8000) >> 15);
    }

    public static final int makePixel(int r, int g, int b, int mask) {
        return (r << 16) | (g << 8) | b | (mask << 24);
    }

    public static final int unmakePixel(int pixel) {
        return ((pixel & 0xf8) << 7) | ((pixel & 0xf800) >> 6) | ((pixel & 0xf80000) >> 19) | ((pixel & 0x1000000) >> 9);
    }

    private static JavaClass m_TemplateTClass;
    private static JavaClass m_TemplateLClass;
    private static JavaClass m_TemplateSClass;
    private static JavaClass m_TemplateQClass;
    private static JavaClass m_TemplateRClass;

    private JavaClass getTemplateTClass() {
        if (m_TemplateTClass == null) {
            String filename = "org/jpsx/runtime/components/hardware/gpu/GPU$TemplateTriangleRenderer.class";
            final URL url = getClass().getClassLoader().getResource(filename);
            try {
                InputStream stream = url.openStream();
                try {
                    m_TemplateTClass = (new ClassParser(stream, filename)).parse();
                } finally {
                    stream.close();
                }
            } catch (IOException e) {
            }
        }
        return m_TemplateTClass.copy();
    }

    private JavaClass getTemplateSClass() {
        if (m_TemplateSClass == null) {
            String filename = "org/jpsx/runtime/components/hardware/gpu/GPU$TemplateSpriteRenderer.class";
            final URL url = getClass().getClassLoader().getResource(filename);
            try {
                InputStream stream = url.openStream();
                try {
                    m_TemplateSClass = (new ClassParser(stream, filename)).parse();
                } finally {
                    stream.close();
                }
            } catch (IOException e) {
            }
        }
        return m_TemplateSClass.copy();
    }

    private JavaClass getTemplateQClass() {
        if (m_TemplateQClass == null) {
            String filename = "org/jpsx/runtime/components/hardware/gpu/GPU$TemplateQuadRenderer.class";
            final URL url = getClass().getClassLoader().getResource(filename);
            try {
                InputStream stream = url.openStream();
                try {
                    m_TemplateQClass = (new ClassParser(stream, filename)).parse();
                } finally {
                    stream.close();
                }
            } catch (IOException e) {
            }
        }
        return m_TemplateQClass.copy();
    }

    private JavaClass getTemplateRClass() {
        if (m_TemplateRClass == null) {
            String filename = "org/jpsx/runtime/components/hardware/gpu/GPU$TemplateRectangleRenderer.class";
            final URL url = getClass().getClassLoader().getResource(filename);
            try {
                InputStream stream = url.openStream();
                try {
                    m_TemplateRClass = (new ClassParser(stream, filename)).parse();
                } finally {
                    stream.close();
                }
            } catch (IOException e) {
            }
        }
        return m_TemplateRClass.copy();
    }

    private JavaClass getTemplateLClass() {
        if (m_TemplateLClass == null) {
            String filename = "org/jpsx/runtime/components/hardware/gpu/GPU$TemplateLineRenderer.class";
            final URL url = getClass().getClassLoader().getResource(filename);
            try {
                InputStream stream = url.openStream();
                try {
                    m_TemplateLClass = (new ClassParser(stream, filename)).parse();
                } finally {
                    stream.close();
                }
            } catch (IOException e) {
            }
        }
        return m_TemplateLClass.copy();
    }

    // _T
    //   0 no tex
    //   4  4bit tex
    //   8  8bit tex
    //   6  16bit tex
    //    0  non gouraud
    //    1  gouraud
    //     0  non-semi
    //     1  5 plus 5
    //     2  10 plus 10
    //     3  10 minus 10
    //     4  10 plus 25
    //      0  no mask set
    //      1  mask set
    //       0  no mask check
    //       1  mask check
    //        0  non solid
    //        1  solid
    // TODO replace other occurences of field with their value
    public ClassGen generateClass(String classname) {
        ClassGen cgen;
        String suffix = classname.substring(classname.indexOf("$_") + 2);
        if (suffix.charAt(0) == 'T' || suffix.charAt(0) == 'L' || suffix.charAt(0) == 'S' || suffix.charAt(0) == 'Q' || suffix.charAt(0) == 'R') {
            int targetTextureType;
            switch (suffix.charAt(1)) {
                case'0':
                    targetTextureType = TEXTURE_NONE;
                    break;
                case'4':
                    targetTextureType = TEXTURE_4BIT;
                    break;
                case'8':
                    targetTextureType = TEXTURE_8BIT;
                    break;
                case'6':
                    targetTextureType = TEXTURE_16BIT;
                    break;
                case'5':
                    targetTextureType = TEXTURE_4BITW;
                    break;
                case'9':
                    targetTextureType = TEXTURE_8BITW;
                    break;
                case'7':
                    targetTextureType = TEXTURE_16BITW;
                    break;
                default:
                    throw new IllegalStateException("Unknown texture type " + suffix.charAt(1));
            }
            boolean targetGouraud;
            switch (suffix.charAt(2)) {
                case'0':
                    targetGouraud = false;
                    break;
                case'1':
                    targetGouraud = true;
                    break;
                default:
                    throw new IllegalStateException("Unknown gouraud flag " + suffix.charAt(2));
            }
            int targetSemi;
            switch (suffix.charAt(3)) {
                case'0':
                    targetSemi = SEMI_NONE;
                    break;
                case'1':
                    targetSemi = SEMI_5P5;
                    break;
                case'2':
                    targetSemi = SEMI_10P10;
                    break;
                case'3':
                    targetSemi = SEMI_10M10;
                    break;
                case'4':
                    targetSemi = SEMI_10P25;
                    break;
                default:
                    throw new IllegalStateException("Unknown semi flag " + suffix.charAt(3));
            }
            boolean targetMaskSet;
            switch (suffix.charAt(4)) {
                case'0':
                    targetMaskSet = false;
                    break;
                case'1':
                    targetMaskSet = true;
                    break;
                default:
                    throw new IllegalStateException("Unknown mask set flag " + suffix.charAt(4));
            }
            boolean targetMaskCheck;
            switch (suffix.charAt(5)) {
                case'0':
                    targetMaskCheck = false;
                    break;
                case'1':
                    targetMaskCheck = true;
                    break;
                default:
                    throw new IllegalStateException("Unknown mask check flag " + suffix.charAt(5));
            }
            boolean targetSolid;
            switch (suffix.charAt(6)) {
                case'0':
                    targetSolid = false;
                    break;
                case'1':
                    targetSolid = true;
                    break;
                default:
                    throw new IllegalStateException("Unknown solid flag " + suffix.charAt(6));
            }

            JavaClass jclass;
            if (suffix.charAt(0) == 'T') {
                jclass = getTemplateTClass();
            } else if (suffix.charAt(0) == 'L') {
                jclass = getTemplateLClass();
            } else if (suffix.charAt(0) == 'S') {
                jclass = getTemplateSClass();
            } else if (suffix.charAt(0) == 'R') {
                jclass = getTemplateRClass();
            } else {
                jclass = getTemplateQClass();
            }
            String origClassName = jclass.getClassName();
            jclass.setFileName(JPSXClassLoader.getClassFilename(classname));
            cgen = new ClassGen(jclass);
            ConstantPoolGen cp = cgen.getConstantPool();

            // note for some reason this messes up jad
            cgen.setClassName(classname);
            if (suffix.charAt(0) == 'Q') {
                int utfIndex = cp.lookupUtf8("org/jpsx/runtime/components/hardware/gpu/GPU$TemplateTriangleRenderer");
                cp.setConstant(utfIndex, new ConstantUtf8(classname.replace('Q', 'T').replace('.', '/')));
                utfIndex = cp.lookupUtf8("org/jpsx/runtime/components/hardware/gpu/GPU$TemplateRectangleRenderer");
                cp.setConstant(utfIndex, new ConstantUtf8(classname.replace('Q', 'R').replace('.', '/')));
            } else {
                // replace the string constant itself
                //int utfIndex = cp.lookupUtf8("org/jpsx/runtime/components/hardware/gpu/GPU$TemplateTriangleRenderer");
                //cp.setConstant( utfIndex, new ConstantUtf8( classname.replace('.','/')));


                Field field = cgen.containsField("_renderTextureType");
                FieldGen fg = new FieldGen(field, cp);
                fg.isFinal(true);
                fg.cancelInitValue();
                fg.setInitValue(targetTextureType);
                cgen.replaceField(field, fg.getField());

                field = cgen.containsField("_renderSemiType");
                fg = new FieldGen(field, cp);
                fg.isFinal(true);
                fg.cancelInitValue();
                fg.setInitValue(targetSemi);
                cgen.replaceField(field, fg.getField());

                field = cgen.containsField("_renderBReg");
                fg = new FieldGen(field, cp);
                fg.isFinal(true);
                fg.cancelInitValue();
                fg.setInitValue(targetGouraud && (targetTextureType != TEXTURE_NONE));
                cgen.replaceField(field, fg.getField());

                field = cgen.containsField("_renderGouraud");
                fg = new FieldGen(field, cp);
                fg.isFinal(true);
                fg.cancelInitValue();
                fg.setInitValue(targetGouraud);
                cgen.replaceField(field, fg.getField());

                field = cgen.containsField("_renderCheckMask");
                fg = new FieldGen(field, cp);
                fg.isFinal(true);
                fg.cancelInitValue();
                fg.setInitValue(targetMaskCheck);
                cgen.replaceField(field, fg.getField());

                field = cgen.containsField("_renderSetMask");
                fg = new FieldGen(field, cp);
                fg.isFinal(true);
                fg.cancelInitValue();
                fg.setInitValue(targetMaskSet);
                cgen.replaceField(field, fg.getField());

                field = cgen.containsField("_renderSolid");
                fg = new FieldGen(field, cp);
                fg.isFinal(true);
                fg.cancelInitValue();
                fg.setInitValue(targetSolid);
                cgen.replaceField(field, fg.getField());
            }

            // replace the class constant for the original class with our one, since it is used
            // in GETSTATIC methods
            cp.setConstant(cp.lookupClass(origClassName), cp.getConstant(cp.lookupClass(classname)));
            return cgen;
        }
        throw new IllegalStateException("Unknown inner class to generate " + classname);
    }

    // 0000 tiim rrrr rrrr gggg gggg bbbb bbbb

    public static void setVRAMFormat(boolean rgb24) {
        if (!rgb24conversion) return;
        if (rgb24 != GPU.rgb24) {
            //System.out.println( "Convert VRAM to " + ((rgb24) ? "24 bit" : "15 bit") );
            GPU.rgb24 = rgb24;
            int[] ram = display.acquireDisplayBuffer();
            if (rgb24) {
                //        G0B0      B1R0      R1G1
                // to:
                //    A_R0G0B0  B_R0G0B1  C_R1G1B1
                int last = 0;
                for (int i = 0; i < 1024 * 512; i++) {
                    int pixel = ram[i];
                    switch (pixel & GPU_RGBXX_X_MASK) {
                        case GPU_RGB15_A: {
                            int decoded = unmakePixel(pixel);
                            int decoded2 = unmakePixel(ram[i + 1]);
                            ram[i] = GPU_RGB24_A | decoded | ((decoded2 & 0xff) << 16);
                            last = decoded;
                            break;
                        }
                        case GPU_RGB15_B: {
                            int decoded = unmakePixel(pixel);
                            int pp = last | ((decoded & 0xff) << 16);
                            ram[i] = GPU_RGB24_B | (pp & 0xffff00) | ((decoded >> 8) & 0xff);
                            last = decoded;
                            break;
                        }
                        case GPU_RGB15_C: {
                            int decoded = unmakePixel(pixel);
                            ram[i] = GPU_RGB24_C | (decoded << 8) | ((last >> 8) & 0xff);
                            break;
                        }
                    }
                }
            } else {
                //    A_R0G0B0  B_R0G0B1  C_R1G1B1
                // to:
                //        G0B0      B1R0      R1G1
                for (int i = 0; i < 1024 * 512; i++) {
                    int pixel = ram[i];
                    switch (pixel & GPU_RGBXX_X_MASK) {
                        case GPU_RGB24_A: {
                            ram[i] = GPU_RGB15_A | makePixel((pixel & 0xffff));
                            break;
                        }
                        case GPU_RGB24_B: {
                            ram[i] = GPU_RGB15_B | makePixel(((pixel & 0xff) << 8) | ((pixel >> 16) & 0xff));
                            break;
                        }
                        case GPU_RGB24_C: {
                            ram[i] = GPU_RGB15_C | makePixel(((pixel >> 8) & 0xffff));
                            break;
                        }
                    }
                }
            }
            display.releaseDisplayBuffer();
        }
    }

    // Temporary hack to show stuff that is missing - we shoud do this with a system wide log4j channel
    static int maxMissing = 10;
    private static void missing(String s) {
        if (maxMissing > 0) {
            maxMissing --;
            System.out.println("Missing "+s);
        }
    }
}


