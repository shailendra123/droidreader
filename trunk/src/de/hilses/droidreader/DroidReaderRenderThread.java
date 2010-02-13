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

import android.graphics.Matrix;
import android.graphics.Rect;
import android.util.Log;

/**
 * renders Pixmaps for PdfPage objects and manages a LIFO queue for requests
 */
class DroidReaderRenderThread extends Thread {
	/**
	 * interface for notification on ready rendered bitmaps
	 */
	interface RenderListener {
		/**
		 * is called by the RenderThread when a new Pixmap is ready
		 * 
		 * @param theJob is the RenderJob for which the rendering was done
		 */
		public void onNewRenderedPixmap(RenderJob theJob);
	}
	
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
	 * we notify the Listener upon a new ready rendered Pixmap
	 */
	protected final RenderListener mListener;
	
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
	boolean mRun;
	
	/**
	 * the PdfPage object we will render
	 */
	protected final PdfPage mPage;
	
	/**
	 * constructs a new render thread
	 * @param object listening for ready Pixmaps (implementing the right interface)
	 */
	public DroidReaderRenderThread(RenderListener listener,
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
