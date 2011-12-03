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

#include <jni.h>

#include <android/log.h>
#include <errno.h>

#include <fitz.h>
#include <mupdf.h>
#include <time.h>

/************************************************************************/
/* Macros: */

/* Bytes per pixel */

#define BYPP 4

/* Number of cache structs for page preloading */
#define NUM_CACHE_STRUCTS               (2)

/* Bit masks for page load options */
#define PDF_PAGE_MEMORY_HOG             (1)

/* Bit masks for rendering options */
#define PDF_RENDER_DISPLAY_INVERT		(1)

/* Debugging helper */

#ifdef PDFRENDER_DEBUG
#define DEBUG(args...) \
    __android_log_print(ANDROID_LOG_DEBUG, "PdfRender", args)
#define INFO(args...) \
    __android_log_print(ANDROID_LOG_INFO, "PdfRender", args)
#else
#define DEBUG(args...) {}
#define INFO(args...) {}
#endif

#define ERROR(args...) \
    __android_log_print(ANDROID_LOG_ERROR, "PdfRender", args)

/* Exception classes */

#define EXC						"java/lang/Exception"
#define EXC_CANNOT_REPAIR		"de/hilses/droidreader/CannotRepairException"
#define EXC_CANNOT_DECRYPTXREF	"de/hilses/droidreader/CannotDecryptXrefException"
#define EXC_NEED_PASSWORD		"de/hilses/droidreader/PasswordNeededException"
#define EXC_PAGELOAD			"de/hilses/droidreader/PageLoadException"
#define EXC_PAGERENDER			"de/hilses/droidreader/PageRenderException"
#define EXC_WRONG_PASSWORD		"de/hilses/droidreader/WrongPasswordException"

/* You'll find ugly double typecasts below. They are just there to
 * get rid of compiler warnings.
 */
/************************************************************************/

typedef struct renderpage_s renderpage_t;
struct renderpage_s
{
    fz_displaylist *list;
    fz_rect contentbox;
    fz_rect pagebox;
    int rotate;
    int pageNo;
};

typedef struct renderdocument_s renderdocument_t;
struct renderdocument_s
{
    pdf_xref *xref;
    pdf_outline *outline;
    int isMemoryHog;
    int currentlyDisplayedPage;
    renderpage_t *pages[NUM_CACHE_STRUCTS];
};

/**
 * we cache a reference to the JVM here
 */
JavaVM *cached_jvm;

/* Fitz info */
fz_glyphcache *glyphcache;

/************************************************************************/

/* our own helper functions: */

void throw_exception(JNIEnv *env, char *exception_class, char *message)
{
    jthrowable new_exception = (*env)->FindClass(env, exception_class);
    if(new_exception == NULL) {
        ERROR("cannot create Exception '%s', Message was '%s'",
                exception_class, message);
        return;
    } else {
        DEBUG("Exception '%s', Message: '%s'", exception_class, message);
    }
    (*env)->ThrowNew(env, new_exception, message);
}

/* a callback to retrieve font file names */

