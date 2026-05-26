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
	 * list. Exposed for the glass-nav fork's AIDL: it indexes the same list returned by
	 * {@link RouteCalculationResult#getOriginalRouteDirections()}.
	 */
	public int getDirectionInfoInd() {
		return directionInfoInd;
	}
}
