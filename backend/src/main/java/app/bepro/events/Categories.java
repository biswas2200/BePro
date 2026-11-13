package app.bepro.events;

public final class Categories {

	public static final String SLEEP = "sleep";

	/** Per-app foreground session; sub_category is the package name. */
	public static final String USAGE = "usage";

	/** sub_category "session" (screen on → off) or "unlock" (point event). */
	public static final String SCREEN = "screen";

	public static final String STEPS = "steps";

	public static final String FOCUS = "focus";

	public static final String MOOD = "mood";

	/** Activity-recognition span; sub_category is walking | running | on_bicycle | in_vehicle | still. */
	public static final String ACTIVITY = "activity";

	public static final String LOCATION = "location";

	public static final String PATTERN = "sleep|usage|screen|steps|focus|mood|activity|location";

	public static final String SCREEN_SESSION = "session";

	public static final String SCREEN_UNLOCK = "unlock";

	private Categories() {
	}

}
