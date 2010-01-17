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
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

public class DroidReaderActivity extends Activity {
	protected static final int REQUEST_CODE_PICK_FILE_OR_DIRECTORY = 1;

	protected DroidReaderView mReaderView;
	protected PdfDocument mDocument;
	protected PdfPage mPage;
	protected int mPageNo = 1;
	protected float mZoom = (float) 0.5;
	protected int mRotate = 0;
	protected int mDpiX = 160;
	protected int mDpiY = 160;
	protected int mTileMaxX = 500;
	protected int mTileMaxY = 512;
	
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

		fl.addView(mReaderView);
		setContentView(fl);
		
		// check if we were called in order to open a PDF:
		Intent intent = getIntent();
		if(intent.getData() != null) {
			// yep:
			String filename = intent.getData().toString();
			if (filename.startsWith("file://")) {
				filename = filename.substring(7);
				mPageNo = 1;
				try {
					mDocument = new PdfDocument(filename, "");
					mReaderView.openPage(mDocument, mPageNo,
							mZoom, mRotate, mDpiX, mDpiY);
				} catch (Exception e) {
					Toast.makeText(this, R.string.error_opening_document, 
							Toast.LENGTH_SHORT).show();
				}
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
				startActivityForResult(intent, REQUEST_CODE_PICK_FILE_OR_DIRECTORY);
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
			mZoom*=2;
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
		case REQUEST_CODE_PICK_FILE_OR_DIRECTORY:
			if (resultCode == RESULT_OK && data != null) {
				String filename = data.getDataString();
				if (filename != null) {
					if (filename.startsWith("file://")) {
						filename = filename.substring(7);
					}
					mPageNo = 1;
					try {
						mDocument = new PdfDocument(filename, "");
						mReaderView.openPage(mDocument, mPageNo,
								mZoom, mRotate, mDpiX, mDpiY);
					} catch (Exception e) {
						Toast.makeText(this, R.string.error_opening_document, 
								Toast.LENGTH_SHORT).show();
					}
				}
			}
			break;
		}
	}
}
