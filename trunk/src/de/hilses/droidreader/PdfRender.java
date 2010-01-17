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
import android.graphics.RectF;

import java.lang.String;

/**
 * Class that just loads the JNI lib and holds some basic configuration
 */
final class PdfRender {
	/**
	 * how much bytes are stored for one pixel
	 */
	protected static int bytesPerPixel = 4;
	/**
	 * how much memory is the MuPDF backend allowed to use
	 */
	protected static int fitzMemory = 512 * 1024;

	static {
		/* JNI: load our native library */
		System.loadLibrary("pdfrender");
	}
	
	/**
	 * Helper method for converting a Rect into an int[4]
	 * @param viewbox the Rect to convert
	 * @return int[4] with the left, top, right, bottom values
	 */
	static int[] getBox(Rect viewbox) {
		int[] rect = { viewbox.left, viewbox.top,
				viewbox.right, viewbox.bottom };
		return rect;
	}
	
	/**
	 * Helper method for converting a Matrix into a float[6], just
	 * as MuPDF defines it's matrizes
	 * @param matrix the Matrix to convert
	 * @return float[6] with the left two rows of the matrix
	 */
	static float[] getMatrix(Matrix matrix) {
		float[] matrixSource = {
				0, 0, 0,
				0, 0, 0,
				0, 0, 0   };
		matrix.getValues(matrixSource);
		float[] matrixArr = {
				matrixSource[0], matrixSource[3],
				matrixSource[1], matrixSource[4],
				matrixSource[2], matrixSource[5]};
		return matrixArr;
	}
}

/**
 * Instantiate this for each PDF document
 */
class PdfDocument {
	/**
	 * the native MuPDF backend will set this to the document title
	 */
	public String metaTitle;
	/**
	 * backend sets this to the number of pages
	 */
	public int pagecount;
	
	/**
	 * this will be used to store a C Pointer (ick!) to the
	 * structure holding our references in the native code
	 */
	protected long mHandle = -1;
	
	/**
	 * will open a PDF document
	 * @param fitzMemory the memory that the MuPDF rendering backend is allowed to claim
	 * @param filename file to be opened
	 * @param password password for the PDF
	 * @return new handle
	 */
	private native long nativeOpen(
			int fitzMemory,
			String filename, String password);
	
	/**
	 * open a PDF
	 * @param filename the PDF file
	 * @param password the password to use for opening
	 */
	public PdfDocument(String filename, String password) {
		mHandle = this.nativeOpen(
				PdfRender.fitzMemory, filename, password);
	}
	
	/**
	 * close a PDF document
	 * @param dochandle the handle that was returned upon opening the PDF
	 */
	private native void nativeClose(long dochandle);
	
	/**
	 * this cleans up the memory used by this document. The document
	 * cannot be used after calling this!
	 */
	public void done() {
		if(mHandle != 0) {
			this.nativeClose(mHandle);
			mHandle = 0;
		}
	}
	
	/**
	 * destructor, cleans up memory
	 */
	public void finalize() {
		this.done();
	}
	
	/**
	 * convenience function that instantiates a new PdfPage for a given page number
	 * within the current document
	 * @param page the page number (page numbers starting at 1!)
	 * @return the resulting PdfPage object
	 */
	public PdfPage openPage(int page) {
		return new PdfPage(this, page);
	}
}

/**
 * references a page within a PdfDocument
 */
class PdfPage {
	/**
	 * the rotation that the page wants to apply
	 */
	public int rotate;
	/**
	 * the number of this page
	 */
	public int no;
	/**
	 * the MediaBox for the page, still as float[4]
	 */
	protected float[] mMediabox = {0, 0, 0, 0};
	/**
	 * a reference to the PdfDocument this page belongs to
	 */
	protected PdfDocument mDoc;

	/**
	 * this will be used to store a C Pointer (ick!) to the
	 * structure holding our references in the native code
	 */
	protected long mHandle = 0;
	
	/**
	 * calls the native code to open a page
	 * @param dochandle the Handle (C pointer) for the document
	 * @param mediabox this will be set to the mediabox of the page
	 * @param no page number to open
	 * @return handle for the opened page
	 */
	private native long nativeOpenPage(long dochandle, float[] mediabox, int no);

	/**
	 * constructs a new PdfPage object for a given page in a given document
	 * @param doc the PdfDocument
	 * @param no the number of the page (starting at 1) to open
	 */
	public PdfPage(PdfDocument doc, int no) {
		mDoc = doc;
		this.no = no;
		mHandle = this.nativeOpenPage(mDoc.mHandle, mMediabox, no);
	}
	
	/**
	 * cleans up the memory we claimed in native code
	 * @param pagehandle the handle we got upon opening the page
	 */
	private native void nativeClosePage(long pagehandle);

	/**
	 * clean up memory. Note that the object must not be used afterwards!
	 */
	public void done() {
		if(mHandle != 0) {
			this.nativeClosePage(mHandle);
			mHandle = 0;
		}
	}
	
	/**
	 * destructor caring for cleaning up memory
	 */
	public void finalize() {
		this.done();
	}
	
	/**
	 * getter for the MediaBox of the opened page
	 * @return RectF holding the MediaBox
	 */
	public RectF getMediaBox() {
		return new RectF(mMediabox[0], mMediabox[1], mMediabox[2], mMediabox[3]);
	}
}

/**
 * Object that controls rendering parts of pages to a int[] pixmap buffer
 */
class PdfView {
	/**
	 * the pixmap we will render to
	 */
	public int[] buf;

	/**
	 * Call native code to render part of a page to a buffer
	 * @param dochandle the handle of the document for which we render
	 * @param pagehandle the handle of the page for which we render
	 * @param viewbox the excerpt that we should render, given as int[4] rectangle
	 * @param matrix the transformation matrix used for rendering, MuPDF format (float[6])
	 * @param buffer the int[] buffer we render to
	 */
	private native void nativeCreateView(
			long dochandle, long pagehandle,
			int[] viewbox, float[] matrix, int[] buffer);

	/**
	 * Render part of the page to a int[] buffer
	 * @param page the PdfPage we render for
	 * @param viewbox the excerpt Rect that we should render (coordinates after applying the matrix)
	 * @param matrix the Matrix used for rendering
	 */
	public void render(PdfPage page, Rect viewbox, Matrix matrix) {
		int size = viewbox.width() * viewbox.height()
				* ((PdfRender.bytesPerPixel * 8) / 32);
		if((buf == null) || (buf.length != size))
			buf = new int[size];
		this.nativeCreateView(
				page.mDoc.mHandle, page.mHandle,
				PdfRender.getBox(viewbox), PdfRender.getMatrix(matrix), buf);
	}
}