fz_error
pdf_getfontfile(pdf_fontdesc *font, char *fontname, char *collection, char **filename)
{
    JNIEnv *env;
    jboolean iscopy;
    jclass pdfrender;
    jclass fontproviderclass;
    jfieldID fontproviderfield;
    jobject fontprovider;
    jmethodID getfontfilemethod;
    jstring fontfilestring;
    jstring fontnamestring;
    jstring collectionstring;
    char *filenamebuf;

    DEBUG("pdf_getfontfile(%p, '%s', '%s')", font, fontname, collection);

    if((*cached_jvm)->GetEnv(cached_jvm, (void **)&env, JNI_VERSION_1_2) != JNI_OK)
        return fz_throw("cannot find our JNI env!");

    pdfrender = (*env)->FindClass(env, "de/hilses/droidreader/PdfRender");
    if(pdfrender == NULL)
        return fz_throw("cannot find JNI interface class");

    fontproviderfield = (*env)->GetStaticFieldID(env, pdfrender,
            "fontProvider", "Lde/hilses/droidreader/FontProvider;");
    if(fontproviderfield == NULL)
        return fz_throw("cannot find fontProvider field");

    fontprovider = (*env)->GetStaticObjectField(env, pdfrender, fontproviderfield);
    if(fontprovider == NULL)
        return fz_throw("cannot access fontProvider field");

    fontproviderclass = (*env)->GetObjectClass(env, fontprovider);
    if(fontproviderclass == NULL)
        return fz_throw("cannot get class for fontProvider field content");

    getfontfilemethod = (*env)->GetMethodID(env, fontproviderclass,
            "getFontFile",
            "(Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/String;");
    if(getfontfilemethod == NULL)
        return fz_throw("cannot find method getFontFile() in fontProvider");

    fontnamestring = (*env)->NewStringUTF(env, fontname);
    collectionstring = (*env)->NewStringUTF(env, collection);

    fontfilestring = (*env)->CallObjectMethod(
            env, fontprovider, getfontfilemethod,
            fontnamestring,
            collectionstring,
            (jint) font->flags);

    /* TODO: release some references?!?
    (*env)->DeleteLocalRef(env, fontnamestring);
    (*env)->DeleteLocalRef(env, collectionstring);
    */

    if(fontfilestring == NULL)
        return fz_throw("could not get filename for font");

    filenamebuf = (char *)(*env)->GetStringUTFChars(env, fontfilestring, &iscopy);
    *filename = fz_strdup(filenamebuf);
    (*env)->ReleaseStringUTFChars(env, fontfilestring, filenamebuf);

    DEBUG("got font file: '%s'", *filename);
    return fz_okay;
}

fz_error
pdf_getfontbuffer(pdf_fontdesc *font, char *fontname, char *collection, unsigned char **data, unsigned int *len) {
    JNIEnv *env;
    jboolean iscopy;
    jclass pdfrender;
    jclass fontproviderclass;
    jfieldID fontproviderfield;
    jobject fontprovider;
    jmethodID getfontbuffermethod;
    jobject fontbuffer;
    jstring fontnamestring;
    jstring collectionstring;

    DEBUG("pdf_getfontbuffer(%p, '%s', '%s')", font, fontname, collection);

    if((*cached_jvm)->GetEnv(cached_jvm, (void **)&env, JNI_VERSION_1_2) != JNI_OK)
        return fz_throw("cannot find our JNI env!");

    pdfrender = (*env)->FindClass(env, "de/hilses/droidreader/PdfRender");
    if(pdfrender == NULL)
        return fz_throw("cannot find JNI interface class");

    fontproviderfield = (*env)->GetStaticFieldID(env, pdfrender,
            "fontProvider", "Lde/hilses/droidreader/FontProvider;");
    if(fontproviderfield == NULL)
        return fz_throw("cannot find fontProvider field");

    fontprovider = (*env)->GetStaticObjectField(env, pdfrender, fontproviderfield);
    if(fontprovider == NULL)
        return fz_throw("cannot access fontProvider field");

    fontproviderclass = (*env)->GetObjectClass(env, fontprovider);
    if(fontproviderclass == NULL)
        return fz_throw("cannot get class for fontProvider field content");

    getfontbuffermethod = (*env)->GetMethodID(env, fontproviderclass,
            "getFontBuffer",
            "(Ljava/lang/String;Ljava/lang/String;I)Ljava/nio/ByteBuffer;");
    if(getfontbuffermethod == NULL)
        return fz_throw("cannot find method getFontBuffer() in fontProvider");

    fontnamestring = (*env)->NewStringUTF(env, fontname);
    collectionstring = (*env)->NewStringUTF(env, collection);

    fontbuffer = (*env)->CallObjectMethod(
            env, fontprovider, getfontbuffermethod,
            fontnamestring,
            collectionstring,
            (jint) font->flags);
/* TODO: release some references?!?
    (*env)->DeleteLocalRef(env, fontnamestring);
    (*env)->DeleteLocalRef(env, collectionstring);
*/
    DEBUG("got font buffer: %d", fontbuffer);

    if(fontbuffer == NULL)
        return fz_throw("could not get buffer for font");

    *data = (unsigned char *) (*env)->GetDirectBufferAddress(env, fontbuffer);
    *len = (unsigned int) (*env)->GetDirectBufferCapacity(env, fontbuffer);

    if((*data == NULL) || (*len == -1))
        return fz_throw("could not get buffer for font (JNI trouble!)");

    DEBUG("got font buffer: %p, length=%d", *data, *len);
    return fz_okay;
}

