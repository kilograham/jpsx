# JPSX

JPSX is a PlayStation emulator written entirely in Java (you can see what it is supposed to look like in this old video https://www.youtube.com/watch?v=_8oyzLJHhS0).
If it doesn't work at least that well for those games, it is probably your environment!

# History

I (Graham) wrote it back in 2003 basically just because it was exactly the sort of thing people were saying Java was too slow for. I had written a C/C++ emu in the late 1990s
that I never made publicly available (though some of the code found a home), so I had already done a bunch of the reverse engineering work.

I have actually done very little to it over time, other than periodically trying it on new JVMs (it should work on anything JDK1.4+, though JDK5 is now probably a sensible minimum).
I also gave a talk on it at JavaOne in 2006, and have been meaning to open source it ever since (though it had so many little things wrong with it
 that annoyed me, which I had never gotten around to fixing, so it never happened until now). Thanks to jvilk for catching me at the right time
 and convincing me to open source it in all its warty glory.

# Philosophy

Since I was writing it in Java, I thought it'd be nice to make it do it in an object oriented style. Therefore there are prett well encapsulated classes 
representing the different physical hardware and indeed internal emulation components.

That said, this was written back with the HotSpot client compiler in mind (the server compiler caused way too many compilation
 pauses back then for game use) so generally I always picked runtime speed over pretty code.

There are all kinds of optimizations in there (you can read about some of it here http://docs.huihoo.com/javaone/2006/cool-stuff/ts-5547.pdf), some of which still make sense
some of which don't and just make things clunky/ugly.
Those which are stupid on a modern JVM will probably be removed, though equally I expect that this codebase is likely to be used on
lower spec Java platforms in the future, where a lot of this stuff will still matter, so I don't expect to rip it all out if it doesn't actively hurt - we might fork the codebase though.

## PSX a different platform; JPSX a different style of emulator

At least at the time (I don't really follow the emu scene)... there seemed to be a lot of focus in emulators about cycle counting. This certainly makes
sense in a lot of cases especially on older platforms where you really cared where the CRT electron beam was when this cycle executed etc,
or you needed to write a sound sample out at exactly this moment etc.

Looking at the PSX, it seemed to be sort of a new breed, with a more mature set of components loosely coupled (via IRQ and DMA) which otherwise carried on largely
independently. For that reason I decided to emulate each component largely independently too. Sometimes in separate threads, sometimes synced to external 
timing events, but coupled via Java synchronization. As such, for example, the R3000 CPU emulation (well more accurately R300 code is transcoded/recompiled/reoptimized
 into bytecode) runs as fast as it can and then blocks.

This turns out to work remarkably well
(of course JPSX has to detect busy wait loops so as not to hog (busy wait on) the native CPU, and because the PSX level loop timeouts are WAY too short for native hardware).

That said of course there are badly written bits of PSX code with race conditions which need to be patched or worked around, some perhaps badly designed hardware
(I spent a LONG time trying to figure out a robust interaction between the CPU thread and CD controller which it seems may never have existed), but in the spirit
of philosophy, unless it proves impossible, I'd like JPSX to continue in this vain.

# More on implementation

JPSX is built entirely of plug-able components. If you look at `jpsx.xml`, you'll see the default definitions that I use.
The XML format is just a (in)convenience - that might be one of the first things to go now this is open sourced.
This is parsed into a MachineDefintion internally (see `MachineDefinition.java`)

Although any part of the emulator can be replaced, it makes less sense to do so with some parts than others; I have three main groupings:

* **core**: internal stuff like address space (that you probably don't want to swap out) - this certainly needs some work in places, but I doubt we'll end up with different implementations
* **hardware**: implementations of emulated hardware that you might want to swap out
* **emulator**: implementations of emulator components such as the recompiler

While the emu doesn't force any particular interfaces on the implementation, obviously for different pieces of the emulator to work together they have to know how to talk to each other. 
This is done by well known (to each other) named connections (basically an interface), and these happen to live in the API tree.

An example connection is `Display` which is used by the current software GPU (which could of course be replaced). The Display implementation provides an `int[]`
 (basically RGB32) for the GPU to draw into, and the Display is only responsible for displaying the correct portion of VRAM to the user when told to.

Similarly there are some abstractions for serial ports and devices, CD sector providers etc.

# Compatibility

## Java Compatibility

See the [wiki](https://github.com/jvilk/jpsx/wiki/Platform-Compatibility) for full details, but recent tests of

* Oracle JDK6
* Oracle JDK7
* Oracle JDK8

on recent

* OS X
* Ubuntu
* Windows (WHAT VERSIONS HAVE BEEN TESTED?)

Work reasonably well, with the following general exceptions

* **GRAPHICS** - The default AWT based screen image display mechanism `ATWDisplay` is broken (painfully slow) on JDK6 on OS X. Use the `LWJGL` display as a workaround (pass `lwjgl` to `osx.sh`).
This may also be broken on certain other JDK versions and platforms (you'll see **!SLOW!** in the title bar)... there seems to have been some screw ups
during the introduction of the OpenGL and DirectX Java2d rendering pipelines, along with the introduction of Compositing Desktops (e.g. Aero). Ironically
this worked great on all platforms way back when, and fortunately now seems to be mostly fixed again.
* **SOUND** - the Default `SPU` is not great at the best of times (ok for sound effects and good for CD audio), 
but it relied on features of `JavaSound` that were removed in later JDKs (JDK7 and JDK8 for sure). This has been
band-aided for now, so you do get sound, but JDK6 sound is better

It will not run on anything prior to JDK1.4, and JDK5 will likely be the minimum since the JavaMemoryModel was not properly defined before that.

## Software Compatibility

See the [wiki](https://github.com/jvilk/jpsx/wiki/Software-Compatibility) for full list of games and demos that have been tested

## Hardware Compatibility

See the [wiki](https://github.com/jvilk/jpsx/wiki/Hardware-Components) for current compatibility status and details of available
hardware emulation components

## Developer Documentation

See the [wiki](https://github.com/jvilk/jpsx/wiki/Development-Documentation) for development related documentation

# Building the Emulator

`ant` should probably do it.

# Running the Emulator

**todo** flesh this out 

Right now this is pretty bare bones, but this should get you started.

* You must launch from the command-line - there is currently no GUI except the screen display
* You need a bios image called bios.bin
* The set of emulation components is defined as a named "machine" in XML: The `"default"` machine `jpsx.xml` is used unless you specify something else.
A machine lists the components that make it up, possible including (and optionally overriding) components from another machine definition. This makes it easy to tweak components
 or component settings for a particular game. If you don't include enough basic hardware, then things won't run.
* Right now CUE/BIN CD image files are the only thing supported (though it should be easy to add other format). Without one, you'll get stuck in the BIOS. Note that
you can (and it is quite gratifying) use the CD player in the BIOS if you provide a CUE/BIN image of a music CD.
* The currently supplied components that can display PSX graphics on your desktop all do so in a window.

## Command Line

`osx.sh` is provided as an example script for OS X

The following are required options for HotSpot, or things won't work properly/at all.

```
-XX:-DontCompileHugeMethods     needed on everything otherwise things may be slow
-XX:-UseSplitVerifier           needed on JDK7+ (current BCEL code gen isnt' supported by JVM otherwise)
-XX:-OmitStackTraceInFastThrow  needed on newer JVMs which otherwise break JPSX by removing required line number information
```

**todo this comment is old, and needs investigation:**  The `DontCompileHugeMethods` is important, since sometimes generated byte code for a method is large (although I did add some code later to split big functions), 
and by default HotSpot doesn't bother to compile methods that are too large.

Additional arguments are `<machineId>` to pick a specific machine, and any number of `property=value`. 

For example, `./osx.sh image=/rips/gt.cue` will 
set the image property used by the CUE/BIN CD drive to a specific file (note I think right not that the CUE must specify an absolute path to the BIN file).

For example, `./osx.sh lwjgl` will use the machine definition called *lwjgl* that uses `LWJGLDisplay` in place of `AWTDisplay`

# Keys

The default machine definition includes a `Console` component... this is interactive sort of like `gdb`.
So to get stuff to run in this mode you need to enter "g" for go. You can look at the code for Console to figure out some other commands. "b" breaks for example

By default the pad is controlled by giving focus to the display window.

These are the default mappings copied from `AWTKeyboardController`

```
        DEF_CONTROLLER_0_MAPPING.put(PADstart, KeyEvent.VK_SPACE);
        DEF_CONTROLLER_0_MAPPING.put(PADselect, KeyEvent.VK_S);
        DEF_CONTROLLER_0_MAPPING.put(PADLup, KeyEvent.VK_UP);
        DEF_CONTROLLER_0_MAPPING.put(PADLleft, KeyEvent.VK_LEFT);
        DEF_CONTROLLER_0_MAPPING.put(PADLright, KeyEvent.VK_RIGHT);
        DEF_CONTROLLER_0_MAPPING.put(PADLdown, KeyEvent.VK_DOWN);
        DEF_CONTROLLER_0_MAPPING.put(PADRup, KeyEvent.VK_8);
        DEF_CONTROLLER_0_MAPPING.put(PADRup, KeyEvent.VK_KP_UP);
        DEF_CONTROLLER_0_MAPPING.put(PADRdown, KeyEvent.VK_K);
        DEF_CONTROLLER_0_MAPPING.put(PADRleft, KeyEvent.VK_KP_LEFT);
        DEF_CONTROLLER_0_MAPPING.put(PADRright, KeyEvent.VK_I);
        DEF_CONTROLLER_0_MAPPING.put(PADRright, KeyEvent.VK_KP_RIGHT);
        DEF_CONTROLLER_0_MAPPING.put(PADRdown, KeyEvent.VK_KP_DOWN);
        DEF_CONTROLLER_0_MAPPING.put(PADRleft, KeyEvent.VK_U);
        DEF_CONTROLLER_0_MAPPING.put(PADL1, KeyEvent.VK_1);
        DEF_CONTROLLER_0_MAPPING.put(PADL2, KeyEvent.VK_Q);
        DEF_CONTROLLER_0_MAPPING.put(PADR1, KeyEvent.VK_2);
        DEF_CONTROLLER_0_MAPPING.put(PADR2, KeyEvent.VK_W);
```


The current displays also supports a few keys (don't forget `fn` on OS X).

* **F12**: Change window size (picks from a preset list of resolutions).
* **F9**: Toggle display of all VRAM - this is kinda cool.