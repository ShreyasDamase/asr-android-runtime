#ifndef FIX_NDK_H
#define FIX_NDK_H

#include <time.h>
#include <sys/types.h>

#ifdef __cplusplus
extern "C" {
#endif

// Ensure struct tm is fully defined
#ifndef _STRUCT_TM
#define _STRUCT_TM
struct tm {
   int tm_sec;
   int tm_min;
   int tm_hour;
   int tm_mday;
   int tm_mon;
   int tm_year;
   int tm_wday;
   int tm_yday;
   int tm_isdst;
   long int tm_gmtoff;
   const char *tm_zone;
};
#endif

#ifdef __cplusplus
}
#endif

#endif
