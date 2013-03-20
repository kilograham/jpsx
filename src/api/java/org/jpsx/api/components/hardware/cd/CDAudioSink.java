/*
Copyright (C) 2007 graham sanderson

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation; either version 2
of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
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
