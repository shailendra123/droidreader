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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import org.openintents.intents.FileManagerIntents;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.View.OnClickListener;

public class DroidReaderActivity extends Activity {
	private static final int REQUEST_CODE_PICK_FILE = 1;
	private static final int DIALOG_GET_PASSWORD = 1;
	private static final int DIALOG_ABOUT = 2;

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
	
	private Button mButtonPrev = null;
	private Button mButtonNext = null;
	
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
		
		View navigationOverlay = getLayoutInflater().inflate(R.layout.navigationoverlay,
				(ViewGroup) findViewById(R.id.navigationlayout));
		
		mButtonPrev = (Button) navigationOverlay.findViewById(R.id.button_prev);
		mButtonNext = (Button) navigationOverlay.findViewById(R.id.button_next);
		
		mButtonPrev.setOnClickListener(new OnClickListener() {
			public void onClick(View v) {
				DroidReaderActivity.this.gotoPrevPage();
			}
		});
		mButtonNext.setOnClickListener(new OnClickListener() {
			public void onClick(View v) {
				DroidReaderActivity.this.gotoNextPage();
			}
		});

		// read the display's DPI
		DisplayMetrics metrics = new DisplayMetrics();
		getWindowManager().getDefaultDisplay().getMetrics(metrics);
		mDpiX = (int) metrics.xdpi;
		mDpiY = (int) metrics.ydpi;

		fl.addView(mReaderView);
		fl.addView(navigationOverlay, new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.FILL_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT,
				Gravity.BOTTOM));
		setContentView(fl);
		
		mButtonPrev.setClickable(false);
		mButtonNext.setClickable(false);
		mButtonPrev.setVisibility(View.INVISIBLE);
		mButtonNext.setVisibility(View.INVISIBLE);
		
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
		case R.id.zoom_in:
			mZoom*=1.5;
			mReaderView.openPage(mDocument, mPageNo,
					mZoom, mRotate, mDpiX, mDpiY);
			return true;
		case R.id.zoom_out:
			mZoom*=.66;
			mReaderView.openPage(mDocument, mPageNo,
					mZoom, mRotate, mDpiX, mDpiY);
			return true;
		case R.id.zoom_fit:
			mZoom = DroidReaderView.ZOOM_FIT;
			mReaderView.openPage(mDocument, mPageNo,
					mZoom, mRotate, mDpiX, mDpiY);
			return true;
		case R.id.zoom_fitw:
			mZoom = DroidReaderView.ZOOM_FIT_WIDTH;
			mReaderView.openPage(mDocument, mPageNo,
					mZoom, mRotate, mDpiX, mDpiY);
			return true;
		case R.id.zoom_fith:
			mZoom = DroidReaderView.ZOOM_FIT_HEIGHT;
			mReaderView.openPage(mDocument, mPageNo,
					mZoom, mRotate, mDpiX, mDpiY);
			return true;
		case R.id.zoom_orig:
			mZoom = 1.0F;
			mReaderView.openPage(mDocument, mPageNo,
					mZoom, mRotate, mDpiX, mDpiY);
			return true;
		case R.id.rotation_left:
			mRotate += 270;
			mRotate %= 360;
			mReaderView.openPage(mDocument, mPageNo,
					mZoom, mRotate, mDpiX, mDpiY);
			return true;
		case R.id.rotation_right:
			mRotate += 90;
			mRotate %= 360;
			mReaderView.openPage(mDocument, mPageNo,
					mZoom, mRotate, mDpiX, mDpiY);
			return true;
		case R.id.open_file:
			mButtonPrev.setClickable(false);
			mButtonNext.setClickable(false);
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
		case R.id.about:
			showDialog(DIALOG_ABOUT);
			return true;
		case R.id.quit:
			finish();
			return true;
		}
		return false;
	}
	
	protected void gotoPrevPage() {
		try {
			if(1 < mPageNo) {
				mPageNo--;
				if(1 == mPageNo) {
					mButtonPrev.setClickable(false);
					mButtonPrev.setVisibility(View.INVISIBLE);
				}
				if(mDocument.pagecount > mPageNo) {
					mButtonNext.setClickable(true);
					mButtonNext.setVisibility(View.VISIBLE);
				}
				mReaderView.openPage(mDocument, mPageNo,
						mZoom, mRotate, mDpiX, mDpiY);
			}
		} catch(NullPointerException e) {
			// no mDocument yet?
		}
	}

	protected void gotoNextPage() {
		try {
			if(mDocument.pagecount > mPageNo) {
				mPageNo++;
				if(mDocument.pagecount == mPageNo) {
					mButtonNext.setClickable(false);
					mButtonNext.setVisibility(View.INVISIBLE);
				}
				if(1 < mPageNo) {
					mButtonPrev.setClickable(true);
					mButtonPrev.setVisibility(View.VISIBLE);
				}
				mReaderView.openPage(mDocument, mPageNo,
						mZoom, mRotate, mDpiX, mDpiY);
			}
		} catch(NullPointerException e) {
			// no mDocument yet?
		}
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
		mButtonPrev.setClickable(false);
		mButtonNext.setClickable(false);
		mButtonPrev.setVisibility(View.INVISIBLE);
		mButtonNext.setVisibility(View.INVISIBLE);
		try {
			try {
				mDocument = new PdfDocument(mFilename, password);
				mReaderView.openPage(mDocument, mPageNo,
						mZoom, mRotate, mDpiX, mDpiY);
				if(mDocument.pagecount > 1) {
					mButtonNext.setClickable(true);
					mButtonNext.setVisibility(View.VISIBLE);
				}
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
		case DIALOG_ABOUT:
			AlertDialog.Builder aboutBuilder = new AlertDialog.Builder(this);
			WebView aboutWebView = new WebView(this);
			aboutWebView.loadData(readAbout().toString(), "text/html", "UTF-8");
			aboutBuilder.setView(aboutWebView);
			aboutBuilder.setCancelable(false);
			aboutBuilder.setPositiveButton(R.string.button_ok,
					new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int id) {
							dialog.dismiss();
						}
					});
			AlertDialog aboutDialog = aboutBuilder.create();
			return aboutDialog;
		}
		return null;
	}
	
	private CharSequence readAbout() {
        BufferedReader in = null;
        try {
            in = new BufferedReader(new InputStreamReader(this.getAssets().open("about.html")));
            String line;
            StringBuilder buffer = new StringBuilder();
            while ((line = in.readLine()) != null)
            	buffer.append(line).append('\n');
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    // We can't do anything...
                }
            }
            return buffer;
        } catch (IOException e) {
            return "";
        }
	}
}
