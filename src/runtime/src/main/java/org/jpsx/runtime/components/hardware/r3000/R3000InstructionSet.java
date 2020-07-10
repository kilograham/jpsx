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
package org.jpsx.runtime.components.hardware.r3000;

import org.apache.bcel.generic.*;
import org.apache.log4j.Logger;
import org.jpsx.api.components.core.addressspace.AddressSpace;
import org.jpsx.api.components.core.cpu.*;
import org.jpsx.runtime.FinalResolvedConnectionCache;
import org.jpsx.runtime.JPSXComponent;
import org.jpsx.runtime.components.core.CoreComponentConnections;
import org.jpsx.runtime.components.core.R3000Impl;

// todo move reg_hi/reg_lo into compilation context
public final class R3000InstructionSet extends JPSXComponent implements InstructionProvider {
    public static final Logger log = Logger.getLogger("R3000 Instruction Set");

    private static final String R3000_CLASS = R3000Impl.class.getName();
    private static final String CLASS = R3000InstructionSet.class.getName();

    private static final boolean ignoreArithmeticOverflow = true;

    private static class Refs extends FinalResolvedConnectionCache {
        public static final AddressSpace addressSpace = resolve(CoreComponentConnections.ADDRESS_SPACE);
        public static final R3000 r3000 = resolve(CoreComponentConnections.R3000);
        public static final int[] r3000Regs = resolve(CoreComponentConnections.R3000).getInterpreterRegs();
        public static final SCP scp = resolve(CoreComponentConnections.SCP);
    }

    public R3000InstructionSet() {
        super("JPSX R3000 Instruction Set");
    }

    @Override
    public void init() {
        super.init();
        CoreComponentConnections.INSTRUCTION_PROVIDERS.add(this);
    }

    // instructions and interpreters

