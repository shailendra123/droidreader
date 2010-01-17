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

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;

import java.lang.String;

/**
 * Class that just loads the JNI lib and holds some basic configuration
 */
class PdfRender {
	/* how much bytes are stored for one pixel */
	protected static int bytesPerPixel = 4;
	/* Android bitmap configuration */
	protected static Bitmap.Config bitmapConfig = Bitmap.Config.ARGB_8888;
	/* how much memory is the MuPDF backend allowed to use */
	protected static int fitzMemory = 512 * 1024;

	static {
		/* JNI: load our native library */
		System.loadLibrary("pdfrender");
	}
}

/**
 * Instantiate this for each PDF document
 */
class PdfDocument {
	public String metaTitle;
	public int pagecount;
	
	long mHandle = 1;
	
	private native long nativeOpen(
			int fitzMemory,
			String filename, String password);
	public PdfDocument(String filename, String password) {
		mHandle = this.nativeOpen(
				PdfRender.fitzMemory, filename, password);
	}
	
	private native long nativeClose(long dochandle);
	public void done() {
		if(mHandle != 0) {
			mHandle = this.nativeClose(mHandle);
			mHandle = 0;
		}
	}
	public void finalize() {
		this.done();
	}
	
	public PdfPage openPage(int page) {
		return new PdfPage(this, page);
	}
}

class PdfPage {
	public int rotate;
	public int no;
	protected float[] mMediabox = {0, 0, 0, 0};
	protected PdfDocument mDoc;

	protected long mHandle = 0;
	
	private native long nativeOpenPage(long dochandle, float[] mediabox, int no);

	public PdfPage(PdfDocument doc, int no) {
		mDoc = doc;
		this.no = no;
		mHandle = this.nativeOpenPage(mDoc.mHandle, mMediabox, no);
	}
	
	private native long nativeClosePage(long pagehandle);

	public void done() {
		if(mHandle != 0) {
			mHandle = this.nativeClosePage(mHandle);
			mHandle = 0;
		}
	}
	
	public void finalize() {
		this.done();
	}
	
	public RectF getMediaBox() {
		return new RectF(mMediabox[0], mMediabox[1], mMediabox[2], mMediabox[3]);
	}
	
	static int[] getBox(Rect viewbox) {
		int[] rect = { viewbox.left, viewbox.top,
				viewbox.right, viewbox.bottom };
		return rect;
	}
	
	static float[] getMatrix(Matrix matrix) {
		float[] matrixSource = { 0, 0, 0, 0, 0, 0, 0, 0, 0 };
		matrix.getValues(matrixSource);
		float[] matrixArr = {
				matrixSource[0], matrixSource[3],
				matrixSource[1], matrixSource[4],
				matrixSource[2], matrixSource[5]};
		return matrixArr;
	}
}

class PdfView {
	protected long mHandle = 0;
	int[] buf;

	private native void nativeCreateView(
			long dochandle, long pagehandle,
			int[] viewbox, float[] matrix, int[] buffer);

	void render(PdfPage page, Rect viewbox, Matrix matrix) {
		int size = viewbox.width() * viewbox.height()
				* ((PdfRender.bytesPerPixel * 8) / 32);
		if((buf == null) || (buf.length != size))
			buf = new int[size];
		this.nativeCreateView(
				page.mDoc.mHandle, page.mHandle,
				PdfPage.getBox(viewbox), PdfPage.getMatrix(matrix), buf);
	}
	
	boolean hasData() {
		if(mHandle != 0)
			return true;
		return false;
	}
}