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

public interface CDAudioSink {

    public int getCDAudioLatency();

    public void setCDAudioRate(int hz);

    public void newCDAudio();

    /**
     * Handle CD Audio data in 2 channel 16 bit sigend little endian format
     *
     * @param data
     * @return true if the audio is now playing
     */
    public boolean cdAudioData(byte[] data, int offset, int length);

    public void setExternalCDAudioVolumeLeft(int vol);

    public void setExternalCDAudioVolumeRight(int vol);

    public boolean isCDAudible();
}
