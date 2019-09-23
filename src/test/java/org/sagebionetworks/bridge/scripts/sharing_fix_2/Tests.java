package org.sagebionetworks.bridge.scripts.sharing_fix_2;

import java.lang.reflect.Field;

public class Tests {
    public static void setVariableValueInObject(Object object, String variable, Object value) {
        try {
            Field field = getFieldByNameIncludingSuperclasses(variable, object.getClass());
            field.setAccessible(true);
            field.set(object, value);
        } catch (IllegalArgumentException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
    @SuppressWarnings("rawtypes")
    private static Field getFieldByNameIncludingSuperclasses(String fieldName, Class clazz) {
        Field retValue = null;
        try {
            retValue = clazz.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            Class superclass = clazz.getSuperclass();
            if ( superclass != null ) {
                retValue = getFieldByNameIncludingSuperclasses( fieldName, superclass );
            }
        }
        return retValue;
    }    
}
