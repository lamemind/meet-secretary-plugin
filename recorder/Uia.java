import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Le interfacce di UI Automation che il lettore usa, sopra Com.java: solo i metodi chiamati, con lo
 * slot di vtable letto dalla type library di UIAutomationCore.dll. Ogni oggetto restituito e' un
 * riferimento COM da chiudere: close() fa Release.
 *
 * I valori numerici (property id, control type id, tree scope) sono quelli di UIAutomationClient.h
 * del Windows SDK.
 */
final class Uia {

    static final String CLSID_CUI_AUTOMATION = "ff48dba4-60ef-4201-aa87-54103eef594e";
    static final String IID_IUI_AUTOMATION = "30cbe57d-d9d0-452a-ab13-7ac5ac4825ee";

    static final int TREE_SCOPE_ELEMENT = 0x1;
    static final int TREE_SCOPE_CHILDREN = 0x2;
    static final int TREE_SCOPE_DESCENDANTS = 0x4;

    static final int PROPERTY_CONDITION_MATCH_SUBSTRING = 0x2;

    static final int RUNTIME_ID_PROPERTY = 30000;
    static final int PROCESS_ID_PROPERTY = 30002;
    static final int CONTROL_TYPE_PROPERTY = 30003;
    static final int NAME_PROPERTY = 30005;
    static final int AUTOMATION_ID_PROPERTY = 30011;
    static final int CLASS_NAME_PROPERTY = 30012;

    static final int TEXT_CONTROL_TYPE = 50020;
    static final int GROUP_CONTROL_TYPE = 50026;

    private Uia() {
    }

    /**
     * Un riferimento COM posseduto: close() fa Release una volta sola.
     */
    static class ComReference implements AutoCloseable {

        final MemorySegment pointer;
        private boolean released;

        ComReference(MemorySegment pointer) {
            this.pointer = pointer;
        }

        @Override
        public void close() {
            if (released)
                return;
            released = true;
            Com.release(pointer);
        }
    }

    //<editor-fold desc="IUIAutomation">

    static final class Automation extends ComReference {

        private static final int GET_ROOT_ELEMENT = 5;
        private static final int GET_RAW_VIEW_WALKER = 16;
        private static final int GET_RAW_VIEW_CONDITION = 17;
        private static final int CREATE_CACHE_REQUEST = 20;
        private static final int CREATE_TRUE_CONDITION = 21;
        private static final int CREATE_PROPERTY_CONDITION = 23;
        private static final int CREATE_PROPERTY_CONDITION_EX = 24;
        private static final int CREATE_AND_CONDITION = 25;
        private static final int CREATE_OR_CONDITION = 28;

        private Automation(MemorySegment pointer) {
            super(pointer);
        }

        static Automation create() {
            return new Automation(Com.createInProcessInstance(CLSID_CUI_AUTOMATION, IID_IUI_AUTOMATION));
        }

        Element rootElement() {
            return new Element(callForPointer(GET_ROOT_ELEMENT, "IUIAutomation::GetRootElement"));
        }

        TreeWalker rawViewWalker() {
            return new TreeWalker(callForPointer(GET_RAW_VIEW_WALKER, "IUIAutomation::get_RawViewWalker"));
        }

        Condition rawViewCondition() {
            return new Condition(callForPointer(GET_RAW_VIEW_CONDITION, "IUIAutomation::get_RawViewCondition"));
        }

        Condition trueCondition() {
            return new Condition(callForPointer(CREATE_TRUE_CONDITION, "IUIAutomation::CreateTrueCondition"));
        }

        CacheRequest cacheRequest() {
            return new CacheRequest(callForPointer(CREATE_CACHE_REQUEST, "IUIAutomation::CreateCacheRequest"));
        }

