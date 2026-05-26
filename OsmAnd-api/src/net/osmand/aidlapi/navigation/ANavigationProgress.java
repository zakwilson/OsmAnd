package net.osmand.aidlapi.navigation;

import android.os.Bundle;
import android.os.Parcel;

import net.osmand.aidlapi.AidlParams;

public class ANavigationProgress extends AidlParams {

	private double currentLat;
	private double currentLon;
	private float bearingDeg;
	private float speedKmh;
	private int remainingDistanceM;
	private int etaSec;
	private int distanceToNextTurnM;
	private int nextTurnType;
	private String nextTurnStreetName = "";
	private int currentTurnIndex = -1;
	private boolean isDeviated;

	public ANavigationProgress() {

	}

	protected ANavigationProgress(Parcel in) {
		readFromParcel(in);
	}

	public static final Creator<ANavigationProgress> CREATOR = new Creator<ANavigationProgress>() {
		@Override
		public ANavigationProgress createFromParcel(Parcel in) {
			return new ANavigationProgress(in);
		}

		@Override
		public ANavigationProgress[] newArray(int size) {
			return new ANavigationProgress[size];
		}
	};

	public double getCurrentLat() {
		return currentLat;
	}

	public void setCurrentLat(double currentLat) {
		this.currentLat = currentLat;
	}

	public double getCurrentLon() {
		return currentLon;
	}

	public void setCurrentLon(double currentLon) {
		this.currentLon = currentLon;
	}

	public float getBearingDeg() {
		return bearingDeg;
	}

	public void setBearingDeg(float bearingDeg) {
		this.bearingDeg = bearingDeg;
	}

	public float getSpeedKmh() {
		return speedKmh;
	}

	public void setSpeedKmh(float speedKmh) {
		this.speedKmh = speedKmh;
	}

	public int getRemainingDistanceM() {
		return remainingDistanceM;
	}

	public void setRemainingDistanceM(int remainingDistanceM) {
		this.remainingDistanceM = remainingDistanceM;
	}

	public int getEtaSec() {
		return etaSec;
	}

	public void setEtaSec(int etaSec) {
		this.etaSec = etaSec;
	}

	public int getDistanceToNextTurnM() {
		return distanceToNextTurnM;
	}

	public void setDistanceToNextTurnM(int distanceToNextTurnM) {
		this.distanceToNextTurnM = distanceToNextTurnM;
	}

	public int getNextTurnType() {
		return nextTurnType;
	}

	public void setNextTurnType(int nextTurnType) {
		this.nextTurnType = nextTurnType;
	}

	public String getNextTurnStreetName() {
		return nextTurnStreetName;
	}

	public void setNextTurnStreetName(String nextTurnStreetName) {
		this.nextTurnStreetName = nextTurnStreetName == null ? "" : nextTurnStreetName;
	}

	public int getCurrentTurnIndex() {
		return currentTurnIndex;
	}

	public void setCurrentTurnIndex(int currentTurnIndex) {
		this.currentTurnIndex = currentTurnIndex;
	}

	public boolean isDeviated() {
		return isDeviated;
	}

	public void setDeviated(boolean deviated) {
		isDeviated = deviated;
	}

	@Override
	protected void readFromBundle(Bundle bundle) {
		currentLat = bundle.getDouble("currentLat");
		currentLon = bundle.getDouble("currentLon");
		bearingDeg = bundle.getFloat("bearingDeg");
		speedKmh = bundle.getFloat("speedKmh");
		remainingDistanceM = bundle.getInt("remainingDistanceM");
		etaSec = bundle.getInt("etaSec");
		distanceToNextTurnM = bundle.getInt("distanceToNextTurnM");
		nextTurnType = bundle.getInt("nextTurnType");
		String s = bundle.getString("nextTurnStreetName");
		nextTurnStreetName = s == null ? "" : s;
		currentTurnIndex = bundle.getInt("currentTurnIndex", -1);
		isDeviated = bundle.getBoolean("isDeviated");
	}

	@Override
	public void writeToBundle(Bundle bundle) {
		bundle.putDouble("currentLat", currentLat);
		bundle.putDouble("currentLon", currentLon);
		bundle.putFloat("bearingDeg", bearingDeg);
		bundle.putFloat("speedKmh", speedKmh);
		bundle.putInt("remainingDistanceM", remainingDistanceM);
		bundle.putInt("etaSec", etaSec);
		bundle.putInt("distanceToNextTurnM", distanceToNextTurnM);
		bundle.putInt("nextTurnType", nextTurnType);
		bundle.putString("nextTurnStreetName", nextTurnStreetName);
		bundle.putInt("currentTurnIndex", currentTurnIndex);
		bundle.putBoolean("isDeviated", isDeviated);
	}
}
