package com.ashy0019.hapticscape.integration.desktop;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Interleaved native-endian f32 PCM at 48 kHz; downmix the negotiated channel count. */
final class PipeWirePcm
{
	static final int SAMPLE_RATE = 48000;
	static final int FRAME_BYTES = 8;
	static float[] read(InputStream stream) throws IOException
	{
		return read(stream, 2);
	}

	static float[] read(InputStream stream, int channels) throws IOException
	{
		if (channels < 1 || channels > 32) throw new IllegalArgumentException("Invalid PipeWire PCM channel count");
		int frameBytes = channels * Float.BYTES;
		byte[] bytes = new byte[480 * frameBytes];
		int offset = 0;
		while (offset < bytes.length)
		{
			int read = stream.read(bytes, offset, bytes.length - offset);
			if (read == -1)
			{
				if (offset == 0) return null;
				if (offset % frameBytes != 0) throw new EOFException("Truncated PipeWire PCM frame");
				break;
			}
			offset += read;
		}
		ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, offset).order(ByteOrder.nativeOrder());
		float[] mono = new float[offset / frameBytes];
		for (int i = 0; i < mono.length; i++)
		{
			float sum = 0;
			for (int channel = 0; channel < channels; channel++) sum += finite(buffer.getFloat());
			mono[i] = sum / channels;
		}
		return mono;
	}
	private static float finite(float value)
	{
		return Float.isFinite(value) ? Math.max(-1f, Math.min(1f, value)) : 0f;
	}
}
