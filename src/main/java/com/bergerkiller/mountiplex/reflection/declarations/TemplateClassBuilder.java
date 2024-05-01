package com.bergerkiller.mountiplex.reflection.declarations;

import static org.objectweb.asm.Opcodes.*;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import com.bergerkiller.mountiplex.MountiplexUtil;
import com.bergerkiller.mountiplex.reflection.ReflectionUtil;
import com.bergerkiller.mountiplex.reflection.declarations.Template.Handle;
import com.bergerkiller.mountiplex.reflection.resolver.ClassDeclarationResolver;
import com.bergerkiller.mountiplex.reflection.resolver.Resolver;
import com.bergerkiller.mountiplex.reflection.util.ExtendedClassWriter;
import com.bergerkiller.mountiplex.reflection.util.asm.MPLType;
import com.bergerkiller.mountiplex.reflection.util.fast.Invoker;

/**
 * Reads the abstract class information of a Template {@link Template.Class Class} type and generates an appropriate
 * implementation of the abstract methods. Since the Class is a singleton, this builder produces an instance
 * of the Class rather than the Class object itself. Of this instance, all the fields are initialized.
 */
public class TemplateClassBuilder<C extends Template.Class<H>, H extends Handle> {
    public final ClassDeclarationResolver classDeclarationResolver;
    public final java.lang.Class<C> classType;
    public final java.lang.Class<H> handleType;
    public final java.lang.Class<?> instanceType;
    public final String instanceClassPath;
    public final String classPackage;
    public final List<String> classImports;
    public final boolean isOptional;
    public final ClassDeclaration classDec;

    @SuppressWarnings("unchecked")
    public TemplateClassBuilder(java.lang.Class<C> classType, ClassDeclarationResolver classDeclarationResolver) {
        this.classDeclarationResolver = classDeclarationResolver;
        this.classType = classType;

        // Identify the Handle type used alongside this Class
        // If this is a top-level Class that is not inside a Handle, it creates
        // dummy Handle classes without any special implementation inside.
        java.lang.Class<?> handleType = this.classType.getDeclaringClass();
        if (handleType != null && Handle.class.isAssignableFrom(handleType)) {
            this.handleType = (java.lang.Class<H>) handleType;
        } else {
            this.handleType = (java.lang.Class<H>) Handle.class;
        }

        // Identify what instance type is represented by this Class
        // If we can't deduce it, assume Object for maximum compatibility
        this.instanceClassPath = ReflectionUtil.recurseFindAnnotationValue(classType, Template.InstanceType.class,
                Template.InstanceType::value, "java.lang.Object");

        // Identify whether the Optional annotation is specified
        this.isOptional = ReflectionUtil.recurseFindAnnotationValue(classType, Template.Optional.class,
                (a) -> Boolean.TRUE, Boolean.FALSE);

        // Identify package path that is used while parsing signatures
        this.classPackage = ReflectionUtil.recurseFindAnnotationValue(classType, Template.Package.class,
                Template.Package::value, null);

        // Identify all the imports that will be used by this class
        this.classImports = ReflectionUtil.getAllDeclaringClasses(classType)
                .flatMap(c -> Stream.of(c.getAnnotationsByType(Template.Import.class)))
                .map(Template.Import::value)
                .collect(Collectors.toList());
        Collections.reverse(this.classImports);

        // Resolve the Class Path to an actual Class object
        // If not found, and the Class is not meant to be optional, log an error
        this.instanceType = Resolver.loadClass(this.instanceClassPath, false);
        if (this.instanceType == null && !this.isOptional) {
            MountiplexUtil.LOGGER.log(Level.SEVERE, "Class " + this.instanceClassPath + " not found; Template '" +
                    handleType.getSimpleName() + " not initialized.");
        }

        // If found, also resolve the class declaration to use
        // If one is expected but not found, log an error
        if (this.instanceType != null && this.instanceType != Object.class && classDeclarationResolver != null) {
            this.classDec = classDeclarationResolver.resolveClassDeclaration(this.instanceClassPath, this.instanceType);
        } else {
            this.classDec = null;
        }
    }

