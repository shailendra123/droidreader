# Copyright (C) 2010 Hans-Werner Hilse <hilse@web.de>
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <http://www.gnu.org/licenses/>.
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.




LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

# compile the needed libraries into one big archive file

LOCAL_MODULE := mupdf

# jpeg-7
# uses pristine source tree

# Homepage: http://www.ijg.org/
# Original Licence: see jpeg-7/README
# Original Copyright (C) 1991-2009, Thomas G. Lane, Guido Vollbeding

MY_JPEG_SRC_FILES := \
	jpeg-7/jcapimin.c \
	jpeg-7/jcapistd.c \
	jpeg-7/jcarith.c \
	jpeg-7/jctrans.c \
	jpeg-7/jcparam.c \
	jpeg-7/jdatadst.c \
	jpeg-7/jcinit.c \
	jpeg-7/jcmaster.c \
	jpeg-7/jcmarker.c \
	jpeg-7/jcmainct.c \
	jpeg-7/jcprepct.c \
	jpeg-7/jccoefct.c \
	jpeg-7/jccolor.c \
	jpeg-7/jcsample.c \
	jpeg-7/jchuff.c \
	jpeg-7/jcdctmgr.c \
	jpeg-7/jfdctfst.c \
	jpeg-7/jfdctflt.c \
	jpeg-7/jfdctint.c \
	jpeg-7/jdapimin.c \
	jpeg-7/jdapistd.c \
	jpeg-7/jdarith.c \
	jpeg-7/jdtrans.c \
	jpeg-7/jdatasrc.c \
	jpeg-7/jdmaster.c \
	jpeg-7/jdinput.c \
	jpeg-7/jdmarker.c \
	jpeg-7/jdhuff.c \
	jpeg-7/jdmainct.c \
	jpeg-7/jdcoefct.c \
	jpeg-7/jdpostct.c \
	jpeg-7/jddctmgr.c \
	jpeg-7/jidctfst.c \
	jpeg-7/jidctflt.c \
	jpeg-7/jidctint.c \
	jpeg-7/jdsample.c \
	jpeg-7/jdcolor.c \
	jpeg-7/jquant1.c \
	jpeg-7/jquant2.c \
	jpeg-7/jdmerge.c \
	jpeg-7/jaricom.c \
	jpeg-7/jcomapi.c \
	jpeg-7/jutils.c \
	jpeg-7/jerror.c \
	jpeg-7/jmemmgr.c \
	jpeg-7/jmemnobs.c

# freetype
# (flat file hierarchy, use 
# "cp .../freetype-.../src/*/*.[ch] freetype/"
#  and copy over the full include/ subdirectory)

# Homepage: http://freetype.org/
# Original Licence: GPL 2 (or its own, but for the purposes
#                   of this project, GPL is fine)
# 

MY_FREETYPE_C_INCLUDES := \
	$(LOCAL_PATH)/freetype/include

MY_FREETYPE_CFLAGS := -DFT2_BUILD_LIBRARY

# libz provided by the Android-3 Stable Native API:
MY_FREETYPE_LDLIBS := -lz

# see freetype/doc/INSTALL.ANY for further customization,
# currently, all sources are being built
MY_FREETYPE_SRC_FILES := \
	freetype/src/base/ftsystem.c \
	freetype/src/base/ftinit.c \
	freetype/src/base/ftdebug.c \
	freetype/src/base/ftbase.c \
	freetype/src/base/ftbbox.c \
	freetype/src/base/ftglyph.c \
	freetype/src/base/ftbdf.c \
	freetype/src/base/ftbitmap.c \
	freetype/src/base/ftcid.c \
	freetype/src/base/ftfstype.c \
	freetype/src/base/ftgasp.c \
	freetype/src/base/ftgxval.c \
	freetype/src/base/ftlcdfil.c \
	freetype/src/base/ftmm.c \
	freetype/src/base/ftotval.c \
	freetype/src/base/ftpatent.c \
	freetype/src/base/ftpfr.c \
	freetype/src/base/ftstroke.c \
	freetype/src/base/ftsynth.c \
	freetype/src/base/fttype1.c \
	freetype/src/base/ftwinfnt.c \
	freetype/src/base/ftxf86.c \
	freetype/src/bdf/bdf.c \
	freetype/src/cff/cff.c \
	freetype/src/cid/type1cid.c \
	freetype/src/pcf/pcf.c \
	freetype/src/pfr/pfr.c \
	freetype/src/sfnt/sfnt.c \
	freetype/src/truetype/truetype.c \
	freetype/src/type1/type1.c \
	freetype/src/type42/type42.c \
	freetype/src/winfonts/winfnt.c \
	freetype/src/raster/raster.c \
	freetype/src/smooth/smooth.c \
	freetype/src/autofit/autofit.c \
	freetype/src/cache/ftcache.c \
	freetype/src/gzip/ftgzip.c \
	freetype/src/lzw/ftlzw.c \
	freetype/src/gxvalid/gxvalid.c \
	freetype/src/otvalid/otvalid.c \
	freetype/src/psaux/psaux.c \
	freetype/src/pshinter/pshinter.c \
	freetype/src/psnames/psnames.c

