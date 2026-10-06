package com.ashy0019.hapticscape.integration.desktop;

import com.ashy0019.hapticscape.music.AudioCaptureApplication;
import com.ashy0019.hapticscape.music.AudioCaptureApplicationCatalog;
import java.util.List;

/** Playback discovery runs on the existing background source-scan worker. */
public final class PipeWireAudioApplicationCatalog implements AudioCaptureApplicationCatalog
{
	@Override
	public List<AudioCaptureApplication> listActiveApplications()
	{
		try (PipeWireProcesses helpers = new PipeWireProcesses())
		{
			return PipeWireApplicationGraph.snapshot(helpers).applications();
		}
	}
}
