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
package org.jpsx.runtime.components.emulator.compiler;

import org.apache.bcel.classfile.JavaClass;
import org.apache.log4j.Logger;
import org.jpsx.api.components.core.ContinueExecutionException;
import org.jpsx.api.components.core.addressspace.AddressSpace;
import org.jpsx.runtime.components.core.CoreComponentConnections;
import org.jpsx.runtime.util.MiscUtil;

import java.lang.ref.SoftReference;

// todo check threading
/**
 * The CodeUnit class represents all that is known about
 * a particular piece sequence R3000 code.
 * <p/>
 * It embodies the various states that a piece
 * of code can be in (flow analyzed, stage 1 compiled, etc.)
 */
public class CodeUnit {
    Logger log = Logger.getLogger("CodeUnit");
    /**
     * Used by the compiled code to determine
     * which version of the code to use
     * <p/>
     * Note this is also used as a guard to ensure visibility between threads
     */
    public volatile boolean useStage2;
    /**
     * Used by the compiled code, to determine
     * when to move through the state machine...
     * when this counts down to zero, countComplete() is called.
     * <p/>
     * This is not volatile, since it is repeatedly updated by the processor thread, and only
     * rarely modified by another thread. it is however guaranteed to be visible, since the processor
     * thread reads useStage2 before reading this, and non processor threads write useStage2 afterwards
     */
    public int count = MultiStageCompiler.Settings.stage2Threshold;

    private Class stage2Class;
    private int state;

    // base address of code unit
    protected final int base;     // todo accessor
    private int end;
    private final boolean rom;

    private Executable executable;
    protected boolean linksFollowed; // todo accessor
    private volatile boolean stage1Ready;
    //private boolean stage2Ready;

    // todo accessor
    protected int stage2Version;

    private int preBreakpointState;
    private boolean preBreakpointUseStage2;
    private int breakpointCount;

    private static final int STATE_STAGE1 = 0;
    private static final int STATE_WAITING_FOR_STAGE2 = 1;
    private static final int STATE_STAGE2 = 2;
    private static final int STATE_BREAKPOINT = 3;

    // todo it is not clear this is safe to share in the presence of code modification
    private SoftReference flowInfoRef = new SoftReference(null);
    private SoftReference stage1JavaClassRef = new SoftReference(null);

    public CodeUnit(int base) {
        this.base = base;
        rom = AddressSpace.Util.isBIOS(base);
    }

    public final int getBase() {
        return base;
    }

    public final boolean isROM() {
        return rom;
    }

    public Executable getExecutable() {
        return executable;
    }

    public void setExecutable(Executable executable) {
        this.executable = executable;
    }

    /**
     * unsynchronzied, since different threads should
     * use different flow analyzers.
     *
     * may return null for a garbage method if not called on the execution thread
     */
    public FlowAnalyzer.FlowInfo getFlowInfo(FlowAnalyzer flowAnalyzer, boolean executionThread) {
        FlowAnalyzer.FlowInfo rc = (FlowAnalyzer.FlowInfo) flowInfoRef.get();
        if (rc == null) {
            rc = flowAnalyzer.buildFlowGraph(base, executionThread);
            if (rc != null) {
                end = rc.end;
                flowInfoRef = new SoftReference(rc);
            }
        }
        return rc;
    }

    /**
     * Get the JavaClass representation of this unit.
     * <p/>
     * This method may be called from the execution or background
     * compilation thread; in either case it is not synchronized,
     * since we don't want the execution thread to wait on the
     * background thread if they end up compiling the same class.
     *
     * this may return null if not on the execution thread for garbage code
     */
    public JavaClass getStage1JavaClass(Stage1Generator generator, boolean executionThread) {
        // note while not synchronized, once stage1Ready is set
        // we know for sure that we don't want to do any
        // background compilation
        if (!executionThread && stage1Ready)
            return null;

        JavaClass rc = (JavaClass) stage1JavaClassRef.get();
        if (rc == null) {
            rc = generator.createJavaClass(this, executionThread);
            if (!executionThread) {
                stage1JavaClassRef = new SoftReference(rc);
            } else {
                //System.out.println("*** COMPILE IN EXEC THREAD "+MiscUtil.toHex( base, 8));
            }
        }
        return rc;
    }

    public void stage1ClassReady() {
        stage1Ready = true;
        // note we may miss clearing the soft reference, but oh well!
        stage1JavaClassRef.clear();
        // make sure we know about any breakpoints
        MultiStageCompiler.enumerateBreakpoints(this);
    }

    // This can return null on non java thread for garbage code
    public JavaClass getStage2JavaClass(Stage2Generator generator, boolean executionThread) {
        return generator.createJavaClass(this, executionThread);
    }

    public void stage2ClassReady(Class stage2Class) {
        //stage2Ready = true;
        this.stage2Class = stage2Class;
        count = Integer.MAX_VALUE;
        state = STATE_STAGE2;
        stage2Version++;
        useStage2 = true;
    }

    public void stage2ClassBroken() {
        // shouldn't be able to happen when we have a breakpoint in the function
        assert state != STATE_BREAKPOINT;
        // todo setting for this
        if (stage2Version < 5) {
            try {
                stage2Class.getField("replaced").setBoolean(null, true);
            } catch (Throwable ignore) {
                assert false;
            }
            if (log.isDebugEnabled()) {
                log.debug("Re-write " + MiscUtil.toHex(base, 8) + " version " + (stage2Version + 1));
            }
            count = MultiStageCompiler.Settings.stage2Threshold;
            state = STATE_STAGE1;
        } else {
            // just give up
            if (log.isDebugEnabled()) {
                log.debug("Too many rewrites for " + MiscUtil.toHex(base, 8));
            }
            state = STATE_WAITING_FOR_STAGE2;
        }
        useStage2 = false;
    }

    public void breakpointAdded(int address) {
        if (stage1Ready) {
            assert end != 0;
            if (address >= base && address < end) {
                breakpointCount++;
                if (breakpointCount == 1) {
                    log.info("HAVE BREAKPOINT IN " + MiscUtil.toHex(base, 8));
                    preBreakpointState = state;
                    preBreakpointUseStage2 = useStage2;
                    state = STATE_BREAKPOINT;
                    count = 0;
                    useStage2 = false;
                }
            }
        }
    }

    public void breakpointRemoved(int address) {
        if (stage1Ready) {
            assert end != 0;
            if (address >= base && address < end) {
                breakpointCount--;
                if (breakpointCount == 0) {
                    count = MultiStageCompiler.Settings.stage2Threshold;
                    state = preBreakpointState;
                    useStage2 = preBreakpointUseStage2;
                }
            }
        }
    }

    public void countComplete() {
        switch (state) {
            case STATE_STAGE1:
                state = STATE_WAITING_FOR_STAGE2;
                count = Integer.MAX_VALUE;
                if (MultiStageCompiler.Settings.enableSecondStage) {
                    MultiStageCompiler.registerForStage2(this);
                }
                break;
            case STATE_BREAKPOINT:
                // break out into the interpreter
                CoreComponentConnections.R3000.resolve().setPC(base);
                count = 0;
                //System.out.println( "entered compiled function that has a breakpoint!" );
                throw ContinueExecutionException.DONT_SKIP_CURRENT;
            default:
                count = Integer.MAX_VALUE;
                break;
        }
    }

    public boolean stage1Ready()
	{
		return stage1Ready;
	}
}