# mupdf
# pristine source tree

# Homepage: http://ccxvii.net/mupdf/
# Licence: GPL 3
# MuPDF is Copyright 2006-2009 Artifex Software, Inc. 

MY_MUPDF_C_INCLUDES := \
	$(LOCAL_PATH)/freetype/include \
	$(LOCAL_PATH)/jpeg-7 \
	$(LOCAL_PATH)/mupdf/fitzdraw \
	$(LOCAL_PATH)/mupdf/fitz \
	$(LOCAL_PATH)/mupdf/mupdf

MY_MUPDF_CFLAGS := -Drestrict= -DNOCJK

# use this to build w/ a CJK font built-in
# ATM, the irony is that it compiles in a bit-wise copy
# of Androids own droid.ttf ... Maybe resort to pointing
# to it in the filesystem? But this would violate proper
# API use. Bleh.
#LOCAL_CFLAGS := -Drestrict=

MY_MUPDF_SRC_FILES := \
	mupdf/mupdf/pdf_crypt.c \
	mupdf/mupdf/pdf_debug.c \
	mupdf/mupdf/pdf_lex.c \
	mupdf/mupdf/pdf_nametree.c \
	mupdf/mupdf/pdf_open.c \
	mupdf/mupdf/pdf_parse.c \
	mupdf/mupdf/pdf_repair.c \
	mupdf/mupdf/pdf_stream.c \
	mupdf/mupdf/pdf_xref.c \
	mupdf/mupdf/pdf_annot.c \
	mupdf/mupdf/pdf_outline.c \
	mupdf/mupdf/pdf_cmap.c \
	mupdf/mupdf/pdf_cmap_parse.c \
	mupdf/mupdf/pdf_cmap_load.c \
	mupdf/mupdf/pdf_cmap_table.c \
	mupdf/mupdf/pdf_fontagl.c \
	mupdf/mupdf/pdf_fontenc.c \
	mupdf/mupdf/pdf_unicode.c \
	mupdf/mupdf/pdf_font.c \
	mupdf/mupdf/pdf_type3.c \
	mupdf/mupdf/pdf_fontmtx.c \
	mupdf/mupdf/pdf_fontfile.c \
	mupdf/mupdf/pdf_function.c \
	mupdf/mupdf/pdf_colorspace1.c \
	mupdf/mupdf/pdf_colorspace2.c \
	mupdf/mupdf/pdf_image.c \
	mupdf/mupdf/pdf_pattern.c \
	mupdf/mupdf/pdf_shade.c \
	mupdf/mupdf/pdf_shade1.c \
	mupdf/mupdf/pdf_shade4.c \
	mupdf/mupdf/pdf_xobject.c \
	mupdf/mupdf/pdf_build.c \
	mupdf/mupdf/pdf_interpret.c \
	mupdf/mupdf/pdf_page.c \
	mupdf/mupdf/pdf_pagetree.c \
	mupdf/mupdf/pdf_store.c \
	mupdf/fitzdraw/glyphcache.c \
	mupdf/fitzdraw/pixmap.c \
	mupdf/fitzdraw/porterduff.c \
	mupdf/fitzdraw/meshdraw.c \
	mupdf/fitzdraw/imagedraw.c \
	mupdf/fitzdraw/imageunpack.c \
	mupdf/fitzdraw/imagescale.c \
	mupdf/fitzdraw/pathscan.c \
	mupdf/fitzdraw/pathfill.c \
	mupdf/fitzdraw/pathstroke.c \
	mupdf/fitzdraw/render.c \
	mupdf/fitzdraw/blendmodes.c \
	mupdf/fitz/base_cpudep.c \
	mupdf/fitz/base_error.c \
	mupdf/fitz/base_hash.c \
	mupdf/fitz/base_matrix.c \
	mupdf/fitz/base_memory.c \
	mupdf/fitz/base_rect.c \
	mupdf/fitz/base_string.c \
	mupdf/fitz/base_unicode.c \
	mupdf/fitz/util_getopt.c \
	mupdf/fitz/crypt_aes.c \
	mupdf/fitz/crypt_arc4.c \
	mupdf/fitz/crypt_crc32.c \
	mupdf/fitz/crypt_md5.c \
	mupdf/fitz/obj_array.c \
	mupdf/fitz/obj_dict.c \
	mupdf/fitz/obj_parse.c \
	mupdf/fitz/obj_print.c \
	mupdf/fitz/obj_simple.c \
	mupdf/fitz/stm_buffer.c \
	mupdf/fitz/stm_filter.c \
	mupdf/fitz/stm_open.c \
	mupdf/fitz/stm_read.c \
	mupdf/fitz/stm_misc.c \
	mupdf/fitz/filt_pipeline.c \
	mupdf/fitz/filt_basic.c \
	mupdf/fitz/filt_arc4.c \
	mupdf/fitz/filt_aesd.c \
	mupdf/fitz/filt_dctd.c \
	mupdf/fitz/filt_faxd.c \
	mupdf/fitz/filt_faxdtab.c \
	mupdf/fitz/filt_flate.c \
	mupdf/fitz/filt_lzwd.c \
	mupdf/fitz/filt_predict.c \
	mupdf/fitz/node_toxml.c \
	mupdf/fitz/node_misc1.c \
	mupdf/fitz/node_misc2.c \
	mupdf/fitz/node_path.c \
	mupdf/fitz/node_text.c \
	mupdf/fitz/node_tree.c \
	mupdf/fitz/res_colorspace.c \
	mupdf/fitz/res_font.c \
	mupdf/fitz/res_image.c \
	mupdf/fitz/res_shade.c \
	fonts/font_mono.c \
	fonts/font_serif.c \
	fonts/font_sans.c \
	fonts/font_misc.c \
	cmaps/cmap_cns.c \
	cmaps/cmap_korea.c \
	cmaps/cmap_tounicode.c \
	cmaps/cmap_japan.c \
	cmaps/cmap_gb.c

