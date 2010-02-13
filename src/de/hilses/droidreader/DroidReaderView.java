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

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.GestureDetector.OnGestureListener;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/**
 * Provides a scrollable View for PdfPage instances
 * @author hw
 *
 * The class uses three threads: The main View UI thread which
 * handles events like User Input, a Thread that cares for blitting
 * rendered images onto the View's Canvas and implements the scrolling,
 * and finally a third thread that drives the actual rendering. The
 * latter two are run transparently, a user of this class should only
 * deal with the methods it provides itself.
 */
public class DroidReaderView extends SurfaceView
implements OnGestureListener, SurfaceHolder.Callback {
	/**
	 * Debug helper
	 */
	protected final static String TAG = "DroidReaderView";

	/**
	 * Constant: Zoom to fit page
	 */
	protected static final int ZOOM_FIT = -1;

	/**
	 * Constant: Zoom to fit page width
	 */
	protected static final int ZOOM_FIT_WIDTH = -2;

	/**
	 * Constant: Zoom to fit page height
	 */
	protected static final int ZOOM_FIT_HEIGHT = -3;

	/**
	 * our view thread which does the drawing
	 */
	protected DroidReaderViewThread mThread;

	/**
	 * our gesture detector
	 */
	protected final GestureDetector mGestureDetector;
	
	/**
	 * our context
	 */
	protected final Context mContext;
	
	/**
	 * our SurfaceHolder
	 */
	protected final SurfaceHolder mSurfaceHolder;
	
	/**
	 * specifies the Zoom factor
	 */
	protected float mZoom;
	/**
	 * specifies the rotation in degrees
	 */
	protected int mRotation;
	/**
	 * horizontal DPI of our display
	 */
	protected int mDpiX;
	/**
	 * vertival DPI of our display
	 */
	protected int mDpiY;
	
	/**
	 * The PdfDocument, if loaded
	 */
	protected PdfDocument mDocument;

	/**
	 * the page number we're rendering
	 */
	protected int mPageNo;
	
	/**
	 * Maximum width of a rendered tile
	 */
	private final int mTileMaxX;
	/**
	 * Maximum height of a rendered tile
	 */
	private final int mTileMaxY;
	
	/**
	 * constructs a new View
	 * @param context Context for the View
	 * @param attrs attributes (may be null)
	 */
	public DroidReaderView(final Context context, AttributeSet attrs,
			int tileMaxX, int tileMaxY) {
		super(context, attrs);
		
		mContext = context;
		mSurfaceHolder = getHolder();

		// tell the SurfaceHolder to inform this thread on
		// changes to the surface
		mSurfaceHolder.addCallback(this);
		
		mTileMaxX = tileMaxX;
		mTileMaxY = tileMaxY;
		
		mGestureDetector = new GestureDetector(this);
	}
	/**
	 * to be called in order to open a new page
	 * @param page PdfPage instance that we should show
	 * @param zoom Zoom (100% == 1.0)
	 * @param rotation Rotation in degrees
	 * @param dpiX Display's horizontal DPI
	 * @param dpiY Display's vertical DPI
	 */
	public void openPage(PdfDocument doc, int pageno,
			float zoom, int rotation, int dpiX, int dpiY) {
		closePage();
		mZoom = zoom;
		mRotation = rotation;
		mDpiX = dpiX;
		mDpiY = dpiY;
		mDocument = doc;
		mPageNo = pageno;
		try {
			mThread.openPage(mDocument, mPageNo, mZoom, mRotation, mDpiX, mDpiY);
		} catch(NullPointerException e) {
			Log.d(TAG, "our ViewThread is not alive...");
		}
	}
	
	/**
	 * close the currently opened page, go to no document state.
	 * to be called externally
	 */
	public void closePage() {
		try {
			mThread.closePage();
		} catch(NullPointerException e) {
			Log.e(TAG, "our ViewThread died!");
		}
	}
	
	/**
	 * close the current document: free resources
	 */
	public void closeDoc() {
		closePage();
		try {
			mDocument.done();
			mDocument = null;
		} catch(NullPointerException e) {
			// no document to drop.
		}
	}

	/* event listeners: */
	
	@Override
	public boolean onTrackballEvent(MotionEvent event) {
		Log.d(TAG, "onTouchEvent(): notifying ViewThread");
		try {
			return mThread.doScroll(null, null, event.getX() * 20, event.getY() * 20);
		} catch(NullPointerException e) {
			return false;
		}
	}
	
	@Override
	public boolean onTouchEvent(final MotionEvent event) {
		Log.d(TAG, "onTouchEvent(): notifying mGestureDetector");
		if (mGestureDetector.onTouchEvent(event))
			return true;
		return super.onTouchEvent(event);
	}
	
	/* keyboard events: */
	
	public boolean onKeyDown(int keyCode, KeyEvent msg) {
		return false;
	}
	
	public boolean onKeyUp(int keyCode, KeyEvent msg) {
		return false;
	}
	
	/* interface for the GestureListener: */
	
	@Override
	public boolean onDown(MotionEvent e) {
		// just consume the event
		return true;
	}
	
	@Override
	public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX,
			float velocityY) {
		Log.d(TAG, "onFling(): notifying ViewThread");
		try {
			return mThread.doFling(e1, e2, velocityX, velocityY);
		} catch(NullPointerException e) {
			return false;
		}
	}

	@Override
	public void onLongPress(MotionEvent e) {
		Log.d(TAG, "onLongPress(): ignoring!");
	}

	@Override
	public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX,
			float distanceY) {
		try {
			return mThread.doScroll(e1, e2, distanceX, distanceY);
		} catch(NullPointerException e) {
			return false;
		}
	}

	@Override
	public void onShowPress(MotionEvent e) {
		Log.d(TAG, "onShowPress(): ignoring!");
	}

	@Override
	public boolean onSingleTapUp(MotionEvent e) {
		// just consume the event...
		return true;
	}
	
	/* surface events: */
	
	@Override
	public void surfaceCreated(SurfaceHolder holder) {
		Log.d(TAG, "surfaceCreated(): starting ViewThread");
		mThread = new DroidReaderViewThread(holder, mContext, mTileMaxX, mTileMaxY);
		mThread.start();
		try {
			mThread.openPage(mDocument, mPageNo, mZoom, mRotation, mDpiX, mDpiY);
		} catch(NullPointerException e) {
			Log.e(TAG, "error loading page");
		}
	}
	
	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int width,
			int height) {
		mThread.surfaceChanged();
	}
	
	@Override
	public void surfaceDestroyed(SurfaceHolder holder) {
		Log.d(TAG, "surfaceDestroyed(): dying");
		boolean retry = true;
		mThread.mRun = false;
		mThread.interrupt();
		while (retry) {
			try {
				mThread.join();
				retry = false;
			} catch (InterruptedException e) {
			}
		}
	}
}
