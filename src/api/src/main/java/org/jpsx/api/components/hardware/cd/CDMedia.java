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
package org.jpsx.api.components.hardware.cd;

public interface CDMedia {
    public enum TrackType {
        UNKNOWN, MODE2_2352, AUDIO
    }

    int getFirstTrack();

    int getLastTrack();

    int getTrackMSF(int track);

    TrackType getTrackType(int track);

    void readSector(int sectorNumber, byte[] buffer) throws MediaException;

    /**
     * @param num
     * @param buffer
     * @throws MediaException
     * @deprecated just for use of old cd stuff
     */
    void readSector(int num, int[] buffer) throws MediaException;
}