fz_error
pdf_getcmapbuffer(char *cmapname, unsigned char **data, unsigned int *len) {
    JNIEnv *env;
    jboolean iscopy;
    jclass pdfrender;
    jclass fontproviderclass;
    jfieldID fontproviderfield;
    jobject fontprovider;
    jmethodID getcmapbuffermethod;
    jobject cmapbuffer;
    jstring cmapnamestring;

    DEBUG("pdf_getcmapbuffer('%s')", cmapname);

    if((*cached_jvm)->GetEnv(cached_jvm, (void **)&env, JNI_VERSION_1_2) != JNI_OK)
        return fz_throw("cannot find our JNI env!");

    pdfrender = (*env)->FindClass(env, "de/hilses/droidreader/PdfRender");
    if(pdfrender == NULL)
        return fz_throw("cannot find JNI interface class");

    fontproviderfield = (*env)->GetStaticFieldID(env, pdfrender,
            "fontProvider", "Lde/hilses/droidreader/FontProvider;");
    if(fontproviderfield == NULL)
        return fz_throw("cannot find fontProvider field");

    fontprovider = (*env)->GetStaticObjectField(env, pdfrender, fontproviderfield);
    if(fontprovider == NULL)
        return fz_throw("cannot access fontProvider field");

    fontproviderclass = (*env)->GetObjectClass(env, fontprovider);
    if(fontproviderclass == NULL)
        return fz_throw("cannot get class for fontProvider field content");

    getcmapbuffermethod = (*env)->GetMethodID(env, fontproviderclass,
            "getCMapBuffer",
            "(Ljava/lang/String;)Ljava/nio/ByteBuffer;");
    if(getcmapbuffermethod == NULL)
        return fz_throw("cannot find method getCMapBuffer() in fontProvider");

    cmapnamestring = (*env)->NewStringUTF(env, cmapname);

    cmapbuffer = (*env)->CallObjectMethod(
            env, fontprovider, getcmapbuffermethod,
            cmapnamestring);
/* TODO: release some references?!?
    (*env)->DeleteLocalRef(env, cmapnamestring);
*/
    DEBUG("got cmap buffer: %d", cmapbuffer);

    if(cmapbuffer == NULL)
        return fz_throw("could not get buffer for cmap");

    *data = (unsigned char *) (*env)->GetDirectBufferAddress(env, cmapbuffer);
    *len = (unsigned int) (*env)->GetDirectBufferCapacity(env, cmapbuffer);

    if((*data == NULL) || (*len == -1))
        return fz_throw("could not get buffer for cmap (JNI trouble!)");

    DEBUG("got cmap buffer: %p, length=%d (%d)", *data, *len, strlen(*data));
    return fz_okay;
}

/* JNI Interface: */

jint JNI_OnLoad(JavaVM *jvm, void *reserved)
{
    DEBUG("initializing PdfRender JNI library based on MuPDF");

    /* Fitz library setup */
    fz_accelerate();
    glyphcache = fz_newglyphcache();

    /* Store the JVM */
    cached_jvm = jvm;

    return JNI_VERSION_1_2;
}

void JNI_OnUnload(JavaVM *jvm, void *reserved)
{
    DEBUG("Cleaning up PdfRender JNI library");

    /* Fitz library cleanup */
    fz_freeglyphcache(glyphcache);
    glyphcache = (fz_glyphcache *)0;

    /* Wipe the stored JVM so that any accesses will break loudly */
    cached_jvm = (JavaVM *)0;
}

