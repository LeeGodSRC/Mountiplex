package com.bergerkiller.mountiplex.conversion.builtin;

import java.lang.reflect.Array;
import java.util.Collection;

import com.bergerkiller.mountiplex.MountiplexUtil;
import com.bergerkiller.mountiplex.conversion.Conversion;
import com.bergerkiller.mountiplex.conversion.Converter;

public class ToStringConversion {
    public static void register() {
        // Object -> String (lazy!)
        Conversion.registerConverter(new Converter<Object, String>(Object.class, String.class) {
            @Override
            public String convertInput(Object value) {
                return value.toString();
            }

            @Override
            public int getCost() {
                return 100;
            }
        });

        // CharSequence -> String
        Conversion.registerConverter(new Converter<CharSequence, String>(CharSequence.class, String.class) {
            @Override
            public String convertInput(CharSequence value) {
                return value.toString();
            }
        });

        // Number Type -> String
        // We have to be specific otherwise Integer value 10 can result in 10.0 or vice versa
        Conversion.registerConverter(new Converter<Double, String>(Double.class, String.class) {
            @Override
            public String convertInput(Double value) {
                return Double.toString(value.doubleValue());
            }
        });
        Conversion.registerConverter(new Converter<Float, String>(Float.class, String.class) {
            @Override
            public String convertInput(Float value) {
                return Float.toString(value.floatValue());
            }
        });
        Conversion.registerConverter(new Converter<Byte, String>(Byte.class, String.class) {
            @Override
            public String convertInput(Byte value) {
                return Byte.toString(value.byteValue());
            }
        });
        Conversion.registerConverter(new Converter<Short, String>(Short.class, String.class) {
            @Override
            public String convertInput(Short value) {
                return Short.toString(value.shortValue());
            }
        });
        Conversion.registerConverter(new Converter<Integer, String>(Integer.class, String.class) {
            @Override
            public String convertInput(Integer value) {
                return Integer.toString(value.intValue());
            }
        });
        Conversion.registerConverter(new Converter<Long, String>(Long.class, String.class) {
            @Override
            public String convertInput(Long value) {
                return Long.toString(value.longValue());
            }
        });
        Conversion.registerConverter(new Converter<Boolean, String>(Boolean.class, String.class) {
            @Override
            public String convertInput(Boolean value) {
                return value.toString();
            }
        });

        // char[] -> String
        Conversion.registerConverter(new Converter<char[], String>(char[].class, String.class) {
            @Override
            public String convertInput(char[] value) {
                return String.copyValueOf((char[]) value);
            }
        });

        // Primitive[] array -> String
        for (Class<?> primitiveType : new Class<?>[] {double.class, float.class, long.class, int.class, short.class, byte.class, boolean.class}) {
            Class<?> arrType = MountiplexUtil.getArrayType(primitiveType);

            @SuppressWarnings("unchecked")
            final Converter<Object, String> elToStrConv = (Converter<Object, String>) Conversion.find(primitiveType, String.class);

            Conversion.registerConverter(new Converter<Object, String>(arrType, String.class) {
                @Override
                public String convertInput(Object value) {
                    int len = Array.getLength(value);
                    StringBuilder builder = new StringBuilder(len * 5);
                    builder.append('[');
                    for (int i = 0; i < len; i++) {
                        if (i > 0) {
                            builder.append(", ");
                        }
                        builder.append(elToStrConv.convertInput(Array.get(value, i)));
                    }
                    builder.append(']');
                    return builder.toString();
                }
            });
        }

        // Array -> String
        Conversion.registerConverter(new Converter<Object[], String>(Object[].class, String.class) {
            @Override
            public String convertInput(Object[] value) {
                Converter<?, String> elConverter = Conversion.find(value.getClass().getComponentType(), String.class);
                StringBuilder builder = new StringBuilder(value.length * 5);
                builder.append('[');
                for (int i = 0; i < value.length; i++) {
                    if (i > 0) {
                        builder.append(", ");
                    }
                    builder.append(elConverter.convert(value[i], ""));
                }
                builder.append(']');
                return builder.toString();
            }
        });

        // Collection -> String
        Conversion.registerConverter(new Converter<Collection<?>, String>(Collection.class, String.class) {
            @Override
            public String convertInput(Collection<?> collection) {
                StringBuilder builder = new StringBuilder(collection.size() * 5);
                Converter<?, String> toStringConv = Conversion.find(String.class);
                boolean first = true;
                builder.append("[");
                for (Object element : collection) {
                    if (first) {
                        first = false;
                    } else {
                        builder.append(", ");
                    }
                    builder.append(toStringConv.convert(element, "null"));
                }
                builder.append("]");
                return builder.toString();
            }
        });
    }
}
