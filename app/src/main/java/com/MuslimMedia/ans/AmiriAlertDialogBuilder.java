package com.MuslimMedia.ans;

import android.app.AlertDialog;
import android.content.Context;

public final class AmiriAlertDialogBuilder extends AlertDialog.Builder {
	public AmiriAlertDialogBuilder(Context context) {
		super(context);
	}

	@Override
	public AlertDialog create() {
		AlertDialog dialog = super.create();
		dialog.setOnShowListener(ignored -> AmiriFont.applyToDialog(dialog));
		return dialog;
	}

	@Override
	public AlertDialog show() {
		AlertDialog dialog = super.show();
		AmiriFont.applyToDialog(dialog);
		return dialog;
	}
}