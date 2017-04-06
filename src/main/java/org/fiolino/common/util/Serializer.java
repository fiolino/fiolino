package org.fiolino.common.util;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

import static java.lang.invoke.MethodHandles.publicLookup;
import static java.lang.invoke.MethodType.methodType;

/**
 * Created by kuli on 21.03.17.
 */
public final class Serializer {
    private static final MethodHandle NEW_STRINGBUILDER, APPEND_OPENING_PARENTHESIS, APPEND_CLOSING_PARENTHESIS;

    static {
        MethodHandle appendChar;
        try {
            NEW_STRINGBUILDER = publicLookup().findConstructor(StringBuilder.class, methodType(void.class));
            appendChar = publicLookup().findVirtual(StringBuilder.class, "append", methodType(StringBuilder.class, char.class))
                    .asType(methodType(void.class, StringBuilder.class, char.class));
        } catch (NoSuchMethodException | IllegalAccessException ex) {
            throw new InternalError(ex);
        }

        APPEND_OPENING_PARENTHESIS = MethodHandles.insertArguments(appendChar, 1, '(');
        APPEND_CLOSING_PARENTHESIS = MethodHandles.insertArguments(appendChar, 1, ')');
    }

    private final MethodHandle appender, printer;

    Serializer(SerializerBuilder s) {
        MethodHandle h = s.buildSerializingHandle();

        MethodHandle closingParenthesis = MethodHandles.dropArguments(APPEND_CLOSING_PARENTHESIS, 1, s.getType());
        MethodHandle a = MethodHandles.foldArguments(closingParenthesis, h);
        appender = MethodHandles.foldArguments(h, APPEND_OPENING_PARENTHESIS);

        printer = MethodHandles.foldArguments(h, NEW_STRINGBUILDER);
    }

    public MethodHandle getAppender() {
        return appender;
    }

    public MethodHandle getPrinter() {
        return printer;
    }
}
