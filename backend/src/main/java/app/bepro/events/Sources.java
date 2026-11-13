package app.bepro.events;

public final class Sources {

	public static final String HEALTH_CONNECT = "health_connect";

	public static final String USAGE_STATS = "usage_stats";

	public static final String ACTIVITY = "activity";

	public static final String MANUAL = "manual";

	public static final String CALENDAR = "calendar";

	/** The phone's hardware step counter. */
	public static final String SENSOR = "sensor";

	public static final String PATTERN = "health_connect|usage_stats|activity|manual|calendar|sensor";

	private Sources() {
	}

}
