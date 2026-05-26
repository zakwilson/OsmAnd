package net.osmand.aidlapi.navigation;

import android.os.Bundle;
import android.os.Parcel;

import net.osmand.aidlapi.AidlParams;

public class ARerouteEvent extends AidlParams {

	private long oldFingerprint;
	private long newFingerprint;
	private long timestampMs;

	public ARerouteEvent() {

	}

	public ARerouteEvent(long oldFingerprint, long newFingerprint, long timestampMs) {
		this.oldFingerprint = oldFingerprint;
		this.newFingerprint = newFingerprint;
		this.timestampMs = timestampMs;
	}

	protected ARerouteEvent(Parcel in) {
		readFromParcel(in);
	}

	public static final Creator<ARerouteEvent> CREATOR = new Creator<ARerouteEvent>() {
		@Override
		public ARerouteEvent createFromParcel(Parcel in) {
			return new ARerouteEvent(in);
		}

		@Override
		public ARerouteEvent[] newArray(int size) {
			return new ARerouteEvent[size];
		}
	};

	public long getOldFingerprint() {
		return oldFingerprint;
	}

	public void setOldFingerprint(long oldFingerprint) {
		this.oldFingerprint = oldFingerprint;
	}

	public long getNewFingerprint() {
		return newFingerprint;
	}

	public void setNewFingerprint(long newFingerprint) {
		this.newFingerprint = newFingerprint;
	}

	public long getTimestampMs() {
		return timestampMs;
	}

	public void setTimestampMs(long timestampMs) {
		this.timestampMs = timestampMs;
	}

	@Override
	protected void readFromBundle(Bundle bundle) {
		oldFingerprint = bundle.getLong("oldFingerprint");
		newFingerprint = bundle.getLong("newFingerprint");
		timestampMs = bundle.getLong("timestampMs");
	}

	@Override
	public void writeToBundle(Bundle bundle) {
		bundle.putLong("oldFingerprint", oldFingerprint);
		bundle.putLong("newFingerprint", newFingerprint);
		bundle.putLong("timestampMs", timestampMs);
	}
}
