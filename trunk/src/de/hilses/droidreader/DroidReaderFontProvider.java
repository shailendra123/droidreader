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

import android.app.Activity;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

/**
 * An instance of this class will provide font file names, reading from Preferences
 */
public class DroidReaderFontProvider implements FontProvider {
	/**
	 * Debug helper
	 */
	private static final String TAG = "DroidReaderFontProvider";
	
	/**
	 * Our Activity
	 */
	private Activity mActivity;
	
	/**
	 * Instantiates a new FontProvider
	 * @param activity our Activity to read Preferences from
	 */
	DroidReaderFontProvider(Activity activity) {
		mActivity = activity;
	}

	/**
	 * callback that is used to retrieve font file names
	 * @param fontName the name of the font to load
	 * @param collection the collection of fonts (CID)
	 * @param flags font flags as understood by the MuPDF library
	 */
	@Override
	public String getFontFile(String fontName, String collection, int flags) {
		Log.d(TAG, "Font: " + fontName + " Collection: " + collection + " Flags: " + flags);
		
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mActivity);
		
		String font = null;
		if(fontName.equals("CID-Substitute")) {
			// it's a CID font, get font file name from Preferences:
			String setting = "cid_font_" + collection + (((flags & 0x0001)==1) ? "_mincho" : "_gothic");
			Log.d(TAG, "CID Font, reading setting "+setting);
			font = prefs.getString(setting,
					mActivity.getResources().getString(R.string.prefs_cid_default_font));
			Log.d(TAG, "got font "+font);
		}
		
		return font;
	}
}
