#!/bin/sh
# for a particular java version
#export JAVA_HOME=`/usr/libexec/java_home -v 1.6`
JAVA_LIBRARY_PATH=
CLASS_PATH=ship/jpsx.jar
REQUIRED_HOTSPOT_OPTIONS="-XX:-UseSplitVerifier -XX:-OmitStackTraceInFastThrow -XX:-DontCompileHugeMethods"

# Optional: only needed if you want LWJGL (pass lwjgl to pick machine from jpsx.xml)
JAVA_LIBRARY_PATH=$JAVA_LIBRARY_PATH:external/lwjgl-2.9.1/native/macosx
CLASS_PATH=$CLASS_PATH:ship/jpsx-lwjgl.jar:external/lwjgl-2.9.1/jar/lwjgl.jar

#java -version
java $REQUIRED_HOTSPOT_OPTIONS -Djava.library.path=$JAVA_LIBRARY_PATH -Xmx256M -classpath $CLASS_PATH org.jpsx.bootstrap.JPSXLauncher $*