JNIEXPORT jint JNICALL
    Java_de_hilses_droidreader_PdfRender_checkFont
    (JNIEnv *env, jobject class, jstring fname)
{
    char *filename;
    jboolean iscopy;
    int result = 1;
    FILE *fd;

    filename = (char *)(*env)->GetStringUTFChars(env, fname, &iscopy);

    fd = fopen(filename, "r");
    if(fd) {
        fclose(fd);
        result = 0;
    } else {
        result = errno;
    }

    (*env)->ReleaseStringUTFChars(env, fname, filename);
    return (jint) result;
}

JNIEXPORT jlong JNICALL
    Java_de_hilses_droidreader_PdfDocument_nativeOpen
    (JNIEnv *env, jobject this,
            jint fitzmemory, jstring fname, jstring pwd)
{
    DEBUG("PdfDocument(%p).nativeOpen(%i, \"%p\", \"%p\")",
            this, fitzmemory, fname, pwd);

    fz_error error;
    fz_obj *obj;
    renderdocument_t *doc;
    jboolean iscopy;
    jclass cls;
    jfieldID fid;
    char *filename;
    char *password;
    fz_obj *info;
    int i;
#ifdef PDFRENDER_DEBUG
    clock_t end, start = clock();
#endif
    filename = (char *)(*env)->GetStringUTFChars(env, fname, &iscopy);
    password = (char *)(*env)->GetStringUTFChars(env, pwd, &iscopy);

    /* Don't need to check the return value from fz_malloc, because
     * fz_malloc abort()s if the allocation fails.
     */
    doc = fz_malloc(sizeof(renderdocument_t));
    memset (doc, 0, sizeof(renderdocument_t));

    for (i=0;i<NUM_CACHE_STRUCTS;i++) {
        doc->pages[i] = fz_malloc(sizeof(renderpage_t));
        memset (doc->pages[i],0,sizeof(renderpage_t));
    }

    /*
     * Open PDF and load xref table. Note that if pdf_needspassword() is going
     * to be called later, the password parameter to pdf_openxref() must be
     * NULL or else pdf_needspassword() will cause a segfault.
     */
    error = pdf_openxref(&doc->xref, filename, NULL);
    if (error) {
        fz_catch(error, "cannot open document.");
        goto cleanup;
    }

    /* If the document needs a password: if a password has been supplied then
     * try authenticating it. If a password hasn't been supplied, indicate
     * that a password is needed.
     */
    if (pdf_needspassword(doc->xref)) {
        if(strlen(password)) {
            int ok = pdf_authenticatepassword(doc->xref, password);
            if(!ok) {
                throw_exception(env, EXC_WRONG_PASSWORD,
                        "Wrong password given");
                goto cleanup;
            }
        } else {
            throw_exception(env, EXC_NEED_PASSWORD,
                    "PDF needs a password!");
            goto cleanup;
        }
    }

    /* Load the pdf page tree. */
    error = pdf_loadpagetree(doc->xref);
    if (error) {
        fz_catch(error, "cannot load page tree.");
        goto cleanup;
    }

    /*
     * Load document metadata (at some point this might be implemented
     * in the muPDF lib itself)
     */
    obj = fz_dictgets(doc->xref->trailer, "Root");
    obj = fz_resolveindirect(obj);
    if (!obj) {
        fz_throw("syntaxerror: missing Root object");
        throw_exception(env, EXC_CANNOT_DECRYPTXREF,
                "PDF syntax: missing \"Root\" object");
        goto cleanup;
    }

    cls = (*env)->GetObjectClass(env, this);

    obj = fz_dictgets(doc->xref->trailer, "Info");
    info = fz_resolveindirect(obj);
    if (info) {
        obj = fz_dictgets(info, "Title");
        if (obj) {
            fid = (*env)->GetFieldID(env, cls, "metaTitle",
                    "Ljava/lang/String;");
            if(fid) {
                jstring jstr = (*env)->NewStringUTF(env, pdf_toutf8(obj));
                (*env)->SetObjectField(env, this, fid, jstr);
            }
        }
    }

    /* TODO: read outline and pass to Java env or create accessor functions */

    fid = (*env)->GetFieldID(env, cls, "pagecount","I");
    if(fid) {
        (*env)->SetIntField(env, this, fid, pdf_getpagecount(doc->xref));
    } else {
        throw_exception(env, EXC, "cannot access instance fields!");
    }

cleanup:
#ifdef PDFRENDER_DEBUG
    end = clock();
    DEBUG("Document %s Load = %10.7fsec",filename,((double) (end - start)) / CLOCKS_PER_SEC);
#endif
    (*env)->ReleaseStringUTFChars(env, fname, filename);
    (*env)->ReleaseStringUTFChars(env, pwd, password);

    DEBUG("PdfDocument.nativeOpen(): return handle = %p", doc);
    return (jlong)(unsigned long) doc;
}

