package net.osmand.aidlapi.navigation;

import android.os.Bundle;
import android.os.Parcel;

import net.osmand.aidlapi.AidlParams;

public class AGetRouteParams extends AidlParams {

	private ARoute route;
	private long fingerprint;

	public AGetRouteParams() {

	}

	protected AGetRouteParams(Parcel in) {
		readFromParcel(in);
	}

	public static final Creator<AGetRouteParams> CREATOR = new Creator<AGetRouteParams>() {
		@Override
		public AGetRouteParams createFromParcel(Parcel in) {
			return new AGetRouteParams(in);
		}

		@Override
		public AGetRouteParams[] newArray(int size) {
			return new AGetRouteParams[size];
		}
	};

	public ARoute getRoute() {
		return route;
	}

	public void setRoute(ARoute route) {
		this.route = route;
	}

	public long getFingerprint() {
		return fingerprint;
	}

	public void setFingerprint(long fingerprint) {
		this.fingerprint = fingerprint;
	}

	@Override
	protected void readFromBundle(Bundle bundle) {
		bundle.setClassLoader(AGetRouteParams.class.getClassLoader());
		route = bundle.getParcelable("route");
		fingerprint = bundle.getLong("fingerprint");
	}

	@Override
	public void writeToBundle(Bundle bundle) {
		bundle.putParcelable("route", route);
		bundle.putLong("fingerprint", fingerprint);
	}
}
