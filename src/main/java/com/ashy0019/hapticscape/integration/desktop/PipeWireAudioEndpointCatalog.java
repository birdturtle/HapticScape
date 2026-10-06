package com.ashy0019.hapticscape.integration.desktop;

import com.ashy0019.hapticscape.music.AudioCaptureEndpoint;
import com.ashy0019.hapticscape.music.AudioCaptureEndpointCatalog;
import java.util.List;

/** Discovery is invoked by the existing background audio-source scan. */
public final class PipeWireAudioEndpointCatalog implements AudioCaptureEndpointCatalog
{
	@Override
	public List<AudioCaptureEndpoint> listActiveEndpoints()
	{
		try (PipeWireProcesses helpers = new PipeWireProcesses())
		{
			return snapshot(helpers).endpoints();
		}
	}
	static PipeWireGraph snapshot(PipeWireProcesses helpers)
	{
		return new PipeWireGraph(helpers.query(List.of("pw-dump", "--no-colors"), 4 * 1024 * 1024));
	}
}