JNIEXPORT void JNICALL
    Java_de_hilses_droidreader_PdfDocument_nativeClose
    (JNIEnv *env, jobject this, jlong handle)
{
    renderdocument_t *doc = (renderdocument_t*)(unsigned long) handle;
    int i;

    DEBUG("PdfDocument(%p).nativeClose(%p)", this, doc);

    /* Drop all the stuff that might have been loaded */
    if(doc) {
        if (doc->xref)
            pdf_freexref(doc->xref);

        for (i=0;i<NUM_CACHE_STRUCTS;i++) {
            if (doc->pages[i]) {
                if (doc->pages[i]->list)
                    fz_freedisplaylist(doc->pages[i]->list);
                fz_free(doc->pages[i]);
            }
        }

        fz_free(doc);
    }
}

JNIEXPORT jint JNICALL
    Java_de_hilses_droidreader_PdfDocument_nativeIsMemoryHog
        (JNIEnv *env, jobject this, jlong handle)
{
    renderdocument_t *doc = (renderdocument_t*)(unsigned long) handle;
    DEBUG("PdfDocument(%p).nativeIsMemoryHog(%p) will return %d", this, doc, doc->isMemoryHog);

    return doc->isMemoryHog;
}

static void fixRect(fz_rect *rect)
{
    float temp;

    if (rect->x1 < rect->x0) {
        temp = rect->x0;
        rect->x0 = rect->x1;
        rect->x1 = temp;
    }
    if (rect->y1 < rect->y0) {
        temp = rect->y0;
        rect->y0 = rect->y1;
        rect->y1 = temp;
    }
}

