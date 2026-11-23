package jdk.test.whitebox;

/**
 * Minimal stub of HotSpot's WhiteBox test API (loaded from -Xbootclasspath/a with -XX:+WhiteBoxAPI). The VM binds the
 * natives declared here (it warns about the ones this stub leaves out). HotReloader uses it to switch
 * AllowEnhancedClassRedefinition off around a swap: method-body changes then take ~1 ms instead of a 200-800 ms
 * heap walk; schema changes (new fields/methods) fail fast and are retried with the flag on.
 */
public class WhiteBox {
	private static native void registerNatives();
	static { registerNatives(); }
	public native void setBooleanVMFlag(String name, boolean value);
	public native Boolean getBooleanVMFlag(String name);
	public static final WhiteBox WB = new WhiteBox();
}
