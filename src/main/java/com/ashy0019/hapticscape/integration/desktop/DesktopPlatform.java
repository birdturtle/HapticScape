package com.ashy0019.hapticscape.integration.desktop;

import com.sun.jna.Platform;

/** Platform selection only; does not load native capture or protection libraries. */
enum DesktopPlatform
{
	WINDOWS, LINUX, UNSUPPORTED;

	static DesktopPlatform current()
	{
		return Platform.isWindows() ? WINDOWS
			: Platform.isLinux() ? LINUX : UNSUPPORTED;
	}
}