        Condition propertyEquals(int propertyId, int value) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment variant = Com.newVariant(arena);
                Com.setVariantInt(variant, value);
                MemorySegment out = Com.newOutPointer(arena);
                Com.callWithIntVariantAndPointer(pointer, CREATE_PROPERTY_CONDITION, propertyId, variant, out,
                        "IUIAutomation::CreatePropertyCondition");
                return new Condition(out.get(ADDRESS, 0));
            }
        }

        Condition propertyEquals(int propertyId, String value) {
            return stringPropertyCondition(propertyId, value, 0);
        }

        Condition propertyContains(int propertyId, String value) {
            return stringPropertyCondition(propertyId, value, PROPERTY_CONDITION_MATCH_SUBSTRING);
        }

        Condition and(Condition first, Condition second) {
            return new Condition(callForPointerWith(CREATE_AND_CONDITION, first, second,
                    "IUIAutomation::CreateAndCondition"));
        }

        Condition or(Condition first, Condition second) {
            return new Condition(callForPointerWith(CREATE_OR_CONDITION, first, second,
                    "IUIAutomation::CreateOrCondition"));
        }

        /**
         * Il BSTR del valore si libera subito: la condizione ne tiene una copia sua.
         */
        private Condition stringPropertyCondition(int propertyId, String value, int flags) {
            MemorySegment bstr = Com.allocateBstr(value);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment variant = Com.newVariant(arena);
                Com.setVariantBstr(variant, bstr);
                MemorySegment out = Com.newOutPointer(arena);
                if (flags == 0)
                    Com.callWithIntVariantAndPointer(pointer, CREATE_PROPERTY_CONDITION, propertyId, variant, out,
                            "IUIAutomation::CreatePropertyCondition");
                else
                    Com.callWithIntVariantIntAndPointer(pointer, CREATE_PROPERTY_CONDITION_EX, propertyId, variant,
                            flags, out, "IUIAutomation::CreatePropertyConditionEx");
                return new Condition(out.get(ADDRESS, 0));
            } finally {
                Com.freeBstr(bstr);
            }
        }

        private MemorySegment callForPointer(int slot, String name) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = Com.newOutPointer(arena);
                Com.callWithPointer(pointer, slot, out, name);
                return out.get(ADDRESS, 0);
            }
        }

        private MemorySegment callForPointerWith(int slot, ComReference first, ComReference second, String name) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = Com.newOutPointer(arena);
                Com.callWithThreePointers(pointer, slot, first.pointer, second.pointer, out, name);
                return out.get(ADDRESS, 0);
            }
        }
    }

    //</editor-fold>

    //<editor-fold desc="IUIAutomationElement">

    static final class Element extends ComReference {

        private static final int GET_RUNTIME_ID = 4;
        private static final int FIND_FIRST = 5;
        private static final int FIND_ALL_BUILD_CACHE = 8;
        private static final int GET_CACHED_PROPERTY_VALUE = 12;
        private static final int GET_CACHED_CHILDREN = 19;
        private static final int GET_CURRENT_NAME = 23;
        private static final int GET_CURRENT_IS_OFFSCREEN = 38;
        private static final int GET_CACHED_PROCESS_ID = 52;
        private static final int GET_CACHED_CONTROL_TYPE = 53;
        private static final int GET_CACHED_NAME = 55;
        private static final int GET_CACHED_AUTOMATION_ID = 61;
        private static final int GET_CACHED_CLASS_NAME = 62;

        Element(MemorySegment pointer) {
            super(pointer);
        }

        /**
         * Il primo discendente (o figlio, secondo scope) che soddisfa la condizione, o null.
         */
        Element findFirst(int scope, Condition condition) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = Com.newOutPointer(arena);
                Com.callWithIntAndTwoPointers(pointer, FIND_FIRST, scope, condition.pointer, out,
                        "IUIAutomationElement::FindFirst");
                MemorySegment found = out.get(ADDRESS, 0);
                return Com.isNull(found) ? null : new Element(found);
            }
        }

        /**
         * Tutti gli elementi che soddisfano la condizione, con le proprieta' della cache request gia'
         * lette: un solo viaggio verso il processo che espone l'albero. Mai null: zero elementi
         * diventano un array vuoto.
         */
        ElementArray findAllBuildCache(int scope, Condition condition, CacheRequest cacheRequest) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = Com.newOutPointer(arena);
                Com.callWithIntAndThreePointers(pointer, FIND_ALL_BUILD_CACHE, scope, condition.pointer,
                        cacheRequest.pointer, out, "IUIAutomationElement::FindAllBuildCache");
                return new ElementArray(out.get(ADDRESS, 0));
            }
        }

        /**
         * I figli letti dalla cache, se la cache request li chiedeva (TreeScope Children). Mai null.
         */
        ElementArray cachedChildren() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = Com.newOutPointer(arena);
                Com.callWithPointer(pointer, GET_CACHED_CHILDREN, out, "IUIAutomationElement::GetCachedChildren");
                return new ElementArray(out.get(ADDRESS, 0));
            }
        }

        /**
         * Il RuntimeId dalla cache, quando la cache request lo portava; altrimenti quello corrente.
         */
        String cachedRuntimeId() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment variant = Com.newVariant(arena);
                Com.callWithIntAndPointer(pointer, GET_CACHED_PROPERTY_VALUE, RUNTIME_ID_PROPERTY, variant,
                        "IUIAutomationElement::GetCachedPropertyValue(RuntimeId)");
                try {
                    if (Com.variantType(variant) == (short) (Com.VT_ARRAY | Com.VT_I4))
                        return joinRuntimeId(Com.readIntSafeArray(Com.variantPointer(variant)));
                } finally {
                    Com.clearVariant(variant);
                }
            }
            return runtimeId();
        }

        String runtimeId() {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = Com.newOutPointer(arena);
                Com.callWithPointer(pointer, GET_RUNTIME_ID, out, "IUIAutomationElement::GetRuntimeId");
                MemorySegment safeArray = out.get(ADDRESS, 0);
                try {
                    return joinRuntimeId(Com.readIntSafeArray(safeArray));
                } finally {
                    Com.destroySafeArray(safeArray);
                }
            }
        }

        String currentName() {
            return callForString(GET_CURRENT_NAME, "IUIAutomationElement::get_CurrentName");
        }

        String cachedName() {
            return callForString(GET_CACHED_NAME, "IUIAutomationElement::get_CachedName");
        }

        String cachedClassName() {
            return callForString(GET_CACHED_CLASS_NAME, "IUIAutomationElement::get_CachedClassName");
        }

        String cachedAutomationId() {
            return callForString(GET_CACHED_AUTOMATION_ID, "IUIAutomationElement::get_CachedAutomationId");
        }

        boolean currentIsOffscreen() {
            return callForInt(GET_CURRENT_IS_OFFSCREEN, "IUIAutomationElement::get_CurrentIsOffscreen") != 0;
        }

        int cachedControlType() {
            return callForInt(GET_CACHED_CONTROL_TYPE, "IUIAutomationElement::get_CachedControlType");
        }

        int cachedProcessId() {
            return callForInt(GET_CACHED_PROCESS_ID, "IUIAutomationElement::get_CachedProcessId");
        }

        private String callForString(int slot, String name) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = Com.newOutPointer(arena);
                Com.callWithPointer(pointer, slot, out, name);
                return Com.takeBstr(out);
            }
        }

        private int callForInt(int slot, String name) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(JAVA_INT);
                Com.callWithPointer(pointer, slot, out, name);
                return out.get(JAVA_INT, 0);
            }
        }

        private static String joinRuntimeId(int[] parts) {
            StringBuilder joined = new StringBuilder();
            for (int part : parts) {
                if (!joined.isEmpty())
                    joined.append('.');
                joined.append(part);
            }
            return joined.toString();
        }
    }

    //</editor-fold>

    //<editor-fold desc="IUIAutomationElementArray, CacheRequest, TreeWalker, Condition">

    /**
     * Un array di elementi. Il puntatore puo' essere nullo (UI Automation lo restituisce cosi' quando
     * non trova niente): vale come array vuoto.
     */
    static final class ElementArray implements AutoCloseable {

        private static final int GET_LENGTH = 3;
        private static final int GET_ELEMENT = 4;

        private final MemorySegment pointer;
        private boolean released;

        ElementArray(MemorySegment pointer) {
            this.pointer = pointer;
        }

        int length() {
            if (Com.isNull(pointer))
                return 0;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = arena.allocate(JAVA_INT);
                Com.callWithPointer(pointer, GET_LENGTH, out, "IUIAutomationElementArray::get_Length");
                return out.get(JAVA_INT, 0);
            }
        }

        Element get(int index) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = Com.newOutPointer(arena);
                Com.callWithIntAndPointer(pointer, GET_ELEMENT, index, out, "IUIAutomationElementArray::GetElement");
                return new Element(out.get(ADDRESS, 0));
            }
        }

        @Override
        public void close() {
            if (released || Com.isNull(pointer))
                return;
            released = true;
            Com.release(pointer);
        }
    }

    static final class CacheRequest extends ComReference {

        private static final int ADD_PROPERTY = 3;
        private static final int PUT_TREE_SCOPE = 7;
        private static final int PUT_TREE_FILTER = 9;

        private CacheRequest(MemorySegment pointer) {
            super(pointer);
        }

        CacheRequest addProperty(int propertyId) {
            Com.callWithInt(pointer, ADD_PROPERTY, propertyId, "IUIAutomationCacheRequest::AddProperty");
            return this;
        }

        CacheRequest treeScope(int scope) {
            Com.callWithInt(pointer, PUT_TREE_SCOPE, scope, "IUIAutomationCacheRequest::put_TreeScope");
            return this;
        }

        CacheRequest treeFilter(Condition filter) {
            Com.callWithPointer(pointer, PUT_TREE_FILTER, filter.pointer, "IUIAutomationCacheRequest::put_TreeFilter");
            return this;
        }
    }

    static final class TreeWalker extends ComReference {

        private static final int GET_PARENT_ELEMENT = 3;

        private TreeWalker(MemorySegment pointer) {
            super(pointer);
        }

        Element parent(Element element) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment out = Com.newOutPointer(arena);
                Com.callWithTwoPointers(pointer, GET_PARENT_ELEMENT, element.pointer, out,
                        "IUIAutomationTreeWalker::GetParentElement");
                MemorySegment parent = out.get(ADDRESS, 0);
                return Com.isNull(parent) ? null : new Element(parent);
            }
        }
    }

    static final class Condition extends ComReference {

        private Condition(MemorySegment pointer) {
            super(pointer);
        }
    }

    //</editor-fold>
}
