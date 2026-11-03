import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Lo strato COM chiamato da Java con FFM, senza DLL nostre: le funzioni di ole32 e oleaut32 per
 * nome, i metodi delle interfacce COM per indirizzo, letto dalla vtable dell'oggetto.
 *
 * Una chiamata COM e' una funzione nativa il cui primo argomento e' il puntatore all'oggetto: il
 * suo indirizzo sta nello slot n della vtable, e la vtable sta nella prima parola dell'oggetto. Gli
 * slot da 0 a 2 sono di IUnknown (QueryInterface, AddRef, Release). Gli altri li porta Uia.java,
 * letti dalla type library di UIAutomationCore.dll (probe di T18, dump-uia-typelib.ps1).
 *
 * Gli handle sono uno per firma, non uno per metodo: un downcall handle creato senza indirizzo
 * prende la funzione da chiamare come primo argomento.
 */
final class Com {

    static final int S_OK = 0;
    static final int S_FALSE = 1;
    static final int RPC_E_CHANGED_MODE = 0x80010106;
    static final int COINIT_MULTITHREADED = 0x0;
    static final int CLSCTX_INPROC_SERVER = 0x1;

    static final short VT_EMPTY = 0;
    static final short VT_I4 = 3;
    static final short VT_BSTR = 8;
    static final short VT_ARRAY = 0x2000;

    /**
     * VARIANT su x64: 8 byte di tipo e riservati, poi 16 byte di unione. Qui servono solo i primi 8
     * byte dell'unione: un intero (VT_I4), un BSTR (VT_BSTR) o un SAFEARRAY (VT_ARRAY).
     */
    static final StructLayout VARIANT = MemoryLayout.structLayout(
            JAVA_SHORT.withName("vt"),
            JAVA_SHORT.withName("reserved1"),
            JAVA_SHORT.withName("reserved2"),
            JAVA_SHORT.withName("reserved3"),
            ValueLayout.JAVA_LONG.withName("value"),
            ValueLayout.JAVA_LONG.withName("value2"));

    private static final long VARIANT_VALUE_OFFSET = 8;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup OLE32 = SymbolLookup.libraryLookup("ole32", Arena.global());
    private static final SymbolLookup OLEAUT32 = SymbolLookup.libraryLookup("oleaut32", Arena.global());

