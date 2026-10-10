package com.ashy0019.hapticscape.integration.desktop;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;
import static org.junit.Assert.*;

public class PipeWirePcmTest
{
	@Test
	public void decodesKnownStereoSignalAcrossFragmentedReads() throws Exception
	{
		ByteBuffer data = ByteBuffer.allocate(800).order(ByteOrder.nativeOrder());
		float[] expected = new float[100];
		for (int i = 0; i < 100; i++)
		{
			float sine = (float) Math.sin(i * 0.1);
			data.putFloat(sine).putFloat(sine * 0.5f);
			expected[i] = sine * 0.75f;
		}
		InputStream fragmented = new FilterInputStream(new ByteArrayInputStream(data.array()))
		{
			@Override public int read(byte[] bytes, int offset, int size) throws IOException { return super.read(bytes, offset, Math.min(size, 3)); }
		};
		assertArrayEquals(expected, PipeWirePcm.read(fragmented), 0.000001f);
		assertNull(PipeWirePcm.read(fragmented));
	}

	@Test
	public void sanitizesNonfiniteAndOutOfRangeSamples() throws Exception
	{
		ByteBuffer data = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder());
		data.putFloat(Float.NaN).putFloat(Float.POSITIVE_INFINITY).putFloat(2).putFloat(0);
		assertArrayEquals(new float[] {0, 0.5f}, PipeWirePcm.read(new ByteArrayInputStream(data.array())), 0);
	}

	@Test(expected = EOFException.class)
	public void rejectsPartialStereoFrame() throws Exception { PipeWirePcm.read(new ByteArrayInputStream(new byte[9])); }

	@Test
	public void decodesMonoAndMultichannelFrames() throws Exception
	{
		ByteBuffer mono = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putFloat(.25f).putFloat(-.5f);
		assertArrayEquals(new float[] { .25f, -.5f }, PipeWirePcm.read(new ByteArrayInputStream(mono.array()), 1), 0);
		ByteBuffer surround = ByteBuffer.allocate(24).order(ByteOrder.nativeOrder());
		for (float value : new float[] { 1, .5f, 0, -.5f, -1, Float.NaN }) surround.putFloat(value);
		assertArrayEquals(new float[] { 0 }, PipeWirePcm.read(new ByteArrayInputStream(surround.array()), 6), 0);
	}

	@Test(expected = EOFException.class)
	public void rejectsTruncatedMultichannelFrame() throws Exception
	{
		PipeWirePcm.read(new ByteArrayInputStream(new byte[25]), 6);
	}
}
