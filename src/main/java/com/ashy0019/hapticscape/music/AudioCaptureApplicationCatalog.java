package com.ashy0019.hapticscape.music;

import java.util.Collections;
import java.util.List;

/** Lists local applications currently represented in the desktop playback graph. */
@FunctionalInterface
public interface AudioCaptureApplicationCatalog
{
	List<AudioCaptureApplication> listActiveApplications();

	static AudioCaptureApplicationCatalog empty()
	{
		return Collections::emptyList;
	}
}
