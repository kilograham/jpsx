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
package org.jpsx.runtime.components.hardware.media;

import org.apache.log4j.Logger;
import org.jpsx.api.components.hardware.cd.CDDrive;
import org.jpsx.api.components.hardware.cd.CDMedia;
import org.jpsx.api.components.hardware.cd.MediaException;
import org.jpsx.runtime.SingletonJPSXComponent;
import org.jpsx.runtime.components.hardware.HardwareComponentConnections;
import org.jpsx.runtime.util.CDUtil;
import org.jpsx.runtime.util.MiscUtil;

import java.io.*;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.LineNumberReader;
import java.io.RandomAccessFile;

public class CueBinImageDrive extends SingletonJPSXComponent implements CDDrive {
    public static final String PROPERTY_IMAGE_FILE = "image";

    private static final String CATEGORY = "CDImage";
    private static final Logger log = Logger.getLogger(CATEGORY);
    private static final String DEFAULT_CUE_FILE = "/rips/wipeoutxl.cue";
    private CDMedia currentMedia;

    private boolean refreshed = false;

    public CueBinImageDrive() {
        super("JPSX CUE/BIN Image CD Drive");
    }

    public void init() {
        super.init();
        HardwareComponentConnections.CD_DRIVE.set(this);
    }

    public CDMedia getCurrentMedia() {
        if (!refreshed) {
            refreshMedia();
            refreshed = true;
        }
        return currentMedia;
    }

    public boolean isDriveOpen() {
        return false;
    }

    public void refreshMedia() {
        String cueFilename = getProperty(PROPERTY_IMAGE_FILE, DEFAULT_CUE_FILE);
        currentMedia = CueBinImageMedia.create(cueFilename);
    }

    private static class CueBinImageMedia implements CDMedia {
        int first;
        int last;
        int[] msf = new int[100];
        TrackType[] trackType = new TrackType[100];
        byte[] byteBuf = new byte[2352];
        RandomAccessFile binFile;

        private CueBinImageMedia() {
        }

        public TrackType getTrackType(int track) {
            return trackType[track];
        }

        public static CueBinImageMedia create(String cueFilename) {
            CueBinImageMedia rc = new CueBinImageMedia();
            if (!rc.parse(cueFilename)) {
                return null;
            }
            return rc;
        }

        private boolean parse(String cueFilename) {
            LineNumberReader reader;
            try {
                reader = new LineNumberReader(new FileReader(cueFilename));
            } catch (IOException e) {
                log.warn("Unable to open CUE file " + cueFilename+" e "+e.getMessage());
                return false;
            }
            String binFilename = cueFilename;
            
            int offset = 150;
            try {
                first = 99;
                last = 0;
                int dot = binFilename.lastIndexOf(".");
                if (dot >= 0) binFilename = binFilename.substring(0, dot) + ".bin";
                
                String line = reader.readLine();
                int trackNum = -1;
                while (line != null) {
                    line = line.trim();
                    String uline = line.toUpperCase();
                    if (uline.startsWith("TRACK")) {
                        trackNum = readNumber(line.substring(5));
                        if (trackNum < first) first = trackNum;
                        if (trackNum > last) last = trackNum;
                        String typeString = uline.substring(8).trim();
                        trackType[trackNum] = CDMedia.TrackType.UNKNOWN;
                        if (typeString.equals("MODE2/2352")) {
                            trackType[trackNum] = CDMedia.TrackType.MODE2_2352;
                        } else if (typeString.equals("AUDIO")) {
                            trackType[trackNum] = CDMedia.TrackType.AUDIO;
                        }
                    } else if (uline.startsWith("INDEX 01")) {
                        msf[trackNum] = toMSF(parseMSFStringAsLBA(line.substring(8)) + offset);
                    } else if (uline.startsWith("FILE")) {
                        int q1 = line.indexOf("\"");
                        if (q1 >= 0) {
                            int q2 = line.indexOf("\"", q1 + 1);
                            if (q2 >= q1) {
                                binFilename = binFilename.substring(0, binFilename.lastIndexOf(File.separator) + 1) + line.substring(q1 + 1, q2);
                            }
                        }
                    }
                    line = reader.readLine();
                }
            } catch (IOException e) {
                log.warn("Error reading CUE file " + cueFilename);
                return false;
            } finally {
                try {
                    reader.close();
                } catch (IOException e) {
                }
            }
            binFile = null;
            long length;
            log.warn("== "+binFilename);
            try {
                binFile = new RandomAccessFile(binFilename, "r");
                length = binFile.length();
            } catch (IOException e) {
                log.warn("Unable to open BIN file " + binFilename+" e "+e.getMessage());
                return false;
            }
            msf[0] = toMSF(offset + (int) (length / 2352L));
            if (log.isDebugEnabled()) {
                for (int i = first; i <= last; i++) {
                    log.debug("track " + i + " " + printMSF(msf[i]));
                }
                log.debug("end " + printMSF(msf[0]));
            }
            return true;
        }

        public void readSector(int num, byte[] buffer) throws MediaException {
            try {
                // note findbugs complains about this, but we know that the value can't overflow
                binFile.seek(num * 2352);
                binFile.readFully(buffer, 0, 2352);
            } catch (IOException e) {
                throw new MediaException("readSector failed", e);
            }
        }

        public void readSector(int num, int[] buffer) throws MediaException {
            try {
                // note findbugs complains about this, but we know that the value can't overflow
                binFile.seek(num * 2352);
                binFile.readFully(byteBuf);
                for (int i = 0; i < 2352 / 4; i++) {
                    buffer[i] = ((((int) byteBuf[i * 4 + 3]) & 0xff) << 24) |
                            ((((int) byteBuf[i * 4 + 2]) & 0xff) << 16) |
                            ((((int) byteBuf[i * 4 + 1]) & 0xff) << 8) |
                            ((((int) byteBuf[i * 4]) & 0xff));
                }
            } catch (IOException e) {
                throw new MediaException("readSector failed", e);
            }
        }

        public int getFirstTrack() {
            return first;
        }

        public int getLastTrack() {
            return last;
        }

        public int getTrackMSF(int trackIndex) {
            return msf[trackIndex];
        }

        static int toMSF(int sector) {
            int f = sector % 75;
            sector /= 75;
            int s = sector % 60;
            sector /= 60;
            int m = sector;
            return (CDUtil.toBCD(m) << 16) | (CDUtil.toBCD(s) << 8) | CDUtil.toBCD(f);
        }

        static String printMSF(int msf) {
            int m = (msf & 0xff0000) >> 16;
            int s = (msf & 0xff00) >> 8;
            int f = msf & 0xff;
            return MiscUtil.toHex(m, 2) + ":" + MiscUtil.toHex(s, 2) + ":" + MiscUtil.toHex(f, 2);
        }

        static int readNumber(String s) {
            // read a two digit number
            int rc = 0;
            s = s.trim();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (!Character.isDigit(c)) break;
                rc = rc * 10 + (c - '0');
            }
            return rc;
        }

        static int parseMSFStringAsLBA(String msf) {
            msf = msf.trim();
            int c1 = msf.indexOf(':');
            int c2 = msf.indexOf(':', c1 + 1);
            int m = readNumber(msf.substring(0, c1));
            int s = readNumber(msf.substring(c1 + 1, c2));
            int f = readNumber(msf.substring(c2 + 1));
            return ((m * 60) + s) * 75 + f;
        }

    }

}
