package com.ashy0019.hapticscape.integration.desktop;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** pw-cat raw f32 is interleaved native-endian PCM, negotiated as 48 kHz stereo. */
final class PipeWirePcm
{
	static final int SAMPLE_RATE = 48000;
	static final int FRAME_BYTES = 8;
	static float[] read(InputStream stream) throws IOException
	{
		byte[] bytes = new byte[480 * FRAME_BYTES];
		int offset = 0;
		while (offset < bytes.length)
		{
			int read = stream.read(bytes, offset, bytes.length - offset);
			if (read == -1)
			{
				if (offset == 0) return null;
				if (offset % FRAME_BYTES != 0) throw new EOFException("Truncated PipeWire PCM frame");
				break;
			}
			offset += read;
		}
		ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, offset).order(ByteOrder.nativeOrder());
		float[] mono = new float[offset / FRAME_BYTES];
		for (int i = 0; i < mono.length; i++)
		{
			float left = buffer.getFloat(), right = buffer.getFloat();
			mono[i] = (finite(left) + finite(right)) * 0.5f;
		}
		return mono;
	}
	private static float finite(float value)
	{
		return Float.isFinite(value) ? Math.max(-1f, Math.min(1f, value)) : 0f;
	}
}
