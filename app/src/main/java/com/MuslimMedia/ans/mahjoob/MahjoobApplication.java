package com.MuslimMedia.ans.mahjoob;

import android.app.Application;

public final class MahjoobApplication extends Application {
	@Override
	public void onTerminate() {
		MahjoobManager.closeProcessInstance();
		super.onTerminate();
	}
}