# omitted when building w/o CJK support:
#	fonts/font_cjk.c

# uses libz, which is officially supported for NDK API
MY_MUPDF_LDLIBS := -lz

LOCAL_CFLAGS := \
	$(MY_FREETYPE_CFLAGS) \
	$(MY_MUPDF_CFLAGS)
LOCAL_C_INCLUDES := \
	$(MY_FREETYPE_C_INCLUDES) \
	$(MY_MUPDF_C_INCLUDES)
LOCAL_LDLIBS := \
	$(MY_FREETYPE_LDLIBS) \
	$(MY_MUPDF_LDLIBS)
LOCAL_SRC_FILES := \
	$(MY_JPEG_SRC_FILES) \
	$(MY_FREETYPE_SRC_FILES) \
	$(MY_MUPDF_SRC_FILES)

include $(BUILD_STATIC_LIBRARY)

include $(CLEAR_VARS)

# and finally, the module for our JNI interface, which is compiled
# to a shared library which then includes only the needed parts
# from the static archive we compiled above

LOCAL_MODULE    := pdfrender

LOCAL_SRC_FILES := \
	pdfrender.c

LOCAL_C_INCLUDES := \
	$(LOCAL_PATH)/mupdf/fitz \
	$(LOCAL_PATH)/mupdf/mupdf

LOCAL_STATIC_LIBRARIES := mupdf

# uses Android log and z library (Android-3 Native API)
LOCAL_LDLIBS := -llog -lz

include $(BUILD_SHARED_LIBRARY)

