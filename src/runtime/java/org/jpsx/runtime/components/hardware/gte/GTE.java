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
package org.jpsx.runtime.components.hardware.gte;

import org.apache.bcel.generic.*;
import org.apache.log4j.Logger;
import org.jpsx.api.components.core.addressspace.AddressSpace;
import org.jpsx.api.components.core.cpu.*;
import org.jpsx.runtime.JPSXComponent;
import org.jpsx.runtime.components.core.CoreComponentConnections;
import org.jpsx.runtime.util.ClassUtil;
import org.jpsx.runtime.util.MiscUtil;

// TODO should vz be 16 bits only when read?

public final class GTE extends JPSXComponent implements InstructionProvider {
    private static final boolean debugLimit = false;
    private static final boolean debugLimitB = false;
    private static final boolean debugLimitG = false;
    private static final boolean debugLimitD = false;
    private static final Logger log = Logger.getLogger("GTE");
    private static final String CLASS = GTE.class.getName();
    private static final String VECTOR_CLASS = Vector.class.getName();
    private static final String VECTOR_SIGNATURE = ClassUtil.signatureOfClass(VECTOR_CLASS);
    private static final String MATRIX_CLASS = Matrix.class.getName();
    private static final String MATRIX_SIGNATURE = ClassUtil.signatureOfClass(MATRIX_CLASS);

    private static final int R_VXY0 = 0;
    private static final int R_VZ0 = 1;
    private static final int R_VXY1 = 2;
    private static final int R_VZ1 = 3;
    private static final int R_VXY2 = 4;
    private static final int R_VZ2 = 5;
    private static final int R_RGB = 6;
    private static final int R_OTZ = 7;
    private static final int R_IR0 = 8;
    private static final int R_IR1 = 9;
    private static final int R_IR2 = 10;
    private static final int R_IR3 = 11;
    private static final int R_SXY0 = 12;
    private static final int R_SXY1 = 13;
    private static final int R_SXY2 = 14;
    private static final int R_SXYP = 15;
    private static final int R_SZX = 16;
    private static final int R_SZ0 = 17;
    private static final int R_SZ1 = 18;
    private static final int R_SZ2 = 19;
    private static final int R_RGB0 = 20;
    private static final int R_RGB1 = 21;
    private static final int R_RGB2 = 22;
    private static final int R_RES1 = 23;
    private static final int R_MAC0 = 24;
    private static final int R_MAC1 = 25;
    private static final int R_MAC2 = 26;
    private static final int R_MAC3 = 27;
    private static final int R_IRGB = 28;
    private static final int R_ORGB = 29;
    private static final int R_LZCS = 30;
    private static final int R_LZCR = 31;
    private static final int R_R11R12 = 32;
    private static final int R_R13R21 = 33;
    private static final int R_R22R23 = 34;
    private static final int R_R31R32 = 35;
    private static final int R_R33 = 36;
    private static final int R_TRX = 37;
    private static final int R_TRY = 38;
    private static final int R_TRZ = 39;
    private static final int R_L11L12 = 40;
    private static final int R_L13L21 = 41;
    private static final int R_L22L23 = 42;
    private static final int R_L31L32 = 43;
    private static final int R_L33 = 44;
    private static final int R_RBK = 45;
    private static final int R_GBK = 46;
    private static final int R_BBK = 47;
    private static final int R_LR1LR2 = 48;
    private static final int R_LR3LG1 = 49;
    private static final int R_LG2LG3 = 50;
    private static final int R_LB1LB2 = 51;
    private static final int R_LB3 = 52;
    private static final int R_RFC = 53;
    private static final int R_GFC = 54;
    private static final int R_BFC = 55;
    private static final int R_OFX = 56;
    private static final int R_OFY = 57;
    private static final int R_H = 58;
    private static final int R_DQA = 59;
    private static final int R_DQB = 60;
    private static final int R_ZSF3 = 61;
    private static final int R_ZSF4 = 62;
    private static final int R_FLAG = 63;

    private static final int GTE_SF_MASK = 0x80000;

    private static final int GTE_MX_MASK = 0x60000;
    private static final int GTE_MX_ROTATION = 0x00000;
    private static final int GTE_MX_LIGHT = 0x20000;
    private static final int GTE_MX_COLOR = 0x40000;

    private static final int GTE_V_MASK = 0x18000;
    private static final int GTE_V_V0 = 0x00000;
    private static final int GTE_V_V1 = 0x08000;
    private static final int GTE_V_V2 = 0x10000;
    private static final int GTE_V_IR = 0x18000;

    private static final int GTE_CV_MASK = 0x06000;
    private static final int GTE_CV_TR = 0x00000;
    private static final int GTE_CV_BK = 0x02000;
    private static final int GTE_CV_FC = 0x04000;
    private static final int GTE_CV_NONE = 0x06000;

    private static final int GTE_LM_MASK = 0x00400;

    private static final int GTE_ALL_MASKS = (GTE_SF_MASK | GTE_MX_MASK | GTE_V_MASK | GTE_CV_MASK | GTE_LM_MASK);

//    public static void setFlag( int bits)
//    {
//        reg_flag |= bits;
//
//        // TODO check these flags!
//        // CHK is set if any flag in 0x7FC7E000 is set
//        //      (A1-A3 B1-B3 D E FP FN G1 G2)
//
//        if (0!=(bits & 0x7fc7e000)) {
//            reg_flag|=FLAG_CHK;
//        }
//    }

// removed above, and rolled FLAG_CHK into them automatically

    private static final int FLAG_CHK = 0x80000000;
    private static final int FLAG_A1P = 0x40000000 | FLAG_CHK;
    private static final int FLAG_A2P = 0x20000000 | FLAG_CHK;
    private static final int FLAG_A3P = 0x10000000 | FLAG_CHK;
    private static final int FLAG_A1N = 0x08000000 | FLAG_CHK;
    private static final int FLAG_A2N = 0x04000000 | FLAG_CHK;
    private static final int FLAG_A3N = 0x02000000 | FLAG_CHK;
    private static final int FLAG_B1 = 0x01000000 | FLAG_CHK;
    private static final int FLAG_B2 = 0x00800000 | FLAG_CHK;
    private static final int FLAG_B3 = 0x00400000;
    private static final int FLAG_C1 = 0x00200000;
    private static final int FLAG_C2 = 0x00100000;
    private static final int FLAG_C3 = 0x00080000;
    private static final int FLAG_D = 0x00040000 | FLAG_CHK;
    private static final int FLAG_E = 0x00020000 | FLAG_CHK;
    private static final int FLAG_FP = 0x00010000 | FLAG_CHK;
    private static final int FLAG_FN = 0x00008000 | FLAG_CHK;
    private static final int FLAG_G1 = 0x00004000 | FLAG_CHK;
    private static final int FLAG_G2 = 0x00002000 | FLAG_CHK;
    private static final int FLAG_H = 0x00001000;

    private static final long BIT43 = 0x80000000000L;
    private static final long BIT31 = 0x80000000L;
    private static final long BIT47 = 0x800000000000L;

    public static class Vector {
        public int x, y, z;
    }

    public static class Matrix {
        public int m11, m12, m13, m21, m22, m23, m31, m32, m33;
    }

    /**
     * Note with respect to internal registers that aren't signed 32 bit values on the playstation:
     *
     * All internal registers here are kept as signed 32 bit values. If the actual register on PSX is say a 16 bit signed value
     * it is sign extended to 32 bits as part of the register write. If it is a 16 bit unsigned value, it is zero extended to 32 bits as
     * part of the register write.
     *
     * Equally the reverse is done when reading GTE regs (i.e. a signed 32 bit internal value may only have 16 bits exposed)
     */

    public static Vector reg_v0 = new Vector();
    public static Vector reg_v1 = new Vector();
    public static Vector reg_v2 = new Vector();

    public static int reg_rgb;
    public static int reg_otz;

    public static int reg_ir0;
    public static int reg_ir1;
    public static int reg_ir2;
    public static int reg_ir3;

    public static int reg_sx0; // checked
    public static int reg_sy0; // checked
    public static int reg_sx1; // checked
    public static int reg_sy1; // checked
    public static int reg_sx2; // checked
    public static int reg_sy2; // checked
    public static int reg_sxp; // checked
    public static int reg_syp; // checked

    public static int reg_szx; // checked
    public static int reg_sz0; // checked
    public static int reg_sz1; // checked
    public static int reg_sz2; // checked
    public static int reg_rgb0;
    public static int reg_rgb1;
    public static int reg_rgb2;
    public static int reg_res1;
    public static int reg_mac0;
    public static int reg_mac1;
    public static int reg_mac2;
    public static int reg_mac3;
//    public static int reg_irgb; // checked
//    public static int reg_orgb; // checked
    public static int reg_lzcr; // checked
    public static int reg_lzcs; // checked

    public static Matrix reg_rot = new Matrix();

    public static int reg_trx;
    public static int reg_try;
    public static int reg_trz;

    public static Matrix reg_ls = new Matrix();
    public static int reg_rbk;
    public static int reg_gbk;
    public static int reg_bbk;

    public static Matrix reg_lc = new Matrix();
    public static int reg_rfc;
    public static int reg_gfc;
    public static int reg_bfc;

    public static int reg_ofx;
    public static int reg_ofy;
    public static int reg_h;
    public static int reg_dqa;
    public static int reg_dqb;

    public static int reg_zsf3;
    public static int reg_zsf4;
    public static int reg_flag;

    public GTE() {
        super("JPSX Geometry Transform Engine");
    }

    private static AddressSpace addressSpace;
    private static R3000 r3000;
    private static int[] r3000regs;

