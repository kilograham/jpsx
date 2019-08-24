# JPSX

JPSX is a fast and efficient PlayStation emulator written entirely in Java. You can view an old demo of its abilities and performance [on YouTube](https://www.youtube.com/watch?v=_8oyzLJHhS0). If it doesn't work at least that well on your computer for those games, there might be an issue with your runtime environment!

That said the code was written a long time ago, and I finally gave up trying to find time to get around to fixing a bunch of embarrassing or old things
up and have just open sourced it as people frequently ask (albeit about 14 years later than planned).

The rest of these instructions are old, but you get the idea;
 
I just tried, and am able to run on my MacBook Pro with JDK8, and Ubuntu 18.04 with JDK8 and JDK11.

`./unix.sh` (which launches you into console from where you have to press `g`)
or `./unix.sh launch` which launches anyway; pass `image=foo.cue` to run a particular game.

Usually the biggest problem nowadays is speed of blit via AWT path (which used to work fine years ago), however
it looks like LWJGL path is currently broken (`./unix.sh lwjgl`) except perhaps for older OSes but the pure Java 
blit on Mac seems just fine. Your mileage on other platforms may vary. (Update, Ubuntu seems fine, and Raspbian on
Raspberry Pi 4 has proper copy up with openjdk-11-jre; with JDK 8 You get 30ms blit rather than about 0.2ms). Note that 
for Raspberry Pi 4 you currently need to pass `no-sound` on the command line as the machine name, because there don't
seem to be enough channels in JavaSound to use them the way the default SPU component does).

Now this is up here, I'll try to at least add some issues that need fixing or changes that I planned to make. Note that JPSX
will certainly play quite a lot of games correctly, but may fail miserably on others. I recommend passing
`speculativeExecution=false` on the command line if your game doesn't work (e.g. Final Fantasy VII); speculative execution
should only really be needed on very slow machines, and breaks some code that uses overlays.

## Building the Emulator

JPSX is very simple to build. Just run `ant`, and it will build `jar` files in the `ship` folder.

## Running the Emulator

To run JPSX in its default configuration, run the following command:

```
java -XX:-DontCompileHugeMethods -XX:-UseSplitVerifier -XX:-OmitStackTraceInFastThrow -jar ship/jpsx.jar image=path/to/game.cue
```

Note that JPSX does not have a GUI; you must launch it from the command line. Also, you need a PlayStation BIOS image named `bios.bin`.

Right now CUE/BIN CD image files are the only image format supported (though it should be easy to add support for additional formats). Note that you can (and it is quite gratifying) use the CD player in the BIOS if you provide a CUE/BIN image of a music CD.

### Configuration Options

JPSX supports a bunch of configuration options in `jpsx.xml`. Configuration options are encapsulated within a *machine* XML tag. You can specify a machine to use on the command line like so:

```
java -XX:-DontCompileHugeMethods -XX:-UseSplitVerifier -XX:-OmitStackTraceInFastThrow -jar ship/jpsx.jar [machine name] image=path/to/game.cue
```

**Once you launch JPSX, you need to type `g` into the console to start the emulator.**

By default, JPSX uses the "default" machine defined in `jpsx.xml`. Each machine lists the components that make it up, possible including (and optionally overriding) components from another machine definition. This makes it easy to tweak components; simply define a new machine with the properties you want.

For configuration options that use the `lwjgl` display class, you need to use the following command line instead (note this probably no longer runs):

```
java -XX:-DontCompileHugeMethods -XX:-UseSplitVerifier -XX:-OmitStackTraceInFastThrow -Djava.library.path=external/lwjgl-2.9.1/native/[platformname] -cp ship/jpsx.jar:ship/jpsx-lwjgl.jar [machine name] image=path/to/game.cue
```

### UNIX Frontend Script

`unix.sh` is provided as an example script for OS X and UNIX

For example, `./unix.sh image=/rips/gt.cue` will 
set the image property used by the CUE/BIN CD drive to a specific file (note I think right not that the CUE must specify an absolute path to the BIN file).

For example, `./unix.sh lwjgl` will use the machine definition called *lwjgl* that uses `LWJGLDisplay` in place of `AWTDisplay`

### What's With The -XX Command Line Arguments

The following are required command line options for HotSpot, or things won't work properly/at all.

