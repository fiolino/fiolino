package org.fiolino.common.util;

import org.fiolino.common.analyzing.ClassWalker;
import org.fiolino.common.reflection.*;
import org.fiolino.data.annotation.SerialFieldIndex;
import org.fiolino.data.annotation.SerializeEmbedded;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Function;

import static java.lang.invoke.MethodHandles.lookup;
import static java.lang.invoke.MethodHandles.publicLookup;
import static java.lang.invoke.MethodType.methodType;

/**
 * Serializes instances into Strings.
 * <p>
 * The serialized class should have annotations @{@link SerialFieldIndex} or @{@link SerializeEmbedded}
 * on its fields or getters/setters.
 * <p>
 * Individual values are separated by a colon.
 * <p>
 * Created by kuli on 29.12.15.
 */
public class SerializerBuilder {

    private static final Logger logger = LoggerFactory.getLogger(SerializerBuilder.class);

    public static final Function<MethodHandles.Lookup, Serializer> BY_ANNOTATION_PROVIDER = Registry.<Function<MethodHandles.Lookup, Serializer>>buildForFunctionalType(l -> {
        SerializerBuilder b = new SerializerBuilder(l);
        b.analyze();
        return b.getSerializer();
    }).getAccessor();

    private void analyze() {
        ClassWalker<RuntimeException> walker = new ClassWalker<>();
        walker.onField(f -> {
            SerialFieldIndex fieldAnno = f.getAnnotation(SerialFieldIndex.class);
            SerializeEmbedded embedAnno = f.getAnnotation(SerializeEmbedded.class);
            if (fieldAnno == null && embedAnno == null) {
                return;
            }
            MethodHandle getter = Methods.findGetter(lookup, f);
            if (getter == null) {
                logger.warn("No getter for " + f);
                return;
            }
            if (fieldAnno != null) {
                addSerialField(getter, fieldAnno.value());
            }
            if (embedAnno != null) {
                Class<?> embeddedType = f.getType();
                Serializer s = BY_ANNOTATION_PROVIDER.apply(lookup.in(embeddedType));
                addAppender(s.getAppender());
                addSerialField(getter, embedAnno.value());
            }
        });

        walker.onMethod(m -> {
            SerialFieldIndex fieldAnno = m.getAnnotation(SerialFieldIndex.class);
            SerializeEmbedded embedAnno = m.getAnnotation(SerializeEmbedded.class);
            if (fieldAnno == null && embedAnno == null) {
                return;
            }
            if (m.getParameterCount() != 0 || m.getReturnType() == void.class) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Ignoring " + m + " because it's not a getter.");
                }
                return;
            }
            MethodHandle getter;
            try {
                getter = lookup.unreflect(m);
            } catch (IllegalAccessException e) {
                logger.warn(m + " is not accessible!");
                return;
            }
            if (fieldAnno != null) {
                addSerialField(getter, fieldAnno.value());
            }
            if (embedAnno != null) {
                Class<?> embeddedType = m.getReturnType();
                Serializer s = BY_ANNOTATION_PROVIDER.apply(lookup.in(embeddedType));
                addAppender(s.getAppender());
                addSerialField(getter, embedAnno.value());
            }
        });

        walker.analyze(getType());
        validateNotEmpty();
    }

    private static final CharSet QUOTED_CHARACTERS = CharSet.of(":,()");
    private static final MethodHandle DATE_GETTIME;
    private static final List<MethodHandle> INITIAL_APPENDERS;

    static {
        try {
            MethodHandle getTime = publicLookup().findVirtual(Date.class, "getTime", methodType(long.class));
            MethodHandle valueOf = publicLookup().findStatic(String.class, "valueOf", methodType(String.class, long.class));
            DATE_GETTIME = MethodHandles.filterReturnValue(getTime, valueOf);
        } catch (NoSuchMethodException | IllegalAccessException ex) {
            throw new InternalError(ex);
        }

        INITIAL_APPENDERS = addAppendersFrom(Appenders.LOOKUP, new ArrayList<>(), Appenders.class);
    }

    private static <T extends Collection<MethodHandle>> T addAppendersFrom(MethodHandles.Lookup lookup, T appenders, Object appenderContainer) {
        return Methods.visitMethodsWithStaticContext(lookup, appenderContainer, appenders,
                (list, m, supp) -> {
                    if (isAppender(m)) {
                        list.add(supp.get());
                    }
                    return list;
                });
    }

    private static boolean isAppender(Method m) {
        return m.getParameterCount() == 2 && StringBuilder.class.equals(m.getParameterTypes()[0]);
    }

    private static boolean isAppender(MethodHandle h) {
        return h.type().parameterCount() == 2 && StringBuilder.class.equals(h.type().parameterType(0));
    }

    private MethodHandle[] getters = new MethodHandle[0];
    private final MethodHandles.Lookup lookup;
    private final ConverterLocator converterLocator;
    private final List<MethodHandle> appenders;

    private SerializerBuilder(MethodHandles.Lookup lookup) {
        this(lookup, Converters.defaultConverters.register(DATE_GETTIME));
    }

    private SerializerBuilder(MethodHandles.Lookup lookup, ConverterLocator converterLocator) {
        this.lookup = lookup;
        this.converterLocator = converterLocator;

        appenders = new ArrayList<>(INITIAL_APPENDERS);
    }

    public void addAppenders(MethodHandles.Lookup lookup, Object appenderContainer) {
        addAppendersFrom(lookup, appenders, appenderContainer);
    }

    public void addAppender(MethodHandle appender) {
        if (!isAppender(appender)) {
            throw new IllegalArgumentException(appender + " should accept a StringBuilder and a bean.");
        }
        appenders.add(appender);
    }

    private void validateNotEmpty() {
        if (getters.length == 0) {
            throw new IllegalStateException("No serialized fields in " + getType().getName());
        }
    }

    @Override
    public String toString() {
        return "SerializerBuilder for " + getType().getName();
    }

    public Class<?> getType() {
        return lookup().lookupClass();
    }

    private void addGetter(int pos, MethodHandle getter) {
        int n = getters.length;
        if (pos >= n) {
            getters = Arrays.copyOf(getters, pos + 1);
        }
        getters[pos] = getter;
    }

    /**
     * Adds some getter as a serial field.
     *
     * @param getter The getter handle
     * @param fieldIndex The index
     */
    public void addSerialField(MethodHandle getter, int fieldIndex) {
        MethodType getterType = getter.type();
        if (getterType.returnType() == void.class) {
            throw new IllegalArgumentException("Getter " + getter + " should return some value!");
        }
        if (getterType.parameterCount() != 1) {
            throw new IllegalArgumentException("Getter " + getter + " should accept some bean!");
        }
        if (!getterType.parameterType(0).isAssignableFrom(getType())) {
            throw new IllegalArgumentException("Getter " + getter + " should accept " + getType().getName());
        }
        getter = getter.asType(getterType.changeParameterType(0, getType()));
        addGetter(fieldIndex, getter);
    }

    MethodHandle buildSerializingHandle() {
        validateNotEmpty();

        MethodHandle all = null;
        for (MethodHandle g : getters) {
            if (g == null) continue;

            Class<?> r = g.type().returnType();
            MethodHandle h = findHandleFor(r);
            h = h.asType(methodType(void.class, StringBuilder.class, r));
            h = MethodHandles.filterArguments(h, 1, g);

            if (all == null) all = h;
            else {
                all = MethodHandles.foldArguments(h, all);
            }
        }

        return all;
    }

    public Serializer getSerializer() {
        return new Serializer(this);
    }

    private MethodHandle findHandleFor(Class<?> type) {
        try {
            Converter converter = converterLocator.find(type, String.class, int.class, long.class, double.class, float.class, boolean.class);
            MethodHandle append = findAppendMethod(converter.getTargetType());
            MethodHandle c = converter.getConverter();
            if (c == null) {
                return append;
            }
            return MethodHandles.filterArguments(append, 1, c);
        } catch (NoMatchingConverterException ex) {
            return findAppendMethod(Object.class);
        }
    }

    private MethodHandle findAppendMethod(Class<?> printedType) {
        if (printedType.isPrimitive()) {
            return findDirectAppendMethod(printedType);
        }

        MethodHandle found = null;
        int d = Integer.MAX_VALUE;
        for (MethodHandle a : appenders) {
            Class<?> appenderType = a.type().parameterType(1);
            int distance = Types.distanceOf(appenderType, printedType);
            if (distance == 0) {
                return a;
            }
            if (distance > 0 && distance < d) {
                d = distance;
                found = a;
            }
        }
        assert found != null : "There must be some match at least at Object";
        return found;
    }

    private MethodHandle findDirectAppendMethod(Class<?> printedType) {
        MethodHandle sbAppend;
        try {
            sbAppend = publicLookup().findVirtual(StringBuilder.class, "append", methodType(StringBuilder.class, printedType));
        } catch (NoSuchMethodException | IllegalAccessException ex) {
            throw new InternalError("Missing StringBuilder.append(" + printedType.getName() + ")", ex);
        }
        return sbAppend.asType(methodType(void.class, StringBuilder.class, printedType));
    }

    @SuppressWarnings("unused")
    private static class Appenders {
        static final MethodHandles.Lookup LOOKUP = lookup();

        @ExecuteDirect
        private static void append(StringBuilder sb, Iterable<?> coll) {
            boolean first = true;
            for (Object v : coll) {
                if (first) {
                    first = false;
                } else {
                    sb.append(',');
                }
                if (v == null) {
                    continue;
                }
                append(sb, v);
            }
        }

        @ExecuteDirect
        private static void append(StringBuilder sb, Object val) {
            if (val instanceof String) {
                append(sb, (String) val);
            } else {
                sb.append(val);
            }
        }

        @ExecuteDirect
        private static void append(StringBuilder sb, String s) {
            if (shouldBeQuoted(s)) {
                Strings.appendQuotedString(sb, s);
            } else {
                sb.append(s);
            }
        }

        private static boolean shouldBeQuoted(String val) {
            return val.isEmpty() || QUOTED_CHARACTERS.isContainedIn(val) || val.charAt(0) == '"';
        }

        @MethodHandleFactory
        private static MethodHandle findDirectAppendMethod(Class<?> printedType) {
            MethodHandle sbAppend;
            try {
                sbAppend = publicLookup().findVirtual(StringBuilder.class, "append", methodType(StringBuilder.class, printedType));
            } catch (NoSuchMethodException | IllegalAccessException ex) {
                throw new InternalError("Missing StringBuilder.append(" + printedType.getName() + ")", ex);
            }
            return sbAppend.asType(methodType(void.class, StringBuilder.class, printedType));
        }
    }
}