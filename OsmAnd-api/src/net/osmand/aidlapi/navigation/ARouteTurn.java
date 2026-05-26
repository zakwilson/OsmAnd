package net.osmand.aidlapi.navigation;

import android.os.Bundle;
import android.os.Parcel;

import net.osmand.aidlapi.AidlParams;

public class ARouteTurn extends AidlParams {

	private int turnType;
	private String instructionText = "";
	private String streetName = "";
	private int distanceFromStartM;
	private int distanceToNextTurnM;
	private double lat;
	private double lon;
	private int exitNumber;

	public ARouteTurn() {

	}

	protected ARouteTurn(Parcel in) {
		readFromParcel(in);
	}

	public static final Creator<ARouteTurn> CREATOR = new Creator<ARouteTurn>() {
		@Override
		public ARouteTurn createFromParcel(Parcel in) {
			return new ARouteTurn(in);
		}

		@Override
		public ARouteTurn[] newArray(int size) {
			return new ARouteTurn[size];
		}
	};

	public int getTurnType() {
		return turnType;
	}

	public void setTurnType(int turnType) {
		this.turnType = turnType;
	}

	public String getInstructionText() {
		return instructionText;
	}

	public void setInstructionText(String instructionText) {
		this.instructionText = instructionText == null ? "" : instructionText;
	}

	public String getStreetName() {
		return streetName;
	}

	public void setStreetName(String streetName) {
		this.streetName = streetName == null ? "" : streetName;
	}

	public int getDistanceFromStartM() {
		return distanceFromStartM;
	}

	public void setDistanceFromStartM(int distanceFromStartM) {
		this.distanceFromStartM = distanceFromStartM;
	}

	public int getDistanceToNextTurnM() {
		return distanceToNextTurnM;
	}

	public void setDistanceToNextTurnM(int distanceToNextTurnM) {
		this.distanceToNextTurnM = distanceToNextTurnM;
	}

	public double getLat() {
		return lat;
	}

	public void setLat(double lat) {
		this.lat = lat;
	}

	public double getLon() {
		return lon;
	}

	public void setLon(double lon) {
		this.lon = lon;
	}

	public int getExitNumber() {
		return exitNumber;
	}

	public void setExitNumber(int exitNumber) {
		this.exitNumber = exitNumber;
	}

	@Override
	protected void readFromBundle(Bundle bundle) {
		turnType = bundle.getInt("turnType");
		String it = bundle.getString("instructionText");
		instructionText = it == null ? "" : it;
		String sn = bundle.getString("streetName");
		streetName = sn == null ? "" : sn;
		distanceFromStartM = bundle.getInt("distanceFromStartM");
		distanceToNextTurnM = bundle.getInt("distanceToNextTurnM");
		lat = bundle.getDouble("lat");
		lon = bundle.getDouble("lon");
		exitNumber = bundle.getInt("exitNumber");
	}

	@Override
	public void writeToBundle(Bundle bundle) {
		bundle.putInt("turnType", turnType);
		bundle.putString("instructionText", instructionText);
		bundle.putString("streetName", streetName);
		bundle.putInt("distanceFromStartM", distanceFromStartM);
		bundle.putInt("distanceToNextTurnM", distanceToNextTurnM);
		bundle.putDouble("lat", lat);
		bundle.putDouble("lon", lon);
		bundle.putInt("exitNumber", exitNumber);
	}
}
