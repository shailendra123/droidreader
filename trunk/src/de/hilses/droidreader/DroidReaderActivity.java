/*

Copyright (C) 2010 Hans-Werner Hilse <hilse@web.de>

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

*/

package de.hilses.droidreader;

import org.openintents.intents.FileManagerIntents;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.util.DisplayMetrics;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

public class DroidReaderActivity extends Activity {
	private static final int REQUEST_CODE_PICK_FILE = 1;
	private static final int DIALOG_GET_PASSWORD = 1;
	private static final String PREFS_NAME = "DroidReaderPrefs";

	protected DroidReaderView mReaderView;
	protected PdfDocument mDocument;
	protected PdfPage mPage;
	protected int mPageNo = 1;
	protected float mZoom = (float) 0.5;
	protected int mRotate = 0;
	protected int mDpiX = 160;
	protected int mDpiY = 160;
	protected int mTileMaxX = 512;
	protected int mTileMaxY = 512;
	private String mFilename;
	
	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// first, show the EULA:
		Eula.show(this);

		// then build our layout. it's so simple that we don't use
		// XML for now.
		FrameLayout fl = new FrameLayout(this);

		mReaderView = new DroidReaderView(this, null, mTileMaxX, mTileMaxY);

		// read the display's DPI
		DisplayMetrics metrics = new DisplayMetrics();
		getWindowManager().getDefaultDisplay().getMetrics(metrics);
		mDpiX = (int) metrics.xdpi;
		mDpiY = (int) metrics.ydpi;

		fl.addView(mReaderView);
		setContentView(fl);
		
		// check if we were called in order to open a PDF:
		Intent intent = getIntent();
		if(intent.getData() != null) {
			// yep:
			mFilename = intent.getData().toString();
			if (mFilename.startsWith("file://")) {
				mFilename = mFilename.substring(7);
				openDocument("");
			} else {
				Toast.makeText(this, R.string.error_only_file_uris, 
						Toast.LENGTH_SHORT).show();
			}
		}
	}
	
	/* Creates the menu items */
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.options_menu, menu);
		return true;
	}

	/* Handles item selections */
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.open_file:
			mReaderView.closeDoc();
			Intent intent = new Intent(FileManagerIntents.ACTION_PICK_FILE);
			intent.setData(Uri.parse("file://"));
			intent.putExtra(FileManagerIntents.EXTRA_TITLE, getString(R.string.open_title));
			try {
				startActivityForResult(intent, REQUEST_CODE_PICK_FILE);
			} catch (ActivityNotFoundException e) {
				Toast.makeText(this, R.string.error_no_filemanager_installed, 
						Toast.LENGTH_SHORT).show();
			}
			return true;
		case R.id.page_next:
			if(mPageNo < mDocument.pagecount) {
				mPageNo++;
				mReaderView.openPage(mDocument, mPageNo,
						mZoom, mRotate, mDpiX, mDpiY);
			}
			return true;
		case R.id.page_prev:
			if(mPageNo > 1) {
				mPageNo--;
				mReaderView.openPage(mDocument, mPageNo,
						mZoom, mRotate, mDpiX, mDpiY);
			}
			return true;
		case R.id.zoom_in:
			mZoom*=2.25;
		case R.id.zoom_out:
			mZoom*=.66;
			mReaderView.openPage(mDocument, mPageNo,
					mZoom, mRotate, mDpiX, mDpiY);
			return true;
		case R.id.quit:
			finish();
			return true;
		}
		return false;
	}
	
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		switch (requestCode) {
		case REQUEST_CODE_PICK_FILE:
			if (resultCode == RESULT_OK && data != null) {
				mFilename = data.getDataString();
				if (mFilename != null) {
					if (mFilename.startsWith("file://")) {
						mFilename = mFilename.substring(7);
					}
					openDocument("");
				}
			}
			break;
		}
	}
	
	protected void openDocument(String password) {
		mPageNo = 1;
		try {
			try {
				mDocument = new PdfDocument(mFilename, password);
				mReaderView.openPage(mDocument, mPageNo,
						mZoom, mRotate, mDpiX, mDpiY);
			} catch (PasswordNeededException e) {
				showDialog(DIALOG_GET_PASSWORD);
			} catch(WrongPasswordException e) {
				Toast.makeText(this, R.string.error_wrong_password, 
						Toast.LENGTH_LONG).show();
			}
		} catch (Exception e) {
			Toast.makeText(this, R.string.error_opening_document, 
					Toast.LENGTH_LONG).show();
		}
	}
	
	protected Dialog onCreateDialog(int id) {
		switch(id) {
		case DIALOG_GET_PASSWORD:
			// displays a password dialog, stores entered password
			// in mPassword, or resets it to an empty string if
			// the input is cancelled.
			AlertDialog.Builder builder = new AlertDialog.Builder(this);
			builder.setMessage(R.string.prompt_password);
			View passwordinput = getLayoutInflater().inflate(R.layout.passworddialog,
					(ViewGroup) findViewById(R.id.input_password));
			builder.setView(passwordinput);
			builder.setCancelable(false);
			builder.setPositiveButton(R.string.button_pwddialog_open,
				new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int id) {
						DroidReaderActivity.this.openDocument(
							((EditText) ((AlertDialog) dialog).findViewById(R.id.input_password)).getText().toString());
						dialog.dismiss();
					}
				});
			builder.setNegativeButton(R.string.button_pwddialog_cancel,
				new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int id) {
						dialog.cancel();
					}
				});
			AlertDialog dialog = builder.create();
			return dialog;
		}
		return null;
	}
}