JNIEXPORT jlong JNICALL
    Java_de_hilses_droidreader_PdfPage_nativeOpenPage
    (JNIEnv *env, jobject this, jlong dochandle, jfloatArray mediabox, jfloatArray contentbox, jint pageno, jint flags)
{
    renderdocument_t *doc = (renderdocument_t*)(unsigned long) dochandle;
    renderpage_t *page = (renderpage_t *)0;
    fz_device *dev = (fz_device *)0;
    pdf_page *pdfpage = (pdf_page *)0;
    jfloat *bbox;
    jclass cls;
    jfieldID fid;
    int i;
#ifdef PDFRENDER_DEBUG
    int itemcount = 0;
    clock_t end, start = clock();
#endif
    DEBUG("PdfPage(%p).nativeOpenPage(%p)", this, doc);

    doc->currentlyDisplayedPage = pageno;

    /* First, see if we've already got the page loaded in the cache */
    for (i=0;i<NUM_CACHE_STRUCTS;i++) {
        if (doc->pages[i]->pageNo == pageno) {
            page = doc->pages[i];
            break;
        }
    }

    if (!page) {
        fz_error error;
        fz_obj *obj = NULL;
        fz_displaynode *pDN;
        fz_rect content;
        int chosen = 0;
        int i;

        /* The page we want isn't currently loaded. Choose a page struct
         * to load it into. How this works:
         * 1) If the pageNo for a struct is 0, then this struct hasn't been
         *    used - so use it.
         * 2) Otherwise, find the page whose page number is furthest from the
         *    page number to be loaded
         * 3) If there are multiple pages whose numbers are the same distance
         *    from the page number to be loaded, use the page with the
         *    lowest number (this will be earlier in the document than the
         *    page to be loaded).
         * Note that this is written to allow more than 2 entries in the
         * struct cache.
         */

        /* Assume struct 0 to start with. If it's blank, use it. */
        if (doc->pages[0]->pageNo)
        {
            /* Iterate through the other structs */
            for (i=1;i<2;i++)
            {
                /* If there's a blank struct, use it */
                if (doc->pages[i]->pageNo)
                {
                    /* Neither this one nor the currently chosen one is blank.
                     * Find the difference between the page numbers in the
                     * structs, and the page to be loaded.
                     */
                    int dist0 = pageno - doc->pages[chosen]->pageNo;
                    int dist1 = pageno - doc->pages[i]->pageNo;

                    if (dist0<0) dist0 = -dist0;
                    if (dist1<0) dist1 = -dist1;

                    /* If this struct has a bigger difference than the currently
                     * chosen one, choose this one instead.
                     */
                    if (dist1 > dist0)
                        chosen = i;
                    else {
                        /* If the differences are the same, choose the one with
                         * the lower page number.
                         */
                        if (dist0 == dist1) {
                            if (doc->pages[i]->pageNo < doc->pages[chosen]->pageNo)
                                chosen = i;
                        }
                    }
                }
                else
                    chosen = i;
            }
        }

        page = doc->pages[chosen];

        /* Release the old content of the page, if any */
        if (page->list) {
            fz_freedisplaylist(page->list);
        }

        page->pageNo = pageno;

        if (flags & PDF_PAGE_MEMORY_HOG) {
            if (doc->xref->store) {
                pdf_freestore(doc->xref->store);
                doc->xref->store = (pdf_store *)0;
            }
        }

        if (!doc->xref->store)
            doc->xref->store = pdf_newstore();

        if (!(flags & PDF_PAGE_MEMORY_HOG))
            pdf_agestore(doc->xref->store, 3);

        fz_start_tracing(doc->xref->store);

        obj = pdf_getpageobject(doc->xref, pageno);
        error = pdf_loadpage(&pdfpage, doc->xref, obj);
        if (error) {
            throw_exception(env, EXC_PAGELOAD, "error loading page");
            goto cleanup;
        }

        page->list = fz_newdisplaylist();
        dev = fz_newlistdevice(page->list);
        error = pdf_runpage(doc->xref, pdfpage, dev, fz_identity);
        if (error) {
            throw_exception(env, EXC_PAGELOAD, "error running page");
            goto cleanup;
        }

        fixRect(&pdfpage->mediabox);
        page->pagebox.x0 = page->contentbox.x1 = pdfpage->mediabox.x0;
        page->pagebox.y0 = page->contentbox.y1 = pdfpage->mediabox.y0;
        page->pagebox.x1 = page->contentbox.x0 = pdfpage->mediabox.x1;
        page->pagebox.y1 = page->contentbox.y0 = pdfpage->mediabox.y1;

        pDN = page->list->first;

        while (pDN) {
            int disregard_content;

            if ((pDN->cmd == FZ_CMDFILLTEXT) ||
                (pDN->cmd == FZ_CMDSTROKETEXT) ||
                (pDN->cmd == FZ_CMDCLIPTEXT) ||
                (pDN->cmd == FZ_CMDCLIPSTROKETEXT) ||
                (pDN->cmd == FZ_CMDIGNORETEXT)) {
                disregard_content = 1;
                for (i=0;i<pDN->item.text->len;i++) {
                    if (pDN->item.text->els[i].ucs > 32) {
                        disregard_content = 0;
                        break;
                    }
                }
            } else
                disregard_content = 0;

            if ( (fabs(pDN->rect.x1 - pDN->rect.x0) < 0.1) ||
                 (fabs(pDN->rect.y1 - pDN->rect.y0) < 0.1) )
                disregard_content = 1;

            if (!disregard_content) {
                DEBUG("Command = %d, (%d,%d) - (%d,%d)",pDN->cmd,(int)pDN->rect.x0,(int)pDN->rect.y0,(int)pDN->rect.x1,(int)pDN->rect.y1);

                if ((pDN->cmd == FZ_CMDFILLTEXT) ||
                    (pDN->cmd == FZ_CMDSTROKETEXT) ||
                    (pDN->cmd == FZ_CMDCLIPTEXT) ||
                    (pDN->cmd == FZ_CMDCLIPSTROKETEXT) ||
                    (pDN->cmd == FZ_CMDIGNORETEXT)) {
                    for (i=0;i<pDN->item.text->len;i++)
                        DEBUG("Char %d, gid = %d, ucs = %d, (%d,%d)",i,pDN->item.text->els[i].gid,pDN->item.text->els[i].ucs,(int)pDN->item.text->els[i].x,(int)pDN->item.text->els[i].y);
                }

                fixRect(&pDN->rect);

                if ((pDN->rect.x0 < page->contentbox.x0) && (pDN->rect.x0 >= page->pagebox.x0))
                    page->contentbox.x0 = pDN->rect.x0;
                if ((pDN->rect.y0 < page->contentbox.y0) && (pDN->rect.y0 >= page->pagebox.y0))
                    page->contentbox.y0 = pDN->rect.y0;
                if ((pDN->rect.x1 > page->contentbox.x1) && (pDN->rect.x1 <= page->pagebox.x1))
                    page->contentbox.x1 = pDN->rect.x1;
                if ((pDN->rect.y1 > page->contentbox.y1) && (pDN->rect.y1 <= page->pagebox.y1))
                    page->contentbox.y1 = pDN->rect.y1;
            }

            pDN = pDN->next;
#ifdef PDFRENDER_DEBUG
            itemcount++;
#endif
        }

        /* If the document's memory allocation was above the threshold set in
         * fitz/base_memory.c, set the "memory hog" flag. Note, don't clear it if
         * it was already set.
         */
        doc->isMemoryHog |= fz_stop_tracing();

        page->rotate = pdfpage->rotate;
    }

    /* Now copy the page information back to the Java environment, whether it
     * came from the cache or from a newly-loaded page
     */
    bbox = (*env)->GetPrimitiveArrayCritical(env, mediabox, 0);
    if(bbox == NULL) {
        throw_exception(env, EXC, "out of memory");
        goto cleanup;
    }

    bbox[0] = page->pagebox.x0;
    bbox[1] = page->pagebox.y0;
    bbox[2] = page->pagebox.x1;
    bbox[3] = page->pagebox.y1;
    (*env)->ReleasePrimitiveArrayCritical(env, mediabox, bbox, 0);

    bbox = (*env)->GetPrimitiveArrayCritical(env, contentbox, 0);
    if(bbox == NULL) {
        throw_exception(env, EXC, "out of memory");
        goto cleanup;
    }
    bbox[0] = page->contentbox.x0;
    bbox[1] = page->contentbox.y0;
    bbox[2] = page->contentbox.x1;
    bbox[3] = page->contentbox.y1;
    (*env)->ReleasePrimitiveArrayCritical(env, contentbox, bbox, 0);

    cls = (*env)->GetObjectClass(env, this);
    fid = (*env)->GetFieldID(env, cls, "rotate","I");
    if(fid) {
        (*env)->SetIntField(env, this, fid, page->rotate);
    } else {
        throw_exception(env, EXC, "cannot access instance fields!");
    }

cleanup:
    if (dev)
        fz_freedevice(dev);

    if (pdfpage)
        pdf_freepage(pdfpage);

#ifdef PDFRENDER_DEBUG
    end = clock();
    DEBUG("Page %d Load = %10.7fsec, %d items",pageno,((double) (end - start)) / CLOCKS_PER_SEC, itemcount);
#endif
    return (jlong)(unsigned long) page;
}