    private static void emitReadReg(InstructionList il, CompilationContext context, int reg) {
        ConstantPoolGen cp = context.getConstantPoolGen();

        if (reg < 32) {
            switch (reg) {
                case R_VXY0:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_v0", VECTOR_SIGNATURE)));
                    il.append(new GETFIELD(context.getConstantPoolGen().addFieldref(VECTOR_CLASS, "x", "I")));
                    il.append(new PUSH(cp, 0xffff));
                    il.append(new IAND());
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_v0", VECTOR_SIGNATURE)));
                    il.append(new GETFIELD(context.getConstantPoolGen().addFieldref(VECTOR_CLASS, "y", "I")));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new IOR());
                    break;
                case R_VZ0:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_v0", VECTOR_SIGNATURE)));
                    il.append(new GETFIELD(context.getConstantPoolGen().addFieldref(VECTOR_CLASS, "z", "I")));
                    break;
                case R_VXY1:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_v1", VECTOR_SIGNATURE)));
                    il.append(new GETFIELD(context.getConstantPoolGen().addFieldref(VECTOR_CLASS, "x", "I")));
                    il.append(new PUSH(cp, 0xffff));
                    il.append(new IAND());
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_v1", VECTOR_SIGNATURE)));
                    il.append(new GETFIELD(context.getConstantPoolGen().addFieldref(VECTOR_CLASS, "y", "I")));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new IOR());
                    break;
                case R_VZ1:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_v1", VECTOR_SIGNATURE)));
                    il.append(new GETFIELD(context.getConstantPoolGen().addFieldref(VECTOR_CLASS, "z", "I")));
                    break;
                case R_VXY2:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_v2", VECTOR_SIGNATURE)));
                    il.append(new GETFIELD(context.getConstantPoolGen().addFieldref(VECTOR_CLASS, "x", "I")));
                    il.append(new PUSH(cp, 0xffff));
                    il.append(new IAND());
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_v2", VECTOR_SIGNATURE)));
                    il.append(new GETFIELD(context.getConstantPoolGen().addFieldref(VECTOR_CLASS, "y", "I")));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new IOR());
                    break;
                case R_VZ2:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_v2", VECTOR_SIGNATURE)));
                    il.append(new GETFIELD(context.getConstantPoolGen().addFieldref(VECTOR_CLASS, "z", "I")));
                    break;
                case R_RGB:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_rgb", "I")));
                    break;
                case R_OTZ:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_otz", "I")));
                    break;
                case R_IR0:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_ir0", "I")));
                    break;
                case R_IR1:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_ir1", "I")));
                    break;
                case R_IR2:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_ir2", "I")));
                    break;
                case R_IR3:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_ir3", "I")));
                    break;
                case R_SXY0:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_sx0", "I")));
                    il.append(new PUSH(cp, 0xffff));
                    il.append(new IAND());
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_sy0", "I")));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new IOR());
                    break;
                case R_SXY1:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_sx1", "I")));
                    il.append(new PUSH(cp, 0xffff));
                    il.append(new IAND());
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_sy1", "I")));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new IOR());
                    break;
                case R_SXY2:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_sx2", "I")));
                    il.append(new PUSH(cp, 0xffff));
                    il.append(new IAND());
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_sy2", "I")));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new IOR());
                    break;
                case R_SXYP:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_sxp", "I")));
                    il.append(new PUSH(cp, 0xffff));
                    il.append(new IAND());
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_syp", "I")));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new IOR());
                    break;
                case R_SZX:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_szx", "I")));
                    break;
                case R_SZ0:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_sz0", "I")));
                    break;
                case R_SZ1:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_sz1", "I")));
                    break;
                case R_SZ2:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_sz2", "I")));
                    break;
                case R_RGB0:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_rgb0", "I")));
                    break;
                case R_RGB1:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_rgb1", "I")));
                    break;
                case R_RGB2:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_rgb2", "I")));
                    break;
                case R_RES1:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_res1", "I")));
                    break;
                case R_MAC0:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_mac0", "I")));
                    break;
                case R_MAC1:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_mac1", "I")));
                    break;
                case R_MAC2:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_mac2", "I")));
                    break;
                case R_MAC3:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_mac3", "I")));
                    break;
                case R_IRGB:
                    il.append(new INVOKESTATIC(cp.addMethodref(CLASS, "readIORGB", "()V")));
                    break;
                case R_ORGB:
                    il.append(new INVOKESTATIC(cp.addMethodref(CLASS, "readIORGB", "()V")));
                    break;
                case R_LZCS:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_lzcs", "I")));
                    break;
                case R_LZCR:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_lzcr", "I")));
                    break;
            }
        } else {
            switch (reg) {
                case R_R11R12:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_rot", MATRIX_SIGNATURE)));
                    il.append(new GETFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m11", "I")));
                    il.append(new PUSH(cp, 0xffff));
                    il.append(new IAND());
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_rot", MATRIX_SIGNATURE)));
                    il.append(new GETFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m12", "I")));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new IOR());
                    break;
                case R_R13R21:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_rot", MATRIX_SIGNATURE)));
                    il.append(new GETFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m13", "I")));
                    il.append(new PUSH(cp, 0xffff));
                    il.append(new IAND());
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_rot", MATRIX_SIGNATURE)));
                    il.append(new GETFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m21", "I")));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new IOR());
                    break;
                case R_R22R23:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_rot", MATRIX_SIGNATURE)));
                    il.append(new GETFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m22", "I")));
                    il.append(new PUSH(cp, 0xffff));
                    il.append(new IAND());
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_rot", MATRIX_SIGNATURE)));
                    il.append(new GETFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m23", "I")));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new IOR());
                    break;
                case R_R31R32:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_rot", MATRIX_SIGNATURE)));
                    il.append(new GETFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m31", "I")));
                    il.append(new PUSH(cp, 0xffff));
                    il.append(new IAND());
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_rot", MATRIX_SIGNATURE)));
                    il.append(new GETFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m32", "I")));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new IOR());
                    break;
                case R_R33:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_rot", MATRIX_SIGNATURE)));
                    il.append(new GETFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m33", "I")));
                    il.append(new PUSH(cp, 0xffff));
                    il.append(new IAND());
                    break;
                case R_TRX:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_trx", "I")));
                    break;
                case R_TRY:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_try", "I")));
                    break;
                case R_TRZ:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_trz", "I")));
                    break;
                case R_L11L12:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_ls", MATRIX_SIGNATURE)));
                    il.append(new GETFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m11", "I")));
                    il.append(new PUSH(cp, 0xffff));
                    il.append(new IAND());
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_ls", MATRIX_SIGNATURE)));
                    il.append(new GETFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m12", "I")));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new IOR());
                    break;
                case R_L13L21:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_ls", MATRIX_SIGNATURE)));
                    il.append(new GETFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m13", "I")));
                    il.append(new PUSH(cp, 0xffff));
                    il.append(new IAND());
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_ls", MATRIX_SIGNATURE)));
                    il.append(new GETFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m21", "I")));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new IOR());
                    break;
                case R_L22L23:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_ls", MATRIX_SIGNATURE)));
                    il.append(new GETFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m22", "I")));
                    il.append(new PUSH(cp, 0xffff));
                    il.append(new IAND());
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_ls", MATRIX_SIGNATURE)));
                    il.append(new GETFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m23", "I")));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new IOR());
                    break;
                case R_L31L32:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_ls", MATRIX_SIGNATURE)));
                    il.append(new GETFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m31", "I")));
                    il.append(new PUSH(cp, 0xffff));
                    il.append(new IAND());
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_ls", MATRIX_SIGNATURE)));
                    il.append(new GETFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m32", "I")));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new IOR());
                    break;
                case R_L33:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_ls", MATRIX_SIGNATURE)));
                    il.append(new GETFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m13", "I")));
                    il.append(new PUSH(cp, 0xffff));
                    il.append(new IAND());
                    break;
                case R_RBK:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_rbk", "I")));
                    break;
                case R_GBK:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_gbk", "I")));
                    break;
                case R_BBK:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_bbk", "I")));
                    break;
                case R_LR1LR2:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_lc", MATRIX_SIGNATURE)));
                    il.append(new GETFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m11", "I")));
                    il.append(new PUSH(cp, 0xffff));
                    il.append(new IAND());
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_lc", MATRIX_SIGNATURE)));
                    il.append(new GETFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m12", "I")));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new IOR());
                    break;
                case R_LR3LG1:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_lc", MATRIX_SIGNATURE)));
                    il.append(new GETFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m13", "I")));
                    il.append(new PUSH(cp, 0xffff));
                    il.append(new IAND());
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_lc", MATRIX_SIGNATURE)));
                    il.append(new GETFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m21", "I")));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new IOR());
                    break;
                case R_LG2LG3:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_lc", MATRIX_SIGNATURE)));
                    il.append(new GETFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m22", "I")));
                    il.append(new PUSH(cp, 0xffff));
                    il.append(new IAND());
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_lc", MATRIX_SIGNATURE)));
                    il.append(new GETFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m23", "I")));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new IOR());
                    break;
                case R_LB1LB2:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_lc", MATRIX_SIGNATURE)));
                    il.append(new GETFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m31", "I")));
                    il.append(new PUSH(cp, 0xffff));
                    il.append(new IAND());
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_lc", MATRIX_SIGNATURE)));
                    il.append(new GETFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m32", "I")));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new IOR());
                    break;
                case R_LB3:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_lc", MATRIX_SIGNATURE)));
                    il.append(new GETFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m33", "I")));
                    il.append(new PUSH(cp, 0xffff));
                    il.append(new IAND());
                    break;
                case R_RFC:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_rfc", "I")));
                    break;
                case R_GFC:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_gfc", "I")));
                    break;
                case R_BFC:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_bfc", "I")));
                    break;
                case R_OFX:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_ofx", "I")));
                    break;
                case R_OFY:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_ofy", "I")));
                    break;
                case R_H:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_h", "I")));
                    // this is according to docs that it is accidentally sign extended
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    break;
                case R_DQA:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_dqa", "I")));
                    break;
                case R_DQB:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_dqb", "I")));
                    break;
                case R_ZSF3:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_zsf3", "I")));
                    break;
                case R_ZSF4:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_zsf4", "I")));
                    break;
                case R_FLAG:
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_flag", "I")));
                    break;
            }
        }
    }

    private static void emitWriteReg(InstructionList il, CompilationContext context, int reg) {
        ConstantPoolGen cp = context.getConstantPoolGen();
        int temp = context.getTempLocal(0);

        if (reg < 32) {
            switch (reg) {
                case R_VXY0:
                    il.append(new ISTORE(temp));
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_v0", VECTOR_SIGNATURE)));
                    il.append(new ILOAD(temp));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTFIELD(context.getConstantPoolGen().addFieldref(VECTOR_CLASS, "x", "I")));
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_v0", VECTOR_SIGNATURE)));
                    il.append(new ILOAD(temp));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTFIELD(context.getConstantPoolGen().addFieldref(VECTOR_CLASS, "y", "I")));
                    break;
                case R_VZ0:
                    il.append(new ISTORE(temp));
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_v0", VECTOR_SIGNATURE)));
                    il.append(new ILOAD(temp));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTFIELD(context.getConstantPoolGen().addFieldref(VECTOR_CLASS, "z", "I")));
                    break;
                case R_VXY1:
                    il.append(new ISTORE(temp));
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_v1", VECTOR_SIGNATURE)));
                    il.append(new ILOAD(temp));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTFIELD(context.getConstantPoolGen().addFieldref(VECTOR_CLASS, "x", "I")));
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_v1", VECTOR_SIGNATURE)));
                    il.append(new ILOAD(temp));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTFIELD(context.getConstantPoolGen().addFieldref(VECTOR_CLASS, "y", "I")));
                    break;
                case R_VZ1:
                    il.append(new ISTORE(temp));
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_v1", VECTOR_SIGNATURE)));
                    il.append(new ILOAD(temp));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTFIELD(context.getConstantPoolGen().addFieldref(VECTOR_CLASS, "z", "I")));
                    break;
                case R_VXY2:
                    il.append(new ISTORE(temp));
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_v2", VECTOR_SIGNATURE)));
                    il.append(new ILOAD(temp));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTFIELD(context.getConstantPoolGen().addFieldref(VECTOR_CLASS, "x", "I")));
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_v2", VECTOR_SIGNATURE)));
                    il.append(new ILOAD(temp));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTFIELD(context.getConstantPoolGen().addFieldref(VECTOR_CLASS, "y", "I")));
                    break;
                case R_VZ2:
                    il.append(new ISTORE(temp));
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_v2", VECTOR_SIGNATURE)));
                    il.append(new ILOAD(temp));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTFIELD(context.getConstantPoolGen().addFieldref(VECTOR_CLASS, "z", "I")));
                    break;
                case R_RGB:
                    il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_rgb", "I")));
                    break;
                case R_OTZ:
                    il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_otz", "I")));
                    break;
                case R_IR0:
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_ir0", "I")));
                    break;
                case R_IR1:
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_ir1", "I")));
                    break;
                case R_IR2:
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_ir2", "I")));
                    break;
                case R_IR3:
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_ir3", "I")));
                    break;
                case R_SXY0:
                    il.append(new ISTORE(temp));
                    il.append(new ILOAD(temp));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_sx0", "I")));
                    il.append(new ILOAD(temp));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_sy0", "I")));
                    break;
                case R_SXY1:
                    il.append(new ISTORE(temp));
                    il.append(new ILOAD(temp));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_sx1", "I")));
                    il.append(new ILOAD(temp));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_sy1", "I")));
                    break;
                case R_SXY2:
                    il.append(new ISTORE(temp));
                    il.append(new ILOAD(temp));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_sx2", "I")));
                    il.append(new ILOAD(temp));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_sy2", "I")));
                    break;
                case R_SZX:
                    il.append(new PUSH(cp, 0xffff));
                    il.append(new IAND());
                    il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_szx", "I")));
                    break;
                case R_SZ0:
                    il.append(new PUSH(cp, 0xffff));
                    il.append(new IAND());
                    il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_sz0", "I")));
                    break;
                case R_SZ1:
                    il.append(new PUSH(cp, 0xffff));
                    il.append(new IAND());
                    il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_sz1", "I")));
                    break;
                case R_SZ2:
                    il.append(new PUSH(cp, 0xffff));
                    il.append(new IAND());
                    il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_sz2", "I")));
                    break;
                case R_RGB0:
                    il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_rgb0", "I")));
                    break;
                case R_RGB1:
                    il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_rgb1", "I")));
                    break;
                case R_RGB2:
                    il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_rgb2", "I")));
                    break;
                case R_RES1:
                    il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_res1", "I")));
                    break;
                case R_MAC0:
                    il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_mac0", "I")));
                    break;
                case R_MAC1:
                    il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_mac1", "I")));
                    break;
                case R_MAC2:
                    il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_mac2", "I")));
                    break;
                case R_MAC3:
                    il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_mac3", "I")));
                    break;
                case R_IRGB:
                    il.append(new INVOKESTATIC(cp.addMethodref(CLASS, "writeIRGB", "(I)V")));
                    break;
                case R_ORGB:
                    il.append(new POP()); // read only
                    break;
                case R_LZCR:
                    il.append(new POP()); // read only
                    break;
//                case R_LZCS:
//                    fall thru for writeRegister
//                case R_SXYP:
//                    fall thru for writeRegister
                default:
                    il.append(new ISTORE(temp));
                    il.append(new PUSH(cp, reg));
                    il.append(new ILOAD(temp));
//                    il.append(new PUSH(cp, reg));
//                    il.append(new SWAP());
                    il.append(new INVOKESTATIC(cp.addMethodref(CLASS, "writeRegister", "(II)V")));
                    break;
            }
        } else {
            switch (reg) {
                case R_R11R12:
                    il.append(new ISTORE(temp));
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_rot", MATRIX_SIGNATURE)));
                    il.append(new ILOAD(temp));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m11", "I")));
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_rot", MATRIX_SIGNATURE)));
                    il.append(new ILOAD(temp));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m12", "I")));
                    break;
                case R_R13R21:
                    il.append(new ISTORE(temp));
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_rot", MATRIX_SIGNATURE)));
                    il.append(new ILOAD(temp));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m13", "I")));
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_rot", MATRIX_SIGNATURE)));
                    il.append(new ILOAD(temp));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m21", "I")));
                    break;
                case R_R22R23:
                    il.append(new ISTORE(temp));
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_rot", MATRIX_SIGNATURE)));
                    il.append(new ILOAD(temp));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m22", "I")));
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_rot", MATRIX_SIGNATURE)));
                    il.append(new ILOAD(temp));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m23", "I")));
                    break;
                case R_R31R32:
                    il.append(new ISTORE(temp));
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_rot", MATRIX_SIGNATURE)));
                    il.append(new ILOAD(temp));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m31", "I")));
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_rot", MATRIX_SIGNATURE)));
                    il.append(new ILOAD(temp));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m32", "I")));
                    break;
                case R_R33:
                    il.append(new ISTORE(temp));
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_rot", MATRIX_SIGNATURE)));
                    il.append(new ILOAD(temp));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m33", "I")));
                    break;
                case R_TRX:
                    il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_trx", "I")));
                    break;
                case R_TRY:
                    il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_try", "I")));
                    break;
                case R_TRZ:
                    il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_trz", "I")));
                    break;
                case R_L11L12:
                    il.append(new ISTORE(temp));
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_ls", MATRIX_SIGNATURE)));
                    il.append(new ILOAD(temp));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m11", "I")));
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_ls", MATRIX_SIGNATURE)));
                    il.append(new ILOAD(temp));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m12", "I")));
                    break;
                case R_L13L21:
                    il.append(new ISTORE(temp));
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_ls", MATRIX_SIGNATURE)));
                    il.append(new ILOAD(temp));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m13", "I")));
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_ls", MATRIX_SIGNATURE)));
                    il.append(new ILOAD(temp));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m21", "I")));
                    break;
                case R_L22L23:
                    il.append(new ISTORE(temp));
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_ls", MATRIX_SIGNATURE)));
                    il.append(new ILOAD(temp));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m22", "I")));
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_ls", MATRIX_SIGNATURE)));
                    il.append(new ILOAD(temp));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m23", "I")));
                    break;
                case R_L31L32:
                    il.append(new ISTORE(temp));
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_ls", MATRIX_SIGNATURE)));
                    il.append(new ILOAD(temp));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m31", "I")));
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_ls", MATRIX_SIGNATURE)));
                    il.append(new ILOAD(temp));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m32", "I")));
                    break;
                case R_L33:
                    il.append(new ISTORE(temp));
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_ls", MATRIX_SIGNATURE)));
                    il.append(new ILOAD(temp));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m33", "I")));
                    break;
                case R_RBK:
                    il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_rbk", "I")));
                    break;
                case R_GBK:
                    il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_gbk", "I")));
                    break;
                case R_BBK:
                    il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_bbk", "I")));
                    break;
                case R_LR1LR2:
                    il.append(new ISTORE(temp));
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_lc", MATRIX_SIGNATURE)));
                    il.append(new ILOAD(temp));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m11", "I")));
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_lc", MATRIX_SIGNATURE)));
                    il.append(new ILOAD(temp));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m12", "I")));
                    break;
                case R_LR3LG1:
                    il.append(new ISTORE(temp));
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_lc", MATRIX_SIGNATURE)));
                    il.append(new ILOAD(temp));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m13", "I")));
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_lc", MATRIX_SIGNATURE)));
                    il.append(new ILOAD(temp));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m21", "I")));
                    break;
                case R_LG2LG3:
                    il.append(new ISTORE(temp));
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_lc", MATRIX_SIGNATURE)));
                    il.append(new ILOAD(temp));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m22", "I")));
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_lc", MATRIX_SIGNATURE)));
                    il.append(new ILOAD(temp));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m23", "I")));
                    break;
                case R_LB1LB2:
                    il.append(new ISTORE(temp));
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_lc", MATRIX_SIGNATURE)));
                    il.append(new ILOAD(temp));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m31", "I")));
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_lc", MATRIX_SIGNATURE)));
                    il.append(new ILOAD(temp));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m32", "I")));
                    break;
                case R_LB3:
                    il.append(new ISTORE(temp));
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_lc", MATRIX_SIGNATURE)));
                    il.append(new ILOAD(temp));
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTFIELD(context.getConstantPoolGen().addFieldref(MATRIX_CLASS, "m33", "I")));
                    break;
                case R_RFC:
                    il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_rfc", "I")));
                    break;
                case R_GFC:
                    il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_gfc", "I")));
                    break;
                case R_BFC:
                    il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_bfc", "I")));
                    break;
                case R_OFX:
                    il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_ofx", "I")));
                    break;
                case R_OFY:
                    il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_ofy", "I")));
                    break;
                case R_H:
                    il.append(new PUSH(cp, 0xffff));
                    il.append(new IAND());
                    il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_h", "I")));
                    break;
                case R_DQA:
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_dqa", "I")));
                    break;
                case R_DQB:
                    il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_dqb", "I")));
                    break;
                case R_ZSF3:
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_zsf3", "I")));
                    break;
                case R_ZSF4:
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHL());
                    il.append(new PUSH(cp, 16));
                    il.append(new ISHR());
                    il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_zsf4", "I")));
                    break;
                case R_FLAG:
                    il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(CLASS, "reg_flag", "I")));
                    break;
                default:
                    il.append(new ISTORE(temp));
                    il.append(new PUSH(cp, reg));
                    il.append(new ILOAD(temp));
                    il.append(new INVOKESTATIC(cp.addMethodref(CLASS, "writeRegister", "(II)V")));
                    break;
            }
        }
    }

    public void init() {
        super.init();
        CoreComponentConnections.INSTRUCTION_PROVIDERS.add(this);
    }

    @Override
    public void resolveConnections() {
        super.resolveConnections();
        addressSpace = CoreComponentConnections.ADDRESS_SPACE.resolve();
        r3000 = CoreComponentConnections.R3000.resolve();
        r3000regs = r3000.getInterpreterRegs();
    }

    public void addInstructions(InstructionRegistrar registrar) {
        log.info("Adding COP2 instructions...");
        i_mfc2 = new CPUInstruction("mfc2", GTE.class, 0, CPUInstruction.FLAG_WRITES_RT) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int rd = R3000.Util.bits_rd(ci);
                int rt = R3000.Util.bits_rt(ci);

                if (rt != 0) {
                    emitReadReg(il, context, rd);
                    context.emitSetReg(il, rt);
                }
            }
        };
        i_cfc2 = new CPUInstruction("cfc2", GTE.class, 0, CPUInstruction.FLAG_WRITES_RT) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int rd = R3000.Util.bits_rd(ci);
                int rt = R3000.Util.bits_rt(ci);

                if (rt != 0) {
                    emitReadReg(il, context, rd + 32);
                    context.emitSetReg(il, rt);
                }
            }
        };

        i_mtc2 = new CPUInstruction("mtc2", GTE.class, 0, CPUInstruction.FLAG_READS_RT) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int rt = R3000.Util.bits_rt(ci);
                int rd = R3000.Util.bits_rd(ci);

                ConstantPoolGen cp = context.getConstantPoolGen();

                if (0 != (context.getConstantRegs() & (1 << rt))) {
                    il.append(new PUSH(cp, context.getRegValue(rt)));
                } else {
                    context.emitGetReg(il, rt);
                }

                emitWriteReg(il, context, rd);
            }
        };

        i_ctc2 = new CPUInstruction("ctc2", GTE.class, 0, CPUInstruction.FLAG_READS_RT) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int rt = R3000.Util.bits_rt(ci);
                int rd = R3000.Util.bits_rd(ci);

                ConstantPoolGen cp = context.getConstantPoolGen();

                if (0 != (context.getConstantRegs() & (1 << rt))) {
                    il.append(new PUSH(cp, context.getRegValue(rt)));
                } else {
                    context.emitGetReg(il, rt);
                }
                emitWriteReg(il, context, rd + 32);
            }
        };
        i_lwc2 = new CPUInstruction("lwc2", GTE.class, 0, CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_MEM32) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int base = R3000.Util.bits_rs(ci);
                int rt = R3000.Util.bits_rt(ci);
                int offset = R3000.Util.sign_extend(ci);

                if (0 != (context.getConstantRegs() & (1 << base))) {
                    context.emitReadMem32(il, context.getRegValue(base) + offset, false);
                } else {
                    context.emitReadMem32(il, base, offset, false);
                }
                emitWriteReg(il, context, rt);
            }
        };
        i_swc2 = new CPUInstruction("swc2", GTE.class, 0, CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_MEM32) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int base = R3000.Util.bits_rs(ci);
                int rt = R3000.Util.bits_rt(ci);
                int offset = R3000.Util.sign_extend(ci);

                InstructionList il2 = new InstructionList();
                emitReadReg(il2, context, rt);
                if (0 != (context.getConstantRegs() & (1 << base))) {
                    context.emitWriteMem32(il, context.getRegValue(base) + offset, il2, false);
                } else {
                    context.emitWriteMem32(il, base, offset, il2, false);
                }
                il2.dispose();
            }
        };
        i_rtpt = new CPUInstruction("rtpt", GTE.class, 0, 0);
        i_rtps = new CPUInstruction("rtps", GTE.class, 0, 0);
        i_mvmva = new CPUInstruction("mvmva", GTE.class, 0, 0);
        i_op = new CPUInstruction("op", GTE.class, 0, 0);
        i_avsz3 = new CPUInstruction("avsz3", GTE.class, 0, 0);
        i_avsz4 = new CPUInstruction("avsz4", GTE.class, 0, 0);
        i_nclip = new CPUInstruction("nclip", GTE.class, 0, 0);
        i_ncct = new CPUInstruction("ncct", GTE.class, 0, 0);
        i_gpf = new CPUInstruction("gpf", GTE.class, 0, 0);
        i_dcpl = new CPUInstruction("dcpl", GTE.class, 0, 0);
        i_dpcs = new CPUInstruction("dpcs", GTE.class, 0, 0);
        i_intpl = new CPUInstruction("intpl", GTE.class, 0, 0);
        i_sqr = new CPUInstruction("sqr", GTE.class, 0, 0);
        i_ncs = new CPUInstruction("ncs", GTE.class, 0, 0);
        i_nct = new CPUInstruction("nct", GTE.class, 0, 0);
        i_ncds = new CPUInstruction("ncds", GTE.class, 0, 0);
        i_ncdt = new CPUInstruction("ncdt", GTE.class, 0, 0);
        i_dpct = new CPUInstruction("dpct", GTE.class, 0, 0);
        i_nccs = new CPUInstruction("nccs", GTE.class, 0, 0);
        i_cdp = new CPUInstruction("cdp", GTE.class, 0, 0);
        i_cc = new CPUInstruction("cc", GTE.class, 0, 0);
        i_gpl = new CPUInstruction("gpl", GTE.class, 0, 0);
        CPUInstruction i_cop2 = new CPUInstruction("cop2", GTE.class, 0, 0) {
            public CPUInstruction subDecode(int ci) {
                switch (R3000.Util.bits_rs(ci)) {
                    case 0:
                        return i_mfc2;
                    case 2:
                        return i_cfc2;
                    case 4:
                        return i_mtc2;
                    case 6:
                        return i_ctc2;
                    case 1:
                    case 3:
                    case 5:
                    case 7:
                        return r3000.getInvalidInstruction();
                }
                switch (ci & 0x3f) {
                    case 0x01:
                        return i_rtps;
                    case 0x06:
                        return i_nclip;
                    case 0x0c:
                        return i_op;
                    case 0x10:
                        return i_dpcs;
                    case 0x11:
                        return i_intpl;
                    case 0x12:
                        return i_mvmva;
                    case 0x13:
                        return i_ncds;
                    case 0x14:
                        return i_cdp;
                    case 0x16:
                        return i_ncdt;
                    case 0x1b:
                        return i_nccs;
                    case 0x1c:
                        return i_cc;
                    case 0x1e:
                        return i_ncs;
                    case 0x20:
                        return i_nct;
                    case 0x28:
                        return i_sqr;
                    case 0x29:
                        return i_dcpl;
                    case 0x2a:
                        return i_dpct;
                    case 0x2d:
                        return i_avsz3;
                    case 0x2e:
                        return i_avsz4;
                    case 0x30:
                        return i_rtpt;
                    case 0x3d:
                        return i_gpf;
                    case 0x3e:
                        return i_gpl;
                    case 0x3f:
                        return i_ncct;
                }
                return r3000.getInvalidInstruction();
            }
        };

        registrar.setInstruction(18, i_cop2);
        registrar.setInstruction(50, i_lwc2);
        registrar.setInstruction(58, i_swc2);
    }

    private static CPUInstruction i_mfc2;
    private static CPUInstruction i_cfc2;
    private static CPUInstruction i_mtc2;
    private static CPUInstruction i_ctc2;
    private static CPUInstruction i_lwc2;
    private static CPUInstruction i_swc2;
    private static CPUInstruction i_rtpt;
    private static CPUInstruction i_rtps;
    private static CPUInstruction i_mvmva;
    private static CPUInstruction i_op;
    private static CPUInstruction i_avsz3;
    private static CPUInstruction i_avsz4;
    private static CPUInstruction i_nclip;
    private static CPUInstruction i_ncct;
    private static CPUInstruction i_gpf;
    private static CPUInstruction i_dcpl;
    private static CPUInstruction i_dpcs;
    private static CPUInstruction i_intpl;
    private static CPUInstruction i_sqr;
    private static CPUInstruction i_ncs;
    private static CPUInstruction i_nct;
    private static CPUInstruction i_ncds;
    private static CPUInstruction i_ncdt;
    private static CPUInstruction i_dpct;
    private static CPUInstruction i_nccs;
    private static CPUInstruction i_cdp;
    private static CPUInstruction i_cc;
    private static CPUInstruction i_gpl;

    public static void interpret_mfc2(final int ci) {
        int rt = R3000.Util.bits_rt(ci);
        int rd = R3000.Util.bits_rd(ci);
        int value = readRegister(rd);
        if (rt != 0)
            r3000regs[rt] = value;
    }

    public static int readRegister(final int reg) {
        int value;
        switch (reg) {
            case R_VXY0:
                value = (reg_v0.x & 0xffff) | ((reg_v0.y << 16) & 0xffff0000);
                break;
            case R_VZ0:
                value = reg_v0.z;
                break;
            case R_VXY1:
                value = (reg_v1.x & 0xffff) | ((reg_v1.y << 16) & 0xffff0000);
                break;
            case R_VZ1:
                value = reg_v1.z;
                break;
            case R_VXY2:
                value = (reg_v2.x & 0xffff) | ((reg_v2.y << 16) & 0xffff0000);
                break;
            case R_VZ2:
                value = reg_v2.z;
                break;
            case R_RGB:
                value = reg_rgb;
                break;
            case R_OTZ:
                value = reg_otz;
                break;
            case R_IR0:
                value = reg_ir0;
                break;
            case R_IR1:
                value = reg_ir1;
                break;
            case R_IR2:
                value = reg_ir2;
                break;
            case R_IR3:
                value = reg_ir3;
                break;
            case R_SXY0:
                value = (reg_sx0 & 0xffff) | ((reg_sy0 << 16) & 0xffff0000);
                break;
            case R_SXY1:
                value = (reg_sx1 & 0xffff) | ((reg_sy1 << 16) & 0xffff0000);
                break;
            case R_SXY2:
                value = (reg_sx2 & 0xffff) | ((reg_sy2 << 16) & 0xffff0000);
                break;
            case R_SXYP:
                value = (reg_sxp & 0xffff) | ((reg_syp << 16) & 0xffff0000);
                break;
            case R_SZX:
                value = reg_szx;
                break;
            case R_SZ0:
                value = reg_sz0;
                break;
            case R_SZ1:
                value = reg_sz1;
                break;
            case R_SZ2:
                value = reg_sz2;
                break;
            case R_RGB0:
                value = reg_rgb0;
                break;
            case R_RGB1:
                value = reg_rgb1;
                break;
            case R_RGB2:
                value = reg_rgb2;
                break;
            case R_RES1:
                value = reg_res1;
                break;
            case R_MAC0:
                value = reg_mac0;
                break;
            case R_MAC1:
                value = reg_mac1;
                break;
            case R_MAC2:
                value = reg_mac2;
                break;
            case R_MAC3:
                value = reg_mac3;
                break;
            case R_IRGB:
                value = readIORGB();
                break;
            case R_ORGB:
                value = readIORGB();
                break;
            case R_LZCS:
                value = reg_lzcs;
                break;
            case R_LZCR:
                value = reg_lzcr;
                break;
            case R_R11R12:
                value = (reg_rot.m11 & 0xffff) | ((reg_rot.m12 << 16) & 0xffff0000);
                break;
            case R_R13R21:
                value = (reg_rot.m13 & 0xffff) | ((reg_rot.m21 << 16) & 0xffff0000);
                break;
            case R_R22R23:
                value = (reg_rot.m22 & 0xffff) | ((reg_rot.m23 << 16) & 0xffff0000);
                break;
            case R_R31R32:
                value = (reg_rot.m31 & 0xffff) | ((reg_rot.m32 << 16) & 0xffff0000);
                break;
            case R_R33:
                value = (reg_rot.m33 & 0xffff);
                break;
            case R_TRX:
                value = reg_trx;
                break;
            case R_TRY:
                value = reg_try;
                break;
            case R_TRZ:
                value = reg_trz;
                break;
            case R_L11L12:
                value = (reg_ls.m11 & 0xffff) | ((reg_ls.m12 << 16) & 0xffff0000);
                break;
            case R_L13L21:
                value = (reg_ls.m13 & 0xffff) | ((reg_ls.m21 << 16) & 0xffff0000);
                break;
            case R_L22L23:
                value = (reg_ls.m21 & 0xffff) | ((reg_ls.m23 << 16) & 0xffff0000);
                break;
            case R_L31L32:
                value = (reg_ls.m31 & 0xffff) | ((reg_ls.m32 << 16) & 0xffff0000);
                break;
            case R_L33:
                value = (reg_ls.m33 & 0xffff);
                break;
            case R_RBK:
                value = reg_rbk;
                break;
            case R_GBK:
                value = reg_gbk;
                break;
            case R_BBK:
                value = reg_bbk;
                break;
            case R_LR1LR2:
                value = (reg_lc.m11 & 0xffff) | ((reg_lc.m12 << 16) & 0xffff0000);
                break;
            case R_LR3LG1:
                value = (reg_lc.m13 & 0xffff) | ((reg_lc.m21 << 16) & 0xffff0000);
                break;
            case R_LG2LG3:
                value = (reg_lc.m22 & 0xffff) | ((reg_lc.m23 << 16) & 0xffff0000);
                break;
            case R_LB1LB2:
                value = (reg_lc.m31 & 0xffff) | ((reg_lc.m32 << 16) & 0xffff0000);
                break;
            case R_LB3:
                value = (reg_lc.m33 & 0xffff);
                break;
            case R_RFC:
                value = reg_rfc;
                break;
            case R_GFC:
                value = reg_gfc;
                break;
            case R_BFC:
                value = reg_bfc;
                break;
            case R_OFX:
                value = reg_ofx;
                break;
            case R_OFY:
                value = reg_ofy;
                break;
            case R_H:
                // this is according to docs that it is accidentally sign extended
                value = (reg_h << 16) >> 16;
                break;
            case R_DQA:
                value = reg_dqa;
                break;
            case R_DQB:
                value = reg_dqb;
                break;
            case R_ZSF3:
                value = reg_zsf3;
                break;
            case R_ZSF4:
                value = reg_zsf4;
                break;
            case R_FLAG:
                value = reg_flag;
                break;
            default:
                value = 0;
        }
        return value;
    }

    public static void interpret_cfc2(final int ci) {
        int rt = R3000.Util.bits_rt(ci);
        int rd = R3000.Util.bits_rd(ci);
        int value = readRegister(rd + 32);
        if (rt != 0)
            r3000regs[rt] = value;
    }

    public static void writeIRGB(int value) {
        reg_ir1 = (value&0x1f)<<7;
        reg_ir2 = ((value>>5)&0x1f)<<7;
        reg_ir3 = ((value>>10)&0x1f)<<7;
    }

    public static int clampIR(int value) {
        if (value < 0) {
            value = 0;
        } else {
            value = value >> 7;
            if (value > 0x1f) {
                value = 0x1f;
            }
        }
        return value;
    }

    public static int readIORGB() {
        return clampIR(reg_ir1)|(clampIR(reg_ir2)<<5)|(clampIR(reg_ir3)<<10);
    }

    public static void writeRegister(int reg, int value) {
        switch (reg) {
            case R_VXY0:
                reg_v0.x = (value << 16) >> 16;
                reg_v0.y = value >> 16;
                break;
            case R_VZ0:
                reg_v0.z = (value << 16) >> 16;
                break;
            case R_VXY1:
                reg_v1.x = (value << 16) >> 16;
                reg_v1.y = value >> 16;
                break;
            case R_VZ1:
                reg_v1.z = (value << 16) >> 16;
                break;
            case R_VXY2:
                reg_v2.x = (value << 16) >> 16;
                reg_v2.y = value >> 16;
                break;
            case R_VZ2:
                reg_v2.z = (value << 16) >> 16;
                break;
            case R_RGB:
                reg_rgb = value;
                break;
            case R_OTZ:
                reg_otz = value & 0xffff; // checked
                break;
            case R_IR0:
                reg_ir0 = (value << 16) >> 16; // checked
                break;
            case R_IR1:
                reg_ir1 = (value << 16) >> 16; // checked
                break;
            case R_IR2:
                reg_ir2 = (value << 16) >> 16; // checked
                break;
            case R_IR3:
                reg_ir3 = (value << 16) >> 16; // checked
                break;
            case R_SXY0:
                reg_sx0 = (value << 16) >> 16;
                reg_sy0 = value >> 16;
                break;
            case R_SXY1:
                reg_sx1 = (value << 16) >> 16;
                reg_sy1 = value >> 16;
                break;
            case R_SXY2:
                reg_sx2 = (value << 16) >> 16;
                reg_sy2 = value >> 16;
                break;
            case R_SXYP:
                reg_sx0 = reg_sx1;
                reg_sx1 = reg_sx2;
                reg_sx2 = (value << 16) >> 16;
                reg_sy0 = reg_sy1;
                reg_sy1 = reg_sy2;
                reg_sy2 = value >> 16;
                break;
            case R_SZX:
                reg_szx = value & 0xffff;
                break;
            case R_SZ0:
                reg_sz0 = value & 0xffff;
                break;
            case R_SZ1:
                reg_sz1 = value & 0xffff;
                break;
            case R_SZ2:
                reg_sz2 = value & 0xffff;
                break;
            case R_RGB0:
                reg_rgb0 = value;
                break;
            case R_RGB1:
                reg_rgb1 = value;
                break;
            case R_RGB2:
                reg_rgb2 = value;
                break;
            case R_RES1:
                reg_res1 = value;
                break;
            case R_MAC0:
                reg_mac0 = value;
                break;
            case R_MAC1:
                reg_mac1 = value;
                break;
            case R_MAC2:
                reg_mac2 = value;
                break;
            case R_MAC3:
                reg_mac3 = value;
                break;
            case R_IRGB:
                writeIRGB(value);
                break;
            case R_ORGB:
                // no op
                break;
            case R_LZCS: {
                reg_lzcs = value;
                /*
                // old code for pre JDK5
                int mask = 0x80000000;
                int comp = value & 0x80000000;
                int bits;

                for (bits = 0; bits < 32; bits++) {
                    if ((value & mask) != comp)
                        break;
                    mask >>= 1;
                    comp >>= 1;
                }
                reg_lzcr = bits;
                */
                reg_lzcr = Integer.numberOfLeadingZeros(value >= 0 ? value : ~value);
                break;
            }
            case R_LZCR:
                // lzcr is read only
                // no op
                break;
            case R_R11R12:
                reg_rot.m11 = (value << 16) >> 16;
                reg_rot.m12 = value >> 16;
                break;
            case R_R13R21:
                reg_rot.m13 = (value << 16) >> 16;
                reg_rot.m21 = value >> 16;
                break;
            case R_R22R23:
                reg_rot.m22 = (value << 16) >> 16;
                reg_rot.m23 = value >> 16;
                break;
            case R_R31R32:
                reg_rot.m31 = (value << 16) >> 16;
                reg_rot.m32 = value >> 16;
                break;
            case R_R33:
                reg_rot.m33 = (value << 16) >> 16;
                break;
            case R_TRX:
                reg_trx = value;
                break;
            case R_TRY:
                reg_try = value;
                break;
            case R_TRZ:
                reg_trz = value;
                break;
            case R_L11L12:
                reg_ls.m11 = (value << 16) >> 16;
                reg_ls.m12 = value >> 16;
                break;
            case R_L13L21:
                reg_ls.m13 = (value << 16) >> 16;
                reg_ls.m21 = value >> 16;
                break;
            case R_L22L23:
                reg_ls.m22 = (value << 16) >> 16;
                reg_ls.m23 = value >> 16;
                break;
            case R_L31L32:
                reg_ls.m31 = (value << 16) >> 16;
                reg_ls.m32 = value >> 16;
                break;
            case R_L33:
                reg_ls.m33 = (value << 16) >> 16;
                break;
            case R_RBK:
                reg_rbk = value;
                break;
            case R_GBK:
                reg_gbk = value;
                break;
            case R_BBK:
                reg_bbk = value;
                break;
            case R_LR1LR2:
                reg_lc.m11 = (value << 16) >> 16;
                reg_lc.m12 = value >> 16;
                break;
            case R_LR3LG1:
                reg_lc.m13 = (value << 16) >> 16;
                reg_lc.m21 = value >> 16;
                break;
            case R_LG2LG3:
                reg_lc.m22 = (value << 16) >> 16;
                reg_lc.m23 = value >> 16;
                break;
            case R_LB1LB2:
                reg_lc.m31 = (value << 16) >> 16;
                reg_lc.m32 = value >> 16;
                break;
            case R_LB3:
                reg_lc.m33 = (value << 16) >> 16;
                break;
            case R_RFC:
                reg_rfc = value;
                break;
            case R_GFC:
                reg_gfc = value;
                break;
            case R_BFC:
                reg_bfc = value;
                break;
            case R_OFX:
                reg_ofx = value;
                break;
            case R_OFY:
                reg_ofy = value;
                break;
            case R_H:
                reg_h = value & 0xffff;
                break;
            case R_DQA:
                reg_dqa = (value << 16) >> 16;
                break;
            case R_DQB:
                reg_dqb = value;
                break;
            case R_ZSF3:
                reg_zsf3 = (value << 16) >> 16;
                break;
            case R_ZSF4:
                reg_zsf4 = (value << 16) >> 16;
                break;
            case R_FLAG:
                reg_flag = value;
                break;
        }
    }

    public static void interpret_mtc2(final int ci) {
        int rt = R3000.Util.bits_rt(ci);
        int rd = R3000.Util.bits_rd(ci);
        int value = r3000regs[rt];

        writeRegister(rd, value);
    }

    public static void interpret_ctc2(final int ci) {
        int rt = R3000.Util.bits_rt(ci);
        int rd = R3000.Util.bits_rd(ci);
        int value = r3000regs[rt];

        writeRegister(rd + 32, value);
    }

    public static void interpret_cop2(final int ci) {
        switch (R3000.Util.bits_rs(ci)) {
            case 0:
                GTE.interpret_mfc2(ci);
                return;
            case 2:
                GTE.interpret_cfc2(ci);
                return;
            case 4:
                GTE.interpret_mtc2(ci);
                return;
            case 6:
                GTE.interpret_ctc2(ci);
                return;
            case 1:
            case 3:
            case 5:
            case 7:
                break;
            default:
                switch (ci & 0x3f) {
                    case 0x01:
                        GTE.interpret_rtps(ci);
                        return;
                    case 0x06:
                        GTE.interpret_nclip(ci);
                        return;
                    case 0x0c:
                        GTE.interpret_op(ci);
                        return;
                    case 0x10:
                        GTE.interpret_dpcs(ci);
                        return;
                    case 0x11:
                        GTE.interpret_intpl(ci);
                        return;
                    case 0x12:
                        GTE.interpret_mvmva(ci);
                        return;
                    case 0x13:
                        GTE.interpret_ncds(ci);
                        return;
                    case 0x14:
                        GTE.interpret_cdp(ci);
                        return;
                    case 0x16:
                        GTE.interpret_ncdt(ci);
                        return;
                    case 0x1b:
                        GTE.interpret_nccs(ci);
                        return;
                    case 0x1c:
                        GTE.interpret_cc(ci);
                        return;
                    case 0x1e:
                        GTE.interpret_ncs(ci);
                        return;
                    case 0x20:
                        GTE.interpret_nct(ci);
                        return;
                    case 0x28:
                        GTE.interpret_sqr(ci);
                        return;
                    case 0x29:
                        GTE.interpret_dcpl(ci);
                        return;
                    case 0x2a:
                        GTE.interpret_dpct(ci);
                        return;
                    case 0x2d:
                        GTE.interpret_avsz3(ci);
                        return;
                    case 0x2e:
                        GTE.interpret_avsz4(ci);
                        return;
                    case 0x30:
                        GTE.interpret_rtpt(ci);
                        return;
                    case 0x3d:
                        GTE.interpret_gpf(ci);
                        return;
                    case 0x3e:
                        GTE.interpret_gpl(ci);
                        return;
                    case 0x3f:
                        GTE.interpret_ncct(ci);
                        return;
                }
        }
        CoreComponentConnections.SCP.resolve().signalReservedInstructionException();
    }

    public static void interpret_lwc2(final int ci) {
        int base = R3000.Util.bits_rs(ci);
        int rt = R3000.Util.bits_rt(ci);
        int offset = (ci << 16) >> 16;
        int addr = r3000regs[base] + offset;
        addressSpace.tagAddressAccessRead32(r3000.getPC(), addr);
        writeRegister(rt, addressSpace.read32(addr));
    }

    public static void interpret_swc2(final int ci) {
        int base = R3000.Util.bits_rs(ci);
        int rt = R3000.Util.bits_rt(ci);
        int offset = (ci << 16) >> 16;
        int addr = r3000regs[base] + offset;
        addressSpace.tagAddressAccessWrite(r3000.getPC(), addr);
        addressSpace.write32(addr, readRegister(rt));
    }

    public static void interpret_rtpt(final int ci) {
        reg_flag = 0;

        // todo is no SF bit allowed?
        if (0 == (ci & GTE_SF_MASK)) {
            log.warn("RTPS with SF field!");
        }

        long vx = reg_v0.x;
        long vy = reg_v0.y;
        long vz = reg_v0.z;

        reg_mac1 = A1(reg_rot.m11 * vx + reg_rot.m12 * vy + reg_rot.m13 * vz + (((long) reg_trx) << 12));
        reg_mac2 = A2(reg_rot.m21 * vx + reg_rot.m22 * vy + reg_rot.m23 * vz + (((long) reg_try) << 12));
        reg_mac3 = A3(reg_rot.m31 * vx + reg_rot.m32 * vy + reg_rot.m33 * vz + (((long) reg_trz) << 12));

        reg_ir1 = LiB1_0(reg_mac1);
        reg_ir2 = LiB2_0(reg_mac2);
        reg_ir3 = LiB3_0(reg_mac3);

        reg_sz0 = LiD(reg_mac3);
        if (debugLimit && reg_sz0 == 0) {
            MiscUtil.assertionMessage("rtpt overflow 0");
        }


        long hsz = LiE(divide(reg_h, reg_sz0));
        reg_sx0 = LiG1(LiF(reg_ofx + reg_ir1 * hsz));
        reg_sy0 = LiG2(LiF(reg_ofy + reg_ir2 * hsz));
        reg_mac0 = LiF(reg_dqb + reg_dqa * hsz);
        reg_ir0 = LiH(reg_mac0);

        // ---------------------------------------------------

        vx = reg_v1.x;
        vy = reg_v1.y;
        vz = reg_v1.z;

        reg_mac1 = A1(reg_rot.m11 * vx + reg_rot.m12 * vy + reg_rot.m13 * vz + (((long) reg_trx) << 12));
        reg_mac2 = A2(reg_rot.m21 * vx + reg_rot.m22 * vy + reg_rot.m23 * vz + (((long) reg_try) << 12));
        reg_mac3 = A3(reg_rot.m31 * vx + reg_rot.m32 * vy + reg_rot.m33 * vz + (((long) reg_trz) << 12));

        reg_ir1 = LiB1_0(reg_mac1);
        reg_ir2 = LiB2_0(reg_mac2);
        reg_ir3 = LiB3_0(reg_mac3);

        reg_sz1 = LiD(reg_mac3);

        if (debugLimit && reg_sz1 == 0) {
            MiscUtil.assertionMessage("rtpt overflow 1");
        }

        hsz = LiE(divide(reg_h, reg_sz1));
        reg_sx1 = LiG1(LiF(reg_ofx + reg_ir1 * hsz));
        reg_sy1 = LiG2(LiF(reg_ofy + reg_ir2 * hsz));
        reg_mac0 = LiF(reg_dqb + reg_dqa * hsz);
        reg_ir0 = LiH(reg_mac0);

        // ---------------------------------------------------

        vx = reg_v2.x;
        vy = reg_v2.y;
        vz = reg_v2.z;

        reg_mac1 = A1(reg_rot.m11 * vx + reg_rot.m12 * vy + reg_rot.m13 * vz + (((long) reg_trx) << 12));
        reg_mac2 = A2(reg_rot.m21 * vx + reg_rot.m22 * vy + reg_rot.m23 * vz + (((long) reg_try) << 12));
        reg_mac3 = A3(reg_rot.m31 * vx + reg_rot.m32 * vy + reg_rot.m33 * vz + (((long) reg_trz) << 12));

        reg_ir1 = LiB1_0(reg_mac1);
        reg_ir2 = LiB2_0(reg_mac2);
        reg_ir3 = LiB3_0(reg_mac3);

        reg_sz2 = LiD(reg_mac3);

        if (debugLimit && reg_sz2 == 0) {
            MiscUtil.assertionMessage("rtpt overflow 2");
        }

        hsz = LiE(divide(reg_h, reg_sz2));
        reg_sx2 = LiG1(LiF(reg_ofx + reg_ir1 * hsz));
        reg_sy2 = LiG2(LiF(reg_ofy + reg_ir2 * hsz));
        reg_mac0 = LiF(reg_dqb + reg_dqa * hsz);
        reg_ir0 = LiH(reg_mac0);
    }

    public static void interpret_rtps(final int ci) {
//        In: V0 Vector to transform. [1,15,0]
//        R Rotation matrix [1,3,12]
//        TR Translation vector [1,31,0]
//        H View plane distance [0,16,0]
//        DQA Depth que interpolation values. [1,7,8]
//        DQB [1,7,8]OFX Screen offset values. [1,15,16]
//        OFY [1,15,16]
//        Out: SXY fifo Screen XY coordinates.(short) [1,15,0]
//        SZ fifo Screen Z coordinate.(short) [0,16,0]
//        IR0 Interpolation value for depth queing. [1,3,12]
//        IR1 Screen X (short) [1,15,0]
//        IR2 Screen Y (short) [1,15,0]
//        IR3 Screen Z (short) [1,15,0]
//        MAC1 Screen X (long) [1,31,0]
//        MAC2 Screen Y (long) [1,31,0]
//        MAC3 Screen Z (long) [1,31,0]
//        Calculation:
//        [1,31,0] MAC1=A1[TRX + R11*VX0 + R12*VY0 + R13*VZ0] [1,31,12]
//        [1,31,0] MAC2=A2[TRY + R21*VX0 + R22*VY0 + R23*VZ0] [1,31,12]
//        [1,31,0] MAC3=A3[TRZ + R31*VX0 + R32*VY0 + R33*VZ0] [1,31,12]
//        [1,15,0] IR1= Lm_B1[MAC1] [1,31,0]
//        [1,15,0] IR2= Lm_B2[MAC2] [1,31,0]
//        [1,15,0] IR3= Lm_B3[MAC3] [1,31,0]
//        SZ0<-SZ1<-SZ2<-SZ3
//                [0,16,0] SZ3= Lm_D(MAC3) [1,31,0]
//        SX0<-SX1<-SX2, SY0<-SY1<-SY2
//                [1,15,0] SX2= Lm_G1[F[OFX + IR1*(H/SZ)]] [1,27,16]
//        [1,15,0] SY2= Lm_G2[F[OFY + IR2*(H/SZ)]] [1,27,16]
//        [1,31,0] MAC0= F[DQB + DQA * (H/SZ)] [1,19,24]
//        [1,15,0] IR0= Lm_H[MAC0] [1,31,0]

        // or


//        IR1 = MAC1 = (TRX*1000h + RT11*VX0 + RT12*VY0 + RT13*VZ0) SAR (sf*12)
//        IR2 = MAC2 = (TRY*1000h + RT21*VX0 + RT22*VY0 + RT23*VZ0) SAR (sf*12)
//        IR3 = MAC3 = (TRZ*1000h + RT31*VX0 + RT32*VY0 + RT33*VZ0) SAR (sf*12)
//        SZ3 = MAC3 SAR ((1-sf)*12)                           ;ScreenZ FIFO 0..+FFFFh
//        MAC0=(((H*20000h/SZ3)+1)/2)*IR1+OFX, SX2=MAC0/10000h ;ScrX FIFO -400h..+3FFh
//        MAC0=(((H*20000h/SZ3)+1)/2)*IR2+OFY, SY2=MAC0/10000h ;ScrY FIFO -400h..+3FFh
//        MAC0=(((H*20000h/SZ3)+1)/2)*DQA+DQB, IR0=MAC0/1000h  ;Depth cueing 0..+1000h

        reg_flag = 0;

        long vx = reg_v0.x;
        long vy = reg_v0.y;
        long vz = reg_v0.z;

        // todo is no SF bit allowed?
        if (0 == (ci & GTE_SF_MASK)) {
            log.warn("RTPS with SF field!");
        }
        reg_mac1 = A1(reg_rot.m11 * vx + reg_rot.m12 * vy + reg_rot.m13 * vz + (((long) reg_trx) << 12));
        reg_mac2 = A2(reg_rot.m21 * vx + reg_rot.m22 * vy + reg_rot.m23 * vz + (((long) reg_try) << 12));
        reg_mac3 = A3(reg_rot.m31 * vx + reg_rot.m32 * vy + reg_rot.m33 * vz + (((long) reg_trz) << 12));

        reg_ir1 = LiB1_0(reg_mac1);
        reg_ir2 = LiB2_0(reg_mac2);
        reg_ir3 = LiB3_0(reg_mac3);

        reg_szx = reg_sz0;
        reg_sz0 = reg_sz1;
        reg_sz1 = reg_sz2;
        reg_sz2 = LiD(reg_mac3);

        reg_sx0 = reg_sx1;
        reg_sy0 = reg_sy1;
        reg_sx1 = reg_sx2;
        reg_sy1 = reg_sy2;

        if (debugLimit && reg_sz2 == 0) {
            MiscUtil.assertionMessage("rtps overflow");
        }

        long hsz = LiE(divide(reg_h, reg_sz2));
        // [1,15,0] SX2= LG1[F[OFX + IR1*(H/SZ)]]                       [1,27,16]
        reg_sx2 = LiG1(LiF(reg_ofx + reg_ir1 * hsz));
        // [1,15,0] SY2= LG2[F[OFY + IR2*(H/SZ)]]                       [1,27,16]
        reg_sy2 = LiG2(LiF(reg_ofy + reg_ir2 * hsz));
        // [1,31,0] MAC0= F[DQB + DQA * (H/SZ)]                           [1,19,24]
        reg_mac0 = LiF(reg_dqb + reg_dqa * hsz);

        // [1,15,0] IR0= LH[MAC0]                                       [1,31,0]
        reg_ir0 = LiH(reg_mac0);
    }

    public static long SIGNED_BIG(int src) {
        if (src == 0)
            return 0;
        if (src > 0)
            return 0x10000000000000L;
        return -0x10000000000000L;
    }

    public static void interpret_mvmva(final int ci) {
//        Fields: sf, cv, lm
//        R/LLM/LCM Rotation, light or color matrix. [1,3,12]
//        TR/BK Translation or background color vector.
//                out: [IR1,IR2,IR3] Short vector
//        [MAC1,MAC2,MAC3] Long vector
//        Calculation:
//        MAC1=A1[CV1 + MX11*V1 + MX12*V2 + MX13*V3]
//        MAC2=A2[CV2 + MX21*V1 + MX22*V2 + MX23*V3]
//        MAC3=A3[CV3 + MX31*V1 + MX32*V2 + MX33*V3]
//        IR1=Lm_B1[MAC1]
//        IR2=Lm_B2[MAC2]
//        IR3=Lm_B3[MAC3]
//        Notes:
//        The cv field allows selection of the far color vector, but this vector
//        is not added correctly by the GTE.


// NOTE: int64/A1,A2,A3 can only happen with IR I think

        reg_flag = 0;

        Matrix matrix;
        switch (ci & GTE_MX_MASK) {
            case GTE_MX_LIGHT:
                matrix = reg_ls;
                break;
            case GTE_MX_COLOR:
                matrix = reg_lc;
                break;
            default:
                matrix = reg_rot;
                break;
        }

        long vx;
        long vy;
        long vz;
        switch (ci & GTE_V_MASK) {
            case GTE_V_IR:
                vx = reg_ir1;
                vy = reg_ir2;
                vz = reg_ir3;
                break;
            case GTE_V_V2:
                vx = reg_v2.x;
                vy = reg_v2.y;
                vz = reg_v2.z;
                break;
            case GTE_V_V1:
                vx = reg_v1.x;
                vy = reg_v1.y;
                vz = reg_v1.z;
                break;
            default:
                vx = reg_v0.x;
                vy = reg_v0.y;
                vz = reg_v0.z;
                break;
        }

        // v values s15.0 or s31.0 (s19.12 in SF case?)

        long ssx = matrix.m11 * vx + matrix.m12 * vy + matrix.m13 * vz;
        long ssy = matrix.m21 * vx + matrix.m22 * vy + matrix.m23 * vz;
        long ssz = matrix.m31 * vx + matrix.m32 * vy + matrix.m33 * vz;

        if (0 != (ci & GTE_SF_MASK)) {
            ssx >>= 12;
            ssy >>= 12;
            ssz >>= 12;
        }

        // ss values are up to about s36.12
        switch (ci & GTE_CV_MASK) {
            case GTE_CV_TR:
                ssx += reg_trx;
                ssy += reg_try;
                ssz += reg_trz;
                break;
            case GTE_CV_BK:
                ssx += reg_rbk;
                ssy += reg_gbk;
                ssz += reg_bbk;
                break;
            case GTE_CV_FC:
                ssx += reg_rfc;
                ssy += reg_gfc;
                ssz += reg_bfc;
                break;
            default:
                break;
        }

        reg_mac1 = A1(ssx << 12);
        reg_mac2 = A2(ssy << 12);
        reg_mac3 = A3(ssz << 12);

        if (0 != (ci & GTE_LM_MASK)) {
            reg_ir1 = LiB1_1(reg_mac1);
            reg_ir2 = LiB2_1(reg_mac2);
            reg_ir3 = LiB3_1(reg_mac3);
        } else {
            reg_ir1 = LiB1_0(reg_mac1);
            reg_ir2 = LiB2_0(reg_mac2);
            reg_ir3 = LiB3_0(reg_mac3);
        }
    }

    public static int LiB1_0(int src) {
        if (src >= 0x8000) {
            reg_flag |= FLAG_B1;
            if (debugLimitB) log.info("B1_0 + "+src);
            return 0x7fff;
        } else if (src < -0x8000) {
            reg_flag |= FLAG_B1;
            if (debugLimitB) log.info("B1_0 - "+src);
            return -0x8000;
        }
        return src;
    }


    public static int LiB1_1(int src) {
        if (src >= 0x8000) {
            reg_flag |= FLAG_B1;
            if (debugLimitB) log.info("B1_1 + "+src);
            return 0x7fff;
        } else if (src < 0) {
            reg_flag |= FLAG_B1;
            if (debugLimitB) log.info("B1_1 0 "+src);
            return 0;
        }
        return src;
    }

    public static int LiB2_0(int src) {
        if (src >= 0x8000) {
            reg_flag |= FLAG_B2;
            if (debugLimitB) log.info("B2_0 + "+src);
            return 0x7fff;
        } else if (src < -0x8000) {
            reg_flag |= FLAG_B2;
            if (debugLimitB) log.info("B2_0 - "+src);
            return -0x8000;
        }
        return src;
    }

    public static int LiB2_1(int src) {
        if (src >= 0x8000) {
            reg_flag |= FLAG_B2;
            if (debugLimitB) log.info("B2_1 + "+src);
            return 0x7fff;
        } else if (src < 0) {
            reg_flag |= FLAG_B2;
            if (debugLimitB) log.info("B2_1 0 "+src);
            return 0;
        }
        return src;
    }

    public static int LiB3_0(int src) {
        if (src >= 0x8000) {
            reg_flag |= FLAG_B3;
            if (debugLimitB) log.info("B3_0 + "+src);
            return 0x7fff;
        } else if (src < -0x8000) {
            reg_flag |= FLAG_B3;
            if (debugLimitB) log.info("B3_0 - "+src);
            return -0x8000;
        }
        return src;
    }

    public static int LiB3_1(int src) {
        if (src >= 0x8000) {
            reg_flag |= FLAG_B3;
            if (debugLimitB) log.info("B3_1 + "+src);
            return 0x7fff;
        } else if (src < 0) {
            reg_flag |= FLAG_B3;
            if (debugLimitB) log.info("B3_1 0 "+src);
            return 0;
        }
        return src;
    }

    public static int LiC1(int src) {
        if (src < 0) {
            reg_flag |= FLAG_C1;
            return 0;
        } else if (src > 0xfff) {
            reg_flag |= FLAG_C1;
            return 0xff;
        }
        return src>>4;
    }

    public static int LiC2(int src) {
        if (src < 0) {
            reg_flag |= FLAG_C2;
            return 0;
        } else if (src > 0xfff) {
            reg_flag |= FLAG_C2;
            return 0xff;
        }
        return src>>4;
    }

    public static int LiC3(int src) {
        if (src < 0) {
            reg_flag |= FLAG_C3;
            return 0;
        } else if (src > 0xfff) {
            reg_flag |= FLAG_C3;
            return 0xff;
        }
        return src>>4;
    }

    public static int LiD(int src) {
        if (src < 0) {
            reg_flag |= FLAG_D;
            if (debugLimitD) log.info("D 0 "+src);
            return 0;
        } else if (src >= 0x10000) {
            reg_flag |= FLAG_D;
            if (debugLimitD) log.info("D + "+src);
            return 0xffff;
        }
        return src;
    }

    private static int LiE(int src) {
        if (src >= 0x20000) {
            reg_flag |= FLAG_E;
            src = 0x1ffff;
        }
        return src;
    }

    private static int LiF(long src) {
        if (src >= BIT47) {
            reg_flag |= FLAG_FP;
            if (debugLimit) log.info("F + "+src);
//            return 0x7fffffff;
        } else if (src <= -BIT47) {
            reg_flag |= FLAG_FN;
            if (debugLimit) log.info("F - "+src);
//            return 0x80000000;
        }
        return (int) (src >> 16);
    }

    public static int LiG1(int src) {
        if (src >= 0x400) {
            reg_flag |= FLAG_G1;
            if (debugLimitG) log.info("G1 + "+src);
            return 0x3ff;
        } else if (src < -0x400) {
            reg_flag |= FLAG_G1;
            if (debugLimitG) log.info("G1 - "+src);
            return -0x400;
        }
        return src;
    }

    public static int LiG2(int src) {
        if (src >= 0x400) {
            reg_flag |= FLAG_G2;
            if (debugLimitG) log.info("G2 + "+src);
            return 0x3ff;
        } else if (src < -0x400) {
            reg_flag |= FLAG_G2;
            if (debugLimitG) log.info("G2 - "+src);
            return -0x400;
        }
        return src;
    }

    public static int LiH(int src) {
        if (src >= 0x1000) {
            reg_flag |= FLAG_H;
//            if (debugLimit) log.info("H "+src);
            return 0xfff;
        } else if (src < 0) {
            reg_flag |= FLAG_H;
//            if (debugLimit) log.info("H "+src);
            return 0;
        }
        return src;
    }

    private static int A1(long val) {
        if (val >= BIT43) {
            reg_flag |= FLAG_A1P;
        } else if (val <= -BIT43) {
            reg_flag |= FLAG_A1N;
        }
        return (int) (val >> 12);
    }

    private static int A2(long val) {
        if (val >= BIT43) {
            reg_flag |= FLAG_A2P;
        } else if (val <= -BIT43) {
            reg_flag |= FLAG_A2N;
        }
        return (int) (val >> 12);
    }

    private static long mac3_64;

    private static int A3(long val) {
        if (val >= BIT43) {
            reg_flag |= FLAG_A3P;
        } else if (val <= -BIT43) {
            reg_flag |= FLAG_A3N;
        }
        return (int) (val >> 12);
    }

    public static void interpret_op(final int ci) {
        // checked except todo unclear whether lm is supported - added here
//        Fields: sf
//        in: [R11R12,R22R23,R33] vector 1
//                [IR1,IR2,IR3] vector 2
//        out: [IR1,IR2,IR3] outer product
//        [MAC1,MAC2,MAC3] outer product
//        Calculation: (D1=R11R12,D2=R22R23,D3=R33)
//        MAC1=A1[D2*IR3 - D3*IR2]
//        MAC2=A2[D3*IR1 - D1*IR3]
//        MAC3=A3[D1*IR2 - D2*IR1]
//        IR1=Lm_B1[MAC0]
//        IR2=Lm_B2[MAC1]
//        IR3=Lm_B3[MAC2]
        reg_flag = 0;

        long a1 = reg_rot.m11;
        long a2 = reg_rot.m22;
        long a3 = reg_rot.m33;

        long ss1 = a2 * reg_ir3 - a3 * reg_ir2;
        long ss2 = a3 * reg_ir1 - a1 * reg_ir3;
        long ss3 = a1 * reg_ir2 - a2 * reg_ir1;

        if (0 == (ci & GTE_SF_MASK)) {
            ss1 <<= 12;
            ss2 <<= 12;
            ss3 <<= 12;
        }

        reg_mac1 = A1(ss1);
        reg_mac2 = A2(ss2);
        reg_mac3 = A3(ss3);
        if (0 != (ci & GTE_LM_MASK)) {
            reg_ir1 = LiB1_1(reg_mac1);
            reg_ir2 = LiB2_1(reg_mac2);
            reg_ir3 = LiB3_1(reg_mac3);
        } else {
            reg_ir1 = LiB1_0(reg_mac1);
            reg_ir2 = LiB2_0(reg_mac2);
            reg_ir3 = LiB3_0(reg_mac3);
        }
    }

    public static void interpret_avsz3(final int ci) {
        // checked
//        in: SZ1, SZ2, SZ3 Z-Values [0,16,0]
//        ZSF3 Divider [1,3,12]
//        out: OTZ Average. [0,16,0]
//        MAC0 Average. [1,31,0]
//        Calculation:
//        [1,31,0] MAC0=F[ZSF3*SZ1 + ZSF3*SZ2 + ZSF3*SZ3] [1,31,12]
//        [0,16,0] OTZ=Lm_D[MAC0] [1,31,0]

        reg_flag = 0;
        reg_mac0 = LiF(reg_zsf3 * (long)((reg_sz0 + reg_sz1 + reg_sz2)<<4));
        reg_otz = LiD(reg_mac0);
    }

    public static void interpret_avsz4(final int ci) {
        // checked
//        Fields:
//        in: SZ1,SZ2,SZ3,SZ4 Z-Values [0,16,0]
//        ZSF4 Divider [1,3,12]
//        out: OTZ Average. [0,16,0]
//        MAC0 Average. [1,31,0]
//        Calculation:
//        [1,31,0] MAC0=F[ZSF4*SZ0 + ZSF4*SZ1 + ZSF4*SZ2 + ZSF4*SZ3] [1,31,12]
//        [0,16,0] OTZ=Lm_D[MAC0] [1,31,0]

        reg_flag = 0;
        reg_mac0 = LiF(reg_zsf4 * (long)((reg_sz0 + reg_sz1 + reg_sz2 + reg_szx)<<4));
        reg_otz = LiD(reg_mac0);
    }

    public static void interpret_nclip(final int ci) {
        /*
              NOTE: I don't think nclip should clear the FLAG register.

              In Tomb Raider, there is code which looks something like this:
                  RTPT
                  NCLIP
                  f = gte->R_FLAG
                  if (f & 0x7fc7e000) goto skip_polygon;

               Since the RTPT can only set SX0,SX1,SX2,SY0,SY1,SY2 to values in the
              range -0x800 to 0x800, there is no way NCLIP's calculation can overflow.
               Hence, if NCLIP clears the FLAG register there is no way the branch
              in the above code can ever be taken.
               Whereas, if NCLIP _doesn't_ clear the FLAG, the above code actually
              makes sense.
          */
        reg_flag = 0;

        // [1,31,0] MAC0 = F[SX0*SY1+SX1*SY2+SX2*SY0-SX0*SY2-SX1*SY0-SX2*SY1] [1,43,0]
        // @@ not too worried about liF() here...
        reg_mac0 = reg_sx0 * reg_sy1 + reg_sx1 * reg_sy2 + reg_sx2 * reg_sy0 -
                reg_sx0 * reg_sy2 - reg_sx1 * reg_sy0 - reg_sx2 * reg_sy1;
    }

    public static void interpret_ncct(final int ci) {
        // untested
        reg_flag = 0;

        int chi = reg_rgb & 0xff000000;
        int r = (reg_rgb & 0xff) << 4;
        int g = (reg_rgb & 0xff00) >> 4;
        int b = (reg_rgb & 0xff0000) >> 12;

        long m11 = reg_ls.m11;
        long m12 = reg_ls.m12;
        long m13 = reg_ls.m13;
        long m21 = reg_ls.m21;
        long m22 = reg_ls.m22;
        long m23 = reg_ls.m23;
        long m31 = reg_ls.m31;
        long m32 = reg_ls.m32;
        long m33 = reg_ls.m33;

        long ss1 = m11 * reg_v0.x + m12 * reg_v0.y + m13 * reg_v0.z;
        long ss2 = m21 * reg_v0.x + m22 * reg_v0.y + m23 * reg_v0.z;
        long ss3 = m31 * reg_v0.x + m32 * reg_v0.y + m33 * reg_v0.z;

        int mac1 = A1(ss1);
        int mac2 = A2(ss2);
        int mac3 = A3(ss3);

        int ir1 = LiB1_1(mac1);
        int ir2 = LiB2_1(mac2);
        int ir3 = LiB3_1(mac3);

        long c11 = reg_lc.m11;
        long c12 = reg_lc.m12;
        long c13 = reg_lc.m13;
        long c21 = reg_lc.m21;
        long c22 = reg_lc.m22;
        long c23 = reg_lc.m23;
        long c31 = reg_lc.m31;
        long c32 = reg_lc.m32;
        long c33 = reg_lc.m33;

        long bkr = reg_rbk;
        long bkg = reg_gbk;
        long bkb = reg_bbk;

        ss1 = c11 * ir1 + c12 * ir2 + c13 * ir3 + (bkr << 12);
        ss2 = c21 * ir1 + c22 * ir2 + c23 * ir3 + (bkg << 12);
        ss3 = c31 * ir1 + c32 * ir2 + c33 * ir3 + (bkb << 12);

        mac1 = A1(ss1);
        mac2 = A2(ss2);
        mac3 = A3(ss3);

        ir1 = LiB1_1(mac1);
        ir2 = LiB2_1(mac2);
        ir3 = LiB3_1(mac3);

        mac1 = A1(r * ir1);
        mac2 = A2(g * ir2);
        mac3 = A3(b * ir3);

        int rr = LiC1(mac1);
        int gg = LiC2(mac2);
        int bb = LiC3(mac3);
        reg_rgb0 = rr | (gg << 8) | (bb << 16) | chi;

        // 2
        ss1 = m11 * reg_v1.x + m12 * reg_v1.y + m13 * reg_v1.z;
        ss2 = m21 * reg_v1.x + m22 * reg_v1.y + m23 * reg_v1.z;
        ss3 = m31 * reg_v1.x + m32 * reg_v1.y + m33 * reg_v1.z;

        mac1 = A1(ss1);
        mac2 = A2(ss2);
        mac3 = A3(ss3);

        ir1 = LiB1_1(mac1);
        ir2 = LiB2_1(mac2);
        ir3 = LiB3_1(mac3);

        ss1 = c11 * ir1 + c12 * ir2 + c13 * ir3 + (bkr << 12);
        ss2 = c21 * ir1 + c22 * ir2 + c23 * ir3 + (bkg << 12);
        ss3 = c31 * ir1 + c32 * ir2 + c33 * ir3 + (bkb << 12);

        mac1 = A1(ss1);
        mac2 = A2(ss2);
        mac3 = A3(ss3);

        ir1 = LiB1_1(mac1);
        ir2 = LiB2_1(mac2);
        ir3 = LiB3_1(mac3);

        mac1 = A1(r * ir1);
        mac2 = A2(g * ir2);
        mac3 = A3(b * ir3);

        // gcs 011802 added >>4
        rr = LiC1(mac1);
        gg = LiC2(mac2);
        bb = LiC3(mac3);
        reg_rgb1 = rr | (gg << 8) | (bb << 16) | chi;

        // 3
        ss1 = m11 * reg_v2.x + m12 * reg_v2.y + m13 * reg_v2.z;
        ss2 = m21 * reg_v2.x + m22 * reg_v2.y + m23 * reg_v2.z;
        ss3 = m31 * reg_v2.x + m32 * reg_v2.y + m33 * reg_v2.z;

        mac1 = A1(ss1);
        mac2 = A2(ss2);
        mac3 = A3(ss3);

        ir1 = LiB1_1(mac1);
        ir2 = LiB2_1(mac2);
        ir3 = LiB3_1(mac3);

        ss1 = c11 * ir1 + c12 * ir2 + c13 * ir3 + (bkr << 12);
        ss2 = c21 * ir1 + c22 * ir2 + c23 * ir3 + (bkg << 12);
        ss3 = c31 * ir1 + c32 * ir2 + c33 * ir3 + (bkb << 12);

        mac1 = A1(ss1);
        mac2 = A2(ss2);
        mac3 = A3(ss3);

        ir1 = LiB1_1(mac1);
        ir2 = LiB2_1(mac2);
        ir3 = LiB3_1(mac3);

        reg_mac1 = A1(r * ir1);
        reg_mac2 = A2(g * ir2);
        reg_mac3 = A3(b * ir3);
        reg_ir1 = LiB1_1(reg_mac1);
        reg_ir2 = LiB2_1(reg_mac2);
        reg_ir3 = LiB3_1(reg_mac3);

        // gcs 011802 added >>4
        rr = LiC1(reg_mac1);
        gg = LiC2(reg_mac2);
        bb = LiC3(reg_mac3);
        reg_rgb2 = rr | (gg << 8) | (bb << 16) | chi;
    }

    public static void interpret_gpf(final int ci) {
        reg_flag = 0;
        //   MAC1=A1[IR0 * IR1]
        //   MAC2=A2[IR0 * IR2]
        //   MAC3=A3[IR0 * IR3]
        //   IR1=LB1[MAC1]
        //   IR2=LB2[MAC2]
        //   IR3=LB3[MAC3]
        //[0,8,0]   Cd0<-Cd1<-Cd2<- CODE
        //[0,8,0]   R0<-R1<-R2<- LC1[MAC1]
        //[0,8,0]   G0<-G1<-G2<- LC2[MAC2]
        //[0,8,0]   B0<-B1<-B2<- LC3[MAC3]

        long m = reg_ir0;
        if (0 != (ci & GTE_SF_MASK)) {
            reg_mac1 = A1(m * reg_ir1);
            reg_mac2 = A2(m * reg_ir2);
            reg_mac3 = A3(m * reg_ir3);
        } else {
            reg_mac1 = A1((m * reg_ir1) << 12);
            reg_mac2 = A2((m * reg_ir2) << 12);
            reg_mac3 = A3((m * reg_ir3) << 12);
        }
        reg_ir1 = LiB1_0(reg_mac1);
        reg_ir2 = LiB2_0(reg_mac2);
        reg_ir3 = LiB3_0(reg_mac3);
        int rr = LiC1(reg_mac1);
        int gg = LiC2(reg_mac2);
        int bb = LiC3(reg_mac3);
        reg_rgb0 = reg_rgb1;
        reg_rgb1 = reg_rgb2;
        reg_rgb2 = (reg_rgb & 0xff000000) | rr | (gg << 8) | (bb << 16);
    }

    public static void interpret_dcpl(final int ci) {

//        In: RGB Primary color. R,G,B,CODE [0,8,0]
//        IR0 interpolation value. [1,3,12]
//        [IR1,IR2,IR3] Local color vector. [1,3,12]
//        CODE Code value from RGB. CODE [0,8,0]
//        FC Far color. [1,27,4]
//        Out: RGBn RGB fifo Rn,Gn,Bn,CDn [0,8,0]
//        [IR1,IR2,IR3] Color vector [1,11,4]
//        [MAC1,MAC2,MAC3] Color vector [1,27,4]
//        Calculation:
//        [1,27,4] MAC1=A1[R*IR1 + IR0*(Lm_B1[RFC-R* IR1])] [1,27,16]
//        [1,27,4] MAC2=A2[G*IR2 + IR0*(Lm_B1[GFC-G* IR2])] [1,27,16]
//        [1,27,4] MAC3=A3[B*IR3 + IR0*(Lm_B1[BFC-B* IR3])] [1,27,16]
//        [1,11,4] IR1=Lm_B1[MAC1] [1,27,4]
//        [1,11,4] IR2=Lm_B2[MAC2] [1,27,4]
//        [1,11,4] IR3=Lm_B3[MAC3] [1,27,4]
//        [0,8,0] Cd0<-Cd1<-Cd2<- CODE
//                [0,8,0] R0<-R1<-R2<- Lm_C1[MAC1] [1,27,4]
//        [0,8,0] G0<-G1<-G2<- Lm_C2[MAC2] [1,27,4]
//        [0,8,0] B0<-B1<-B2<- Lm_C3[MAC3] [1,27,4]

        reg_flag = 0;
        int chi = reg_rgb & 0xff000000;
        int r = (reg_rgb & 0xff) << 4;
        int g = (reg_rgb & 0xff00) >> 4;
        int b = (reg_rgb & 0xff0000) >> 12;

        // TODO - is this B1 all the way correct?
        reg_mac1 = A1(r * reg_ir1 + reg_ir0 * LiB1_0(reg_rfc - ((r * reg_ir1) >> 12)));
        reg_mac2 = A2(g * reg_ir2 + reg_ir0 * LiB1_0(reg_gfc - ((g * reg_ir2) >> 12)));
        reg_mac3 = A3(b * reg_ir3 + reg_ir0 * LiB1_0(reg_bfc - ((b * reg_ir3) >> 12)));

        // TODO - is this B1 all the way correct?
        reg_ir1 = LiB1_0(reg_mac1);
        reg_ir2 = LiB2_0(reg_mac2);
        reg_ir3 = LiB3_0(reg_mac3);

        int rr = LiC1(reg_mac1);
        int gg = LiC2(reg_mac2);
        int bb = LiC3(reg_mac3);
        reg_rgb0 = reg_rgb1;
        reg_rgb1 = reg_rgb2;
        reg_rgb2 = rr | (gg << 8) | (bb << 16) | chi;
    }

    public static void interpret_dpcs(final int ci) {
        // checked
        /**
         In: IR0 Interpolation value [1,3,12]
         RGB Color R,G,B,CODE [0,8,0]
         FC Far color RFC,GFC,BFC [1,27,4]

         Out: RGBn RGB fifo Rn,Gn,Bn,CDn [0,8,0]
         [IR1,IR2,IR3] Color vector [1,11,4]
         [MAC1,MAC2,MAC3] Color vector [1,27,4]

         [1,27,4] MAC1=A1[(R + IR0*(Lm_B1[RFC - R])] [1,27,16][lm=0]
         [1,27,4] MAC2=A2[(G + IR0*(Lm_B1[GFC - G])] [1,27,16][lm=0]
         [1,27,4] MAC3=A3[(B + IR0*(Lm_B1[BFC - B])] [1,27,16][lm=0]
         [1,11,4] IR1=Lm_B1[MAC1] [1,27,4][lm=0]
         [1,11,4] IR2=Lm_B2[MAC2] [1,27,4][lm=0]
         [1,11,4] IR3=Lm_B3[MAC3] [1,27,4][lm=0]
         [0,8,0] Cd0<-Cd1<-Cd2<- CODE
         [0,8,0] R0<-R1<-R2<- Lm_C1[MAC1] [1,27,4]
         [0,8,0] G0<-G1<-G2<- Lm_C2[MAC2] [1,27,4]
         [0,8,0] B0<-B1<-B2<- Lm_C3[MAC3] [1,27,4]
         */
        reg_flag = 0;

        int chi = reg_rgb & 0xff000000;
        int r = (reg_rgb & 0xff) << 4;
        int g = (reg_rgb & 0xff00) >> 4;
        int b = (reg_rgb & 0xff0000) >> 12;

        reg_mac1 = A1((r << 12) + reg_ir0 * LiB1_0(reg_rfc - r));
        reg_mac2 = A2((g << 12) + reg_ir0 * LiB1_0(reg_gfc - g));
        reg_mac3 = A3((b << 12) + reg_ir0 * LiB1_0(reg_bfc - b));

        reg_ir1 = LiB1_0(reg_mac1);
        reg_ir2 = LiB2_0(reg_mac2);
        reg_ir3 = LiB3_0(reg_mac3);

        int rr = LiC1(reg_mac1);
        int gg = LiC2(reg_mac2);
        int bb = LiC3(reg_mac3);

        reg_rgb0 = reg_rgb1;
        reg_rgb1 = reg_rgb2;
        reg_rgb2 = rr | (gg << 8) | (bb << 16) | chi;
    }

    public static void interpret_intpl(final int ci) {
        reg_flag = 0;
        int chi = reg_rgb & 0xff000000;

        long ir0 = reg_ir0;
        reg_mac1 = A1((reg_ir1 << 12) + ir0 * LiB1_0(reg_rfc - reg_ir1));
        reg_mac2 = A2((reg_ir2 << 12) + ir0 * LiB2_0(reg_gfc - reg_ir2));
        reg_mac3 = A3((reg_ir3 << 12) + ir0 * LiB3_0(reg_bfc - reg_ir3));
        reg_ir1 = LiB1_0(reg_mac1);
        reg_ir2 = LiB2_0(reg_mac2);
        reg_ir3 = LiB3_0(reg_mac3);

        int rr = LiC1(reg_mac1);
        int gg = LiC2(reg_mac2);
        int bb = LiC3(reg_mac3);
        reg_rgb0 = reg_rgb1;
        reg_rgb1 = reg_rgb2;
        reg_rgb2 = rr | (gg << 8) | (bb << 16) | chi;
    }

    public static void interpret_sqr(final int ci) {
        // checked
//        Fields: sf
//        in: [IR1,IR2,IR3] vector [1,15,0][1,3,12]
//        out: [IR1,IR2,IR3] vector^2 [1,15,0][1,3,12]
//        [MAC1,MAC2,MAC3] vector^2 [1,31,0][1,19,12]
//        Calculation: (left format sf=0, right format sf=1)
//        [1,31,0][1,19,12] MAC1=A1[IR1*IR1] [1,43,0][1,31,12]
//        [1,31,0][1,19,12] MAC2=A2[IR2*IR2] [1,43,0][1,31,12]
//        [1,31,0][1,19,12] MAC3=A3[IR3*IR3] [1,43,0][1,31,12]
//        [1,15,0][1,3,12] IR1=Lm_B1[MAC1] [1,31,0][1,19,12][lm=1]
//        [1,15,0][1,3,12] IR2=Lm_B2[MAC2] [1,31,0][1,19,12][lm=1]
//        [1,15,0][1,3,12] IR3=Lm_B3[MAC3] [1,31,0][1,19,12][lm=1]

        reg_flag = 0;

        // [1,31,0] MAC1=A1[IR1*IR1]                     [1,43,0]
        // [1,31,0] MAC2=A2[IR2*IR2]                     [1,43,0]
        // [1,31,0] MAC3=A3[IR3*IR3]                     [1,43,0]
        // [1,15,0] IR1=LB1[MAC1]                      [1,31,0][lm=1]
        // [1,15,0] IR2=LB2[MAC2]                      [1,31,0][lm=1]
        // [1,15,0] IR3=LB3[MAC3]                      [1,31,0][lm=1]

        int i1 = reg_ir1 * reg_ir1;
        int i2 = reg_ir2 * reg_ir2;
        int i3 = reg_ir3 * reg_ir3;

        if (0 != (ci & GTE_SF_MASK)) {
            i1 >>= 12;
            i2 >>= 12;
            i3 >>= 12;
        }

        // A1,A2,A3 not possible since the inputs are signed 16 bit
        reg_mac1 = i1;
        reg_mac2 = i2;
        reg_mac3 = i3;

        // lm=0 also not pertinent
        reg_ir1 = LiB1_1(i1);
        reg_ir2 = LiB2_1(i2);
        reg_ir3 = LiB3_1(i3);
    }

    public static void interpret_ncs(final int ci) {
        // test with ridge racer
        reg_flag = 0;
        int chi = reg_rgb & 0xff000000;

        long m11 = reg_ls.m11;
        long m12 = reg_ls.m12;
        long m13 = reg_ls.m13;
        long m21 = reg_ls.m21;
        long m22 = reg_ls.m22;
        long m23 = reg_ls.m23;
        long m31 = reg_ls.m31;
        long m32 = reg_ls.m32;
        long m33 = reg_ls.m33;

        int mac1 = A1(m11 * reg_v0.x + m12 * reg_v0.y + m13 * reg_v0.z);
        int mac2 = A2(m21 * reg_v0.x + m22 * reg_v0.y + m23 * reg_v0.z);
        int mac3 = A3(m31 * reg_v0.x + m32 * reg_v0.y + m33 * reg_v0.z);

        int ir1 = LiB1_1(mac1);
        int ir2 = LiB2_1(mac2);
        int ir3 = LiB3_1(mac3);

        long c11 = reg_lc.m11;
        long c12 = reg_lc.m12;
        long c13 = reg_lc.m13;
        long c21 = reg_lc.m21;
        long c22 = reg_lc.m22;
        long c23 = reg_lc.m23;
        long c31 = reg_lc.m31;
        long c32 = reg_lc.m32;
        long c33 = reg_lc.m33;

        long bkr = reg_rbk;
        long bkg = reg_gbk;
        long bkb = reg_bbk;

        reg_mac1 = A1(c11 * ir1 + c12 * ir2 + c13 * ir3 + (bkr << 12));
        reg_mac2 = A2(c21 * ir1 + c22 * ir2 + c23 * ir3 + (bkg << 12));
        reg_mac3 = A3(c31 * ir1 + c32 * ir2 + c33 * ir3 + (bkb << 12));

        reg_ir1 = LiB1_1(reg_mac1);
        reg_ir2 = LiB2_1(reg_mac2);
        reg_ir3 = LiB3_1(reg_mac3);

        int rr = LiC1(reg_mac1);
        int gg = LiC2(reg_mac2);
        int bb = LiC3(reg_mac3);
        reg_rgb0 = reg_rgb1;
        reg_rgb1 = reg_rgb2;
        reg_rgb2 = rr | (gg << 8) | (bb << 16) | chi;
    }

    public static void interpret_nct(final int ci) {
        // test with ridge racer
        reg_flag = 0;
        int chi = reg_rgb & 0xff000000;

        long m11 = reg_ls.m11;
        long m12 = reg_ls.m12;
        long m13 = reg_ls.m13;
        long m21 = reg_ls.m21;
        long m22 = reg_ls.m22;
        long m23 = reg_ls.m23;
        long m31 = reg_ls.m31;
        long m32 = reg_ls.m32;
        long m33 = reg_ls.m33;

        int mac1 = A1(m11 * reg_v0.x + m12 * reg_v0.y + m13 * reg_v0.z);
        int mac2 = A2(m21 * reg_v0.x + m22 * reg_v0.y + m23 * reg_v0.z);
        int mac3 = A3(m31 * reg_v0.x + m32 * reg_v0.y + m33 * reg_v0.z);

        int ir1 = LiB1_1(mac1);
        int ir2 = LiB2_1(mac2);
        int ir3 = LiB3_1(mac3);

        long c11 = reg_lc.m11;
        long c12 = reg_lc.m12;
        long c13 = reg_lc.m13;
        long c21 = reg_lc.m21;
        long c22 = reg_lc.m22;
        long c23 = reg_lc.m23;
        long c31 = reg_lc.m31;
        long c32 = reg_lc.m32;
        long c33 = reg_lc.m33;

        long bkr = reg_rbk;
        long bkg = reg_gbk;
        long bkb = reg_bbk;

        mac1 = A1(c11 * ir1 + c12 * ir2 + c13 * ir3 + (bkr << 12));
        mac2 = A2(c21 * ir1 + c22 * ir2 + c23 * ir3 + (bkg << 12));
        mac3 = A3(c31 * ir1 + c32 * ir2 + c33 * ir3 + (bkb << 12));

        int rr = LiC1(mac1);
        int gg = LiC2(mac2);
        int bb = LiC3(mac3);
        reg_rgb0 = rr | (gg << 8) | (bb << 16) | chi;

        // 2

        mac1 = A1(m11 * reg_v1.x + m12 * reg_v1.y + m13 * reg_v1.z);
        mac2 = A2(m21 * reg_v1.x + m22 * reg_v1.y + m23 * reg_v1.z);
        mac3 = A3(m31 * reg_v1.x + m32 * reg_v1.y + m33 * reg_v1.z);

        ir1 = LiB1_1(mac1);
        ir2 = LiB2_1(mac2);
        ir3 = LiB3_1(mac3);

        mac1 = A1(c11 * ir1 + c12 * ir2 + c13 * ir3 + (bkr << 12));
        mac2 = A2(c21 * ir1 + c22 * ir2 + c23 * ir3 + (bkg << 12));
        mac3 = A3(c31 * ir1 + c32 * ir2 + c33 * ir3 + (bkb << 12));

        rr = LiC1(mac1);
        gg = LiC2(mac2);
        bb = LiC3(mac3);
        reg_rgb1 = rr | (gg << 8) | (bb << 16) | chi;

        // 3

        mac1 = A1(m11 * reg_v2.x + m12 * reg_v2.y + m13 * reg_v2.z);
        mac2 = A2(m21 * reg_v2.x + m22 * reg_v2.y + m23 * reg_v2.z);
        mac3 = A3(m31 * reg_v2.x + m32 * reg_v2.y + m33 * reg_v2.z);

        ir1 = LiB1_1(mac1);
        ir2 = LiB2_1(mac2);
        ir3 = LiB3_1(mac3);

        reg_mac1 = A1(c11 * ir1 + c12 * ir2 + c13 * ir3 + (bkr << 12));
        reg_mac2 = A2(c21 * ir1 + c22 * ir2 + c23 * ir3 + (bkg << 12));
        reg_mac3 = A3(c31 * ir1 + c32 * ir2 + c33 * ir3 + (bkb << 12));

        reg_ir1 = LiB1_1(reg_mac1);
        reg_ir2 = LiB2_1(reg_mac2);
        reg_ir3 = LiB3_1(reg_mac3);

        rr = LiC1(reg_mac1);
        gg = LiC2(reg_mac2);
        bb = LiC3(reg_mac3);
        reg_rgb2 = rr | (gg << 8) | (bb << 16) | chi;
    }

    public static void interpret_ncds(final int ci) {
        reg_flag = 0;

        int chi = reg_rgb & 0xff000000;
        int r = (reg_rgb & 0xff) << 4;
        int g = (reg_rgb & 0xff00) >> 4;
        int b = (reg_rgb & 0xff0000) >> 12;

        // [1,19,12] MAC1=A1[L11*VX0 + L12*VY0 + L13*VZ0]                 [1,19,24]
        // [1,19,12] MAC2=A1[L21*VX0 + L22*VY0 + L23*VZ0]                 [1,19,24]
        // [1,19,12] MAC3=A1[L31*VX0 + L32*VY0 + L33*VZ0]                 [1,19,24]
        // [1,3,12]  IR1= LB1[MAC1]                                     [1,19,12][lm=1]
        // [1,3,12]  IR2= LB2[MAC2]                                     [1,19,12][lm=1]
        // [1,3,12]  IR3= LB3[MAC3]                                     [1,19,12][lm=1]

        long m11 = reg_ls.m11;
        long m12 = reg_ls.m12;
        long m13 = reg_ls.m13;
        long m21 = reg_ls.m21;
        long m22 = reg_ls.m22;
        long m23 = reg_ls.m23;
        long m31 = reg_ls.m31;
        long m32 = reg_ls.m32;
        long m33 = reg_ls.m33;

        int mac1 = A1(m11 * reg_v0.x + m12 * reg_v0.y + m13 * reg_v0.z);
        int mac2 = A2(m21 * reg_v0.x + m22 * reg_v0.y + m23 * reg_v0.z);
        int mac3 = A3(m31 * reg_v0.x + m32 * reg_v0.y + m33 * reg_v0.z);
        int ir1 = LiB1_1(mac1);
        int ir2 = LiB2_1(mac2);
        int ir3 = LiB3_1(mac3);

        // [1,19,12] MAC1=A1[RBK + LR1*IR1 + LR2*IR2 + LR3*IR3]           [1,19,24]
        // [1,19,12] MAC2=A1[GBK + LG1*IR1 + LG2*IR2 + LG3*IR3]           [1,19,24]
        // [1,19,12] MAC3=A1[BBK + LB1*IR1 + LB2*IR2 + LB3*IR3]           [1,19,24]
        // [1,3,12]  IR1= LB1[MAC1]                                     [1,19,12][lm=1]
        // [1,3,12]  IR2= LB2[MAC2]                                     [1,19,12][lm=1]
        // [1,3,12]  IR3= LB3[MAC3]                                     [1,19,12][lm=1]

        long c11 = reg_lc.m11;
        long c12 = reg_lc.m12;
        long c13 = reg_lc.m13;
        long c21 = reg_lc.m21;
        long c22 = reg_lc.m22;
        long c23 = reg_lc.m23;
        long c31 = reg_lc.m31;
        long c32 = reg_lc.m32;
        long c33 = reg_lc.m33;

        long bkr = reg_rbk;
        long bkg = reg_gbk;
        long bkb = reg_bbk;

        mac1 = A1(c11 * ir1 + c12 * ir2 + c13 * ir3 + (bkr << 12));
        mac2 = A2(c21 * ir1 + c22 * ir2 + c23 * ir3 + (bkg << 12));
        mac3 = A3(c31 * ir1 + c32 * ir2 + c33 * ir3 + (bkb << 12));

        ir1 = LiB1_1(mac1);
        ir2 = LiB2_1(mac2);
        ir3 = LiB3_1(mac3);

        // [1,27,4]  MAC1=A1[R*IR1 + IR0*(LB1[RFC-R*IR1])]              [1,27,16][lm=0]
        // [1,27,4]  MAC2=A1[G*IR2 + IR0*(LB2[GFC-G*IR2])]              [1,27,16][lm=0]
        // [1,27,4]  MAC3=A1[B*IR3 + IR0*(LB3[BFC-B*IR3])]              [1,27,16][lm=0]
        // [1,3,4]  IR1= LB1[MAC1]                                     [1,27,4][lm=1]
        // [1,3,4]  IR2= LB2[MAC2]                                     [1,27,4][lm=1]
        // [1,3,4]  IR3= LB3[MAC3]                                     [1,27,4][lm=1]
        long ir0 = reg_ir0;
        reg_mac1 = A1(r * ir1 + ((ir0 * LiB1_0((reg_rfc << 12) - r * ir1)) >> 12));
        reg_mac2 = A2(g * ir2 + ((ir0 * LiB2_0((reg_gfc << 12) - g * ir2)) >> 12));
        reg_mac3 = A3(b * ir3 + ((ir0 * LiB3_0((reg_bfc << 12) - b * ir3)) >> 12));

        reg_ir1 = LiB1_1(reg_mac1);
        reg_ir2 = LiB2_1(reg_mac2);
        reg_ir3 = LiB3_1(reg_mac3);

        // [0,8,0]   Cd0<-Cd1<-Cd2<- CODE
        // [0,8,0]   R0<-R1<-R2<- LC1[MAC1]                             [1,27,4]
        // [0,8,0]   G0<-G1<-G2<- LC2[MAC2]                             [1,27,4]
        // [0,8,0]   B0<-B1<-B2<- LC3[MAC3]                             [1,27,4]
        int rr = LiC1(reg_mac1);
        int gg = LiC2(reg_mac2);
        int bb = LiC3(reg_mac3);
        reg_rgb0 = reg_rgb1;
        reg_rgb1 = reg_rgb2;
        reg_rgb2 = rr | (gg << 8) | (bb << 16) | chi;
    }

    public static void interpret_ncdt(final int ci) {
        reg_flag = 0;

        int chi = reg_rgb & 0xff000000;
        int r = (reg_rgb & 0xff) << 4;
        int g = (reg_rgb & 0xff00) >> 4;
        int b = (reg_rgb & 0xff0000) >> 12;

        // [1,19,12] MAC1=A1[L11*VX0 + L12*VY0 + L13*VZ0]                 [1,19,24]
        // [1,19,12] MAC2=A1[L21*VX0 + L22*VY0 + L23*VZ0]                 [1,19,24]
        // [1,19,12] MAC3=A1[L31*VX0 + L32*VY0 + L33*VZ0]                 [1,19,24]
        // [1,3,12]  IR1= LB1[MAC1]                                     [1,19,12][lm=1]
        // [1,3,12]  IR2= LB2[MAC2]                                     [1,19,12][lm=1]
        // [1,3,12]  IR3= LB3[MAC3]                                     [1,19,12][lm=1]

        long m11 = reg_ls.m11;
        long m12 = reg_ls.m12;
        long m13 = reg_ls.m13;
        long m21 = reg_ls.m21;
        long m22 = reg_ls.m22;
        long m23 = reg_ls.m23;
        long m31 = reg_ls.m31;
        long m32 = reg_ls.m32;
        long m33 = reg_ls.m33;

        int mac1 = A1(m11 * reg_v0.x + m12 * reg_v0.y + m13 * reg_v0.z);
        int mac2 = A2(m21 * reg_v0.x + m22 * reg_v0.y + m23 * reg_v0.z);
        int mac3 = A3(m31 * reg_v0.x + m32 * reg_v0.y + m33 * reg_v0.z);
        int ir1 = LiB1_1(mac1);
        int ir2 = LiB2_1(mac2);
        int ir3 = LiB3_1(mac3);

        // [1,19,12] MAC1=A1[RBK + LR1*IR1 + LR2*IR2 + LR3*IR3]           [1,19,24]
        // [1,19,12] MAC2=A1[GBK + LG1*IR1 + LG2*IR2 + LG3*IR3]           [1,19,24]
        // [1,19,12] MAC3=A1[BBK + LB1*IR1 + LB2*IR2 + LB3*IR3]           [1,19,24]
        // [1,3,12]  IR1= LB1[MAC1]                                     [1,19,12][lm=1]
        // [1,3,12]  IR2= LB2[MAC2]                                     [1,19,12][lm=1]
        // [1,3,12]  IR3= LB3[MAC3]                                     [1,19,12][lm=1]

        long c11 = reg_lc.m11;
        long c12 = reg_lc.m12;
        long c13 = reg_lc.m13;
        long c21 = reg_lc.m21;
        long c22 = reg_lc.m22;
        long c23 = reg_lc.m23;
        long c31 = reg_lc.m31;
        long c32 = reg_lc.m32;
        long c33 = reg_lc.m33;

        long bkr = reg_rbk;
        long bkg = reg_gbk;
        long bkb = reg_bbk;

        mac1 = A1(c11 * ir1 + c12 * ir2 + c13 * ir3 + (bkr << 12));
        mac2 = A2(c21 * ir1 + c22 * ir2 + c23 * ir3 + (bkg << 12));
        mac3 = A3(c31 * ir1 + c32 * ir2 + c33 * ir3 + (bkb << 12));

        ir1 = LiB1_1(mac1);
        ir2 = LiB2_1(mac2);
        ir3 = LiB3_1(mac3);

        // [1,27,4]  MAC1=A1[R*IR1 + IR0*(LB1[RFC-R*IR1])]              [1,27,16][lm=0]
        // [1,27,4]  MAC2=A1[G*IR2 + IR0*(LB2[GFC-G*IR2])]              [1,27,16][lm=0]
        // [1,27,4]  MAC3=A1[B*IR3 + IR0*(LB3[BFC-B*IR3])]              [1,27,16][lm=0]
        // [1,3,4]  IR1= LB1[MAC1]                                     [1,27,4][lm=1]
        // [1,3,4]  IR2= LB2[MAC2]                                     [1,27,4][lm=1]
        // [1,3,4]  IR3= LB3[MAC3]                                     [1,27,4][lm=1]
        long ir0 = reg_ir0;
        reg_mac1 = A1(r * ir1 + ((ir0 * LiB1_0((reg_rfc << 12) - r * ir1)) >> 12));
        reg_mac2 = A2(g * ir2 + ((ir0 * LiB2_0((reg_gfc << 12) - g * ir2)) >> 12));
        reg_mac3 = A3(b * ir3 + ((ir0 * LiB3_0((reg_bfc << 12) - b * ir3)) >> 12));

        // [0,8,0]   Cd0<-Cd1<-Cd2<- CODE
        // [0,8,0]   R0<-R1<-R2<- LC1[MAC1]                             [1,27,4]
        // [0,8,0]   G0<-G1<-G2<- LC2[MAC2]                             [1,27,4]
        // [0,8,0]   B0<-B1<-B2<- LC3[MAC3]                             [1,27,4]
        int rr = LiC1(reg_mac1);
        int gg = LiC2(reg_mac2);
        int bb = LiC3(reg_mac3);
        reg_rgb0 = rr | (gg << 8) | (bb << 16) | chi;

        // 2 ----

        mac1 = A1(m11 * reg_v1.x + m12 * reg_v1.y + m13 * reg_v1.z);
        mac2 = A2(m21 * reg_v1.x + m22 * reg_v1.y + m23 * reg_v1.z);
        mac3 = A3(m31 * reg_v1.x + m32 * reg_v1.y + m33 * reg_v1.z);
        ir1 = LiB1_1(mac1);
        ir2 = LiB2_1(mac2);
        ir3 = LiB3_1(mac3);

        mac1 = A1(c11 * ir1 + c12 * ir2 + c13 * ir3 + (bkr << 12));
        mac2 = A2(c21 * ir1 + c22 * ir2 + c23 * ir3 + (bkg << 12));
        mac3 = A3(c31 * ir1 + c32 * ir2 + c33 * ir3 + (bkb << 12));

        ir1 = LiB1_1(mac1);
        ir2 = LiB2_1(mac2);
        ir3 = LiB3_1(mac3);

        reg_mac1 = A1(r * ir1 + ((ir0 * LiB1_0((reg_rfc << 12) - r * ir1)) >> 12));
        reg_mac2 = A2(g * ir2 + ((ir0 * LiB2_0((reg_gfc << 12) - g * ir2)) >> 12));
        reg_mac3 = A3(b * ir3 + ((ir0 * LiB3_0((reg_bfc << 12) - b * ir3)) >> 12));

        rr = LiC1(reg_mac1);
        gg = LiC2(reg_mac2);
        bb = LiC3(reg_mac3);
        reg_rgb1 = rr | (gg << 8) | (bb << 16) | chi;

        // 3 ----

        mac1 = A1(m11 * reg_v0.x + m12 * reg_v0.y + m13 * reg_v0.z);
        mac2 = A2(m21 * reg_v0.x + m22 * reg_v0.y + m23 * reg_v0.z);
        mac3 = A3(m31 * reg_v0.x + m32 * reg_v0.y + m33 * reg_v0.z);
        ir1 = LiB1_1(mac1);
        ir2 = LiB2_1(mac2);
        ir3 = LiB3_1(mac3);

        mac1 = A1(c11 * ir1 + c12 * ir2 + c13 * ir3 + (bkr << 12));
        mac2 = A2(c21 * ir1 + c22 * ir2 + c23 * ir3 + (bkg << 12));
        mac3 = A3(c31 * ir1 + c32 * ir2 + c33 * ir3 + (bkb << 12));

        ir1 = LiB1_1(mac1);
        ir2 = LiB2_1(mac2);
        ir3 = LiB3_1(mac3);

        reg_mac1 = A1(r * ir1 + ((ir0 * LiB1_0((reg_rfc << 12) - r * ir1)) >> 12));
        reg_mac2 = A2(g * ir2 + ((ir0 * LiB2_0((reg_gfc << 12) - g * ir2)) >> 12));
        reg_mac3 = A3(b * ir3 + ((ir0 * LiB3_0((reg_bfc << 12) - b * ir3)) >> 12));

        rr = LiC1(reg_mac1);
        gg = LiC2(reg_mac2);
        bb = LiC3(reg_mac3);
        reg_rgb2 = rr | (gg << 8) | (bb << 16) | chi;
    }

    public static void interpret_dpct(final int ci) {
        // checked
//        [1,27,4] MAC1=A1[R0+ IR0*(Lm_B1[RFC - R0])] [1,27,16][lm=0]
//        [1,27,4] MAC2=A2[G0+ IR0*(Lm_B1[GFC - G0])] [1,27,16][lm=0]
//        [1,27,4] MAC3=A3[B0+ IR0*(Lm_B1[BFC - B0])] [1,27,16][lm=0]
//        [1,11,4] IR1=Lm_B1[MAC1] [1,27,4][lm=0]
//        [1,11,4] IR2=Lm_B2[MAC2] [1,27,4][lm=0]
//        [1,11,4] IR3=Lm_B3[MAC3] [1,27,4][lm=0]
//        [0,8,0] Cd0<-Cd1<-Cd2<- CODE
//        [0,8,0] R0<-R1<-R2<- Lm_C1[MAC1] [1,27,4]
//        [0,8,0] G0<-G1<-G2<- Lm_C2[MAC2] [1,27,4]
//        [0,8,0] B0<-B1<-B2<- Lm_C3[MAC3] [1,27,4]
//        *3

        reg_flag = 0;

        // 1 ----
        int chi = reg_rgb & 0xff000000;
        int r = (reg_rgb0 & 0xff) << 4;
        int g = (reg_rgb0 & 0xff00) >> 4;
        int b = (reg_rgb0 & 0xff0000) >> 12;

        int rr = LiC1(A1((r << 12) + reg_ir0 * LiB1_0(reg_rfc - r)));
        int gg = LiC2(A2((g << 12) + reg_ir0 * LiB1_0(reg_gfc - g)));
        int bb = LiC3(A3((b << 12) + reg_ir0 * LiB1_0(reg_bfc - b)));
        reg_rgb0 = chi | rr | (gg << 8) | (bb << 16);

        // 2 ----

        r = (reg_rgb1 & 0xff) << 4;
        g = (reg_rgb1 & 0xff00) >> 4;
        b = (reg_rgb1 & 0xff0000) >> 12;

        rr = LiC1(A1((r << 12) + reg_ir0 * LiB1_0(reg_rfc - r)));
        gg = LiC2(A2((g << 12) + reg_ir0 * LiB1_0(reg_gfc - g)));
        bb = LiC3(A3((b << 12) + reg_ir0 * LiB1_0(reg_bfc - b)));
        reg_rgb1 = chi | rr | (gg << 8) | (bb << 16);

        // 3 ----

        r = (reg_rgb2 & 0xff) << 4;
        g = (reg_rgb2 & 0xff00) >> 4;
        b = (reg_rgb2 & 0xff0000) >> 12;

        reg_mac1 = A1((r << 12) + reg_ir0 * LiB1_0(reg_rfc - r));
        reg_mac2 = A2((g << 12) + reg_ir0 * LiB1_0(reg_gfc - g));
        reg_mac3 = A3((b << 12) + reg_ir0 * LiB1_0(reg_bfc - b));

        reg_ir1 = LiB1_0(reg_mac1);
        reg_ir2 = LiB2_0(reg_mac2);
        reg_ir3 = LiB3_0(reg_mac3);

        rr = LiC1(reg_mac1);
        gg = LiC2(reg_mac2);
        bb = LiC3(reg_mac3);
        reg_rgb2 = chi | rr | (gg << 8) | (bb << 16);
    }

    public static void interpret_nccs(final int ci) {
        // untested
        reg_flag = 0;

        int chi = reg_rgb & 0xff000000;
        int r = (reg_rgb & 0xff) << 4;
        int g = (reg_rgb & 0xff00) >> 4;
        int b = (reg_rgb & 0xff0000) >> 12;

        long m11 = reg_ls.m11;
        long m12 = reg_ls.m12;
        long m13 = reg_ls.m13;
        long m21 = reg_ls.m21;
        long m22 = reg_ls.m22;
        long m23 = reg_ls.m23;
        long m31 = reg_ls.m31;
        long m32 = reg_ls.m32;
        long m33 = reg_ls.m33;

        long ss1 = m11 * reg_v0.x + m12 * reg_v0.y + m13 * reg_v0.z;
        long ss2 = m21 * reg_v0.x + m22 * reg_v0.y + m23 * reg_v0.z;
        long ss3 = m31 * reg_v0.x + m32 * reg_v0.y + m33 * reg_v0.z;

        int mac1 = A1(ss1);
        int mac2 = A2(ss2);
        int mac3 = A3(ss3);

        int ir1 = LiB1_1(mac1);
        int ir2 = LiB2_1(mac2);
        int ir3 = LiB3_1(mac3);

        long c11 = reg_lc.m11;
        long c12 = reg_lc.m12;
        long c13 = reg_lc.m13;
        long c21 = reg_lc.m21;
        long c22 = reg_lc.m22;
        long c23 = reg_lc.m23;
        long c31 = reg_lc.m31;
        long c32 = reg_lc.m32;
        long c33 = reg_lc.m33;

        long bkr = reg_rbk;
        long bkg = reg_gbk;
        long bkb = reg_bbk;

        ss1 = c11 * ir1 + c12 * ir2 + c13 * ir3 + (bkr << 12);
        ss2 = c21 * ir1 + c22 * ir2 + c23 * ir3 + (bkg << 12);
        ss3 = c31 * ir1 + c32 * ir2 + c33 * ir3 + (bkb << 12);

        mac1 = A1(ss1);
        mac2 = A2(ss2);
        mac3 = A3(ss3);

        ir1 = LiB1_1(mac1);
        ir2 = LiB2_1(mac2);
        ir3 = LiB3_1(mac3);

        reg_mac1 = A1(r * ir1);
        reg_mac2 = A2(g * ir2);
        reg_mac3 = A3(b * ir3);
        reg_ir1 = LiB1_1(reg_mac1);
        reg_ir2 = LiB2_1(reg_mac2);
        reg_ir3 = LiB3_1(reg_mac3);

        int rr = LiC1(reg_mac1);
        int gg = LiC2(reg_mac2);
        int bb = LiC3(reg_mac3);
        reg_rgb0 = reg_rgb1;
        reg_rgb1 = reg_rgb2;
        reg_rgb2 = rr | (gg << 8) | (bb << 16) | chi;
    }

    public static void interpret_cdp(final int ci) {
        // [1,19,12] MAC1=A1[RBK + LR1*IR1 + LR2*IR2 + LR3*IR3] [1,19,24]
        // [1,19,12] MAC2=A2[GBK + LG1*IR1 + LG2*IR2 + LG3*IR3] [1,19,24]
        // [1,19,12] MAC3=A3[BBK + LB1*IR1 + LB2*IR2 + LB3*IR3] [1,19,24]
        int ir1 = reg_ir1;
        int ir2 = reg_ir2;
        int ir3 = reg_ir3;
        long c11 = reg_lc.m11;
        long c12 = reg_lc.m12;
        long c13 = reg_lc.m13;
        long c21 = reg_lc.m21;
        long c22 = reg_lc.m22;
        long c23 = reg_lc.m23;
        long c31 = reg_lc.m31;
        long c32 = reg_lc.m32;
        long c33 = reg_lc.m33;

        long bkr = reg_rbk;
        long bkg = reg_gbk;
        long bkb = reg_bbk;

        int mac1 = A1(c11 * ir1 + c12 * ir2 + c13 * ir3 + (bkr << 12));
        int mac2 = A2(c21 * ir1 + c22 * ir2 + c23 * ir3 + (bkg << 12));
        int mac3 = A3(c31 * ir1 + c32 * ir2 + c33 * ir3 + (bkb << 12));

        // [1,3,12] IR1= Lm_B1[MAC1] [1,19,12][lm=1]
        // [1,3,12] IR2= Lm_B2[MAC2] [1,19,12][lm=1]
        // [1,3,12] IR3= Lm_B3[MAC3] [1,19,12][lm=1]
        ir1 = LiB1_1(mac1);
        ir2 = LiB1_1(mac2);
        ir3 = LiB1_1(mac3);

        // [1,27,4] MAC1=A1[R*IR1 + IR0*(Lm_B1[RFC-R*IR1])] [1,27,16][lm=0]
        // [1,27,4] MAC2=A2[G*IR2 + IR0*(Lm_B2[GFC-G*IR2])] [1,27,16][lm=0]
        // [1,27,4] MAC3=A3[B*IR3 + IR0*(Lm_B3[BFC-B*IR3])] [1,27,16][lm=0]
        // [1,3,12] IR1= Lm_B1[MAC1] [1,27,4][lm=1]
        // [1,3,12] IR2= Lm_B2[MAC2] [1,27,4][lm=1]
        // [1,3,12] IR3= Lm_B3[MAC3] [1,27,4][lm=1]
        long ir0 = reg_ir0;
        int r = (reg_rgb & 0xff) << 4;
        int g = (reg_rgb & 0xff00) >> 4;
        int b = (reg_rgb & 0xff0000) >> 12;
        reg_mac1 = A1(r * ir1 + ((ir0 * LiB1_0((reg_rfc << 12) - r * ir1)) >> 12));
        reg_mac2 = A2(g * ir2 + ((ir0 * LiB2_0((reg_gfc << 12) - g * ir2)) >> 12));
        reg_mac3 = A3(b * ir3 + ((ir0 * LiB3_0((reg_bfc << 12) - b * ir3)) >> 12));
        reg_ir1 = LiB1_1(mac1);
        reg_ir2 = LiB1_1(mac2);
        reg_ir3 = LiB1_1(mac3);

        // [0,8,0] Cd0<-Cd1<-Cd2<- CODE
        // [0,8,0] R0<-R1<-R2<- Lm_C1[MAC1] [1,27,4]
        // [0,8,0] G0<-G1<-G2<- Lm_C2[MAC2] [1,27,4]
        // [0,8,0] B0<-B1<-B2<- Lm_C3[MAC3] [1,27,4]
        int rr = LiC1(reg_mac1);
        int gg = LiC2(reg_mac2);
        int bb = LiC3(reg_mac3);
        reg_rgb0 = reg_rgb1;
        reg_rgb1 = reg_rgb2;
        reg_rgb2 = (reg_rgb & 0xff000000) | rr | (gg << 8) | (bb << 16);
    }

    public static void interpret_cc(final int ci) {
        // [1,19,12] MAC1=A1[RBK + LR1*IR1 + LR2*IR2 + LR3*IR3] [1,19,24]
        // [1,19,12] MAC2=A2[GBK + LG1*IR1 + LG2*IR2 + LG3*IR3] [1,19,24]
        // [1,19,12] MAC3=A3[BBK + LB1*IR1 + LB2*IR2 + LB3*IR3] [1,19,24]
        int ir1 = reg_ir1;
        int ir2 = reg_ir2;
        int ir3 = reg_ir3;
        long c11 = reg_lc.m11;
        long c12 = reg_lc.m12;
        long c13 = reg_lc.m13;
        long c21 = reg_lc.m21;
        long c22 = reg_lc.m22;
        long c23 = reg_lc.m23;
        long c31 = reg_lc.m31;
        long c32 = reg_lc.m32;
        long c33 = reg_lc.m33;

        long bkr = reg_rbk;
        long bkg = reg_gbk;
        long bkb = reg_bbk;

        int mac1 = A1(c11 * ir1 + c12 * ir2 + c13 * ir3 + (bkr << 12));
        int mac2 = A2(c21 * ir1 + c22 * ir2 + c23 * ir3 + (bkg << 12));
        int mac3 = A3(c31 * ir1 + c32 * ir2 + c33 * ir3 + (bkb << 12));

        // [1,3,12] IR1= Lm_B1[MAC1] [1,19,12][lm=1]
        // [1,3,12] IR2= Lm_B2[MAC2] [1,19,12][lm=1]
        // [1,3,12] IR3= Lm_B3[MAC3] [1,19,12][lm=1]
        ir1 = LiB1_1(mac1);
        ir2 = LiB1_1(mac2);
        ir3 = LiB1_1(mac3);

        // [1,27,4] MAC1=A1[R*IR1] [1,27,16]
        // [1,27,4] MAC2=A2[G*IR2] [1,27,16]
        // [1,27,4] MAC3=A3[B*IR3] [1,27,16]
        // [1,3,12] IR1= Lm_B1[MAC1] [1,27,4][lm=1]
        // [1,3,12] IR2= Lm_B2[MAC2] [1,27,4][lm=1]
        // [1,3,12] IR3= Lm_B3[MAC3] [1,27,4][lm=1]
        int r = (reg_rgb & 0xff) << 4;
        int g = (reg_rgb & 0xff00) >> 4;
        int b = (reg_rgb & 0xff0000) >> 12;
        reg_mac1 = A1(r * ir1);
        reg_mac2 = A2(g * ir2);
        reg_mac3 = A3(b * ir3);
        reg_ir1 = LiB1_1(mac1);
        reg_ir2 = LiB1_1(mac2);
        reg_ir3 = LiB1_1(mac3);

        // [0,8,0] Cd0<-Cd1<-Cd2<- CODE
        // [0,8,0] R0<-R1<-R2<- Lm_C1[MAC1] [1,27,4]
        // [0,8,0] G0<-G1<-G2<- Lm_C2[MAC2] [1,27,4]
        // [0,8,0] B0<-B1<-B2<- Lm_C3[MAC3] [1,27,4]
        int rr = LiC1(reg_mac1);
        int gg = LiC2(reg_mac2);
        int bb = LiC3(reg_mac3);
        reg_rgb0 = reg_rgb1;
        reg_rgb1 = reg_rgb2;
        reg_rgb2 = (reg_rgb & 0xff000000) | rr | (gg << 8) | (bb << 16);
    }

    public static void interpret_gpl(final int ci) {
        reg_flag = 0;

        long i = reg_ir0;
        if (0 != (ci & GTE_SF_MASK)) {
            reg_mac1 = A1((((long) reg_mac1) << 12) + i * reg_ir1);
            reg_mac2 = A2((((long) reg_mac2) << 12) + i * reg_ir2);
            reg_mac3 = A3((((long) reg_mac3) << 12) + i * reg_ir3);
        } else {
            reg_mac1 = A1((reg_mac1 + i * reg_ir1) << 12);
            reg_mac2 = A2((reg_mac2 + i * reg_ir2) << 12);
            reg_mac3 = A3((reg_mac3 + i * reg_ir3) << 12);
        }
        reg_ir1 = LiB1_0(reg_mac1);
        reg_ir2 = LiB2_0(reg_mac2);
        reg_ir3 = LiB3_0(reg_mac3);
        int rr = LiC1(reg_mac1);
        int gg = LiC2(reg_mac2);
        int bb = LiC3(reg_mac3);
        reg_rgb0 = reg_rgb1;
        reg_rgb1 = reg_rgb2;
        reg_rgb2 = (reg_rgb & 0xff000000) | rr | (gg << 8) | (bb << 16);
    }

    static int table[] =
            {
                    0xff, 0xfd, 0xfb, 0xf9, 0xf7, 0xf5, 0xf3, 0xf1, 0xef, 0xee, 0xec, 0xea, 0xe8, 0xe6, 0xe4, 0xe3,
                    0xe1, 0xdf, 0xdd, 0xdc, 0xda, 0xd8, 0xd6, 0xd5, 0xd3, 0xd1, 0xd0, 0xce, 0xcd, 0xcb, 0xc9, 0xc8,
                    0xc6, 0xc5, 0xc3, 0xc1, 0xc0, 0xbe, 0xbd, 0xbb, 0xba, 0xb8, 0xb7, 0xb5, 0xb4, 0xb2, 0xb1, 0xb0,
                    0xae, 0xad, 0xab, 0xaa, 0xa9, 0xa7, 0xa6, 0xa4, 0xa3, 0xa2, 0xa0, 0x9f, 0x9e, 0x9c, 0x9b, 0x9a,
                    0x99, 0x97, 0x96, 0x95, 0x94, 0x92, 0x91, 0x90, 0x8f, 0x8d, 0x8c, 0x8b, 0x8a, 0x89, 0x87, 0x86,
                    0x85, 0x84, 0x83, 0x82, 0x81, 0x7f, 0x7e, 0x7d, 0x7c, 0x7b, 0x7a, 0x79, 0x78, 0x77, 0x75, 0x74,
                    0x73, 0x72, 0x71, 0x70, 0x6f, 0x6e, 0x6d, 0x6c, 0x6b, 0x6a, 0x69, 0x68, 0x67, 0x66, 0x65, 0x64,
                    0x63, 0x62, 0x61, 0x60, 0x5f, 0x5e, 0x5d, 0x5d, 0x5c, 0x5b, 0x5a, 0x59, 0x58, 0x57, 0x56, 0x55,
                    0x54, 0x53, 0x53, 0x52, 0x51, 0x50, 0x4f, 0x4e, 0x4d, 0x4d, 0x4c, 0x4b, 0x4a, 0x49, 0x48, 0x48,
                    0x47, 0x46, 0x45, 0x44, 0x43, 0x43, 0x42, 0x41, 0x40, 0x3f, 0x3f, 0x3e, 0x3d, 0x3c, 0x3c, 0x3b,
                    0x3a, 0x39, 0x39, 0x38, 0x37, 0x36, 0x36, 0x35, 0x34, 0x33, 0x33, 0x32, 0x31, 0x31, 0x30, 0x2f,
                    0x2e, 0x2e, 0x2d, 0x2c, 0x2c, 0x2b, 0x2a, 0x2a, 0x29, 0x28, 0x28, 0x27, 0x26, 0x26, 0x25, 0x24,
                    0x24, 0x23, 0x22, 0x22, 0x21, 0x20, 0x20, 0x1f, 0x1e, 0x1e, 0x1d, 0x1d, 0x1c, 0x1b, 0x1b, 0x1a,
                    0x19, 0x19, 0x18, 0x18, 0x17, 0x16, 0x16, 0x15, 0x15, 0x14, 0x14, 0x13, 0x12, 0x12, 0x11, 0x11,
                    0x10, 0x0f, 0x0f, 0x0e, 0x0e, 0x0d, 0x0d, 0x0c, 0x0c, 0x0b, 0x0a, 0x0a, 0x09, 0x09, 0x08, 0x08,
                    0x07, 0x07, 0x06, 0x06, 0x05, 0x05, 0x04, 0x04, 0x03, 0x03, 0x02, 0x02, 0x01, 0x01, 0x00, 0x00,
                    0x00
            };

    static int dividex(int numerator, int denominator) {
        if (denominator == 0) {
            return Integer.MAX_VALUE;
        }
        return (int)((((long)numerator)<<16)/denominator);
    }

    static int divide(int numerator, int denominator) {
        if (denominator == 0) {
            return Integer.MAX_VALUE;
        }
        if (numerator <= 0x7fff) {
            return (numerator<<16)/denominator;
        } else {
            return (int)((((long)numerator)<<16)/denominator);
        }
    }

    static int gte_divide(int numerator, int denominator) {
        if (numerator < (denominator * 2)) {
            int shift = Integer.numberOfLeadingZeros(denominator) - 16;

            int r1 = (denominator << shift) & 0x7fff;
            int r2 = table[((r1 + 0x40) >> 7)] + 0x101;
            int r3 = ((0x80 - (r2 * (r1 + 0x8000))) >> 8) & 0x1ffff;
            int reciprocal = ((r2 * r3) + 0x80) >> 8;

            return (int) ((((long) reciprocal * (numerator << shift)) + 0x8000) >> 16);
        }

        return Integer.MAX_VALUE;
    }
}
