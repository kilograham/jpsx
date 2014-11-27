# JPSX

JPSX is a PlayStation emulator written entirely in Java (you can see what it is supposed to look like in this old video https://www.youtube.com/watch?v=_8oyzLJHhS0)..
if it doesn't work at least that well for those games, it is probably your environment!

# History

I (graham) wrote it back in 2003 basically just because it was exactly the sort of thing people were saying Java was too slow for. I had written a C/C++ emu in the late 1990s
that I never made publicly available (though some of the code found a home), so I had already done a bunch of the reverse engineering work.

I have actually done very little to it over time, other than periodically trying it on new JVMs (it should work on anything JDK1.4+, though JDK5 is now probably a sensible minimum).
I did give a talk on it at JavaOne in 2006. I have been meaning to open source it ever since, so thanks to jvilk I now have.

# Philosophy

Since I was writing it in Java, I thought it'd be nice to make it do it in an object oriented style. Therefore there are largely encapsulated classes representing the different
physical hardware and indeed internal emulation components.

That said, this was written back with the HotSpot client compiler in mind (the server compiler caused way too many compilation pauses back then for game use), so generally
I always picked speed over pretty code.

There are all kinds of optimizations in there (you can read about some of it here http://docs.huihoo.com/javaone/2006/cool-stuff/ts-5547.pdf), some of which still make sense
some of which don't, and just make things clunky/ugly.
Those which are stupid on a modern JVM will probably be removed, though equally I expect that this codebase is likely to be used on
lower spec Java platforms in the future, where a lot of this stuff will still matter, so I don't expect to rip it all out if it doesn't actively hurt - we might fork the codebase though.

## PSX a different platform, JPSX a different style of emulator

At least at the time (I don't really follow the emu scene)... there seemed to be a lot of focus in emulators about cycle counting. This certainly makes sense in a lot of cases
especially older platforms where you really cared where the CRT electron beam was when this cycle executed etc, or you needed to write a sound sample out at exactly this moment etc.

From looking at the PSX, it seemed to be sort of a new breed, with a more mature set of components loosely coupled (via IRQ and DMA) which otherwise carried on largely
independently. For that reason I decided to emulate each component largely independently. Sometimes in separate threads, sometimes synced to external timing events, but coupled via Java synchronization. As such for example
for example the R3000 emulation (well more accurately it is transcoded/recompiled into bytecode) runs as fast as it can and then blocks.

This turns out to work remarkably well
(of course JPSX has to detect busy wait loops so as not to hog (busy wait on) the native CPU, or indeed because the PSX level loop timeouts are WAY too short for native hardware),

That said of course there are badly written bits of PSX code with race conditions which need to be patched or worked around, some perhaps badly designed hardware
(I spent a LONG time trying to figure out a robust interaction between the CPU thread and CD controller which it seems may never have existed), but in the spirit
of philosophy, unless it proves impossible, I'd like JPSX to conntinue in this vain.

# More on implementation

JPSX is built entirely of plug-able components. If you look at `jpsx.xml`, you'll see the default definitions that I use.
The XML format is just a (in)convenience - that will likely be one of the first things to go now this is open sourced.
This is parsed into a MachineDefintion internally (see `MachineDefinition.java`)

Although any part of the emulator can be replaced, it makes less sense to do so with some parts than others; I have three main groupings:

* **core**: internal stuff like address space (that you probably don't want to swap out) - this certainly needs some work in places, but I doubt we'll end up with different implementations
* **hardware**: implementations of emulated hardware that you might want to swap out
* **emulator**: implementations of emulator components such as the recompiler

While the emu doesn't force any particular interfaces on the implementation, obviously for different pieces of the emulator to work together they have to know how to talk to eachother. This is done by named connections (basically an interface), and these happen to live in the API tree.

An example connection is Display which is used by the current software GPU (which could of course be replaced). The Display implementation provides an `int[]` basically XRGB32
for the GPU to draw into, and the Display is only responsible for displaying the correct portion of VRAM to the user when told to.

Similarly there are some abstractions for serial ports and devices, providers for CD data etc.

# PSX Compatibility

TODO move these to wiki pages
## Games

Compatibility of games as evidenced on at least one platform

TODO we should note platform/JDK etc. where we know it works

Mostly flawless

* Abe's Odyssey
* Wipeout
* Wipeout XL
* Tekken
* TombRaider

OK

* BIOS
    Sound kind of sucks on existing SPU (reverb)
    Occasionally get corrupted display in CD player
    CD player displayed minutes and seconds are wrong I think
* Crash Bandicoot (note you must use the "bandicoot" machine which turns on a fudge for root counters which aren't in there yet)
* MIDI sound kind of sucks

Somewhat

* Gran Turismo (if you turn the sound off - against there is a "gt" machine)

## Hardware

TODO - see wiki here

# JDK/Platform Compatibility

As mentioned, this was originally written a long time ago around JDK1.4, that said we have recently tested it on JDK6, 7, 8 and it generally runs OK.

## AWT image handling

The software GPU creates basically a Java int[] representing 32 bit RGB for the display RAM, a rectangle of which simply needs to be copied up to the screen.
This should be mind-bogglingly simple (especially since for AWT the int[] is part of a BufferedImage). That said (whilst working fine back in the day on nearly all platforms)
this has been frequently broken (in terms of performance) since.

Look for a "Blit XX ms" in the window title bar. If this is >1ms something is horribly wrong... Right now this blit is in the main PSX thread (with CPU, GTE, GPU, MDEC and some others)
and generally needs to be done 25/30/50/60 times per second depending on the game region so once it gets to the >20ms range which I've seen on some platforms (I'm looking at you OSX JDK 6, and JDK 5 on Vista) then
basically it is taking 100% of the game thread.

Fortunately it seems fixed again on newer JVMs, but as an alternative you can use LWJGL to do the copy up via OpenGL pipe which generally plays well
with modern composited windowing systems. Of course someone can and hopefully will implement a fully OpenGL renderer too!

TODO - see wiki here for full list SPU details etc.

# Emulation state

TODO - see wiki here
# Building the Emulator

TODO: complete this
"ant" should do it.

# Running the Emulator


If you are unsure if you're getting acceleration try `-Dsun.java2d.trace=log`, if use see a lot of java2d.loops stuff that is unnaccelerated (note if you are successfully using quartz on OS X then you won't see any logging)

## Command Line

I included a bat file for windows, but for OSX it should look something like:

``` 
java -Xmx128M -XX:-DontCompileHugeMethods -XX:-UseSplitVerifier -XX:-OmitStackTraceInFastThrow org.jpsx.bootstrap.JPSXLauncher $*
```

-XX:-DontCompileHugeMethods     needed on everything
-XX:-UseSplitVerifier           needed on JDK7+ (current BCEL code gen doesn't do the right thing)
-XX:-OmitStackTraceInFastThrow  needed on newer JVMs (if it supports it use it)

Note the `DontCompileHugeMethods` is important, since sometimes generated bytecode for a method is large (although I did add some code later to split big functions), and by default HotSpot doesn't bother to compile methods that are too large.

Additional arguments are `<machineId>` to pick a specific machine, and any number of `property=value`. For example, `rnea image=/rips/gt.cue` will set the image property used by the CUE/BIN CD drive to a specific file (note I think right not that the CUE must specify an absolute path to the BIN file).


# Keys

The default machine definition includes a "console" component... this is interactive sort of like `gdb`.
So to get stuff to run in this mode you need to enter "g" for go. You can look at the code for Console to figure out some other commands. "b" breaks for example

Look at `AWTKeyboardController` for the controller mappings

The display also supports a few keys (don't forget `fn` on OS X).

* **F12**: resize (on Vista the display is sometimes off by a few pixels, hitting F12 back to the same original size fixes this)
* **F9**: toggle full VRAM display - this is kinda cool

# Final Words

GOOD LUCK; let me know what happens.


I have tried Final Fantasy VII in the past which I think was OK... Tomb Raider is currently broken because one of the CdlGetLocs is not done right - I wrote a C emu in the past, and had exactly the same problem... I should fix!
