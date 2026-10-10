package com.ashy0019.hapticscape.integration.desktop;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class LinuxTrayMenuTest
{
	@Test
	public void exposesOpenSeparatorAndExitWithStableIds()
	{
		String layout = LinuxTrayMenu.layout(0, -1, List.of());
		assertTrue(layout.contains("Open HapticScape"));
		assertTrue(layout.contains("separator"));
		assertTrue(layout.contains("Exit"));
		assertEquals("'Open HapticScape'", LinuxTrayMenu.properties(1).get("label"));
		assertEquals("'Exit'", LinuxTrayMenu.properties(3).get("label"));
	}

	@Test
	public void honorsDepthAndRequestedProperties()
	{
		assertFalse(LinuxTrayMenu.layout(0, 0, List.of()).contains("Open HapticScape"));
		String filtered = LinuxTrayMenu.layout(0, 1, List.of("label"));
		assertTrue(filtered.contains("Open HapticScape"));
		assertFalse(filtered.contains("enabled"));
		assertEquals("{}", LinuxTrayMenu.dictionary(1, List.of("unknown")));
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsInvalidParent() { LinuxTrayMenu.layout(4, -1, List.of()); }

	@Test
	public void escapesVariantStrings()
	{
		assertEquals("'a\\'b\\\\c\\n'", LinuxTrayMenu.quote("a'b\\c\n"));
	}
}
