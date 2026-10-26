package com.MuslimMedia.ans.mahjoob;

import android.content.SharedPreferences;

import dev.mahjoob.core.TargetGender;

public final class MahjoobSettings {
	private static final String MODE_KEY = "mahjoob_mode";
	private static final String STRICT_KEY = "mahjoob_strict";

	public enum Mode {
		OFF("off", null),
		WOMEN("women", TargetGender.FEMALE),
		MEN("men", TargetGender.MALE),
		EVERYONE("everyone", TargetGender.ANY);

		private final String preferenceValue;
		private final TargetGender targetGender;

		Mode(String preferenceValue, TargetGender targetGender) {
			this.preferenceValue = preferenceValue;
			this.targetGender = targetGender;
		}

		public TargetGender targetGender() {
			return targetGender;
		}

		public static Mode fromPreference(String value) {
			for (Mode mode : values()) {
				if (mode.preferenceValue.equals(value)) {
					return mode;
				}
			}
			return EVERYONE;
		}
	}

	private MahjoobSettings() {
	}

	public static Mode readMode(SharedPreferences preferences) {
		return Mode.fromPreference(preferences.getString(MODE_KEY, Mode.EVERYONE.preferenceValue));
	}

	public static boolean readStrict(SharedPreferences preferences) {
		return preferences.getBoolean(STRICT_KEY, true);
	}

	public static void writeMode(SharedPreferences preferences, Mode mode) {
		preferences.edit().putString(MODE_KEY, mode.preferenceValue).apply();
	}

	public static void writeStrict(SharedPreferences preferences, boolean strict) {
		preferences.edit().putBoolean(STRICT_KEY, strict).apply();
	}
}