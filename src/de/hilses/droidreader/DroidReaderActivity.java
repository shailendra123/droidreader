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
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
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
	private static final int REQUEST_CODE_OPTION_DIALOG = 2;
	
	private static final int DIALOG_GET_PASSWORD = 1;
	private static final int DIALOG_ABOUT = 2;
	private static final int DIALOG_GOTO_PAGE = 3;

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
		
		// Initialize the PdfRender engine
		PdfRender.setFontProvider(new DroidReaderFontProvider(this));
		
		// then build our layout. it's so simple that we don't use
		// XML for now.
		FrameLayout fl = new FrameLayout(this);

		readPreferences();
		mReaderView = new DroidReaderView(this, null, mTileMaxX, mTileMaxY);
		
		View navigationOverlay = getLayoutInflater().inflate(R.layout.navigationoverlay,
				(ViewGroup) findViewById(R.id.navigationlayout));
		
		mButtonPrev = (Button) navigationOverlay.findViewById(R.id.button_prev);
		mButtonNext = (Button) navigationOverlay.findViewById(R.id.button_next);
		
		mButtonPrev.setOnClickListener(new OnClickListener() {
			public void onClick(View v) {
				DroidReaderActivity.this.gotoPage(DroidReaderActivity.this.mPageNo - 1);
			}
		});
		mButtonNext.setOnClickListener(new OnClickListener() {
			public void onClick(View v) {
				DroidReaderActivity.this.gotoPage(DroidReaderActivity.this.mPageNo + 1);
			}
		});

		// add the viewing area and the navigation
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
				// try to open with no password
				openDocument("");
			} else {
				Toast.makeText(this, R.string.error_only_file_uris, 
						Toast.LENGTH_SHORT).show();
			}
		}
	}
	
	private void readPreferences() {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
		DisplayMetrics metrics = new DisplayMetrics();
		getWindowManager().getDefaultDisplay().getMetrics(metrics);

		if(prefs.getString("zoom_type", "0").equals("0")) {
			int zoom = Integer.parseInt(prefs.getString("zoom_percent", "50"));
			if((1 <= zoom) && (1000 >= zoom)) {
				mZoom = ((float)zoom) / 100;
			}
		} else {
			mZoom = Float.parseFloat(prefs.getString("zoom_type", "0"));
		}
		
		if(prefs.getBoolean("dpi_auto", true)) {
			// read the display's DPI
			mDpiX = (int) metrics.xdpi;
			mDpiY = (int) metrics.ydpi;
		} else {
			mDpiX = Integer.parseInt(prefs.getString("dpi_manual", "160"));
			if((mDpiX < 1) || (mDpiX > 4096))
				mDpiX = 160; // sanity check fallback
			mDpiY = mDpiX;
		}
		
		if(prefs.getBoolean("tilesize_by_factor", true)) {
			// set the tile size for rendering by factor
			Float factor = Float.parseFloat(prefs.getString("tilesize_factor", "1.5"));
			mTileMaxX = (int) (metrics.widthPixels * factor);
			mTileMaxY = (int) (metrics.heightPixels * factor);
		} else {
			int tilesize_x = Integer.parseInt(prefs.getString("tilesize_x", "640"));
			int tilesize_y = Integer.parseInt(prefs.getString("tilesize_x", "480"));
			if(metrics.widthPixels < metrics.heightPixels) {
				mTileMaxX = tilesize_x;
				mTileMaxY = tilesize_y;
			} else {
				mTileMaxY = tilesize_x;
				mTileMaxX = tilesize_y;
			}
		}
	}

	/** Creates the menu items */
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.options_menu, menu);
		return true;
	}

	/** Handles item selections */
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		
		// Zooming:
		
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
			
		// Rotation:
			
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
			
		// Page Navigation:
			
		case R.id.goto_first:
			gotoPage(1);
			return true;
		case R.id.goto_last:
			try {
				gotoPage(mDocument.pagecount);
			} catch(NullPointerException e) {
				// do document yet...
			}
			return true;
		case R.id.goto_ask:
			showDialog(DIALOG_GOTO_PAGE);
			return true;
			
		// File menu
			
		case R.id.open_file:
			// present the file manager's "open..." dialog
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
		case R.id.options:
			Intent optionsIntent = new Intent(this,DroidReaderOptions.class);
			startActivityForResult(optionsIntent, REQUEST_CODE_OPTION_DIALOG);
			return true;
		case R.id.about:
			// show the about dialog
			showDialog(DIALOG_ABOUT);
			return true;
		case R.id.quit:
			// quit Activity
			finish();
			return true;
		}
		return false;
	}

	/**
	 * jump to a certain page of the PDF
	 * @param pageNo the page number to show
	 */
	protected void gotoPage(int pageNo) {
		try {
			if((pageNo >= 1) && (pageNo <= mDocument.pagecount)) {
				if(pageNo == 1) {
					mButtonPrev.setClickable(false);
					mButtonPrev.setVisibility(View.INVISIBLE);
				} else {
					mButtonPrev.setClickable(true);
					mButtonPrev.setVisibility(View.VISIBLE);
				}
				if(pageNo == mDocument.pagecount) {
					mButtonNext.setClickable(false);
					mButtonNext.setVisibility(View.INVISIBLE);
				} else {
					mButtonNext.setClickable(true);
					mButtonNext.setVisibility(View.VISIBLE);
				}
				mPageNo = pageNo;
				mReaderView.openPage(mDocument, mPageNo,
						mZoom, mRotate, mDpiX, mDpiY);
			} else {
				Toast.makeText(this, R.string.error_no_such_page, 
						Toast.LENGTH_SHORT).show();
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
		case REQUEST_CODE_OPTION_DIALOG:
			readPreferences();
			try {
				mReaderView.openPage(mDocument, mPageNo,
						mZoom, mRotate, mDpiX, mDpiY);
			} catch (NullPointerException e) {
				// no document loaded
			}
			break;
		}
	}
	
	protected void openDocument(String password) {
		try {
			mDocument = new PdfDocument(mFilename, password);
			readPreferences();
			gotoPage(1);
		} catch (PasswordNeededException e) {
			showDialog(DIALOG_GET_PASSWORD);
		} catch (WrongPasswordException e) {
			Toast.makeText(this, R.string.error_wrong_password, 
					Toast.LENGTH_LONG).show();
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
		case DIALOG_GOTO_PAGE:
			AlertDialog.Builder gotoBuilder = new AlertDialog.Builder(this);
			gotoBuilder.setMessage(R.string.prompt_goto_page);
			View pageinput = getLayoutInflater().inflate(R.layout.pagedialog,
					(ViewGroup) findViewById(R.id.input_page));
			gotoBuilder.setView(pageinput);
			gotoBuilder.setCancelable(false);
			gotoBuilder.setPositiveButton(R.string.button_page_open,
				new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int id) {
						DroidReaderActivity.this.gotoPage(
							Integer.parseInt(
								((EditText)
										((AlertDialog) dialog).findViewById(R.id.input_page))
										.getText()
										.toString()));
						dialog.dismiss();
					}
				});
			gotoBuilder.setNegativeButton(R.string.button_page_cancel,
				new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int id) {
						dialog.cancel();
					}
				});
			AlertDialog gotoDialog = gotoBuilder.create();
			return gotoDialog;
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
