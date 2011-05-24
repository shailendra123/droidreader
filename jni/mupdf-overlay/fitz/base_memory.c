#include "fitz.h"
#include "mupdf.h"

#define BIG_MEM_THRESHOLD   (30 * 1024 * 1024)

static int tracing;
static int memTotal;
static int mayFlushCache;
static pdf_store *pCache;

/* When loading a page, trace the memory allocations. If they're
 * above BIG_MEM_THRESHOLD, flush the mupdf cache and let the
 * caller know about it. This enables DroidReader to flag the
 * document as a memory hog and be more careful with memory
 * (this also makes it slower, unfortunately).
 */
void fz_start_tracing (pdf_store *pStore)
{
    pCache = pStore;
    tracing = 1;
    mayFlushCache = 1;
    memTotal = 0;
}

int fz_stop_tracing (void)
{
    if (!tracing) {
        return -1;
    }

    tracing = 0;
    return 1 - mayFlushCache;
}

void *fz_malloc(int n)
{
	void *p;

    if (tracing)
    {
        memTotal += n;
        if ((memTotal > BIG_MEM_THRESHOLD) && (mayFlushCache))
        {
            pdf_agestore(pCache, 0);
            mayFlushCache = 0;
        }
    }

    p = malloc(n);
	if (!p)
	{
		fprintf(stderr, "fatal error: out of memory\n");
		abort();
	}
	return p;
}

void *
fz_calloc(int count, int size)
{
	void *p;

	if (count == 0 || size == 0)
		return 0;

	if (count < 0 || size < 0 || count > INT_MAX / size)
	{
		fprintf(stderr, "fatal error: out of memory (integer overflow)\n");
		abort();
	}

	p = fz_malloc(count * size);
    /* No need to check p, if fz_malloc fails then it won't return.
	if (!p)
	{
		fprintf(stderr, "fatal error: out of memory\n");
		abort();
	} */
	return p;
}

void *
fz_realloc(void *p, int count, int size)
{
	void *np;

	if (count == 0 || size == 0)
	{
		fz_free(p);
		return 0;
	}

	if (count < 0 || size < 0 || count > INT_MAX / size)
	{
		fprintf(stderr, "fatal error: out of memory (integer overflow)\n");
		abort();
	}

	np = realloc(p, count * size);
	if (np == nil)
	{
		fprintf(stderr, "fatal error: out of memory\n");
		abort();
	}
	return np;
}

void
fz_free(void *p)
{
	free(p);
}

char *
fz_strdup(char *s)
{
	int len = strlen(s) + 1;
	char *ns = fz_malloc(len);
	memcpy(ns, s, len);
	return ns;
}