    public void addInstructions(InstructionRegistrar registrar) {
        log.info("Adding R3000 instructions...");
        CPUInstruction i_add;
        if (ignoreArithmeticOverflow) {
            i_add = new CPUInstruction("add", R3000InstructionSet.class, 0, CPUInstruction.FLAG_SIMULATABLE | CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_READS_RT | CPUInstruction.FLAG_WRITES_RD) {
                public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                    int rs = bits_rs(ci);
                    int rt = bits_rt(ci);
                    int rd = bits_rd(ci);
                    ConstantPoolGen cp = context.getConstantPoolGen();

                    if (rd != 0) {
                        if (0 != (context.getConstantRegs() & (1 << rs))) {
                            if (0 != (context.getConstantRegs() & (1 << rt))) {
                                assert false;
                                int val = context.getRegValue(rs) + context.getRegValue(rt);
                                il.append(new PUSH(cp, val));
                                context.emitSetReg(il, rd);
                            } else {
                                context.emitGetReg(il, rt);
                                int val = context.getRegValue(rs);
                                if (val != 0) {
                                    il.append(new PUSH(cp, val));
                                    il.append(new IADD());
                                }
                                context.emitSetReg(il, rd);
                            }
                        } else {
                            if (0 != (context.getConstantRegs() & (1 << rt))) {
                                context.emitGetReg(il, rs);
                                int val = context.getRegValue(rt);
                                if (val != 0) {
                                    il.append(new PUSH(cp, val));
                                    il.append(new IADD());
                                }
                                context.emitSetReg(il, rd);
                            } else {
                                context.emitGetReg(il, rs);
                                context.emitGetReg(il, rt);
                                il.append(new IADD());
                                context.emitSetReg(il, rd);
                            }
                        }
                    }
                }

                public boolean simulate(int ci, int[] regs) {
                    int rs = bits_rs(ci);
                    int rt = bits_rt(ci);
                    int rd = bits_rd(ci);
                    if (rd != 0) {
                        regs[rd] = regs[rs] + regs[rt];
                    }
                    return true;
                }
            };
        } else {
            i_add = new CPUInstruction("add", R3000InstructionSet.class, 0, CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_READS_RT | CPUInstruction.FLAG_WRITES_RD | CPUInstruction.FLAG_MAY_SIGNAL_EXCEPTION);
        }
        CPUInstruction i_addi;
        if (ignoreArithmeticOverflow) {
            i_addi = new CPUInstruction("addi", R3000InstructionSet.class, 0, CPUInstruction.FLAG_SIMULATABLE | CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_WRITES_RT) {
                public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                    int rs = bits_rs(ci);
                    int rt = bits_rt(ci);
                    int imm = sign_extend(ci);
                    ConstantPoolGen cp = context.getConstantPoolGen();

                    if (rt != 0) {
                        if (0 != (context.getConstantRegs() & (1 << rs))) {
                            assert false;
                            int val = context.getRegValue(rs) + imm;
                            il.append(new PUSH(cp, val));
                            context.emitSetReg(il, rt);
                        } else {
                            context.emitGetReg(il, rs);
                            if (imm != 0) {
                                il.append(new PUSH(cp, imm));
                                il.append(new IADD());
                            }
                            context.emitSetReg(il, rt);
                        }
                    }
                }

                public boolean simulate(int ci, int[] regs) {
                    int rs = bits_rs(ci);
                    int rt = bits_rt(ci);
                    int imm = sign_extend(ci);
                    if (rt != 0) {
                        regs[rt] = regs[rs] + imm;
                    }
                    return true;
                }
            };
        } else {
            i_addi = new CPUInstruction("addi", R3000InstructionSet.class, 0, CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_WRITES_RT | CPUInstruction.FLAG_MAY_SIGNAL_EXCEPTION);
        }
        CPUInstruction i_addiu = new CPUInstruction("addiu", R3000InstructionSet.class, 0, CPUInstruction.FLAG_SIMULATABLE | CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_WRITES_RT) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int rs = bits_rs(ci);
                int rt = bits_rt(ci);
                int imm = sign_extend(ci);
                ConstantPoolGen cp = context.getConstantPoolGen();

                if (rt != 0) {
                    if (0 != (context.getConstantRegs() & (1 << rs))) {
                        int val = context.getRegValue(rs) + imm;
                        il.append(new PUSH(cp, val));
                        context.emitSetReg(il, rt);
                    } else {
                        context.emitGetReg(il, rs);
                        if (imm != 0) {
                            il.append(new PUSH(cp, imm));
                            il.append(new IADD());
                        }
                        context.emitSetReg(il, rt);
                    }
                }
            }

            public boolean simulate(int ci, int[] regs) {
                int rs = bits_rs(ci);
                int rt = bits_rt(ci);
                int imm = sign_extend(ci);
                if (rt != 0) {
                    regs[rt] = regs[rs] + imm;
                }
                return true;
            }
        };
        CPUInstruction i_addu = new CPUInstruction("addu", R3000InstructionSet.class, 0, CPUInstruction.FLAG_SIMULATABLE | CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_READS_RT | CPUInstruction.FLAG_WRITES_RD) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int rs = bits_rs(ci);
                int rt = bits_rt(ci);
                int rd = bits_rd(ci);
                ConstantPoolGen cp = context.getConstantPoolGen();

                if (rd != 0) {
                    if (0 != (context.getConstantRegs() & (1 << rs))) {
                        if (0 != (context.getConstantRegs() & (1 << rt))) {
                            assert false;
                            int val = context.getRegValue(rs) + context.getRegValue(rt);
                            il.append(new PUSH(cp, val));
                            context.emitSetReg(il, rd);
                        } else {
                            context.emitGetReg(il, rt);
                            int val = context.getRegValue(rs);
                            if (val != 0) {
                                il.append(new PUSH(cp, val));
                                il.append(new IADD());
                            }
                            context.emitSetReg(il, rd);
                        }
                    } else {
                        if (0 != (context.getConstantRegs() & (1 << rt))) {
                            context.emitGetReg(il, rs);
                            int val = context.getRegValue(rt);
                            if (val != 0) {
                                il.append(new PUSH(cp, val));
                                il.append(new IADD());
                            }
                            context.emitSetReg(il, rd);
                        } else {
                            context.emitGetReg(il, rs);
                            context.emitGetReg(il, rt);
                            il.append(new IADD());
                            context.emitSetReg(il, rd);
                        }
                    }
                }
            }

            public boolean simulate(int ci, int[] regs) {
                int rs = bits_rs(ci);
                int rt = bits_rt(ci);
                int rd = bits_rd(ci);
                if (rd != 0) {
                    regs[rd] = regs[rs] + regs[rt];
                }
                return true;
            }
        };
        CPUInstruction i_and = new CPUInstruction("and", R3000InstructionSet.class, 0, CPUInstruction.FLAG_SIMULATABLE | CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_READS_RT | CPUInstruction.FLAG_WRITES_RD) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int rs = bits_rs(ci);
                int rt = bits_rt(ci);
                int rd = bits_rd(ci);
                ConstantPoolGen cp = context.getConstantPoolGen();

                if (rd != 0) {
                    if (0 != (context.getConstantRegs() & (1 << rs))) {
                        if (0 != (context.getConstantRegs() & (1 << rt))) {
                            assert false;
                            int val = context.getRegValue(rs) & context.getRegValue(rt);
                            il.append(new PUSH(cp, val));
                            context.emitSetReg(il, rd);
                        } else {
                            int val = context.getRegValue(rs);
                            context.emitGetReg(il, rt);
                            il.append(new PUSH(cp, val));
                            il.append(new IAND());
                            context.emitSetReg(il, rd);
                        }
                    } else {
                        if (0 != (context.getConstantRegs() & (1 << rt))) {
                            context.emitGetReg(il, rs);
                            int val = context.getRegValue(rt);
                            il.append(new PUSH(cp, val));
                            il.append(new IAND());
                            context.emitSetReg(il, rd);
                        } else {
                            context.emitGetReg(il, rs);
                            context.emitGetReg(il, rt);
                            il.append(new IAND());
                            context.emitSetReg(il, rd);
                        }
                    }
                }
            }

            public boolean simulate(int ci, int[] regs) {
                int rs = bits_rs(ci);
                int rt = bits_rt(ci);
                int rd = bits_rd(ci);
                if (rd != 0) {
                    regs[rd] = regs[rs] & regs[rt];
                }
                return true;
            }
        };
        CPUInstruction i_andi = new CPUInstruction("andi", R3000InstructionSet.class, 0, CPUInstruction.FLAG_SIMULATABLE | CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_WRITES_RT) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int rs = bits_rs(ci);
                int rt = bits_rt(ci);
                int imm = lo(ci);
                ConstantPoolGen cp = context.getConstantPoolGen();

                if (rt != 0) {
                    if (0 != (context.getConstantRegs() & (1 << rs))) {
                        assert false;
                        int val = context.getRegValue(rs) & imm;
                        il.append(new PUSH(cp, val));
                        context.emitSetReg(il, rt);
                    } else {
                        context.emitGetReg(il, rs);
                        if (imm != 0) {
                            il.append(new PUSH(cp, imm));
                            il.append(new IAND());
                        }
                        context.emitSetReg(il, rt);
                    }
                }
            }

            public boolean simulate(int ci, int[] regs) {
                int rs = bits_rs(ci);
                int rt = bits_rt(ci);
                int imm = lo(ci);
                if (rt != 0) {
                    regs[rt] = regs[rs] & imm;
                }
                return true;
            }
        };
        CPUInstruction i_beq = new CPUInstruction("beq", R3000InstructionSet.class, 0, CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_READS_RT | CPUInstruction.FLAG_BRANCH | CPUInstruction.FLAG_IMM_NEAR_TARGET) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int rs = bits_rs(ci);
                int rt = bits_rt(ci);
                ConstantPoolGen cp = context.getConstantPoolGen();

                if (rs == rt) {
                    context.emitDelaySlot(il);
                    il.append(new GOTO(context.getBranchTarget(address + 4 + R3000.Util.signed_branch_delta(ci))));
                } else {
                    int cr = context.getConstantRegs();
                    if (0 != (cr & (1 << rs))) {
                        if (0 != (cr & (1 << rt))) {
                            // rs const, rt const
                            il.append(new PUSH(cp, context.getRegValue(rs)));
                            il.append(new PUSH(cp, context.getRegValue(rt)));
                            context.emitDelaySlot(il);
                            il.append(new IF_ICMPEQ(context.getBranchTarget(address + 4 + R3000.Util.signed_branch_delta(ci))));
                        } else {
                            // rs const, rt varied
                            il.append(new PUSH(cp, context.getRegValue(rs)));
                            context.emitGetReg(il, rt);
                            context.emitDelaySlot(il);
                            il.append(new IF_ICMPEQ(context.getBranchTarget(address + 4 + R3000.Util.signed_branch_delta(ci))));
                        }
                    } else {
                        if (0 != (cr & (1 << rt))) {
                            // rs varied, rt const
                            context.emitGetReg(il, rs);
                            il.append(new PUSH(cp, context.getRegValue(rt)));
                            context.emitDelaySlot(il);
                            il.append(new IF_ICMPEQ(context.getBranchTarget(address + 4 + R3000.Util.signed_branch_delta(ci))));
                        } else {
                            // rs varied, rt varied
                            context.emitGetReg(il, rs);
                            context.emitGetReg(il, rt);
                            context.emitDelaySlot(il);
                            il.append(new IF_ICMPEQ(context.getBranchTarget(address + 4 + R3000.Util.signed_branch_delta(ci))));
                        }
                    }
                }
            }

            public int getBranchType(int ci) {
                if (R3000.Util.bits_rs(ci) != R3000.Util.bits_rt(ci))
                    return BRANCH_SOMETIMES;
                return BRANCH_ALWAYS;
            }
        };
        CPUInstruction i_bgez = new CPUInstruction("bgez", R3000InstructionSet.class, 0, CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_BRANCH | CPUInstruction.FLAG_IMM_NEAR_TARGET) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int rs = bits_rs(ci);

                int cr = context.getConstantRegs();
                if (0 != (cr & (1 << rs))) {
                    context.emitDelaySlot(il);
                    if (context.getRegValue(rs) >= 0) {
                        il.append(new GOTO(context.getBranchTarget(address + 4 + R3000.Util.signed_branch_delta(ci))));
                    }
                } else {
                    context.emitGetReg(il, rs);
                    context.emitDelaySlot(il);
                    il.append(new IFGE(context.getBranchTarget(address + 4 + R3000.Util.signed_branch_delta(ci))));
                }
            }

            public int getBranchType(int ci) {
                if (R3000.Util.bits_rs(ci) != 0)
                    return BRANCH_SOMETIMES;
                return BRANCH_ALWAYS;
            }
        };
        CPUInstruction i_bgezal = new CPUInstruction("bgezal", R3000InstructionSet.class, 0, CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_BRANCH | CPUInstruction.FLAG_IMM_NEAR_TARGET | CPUInstruction.FLAG_LINK) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int rs = bits_rs(ci);

                int cr = context.getConstantRegs();
                int target = address + 4 + R3000.Util.signed_branch_delta(ci);
                if (0 != (cr & (1 << rs))) {
                    log.warn("Untested constant bgezal");
                    context.emitDelaySlot(il);
                    if (context.getRegValue(rs) >= 0) {
                        ConstantPoolGen cp = context.getConstantPoolGen();
                        il.append(new PUSH(cp, address + 8));
                        context.emitSetReg(il, R3000.R_RETADDR);
                        context.emitCall(il, target, address + 8);
                    }
                } else {
                    context.emitGetReg(il, rs);
                    context.emitDelaySlot(il);
                    // skip over the call if not GE
                    IFLT lt = new IFLT(null);
                    il.append(lt);
                    ConstantPoolGen cp = context.getConstantPoolGen();
                    il.append(new PUSH(cp, address + 8));
                    context.emitSetReg(il, R3000.R_RETADDR);
                    context.emitCall(il, target, address + 8);
                    // Link up the to whatever comes next - since we don't now what that is, we'll insert a NOP
                    lt.setTarget(il.append(new NOP()));
                }
            }

            public int getBranchType(int ci) {
                if (R3000.Util.bits_rs(ci) != 0)
                    return BRANCH_SOMETIMES;
                return BRANCH_ALWAYS;
            }
        };
        CPUInstruction i_bgtz = new CPUInstruction("bgtz", R3000InstructionSet.class, 0, CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_BRANCH | CPUInstruction.FLAG_IMM_NEAR_TARGET) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int rs = bits_rs(ci);

                int cr = context.getConstantRegs();
                if (0 != (cr & (1 << rs))) {
                    context.emitDelaySlot(il);
                    if (context.getRegValue(rs) > 0) {
                        il.append(new GOTO(context.getBranchTarget(address + 4 + R3000.Util.signed_branch_delta(ci))));
                    }
                } else {
                    context.emitGetReg(il, rs);
                    context.emitDelaySlot(il);
                    il.append(new IFGT(context.getBranchTarget(address + 4 + R3000.Util.signed_branch_delta(ci))));
                }
            }

            public int getBranchType(int ci) {
                if (R3000.Util.bits_rs(ci) != 0)
                    return BRANCH_SOMETIMES;
                return BRANCH_NEVER;
            }
        };
        CPUInstruction i_blez = new CPUInstruction("blez", R3000InstructionSet.class, 0, CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_BRANCH | CPUInstruction.FLAG_IMM_NEAR_TARGET) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int rs = bits_rs(ci);

                int cr = context.getConstantRegs();
                if (0 != (cr & (1 << rs))) {
                    context.emitDelaySlot(il);
                    if (context.getRegValue(rs) <= 0) {
                        il.append(new GOTO(context.getBranchTarget(address + 4 + R3000.Util.signed_branch_delta(ci))));
                    }
                } else {
                    context.emitGetReg(il, rs);
                    context.emitDelaySlot(il);
                    il.append(new IFLE(context.getBranchTarget(address + 4 + R3000.Util.signed_branch_delta(ci))));
                }
            }

            public int getBranchType(int ci) {
                if (R3000.Util.bits_rs(ci) != 0)
                    return BRANCH_SOMETIMES;
                return BRANCH_ALWAYS;
            }
        };
        CPUInstruction i_bltz = new CPUInstruction("bltz", R3000InstructionSet.class, 0, CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_BRANCH | CPUInstruction.FLAG_IMM_NEAR_TARGET) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int rs = bits_rs(ci);

                int cr = context.getConstantRegs();
                if (0 != (cr & (1 << rs))) {
                    context.emitDelaySlot(il);
                    if (context.getRegValue(rs) < 0) {
                        il.append(new GOTO(context.getBranchTarget(address + 4 + R3000.Util.signed_branch_delta(ci))));
                    }
                } else {
                    context.emitGetReg(il, rs);
                    context.emitDelaySlot(il);
                    il.append(new IFLT(context.getBranchTarget(address + 4 + R3000.Util.signed_branch_delta(ci))));
                }
            }

            public int getBranchType(int ci) {
                if (R3000.Util.bits_rs(ci) != 0)
                    return BRANCH_SOMETIMES;
                return BRANCH_NEVER;
            }
        };
        CPUInstruction i_bltzal = new CPUInstruction("bltzal", R3000InstructionSet.class, 0, CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_BRANCH | CPUInstruction.FLAG_IMM_NEAR_TARGET | CPUInstruction.FLAG_LINK) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int rs = bits_rs(ci);

                int cr = context.getConstantRegs();
                int target = address + 4 + R3000.Util.signed_branch_delta(ci);
                if (0 != (cr & (1 << rs))) {
                    log.warn("Untested constant bltzal");
                    context.emitDelaySlot(il);
                    if (context.getRegValue(rs) < 0) {
                        ConstantPoolGen cp = context.getConstantPoolGen();
                        il.append(new PUSH(cp, address + 8));
                        context.emitSetReg(il, R3000.R_RETADDR);
                        context.emitCall(il, target, address + 8);
                    }
                } else {
                    context.emitGetReg(il, rs);
                    context.emitDelaySlot(il);
                    // skip over the call if not LT
                    IFGE ge = new IFGE(null);
                    il.append(ge);
                    ConstantPoolGen cp = context.getConstantPoolGen();
                    il.append(new PUSH(cp, address + 8));
                    context.emitSetReg(il, R3000.R_RETADDR);
                    context.emitCall(il, target, address + 8);
                    // Link up the to whatever comes next - since we don't now what that is, we'll insert a NOP
                    ge.setTarget(il.append(new NOP()));
                }
            }

            public int getBranchType(int ci) {
                if (R3000.Util.bits_rs(ci) != 0)
                    return BRANCH_SOMETIMES;
                return BRANCH_NEVER;
            }
        };
        CPUInstruction i_bne = new CPUInstruction("bne", R3000InstructionSet.class, 0, CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_BRANCH | CPUInstruction.FLAG_IMM_NEAR_TARGET) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int rs = bits_rs(ci);
                int rt = bits_rt(ci);
                ConstantPoolGen cp = context.getConstantPoolGen();

                if (rs != rt) {
                    // todo constant values!
                    int cr = context.getConstantRegs();
                    if (0 != (cr & (1 << rs))) {
                        if (0 != (cr & (1 << rt))) {
                            // rs const, rt const
                            il.append(new PUSH(cp, context.getRegValue(rs)));
                            il.append(new PUSH(cp, context.getRegValue(rt)));
                            context.emitDelaySlot(il);
                            il.append(new IF_ICMPNE(context.getBranchTarget(address + 4 + R3000.Util.signed_branch_delta(ci))));
                        } else {
                            // rs const, rt varied
                            il.append(new PUSH(cp, context.getRegValue(rs)));
                            context.emitGetReg(il, rt);
                            context.emitDelaySlot(il);
                            il.append(new IF_ICMPNE(context.getBranchTarget(address + 4 + R3000.Util.signed_branch_delta(ci))));
                        }
                    } else {
                        if (0 != (cr & (1 << rt))) {
                            // rs varied, rt const
                            context.emitGetReg(il, rs);
                            il.append(new PUSH(cp, context.getRegValue(rt)));
                            context.emitDelaySlot(il);
                            il.append(new IF_ICMPNE(context.getBranchTarget(address + 4 + R3000.Util.signed_branch_delta(ci))));
                        } else {
                            // rs varied, rt varied
                            context.emitGetReg(il, rs);
                            context.emitGetReg(il, rt);
                            context.emitDelaySlot(il);
                            il.append(new IF_ICMPNE(context.getBranchTarget(address + 4 + R3000.Util.signed_branch_delta(ci))));
                        }
                    }
                }
            }

            public int getBranchType(int ci) {
                if (R3000.Util.bits_rs(ci) != R3000.Util.bits_rt(ci))
                    return BRANCH_SOMETIMES;
                return BRANCH_NEVER;
            }
        };
        CPUInstruction i_break = new CPUInstruction("break", R3000InstructionSet.class, 0, CPUInstruction.FLAG_MAY_SIGNAL_EXCEPTION);
        CPUInstruction i_div = new CPUInstruction("div", R3000InstructionSet.class, 0, CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_READS_RT) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int rs = bits_rs(ci);
                int rt = bits_rt(ci);
                ConstantPoolGen cp = context.getConstantPoolGen();

                // reg_lo = regs[rs]/regs[rt];
                // reg_hi = regs[rs]%regs[rt];

                IFEQ gotoDivideByZero = null;
                GOTO gotoWriteRegHi = null;
                if (rt != 0) {
                    if (0 != (context.getConstantRegs() & (1 << rt))) {
                        il.append(new PUSH(cp, context.getRegValue(rt)));
                    } else {
                        context.emitGetReg(il, rt);
                    }

                    gotoDivideByZero = new IFEQ(null);
                    il.append(gotoDivideByZero);

                    if (0 != (context.getConstantRegs() & (1 << rs))) {
                        il.append(new PUSH(cp, context.getRegValue(rs)));
                    } else {
                        context.emitGetReg(il, rs);
                    }

                    if (0 != (context.getConstantRegs() & (1 << rt))) {
                        il.append(new PUSH(cp, context.getRegValue(rt)));
                    } else {
                        context.emitGetReg(il, rt);
                    }

                    il.append(new IDIV());
                    il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(R3000_CLASS, "reg_lo", "I")));

                    if (0 != (context.getConstantRegs() & (1 << rs))) {
                        il.append(new PUSH(cp, context.getRegValue(rs)));
                    } else {
                        context.emitGetReg(il, rs);
                    }
                    if (0 != (context.getConstantRegs() & (1 << rt))) {
                        il.append(new PUSH(cp, context.getRegValue(rt)));
                    } else {
                        context.emitGetReg(il, rt);
                    }
                    il.append(new IREM());
                    gotoWriteRegHi = new GOTO(null);
                    il.append(gotoWriteRegHi);
                }
                // Code for divide by zero
                InstructionHandle end = il.getEnd();
                if (0 != (context.getConstantRegs() & (1 << rs))) {
                    il.append(new PUSH(cp, context.getRegValue(rs)));
                } else {
                    context.emitGetReg(il, rs);
                }
                if (gotoDivideByZero != null) {
                    gotoDivideByZero.setTarget(end.getNext());
                }
                il.append(new DUP());
                il.append(new PUSH(cp, 31));
                il.append(new ISHR());
                il.append(new PUSH(cp, 1));
                il.append(new ISHL());
                il.append(new PUSH(cp, 1));
                il.append(new ISUB());
                il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(R3000_CLASS, "reg_lo", "I")));

                InstructionHandle writeRegHi = il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(R3000_CLASS, "reg_hi", "I")));
                if (gotoWriteRegHi != null) {
                    gotoWriteRegHi.setTarget(writeRegHi);
                }
            }
        };

        CPUInstruction i_divu = new CPUInstruction("divu", R3000InstructionSet.class, 0, CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_READS_RT) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int rs = bits_rs(ci);
                int rt = bits_rt(ci);
                ConstantPoolGen cp = context.getConstantPoolGen();

                //long a = longFromUnsigned( regs[rs]);
                //long b = longFromUnsigned( regs[rt]);
                //reg_lo = (int)(a/b);
                //reg_hi = (int)(a%b);

                IFEQ gotoDivideByZero = null;
                GOTO gotoWriteRegHi = null;

                if (rt != 0) {
                    if (0 != (context.getConstantRegs() & (1 << rt))) {
                        il.append(new PUSH(cp, context.getRegValue(rt)));
                    } else {
                        context.emitGetReg(il, rt);
                    }

                    gotoDivideByZero = new IFEQ(null);
                    il.append(gotoDivideByZero);

                    if (0 != (context.getConstantRegs() & (1 << rs))) {
                        il.append(new PUSH(cp, longFromUnsigned(context.getRegValue(rs))));
                    } else {
                        context.emitGetReg(il, rs);
                        emitLongFromUnsigned(cp, il);
                    }
                    if (0 != (context.getConstantRegs() & (1 << rt))) {
                        il.append(new PUSH(cp, longFromUnsigned(context.getRegValue(rt))));
                    } else {
                        context.emitGetReg(il, rt);
                        emitLongFromUnsigned(cp, il);
                    }
                    il.append(new LDIV());
                    il.append(new L2I());
                    il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(R3000_CLASS, "reg_lo", "I")));

                    if (0 != (context.getConstantRegs() & (1 << rs))) {
                        il.append(new PUSH(cp, longFromUnsigned(context.getRegValue(rs))));
                    } else {
                        context.emitGetReg(il, rs);
                        emitLongFromUnsigned(cp, il);
                    }
                    if (0 != (context.getConstantRegs() & (1 << rt))) {
                        il.append(new PUSH(cp, longFromUnsigned(context.getRegValue(rt))));
                    } else {
                        context.emitGetReg(il, rt);
                        emitLongFromUnsigned(cp, il);
                    }
                    il.append(new LREM());
                    il.append(new L2I());
                    gotoWriteRegHi = new GOTO(null);
                    il.append(gotoWriteRegHi);
                }
                // Code for divide by zero
                InstructionHandle divdieByZero = il.append(new PUSH(cp, -1));
                if (gotoDivideByZero != null) {
                    gotoDivideByZero.setTarget(divdieByZero);
                }
                il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(R3000_CLASS, "reg_lo", "I")));

                if (0 != (context.getConstantRegs() & (1 << rs))) {
                    il.append(new PUSH(cp, context.getRegValue(rs)));
                } else {
                    context.emitGetReg(il, rs);
                }
                InstructionHandle writeRegHi = il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(R3000_CLASS, "reg_hi", "I")));
                if (gotoWriteRegHi != null) {
                    gotoWriteRegHi.setTarget(writeRegHi);
                }
            }
        };
        CPUInstruction i_j = new CPUInstruction("j", R3000InstructionSet.class, 0, CPUInstruction.FLAG_BRANCH | CPUInstruction.FLAG_IMM_FAR_TARGET | CPUInstruction.FLAG_UNCONDITIONAL) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                context.emitDelaySlot(il);
                int delay = address + 4;
                int target = delay & 0xf0000000;
                target += ((ci & 0x3ffffff) << 2);
                il.append(new GOTO(context.getBranchTarget(target)));
            }
        };
        CPUInstruction i_jal = new CPUInstruction("jal", R3000InstructionSet.class, 0, CPUInstruction.FLAG_BRANCH | CPUInstruction.FLAG_IMM_FAR_TARGET | CPUInstruction.FLAG_UNCONDITIONAL | CPUInstruction.FLAG_LINK) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                context.emitDelaySlot(il);
                int delay = address + 4;
                int target = delay & 0xf0000000;
                target += ((ci & 0x3ffffff) << 2);
                ConstantPoolGen cp = context.getConstantPoolGen();
                il.append(new PUSH(cp, address + 8));
                context.emitSetReg(il, R3000.R_RETADDR);
                context.emitCall(il, target, address + 8);
            }
        };
        CPUInstruction i_jalr = new CPUInstruction("jalr", R3000InstructionSet.class, 0, CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_WRITES_RD | CPUInstruction.FLAG_BRANCH | CPUInstruction.FLAG_UNCONDITIONAL | CPUInstruction.FLAG_LINK) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {

                int rs = bits_rs(ci);
                int rd = bits_rd(ci);
                context.emitDelaySlot(il);
                // todo constant!
                context.emitGetReg(il, rs);
                if (rd != 0) {
                    ConstantPoolGen cp = context.getConstantPoolGen();
                    il.append(new PUSH(cp, address + 8));
                    context.emitSetReg(il, rd);
                }
                context.emitCall(il, address + 8);
            }
        };
        CPUInstruction i_jr = new CPUInstruction("jr", R3000InstructionSet.class, 0, CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_BRANCH | CPUInstruction.FLAG_UNCONDITIONAL) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                context.emitDelaySlot(il);
                int r = bits_rs(ci);
                if (0 != (context.getConstantRegs() & (1 << r))) {
                    context.emitJump(il, context.getRegValue(r));
                } else {
                    context.emitGetReg(il, bits_rs(ci));
                    context.emitJump(il);
                }
            }
        };

        CPUInstruction i_lb = new CPUInstruction("lb", R3000InstructionSet.class, 0, CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_WRITES_RT | CPUInstruction.FLAG_MEM8) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int base = bits_rs(ci);
                int rt = bits_rt(ci);
                int offset = sign_extend(ci);

                if (0 != (context.getConstantRegs() & (1 << base))) {
                    context.emitReadMem8(il, context.getRegValue(base) + offset, true);
                    if (rt != 0) {
                        context.emitSetReg(il, rt);
                    } else {
                        il.append(new POP());
                    }
                } else {
                    context.emitReadMem8(il, base, offset);
                    if (rt != 0) {
                        ConstantPoolGen cp = context.getConstantPoolGen();
                        il.append(new PUSH(cp, 24));
                        il.append(new ISHL());
                        il.append(new PUSH(cp, 24));
                        il.append(new ISHR());
                        context.emitSetReg(il, rt);
                    } else {
                        il.append(new POP());
                    }
                }
            }
        };
        CPUInstruction i_lbu = new CPUInstruction("lbu", R3000InstructionSet.class, 0, CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_WRITES_RT | CPUInstruction.FLAG_MEM8) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int base = bits_rs(ci);
                int rt = bits_rt(ci);
                int offset = sign_extend(ci);

                if (0 != (context.getConstantRegs() & (1 << base))) {
                    context.emitReadMem8(il, context.getRegValue(base) + offset, false);
                } else {
                    context.emitReadMem8(il, base, offset);
                }
                if (rt != 0) {
                    context.emitSetReg(il, rt);
                } else {
                    il.append(new POP());
                }
            }
        };
        CPUInstruction i_lh = new CPUInstruction("lh", R3000InstructionSet.class, 0, CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_WRITES_RT | CPUInstruction.FLAG_MEM16) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int base = bits_rs(ci);
                int rt = bits_rt(ci);
                int offset = sign_extend(ci);

                if (0 != (context.getConstantRegs() & (1 << base))) {
                    context.emitReadMem16(il, context.getRegValue(base) + offset, true);
                    if (rt != 0) {
                        context.emitSetReg(il, rt);
                    } else {
                        il.append(new POP());
                    }
                } else {
                    context.emitReadMem16(il, base, offset);
                    if (rt != 0) {
                        ConstantPoolGen cp = context.getConstantPoolGen();
                        il.append(new PUSH(cp, 16));
                        il.append(new ISHL());
                        il.append(new PUSH(cp, 16));
                        il.append(new ISHR());
                        context.emitSetReg(il, rt);
                    } else {
                        il.append(new POP());
                    }
                }
            }
        };
        CPUInstruction i_lhu = new CPUInstruction("lhu", R3000InstructionSet.class, 0, CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_WRITES_RT | CPUInstruction.FLAG_MEM16) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int base = bits_rs(ci);
                int rt = bits_rt(ci);
                int offset = sign_extend(ci);

                if (0 != (context.getConstantRegs() & (1 << base))) {
                    context.emitReadMem16(il, context.getRegValue(base) + offset, false);
                } else {
                    context.emitReadMem16(il, base, offset);
                }
                if (rt != 0) {
                    context.emitSetReg(il, rt);
                } else {
                    il.append(new POP());
                }
            }
        };

        CPUInstruction i_lui = new CPUInstruction("lui", R3000InstructionSet.class, 0, CPUInstruction.FLAG_SIMULATABLE | CPUInstruction.FLAG_WRITES_RT) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int rt = bits_rt(ci);
                if (rt != 0) {
                    il.append(new PUSH(context.getConstantPoolGen(), ci << 16));
                    context.emitSetReg(il, rt);
                }
            }

            public boolean simulate(int ci, int[] regs) {
                int rt = bits_rt(ci);
                if (rt != 0) {
                    regs[rt] = ci << 16;
                }
                return true;
            }
        };
        CPUInstruction i_lw = new CPUInstruction("lw", R3000InstructionSet.class, 0, CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_WRITES_RT | CPUInstruction.FLAG_MEM32) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int base = bits_rs(ci);
                int rt = bits_rt(ci);
                int offset = sign_extend(ci);

                if (0 != (context.getConstantRegs() & (1 << base))) {
                    context.emitReadMem32(il, context.getRegValue(base) + offset, false);
                } else {
                    context.emitReadMem32(il, base, offset, false);
                }
                if (rt != 0) {
                    context.emitSetReg(il, rt);
                } else {
                    il.append(new POP());
                }
            }
        };
        CPUInstruction i_lwc1 = new CPUInstruction("lwc1", R3000InstructionSet.class, 0, CPUInstruction.FLAG_MAY_SIGNAL_EXCEPTION);
        CPUInstruction i_lwc3 = new CPUInstruction("lwc3", R3000InstructionSet.class, 0, CPUInstruction.FLAG_MAY_SIGNAL_EXCEPTION);
        CPUInstruction i_lwl = new CPUInstruction("lwl", R3000InstructionSet.class, 0, CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_READS_RT | CPUInstruction.FLAG_WRITES_RT | CPUInstruction.FLAG_MEM32) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int base = bits_rs(ci);
                int rt = bits_rt(ci);
                int offset = sign_extend(ci);

                ConstantPoolGen cp = context.getConstantPoolGen();

                // regs[rt]=(regs[rt]&lwl_mask[addr&3])|(value<<lwl_shift[addr&3]);
                if (0 != (context.getConstantRegs() & (1 << base))) {
                    int addr = context.getRegValue(base) + offset;
                    int mask = lwl_mask[addr & 3];
                    int shift = lwl_shift[addr & 3];

                    // regs[rt]&mask;
                    if (0 != (context.getConstantRegs() & (1 << rt))) {
                        int val = context.getRegValue(rt);
                        if (mask != 0) {
                            il.append(new PUSH(cp, val & mask));
                        }
                    } else {
                        if (mask != 0) {
                            context.emitGetReg(il, rt);
                            il.append(new PUSH(cp, mask));
                            il.append(new IAND());
                        }
                    }

                    // value<<shift
                    context.emitReadMem32(il, addr, true);
                    if (0 != shift) {
                        il.append(new PUSH(cp, shift));
                        il.append(new ISHL());
                    }
                    if (0 != mask) {
                        il.append(new IOR());
                    }
                } else {
                    // low2x8 = ((regs[base]+offset)&3) << 8
                    int low2x8 = context.getTempLocal(0);
                    context.emitGetReg(il, base);
                    if (offset != 0) {
                        il.append(new PUSH(cp, offset));
                        il.append(new IADD());
                    }
                    il.append(new PUSH(cp, 3));
                    il.append(new IAND());
                    il.append(new PUSH(cp, 3));
                    il.append(new ISHL());
                    il.append(new ISTORE(low2x8));

                    // private final static int[] lwl_mask = new int[] { 0x00ffffff, 0x0000ffff, 0x000000ff, 0x00000000};
                    // private final static int[] lwl_shift = new int[] { 24, 16, 8, 0};

                    // mask = 0x00ffffff >> low2x8
                    // shift = 24 - low2x8;

                    // regs[rt]
                    if (0 != (context.getConstantRegs() & (1 << rt))) {
                        il.append(new PUSH(cp, context.getRegValue(rt)));
                    } else {
                        context.emitGetReg(il, rt);
                    }

                    // mask
                    il.append(new PUSH(cp, 0x00ffffff));
                    il.append(new ILOAD(low2x8));
                    il.append(new ISHR());

                    // &
                    il.append(new IAND());

                    // value
                    context.emitReadMem32(il, base, offset, true);

                    // shift
                    il.append(new PUSH(cp, 24));
                    il.append(new ILOAD(low2x8));
                    il.append(new ISUB());

                    // <<
                    il.append(new ISHL());

                    // |
                    il.append(new IOR());
                }
                if (rt != 0) {
                    context.emitSetReg(il, rt);
                } else {
                    il.append(new POP());
                }
            }
        };
        CPUInstruction i_lwr = new CPUInstruction("lwr", R3000InstructionSet.class, 0, CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_READS_RT | CPUInstruction.FLAG_WRITES_RT | CPUInstruction.FLAG_MEM32) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int base = bits_rs(ci);
                int rt = bits_rt(ci);
                int offset = sign_extend(ci);

                ConstantPoolGen cp = context.getConstantPoolGen();

                // regs[rt]=(regs[rt]&lwr_mask[addr&3])|((value>>lwr_shift[addr&3])&~lwr_mask[addr&3]);

                if (0 != (context.getConstantRegs() & (1 << base))) {
                    // constant address
                    int addr = context.getRegValue(base) + offset;
                    int mask = lwr_mask[addr & 3];
                    int shift = lwr_shift[addr & 3];

                    // regs[rt]&mask;
                    if (0 != (context.getConstantRegs() & (1 << rt))) {
                        int val = context.getRegValue(rt);
                        if (mask != 0) {
                            il.append(new PUSH(cp, val & mask));
                        }
                    } else {
                        if (mask != 0) {
                            context.emitGetReg(il, rt);
                            il.append(new PUSH(cp, mask));
                            il.append(new IAND());
                        }
                    }

                    // value>>shift
                    context.emitReadMem32(il, addr, true);
                    if (0 != shift) {
                        il.append(new PUSH(cp, shift));
                        il.append(new ISHR());
                    }

                    if (0 != mask) {
                        int nmask = ~mask;
                        il.append(new PUSH(cp, nmask));
                        il.append(new IAND());
                        il.append(new IOR());
                    }
                } else {
                    // low2x8 = ((regs[base]+offset)&3) << 8
                    int low2x8 = context.getTempLocal(0);
                    int mask = context.getTempLocal(1);

                    context.emitGetReg(il, base);
                    if (offset != 0) {
                        il.append(new PUSH(cp, offset));
                        il.append(new IADD());
                    }
                    il.append(new PUSH(cp, 3));
                    il.append(new IAND());
                    il.append(new PUSH(cp, 3));
                    il.append(new ISHL());
                    il.append(new ISTORE(low2x8));

                    // private final static int[] lwr_mask = new int[] { 0x00000000, 0xff000000, 0xffff0000, 0xffffff00};
                    // private final static int[] lwr_shift = new int[] { 0, 8, 16, 24};

                    // mask = 0xffffff00 << (24 - low2x8);
                    // shift = low2x8

                    // regs[rt]&mask
                    if (0 != (context.getConstantRegs() & (1 << rt))) {
                        il.append(new PUSH(cp, context.getRegValue(rt)));
                    } else {
                        context.emitGetReg(il, rt);
                    }

                    if (false) {
                        // for some reason this causes a MASSIVE slowdown, but I can't figure
                        // out why; the hotspot compiler produces identical code!!!... would be good
                        // to know so we don't hit similar things elsewhere.
                        il.append(new PUSH(cp, 0xffffff00));
                        il.append(new PUSH(cp, 24));
                        il.append(new ILOAD(low2x8));
                        il.append(new ISUB());
                        il.append(new ISHL());
                        il.append(new IAND());
                    } else {
                        il.append(new PUSH(cp, 24));
                        il.append(new ILOAD(low2x8));
                        il.append(new ISUB());
                        il.append(new PUSH(cp, 0xffffff00));
                        il.append(new SWAP());
                        il.append(new ISHL());
                        il.append(new ISTORE(mask));
                        il.append(new ILOAD(mask));
                        il.append(new IAND());
                    }

                    // value>>shift;
                    context.emitReadMem32(il, base, offset, true);
                    il.append(new ILOAD(low2x8));
                    il.append(new ISHR());

                    // &~mask
                    il.append(new ILOAD(mask));
                    il.append(new PUSH(cp, -1));
                    il.append(new IXOR());
                    il.append(new IAND());

                    il.append(new IOR());
                }
                if (rt != 0) {
                    context.emitSetReg(il, rt);
                } else {
                    il.append(new POP());
                }
            }
        };
        CPUInstruction i_mfhi = new CPUInstruction("mfhi", R3000InstructionSet.class, 0, CPUInstruction.FLAG_WRITES_RD) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                //int rs = bits_rs( ci );
                //int rt = bits_rt( ci );
                int rd = bits_rd(ci);
                if (rd != 0) {
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(R3000_CLASS, "reg_hi", "I")));
                    context.emitSetReg(il, rd);
                }

            }
        };
        CPUInstruction i_mflo = new CPUInstruction("mflo", R3000InstructionSet.class, 0, CPUInstruction.FLAG_WRITES_RD) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                //int rs = bits_rs( ci );
                //int rt = bits_rt( ci );
                int rd = bits_rd(ci);
                if (rd != 0) {
                    il.append(new GETSTATIC(context.getConstantPoolGen().addFieldref(R3000_CLASS, "reg_lo", "I")));
                    context.emitSetReg(il, rd);
                }

            }
        };
        CPUInstruction i_mthi = new CPUInstruction("mthi", R3000InstructionSet.class, 0, CPUInstruction.FLAG_READS_RS);
        CPUInstruction i_mtlo = new CPUInstruction("mtlo", R3000InstructionSet.class, 0, CPUInstruction.FLAG_READS_RS);
        CPUInstruction i_mult = new CPUInstruction("mult", R3000InstructionSet.class, 0, CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_READS_RT) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int rs = bits_rs(ci);
                int rt = bits_rt(ci);
                ConstantPoolGen cp = context.getConstantPoolGen();
                // long result = ((long)regs[rs])*((long)regs[rt]);

                if (0 != (context.getConstantRegs() & (1 << rs))) {
                    il.append(new PUSH(cp, (long) context.getRegValue(rs)));
                } else {
                    context.emitGetReg(il, rs);
                    il.append(new I2L());
                }
                if (0 != (context.getConstantRegs() & (1 << rt))) {
                    il.append(new PUSH(cp, (long) context.getRegValue(rt)));
                } else {
                    context.emitGetReg(il, rt);
                    il.append(new I2L());
                }
                il.append(new LMUL());
                il.append(new DUP2());
                il.append(new PUSH(cp, 32));
                il.append(new LSHR());
                il.append(new L2I());
                il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(R3000_CLASS, "reg_hi", "I")));
                il.append(new L2I());
                il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(R3000_CLASS, "reg_lo", "I")));
            }
        };
        CPUInstruction i_multu = new CPUInstruction("multu", R3000InstructionSet.class, 0, CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_READS_RT) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int rs = bits_rs(ci);
                int rt = bits_rt(ci);
                ConstantPoolGen cp = context.getConstantPoolGen();

                // long result = (longFromUnsigned(regs[rs]))*(longFromUnsigned(regs[rt]));
                if (0 != (context.getConstantRegs() & (1 << rs))) {
                    il.append(new PUSH(cp, longFromUnsigned(context.getRegValue(rs))));
                } else {
                    context.emitGetReg(il, rs);
                    emitLongFromUnsigned(cp, il);
                }
                if (0 != (context.getConstantRegs() & (1 << rt))) {
                    il.append(new PUSH(cp, longFromUnsigned(context.getRegValue(rt))));
                } else {
                    context.emitGetReg(il, rt);
                    emitLongFromUnsigned(cp, il);
                }
                il.append(new LMUL());
                il.append(new DUP2());
                il.append(new PUSH(cp, 32));
                il.append(new LSHR());
                il.append(new L2I());
                il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(R3000_CLASS, "reg_hi", "I")));
                il.append(new L2I());
                il.append(new PUTSTATIC(context.getConstantPoolGen().addFieldref(R3000_CLASS, "reg_lo", "I")));
            }
        };
        CPUInstruction i_nor = new CPUInstruction("nor", R3000InstructionSet.class, 0, CPUInstruction.FLAG_SIMULATABLE | CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_READS_RT | CPUInstruction.FLAG_WRITES_RD) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int rs = bits_rs(ci);
                int rt = bits_rt(ci);
                int rd = bits_rd(ci);
                ConstantPoolGen cp = context.getConstantPoolGen();

                if (rd != 0) {
                    if (0 != (context.getConstantRegs() & (1 << rs))) {
                        if (0 != (context.getConstantRegs() & (1 << rt))) {
                            assert false;
                            int val = (context.getRegValue(rs) | context.getRegValue(rt)) ^ 0xffffffff;
                            il.append(new PUSH(cp, val));
                            context.emitSetReg(il, rd);
                        } else {
                            context.emitGetReg(il, rt);
                            int val = context.getRegValue(rs);
                            if (val != 0) {
                                il.append(new PUSH(cp, val));
                                il.append(new IOR());
                            }
                            il.append(new PUSH(cp, 0xffffffff));
                            il.append(new IXOR());
                            context.emitSetReg(il, rd);
                        }
                    } else {
                        if (0 != (context.getConstantRegs() & (1 << rt))) {
                            context.emitGetReg(il, rs);
                            int val = context.getRegValue(rt);
                            if (val != 0) {
                                il.append(new PUSH(cp, val));
                                il.append(new IOR());
                            }
                            il.append(new PUSH(cp, 0xffffffff));
                            il.append(new IXOR());
                            context.emitSetReg(il, rd);
                        } else {
                            context.emitGetReg(il, rs);
                            context.emitGetReg(il, rt);
                            il.append(new IOR());
                            il.append(new PUSH(cp, 0xffffffff));
                            il.append(new IXOR());
                            context.emitSetReg(il, rd);
                        }
                    }
                }
            }

            public boolean simulate(int ci, int[] regs) {
                int rs = bits_rs(ci);
                int rt = bits_rt(ci);
                int rd = bits_rd(ci);
                if (rd != 0) {
                    regs[rd] = (regs[rs] | regs[rt]) ^ 0xffffffff;
                }
                return true;
            }
        };
        CPUInstruction i_or = new CPUInstruction("or", R3000InstructionSet.class, 0, CPUInstruction.FLAG_SIMULATABLE | CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_READS_RT | CPUInstruction.FLAG_WRITES_RD) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int rs = bits_rs(ci);
                int rt = bits_rt(ci);
                int rd = bits_rd(ci);
                ConstantPoolGen cp = context.getConstantPoolGen();

                if (rd != 0) {
                    if (0 != (context.getConstantRegs() & (1 << rs))) {
                        if (0 != (context.getConstantRegs() & (1 << rt))) {
                            assert false;
                            int val = context.getRegValue(rs) | context.getRegValue(rt);
                            il.append(new PUSH(cp, val));
                            context.emitSetReg(il, rd);
                        } else {
                            context.emitGetReg(il, rt);
                            int val = context.getRegValue(rs);
                            if (val != 0) {
                                il.append(new PUSH(cp, val));
                                il.append(new IOR());
                            }
                            context.emitSetReg(il, rd);
                        }
                    } else {
                        if (0 != (context.getConstantRegs() & (1 << rt))) {
                            context.emitGetReg(il, rs);
                            int val = context.getRegValue(rt);
                            if (val != 0) {
                                il.append(new PUSH(cp, val));
                                il.append(new IOR());
                            }
                            context.emitSetReg(il, rd);
                        } else {
                            context.emitGetReg(il, rs);
                            context.emitGetReg(il, rt);
                            il.append(new IOR());
                            context.emitSetReg(il, rd);
                        }
                    }
                }
            }

            public boolean simulate(int ci, int[] regs) {
                int rs = bits_rs(ci);
                int rt = bits_rt(ci);
                int rd = bits_rd(ci);
                if (rd != 0) {
                    regs[rd] = regs[rs] | regs[rt];
                }
                return true;
            }
        };
        CPUInstruction i_ori = new CPUInstruction("ori", R3000InstructionSet.class, 0, CPUInstruction.FLAG_SIMULATABLE | CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_WRITES_RT) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int rs = bits_rs(ci);
                int rt = bits_rt(ci);
                int imm = lo(ci);
                ConstantPoolGen cp = context.getConstantPoolGen();

                if (rt != 0) {
                    if (0 != (context.getConstantRegs() & (1 << rs))) {
                        int val = context.getRegValue(rs) | imm;
                        il.append(new PUSH(cp, val));
                        context.emitSetReg(il, rt);
                    } else {
                        context.emitGetReg(il, rs);
                        if (imm != 0) {
                            il.append(new PUSH(cp, imm));
                            il.append(new IOR());
                        }
                        context.emitSetReg(il, rt);
                    }
                }
            }

            public boolean simulate(int ci, int[] regs) {
                int rs = bits_rs(ci);
                int rt = bits_rt(ci);
                int imm = lo(ci);
                if (rt != 0) {
                    regs[rt] = regs[rs] | imm;
                }
                return true;
            }
        };
        CPUInstruction i_sb = new CPUInstruction("sb", R3000InstructionSet.class, 0, CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_READS_RT | CPUInstruction.FLAG_MEM16) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int base = bits_rs(ci);
                int rt = bits_rt(ci);
                int offset = sign_extend(ci);

                InstructionList il2 = new InstructionList();
                if (0 != (context.getConstantRegs() & (1 << rt))) {
                    ConstantPoolGen cp = context.getConstantPoolGen();
                    il2.append(new PUSH(cp, context.getRegValue(rt)));
                } else {
                    context.emitGetReg(il2, rt);
                }
                if (0 != (context.getConstantRegs() & (1 << base))) {
                    context.emitWriteMem8(il, context.getRegValue(base) + offset, il2);
                } else {
                    il.append(il2);
                    context.emitWriteMem8(il, base, offset);
                }
                il2.dispose();
            }
        };

        CPUInstruction i_sh = new CPUInstruction("sh", R3000InstructionSet.class, 0, CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_READS_RT | CPUInstruction.FLAG_MEM8) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int base = bits_rs(ci);
                int rt = bits_rt(ci);
                int offset = sign_extend(ci);

                InstructionList il2 = new InstructionList();
                if (0 != (context.getConstantRegs() & (1 << rt))) {
                    ConstantPoolGen cp = context.getConstantPoolGen();
                    il2.append(new PUSH(cp, context.getRegValue(rt)));
                } else {
                    context.emitGetReg(il2, rt);
                }
                if (0 != (context.getConstantRegs() & (1 << base))) {
                    context.emitWriteMem16(il, context.getRegValue(base) + offset, il2);
                } else {
                    il.append(il2);
                    context.emitWriteMem16(il, base, offset);
                }
                il2.dispose();
            }
        };
        CPUInstruction i_sll = new CPUInstruction("sll", R3000InstructionSet.class, 0, CPUInstruction.FLAG_SIMULATABLE | CPUInstruction.FLAG_READS_RT | CPUInstruction.FLAG_WRITES_RD) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int rd = bits_rd(ci);
                int rt = bits_rt(ci);
                int sa = bits_sa(ci);
                if (rd != 0) {
                    ConstantPoolGen cp = context.getConstantPoolGen();
                    if (0 != (context.getConstantRegs() & (1 << rt))) {
                        assert false;
                        int val = context.getRegValue(rt) << sa;
                        il.append(new PUSH(cp, val));
                        context.emitSetReg(il, rd);
                    } else {
                        context.emitGetReg(il, rt);
                        il.append(new PUSH(cp, sa));
                        il.append(new ISHL());
                        context.emitSetReg(il, rd);
                    }
                }
            }

            public boolean simulate(int ci, int[] regs) {
                int rd = bits_rd(ci);
                int rt = bits_rt(ci);
                int sa = bits_sa(ci);
                if (rd != 0) {
                    regs[rd] = regs[rt] << sa;
                }
                return true;
            }
        };
        CPUInstruction i_sllv = new CPUInstruction("sllv", R3000InstructionSet.class, 0, CPUInstruction.FLAG_SIMULATABLE | CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_READS_RT | CPUInstruction.FLAG_WRITES_RD) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int rd = bits_rd(ci);
                int rt = bits_rt(ci);
                int rs = bits_rs(ci);
                if (rd != 0) {
                    ConstantPoolGen cp = context.getConstantPoolGen();
                    if (0 != (context.getConstantRegs() & (1 << rt))) {
                        if (0 != (context.getConstantRegs() & (1 << rs))) {
                            assert false;
                            int val = context.getRegValue(rt) << (context.getRegValue(rs) & 31);
                            il.append(new PUSH(cp, val));
                            context.emitSetReg(il, rd);
                            return;
                        } else {
                            il.append(new PUSH(cp, context.getRegValue(rt)));
                            context.emitGetReg(il, rs);
                            il.append(new PUSH(cp, 31));
                            il.append(new IAND());
                        }
                    } else {
                        if (0 != (context.getConstantRegs() & (1 << rs))) {
                            context.emitGetReg(il, rt);
                            il.append(new PUSH(cp, context.getRegValue(rs) & 31));
                        } else {
                            context.emitGetReg(il, rt);
                            context.emitGetReg(il, rs);
                            il.append(new PUSH(cp, 31));
                            il.append(new IAND());
                        }
                    }
                    il.append(new ISHL());
                    context.emitSetReg(il, rd);
                }
            }

            public boolean simulate(int ci, int[] regs) {
                int rd = bits_rd(ci);
                int rt = bits_rt(ci);
                int rs = bits_rs(ci);
                if (rd != 0) {
                    regs[rd] = regs[rt] << (regs[rs] & 31);
                }
                return true;
            }
        };
        CPUInstruction i_slt = new CPUInstruction("slt", R3000InstructionSet.class, 0, CPUInstruction.FLAG_SIMULATABLE | CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_READS_RT | CPUInstruction.FLAG_WRITES_RD) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int rs = bits_rs(ci);
                int rt = bits_rt(ci);
                int rd = bits_rd(ci);
                ConstantPoolGen cp = context.getConstantPoolGen();
                // regs[rd]=(regs[rs]<regs[rt])?1:0;

                if (rd != 0) {
                    if (0 != (context.getConstantRegs() & (1 << rs))) {
                        if (0 != (context.getConstantRegs() & (1 << rt))) {
                            assert false;
                            int val = (context.getRegValue(rs) < context.getRegValue(rt)) ? 1 : 0;
                            il.append(new PUSH(cp, val));
                            context.emitSetReg(il, rd);
                            // don't need to do compare, so exit
                            return;
                        } else {
                            il.append(new PUSH(cp, context.getRegValue(rs)));
                            context.emitGetReg(il, rt);
                        }
                    } else {
                        if (0 != (context.getConstantRegs() & (1 << rt))) {
                            context.emitGetReg(il, rs);
                            il.append(new PUSH(cp, context.getRegValue(rt)));
                        } else {
                            context.emitGetReg(il, rs);
                            context.emitGetReg(il, rt);
                        }
                    }
                    IF_ICMPLT lt = new IF_ICMPLT(null);
                    il.append(lt);
                    il.append(new ICONST(0));
                    GOTO gt = new GOTO(null);
                    il.append(gt);
                    lt.setTarget(il.append(new ICONST(1)));
                    context.emitSetReg(il, rd);
                    // the goto is whatever got emitted after the ICONST(1);
                    gt.setTarget(lt.getTarget().getNext());
                }
            }

            public boolean simulate(int ci, int[] regs) {
                int rs = bits_rs(ci);
                int rt = bits_rt(ci);
                int rd = bits_rd(ci);
                if (rd != 0) {
                    regs[rd] = (regs[rs] < regs[rt]) ? 1 : 0;
                }
                return true;
            }
        };
        CPUInstruction i_slti = new CPUInstruction("slti", R3000InstructionSet.class, 0, CPUInstruction.FLAG_SIMULATABLE | CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_WRITES_RT) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int rs = bits_rs(ci);
                int rt = bits_rt(ci);
                int imm = sign_extend(ci);
                ConstantPoolGen cp = context.getConstantPoolGen();
                // regs[rd]=(regs[rs]<imm)?1:0;

                if (rt != 0) {
                    if (0 != (context.getConstantRegs() & (1 << rs))) {
                        assert false;
                        int val = (context.getRegValue(rs) < imm) ? 1 : 0;
                        il.append(new PUSH(cp, val));
                        context.emitSetReg(il, rt);
                    } else {
                        context.emitGetReg(il, rs);
                        il.append(new PUSH(cp, imm));
                        IF_ICMPLT lt = new IF_ICMPLT(null);
                        il.append(lt);
                        il.append(new ICONST(0));
                        GOTO gt = new GOTO(null);
                        il.append(gt);
                        lt.setTarget(il.append(new ICONST(1)));
                        context.emitSetReg(il, rt);
                        // the goto is whatever got emitted after the ICONST(1);
                        gt.setTarget(lt.getTarget().getNext());
                    }
                }
            }

            public boolean simulate(int ci, int[] regs) {
                int rs = bits_rs(ci);
                int rt = bits_rt(ci);
                int imm = sign_extend(ci);
                if (rt != 0) {
                    regs[rt] = (regs[rs] < imm) ? 1 : 0;
                }
                return true;
            }
        };
        CPUInstruction i_sltiu = new CPUInstruction("sltiu", R3000InstructionSet.class, 0, CPUInstruction.FLAG_SIMULATABLE | CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_WRITES_RT) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int rs = bits_rs(ci);
                int rt = bits_rt(ci);
                long imm = lo(ci);
                ConstantPoolGen cp = context.getConstantPoolGen();
                //regs[rt] = (longFromUnsigned(regs[rs])<(long)imm);

                if (rt != 0) {
                    if (0 != (context.getConstantRegs() & (1 << rs))) {
                        assert false;
                        int val = (R3000.Util.longFromUnsigned(context.getRegValue(rs)) < imm) ? 1 : 0;
                        il.append(new PUSH(cp, val));
                        context.emitSetReg(il, rt);
                    } else {
                        context.emitGetReg(il, rs);
                        emitLongFromUnsigned(cp, il);
                        // stack(0) = longFromUnsigned(regs[rs]);
                        il.append(new PUSH(cp, imm));
                        // stack(1) = (long)imm;
                        il.append(new LCMP());
                        // stack(0) = -1 rs<imm
                        //            0  rs==imm
                        //            1  rs>imm
                        IFLT lt = new IFLT(null);
                        il.append(lt);
                        il.append(new ICONST(0));
                        GOTO gt = new GOTO(null);
                        il.append(gt);
                        lt.setTarget(il.append(new ICONST(1)));
                        context.emitSetReg(il, rt);
                        // the goto is whatever got emitted after the ICONST(1);
                        gt.setTarget(lt.getTarget().getNext());
                    }
                }
            }

            public boolean simulate(int ci, int[] regs) {
                int rs = bits_rs(ci);
                int rt = bits_rt(ci);
                int imm = lo(ci);
                if (rt != 0) {
                    regs[rt] = (longFromUnsigned(regs[rs]) < (long) imm) ? 1 : 0;
                }
                return true;
            }
        };
        CPUInstruction i_sltu = new CPUInstruction("sltu", R3000InstructionSet.class, 0, CPUInstruction.FLAG_SIMULATABLE | CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_READS_RT | CPUInstruction.FLAG_WRITES_RD) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int rs = bits_rs(ci);
                int rt = bits_rt(ci);
                int rd = bits_rd(ci);
                ConstantPoolGen cp = context.getConstantPoolGen();
                //regs[rt] = (longFromUnsigned(regs[rs])<longFromUnsigned(m_resg[rt]);

                if (rd != 0) {
                    if (0 != (context.getConstantRegs() & (1 << rs))) {
                        if (0 != (context.getConstantRegs() & (1 << rt))) {
                            assert false;
                            int val = (R3000.Util.longFromUnsigned(context.getRegValue(rs)) < R3000.Util.longFromUnsigned(context.getRegValue(rt))) ? 1 : 0;
                            il.append(new PUSH(cp, val));
                            context.emitSetReg(il, rd);
                            // don't need to do compare, so exit
                            return;
                        } else {
                            il.append(new PUSH(cp, R3000.Util.longFromUnsigned(context.getRegValue(rs))));
                            context.emitGetReg(il, rt);
                            emitLongFromUnsigned(cp, il);
                        }
                    } else {
                        if (0 != (context.getConstantRegs() & (1 << rt))) {
                            context.emitGetReg(il, rs);
                            emitLongFromUnsigned(cp, il);
                            il.append(new PUSH(cp, R3000.Util.longFromUnsigned(context.getRegValue(rt))));
                        } else {
                            context.emitGetReg(il, rs);
                            emitLongFromUnsigned(cp, il);
                            context.emitGetReg(il, rt);
                            emitLongFromUnsigned(cp, il);
                        }
                    }
                    il.append(new LCMP());
                    // stack(0) = -1 rs<rt
                    //            0  rs==rt
                    //            1  rs>rt
                    IFLT lt = new IFLT(null);
                    il.append(lt);
                    il.append(new ICONST(0));
                    GOTO gt = new GOTO(null);
                    il.append(gt);
                    lt.setTarget(il.append(new ICONST(1)));
                    context.emitSetReg(il, rd);
                    // the goto is whatever got emitted after the ICONST(1);
                    gt.setTarget(lt.getTarget().getNext());

                }
            }

            public boolean simulate(int ci, int[] regs) {
                int rs = bits_rs(ci);
                int rt = bits_rt(ci);
                int rd = bits_rd(ci);
                if (rd != 0) {
                    regs[rd] = (longFromUnsigned(regs[rs]) < longFromUnsigned(regs[rt])) ? 1 : 0;
                }
                return true;
            }
        };
        CPUInstruction i_sra = new CPUInstruction("sra", R3000InstructionSet.class, 0, CPUInstruction.FLAG_SIMULATABLE | CPUInstruction.FLAG_READS_RT | CPUInstruction.FLAG_WRITES_RD) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int rd = bits_rd(ci);
                int rt = bits_rt(ci);
                int sa = bits_sa(ci);
                if (rd != 0) {
                    ConstantPoolGen cp = context.getConstantPoolGen();
                    if (0 != (context.getConstantRegs() & (1 << rt))) {
                        assert false;
                        int val = context.getRegValue(rt) >> sa;
                        il.append(new PUSH(cp, val));
                        context.emitSetReg(il, rd);
                    } else {
                        context.emitGetReg(il, rt);
                        il.append(new PUSH(cp, sa));
                        il.append(new ISHR());
                        context.emitSetReg(il, rd);
                    }
                }
            }

            public boolean simulate(int ci, int[] regs) {
                int sa = bits_sa(ci);
                int rt = bits_rt(ci);
                int rd = bits_rd(ci);
                if (rd != 0) {
                    regs[rd] = regs[rt] >> sa;
                }
                return true;
            }
        };
        CPUInstruction i_srav = new CPUInstruction("srav", R3000InstructionSet.class, 0, CPUInstruction.FLAG_SIMULATABLE | CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_READS_RT | CPUInstruction.FLAG_WRITES_RD) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int rd = bits_rd(ci);
                int rt = bits_rt(ci);
                int rs = bits_rs(ci);
                if (rd != 0) {
                    ConstantPoolGen cp = context.getConstantPoolGen();
                    if (0 != (context.getConstantRegs() & (1 << rt))) {
                        if (0 != (context.getConstantRegs() & (1 << rs))) {
                            assert false;
                            int val = context.getRegValue(rt) >> (context.getRegValue(rs) & 31);
                            il.append(new PUSH(cp, val));
                            context.emitSetReg(il, rd);
                            return;
                        } else {
                            il.append(new PUSH(cp, context.getRegValue(rt)));
                            context.emitGetReg(il, rs);
                            il.append(new PUSH(cp, 31));
                            il.append(new IAND());
                        }
                    } else {
                        if (0 != (context.getConstantRegs() & (1 << rs))) {
                            context.emitGetReg(il, rt);
                            il.append(new PUSH(cp, context.getRegValue(rs) & 31));
                        } else {
                            context.emitGetReg(il, rt);
                            context.emitGetReg(il, rs);
                            il.append(new PUSH(cp, 31));
                            il.append(new IAND());
                        }
                    }
                    il.append(new ISHR());
                    context.emitSetReg(il, rd);
                }
            }

            public boolean simulate(int ci, int[] regs) {
                int rs = bits_rs(ci);
                int rt = bits_rt(ci);
                int rd = bits_rd(ci);
                if (rd != 0) {
                    regs[rd] = regs[rt] >> regs[rs];
                }
                return true;
            }
        };
        CPUInstruction i_srl = new CPUInstruction("srl", R3000InstructionSet.class, 0, CPUInstruction.FLAG_SIMULATABLE | CPUInstruction.FLAG_READS_RT | CPUInstruction.FLAG_WRITES_RD) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int rd = bits_rd(ci);
                int rt = bits_rt(ci);
                int sa = bits_sa(ci);
                // regs[rd] = (int)((longFromUnsigned(regs[rt]))>>sa);
                if (rd != 0) {
                    ConstantPoolGen cp = context.getConstantPoolGen();
                    if (0 != (context.getConstantRegs() & (1 << rt))) {
                        assert false;
                        int val = (int) (longFromUnsigned(context.getRegValue(rt)) >> sa);
                        il.append(new PUSH(cp, val));
                        context.emitSetReg(il, rd);
                    } else {
                        context.emitGetReg(il, rt);
                        emitLongFromUnsigned(cp, il);
                        il.append(new PUSH(cp, sa));
                        il.append(new LSHR());
                        il.append(new L2I());
                        context.emitSetReg(il, rd);
                    }
                }
            }

            public boolean simulate(int ci, int[] regs) {
                int sa = bits_sa(ci);
                int rt = bits_rt(ci);
                int rd = bits_rd(ci);
                if (rd != 0) {
                    regs[rd] = (int) ((longFromUnsigned(regs[rt])) >> sa);
                }
                return true;
            }
        };
        CPUInstruction i_srlv = new CPUInstruction("srlv", R3000InstructionSet.class, 0, CPUInstruction.FLAG_SIMULATABLE | CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_READS_RT | CPUInstruction.FLAG_WRITES_RD) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int rd = bits_rd(ci);
                int rt = bits_rt(ci);
                int rs = bits_rs(ci);
                if (rd != 0) {
                    ConstantPoolGen cp = context.getConstantPoolGen();
                    if (0 != (context.getConstantRegs() & (1 << rt))) {
                        if (0 != (context.getConstantRegs() & (1 << rs))) {
                            assert false;
                            int val = (int) longFromUnsigned(context.getRegValue(rt)) >> (context.getRegValue(rs) & 31);
                            il.append(new PUSH(cp, val));
                            context.emitSetReg(il, rd);
                            return;
                        } else {
                            il.append(new PUSH(cp, longFromUnsigned(context.getRegValue(rt))));
                            context.emitGetReg(il, rs);
                            il.append(new PUSH(cp, 31));
                            il.append(new IAND());
                        }
                    } else {
                        if (0 != (context.getConstantRegs() & (1 << rs))) {
                            context.emitGetReg(il, rt);
                            emitLongFromUnsigned(cp, il);
                            il.append(new PUSH(cp, context.getRegValue(rs) & 31));
                        } else {
                            context.emitGetReg(il, rt);
                            emitLongFromUnsigned(cp, il);
                            context.emitGetReg(il, rs);
                            il.append(new PUSH(cp, 31));
                            il.append(new IAND());
                        }
                    }
                    il.append(new LSHR());
                    il.append(new L2I());
                    context.emitSetReg(il, rd);
                }
            }

            public boolean simulate(int ci, int[] regs) {
                int rs = bits_rs(ci);
                int rt = bits_rt(ci);
                int rd = bits_rd(ci);
                if (rd != 0) {
                    regs[rd] = (int) ((longFromUnsigned(regs[rt])) >> regs[rs]);
                }
                return true;
            }
        };
        CPUInstruction i_sub;
        if (ignoreArithmeticOverflow) {
            i_sub = new CPUInstruction("sub", R3000InstructionSet.class, 0, CPUInstruction.FLAG_SIMULATABLE | CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_READS_RT | CPUInstruction.FLAG_WRITES_RD) {
                public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                    int rs = bits_rs(ci);
                    int rt = bits_rt(ci);
                    int rd = bits_rd(ci);
                    ConstantPoolGen cp = context.getConstantPoolGen();

                    if (rd != 0) {
                        if (0 != (context.getConstantRegs() & (1 << rs))) {
                            if (0 != (context.getConstantRegs() & (1 << rt))) {
                                assert false;
                                int val = context.getRegValue(rs) - context.getRegValue(rt);
                                il.append(new PUSH(cp, val));
                                context.emitSetReg(il, rd);
                            } else {
                                il.append(new PUSH(cp, context.getRegValue(rs)));
                                context.emitGetReg(il, rt);
                                il.append(new ISUB());
                                context.emitSetReg(il, rd);
                            }
                        } else {
                            if (0 != (context.getConstantRegs() & (1 << rt))) {
                                context.emitGetReg(il, rs);
                                int val = context.getRegValue(rt);
                                if (val != 0) {
                                    il.append(new PUSH(cp, val));
                                    il.append(new ISUB());
                                }
                                context.emitSetReg(il, rd);
                            } else {
                                context.emitGetReg(il, rs);
                                context.emitGetReg(il, rt);
                                il.append(new ISUB());
                                context.emitSetReg(il, rd);
                            }
                        }
                    }
                }

                public boolean simulate(int ci, int[] regs) {
                    int rs = bits_rs(ci);
                    int rt = bits_rt(ci);
                    int rd = bits_rd(ci);

                    if (rd != 0) {
                        regs[rd] = regs[rs] - regs[rt];
                    }
                    return true;
                }
            };
        } else {
            i_sub = new CPUInstruction("sub", R3000InstructionSet.class, 0, CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_READS_RT | CPUInstruction.FLAG_WRITES_RD | CPUInstruction.FLAG_MAY_SIGNAL_EXCEPTION);
        }
        CPUInstruction i_subu = new CPUInstruction("subu", R3000InstructionSet.class, 0, CPUInstruction.FLAG_SIMULATABLE | CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_READS_RT | CPUInstruction.FLAG_WRITES_RD) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int rs = bits_rs(ci);
                int rt = bits_rt(ci);
                int rd = bits_rd(ci);
                ConstantPoolGen cp = context.getConstantPoolGen();

                if (rd != 0) {
                    if (0 != (context.getConstantRegs() & (1 << rs))) {
                        if (0 != (context.getConstantRegs() & (1 << rt))) {
                            assert false;
                            int val = context.getRegValue(rs) - context.getRegValue(rt);
                            il.append(new PUSH(cp, val));
                            context.emitSetReg(il, rd);
                        } else {
                            il.append(new PUSH(cp, context.getRegValue(rs)));
                            context.emitGetReg(il, rt);
                            il.append(new ISUB());
                            context.emitSetReg(il, rd);
                        }
                    } else {
                        if (0 != (context.getConstantRegs() & (1 << rt))) {
                            context.emitGetReg(il, rs);
                            int val = context.getRegValue(rt);
                            if (val != 0) {
                                il.append(new PUSH(cp, val));
                                il.append(new ISUB());
                            }
                            context.emitSetReg(il, rd);
                        } else {
                            context.emitGetReg(il, rs);
                            context.emitGetReg(il, rt);
                            il.append(new ISUB());
                            context.emitSetReg(il, rd);
                        }
                    }
                }
            }

            public boolean simulate(int ci, int[] regs) {
                int rs = bits_rs(ci);
                int rt = bits_rt(ci);
                int rd = bits_rd(ci);

                if (rd != 0) {
                    regs[rd] = regs[rs] - regs[rt];
                }
                return true;
            }
        };
        CPUInstruction i_sw = new CPUInstruction("sw", R3000InstructionSet.class, 0, CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_READS_RT | CPUInstruction.FLAG_MEM32) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int base = bits_rs(ci);
                int rt = bits_rt(ci);
                int offset = sign_extend(ci);

                InstructionList il2 = new InstructionList();

                if (0 != (context.getConstantRegs() & (1 << rt))) {
                    ConstantPoolGen cp = context.getConstantPoolGen();
                    il2.append(new PUSH(cp, context.getRegValue(rt)));
                } else {
                    context.emitGetReg(il2, rt);
                }
                if (0 != (context.getConstantRegs() & (1 << base))) {
                    context.emitWriteMem32(il, context.getRegValue(base) + offset, il2, false);
                } else {
                    context.emitWriteMem32(il, base, offset, il2, false);
                }
                il2.dispose();
            }
        };

        CPUInstruction i_swc1 = new CPUInstruction("swc1", R3000InstructionSet.class, 0, CPUInstruction.FLAG_MAY_SIGNAL_EXCEPTION);
        CPUInstruction i_swc3 = new CPUInstruction("swc3", R3000InstructionSet.class, 0, CPUInstruction.FLAG_MAY_SIGNAL_EXCEPTION);
        CPUInstruction i_swl = new CPUInstruction("swl", R3000InstructionSet.class, 0, CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_READS_RT | CPUInstruction.FLAG_MEM32) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int base = bits_rs(ci);
                int rt = bits_rt(ci);
                int offset = sign_extend(ci);

                ConstantPoolGen cp = context.getConstantPoolGen();

                InstructionList il2 = new InstructionList();
                // private static final int[] swl_mask = new int[] { 0xffffff00, 0xffff0000, 0xff000000, 0x00000000};
                // private static final int[] swl_shift = new int[] { 24, 16, 8, 0};
                // value = (value&swl_mask[addr&3])|((regs[rt]>>swl_shift[addr&3])&~swl_mask[addr&3]);

                if (0 != (context.getConstantRegs() & (1 << base))) {
                    // constant address
                    int addr = context.getRegValue(base) + offset;
                    int mask = swl_mask[addr & 3];
                    int shift = swl_shift[addr & 3];

                    // regs[rt]>>shift & ~mask;
                    if (0 != (context.getConstantRegs() & (1 << rt))) {
                        il2.append(new PUSH(cp, context.getRegValue(rt)));
                    } else {
                        context.emitGetReg(il2, rt);
                    }
                    if (0 != shift) {
                        il2.append(new PUSH(cp, shift));
                        il2.append(new ISHR());
                    }
                    if (mask != 0) {
                        il2.append(new PUSH(cp, ~mask));
                        il2.append(new IAND());
                    }

                    // value&mask
                    context.emitReadMem32(il2, addr, true);
                    il2.append(new PUSH(cp, mask));
                    il2.append(new IAND());

                    il2.append(new IOR());
                    context.emitWriteMem32(il, addr, il2, true);
                } else {
                    // low2x8 = ((regs[base]+offset)&3) << 8
                    int low2x8 = context.getTempLocal(0);
                    int maskVar = context.getTempLocal(1);
                    context.emitGetReg(il2, base);
                    if (offset != 0) {
                        il2.append(new PUSH(cp, offset));
                        il2.append(new IADD());
                    }
                    il2.append(new PUSH(cp, 3));
                    il2.append(new IAND());
                    il2.append(new PUSH(cp, 3));
                    il2.append(new ISHL());
                    il2.append(new ISTORE(low2x8));

                    // mask = 0xffffff00 << low2x8
                    // shift = 24 - low2x8;

                    il2.append(new PUSH(cp, 0xffffff00));
                    il2.append(new ILOAD(low2x8));
                    il2.append(new ISHL());
                    il2.append(new ISTORE(maskVar));

                    // regs[rt]>>shift & ~mask;
                    if (0 != (context.getConstantRegs() & (1 << rt))) {
                        il2.append(new PUSH(cp, context.getRegValue(rt)));
                    } else {
                        context.emitGetReg(il2, rt);
                    }
                    il2.append(new PUSH(cp, 24));
                    il2.append(new ILOAD(low2x8));
                    il2.append(new ISUB());
                    il2.append(new ISHR());

                    il2.append(new ILOAD(maskVar));
                    il2.append(new PUSH(cp, 0xffffffff));
                    il2.append(new IXOR());
                    il2.append(new IAND());

                    // value&mask;
                    context.emitReadMem32(il2, base, offset, true);
                    il2.append(new ILOAD(maskVar));
                    il2.append(new IAND());

                    il2.append(new IOR());
                    context.emitWriteMem32(il, base, offset, il2, true);
                }
                il2.dispose();
            }
        };
        CPUInstruction i_swr = new CPUInstruction("swr", R3000InstructionSet.class, 0, CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_READS_RT | CPUInstruction.FLAG_MEM32) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int base = bits_rs(ci);
                int rt = bits_rt(ci);
                int offset = sign_extend(ci);

                ConstantPoolGen cp = context.getConstantPoolGen();

                InstructionList il2 = new InstructionList();
                // private static final int[] swr_mask = new int[] { 0x00000000, 0x000000ff, 0x0000ffff, 0x00ffffff};
                // private static final int[] swr_shift = new int[] { 0, 8, 16, 24};
                // value = (value&swr_mask[addr&3])|(regs[rt]<<swr_shift[addr&3]);

                if (0 != (context.getConstantRegs() & (1 << base))) {
                    // constant address
                    int addr = context.getRegValue(base) + offset;
                    int mask = swl_mask[addr & 3];
                    int shift = swl_shift[addr & 3];

                    // regs[rt]<<shift;
                    if (0 != (context.getConstantRegs() & (1 << rt))) {
                        il2.append(new PUSH(cp, context.getRegValue(rt)));
                    } else {
                        context.emitGetReg(il2, rt);
                    }
                    if (0 != shift) {
                        il2.append(new PUSH(cp, shift));
                        il2.append(new ISHL());
                    }

                    // value&mask
                    context.emitReadMem32(il2, addr, true);
                    il2.append(new PUSH(cp, mask));
                    il2.append(new IAND());

                    il2.append(new IOR());
                    context.emitWriteMem32(il, addr, il2, true);
                } else {
                    // low2x8 = ((regs[base]+offset)&3) << 8
                    int low2x8 = context.getTempLocal(0);
                    context.emitGetReg(il2, base);
                    if (offset != 0) {
                        il2.append(new PUSH(cp, offset));
                        il2.append(new IADD());
                    }
                    il2.append(new PUSH(cp, 3));
                    il2.append(new IAND());
                    il2.append(new PUSH(cp, 3));
                    il2.append(new ISHL());
                    il2.append(new ISTORE(low2x8));

                    // mask = 0x00ffffff >> (24-low2x8)
                    // shift = low2x8;

                    // regs[rt]<<shift
                    if (0 != (context.getConstantRegs() & (1 << rt))) {
                        il2.append(new PUSH(cp, context.getRegValue(rt)));
                    } else {
                        context.emitGetReg(il2, rt);
                    }
                    il2.append(new ILOAD(low2x8));
                    il2.append(new ISHL());

                    // value&mask;
                    context.emitReadMem32(il2, base, offset, true);
                    il2.append(new PUSH(cp, 24));
                    il2.append(new ILOAD(low2x8));
                    il2.append(new ISUB());
                    il2.append(new PUSH(cp, 0x00ffffff));
                    il2.append(new SWAP());
                    il2.append(new ISHR());
                    il2.append(new IAND());

                    il2.append(new IOR());
                    context.emitWriteMem32(il, base, offset, il2, true);
                }
                il2.dispose();
            }
        };
        CPUInstruction i_syscall = new CPUInstruction("syscall", R3000InstructionSet.class, 0, CPUInstruction.FLAG_MAY_SIGNAL_EXCEPTION);
        CPUInstruction i_xor = new CPUInstruction("xor", R3000InstructionSet.class, 0, CPUInstruction.FLAG_SIMULATABLE | CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_READS_RT | CPUInstruction.FLAG_WRITES_RD) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int rs = bits_rs(ci);
                int rt = bits_rt(ci);
                int rd = bits_rd(ci);
                ConstantPoolGen cp = context.getConstantPoolGen();

                if (rd != 0) {
                    if (0 != (context.getConstantRegs() & (1 << rs))) {
                        if (0 != (context.getConstantRegs() & (1 << rt))) {
                            assert false;
                            int val = context.getRegValue(rs) ^ context.getRegValue(rt);
                            il.append(new PUSH(cp, val));
                            context.emitSetReg(il, rd);
                        } else {
                            context.emitGetReg(il, rt);
                            int val = context.getRegValue(rs);
                            if (val != 0) {
                                il.append(new PUSH(cp, val));
                                il.append(new IXOR());
                            }
                            context.emitSetReg(il, rd);
                        }
                    } else {
                        if (0 != (context.getConstantRegs() & (1 << rt))) {
                            context.emitGetReg(il, rs);
                            int val = context.getRegValue(rt);
                            if (val != 0) {
                                il.append(new PUSH(cp, val));
                                il.append(new IXOR());
                            }
                            context.emitSetReg(il, rd);
                        } else {
                            context.emitGetReg(il, rs);
                            context.emitGetReg(il, rt);
                            il.append(new IXOR());
                            context.emitSetReg(il, rd);
                        }
                    }
                }
            }

            public boolean simulate(int ci, int[] regs) {
                int rs = bits_rs(ci);
                int rt = bits_rt(ci);
                int rd = bits_rd(ci);
                if (rd != 0) {
                    regs[rd] = regs[rs] ^ regs[rt];
                }
                return true;
            }
        };
        CPUInstruction i_xori = new CPUInstruction("xori", R3000InstructionSet.class, 0, CPUInstruction.FLAG_SIMULATABLE | CPUInstruction.FLAG_READS_RS | CPUInstruction.FLAG_WRITES_RT) {
            public void compile(CompilationContext context, int address, int ci, InstructionList il) {
                int rs = bits_rs(ci);
                int rt = bits_rt(ci);
                int imm = lo(ci);
                ConstantPoolGen cp = context.getConstantPoolGen();

                if (rt != 0) {
                    if (0 != (context.getConstantRegs() & (1 << rs))) {
                        assert false;
                        int val = context.getRegValue(rs) ^ imm;
                        il.append(new PUSH(cp, val));
                        context.emitSetReg(il, rt);
                    } else {
                        context.emitGetReg(il, rs);
                        if (imm != 0) {
                            il.append(new PUSH(cp, imm));
                            il.append(new IXOR());
                        }
                        context.emitSetReg(il, rt);
                    }
                }
            }

            public boolean simulate(int ci, int[] regs) {
                int rs = bits_rs(ci);
                int rt = bits_rt(ci);
                int imm = lo(ci);
                if (rt != 0) {
                    regs[rt] = regs[rs] ^ imm;
                }
                return true;
            }
        };

        CPUInstruction[] decoding = new CPUInstruction[]{
                null, null, i_j, i_jal, i_beq, i_bne, i_blez, i_bgtz,
                i_addi, i_addiu, i_slti, i_sltiu, i_andi, i_ori, i_xori, i_lui,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                i_lb, i_lh, i_lwl, i_lw, i_lbu, i_lhu, i_lwr, null,
                i_sb, i_sh, i_swl, i_sw, null, null, i_swr, null,
                null, i_lwc1, null, i_lwc3, null, null, null, null,
                null, i_swc1, null, i_swc3, null, null, null, null
        };
        for (int i = 0; i < 64; i++) {
            if (null != decoding[i]) {
                registrar.setInstruction(i, decoding[i]);
            }
        }

        decoding = new CPUInstruction[]{
                i_sll, null, i_srl, i_sra, i_sllv, null, i_srlv, i_srav,
                i_jr, i_jalr, null, null, i_syscall, i_break, null, null,
                i_mfhi, i_mthi, i_mflo, i_mtlo, null, null, null, null,
                i_mult, i_multu, i_div, i_divu, null, null, null, null,
                i_add, i_addu, i_sub, i_subu, i_and, i_or, i_xor, i_nor,
                null, null, i_slt, i_sltu, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null
        };
        for (int i = 0; i < 64; i++) {
            if (null != decoding[i]) {
                registrar.setSPECIALInstruction(i, decoding[i]);
            }
        }

        decoding = new CPUInstruction[]{
                i_bltz, i_bgez, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                i_bltzal, i_bgezal, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null
        };
        for (int i = 0; i < 32; i++) {
            if (null != decoding[i]) {
                registrar.setREGIMMInstruction(i, decoding[i]);
            }
        }
    }

    private static final int bits_rs(final int ci) {
        return (ci >> 21) & 0x1f;
    }

    private static final int bits_rt(final int ci) {
        return (ci >> 16) & 0x1f;
    }

    private static final int bits_rd(final int ci) {
        return (ci >> 11) & 0x1f;
    }

    private static final int bits_sa(final int ci) {
        return (ci >> 6) & 0x1f;
    }

    private static final boolean mask_bit31(int x) {
        return (x < 0);
    }

    private static final int bits25_6(int x) {
        return (x >> 6) & 0xfffff;
    }

    private static final int sign_extend(int x) {
        return ((x) << 16) >> 16;
    }

    private static final int signed_branch_delta(int x) {
        return sign_extend(x) << 2;
    }

    private static final int lo(int x) {
        return x & 0xffff;
    }

    private static final long longFromUnsigned(int x) {
        return ((long) x) & 0xffffffffL;
    }

    public static void interpret_add(final int ci) {
        int rs = bits_rs(ci);
        int rt = bits_rt(ci);
        int rd = bits_rd(ci);

        int res = Refs.r3000Regs[rs] + Refs.r3000Regs[rt];

        // arithmetic overflow if rs and rs have same top bit (i.e. sign)
        // and the result has a different sign
        if (!ignoreArithmeticOverflow) {
            if (!mask_bit31(Refs.r3000Regs[rs] ^ Refs.r3000Regs[rt]) && mask_bit31(res ^ Refs.r3000Regs[rs])) {
                Refs.scp.signalIntegerOverflowException();
                return;
            }
        }
        if (rd != 0) {
            Refs.r3000Regs[rd] = res;
        }
    }

    public static void interpret_addi(final int ci) {
        int rs = bits_rs(ci);
        int rt = bits_rt(ci);
        int imm = sign_extend(ci);

        int res = Refs.r3000Regs[rs] + imm;

        // arithmetic overflow if rs and rs have same top bit (i.e. sign)
        // and the result has a different sign
        if (!ignoreArithmeticOverflow) {
            if (!mask_bit31(Refs.r3000Regs[rs] ^ imm) && mask_bit31(res ^ Refs.r3000Regs[rs])) {
                Refs.scp.signalIntegerOverflowException();
                return;
            }
        }
        if (rt != 0) {
            Refs.r3000Regs[rt] = res;
        }
    }

    // getstatic #reg1
    // iconst imm
    // iadd
    // putstatic #reg2

    public static void interpret_addiu(final int ci) {
        int rs = bits_rs(ci);
        int rt = bits_rt(ci);
        int imm = sign_extend(ci);

        if (rt != 0) {
            Refs.r3000Regs[rt] = Refs.r3000Regs[rs] + imm;
        }
    }

    public static void interpret_addu(final int ci) {
        int rs = bits_rs(ci);
        int rt = bits_rt(ci);
        int rd = bits_rd(ci);

        if (rd != 0) {
            Refs.r3000Regs[rd] = Refs.r3000Regs[rs] + Refs.r3000Regs[rt];
        }
    }

    public static void interpret_and(final int ci) {
        int rs = bits_rs(ci);
        int rt = bits_rt(ci);
        int rd = bits_rd(ci);

        if (rd != 0) {
            Refs.r3000Regs[rd] = Refs.r3000Regs[rs] & Refs.r3000Regs[rt];
        }
    }

    public static void interpret_andi(final int ci) {
        int rs = bits_rs(ci);
        int rt = bits_rt(ci);
        int imm = lo(ci);

        if (rt != 0) {
            Refs.r3000Regs[rt] = Refs.r3000Regs[rs] & imm;
        }
    }

    public static void interpret_beq(final int ci) {
        int rs = bits_rs(ci);
        int rt = bits_rt(ci);

        if (Refs.r3000Regs[rs] == Refs.r3000Regs[rt])
            Refs.r3000.interpreterBranch(signed_branch_delta(ci));
    }

    public static void interpret_bgez(final int ci) {
        int rs = bits_rs(ci);

        if (Refs.r3000Regs[rs] >= 0)
            Refs.r3000.interpreterBranch(signed_branch_delta(ci));
    }

    public static void interpret_bgezal(final int ci) {
        int rs = bits_rs(ci);
        // note operation is undefined if rs==31 so we don't care
        if (Refs.r3000Regs[rs] >= 0) {
            Refs.r3000Regs[R3000.R_RETADDR] = Refs.r3000.getPC() + 8; // return address
            Refs.r3000.interpreterBranch(signed_branch_delta(ci));
        }
    }

    public static void interpret_bgtz(final int ci) {
        int rs = bits_rs(ci);

        if (Refs.r3000Regs[rs] > 0)
            Refs.r3000.interpreterBranch(signed_branch_delta(ci));
    }

    public static void interpret_blez(final int ci) {
        int rs = bits_rs(ci);

        if (Refs.r3000Regs[rs] <= 0)
            Refs.r3000.interpreterBranch(signed_branch_delta(ci));
    }

    public static void interpret_bltz(final int ci) {
        int rs = bits_rs(ci);

        if (Refs.r3000Regs[rs] < 0)
            Refs.r3000.interpreterBranch(signed_branch_delta(ci));
    }

    public static void interpret_bltzal(final int ci) {
        int rs = bits_rs(ci);
        // note operation is undefined if rs==31 so we don't care
        if (Refs.r3000Regs[rs] < 0) {
            Refs.r3000Regs[R3000.R_RETADDR] = Refs.r3000.getPC() + 8; // return address
            Refs.r3000.interpreterBranch(signed_branch_delta(ci));
        }
    }

    public static void interpret_bne(final int ci) {
        int rs = bits_rs(ci);
        int rt = bits_rt(ci);

        if (Refs.r3000Regs[rs] != Refs.r3000Regs[rt])
            Refs.r3000.interpreterBranch(signed_branch_delta(ci));
    }

    // todo recheck this; this is a hack to avoid problems with certain versions of HotSpot (perhaps 1.4?)
    protected static boolean breakHotspot;

    public static void interpret_break(final int ci) {
        // TODO - note this is used for division by zero in wipeout!
        breakHotspot = false;
        Refs.scp.signalBreakException();
    }

    public static void interpret_div(final int ci) {
        int rs = bits_rs(ci);
        int rt = bits_rt(ci);

        if (Refs.r3000Regs[rt] != 0) {
            Refs.r3000.setLO(Refs.r3000Regs[rs] / Refs.r3000Regs[rt]);
            Refs.r3000.setHI(Refs.r3000Regs[rs] % Refs.r3000Regs[rt]);
        } else {
            // According to docs, this is what it does
            Refs.r3000.setLO(((Refs.r3000Regs[rs] >>> 31) << 1) - 1);
            Refs.r3000.setHI(Refs.r3000Regs[rs]);
        }
    }

    public static void interpret_divu(final int ci) {
        int rs = bits_rs(ci);
        int rt = bits_rt(ci);

        if (Refs.r3000Regs[rt] != 0) {
            long a = longFromUnsigned(Refs.r3000Regs[rs]);
            long b = longFromUnsigned(Refs.r3000Regs[rt]);
            Refs.r3000.setLO((int) (a / b));
            Refs.r3000.setHI((int) (a % b));
        } else {
            // According to docs, this is what it does
            Refs.r3000.setLO(-1);
            Refs.r3000.setHI(Refs.r3000Regs[rs]);
        }
    }

    public static void interpret_j(final int ci) {
        int delay = Refs.r3000.getPC() + 4;
        int target = delay & 0xf0000000;
        target += ((ci & 0x3ffffff) << 2);
        Refs.r3000.interpreterBranch(target - delay);
    }


    public static void interpret_jal(final int ci) {
        int pc = Refs.r3000.getPC();
        int delay = pc + 4;
        int target = (delay & 0xf0000000);
        target += ((ci & 0x3ffffff) << 2);

        Refs.addressSpace.tagClearPollCounters();
        Refs.r3000Regs[R3000.R_RETADDR] = pc + 8;
        Refs.r3000.interpreterJumpAndLink(target - delay, target, pc + 8);
    }

    public static void interpret_jalr(final int ci) {
        int rs = bits_rs(ci);
        int rd = bits_rd(ci);
        int pc = Refs.r3000.getPC();
        if (rd != 0) {
            Refs.r3000Regs[rd] = pc + 8;
        }
        Refs.addressSpace.tagClearPollCounters();
        int delay = pc + 4;
        int target = Refs.r3000Regs[rs];
        Refs.r3000.interpreterJumpAndLink(target - delay, target, pc + 8);
    }

    public static void interpret_jr(final int ci) {
        int rs = bits_rs(ci);
        // for now, mask to low PC
        int target = Refs.r3000Regs[rs];
        int delay = Refs.r3000.getPC() + 4;
        Refs.r3000.interpreterJump(target - delay, target);
    }

    public static void interpret_lb(final int ci) {
        int base = bits_rs(ci);
        int rt = bits_rt(ci);
        int offset = sign_extend(ci);
        int addr = Refs.r3000Regs[base] + offset;
        if (base != R3000.R_SP) {
            Refs.addressSpace.tagAddressAccessRead8(Refs.r3000.getPC(), addr);
        }
        int value = Refs.addressSpace.read8(addr);
        //System.out.println("lb "+MiscUtil.toHex( addr, 8)+" "+MiscUtil.toHex( (value<<24)>>24, 8));
        if (rt != 0) {
            value = (value << 24) >> 24;
            Refs.r3000Regs[rt] = value;
        }
    }

    public static void interpret_lbu(final int ci) {
        int base = bits_rs(ci);
        int rt = bits_rt(ci);
        int offset = sign_extend(ci);
        int addr = Refs.r3000Regs[base] + offset;
        if (base != R3000.R_SP) {
            Refs.addressSpace.tagAddressAccessRead8(Refs.r3000.getPC(), addr);
        }
        int value = Refs.addressSpace.read8(addr);
        //System.out.println("lbu "+MiscUtil.toHex( addr, 8)+" "+MiscUtil.toHex( value, 8));
        if (rt != 0) {
            Refs.r3000Regs[rt] = value;
        }
    }

    public static void interpret_lh(final int ci) {
        int base = bits_rs(ci);
        int rt = bits_rt(ci);
        int offset = sign_extend(ci);
        int addr = Refs.r3000Regs[base] + offset;
        if (base != R3000.R_SP) {
            Refs.addressSpace.tagAddressAccessRead16(Refs.r3000.getPC(), addr);
        }
        int value = Refs.addressSpace.read16(addr);
        //System.out.println("lh "+MiscUtil.toHex( addr, 8)+" "+MiscUtil.toHex( (value<<16)>>16, 8));
        if (rt != 0) {
            value = (value << 16) >> 16;
            Refs.r3000Regs[rt] = value;
        }
    }

    public static void interpret_lhu(final int ci) {
        int base = bits_rs(ci);
        int rt = bits_rt(ci);
        int offset = sign_extend(ci);
        int addr = Refs.r3000Regs[base] + offset;
        if (base != R3000.R_SP) {
            Refs.addressSpace.tagAddressAccessRead16(Refs.r3000.getPC(), addr);
        }
        int value = Refs.addressSpace.read16(addr);
        //System.out.println("lhu "+MiscUtil.toHex( addr, 8)+" "+MiscUtil.toHex( value, 8));
        if (rt != 0) {
            Refs.r3000Regs[rt] = value;
        }
    }

    public static void interpret_lui(final int ci) {
        int rt = bits_rt(ci);
        if (rt != 0) {
            Refs.r3000Regs[rt] = ci << 16;
        }
    }

    public static void interpret_lw(final int ci) {
        int base = bits_rs(ci);
        int rt = bits_rt(ci);
        int offset = sign_extend(ci);
        int addr = Refs.r3000Regs[base] + offset;
        if (base != R3000.R_SP) {
            Refs.addressSpace.tagAddressAccessRead32(Refs.r3000.getPC(), addr);
        }
        int value = Refs.addressSpace.read32(addr);
        if (rt != 0) {
            Refs.r3000Regs[rt] = value;
        }
    }

    public static void interpret_lwc1(final int ci) {
        breakHotspot = false;
        Refs.scp.signalReservedInstructionException();
    }

    public static void interpret_lwc3(final int ci) {
        breakHotspot = false;
        Refs.scp.signalReservedInstructionException();
    }

    private final static int[] lwl_mask = new int[]{0x00ffffff, 0x0000ffff, 0x000000ff, 0x00000000};
    private final static int[] lwl_shift = new int[]{24, 16, 8, 0};

    public static void interpret_lwl(final int ci) {
        int base = bits_rs(ci);
        int rt = bits_rt(ci);
        int offset = sign_extend(ci);
        int addr = Refs.r3000Regs[base] + offset;
        if (base != R3000.R_SP) {
            Refs.addressSpace.tagAddressAccessRead32(Refs.r3000.getPC(), addr);
        }
        int value = Refs.addressSpace.read32(addr & ~3);
        if (rt != 0) {
            Refs.r3000Regs[rt] = (Refs.r3000Regs[rt] & lwl_mask[addr & 3]) | (value << lwl_shift[addr & 3]);
        }
    }

    private final static int[] lwr_mask = new int[]{0x00000000, 0xff000000, 0xffff0000, 0xffffff00};
    private final static int[] lwr_shift = new int[]{0, 8, 16, 24};

    public static void interpret_lwr(final int ci) {
        int base = bits_rs(ci);
        int rt = bits_rt(ci);
        int offset = sign_extend(ci);
        int addr = Refs.r3000Regs[base] + offset;
        if (base != R3000.R_SP) {
            Refs.addressSpace.tagAddressAccessRead32(Refs.r3000.getPC(), addr);
        }
        int value = Refs.addressSpace.read32(addr & ~3);
        if (rt != 0) {
            Refs.r3000Regs[rt] = (Refs.r3000Regs[rt] & lwr_mask[addr & 3]) | ((value >> lwr_shift[addr & 3]) & ~lwr_mask[addr & 3]);
        }
    }

    public static void interpret_mfhi(final int ci) {
        int rd = bits_rd(ci);
        Refs.r3000Regs[rd] = Refs.r3000.getHI();
    }

    public static void interpret_mflo(final int ci) {
        int rd = bits_rd(ci);
        Refs.r3000Regs[rd] = Refs.r3000.getLO();
    }

    public static void interpret_mthi(final int ci) {
        int rs = bits_rs(ci);
        Refs.r3000.setHI(Refs.r3000Regs[rs]);
    }

    public static void interpret_mtlo(final int ci) {
        int rs = bits_rs(ci);
        Refs.r3000.setLO(Refs.r3000Regs[rs]);
    }

    public static void interpret_mult(final int ci) {
        int rs = bits_rs(ci);
        int rt = bits_rt(ci);

        long result = ((long) Refs.r3000Regs[rs]) * ((long) Refs.r3000Regs[rt]);
        Refs.r3000.setLO((int) result);
        Refs.r3000.setHI((int) (result >> 32));
    }

    public static void interpret_multu(final int ci) {
        int rs = bits_rs(ci);
        int rt = bits_rt(ci);

        long result = (longFromUnsigned(Refs.r3000Regs[rs])) * (longFromUnsigned(Refs.r3000Regs[rt]));
        Refs.r3000.setLO((int) result);
        Refs.r3000.setHI((int) (result >> 32));
    }

    public static void interpret_nor(final int ci) {
        int rs = bits_rs(ci);
        int rt = bits_rt(ci);
        int rd = bits_rd(ci);
        if (rd != 0) {
            Refs.r3000Regs[rd] = (Refs.r3000Regs[rs] | Refs.r3000Regs[rt]) ^ 0xffffffff;
        }
    }

    public static void interpret_or(final int ci) {
        int rs = bits_rs(ci);
        int rt = bits_rt(ci);
        int rd = bits_rd(ci);
        if (rd != 0) {
            Refs.r3000Regs[rd] = Refs.r3000Regs[rs] | Refs.r3000Regs[rt];
        }
    }

    public static void interpret_ori(final int ci) {
        int rs = bits_rs(ci);
        int rt = bits_rt(ci);
        if (rt != 0) {
            Refs.r3000Regs[rt] = Refs.r3000Regs[rs] | lo(ci);
        }
    }

    public static void interpret_sb(final int ci) {
        int base = bits_rs(ci);
        int rt = bits_rt(ci);
        int offset = sign_extend(ci);
        int addr = Refs.r3000Regs[base] + offset;
        if (base != R3000.R_SP) {
            Refs.addressSpace.tagAddressAccessWrite(Refs.r3000.getPC(), addr);
        }
        Refs.addressSpace.write8(addr, Refs.r3000Regs[rt]);
    }

    public static void interpret_sh(final int ci) {
        int base = bits_rs(ci);
        int rt = bits_rt(ci);
        int offset = sign_extend(ci);
        int addr = Refs.r3000Regs[base] + offset;
        if (base != R3000.R_SP) {
            Refs.addressSpace.tagAddressAccessWrite(Refs.r3000.getPC(), addr);
        }
        Refs.addressSpace.write16(addr, Refs.r3000Regs[rt]);
    }

    public static void interpret_sll(final int ci) {
        if (ci == 0)
            return; // nop
        int rd = bits_rd(ci);
        int rt = bits_rt(ci);
        int sa = bits_sa(ci);
        if (rd != 0) {
            Refs.r3000Regs[rd] = Refs.r3000Regs[rt] << sa;
        }
    }

    public static void interpret_sllv(final int ci) {
        int rd = bits_rd(ci);
        int rt = bits_rt(ci);
        int rs = bits_rs(ci);
        if (rd != 0) {
            Refs.r3000Regs[rd] = Refs.r3000Regs[rt] << (Refs.r3000Regs[rs] & 31);
        }
    }

    public static void interpret_slt(final int ci) {
        int rs = bits_rs(ci);
        int rt = bits_rt(ci);
        int rd = bits_rd(ci);
        if (rd != 0) {
            Refs.r3000Regs[rd] = (Refs.r3000Regs[rs] < Refs.r3000Regs[rt]) ? 1 : 0;
        }
    }

    public static void interpret_slti(final int ci) {
        int rs = bits_rs(ci);
        int rt = bits_rt(ci);
        int imm = sign_extend(ci);
        if (rt != 0) {
            Refs.r3000Regs[rt] = (Refs.r3000Regs[rs] < imm) ? 1 : 0;
        }
    }

    public static void interpret_sltiu(final int ci) {
        int rs = bits_rs(ci);
        int rt = bits_rt(ci);
        int imm = lo(ci);
        if (rt != 0) {
            Refs.r3000Regs[rt] = (longFromUnsigned(Refs.r3000Regs[rs]) < (long) imm) ? 1 : 0;
        }
    }

    public static void interpret_sltu(final int ci) {
        int rs = bits_rs(ci);
        int rt = bits_rt(ci);
        int rd = bits_rd(ci);
        if (rd != 0) {
            Refs.r3000Regs[rd] = (longFromUnsigned(Refs.r3000Regs[rs]) < longFromUnsigned(Refs.r3000Regs[rt])) ? 1 : 0;
        }
    }

    public static void interpret_srl(final int ci) {
        int sa = bits_sa(ci);
        int rt = bits_rt(ci);
        int rd = bits_rd(ci);
        if (rd != 0) {
            Refs.r3000Regs[rd] = (int) ((longFromUnsigned(Refs.r3000Regs[rt])) >> sa);
        }
    }

    public static void interpret_srlv(final int ci) {
        int rs = bits_rs(ci);
        int rt = bits_rt(ci);
        int rd = bits_rd(ci);
        if (rd != 0) {
            Refs.r3000Regs[rd] = (int) ((longFromUnsigned(Refs.r3000Regs[rt])) >> Refs.r3000Regs[rs]);
        }
    }

    public static void interpret_sra(final int ci) {
        int sa = bits_sa(ci);
        int rt = bits_rt(ci);
        int rd = bits_rd(ci);
        if (rd != 0) {
            Refs.r3000Regs[rd] = Refs.r3000Regs[rt] >> sa;
        }
    }

    public static void interpret_srav(final int ci) {
        int rs = bits_rs(ci);
        int rt = bits_rt(ci);
        int rd = bits_rd(ci);
        if (rd != 0) {
            Refs.r3000Regs[rd] = Refs.r3000Regs[rt] >> Refs.r3000Regs[rs];
        }
    }

    public static void interpret_sub(final int ci) {
        int rs = bits_rs(ci);
        int rt = bits_rt(ci);
        int rd = bits_rd(ci);

        int res = Refs.r3000Regs[rs] - Refs.r3000Regs[rt];

        // arithmetic overflow if rs and rs have different top bit (i.e. sign)
        // and the result has a different sign to rs
        if (!ignoreArithmeticOverflow) {
            if (mask_bit31(Refs.r3000Regs[rs] ^ Refs.r3000Regs[rt]) && mask_bit31(res ^ Refs.r3000Regs[rs])) {
                Refs.scp.signalIntegerOverflowException();
                return;
            }
        }
        if (rd != 0) {
            Refs.r3000Regs[rd] = res;
        }
    }

    public static void interpret_subu(final int ci) {
        int rs = bits_rs(ci);
        int rt = bits_rt(ci);
        int rd = bits_rd(ci);

        if (rd != 0) {
            Refs.r3000Regs[rd] = Refs.r3000Regs[rs] - Refs.r3000Regs[rt];
        }
    }

    public static void interpret_sw(final int ci) {
        int base = bits_rs(ci);
        int rt = bits_rt(ci);
        int offset = sign_extend(ci);
        int addr = Refs.r3000Regs[base] + offset;
        if (base != R3000.R_SP) {
            Refs.addressSpace.tagAddressAccessWrite(Refs.r3000.getPC(), addr);
        }
        Refs.addressSpace.write32(addr, Refs.r3000Regs[rt]);

        // tmp hack
        //if (addr==0x800dee54) {
        //    System.out.println("Write cdCurrentCmd "+MiscUtil.toHex( Refs.r3000Regs[rt], 8)+" at "+MiscUtil.toHex( Refs.r3000.getPC(), 8));
        //    m_cpuCmdPending = true;
        //}
    }

    public static void interpret_swc1(final int ci) {
        breakHotspot = false;
        Refs.scp.signalReservedInstructionException();
    }

    public static void interpret_swc3(final int ci) {
        breakHotspot = false;
        Refs.scp.signalReservedInstructionException();
    }

    private static final int[] swl_mask = new int[]{0xffffff00, 0xffff0000, 0xff000000, 0x00000000};
    private static final int[] swl_shift = new int[]{24, 16, 8, 0};

    public static void interpret_swl(final int ci) {
        int base = bits_rs(ci);
        int rt = bits_rt(ci);
        int offset = sign_extend(ci);
        int addr = Refs.r3000Regs[base] + offset;
        Refs.addressSpace.tagAddressAccessWrite(Refs.r3000.getPC(), addr);
        int value = Refs.addressSpace.read32(addr & ~3);
        value = (value & swl_mask[addr & 3]) | ((Refs.r3000Regs[rt] >> swl_shift[addr & 3]) & ~swl_mask[addr & 3]);
        Refs.addressSpace.write32(addr & ~3, value);
    }

    private static final int[] swr_mask = new int[]{0x00000000, 0x000000ff, 0x0000ffff, 0x00ffffff};
    private static final int[] swr_shift = new int[]{0, 8, 16, 24};

    public static void interpret_swr(final int ci) {
        int base = bits_rs(ci);
        int rt = bits_rt(ci);
        int offset = sign_extend(ci);
        int addr = Refs.r3000Regs[base] + offset;
        Refs.addressSpace.tagAddressAccessWrite(Refs.r3000.getPC(), addr);
        int value = Refs.addressSpace.read32(addr & ~3);
        value = (value & swr_mask[addr & 3]) | (Refs.r3000Regs[rt] << swr_shift[addr & 3]);
        Refs.addressSpace.write32(addr & ~3, value);
    }

    public static void interpret_syscall(final int ci) {
        breakHotspot = false;
        Refs.scp.signalSyscallException();
    }

    public static void interpret_xor(final int ci) {
        int rs = bits_rs(ci);
        int rt = bits_rt(ci);
        int rd = bits_rd(ci);
        if (rd != 0) {
            Refs.r3000Regs[rd] = Refs.r3000Regs[rs] ^ Refs.r3000Regs[rt];
        }
    }

    public static void interpret_xori(final int ci) {
        int rs = bits_rs(ci);
        int rt = bits_rt(ci);
        if (rt != 0) {
            Refs.r3000Regs[rt] = Refs.r3000Regs[rs] ^ lo(ci);
        }
    }

    public static void emitLongFromUnsigned(ConstantPoolGen cp, InstructionList il) {
        il.append(new I2L());
        il.append(new PUSH(cp, 0xffffffffL));
        il.append(new LAND());
    }
}
