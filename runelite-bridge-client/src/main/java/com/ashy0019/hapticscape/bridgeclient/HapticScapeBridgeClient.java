package com.ashy0019.hapticscape.bridgeclient;

import com.ashy0019.localeventbridge.LocalEventBridgePlugin;
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Minimal packaged RuneLite entry point used when the Local Event Bridge is
 * not installed through the RuneLite Plugin Hub.
 */
public final class HapticScapeBridgeClient
{
    private HapticScapeBridgeClient()
    {
    }

    public static void main(String[] args) throws Exception
    {
        try
        {
            verifyRuntime();
            if (args.length == 1 && "--verify-runtime".equals(args[0]))
            {
                System.out.println("LumBridge Java runtime verified.");
                return;
            }
            ExternalPluginManager.loadBuiltin(LocalEventBridgePlugin.class);
            RuneLite.main(args);
        }
        catch (Throwable failure)
        {
            // RuneLite starts non-daemon preload threads before initializing its
            // UI. A fatal main-thread failure must not leave a phantom client.
            failure.printStackTrace(System.err);
            System.exit(1);
        }
    }

    static void verifyRuntime() throws ClassNotFoundException
    {
        for (String name : new String[] {
            "com.sun.net.httpserver.HttpServer", "jdk.jshell.JShell",
            "com.sun.tools.attach.VirtualMachine", "com.sun.management.OperatingSystemMXBean",
            "netscape.javascript.JSObject", "java.awt.Toolkit", "java.sql.Driver",
            "javax.naming.InitialContext", "java.rmi.Remote"
        })
        {
            Class.forName(name, false, HapticScapeBridgeClient.class.getClassLoader());
        }
        if (javax.tools.ToolProvider.getSystemJavaCompiler() == null)
        {
            throw new IllegalStateException("LumBridge requires the jdk.compiler runtime module.");
        }
    }
}
