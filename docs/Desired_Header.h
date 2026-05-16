/* ═══════════════════════════════════════════════════════════
 * class ListIterator<Float?> : Iterator<Float?>
 * package: ktc.std
 * file: Collections.kt
 * mangled: ktc_std_ListIterator$1_Float$Opt
 * ═══════════════════════════════════════════════════════════ */
#define CLS ktc_std_ListIterator$1_ktc_Float$Opt
#define CLS_OPT ktc_std_ListIterator$Opt$1_ktc_Float$Opt
#define ktc_std_ListIterator$1_Float$Opt_TYPE_ID 49

typedef struct {
    ktc_core_AnySupertype __base;
    /*VAL*/ ktc_Float$Opt * buf; /** notnull */
    ktc_Int buf$len;
    /*VAL*/ ktc_Int size;
    /*VAR*/ ktc_Int idx;
} CLS;

typedef struct {
    ktc_OptionalTag tag;
    CLS value;
} CLS_OPT;

// ════ constructors ════
KTC_METHOD(CLS, primaryConstructor)(ktc_Float$Opt* buf, ktc_Int buf$len, ktc_Int size);

// ════ implements Iterator<Float?> (Iterator.kt) ════
KTC_METHOD(ktc_Bool, hasNext)(CLS* $self);
KTC_METHOD(ktc_Float$Opt, next)(CLS* $self);

// ════ implements Any ════
KTC_METHOD(ktc_Int, hashCode)(CLS $self);
KTC_METHOD(ktc_Bool, equals)(CLS a, CLS b);
KTC_METHOD(void, toString)(CLS* $self, ktc_StrBuf* sb); // max output: 34 chars
#define ktc_std_ListIterator$1_Float$Opt_dispose(self) ((void)(self))

// ════ Any cast ════
KTC_METHOD(ktc_Any, as_Any)(CLS* $self);
extern const ktc_core_AnyVt KTC_RELATED(AnyVt);

#undef CLS
#undef CLS_OPT
/* ═══════════════════════════════════════════════════════════
 * END class ListIterator<Float?> : Iterator<Float?>
 * ═══════════════════════════════════════════════════════════ */