JNIEXPORT void JNICALL
    Java_de_hilses_droidreader_PdfPage_nativeClosePage
    (JNIEnv *env, jobject this, jlong handle)
{
    /* Nothing to do! */
}

JNIEXPORT void JNICALL
    Java_de_hilses_droidreader_PdfView_nativeCreateView
    (JNIEnv *env, jobject this, jlong dochandle, jlong pagehandle,
        jintArray viewboxarray, jfloatArray matrixarray,
        jintArray bufferarray, jlong flags)
{
    renderpage_t *page = (renderpage_t*)(unsigned long) pagehandle;
    fz_matrix ctm;
    fz_bbox viewbox;
    fz_device *dev;
    jfloat *matrix;
    jint *viewboxarr;
    jint *buffer;
    int length, val;
    fz_pixmap pixmap;
    int i,j;

    DEBUG("PdfView(%p).nativeCreateView(%p)", this, page);

    /* initialize parameter arrays for MuPDF */
    matrix = (*env)->GetPrimitiveArrayCritical(env, matrixarray, 0);
    ctm.a = matrix[0];
    ctm.b = matrix[1];
    ctm.c = matrix[2];
    ctm.d = matrix[3];
    ctm.e = matrix[4];
    ctm.f = matrix[5];
    (*env)->ReleasePrimitiveArrayCritical(env, matrixarray, matrix, 0);
    DEBUG("Matrix: %f %f %f %f %f %f",
            ctm.a, ctm.b, ctm.c, ctm.d, ctm.e, ctm.f);

    viewboxarr = (*env)->GetPrimitiveArrayCritical(env, viewboxarray, 0);
    viewbox.x0 = viewboxarr[0];
    viewbox.y0 = viewboxarr[1];
    viewbox.x1 = viewboxarr[2];
    viewbox.y1 = viewboxarr[3];
    (*env)->ReleasePrimitiveArrayCritical(env, viewboxarray, viewboxarr, 0);
    DEBUG("Viewbox: (%d,%d) (%d,%d)",
            viewbox.x0, viewbox.y0, viewbox.x1, viewbox.y1);

    DEBUG("Content box: (%d,%d) (%d,%d)",
            (int)page->contentbox.x0, (int)page->contentbox.y0,
            (int)page->contentbox.x1, (int)page->contentbox.y1);
    DEBUG("Page box: (%d,%d) (%d,%d)",
            (int)page->pagebox.x0, (int)page->pagebox.y0,
            (int)page->pagebox.x1, (int)page->pagebox.y1);
    DEBUG("Zoom: %5.3f",zoom);

    /* do the rendering */
    buffer = (*env)->GetPrimitiveArrayCritical(env, bufferarray, 0);

    pixmap.x = viewbox.x0;
    pixmap.y = viewbox.y0;
    pixmap.w = viewbox.x1 - viewbox.x0;
    pixmap.h = viewbox.y1 - viewbox.y0;
    pixmap.refs = 1;
    if (flags & PDF_RENDER_DISPLAY_INVERT) {
        pixmap.n = 2;
        pixmap.colorspace = fz_devicegray;
    } else {
        pixmap.n = 4;
        pixmap.colorspace = fz_devicebgr;
    }
    pixmap.mask = 0;
    pixmap.samples = (void*)buffer;

    // white:
    j = pixmap.w * pixmap.h;
    for (i=0;i<j;i++)
        buffer[i] = 0xffffffff;

    dev = fz_newdrawdevice(glyphcache, &pixmap);
    fz_executedisplaylist(page->list, dev, ctm);
    fz_freedevice(dev);

    if (flags & PDF_RENDER_DISPLAY_INVERT) {
        unsigned char *pGS = (unsigned char *)buffer;
        for (i=j-1;i>=0;i--)
        {
            buffer[i] = ((jint)pGS[i*2] +
                        ((jint)pGS[i*2] << 8) +
                        ((jint)pGS[i*2] << 16) +
                        ((jint)pGS[i*2 + 1] << 24))
                        ^ 0xffffffff;
        }
    }

    (*env)->ReleasePrimitiveArrayCritical(env, bufferarray, buffer, 0);

    DEBUG("PdfView.nativeCreateView() done");
}
