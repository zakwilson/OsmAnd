package net.osmand.plus.routing;

public class NextDirectionInfo {

	public RouteDirectionInfo directionInfo;
	public int distanceTo;
	public boolean intermediatePoint;
	public String pointName;
	public int imminent;
	protected int directionInfoInd;

	/**
	 * Index of {@link #directionInfo} in the raw {@link RouteCalculationResult} directions
	 * list. Exposed as a public accessor so the in-process GlassNav plugin can correlate
	 * {@link RoutingHelper#getNextRouteDirectionInfo(NextDirectionInfo, boolean)} results
	 * with the route's turn list.
	 */
	public int getDirectionInfoInd() {
		return directionInfoInd;
	}
}