    /**
     * Builds the most appropriate {@link Template.Class Class} instance given the provided information
     * 
     * @return Class
     */
    public C build() {
        // If the class type is abstract then we must extend it, and implement whatever we can
        if (Modifier.isAbstract(this.classType.getModifiers())) {
            return buildExtend();
        }

        // By default, just call the default constructor on the Class type
        return buildDefault();
    }

    /**
     * Builds the Class object by using the original class, no new class is generated
     * 
     * @return constructed Class
     */
    @SuppressWarnings("deprecation")
    private C buildDefault() {
        try {
            C generatedClass = this.classType.newInstance();
            generatedClass.init(this);
            return generatedClass;
        } catch (Throwable t) {
            throw MountiplexUtil.uncheckedRethrow(t);
        }
    }

    /**
     * Extends the Class type specified and implements the missing methods
     * 
     * @return constructed Class
     */
    private C buildExtend() {
        // Set up the class writer for the implementation of the class type
        ExtendedClassWriter<C> cw = ExtendedClassWriter.builder(this.classType)
                .setFlags(ClassWriter.COMPUTE_MAXS)
                .setAccess(ACC_FINAL)
                .setClassLoader(this.classType.getClassLoader())
                .setPostfix("$impl").build();

        MethodVisitor mv;

        // Add default empty constructor
        mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, MPLType.getInternalName(this.classType), "<init>", "()V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();

        // Override getSelfClassType() and return getClass().getSuperClass() instead
        mv = cw.visitMethod(ACC_PUBLIC, "getSelfClassType", "()Ljava/lang/Class;", "()Ljava/lang/Class<*>;", null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getSuperclass", "()Ljava/lang/Class;", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();

        int fieldNameIdx = 1; // Makes sure field names are unique

        ClassResolver resolver = null;
        for (Method method : this.classType.getDeclaredMethods()) {
            Template.Generated generatedAnnot = method.getAnnotation(Template.Generated.class);
            if (generatedAnnot == null) {
                continue; // Skip
            }

            // First-time initialization of ClassResolver used while loading declarations
            if (resolver == null) {
                resolver = new ClassResolver();
                resolver.setClassLoader(cw.getClassLoader());
                resolver.setDeclaredClass(this.instanceType, this.instanceClassPath);
                if (this.classDeclarationResolver != null) {
                    resolver.setAllVariables(this.classDeclarationResolver);
                } else {
                    resolver.setAllVariables(Resolver.resolveClassVariables(this.instanceClassPath, this.instanceType));
                }
                if (this.classPackage != null) {
                    resolver.setPackage(this.classPackage, false);
                }
                resolver.addImports(this.classImports);

                // Add all requirement annotations to this class resolver
                {
                    StringBuilder requirementsText = new StringBuilder();
                    for (Template.Require require : classType.getAnnotationsByType(Template.Require.class)) {
                        String body = SourceDeclaration.preprocess(require.value(), resolver).trim();
                        if (body.isEmpty()) {
                            continue; // Skip, conditional requirements
                        }

                        if (!require.declaring().isEmpty()) {
                            requirementsText.append("#require ");
                            requirementsText.append(require.declaring()).append(' ');
                        } else if (!body.startsWith("#")) {
                            // Omit if the body already puts a #require (or other?) there
                            requirementsText.append("#require ");
                        }
                        requirementsText.append(body).append('\n');
                    }
                    if (requirementsText.length() > 0) {
                        // Kind of hacky, but make use of a ClassDeclaration to parse these requirements
                        // The parsing code has a bunch of code of advancing the text, so this is easier
                        // Add some mockup of a class to make it valid
                        requirementsText.insert(0, "class DummyClassPleaseIgnore {\n");
                        requirementsText.append("}");
                        ClassDeclaration cc = new ClassDeclaration(resolver, requirementsText.toString());
                        for (Requirement r : cc.getResolver().getRequirements()) {
                            resolver.storeRequirement(r);
                        }
                    }
                }
            }

            // Preprocess the source declaration to handle things like #if
            String preprocessedDeclarationStr = SourceDeclaration.preprocess(generatedAnnot.value(), resolver);

            // Use the resolver to decode the declaration in the annotation
            Declaration parsedDeclaration = Declaration.parseDeclaration(resolver, preprocessedDeclarationStr);
            if (parsedDeclaration == null || !parsedDeclaration.isValid()) {
                MountiplexUtil.LOGGER.warning("Declaration for method " + MPLType.getName(method) +
                        " could not be parsed: " + preprocessedDeclarationStr);
                cw.visitMethodUnsupported(method, "Declaration for this generated method could not be parsed");
                continue;
            } else if (!parsedDeclaration.isResolved()) {
                MountiplexUtil.LOGGER.warning("Declaration for method " + MPLType.getName(method) +
                        " could not be resolved: " + parsedDeclaration);
                cw.visitMethodUnsupported(method, "Declaration for this generated method could not be resolved (missing types)");
                continue;
            }

            // Discover the real method, if possible
            Declaration declaration = parsedDeclaration.discover();
            if (declaration == null) {
                cw.visitMethodUnsupported(method, "Failed to find: " + parsedDeclaration);
                continue;
            }
            if (declaration instanceof FieldDeclaration) {
                cw.visitMethodUnsupported(method, "Field getters/setters not implemented yet");
                continue;
            }
            if (declaration instanceof MethodDeclaration) {
                MethodDeclaration methodDec = (MethodDeclaration) declaration;

                if (!methodDec.modifiers.isStatic() && methodDec.constructor == null) {
                    // public int() declaration, while only public static int() is supported
                    cw.visitMethodUnsupported(method, "Local methods cannot be called statically");
                    continue;
                }
                if (methodDec.body == null && methodDec.method == null && methodDec.constructor == null) {
                    // not a generated method, but the method could not be found
                    cw.visitMethodUnsupported(method, "Static method '" + methodDec.name.toString() + "' was not found");
                    continue;
                }

                //TODO: Conversion. For now just fail the method body when used
                boolean hasConversion = false;
                if (methodDec.returnType.cast != null &&
                    !methodDec.returnType.cast.isAssignableFrom(methodDec.returnType) &&
                    !methodDec.returnType.isAssignableFrom(methodDec.returnType.cast))
                {
                    hasConversion = true;
                } else {
                    for (ParameterDeclaration param : methodDec.parameters.parameters) {
                        if (param.type.cast != null &&
                            !param.type.isAssignableFrom(param.type.cast) &&
                            !param.type.cast.isAssignableFrom(param.type))
                        {
                            hasConversion = true;
                            break;
                        }
                    }
                }
                if (hasConversion) {
                    cw.visitMethodUnsupported(method, "Conversion of parameters/return type is not supported yet");
                    continue;
                }

                boolean isPublic = (methodDec.method != null && Modifier.isPublic(methodDec.method.getModifiers())) ||
                                   (methodDec.constructor != null && Modifier.isPublic(methodDec.constructor.getModifiers()));

                if (methodDec.body == null && isPublic && Resolver.isPublic(this.instanceType)) {
                    // static method can be called from within the method body just fine
                    mv = cw.visitMethod(ACC_PUBLIC, MPLType.getName(method), MPLType.getMethodDescriptor(method), null, null);
                    mv.visitCode();

                    // for constructors we must perform a NEW and DUP up-front, before loading parameters
                    if (methodDec.constructor != null) {
                        mv.visitTypeInsn(NEW, MPLType.getInternalName(methodDec.constructor.getDeclaringClass()));
                        mv.visitInsn(DUP);
                    }

                    // load all the parameters onto the stack
                    int varIdx = 1;
                    for (ParameterDeclaration param : methodDec.parameters.parameters) {
                        varIdx = MPLType.visitVarILoad(mv, varIdx, param.type.exposed().type);
                        if (param.type.cast != null) {
                            ExtendedClassWriter.visitUnboxObjectVariable(mv, param.type.type);
                        }
                    }

                    if (methodDec.constructor != null) {
                        // call static constructor directly and proxy-return the return value
                        mv.visitMethodInsn(INVOKESPECIAL, MPLType.getInternalName(this.instanceType), "<init>",
                                MPLType.getInternalMethodDescriptor(methodDec), false);
                    } else {
                        // call static method directly and proxy-return the return value
                        mv.visitMethodInsn(INVOKESTATIC, MPLType.getInternalName(this.instanceType), methodDec.name.value(),
                                MPLType.getInternalMethodDescriptor(methodDec), false);
                    }
                    if (methodDec.returnType.cast != null) {
                        ExtendedClassWriter.visitUnboxObjectVariable(mv, methodDec.returnType.cast.type);
                    }
                    mv.visitInsn(MPLType.getOpcode(method.getReturnType(), IRETURN));
                    if (methodDec.constructor != null) {
                        mv.visitMaxs(varIdx + 1, varIdx);
                    } else {
                        mv.visitMaxs(varIdx, varIdx);
                    }
                    mv.visitEnd();
                } else {
                    // we need to use reflection or runtime-code-gen to call this method.
                    // for this we add a static Invoker field to handle initialization/execution for us
                    // the invoker is initialized in such a way that, when first called, it will update the field storing it
                    String invoker_name = methodDec.name.real() + "_invoker_" + (fieldNameIdx++);
                    if (invoker_name.startsWith("<init>_")) {
                        invoker_name = "initializer_" + invoker_name.substring(7); // Fix invalid names
                    }
                    cw.visitStaticInvokerField(invoker_name, methodDec);

                    // Add a method body that calls the invoker's invoke() method
                    mv = cw.visitMethod(ACC_PUBLIC, MPLType.getName(method), MPLType.getMethodDescriptor(method), null, null);
                    mv.visitCode();

                    // Store the static invoker field instance onto the stack
                    mv.visitFieldInsn(GETSTATIC, cw.getInternalName(), invoker_name, MPLType.getDescriptor(Invoker.class));

                    // Null instance onto the stack, because static
                    mv.visitInsn(ACONST_NULL);

                    // Invoke the right virtual method of the Invoker interface with the input parameters
                    if (methodDec.parameters.parameters.length > 5) {
                        // More than five, must pack parameters into an array first
                        ExtendedClassWriter.visitPushInt(mv, methodDec.parameters.parameters.length);
                        mv.visitTypeInsn(ANEWARRAY, "java/lang/Object");

                        int varIdx = 1;
                        for (Class<?> paramType : method.getParameterTypes()) {
                            mv.visitInsn(DUP);
                            ExtendedClassWriter.visitPushInt(mv, varIdx-1);
                            varIdx = MPLType.visitVarILoadAndBox(mv, varIdx, paramType);
                            mv.visitInsn(AASTORE);
                        }

                        // invokeVA
                        mv.visitMethodInsn(INVOKEINTERFACE, MPLType.getInternalName(Invoker.class), "invokeVA",
                                "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", true);

                    } else {
                        // Load parameters onto the stack
                        int varIdx = 1;
                        for (Class<?> paramType : method.getParameterTypes()) {
                            varIdx = MPLType.visitVarILoadAndBox(mv, varIdx, paramType);
                        }

                        // invoke(null, [0], [1], [2], [3], [4])
                        StringBuilder descriptor = new StringBuilder();
                        descriptor.append('(');
                        for (int i = 0; i <= methodDec.parameters.parameters.length; i++) {
                            descriptor.append("Ljava/lang/Object;");
                        }
                        descriptor.append(")Ljava/lang/Object;");

                        mv.visitMethodInsn(INVOKEINTERFACE, MPLType.getInternalName(Invoker.class), "invoke",
                                descriptor.toString(), true);
                    }

                    if (method.getReturnType() == void.class) {
                        // No return value. Pop (ignore) the returned Object and return instantly.
                        mv.visitInsn(POP);
                        mv.visitInsn(RETURN);
                    } else {
                        // Return type is Object, if not already Object, convert to the right return value
                        // We might need to unbox values such as Integer/Long/etc.
                        ExtendedClassWriter.visitUnboxObjectVariable(mv, method.getReturnType());
                        mv.visitInsn(MPLType.getOpcode(method.getReturnType(), IRETURN));
                    }

                    //TODO: Compute these, for now it requires the auto-compute flag to be set
                    mv.visitMaxs(0, 0);
                    mv.visitEnd();
                }

                continue;
            }

            cw.visitMethodUnsupported(method, "Not supported yet");
        }

        // Generate the final Class instance and further initialize it
        C generatedClass = cw.generateInstance(new Class[0], new Object[0]);
        generatedClass.init(this);
        return generatedClass;
    }
}
