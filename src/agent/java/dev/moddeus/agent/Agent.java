package dev.moddeus.agent;

import java.lang.instrument.Instrumentation;

/** Loaded by -javaagent. Stashes Instrumentation where any classloader (incl. Fabric's Knot) can reach it. */
public final class Agent {
	public static void premain(String args, Instrumentation inst) {
		System.getProperties().put("moddeus.instrumentation", inst);
	}

	public static void agentmain(String args, Instrumentation inst) {
		premain(args, inst);
	}
}