    private static final MethodHandle CO_INITIALIZE_EX = function(OLE32, "CoInitializeEx",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle CO_CREATE_INSTANCE = function(OLE32, "CoCreateInstance",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle SYS_ALLOC_STRING_LEN = function(OLEAUT32, "SysAllocStringLen",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle SYS_FREE_STRING = function(OLEAUT32, "SysFreeString",
            FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle SYS_STRING_LEN = function(OLEAUT32, "SysStringLen",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle VARIANT_CLEAR = function(OLEAUT32, "VariantClear",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle SAFE_ARRAY_GET_LBOUND = function(OLEAUT32, "SafeArrayGetLBound",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
    private static final MethodHandle SAFE_ARRAY_GET_UBOUND = function(OLEAUT32, "SafeArrayGetUBound",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
    private static final MethodHandle SAFE_ARRAY_ACCESS_DATA = function(OLEAUT32, "SafeArrayAccessData",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle SAFE_ARRAY_UNACCESS_DATA = function(OLEAUT32, "SafeArrayUnaccessData",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle SAFE_ARRAY_DESTROY = function(OLEAUT32, "SafeArrayDestroy",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    private static final MethodHandle METHOD_THIS = method(JAVA_INT, ADDRESS);
    private static final MethodHandle METHOD_THIS_PTR = method(JAVA_INT, ADDRESS, ADDRESS);
    private static final MethodHandle METHOD_THIS_INT = method(JAVA_INT, ADDRESS, JAVA_INT);
    private static final MethodHandle METHOD_THIS_INT_PTR = method(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS);
    private static final MethodHandle METHOD_THIS_PTR_PTR = method(JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
    private static final MethodHandle METHOD_THIS_PTR_PTR_PTR = method(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final MethodHandle METHOD_THIS_INT_PTR_PTR = method(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS);
    private static final MethodHandle METHOD_THIS_INT_PTR_PTR_PTR =
            method(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
    private static final MethodHandle METHOD_THIS_INT_VARIANT_PTR = method(JAVA_INT, ADDRESS, JAVA_INT, VARIANT, ADDRESS);
    private static final MethodHandle METHOD_THIS_INT_VARIANT_INT_PTR =
            method(JAVA_INT, ADDRESS, JAVA_INT, VARIANT, JAVA_INT, ADDRESS);

    private static final int IUNKNOWN_RELEASE_SLOT = 2;

    private Com() {
    }

    //<editor-fold desc="Apartment e creazione degli oggetti">

    /**
     * Entra nell'apartment multithread sul thread corrente. Il thread e' gia' in un apartment
     * single-thread (RPC_E_CHANGED_MODE) solo se qualcuno l'ha inizializzato prima: UI Automation
     * lavora anche li', quindi si va avanti.
     */
    static void initializeMultithreadedApartment() {
        int hresult;
        try {
            hresult = (int) CO_INITIALIZE_EX.invokeExact(MemorySegment.NULL, COINIT_MULTITHREADED);
        } catch (Throwable failure) {
            throw new IllegalStateException("CoInitializeEx", failure);
        }
        if (hresult != S_OK && hresult != S_FALSE && hresult != RPC_E_CHANGED_MODE)
            throw new ComException("CoInitializeEx", hresult);
    }

    static MemorySegment createInProcessInstance(String classId, String interfaceId) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = newOutPointer(arena);
            int hresult;
            try {
                hresult = (int) CO_CREATE_INSTANCE.invokeExact(guid(arena, classId), MemorySegment.NULL,
                        CLSCTX_INPROC_SERVER, guid(arena, interfaceId), out);
            } catch (Throwable failure) {
                throw new IllegalStateException("CoCreateInstance", failure);
            }
            check(hresult, "CoCreateInstance " + classId);
            return out.get(ADDRESS, 0);
        }
    }

    /**
     * Un GUID nel formato di memoria di Windows: Data1 a 32 bit, Data2 e Data3 a 16 bit, little
     * endian, poi gli 8 byte di Data4 nell'ordine in cui si scrivono.
     */
    static MemorySegment guid(Arena arena, String text) {
        String hex = text.replace("-", "");
        if (hex.length() != 32)
            throw new IllegalArgumentException("GUID malformato: " + text);
        MemorySegment guid = arena.allocate(16, 4);
        guid.set(JAVA_INT, 0, (int) Long.parseLong(hex.substring(0, 8), 16));
        guid.set(JAVA_SHORT, 4, (short) Integer.parseInt(hex.substring(8, 12), 16));
        guid.set(JAVA_SHORT, 6, (short) Integer.parseInt(hex.substring(12, 16), 16));
        for (int index = 0; index < 8; index++)
            guid.set(ValueLayout.JAVA_BYTE, 8 + index,
                    (byte) Integer.parseInt(hex.substring(16 + index * 2, 18 + index * 2), 16));
        return guid;
    }

    //</editor-fold>

    //<editor-fold desc="Chiamate ai metodi COM, una per firma">

    static int release(MemorySegment self) {
        try {
            return (int) METHOD_THIS.invokeExact(methodAddress(self, IUNKNOWN_RELEASE_SLOT), self);
        } catch (Throwable failure) {
            throw new IllegalStateException("IUnknown::Release", failure);
        }
    }

    static void callWithPointer(MemorySegment self, int slot, MemorySegment argument, String name) {
        int hresult;
        try {
            hresult = (int) METHOD_THIS_PTR.invokeExact(methodAddress(self, slot), self, argument);
        } catch (Throwable failure) {
            throw new IllegalStateException(name, failure);
        }
        check(hresult, name);
    }

    static void callWithInt(MemorySegment self, int slot, int argument, String name) {
        int hresult;
        try {
            hresult = (int) METHOD_THIS_INT.invokeExact(methodAddress(self, slot), self, argument);
        } catch (Throwable failure) {
            throw new IllegalStateException(name, failure);
        }
        check(hresult, name);
    }

    static void callWithIntAndPointer(MemorySegment self, int slot, int first, MemorySegment second, String name) {
        int hresult;
        try {
            hresult = (int) METHOD_THIS_INT_PTR.invokeExact(methodAddress(self, slot), self, first, second);
        } catch (Throwable failure) {
            throw new IllegalStateException(name, failure);
        }
        check(hresult, name);
    }

    static void callWithTwoPointers(MemorySegment self, int slot, MemorySegment first, MemorySegment second,
                                    String name) {
        int hresult;
        try {
            hresult = (int) METHOD_THIS_PTR_PTR.invokeExact(methodAddress(self, slot), self, first, second);
        } catch (Throwable failure) {
            throw new IllegalStateException(name, failure);
        }
        check(hresult, name);
    }

    static void callWithThreePointers(MemorySegment self, int slot, MemorySegment first, MemorySegment second,
                                      MemorySegment third, String name) {
        int hresult;
        try {
            hresult = (int) METHOD_THIS_PTR_PTR_PTR.invokeExact(methodAddress(self, slot), self, first, second, third);
        } catch (Throwable failure) {
            throw new IllegalStateException(name, failure);
        }
        check(hresult, name);
    }

    static void callWithIntAndTwoPointers(MemorySegment self, int slot, int first, MemorySegment second,
                                          MemorySegment third, String name) {
        int hresult;
        try {
            hresult = (int) METHOD_THIS_INT_PTR_PTR.invokeExact(methodAddress(self, slot), self, first, second, third);
        } catch (Throwable failure) {
            throw new IllegalStateException(name, failure);
        }
        check(hresult, name);
    }

    static void callWithIntAndThreePointers(MemorySegment self, int slot, int first, MemorySegment second,
                                            MemorySegment third, MemorySegment fourth, String name) {
        int hresult;
        try {
            hresult = (int) METHOD_THIS_INT_PTR_PTR_PTR.invokeExact(methodAddress(self, slot), self, first, second,
                    third, fourth);
        } catch (Throwable failure) {
            throw new IllegalStateException(name, failure);
        }
        check(hresult, name);
    }

    static void callWithIntVariantAndPointer(MemorySegment self, int slot, int first, MemorySegment variant,
                                             MemorySegment out, String name) {
        int hresult;
        try {
            hresult = (int) METHOD_THIS_INT_VARIANT_PTR.invokeExact(methodAddress(self, slot), self, first, variant,
                    out);
        } catch (Throwable failure) {
            throw new IllegalStateException(name, failure);
        }
        check(hresult, name);
    }

    static void callWithIntVariantIntAndPointer(MemorySegment self, int slot, int first, MemorySegment variant,
                                                int flags, MemorySegment out, String name) {
        int hresult;
        try {
            hresult = (int) METHOD_THIS_INT_VARIANT_INT_PTR.invokeExact(methodAddress(self, slot), self, first,
                    variant, flags, out);
        } catch (Throwable failure) {
            throw new IllegalStateException(name, failure);
        }
        check(hresult, name);
    }

    //</editor-fold>

    //<editor-fold desc="BSTR, VARIANT e SAFEARRAY" defaultstate="collapsed">

    static MemorySegment newOutPointer(Arena arena) {
        MemorySegment out = arena.allocate(ADDRESS);
        out.set(ADDRESS, 0, MemorySegment.NULL);
        return out;
    }

    static boolean isNull(MemorySegment pointer) {
        return pointer == null || pointer.address() == 0;
    }

    static MemorySegment allocateBstr(String value) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment characters = arena.allocateFrom(JAVA_CHAR, value.toCharArray());
            MemorySegment bstr;
            try {
                bstr = (MemorySegment) SYS_ALLOC_STRING_LEN.invokeExact(characters, value.length());
            } catch (Throwable failure) {
                throw new IllegalStateException("SysAllocStringLen", failure);
            }
            if (isNull(bstr))
                throw new IllegalStateException("SysAllocStringLen: memoria esaurita");
            return bstr;
        }
    }

    static void freeBstr(MemorySegment bstr) {
        if (isNull(bstr))
            return;
        try {
            SYS_FREE_STRING.invokeExact(bstr);
        } catch (Throwable failure) {
            throw new IllegalStateException("SysFreeString", failure);
        }
    }

    /**
     * Legge il BSTR scritto da un metodo COM nel puntatore di uscita, e lo libera: il chiamante ne e'
     * il proprietario. Un BSTR nullo vale stringa vuota, come per COM.
     */
    static String takeBstr(MemorySegment out) {
        MemorySegment bstr = out.get(ADDRESS, 0);
        if (isNull(bstr))
            return "";
        try {
            int length;
            try {
                length = (int) SYS_STRING_LEN.invokeExact(bstr);
            } catch (Throwable failure) {
                throw new IllegalStateException("SysStringLen", failure);
            }
            return new String(bstr.reinterpret(length * 2L).toArray(JAVA_CHAR));
        } finally {
            freeBstr(bstr);
        }
    }

    static MemorySegment newVariant(Arena arena) {
        MemorySegment variant = arena.allocate(VARIANT);
        variant.fill((byte) 0);
        return variant;
    }

    static void setVariantInt(MemorySegment variant, int value) {
        variant.set(JAVA_SHORT, 0, VT_I4);
        variant.set(JAVA_INT, VARIANT_VALUE_OFFSET, value);
    }

    static void setVariantBstr(MemorySegment variant, MemorySegment bstr) {
        variant.set(JAVA_SHORT, 0, VT_BSTR);
        variant.set(ADDRESS, VARIANT_VALUE_OFFSET, bstr);
    }

    static short variantType(MemorySegment variant) {
        return variant.get(JAVA_SHORT, 0);
    }

    static MemorySegment variantPointer(MemorySegment variant) {
        return variant.get(ADDRESS, VARIANT_VALUE_OFFSET);
    }

    static void clearVariant(MemorySegment variant) {
        int hresult;
        try {
            hresult = (int) VARIANT_CLEAR.invokeExact(variant);
        } catch (Throwable failure) {
            throw new IllegalStateException("VariantClear", failure);
        }
        check(hresult, "VariantClear");
    }

    static int[] readIntSafeArray(MemorySegment safeArray) {
        if (isNull(safeArray))
            return new int[0];
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment lowerBound = arena.allocate(JAVA_INT);
            MemorySegment upperBound = arena.allocate(JAVA_INT);
            MemorySegment data = newOutPointer(arena);
            try {
                check((int) SAFE_ARRAY_GET_LBOUND.invokeExact(safeArray, 1, lowerBound), "SafeArrayGetLBound");
                check((int) SAFE_ARRAY_GET_UBOUND.invokeExact(safeArray, 1, upperBound), "SafeArrayGetUBound");
                int count = upperBound.get(JAVA_INT, 0) - lowerBound.get(JAVA_INT, 0) + 1;
                if (count <= 0)
                    return new int[0];
                check((int) SAFE_ARRAY_ACCESS_DATA.invokeExact(safeArray, data), "SafeArrayAccessData");
                try {
                    return data.get(ADDRESS, 0).reinterpret(count * JAVA_INT.byteSize()).toArray(JAVA_INT);
                } finally {
                    check((int) SAFE_ARRAY_UNACCESS_DATA.invokeExact(safeArray), "SafeArrayUnaccessData");
                }
            } catch (ComException failure) {
                throw failure;
            } catch (Throwable failure) {
                throw new IllegalStateException("lettura SAFEARRAY", failure);
            }
        }
    }

    static void destroySafeArray(MemorySegment safeArray) {
        if (isNull(safeArray))
            return;
        int hresult;
        try {
            hresult = (int) SAFE_ARRAY_DESTROY.invokeExact(safeArray);
        } catch (Throwable failure) {
            throw new IllegalStateException("SafeArrayDestroy", failure);
        }
        check(hresult, "SafeArrayDestroy");
    }

    //</editor-fold>

    //<editor-fold desc="Risoluzione di funzioni e metodi" defaultstate="collapsed">

    static void check(int hresult, String name) {
        if (hresult < 0)
            throw new ComException(name, hresult);
    }

    private static MemorySegment methodAddress(MemorySegment self, int slot) {
        if (isNull(self))
            throw new IllegalStateException("chiamata COM su un puntatore nullo, slot " + slot);
        MemorySegment vtable = self.reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0);
        return vtable.reinterpret((slot + 1L) * ADDRESS.byteSize()).getAtIndex(ADDRESS, slot);
    }

    private static MethodHandle function(SymbolLookup library, String name, FunctionDescriptor descriptor) {
        MemorySegment address = library.find(name)
                .orElseThrow(() -> new IllegalStateException("funzione nativa assente: " + name));
        return LINKER.downcallHandle(address, descriptor);
    }

    private static MethodHandle method(ValueLayout result, MemoryLayout... arguments) {
        return LINKER.downcallHandle(FunctionDescriptor.of(result, arguments));
    }

    //</editor-fold>

    /**
     * Un HRESULT di errore restituito da una chiamata COM. Il codice resta leggibile in esadecimale,
     * come lo riportano la documentazione e i log di Windows.
     */
    static final class ComException extends RuntimeException {

        final int hresult;

        ComException(String name, int hresult) {
            super(name + " fallita, HRESULT 0x" + String.format("%08X", hresult));
            this.hresult = hresult;
        }
    }
}
