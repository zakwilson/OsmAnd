package net.osmand.aidlapi.navigation;

import android.os.Bundle;
import android.os.Parcel;

import net.osmand.aidlapi.AidlParams;
import net.osmand.aidlapi.map.ALatLon;

import java.util.ArrayList;

public class ARoute extends AidlParams {

	private ArrayList<ALatLon> polyline = new ArrayList<>();
	private int totalDistanceM;
	private int totalTimeSec;
	private ArrayList<ARouteTurn> turns = new ArrayList<>();

	public ARoute() {

	}

	protected ARoute(Parcel in) {
		readFromParcel(in);
	}

	public static final Creator<ARoute> CREATOR = new Creator<ARoute>() {
		@Override
		public ARoute createFromParcel(Parcel in) {
			return new ARoute(in);
		}

		@Override
		public ARoute[] newArray(int size) {
			return new ARoute[size];
		}
	};

	public ArrayList<ALatLon> getPolyline() {
		return polyline;
	}

	public void setPolyline(ArrayList<ALatLon> polyline) {
		this.polyline = polyline == null ? new ArrayList<ALatLon>() : polyline;
	}

	public int getTotalDistanceM() {
		return totalDistanceM;
	}

	public void setTotalDistanceM(int totalDistanceM) {
		this.totalDistanceM = totalDistanceM;
	}

	public int getTotalTimeSec() {
		return totalTimeSec;
	}

	public void setTotalTimeSec(int totalTimeSec) {
		this.totalTimeSec = totalTimeSec;
	}

	public ArrayList<ARouteTurn> getTurns() {
		return turns;
	}

	public void setTurns(ArrayList<ARouteTurn> turns) {
		this.turns = turns == null ? new ArrayList<ARouteTurn>() : turns;
	}

	@Override
	protected void readFromBundle(Bundle bundle) {
		bundle.setClassLoader(ARoute.class.getClassLoader());
		ArrayList<ALatLon> p = bundle.getParcelableArrayList("polyline");
		polyline = p == null ? new ArrayList<ALatLon>() : p;
		totalDistanceM = bundle.getInt("totalDistanceM");
		totalTimeSec = bundle.getInt("totalTimeSec");
		ArrayList<ARouteTurn> t = bundle.getParcelableArrayList("turns");
		turns = t == null ? new ArrayList<ARouteTurn>() : t;
	}

	@Override
	public void writeToBundle(Bundle bundle) {
		bundle.putParcelableArrayList("polyline", polyline);
		bundle.putInt("totalDistanceM", totalDistanceM);
		bundle.putInt("totalTimeSec", totalTimeSec);
		bundle.putParcelableArrayList("turns", turns);
	}
}