<table>
  <tr><th>Option</th><th>Description</th></tr>
  <tr><td><pre>-XX:-DontCompileHugeMethods</pre></td><td>Needed on everything otherwise things may be slow. Otherwise, HotSpot will refuse to compile some of the byte code that JPSX generates.</td></tr>
  <tr><td><pre>-XX:-UseSplitVerifier</pre></td><td>needed on JDK7+ (current BCEL code gen isnt' supported by JVM otherwise)</td></tr>
  <tr><td><pre>-XX:-OmitStackTraceInFastThrow</pre></td><td>needed on newer JVMs which otherwise break JPSX by removing required line number information</td></tr>
</table>

### Keys

The default machine definition includes a `Console` component... this is interactive sort of like `gdb`. So to get stuff to run in this mode you need to enter `g` for go. You can look at the code for Console to figure out some other commands. `b` breaks for example

By default the pad is controlled by giving focus to the display window.

These are the default mappings for controller 1 copied from `AWTKeyboardController`, which you can currently only change in the source:


<table>
    <tr><th>Button</th><th>Key</th><th></th><th>Button</th><th>Key</th></tr>
    <tr><td>⬆</td><td><kbd>⬆</kbd></td><td></td><td>△</td><td><kbd>8</kbd> or keypad <kbd>⬆</kbd></td></tr>
    <tr><td>➡</td><td><kbd>➡</kbd></td><td></td><td>ⓞ</td><td><kbd>I</kbd> or keypad <kbd>➡</kbd></td></tr>
    <tr><td>⬇</td><td><kbd>⬇</kbd></td><td></td><td>ⓧ</td><td><kbd>K</kbd> or keypad <kbd>⬇</kbd></td></tr>
    <tr><td>⬅</td><td><kbd>⬅</kbd></td><td></td><td>□</td><td><kbd>U</kbd> or keypad <kbd>⬅</kbd></td></tr>
    <tr><td><kbd>L1</kbd></td><td><kbd>1</kbd></td><td></td><td><kbd>R1</kbd></td><td> <kbd>2</kbd></td></tr>
    <tr><td><kbd>L2</kbd></td><td><kbd>Q</kbd></td><td></td><td><kbd>R2</kbd></td><td><kbd>W</kbd></td></tr>
    <tr><td>Select</td><td><kbd>S</kbd></td><td></td><td>Start</td><td><kbd>Space</kbd></td></tr>
</table>


The current displays also supports a few keys (don't forget <kbd>fn</kbd> on OS X).

* **<kbd>F12</kbd>**: Change window size (picks from a preset list of resolutions).
* **<kbd>F9</kbd>**: Toggle display of all VRAM - this is kinda cool.

## History

I wrote it back in 2003 basically just because it was exactly the sort of thing people were saying Java was too slow for. I had written a C/C++ emu in the late 1990s
that I never made publicly available (though some of the code found a home), so I had already done a bunch of the reverse engineering work.

I have actually done very little to it over time, other than periodically trying it on new JVMs (it should work on anything JDK1.4+, though JDK5 is now probably a sensible minimum).
I also gave a [talk on it at JavaOne in 2006](http://docs.huihoo.com/javaone/2006/cool-stuff/ts-5547.pdf), and have been meaning to open source it ever since (though it had so many little things wrong with it that annoyed me, which I had never gotten around to fixing, so it never happened until now). Thanks to [John Vilk](https://github.com/jvilk) for catching me at the right time and convincing me to open source it in all its warty glory.

### Philosophy

Since I was writing it in Java, I thought it'd be nice to make it do it in an object oriented style. Therefore there are well encapsulated classes representing the different physical hardware and internal emulation components.

That said, this was written back with the HotSpot client compiler in mind (the server compiler caused way too many compilation pauses back then for game use), so generally I always picked runtime speed over pretty code.

There are all kinds of optimizations in there (you can read about some of it [here](http://docs.huihoo.com/javaone/2006/cool-stuff/ts-5547.pdf)).
Some still make sense, but others are no longer necessary on modern versions of HotSpot.
Those which are stupid on a modern JVM will probably be removed, though equally I expect that this codebase is likely to be used on lower spec Java platforms in the future where a lot of this stuff will still matter, so I don't expect to rip it all out if it doesn't actively hurt.

### PSX a different platform; JPSX a different style of emulator

At least at the time... there seemed to be a lot of focus in emulators about cycle counting. This certainly makes
sense in a lot of cases especially on older platforms where, for example, the program needed to know where the CRT electron beam was when a cycle executed,
or it needed to write a sound sample out at an exact moment.

The PSX appeared to be a new breed of gaming machine, with a more mature set of components loosely coupled (via IRQ and DMA) which otherwise carried on largely
independently. For that reason I decided to emulate each component largely independently too. Sometimes in separate threads, sometimes synced to external 
timing events, but coupled via Java synchronization. As such, for example, the R3000 CPU emulation (well more accurately R300 code is transcoded/recompiled/reoptimized
 into bytecode) runs as fast as it can and then blocks.

This turns out to work remarkably well. Of course, JPSX has to detect busy wait loops so as not to hog (busy wait on) the native CPU, and because the PSX level loop timeouts are WAY too short for native hardware.

With that said, there are badly written bits of PSX code with race conditions which need to be patched or worked around, and some perhaps badly designed hardware
(I spent a LONG time trying to figure out a robust interaction between the CPU thread and CD controller which it seems may never have existed).
Despite this, unless it proves impossible, I'd like JPSX to continue with the same basic design of largely independent components, as this particular design is the reason why JPSX is lean and efficient.

## Implementation

JPSX is built entirely of pluggable components. If you look at `jpsx.xml`, you'll see the default definitions that I use.
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

## Documentation

todo

Note here is a 2006 JavaOne talk I gave on JPSX: [JPSX JavaOne Talk](javaone.pdf)
## Thanks

Thanks to John Vilk https://github.com/jvilk for his help in getting me closer to open sourcing a few years back!