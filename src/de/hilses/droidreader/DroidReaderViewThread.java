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
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.widget.Scroller;

/**
 * Thread that cares for blitting Pixmaps onto the Canvas and handles scrolling
 */
class DroidReaderViewThread extends Thread
implements DroidReaderRenderThread.RenderListener {
	/**
	 * Debug helper
	 */
	protected final static String TAG = "DroidReaderViewThread";
	/**
	 * the SurfaceHolder for our Surface
	 */
	protected final SurfaceHolder mSurfaceHolder;
	/**
	 * the Pixmap we work with
	 */
	protected final PdfView mActivePixmap;
	/**
	 * The ViewPort that we cover. This fits into the page size.
	 */
	protected Rect mActiveViewPort = null;
	/**
	 * The ViewPort that is actually being displayed
	 */
	protected Rect mPageSize = new Rect(0, 0, 0, 0);
	/**
	 * The transformation matrix for rendering the Page
	 */
	protected Matrix mPageMatrix = null;
	
	/**
	 * defines the scrollable area in pixels
	 */
	protected Rect mScrollableViewPort = null;
	
	/**
	 * Paint for not (yet) rendered parts of the page
	 */
	protected final Paint mEmptyPaint;
	/**
	 * Paint for filling the display when there is no PdfPage (yet)
	 */
	protected final Paint mNoPagePaint;
	/**
	 * Paint for the status text
	 */
	protected final Paint mStatusPaint;
	
	/**
	 * Trigger for the case that the frame is dirty, i.e. when
	 * the PdfPage changed
	 */
	protected boolean mFrameDirty = true;
	/**
	 * Trigger for the case the position of mActiveInnerViewPort changed
	 */
	protected boolean mPositionDirty = false;
	/**
	 * Trigger for the case that we should check whether the Pixmap
	 * should be re-rendered.
	 */
	protected boolean mPixmapDirty = false;
	/**
	 * Trigger for running the actual painting  to the surface
	 */
	protected boolean mRedraw = true;
	/**
	 * Flag that we loaded a page
	 */
	protected boolean mPageLoaded = false;
	/**
	 * Flag that our Pixmap is current
	 */
	protected boolean mPixmapIsCurrent = false;
	/**
	 * Flag that our thread should be running
	 */
	protected boolean mRun = true;
	/**
	 * Offset of the left upper edge (on display) relative to the 
	 * active Page
	 * 
	 * TODO: change to Point object
	 */
	protected int mOffsetX, mOffsetY;
	
	/**
	 * our scroller
	 */
	protected final Scroller mScroller;
	
	/**
	 * Maximum width of a rendered tile
	 * 
	 * TODO: make a configuration option
	 */
	private final int mTileMaxX;
	/**
	 * Maximum height of a rendered tile
	 * 
	 * TODO: make a configuration option
	 */
	private final int mTileMaxY;
	/**
	 * Timeout in milliseconds for lazy rendering
	 * 
	 * TODO: make a configuration option
	 */
	private int mLazyRender = 250;
	
	/**
	 * our background rendering thread
	 */
	protected DroidReaderRenderThread mRenderThread;
	
	/**
	 * Background render thread, using the SurfaceView programming
	 * scheme
	 * @param holder our SurfaceHolder
	 * @param context the Context for our drawing
	 */
	public DroidReaderViewThread(SurfaceHolder holder, Context context,
			int tileMaxX, int tileMaxY) {
		// store a reference to our SurfaceHolder
		mSurfaceHolder = holder;
		
		// initialize Paints for non-Pixmap areas
		mEmptyPaint = new Paint();
		mEmptyPaint.setStyle(Paint.Style.FILL);
		mEmptyPaint.setColor(0xffc0c0c0); // light gray
		
		mNoPagePaint = new Paint();
		mNoPagePaint.setStyle(Paint.Style.FILL);
		mNoPagePaint.setColor(0xff303030); // dark gray
		
		mStatusPaint = new Paint();
		mNoPagePaint.setColor(0xff808080); // medium gray
		
		// the scroller, i.e. the object that calculates/interpolates
		// positions for scrolling/jumping/flinging
		mScroller = new Scroller(context);
		
		// set up our Pixmap:
		mTileMaxX = tileMaxX;
		mTileMaxY = tileMaxY;
		mActivePixmap = new PdfView();
		mActiveViewPort = new Rect(0, 0, 0, 0);
	}
	
	/**
	 * Main Thread loop
	 */
	@Override
	public void run() {
		// keeps track of whether we go to sleep after this loop run
		boolean doSleep;
		while (mRun) {
			doSleep = true; // default to go asleep
			synchronized(mSurfaceHolder) {
				// check each trigger and act accordingly
				if(mFrameDirty) {
					Log.d(TAG, "frame is dirty");
					resetScrollableViewPort();
					clampOffsets();
					mFrameDirty = false;
					mPixmapDirty = true;
					mRedraw = true;
				}
				if(mPositionDirty) {
					Log.d(TAG, "position is dirty");
					if(mScroller.computeScrollOffset()) {
						mOffsetX = mScroller.getCurrX();
						mOffsetY = mScroller.getCurrY();
					}
					if(mScroller.isFinished()) {
						mPositionDirty = false;
						Log.d(TAG, "scroll has finished:");
					} else {
						// do not go to sleep since we want to _animate_ the
						// change of coordinates through time
						doSleep = false;
					}
					clampOffsets();
					Log.d(TAG, "scrolled to (" + mOffsetX + "," + mOffsetY + ")");
					// if we moved, the Pixmap might be dirty
					mPixmapDirty = true;
					mRedraw = true;
				}
				if(mPixmapDirty) {
					Log.d(TAG, "Pixmap is dirty");
					long sleepTime = mLazyRender;
					// if we don't have a current Pixmap, render
					// one ASAP:
					if(!mPixmapIsCurrent)
						sleepTime = 0;
					// check if we need a new Pixmap, and if yes,
					// enqueue a new RenderJob
					if(mPageLoaded && checkForDirtyPixmap()) {
						try {
							mRenderThread.newRenderJob(
									calcCenteredViewPort(),
									mPageMatrix, sleepTime);
						} catch(NullPointerException e) {
							Log.e(TAG, "no RenderThread we could use!");
						}
					}
					mPixmapDirty = false;
					// note that there is no real reason to redraw for
					// _this_ trigger, since we will update if and when
					// we are triggered for a new ready Pixmap by the
					// rendering Thread
				}
				if(mRedraw) {
					Log.d(TAG, "we redraw...");
					mRedraw = false;
					doDraw();
				}
			}
			// if we're allowed, we will go to sleep now
			if(doSleep) {
				try {
					// nothing to do, wait for someone waking us up:
					Log.d(TAG, "ViewThread going to sleep");
					Thread.sleep(3600000);
				} catch (InterruptedException e) {
					Log.d(TAG, "ViewThread woken up");
				}
			}
		}
		// mRun is now false, so we shut down. But before we can do
		// that, we must trigger our RenderThread to die first, if
		// it is still running...
		Log.d(TAG, "shutting down the RenderThread");
		try {
			boolean retry = true;
			mRenderThread.mRun = false;
			mRenderThread.interrupt();
			while (retry) {
				try {
					mRenderThread.join();
					retry = false;
				} catch (InterruptedException e) {
				}
			}
		} catch(NullPointerException e) {
			// there wasn't any thread running
		}
		Log.d(TAG, "now dying...");
	}
	
	/**
	 * helper function: calculates the visible view port
	 * @return rectangle specifying the view port
	 */
	protected Rect getInnerViewPort() {
		Rect surfaceSize = mSurfaceHolder.getSurfaceFrame();
		Rect innerViewPort = new Rect(mOffsetX, mOffsetY,
				mOffsetX + surfaceSize.width(), mOffsetY + surfaceSize.height());
		if(innerViewPort.right > mPageSize.right)
			innerViewPort.right = mPageSize.right;
		if(innerViewPort.bottom > mPageSize.bottom)
			innerViewPort.bottom = mPageSize.bottom;
		return innerViewPort;
	}
	
	/**
	 * this checks whether our Pixmap is "dirty", i.e. needs to be
	 * re-rendered
	 * @return true if we need to re-render, false otherwise
	 */
	protected boolean checkForDirtyPixmap() {
		if(!mPixmapIsCurrent)
			return true;
		
		return !mActiveViewPort.contains(getInnerViewPort());
	}
	
	/**
	 * helper function for clamping the currently shown view port's
	 * upper left coordinates to the valid rectangle (mScrollabeViewPort)
	 */
	protected void clampOffsets() {
		if(mOffsetX < mScrollableViewPort.left)
			mOffsetX = mScrollableViewPort.left;
		if(mOffsetX > mScrollableViewPort.right)
			mOffsetX = mScrollableViewPort.right;
		if(mOffsetY < mScrollableViewPort.top)
			mOffsetY = mScrollableViewPort.top;
		if(mOffsetY > mScrollableViewPort.bottom)
			mOffsetY = mScrollableViewPort.bottom;
	}
	
	/**
	 * This calculates a view port (for rendering a Pixmap from it)
	 * which tries to fit the current display (identified by it's
	 * upper left coordinates) centered into the new rendered view.
	 * it takes the page size into account and will always fit into
	 * the page size.
	 * @return new view port
	 */
	private Rect calcCenteredViewPort() {
		Rect activeInnerViewPort = getInnerViewPort();
		Rect viewPort = new Rect(
				activeInnerViewPort.centerX() - (mTileMaxX/2),
				activeInnerViewPort.centerY() - (mTileMaxY/2),
				activeInnerViewPort.centerX() + (mTileMaxX/2),
				activeInnerViewPort.centerY() + (mTileMaxY/2));
		
		// now check if we touch the page's bounds and update
		// accordingly so we don't cover parts outside the
		// page size unneededly
		if(viewPort.right > mPageSize.right) {
			viewPort.left -= (viewPort.right - mPageSize.right);
			viewPort.right = mPageSize.right;
		}
		if(viewPort.left < mPageSize.left) {
			viewPort.right += (mPageSize.left - viewPort.left);
			viewPort.left = mPageSize.left;
		}
		if(viewPort.bottom > mPageSize.bottom) {
			viewPort.top -= (viewPort.bottom - mPageSize.bottom);
			viewPort.bottom = mPageSize.bottom;
		}
		if(viewPort.top < mPageSize.top) {
			viewPort.bottom += (mPageSize.top - viewPort.top);
			viewPort.top = mPageSize.top;
		}
		return viewPort;
	}
	
	/**
	 * this is being called when we should re-measure our surface
	 */
	public void resetScrollableViewPort() {
		Rect surfaceSize = mSurfaceHolder.getSurfaceFrame();
		mScrollableViewPort = new Rect(
				mPageSize.left, 
				mPageSize.top,
				mPageSize.right - surfaceSize.width(),
				mPageSize.bottom - surfaceSize.height());
		if(mScrollableViewPort.right < mScrollableViewPort.left)
			mScrollableViewPort.right = mScrollableViewPort.left;
		if(mScrollableViewPort.bottom < mScrollableViewPort.top)
			mScrollableViewPort.bottom = mScrollableViewPort.top;
	}
	
	
	/**
	 * this does the actual drawing to the Canvas for our surface
	 */
	private void doDraw() {
		Log.d(TAG, "drawing...");
		Canvas c = null;
		try {
			c = mSurfaceHolder.lockCanvas(null);
			if(!mPageLoaded) {
				// no page/document loaded
				Log.d(TAG, "no page loaded.");
				c.drawRect(0, 0, c.getWidth(), c.getHeight(), mNoPagePaint);
			} else if(mPixmapIsCurrent) {
				// we have both page and Pixmap, so draw:
				Log.d(TAG, "rendering Pixmap at (" + (-mOffsetX + mActiveViewPort.left) + "," +
						(-mOffsetY + mActiveViewPort.top) + ")");
				// background:
				c.drawRect(0, 0, c.getWidth(), c.getHeight(), mEmptyPaint);
				synchronized(mActivePixmap) {
					// pixmap:
					c.drawBitmap(
							mActivePixmap.buf,
							0,
							mActiveViewPort.width(),
							-mOffsetX + mActiveViewPort.left,
							-mOffsetY + mActiveViewPort.top,
							mActiveViewPort.width(),
							mActiveViewPort.height(),
							false,
							null);
				}
			} else {
				// page loaded, but no Pixmap yet
				Log.d(TAG, "page loaded, but no active Pixmap.");
				c.drawRect(0, 0, c.getWidth(), c.getHeight(), mEmptyPaint);
			}
		} finally {
			if (c != null) {
				mSurfaceHolder.unlockCanvasAndPost(c);
			}
		}
	}
	
	/**
	 * triggered by our RenderThread when a new Pixmap is ready
	 */
	@Override
	public void onNewRenderedPixmap(DroidReaderRenderThread.RenderJob theJob) {
		synchronized(mSurfaceHolder) {
			Log.d(TAG, "got a new Pixmap, viewPort: "+theJob.mViewPort.toShortString());
			mActiveViewPort = theJob.mViewPort;
			mPixmapIsCurrent = true;
			mRedraw = true;
			interrupt();
		}
	}
	
	/**
	 * triggered by our UI thread on a scroll event
	 * @param e1 unused
	 * @param e2 unused
	 * @param distanceX handed over to our Scroller
	 * @param distanceY handed over to our Scroller
	 * @return true if we handled the event (always for now)
	 */
	public boolean doScroll(MotionEvent e1, MotionEvent e2,
			float distanceX, float distanceY) {
		Log.d(TAG, "got a Scroll event");
		synchronized(mSurfaceHolder) {
			if(!mScroller.isFinished())
				mScroller.abortAnimation();
			mScroller.startScroll(mOffsetX, mOffsetY, (int)distanceX, (int)distanceY);
			mPositionDirty = true;
			interrupt();
		}
		Log.d(TAG, "Scroll event handled.");
		return true;
	}

	/**
	 * triggered by our UI thread upon a fling event
	 * @param e1 unused
	 * @param e2 unused
	 * @param velocityX handed over to our Scroller
	 * @param velocityY handed over to our Scroller
	 * @return true if we handled the event (always for now)
	 */
	public boolean doFling(MotionEvent e1, MotionEvent e2, float velocityX,
			float velocityY) {
		Log.d(TAG, "got a Fling event");
		synchronized(mSurfaceHolder) {
			if(!mScroller.isFinished())
				mScroller.abortAnimation();
			// lock the fling to the page size
			mScroller.fling(mOffsetX, mOffsetY,
					-(int)velocityX, -(int)velocityY,
					mScrollableViewPort.left, mScrollableViewPort.right,
					mScrollableViewPort.top, mScrollableViewPort.bottom);
			mPositionDirty = true;
			interrupt();
		}
		Log.d(TAG, "Fling event handled.");
		return true;
	}

	/**
	 * triggered by the UI thread when the surface changed
	 */
	
	public void surfaceChanged() {
		synchronized(mSurfaceHolder) {
			Log.d(TAG, "got informed that the surface changed");
			mFrameDirty = true;
			interrupt();
		}
	}

	public void openPage(PdfDocument doc, int pageno, float zoom, int rotation, int dpiX, int dpiY) {
		synchronized(mSurfaceHolder) {
			Log.d(TAG, "onOpenPage event triggered...");
			try {
				PdfPage page = doc.openPage(pageno);
				float zoomCalc = zoom;
				float zoomW = 1F, zoomH = 1F;
				
				mActiveViewPort = new Rect(0, 0, 0, 0);
				
				// this is the mediaBox in Postscript points (72 points = 1 inch)
				RectF mediaBox = page.getMediaBox();
				// matrix to apply to the mediaBox in order to calculate
				// the page size in pixels
				//Matrix pageSizeMatrix = new Matrix();
				
				// create Matrix for _rendering_ the PDF
				mPageMatrix = new Matrix();
				// the special thing is that in PDF, the coordinates (0,0) specify
				// the _lower_ left corner, not the _upper_ left corner.
				// so the coordinate system is basically mirrored on the x axis
				// hence we scale the y dimension by -1
				// and since we mirrored, we must move everything upwards
				mPageMatrix.postScale(1, -1);
				mPageMatrix.postTranslate(-mediaBox.left, mediaBox.bottom);
				mPageMatrix.postRotate(page.rotate + rotation);
				switch((page.rotate + rotation) % 360) {
				case 270:
					zoomW = mSurfaceHolder.getSurfaceFrame().width() * 72 / mediaBox.height() / dpiX;
					zoomH = mSurfaceHolder.getSurfaceFrame().height() * 72 / mediaBox.width() / dpiY;
					mPageMatrix.postTranslate(0, mediaBox.width());
					break;
				case 180:
					zoomW = mSurfaceHolder.getSurfaceFrame().width() * 72 / mediaBox.width() / dpiX;
					zoomH = mSurfaceHolder.getSurfaceFrame().height() * 72 / mediaBox.height() / dpiY;
					mPageMatrix.postTranslate(mediaBox.width(), mediaBox.height());
					break;
				case 90:
					zoomW = mSurfaceHolder.getSurfaceFrame().width() * 72 / mediaBox.height() / dpiX;
					zoomH = mSurfaceHolder.getSurfaceFrame().height() * 72 / mediaBox.width() / dpiY;
					mPageMatrix.postTranslate(mediaBox.height(), 0);
					break;
				case 0:
					zoomW = mSurfaceHolder.getSurfaceFrame().width() * 72 / mediaBox.width() / dpiX;
					zoomH = mSurfaceHolder.getSurfaceFrame().height() * 72 / mediaBox.height() / dpiY;
				}
				
				switch((int)zoomCalc) {
				case DroidReaderView.ZOOM_FIT:
					if(zoomW < zoomH)
						zoomCalc = zoomW;
					else
						zoomCalc = zoomH;
					break;
				case DroidReaderView.ZOOM_FIT_WIDTH:
					zoomCalc = zoomW;
					break;
				case DroidReaderView.ZOOM_FIT_HEIGHT:
					zoomCalc = zoomH;
				}

				
				mPageMatrix.postScale((float) zoomCalc * dpiX / 72, (float) zoomCalc * dpiY / 72);
				
				// similar for the calculation of the resulting page size in
				// pixels, but we can ignore the different coordinate systems
				// (but not the zoom factor/rotation)
				//pageSizeMatrix.postScale((float) zoomCalc * dpiX / 72, (float) zoomCalc * dpiY / 72);
				//pageSizeMatrix.mapRect(mediaBox);
				mPageMatrix.mapRect(mediaBox);
				// sets mPageSize:
				mediaBox.round(mPageSize);
				
				// initializes the upper left display coordinates
				mOffsetX = 0;
				mOffsetY = 0;
				
				// finally, start the thread that will render for us:
				Log.d(TAG, "starting a new RenderThread...");
				mRenderThread = new DroidReaderRenderThread(this, mActivePixmap, page);
				mRenderThread.start();
				mPageLoaded = true;
			} catch(PageLoadException e) {
				// TODO: handle this!
			}
			// trigger update of the view
			mFrameDirty = true;
			interrupt();
		}
	}

	public void closePage() {
		synchronized(mSurfaceHolder) {
			// shut down our render thread, if running:
			Log.d(TAG, "closing page: shutting down the RenderThread");
			try {
				boolean retry = true;
				mRenderThread.mRun = false;
				mRenderThread.interrupt();
				while (retry) {
					try {
						mRenderThread.join();
						retry = false;
					} catch (InterruptedException e) {
					}
				}
			} catch(NullPointerException e) {
				// there wasn't any thread running
			}
			
			mPixmapIsCurrent = false;
			mPageLoaded = false;
			
			// set page size to zero
			mPageSize = new Rect(0, 0, 0, 0);
			
			// trigger a redraw of the view
			mRedraw = true;
			interrupt();
		}
	}
}
