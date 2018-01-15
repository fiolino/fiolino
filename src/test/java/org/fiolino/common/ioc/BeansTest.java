package org.fiolino.common.ioc;

import org.fiolino.annotations.Component;
import org.fiolino.annotations.Factory;
import org.fiolino.annotations.Inject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BeansTest {

    public interface MyInterface {
    }

    @Component
    public static class DefaultClass implements MyInterface {
    }

    @Component("a")
    public static class ClassNamedA implements MyInterface {
    }

    @Component("b")
    public static class ClassNamedB implements MyInterface {
    }

    @Test
    public void testDefault() {
        MyInterface bean = Beans.get(DefaultClass.class);
        assertTrue(bean instanceof DefaultClass);
    }

    @Test
    public void testNameA() {
        MyInterface bean = Beans.get("a", MyInterface.class);
        assertTrue(bean instanceof ClassNamedA);
    }

    @Test
    public void testNameB() {
        MyInterface bean = Beans.get("b", MyInterface.class);
        assertTrue(bean instanceof ClassNamedB);
    }

    public interface NextInterface {
    }

    @Component
    public static class NextInterfaceFactory implements Factory<NextInterface> {
        @Override
        public NextInterface create() throws Exception {
            return new NextInterface() {
                @Override
                public String toString() {
                    return "From factory";
                }
            };
        }
    }

    @Test
    public void testFactory() {
        NextInterface bean = Beans.get(NextInterface.class);
        String toString = bean.toString();
        assertEquals("From factory", toString);
    }

    @Component
    public static class ClassWithParameter {
        final String string;

        public ClassWithParameter(String string) {
            this.string = string;
        }
    }

    @Test
    public void testParameter() {
        ClassWithParameter bean = Beans.instantiate(ClassWithParameter.class, "Some String");
        assertEquals("Some String", bean.string);
    }

    @Component
    public static class ClassWithParameterAndBean extends ClassWithParameter {
        final MyInterface myInterface;

        public ClassWithParameterAndBean(String string, @Inject("a") MyInterface myInterface) {
            super(string);
            this.myInterface = myInterface;
        }
    }

    @Test
    public void testWithBean() {
        ClassWithParameterAndBean bean = Beans.instantiate(ClassWithParameterAndBean.class, "Another String");
        assertEquals("Another String", bean.string);
        assertTrue(bean.myInterface instanceof ClassNamedA);
    }
}
