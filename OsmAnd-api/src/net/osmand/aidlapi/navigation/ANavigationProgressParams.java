package net.osmand.aidlapi.navigation;

import android.os.Bundle;
import android.os.Parcel;

import net.osmand.aidlapi.AidlParams;

public class ANavigationProgressParams extends AidlParams {

	private boolean subscribeToUpdates = true;
	private long callbackId = -1L;
	private long intervalMs = 1000L;

	public ANavigationProgressParams() {

	}

	protected ANavigationProgressParams(Parcel in) {
		readFromParcel(in);
	}

	public static final Creator<ANavigationProgressParams> CREATOR = new Creator<ANavigationProgressParams>() {
		@Override
		public ANavigationProgressParams createFromParcel(Parcel in) {
			return new ANavigationProgressParams(in);
		}

		@Override
		public ANavigationProgressParams[] newArray(int size) {
			return new ANavigationProgressParams[size];
		}
	};

	public boolean isSubscribeToUpdates() {
		return subscribeToUpdates;
	}

	public void setSubscribeToUpdates(boolean subscribeToUpdates) {
		this.subscribeToUpdates = subscribeToUpdates;
	}

	public long getCallbackId() {
		return callbackId;
	}

	public void setCallbackId(long callbackId) {
		this.callbackId = callbackId;
	}

	public long getIntervalMs() {
		return intervalMs;
	}

	public void setIntervalMs(long intervalMs) {
		this.intervalMs = intervalMs;
	}

	@Override
	protected void readFromBundle(Bundle bundle) {
		subscribeToUpdates = bundle.getBoolean("subscribeToUpdates");
		callbackId = bundle.getLong("callbackId");
		intervalMs = bundle.getLong("intervalMs");
	}

	@Override
	public void writeToBundle(Bundle bundle) {
		bundle.putBoolean("subscribeToUpdates", subscribeToUpdates);
		bundle.putLong("callbackId", callbackId);
		bundle.putLong("intervalMs", intervalMs);
	}
}
