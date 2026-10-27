package com.MuslimMedia.ans;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

public final class AmiriFont {
	private static Typeface typeface;

	private AmiriFont() {
	}

	public static void applyToActivity(Activity activity) {
		applyToView(activity.findViewById(android.R.id.content), activity);
	}

	public static void applyToDialog(AlertDialog dialog) {
		if (dialog.getWindow() != null) {
			applyToView(dialog.getWindow().getDecorView(), dialog.getContext());
		}
	}

	public static Toast makeText(Context context, CharSequence message, int duration) {
		Toast toast = Toast.makeText(context, message, duration);
		View toastView = toast.getView();
		if (toastView != null) {
			applyToView(toastView, context);
		}
		return toast;
	}

	public static Toast makeText(Context context, int messageResource, int duration) {
		Toast toast = Toast.makeText(context, messageResource, duration);
		View toastView = toast.getView();
		if (toastView != null) {
			applyToView(toastView, context);
		}
		return toast;
	}

	private static void applyToView(View view, Context context) {
		if (view instanceof TextView) {
			((TextView) view).setTypeface(getTypeface(context));
		}
		if (view instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) view;
			for (int index = 0; index < group.getChildCount(); index++) {
				applyToView(group.getChildAt(index), context);
			}
		}
	}

	private static Typeface getTypeface(Context context) {
		if (typeface == null) {
			synchronized (AmiriFont.class) {
				if (typeface == null) {
					typeface = Typeface.createFromAsset(context.getAssets(), "fonts/amiri.ttf");
				}
			}
		}
		return typeface;
	}
}