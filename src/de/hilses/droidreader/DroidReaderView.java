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
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.Log;
import android.view.GestureDetector.OnGestureListener;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Scroller;

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
	 * Holds the actual data for a RenderJob to be cared of by the render thread
	 */
	class RenderJob {
		/**
		 * time the render job should wait before starting the rendering
		 */
		long mLazyStart;
		/**
		 * view port (excerpt) that we want to be rendered
		 */
		Rect mViewPort;
		/**
		 * transformation matrix for rendering the PDF
		 */
		Matrix mMatrix;
		/**
		 * internal flag which always starts as "true"
		 */
		boolean mIsNew;
		
		/**
		 * constructs a new empty RenderJob, only used by the RenderThread internally.
		 */
		RenderJob() {
			mIsNew = false;
		}
		
		/**
		 * constructs a new RenderJob with all needed parameters
		 * @param page the PdfPage object to render from
		 * @param viewPort the box out of the (virtual) full rendered Page
		 * @param matrix the transformation Matrix for rendering the PDF
		 * @param lazyStart the interruptable time (in milliseconds) to wait before starting
		 */
		RenderJob(PdfPage page, Rect viewPort, Matrix matrix, long lazyStart) {
			mLazyStart = lazyStart;
			mViewPort = viewPort;
			mMatrix = matrix;
			mIsNew = true;
		}
		
		/**
		 * copy method that fills in the values, copying them from another RenderJob
		 * @param job the RenderJob from which the values are copied
		 */
		public void copyFrom(RenderJob job) {
			mLazyStart = job.mLazyStart;
			mViewPort = job.mViewPort;
			mMatrix = job.mMatrix;
			mIsNew = job.mIsNew;
		}
	}
	
	/**
	 * interface for notification on ready rendered bitmaps
	 */
	interface DroidReaderRenderListener {
		/**
		 * is called by the RenderThread when a new Pixmap is ready
		 * 
		 * @param theJob is the RenderJob for which the rendering was done
		 */
		public void onNewRenderedPixmap(RenderJob theJob);
	}
	
	/**
	 * renders Pixmaps for PdfPage objects and manages a LIFO queue for requests
	 */
	class DroidReaderRenderThread extends Thread {
		/**
		 * we notify the Listener upon a new ready rendered Pixmap
		 */
		protected final DroidReaderRenderListener mListener;
		
		/**
		 * the current RenderJob that we either are waiting to work on
		 * or currently rendering
		 */
		protected final RenderJob mCurrentRenderJob = new RenderJob();
		/**
		 * the input side of the render queue
		 */
		protected final RenderJob mNextRenderJob = new RenderJob();
		
		/**
		 * Helper for debugging
		 */
		protected final static String TAG = "DroidReaderRenderThread";
		
		/**
		 * the Pixmap we're rendering to
		 */
		protected final PdfView mPixmap;
		
		/**
		 * Thread state keeper
		 */
		private boolean mRun;
		
		/**
		 * the PdfPage object we will render
		 */
		protected final PdfPage mPage;
		
		/**
		 * constructs a new render thread
		 * @param object listening for ready Pixmaps (implementing the right interface)
		 */
		public DroidReaderRenderThread(DroidReaderRenderListener listener,
				PdfView pixmap, PdfPage page) {
			mListener = listener;
			mPixmap = pixmap;
			mPage = page;
			mRun = true;
		}
		
		/**
		 * adds a new RenderJob to the queue or replaces the job that is currently
		 * waiting, deliberately not taking new objects in order to keep the
		 * instantiation of new objects (memory pressure!) low.
		 * 
		 * @param page PdfPage instance to be rendered
		 * @param viewPort the view port we want to be rendered
		 * @param matrix the rendering matrix
		 * @param lazyStart the time in milliseconds to wait for the rendering
		 */
		public void newRenderJob(Rect viewPort, Matrix matrix, long lazyStart) {
			Log.d(TAG, "got a new render job: " + viewPort.toShortString());
			// lock on mNextRenderJob, which is the object we want to modify
			synchronized(mNextRenderJob) {
				if(viewPort.equals(mNextRenderJob.mViewPort)) {
					// same viewport as the job in the queue
					// note that the data in mNextRenderJob might also
					// already been rendered and is just a stale dataset
					Log.d(TAG, "but it's already in the queue, ignoring.");
				} else {
					// new job, so set the queue entrance's data:
					mNextRenderJob.mViewPort = viewPort;
					mNextRenderJob.mMatrix = matrix;
					mNextRenderJob.mLazyStart = lazyStart;
					mNextRenderJob.mIsNew = true;
					// wake up the thread:
					interrupt();
				}
			}
		}
		
		/**
		 * Thread run() loop, stopping only when mRun is set to false
		 */
		@Override
		public void run() {
			// keeps track of whether the while loop goes to sleep before
			// restarting:
			boolean doSleep;
			while (mRun) {
				Log.d(TAG, "starting loop");
				// we default to sleep after this run
				doSleep = true;
				// step #1: check if there is a new job that we should render
				//          at the start of the queue, so we lock on the
				//          start of the queue:
				synchronized(mCurrentRenderJob) {
					if(mCurrentRenderJob.mIsNew) {
						// it's a new job that we did not yet render
						Log.d(TAG, "got a new current render job");
						try {
							// if we are to wait some time before starting to
							// render, we do that here:
							if(mCurrentRenderJob.mLazyStart > 0)
								Thread.sleep(mCurrentRenderJob.mLazyStart);
							
							// flag this Job as not being new anymore:
							mCurrentRenderJob.mIsNew = false;
							
							// note that this point may never be reached when
							// we got interrupt()ed...
							Log.d(TAG, "now rendering the current render job");
							try {
								// lock our PdfPage object and render it
								synchronized(mPage) {
									Log.d(TAG, "locked the page object");
									// do the rendering, consumes some seconds
									// of precious CPU time...
									synchronized(mPixmap) {
										try{
											mPixmap.render(
												mPage,
												mCurrentRenderJob.mViewPort,
												mCurrentRenderJob.mMatrix);
										} catch(PageRenderException e) {
											// TODO: Handle this
										}
										Log.d(TAG, "now triggering the view thread");
									}
									mListener.onNewRenderedPixmap(
											mCurrentRenderJob);
								}
							} catch(NullPointerException e) {
								Log.e(TAG, "PdfPage field became invalid!");
							}
						} catch (InterruptedException e) {
							// we were interrupted, so there is probably
							// a new rendering job pending which
							// interrupted our sleeping, which we
							// will recognize in the next code block
							Log.d(TAG, "got interrupted before rendering the current render job");
							synchronized(mNextRenderJob) {
								if(mNextRenderJob.mIsNew != true) {
									Log.d(TAG, "but there is no new render job, so retry.");
									doSleep = false;
								}
							}
						}
					}
				}
				// step #2: check if there is a new job that we should move through
				//          the queue towards the start position. locks on the
				//          start of the queue:
				synchronized(mNextRenderJob) {
					// we check on mRun also because we might also got interrupt()ed
					// above in step #1 while waiting in order to die...
					if(mRun && mNextRenderJob.mIsNew) {
						Log.d(TAG, "got a new next render job, waiting to make it the current one...");
						// log the "current" position:
						synchronized(mCurrentRenderJob) {
							// now put the new job into the "current" position.
							// note that this keeps the data in mNextRenderJob's
							// fields, so the duplicate check for new jobs will
							// still trigger a duplicate
							mCurrentRenderJob.copyFrom(mNextRenderJob);
							// re-loop immediately in order to start step #1
							// for new "current" job
							doSleep = false;
							// flag the queue entrance as old/seen
							mNextRenderJob.mIsNew = false;
							Log.d(TAG, "made the next render job the current render job");
						}
					}
				}
				// if we're still running (we might have gotten interrupt()ed
				// above in step #1) and we shouldn't restart the loop
				// immediately, we will go to sleep
				if(mRun && doSleep) {
					try {
						// no new jobs, old job properly done,
						// so we have nothing to do...
						Log.d(TAG, "RenderThread going to sleep");
						Thread.sleep(3600000);
					} catch (InterruptedException e) {
						Log.d(TAG, "RenderThread woken up");
					}
				}
			}
			Log.d(TAG, "shutting down.");
			// when we finish, we close the PdfPage object:
			mPage.done();
		}
	}

	/**
	 * Thread that cares for blitting Pixmaps onto the Canvas and handles scrolling
	 */
	class DroidReaderViewThread extends Thread
	implements DroidReaderRenderListener {
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
					c.drawText(mContext.getString(R.string.status_no_document),
							c.getClipBounds().left,
							c.getClipBounds().bottom - mStatusPaint.getFontSpacing(),
							mStatusPaint);
				} else if(mPixmapIsCurrent) {
					// we have both page and Pixmap, so draw:
					Log.d(TAG, "rendering Pixmap at (" + (-mOffsetX + mActiveViewPort.left) + "," +
							(-mOffsetY + mActiveViewPort.top) + ")");
					synchronized(mActivePixmap) {
						// background:
						c.drawRect(0, 0, c.getWidth(), c.getHeight(), mEmptyPaint);
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
					c.drawText(mContext.getString(R.string.status_page_rendering),
							c.getClipBounds().left,
							c.getClipBounds().bottom - mStatusPaint.getFontSpacing(),
							mStatusPaint);
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
		public void onNewRenderedPixmap(RenderJob theJob) {
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
					
					mActiveViewPort = new Rect(0, 0, 0, 0);
					
					// this is the mediaBox in Postscript points (72 points = 1 inch)
					RectF mediaBox = page.getMediaBox();
					// matrix to apply to the mediaBox in order to calculate
					// the page size in pixels
					Matrix pageSizeMatrix = new Matrix();
					
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
						mPageMatrix.postTranslate(0, mediaBox.width());
						break;
					case 180:
						mPageMatrix.postTranslate(mediaBox.width(), mediaBox.height());
						break;
					case 90:
						mPageMatrix.postTranslate(mediaBox.height(), 0);
					}
					mPageMatrix.postScale((float) zoom * dpiX / 72, (float) zoom * dpiY / 72);
					
					// similar for the calculation of the resulting page size in
					// pixels, but we can ignore the different coordinate systems
					// (but not the zoom factor/rotation)
					pageSizeMatrix.postScale((float) zoom * dpiX / 72, (float) zoom * dpiY / 72);
					pageSizeMatrix.mapRect(mediaBox);
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

	/**
	 * Debug helper
	 */
	protected final static String TAG = "DroidReaderView";

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