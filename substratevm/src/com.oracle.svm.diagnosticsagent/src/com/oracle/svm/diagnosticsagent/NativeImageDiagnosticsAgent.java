/*
 * Copyright (c) 2020, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.diagnosticsagent;

import static com.oracle.svm.jni.JNIObjectHandles.nullHandle;
import static com.oracle.svm.jvmtiagentbase.Support.check;
import static com.oracle.svm.jvmtiagentbase.Support.jvmtiFunctions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.graalvm.compiler.serviceprovider.JavaVersionUtil;
import org.graalvm.nativeimage.StackValue;
import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.function.CEntryPointLiteral;
import org.graalvm.nativeimage.c.function.CFunctionPointer;
import org.graalvm.nativeimage.c.struct.SizeOf;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CCharPointerPointer;
import org.graalvm.nativeimage.c.type.CIntPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.nativeimage.c.type.WordPointer;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.word.Pointer;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.c.function.CEntryPointOptions;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.hosted.agent.TracingAdvisor;
import com.oracle.svm.jni.nativeapi.JNIEnvironment;
import com.oracle.svm.jni.nativeapi.JNIJavaVM;
import com.oracle.svm.jni.nativeapi.JNIMethodId;
import com.oracle.svm.jni.nativeapi.JNIObjectHandle;
import com.oracle.svm.jvmtiagentbase.AgentIsolate;
import com.oracle.svm.jvmtiagentbase.JNIHandleSet;
import com.oracle.svm.jvmtiagentbase.JvmtiAgentBase;
import com.oracle.svm.jvmtiagentbase.Support;
import com.oracle.svm.jvmtiagentbase.jvmti.JvmtiCapabilities;
import com.oracle.svm.jvmtiagentbase.jvmti.JvmtiEnv;
import com.oracle.svm.jvmtiagentbase.jvmti.JvmtiEnv11;
import com.oracle.svm.jvmtiagentbase.jvmti.JvmtiError;
import com.oracle.svm.jvmtiagentbase.jvmti.JvmtiEvent;
import com.oracle.svm.jvmtiagentbase.jvmti.JvmtiEventCallbacks;
import com.oracle.svm.jvmtiagentbase.jvmti.JvmtiEventMode;
import com.oracle.svm.jvmtiagentbase.jvmti.JvmtiFrameInfo;
import com.oracle.svm.jvmtiagentbase.jvmti.JvmtiInterface;
import com.oracle.svm.jvmtiagentbase.jvmti.JvmtiLineNumberEntry;

/**
 * JVMTI agent that provides diagnostics information that helps resolve native-image build failures.
 *
 * Currently, this agent tracks how a specified set of classes got initialized and how objects of a
 * specified set of classes got instantiated during a native-image build.
 *
 * The agent is configured through command-line options generated by
 * {@link com.oracle.svm.driver.NativeImage}, constructed from user-specified options.
 *
 * The agent works by setting breakpoints at the start of class initializers (&lt;clinit&gt;
 * methods) and constructors (&lt;init&gt; methods) for the relevant classes. Once a particular
 * class is loaded, it will trigger a JVMTI ClassPrepare event, during which the breakpoints get
 * set. Once a breakpoint is hit we find the particular class the method originates from and call
 * our reporting classes using JNI.
 *
 * If a desired class does not have a static initializer, a synthetic one will be generated by
 * {@link com.oracle.svm.hosted.agent.NativeImageBytecodeInstrumentationAgent}.
 */
public class NativeImageDiagnosticsAgent extends JvmtiAgentBase<NativeImageDiagnosticsAgentJNIHandleSet> {
    private static final CEntryPointLiteral<CFunctionPointer> ON_CLASS_PREPARE = CEntryPointLiteral.create(NativeImageDiagnosticsAgent.class, "onClassPrepare",
                    JvmtiEnv.class, JNIEnvironment.class, JNIObjectHandle.class, JNIObjectHandle.class);

    private static final CEntryPointLiteral<CFunctionPointer> ON_BREAKPOINT = CEntryPointLiteral.create(NativeImageDiagnosticsAgent.class, "onBreakpoint",
                    JvmtiEnv.class, JNIEnvironment.class, JNIObjectHandle.class, JNIMethodId.class, long.class);

    /* Maps a JNIMethodId of <clinit> to a class handle */
    private final Map<Long, ClassHandleHolder> clinitClassMap = new ConcurrentHashMap<>();
    /* Maps JNIMethodIds of <init> to a class handle */
    private final Map<Long, ClassHandleHolder> initClassMap = new ConcurrentHashMap<>();

    private TracingAdvisor advisor;

    private static final long LINE_NUMBER_UNAVAILABLE = -1;

    /*
     * We cannot store a JNIObjectHandle directly in a map so we store it in a wrapper class.
     */
    private static final class ClassHandleHolder {
        final JNIObjectHandle clazz;

        ClassHandleHolder(JNIObjectHandle clazz) {
            this.clazz = clazz;
        }
    }

    private static final class MethodIdHolder {
        final JNIMethodId methodId;

        MethodIdHolder(JNIMethodId methodId) {
            this.methodId = methodId;
        }
    }

    @Override
    protected JNIHandleSet constructJavaHandles(JNIEnvironment env) {
        return new NativeImageDiagnosticsAgentJNIHandleSet(env);
    }

    @Override
    protected int onLoadCallback(JNIJavaVM vm, JvmtiEnv jvmti, JvmtiEventCallbacks callbacks, String options) {
        advisor = new TracingAdvisor(options);

        enableCapabilities(jvmti);

        callbacks.setClassPrepare(ON_CLASS_PREPARE.getFunctionPointer());
        callbacks.setBreakpoint(ON_BREAKPOINT.getFunctionPointer());

        jvmti.getFunctions().SetEventNotificationMode().invoke(jvmti, JvmtiEventMode.JVMTI_ENABLE, JvmtiEvent.JVMTI_EVENT_BREAKPOINT, nullHandle());
        return 0;
    }

    @Override
    protected void onVMInitCallback(JvmtiEnv jvmti, JNIEnvironment jni, JNIObjectHandle thread) {
        if (JavaVersionUtil.JAVA_SPEC > 8) {
            openInstrumentationModuleToAllOtherModules((JvmtiEnv11) jvmti, jni);
        }
        handles().initializeTrackingSupportHandles(jni);
        /*
         * This is the earliest VM phase in which we can set breakpoints. This means that we cannot
         * set breakpoints for classes loaded very early during the VM startup.
         */
        jvmti.getFunctions().SetEventNotificationMode().invoke(jvmti, JvmtiEventMode.JVMTI_ENABLE, JvmtiEvent.JVMTI_EVENT_CLASS_PREPARE, nullHandle());
        /*
         * Some classes the user might be interested in (e.g. java.lang.Thread) have been
         * initialized early in the VM startup. Check if the user specified any of those classes and
         * track their objects' instantiation accordingly.
         */
        setConstructorBreakpointsForLoadedClasses(jvmti, jni);
    }

    private void setConstructorBreakpointsForLoadedClasses(JvmtiEnv jvmti, JNIEnvironment jni) {
        CIntPointer classCountPtr = StackValue.get(CIntPointer.class);
        WordPointer classesPtr = StackValue.get(WordPointer.class);
        check(jvmtiFunctions().GetLoadedClasses().invoke(jvmti, classCountPtr, classesPtr));
        WordPointer classesArray = classesPtr.read();

        int classCount = classCountPtr.read();
        for (int i = 0; i < classCount; ++i) {
            JNIObjectHandle clazz = classesArray.read(i);
            String className = Support.getClassNameOrNull(jni, clazz);
            if (advisor.shouldTraceObjectInstantiation(className)) {
                setConstructorBreakpointsForClass(jvmti, jni, clazz, className);
            }
        }

        jvmtiFunctions().Deallocate().invoke(jvmti, classesArray);
    }

    private void onClassPrepareCallback(JvmtiEnv jvmti, JNIEnvironment jni, JNIObjectHandle clazz) {
        String className = Support.getClassNameOrNull(jni, clazz);
        if (className != null) {
            if (advisor.shouldTraceClassInitialization(className)) {
                JNIMethodId clinitMethodId = getClassClinitMethodIdOrNull(jvmti, clazz);
                if (clinitMethodId.notEqual(nullHandle())) {
                    JNIObjectHandle klass = handles().newTrackedGlobalRef(jni, clazz);
                    ClassHandleHolder classHandleHolder = new ClassHandleHolder(klass);
                    clinitClassMap.put(clinitMethodId.rawValue(), classHandleHolder);
                    check(jvmti.getFunctions().SetBreakpoint().invoke(jvmti, clinitMethodId, 0L));
                } else {
                    System.err.println("Trace class initialization requested for " + className + " but the class has not been instrumented with <clinit>.");
                }
            }
            if (advisor.shouldTraceObjectInstantiation(className)) {
                setConstructorBreakpointsForClass(jvmti, jni, clazz, className);
            }
        }
    }

    private void setConstructorBreakpointsForClass(JvmtiEnv jvmti, JNIEnvironment jni, JNIObjectHandle clazz, String className) {
        List<MethodIdHolder> initMethodIds = getClassMethodIdsWithName(jvmti, clazz, "<init>");
        if (initMethodIds.size() != 0) {
            JNIObjectHandle klass = handles().newTrackedGlobalRef(jni, clazz);
            ClassHandleHolder classHandleHolder = new ClassHandleHolder(klass);
            for (MethodIdHolder holder : initMethodIds) {
                initClassMap.put(holder.methodId.rawValue(), classHandleHolder);
                check(jvmti.getFunctions().SetBreakpoint().invoke(jvmti, holder.methodId, 0L));
            }
        } else {
            /* This should never happen. */
            System.err.println("Trace object instantiation requested for " + className + " but the class has no constructors.");
        }
    }

    private void onBreakpointCallback(JvmtiEnv jvmti, JNIEnvironment jni, JNIObjectHandle thread, JNIMethodId method) {
        if (clinitClassMap.get(method.rawValue()) != null) {
            handleClinitBreakpoint(jvmti, jni, method);
        } else if (initClassMap.get(method.rawValue()) != null) {
            handleInitBreakpoint(jvmti, jni, thread);
        } else {
            throw VMError.shouldNotReachHere(
                            "Breakpoint hit for a method that isn't tracked in the diagnostics agent. (For developers: have you set a breakpoint in a method that isn't <clinit> or <init>)");
        }
    }

    private void handleClinitBreakpoint(JvmtiEnv jvmti, JNIEnvironment jni, JNIMethodId method) {
        JNIObjectHandle clazz = clinitClassMap.get(method.rawValue()).clazz;
        JNIObjectHandle threadStackTrace = getCurrentThreadStackTrace(jvmti, jni);
        reportClassInitialized(jni, clazz, threadStackTrace);
    }

    private void handleInitBreakpoint(JvmtiEnv jvmti, JNIEnvironment jni, JNIObjectHandle thread) {
        WordPointer thisPtr = StackValue.get(WordPointer.class);
        check(jvmti.getFunctions().GetLocalInstance().invoke(jvmti, thread, 0, thisPtr));
        JNIObjectHandle thisHandle = thisPtr.read();
        JNIObjectHandle threadStackTrace = getCurrentThreadStackTrace(jvmti, jni);
        reportObjectInstantiated(jni, thisHandle, threadStackTrace);
    }

    private static void enableCapabilities(JvmtiEnv jvmti) {
        JvmtiCapabilities capabilities = UnmanagedMemory.calloc(SizeOf.get(JvmtiCapabilities.class));
        check(jvmti.getFunctions().GetCapabilities().invoke(jvmti, capabilities));
        capabilities.setCanGenerateBreakpointEvents(1);
        capabilities.setCanAccessLocalVariables(1);
        check(jvmti.getFunctions().AddCapabilities().invoke(jvmti, capabilities));
        /*
         * Getting Java source lines and the source file name are optional capabilities. Try to
         * enable them, but do not fail if they aren't available.
         */
        capabilities.setCanGetLineNumbers(1);
        capabilities.setCanGetSourceFileName(1);
        jvmti.getFunctions().AddCapabilities().invoke(jvmti, capabilities);
        UnmanagedMemory.free(capabilities);
    }

    private void openInstrumentationModuleToAllOtherModules(JvmtiEnv11 jvmti, JNIEnvironment jni) {
        /*
         * JNI access from JVMTI is still limited by module visibility rules. Since a
         * ClassPrepareEvent can come from any thread that is executing code from any module, we
         * allow access to our module that contains the instrumentation code from any other module.
         */
        JNIObjectHandle moduleClass = handles().findClass(jni, "java/lang/Module");
        JNIMethodId moduleGetName = handles().getMethodId(jni, moduleClass, "getName", "()Ljava/lang/String;", false);

        try (CTypeConversion.CCharPointerHolder packageName = Support.toCString("org.graalvm.nativeimage.impl.clinit")) {
            CIntPointer moduleCountPtr = StackValue.get(CIntPointer.class);
            WordPointer modulesPtr = StackValue.get(WordPointer.class);
            check(jvmti.getFunctions().GetAllModules().invoke(jvmti, moduleCountPtr, modulesPtr));

            int moduleCount = moduleCountPtr.read();
            WordPointer modulesArrayPtr = modulesPtr.read();

            JNIObjectHandle clinitTrackingSupportModule = nullHandle();
            for (int i = 0; i < moduleCount; ++i) {
                JNIObjectHandle module = modulesArrayPtr.read(i);
                VMError.guarantee(module.notEqual(nullHandle()), "Unexpected null handle while iterating over modules.");

                JNIObjectHandle moduleName = Support.callObjectMethod(jni, module, moduleGetName);
                String name = Support.fromJniString(jni, moduleName);
                if (name != null && name.equals("org.graalvm.sdk")) {
                    clinitTrackingSupportModule = module;
                    break;
                }
            }

            VMError.guarantee(clinitTrackingSupportModule.notEqual(nullHandle()), "The the module name that provides clinit reporting support has changed.");
            for (int i = 0; i < moduleCount; ++i) {
                JNIObjectHandle module = modulesArrayPtr.read(i);
                check(jvmti.getFunctions().AddModuleOpens().invoke(jvmti, clinitTrackingSupportModule, packageName.get(), module));
            }

            jvmti.getFunctions().Deallocate().invoke(jvmti, modulesArrayPtr);
        }
    }

    /*
     * We cannot use the JNI getStaticMethodID call here as it would force the class to be
     * initialized.
     */
    private static List<MethodIdHolder> getClassMethodIdsWithName(JvmtiEnv jvmti, JNIObjectHandle clazz, String methodName) {
        List<MethodIdHolder> methodIds = new ArrayList<>();
        CIntPointer methodCountPtr = StackValue.get(CIntPointer.class);
        WordPointer methodsPtr = StackValue.get(WordPointer.class);

        check(jvmti.getFunctions().GetClassMethods().invoke(jvmti, clazz, methodCountPtr, methodsPtr));

        int methodCount = methodCountPtr.read();
        WordPointer methodsArray = methodsPtr.read();

        for (int i = 0; i < methodCount; ++i) {
            JNIMethodId methodId = methodsArray.read(i);
            String currentMethodName = Support.getMethodNameOr(methodId, "");

            if (currentMethodName.equals(methodName)) {
                methodIds.add(new MethodIdHolder(methodId));
            }
        }
        check(jvmti.getFunctions().Deallocate().invoke(jvmti, methodsPtr.read()));
        return methodIds;
    }

    private static JNIMethodId getClassClinitMethodIdOrNull(JvmtiEnv jvmti, JNIObjectHandle clazz) {
        List<MethodIdHolder> classMethodIdsWithName = getClassMethodIdsWithName(jvmti, clazz, "<clinit>");
        VMError.guarantee(classMethodIdsWithName.size() < 2);
        return classMethodIdsWithName.size() == 1 ? classMethodIdsWithName.get(0).methodId : WordFactory.nullPointer();
    }

    private static int getCurrentThreadStackFrameCount(JvmtiEnv jvmti) {
        CIntPointer countPointer = StackValue.get(CIntPointer.class);
        check(jvmti.getFunctions().GetFrameCount().invoke(jvmti, nullHandle(), countPointer));
        return countPointer.read();
    }

    private static long getFrameSourceLineNumber(JvmtiEnv jvmti, JvmtiFrameInfo frameInfo) {
        CIntPointer entryCountPointer = StackValue.get(CIntPointer.class);
        WordPointer lineEntryTablePointer = StackValue.get(WordPointer.class);
        JvmtiError errorCode = jvmti.getFunctions().GetLineNumberTable().invoke(jvmti, frameInfo.getMethod(), entryCountPointer, lineEntryTablePointer);
        if (errorCode == JvmtiError.JVMTI_ERROR_MUST_POSSESS_CAPABILITY || errorCode == JvmtiError.JVMTI_ERROR_ABSENT_INFORMATION) {
            return LINE_NUMBER_UNAVAILABLE;
        }
        check(errorCode);

        int entryCount = entryCountPointer.read();
        Pointer lineEntryTable = lineEntryTablePointer.read();
        VMError.guarantee(lineEntryTable.isNonNull());
        long previousLineNumber = LINE_NUMBER_UNAVAILABLE;
        for (int i = 0; i < entryCount; ++i) {
            JvmtiLineNumberEntry entry = (JvmtiLineNumberEntry) lineEntryTable.add(i * SizeOf.get(JvmtiLineNumberEntry.class));
            if (entry.getStartLocation() > frameInfo.getLocation()) {
                break;
            }
            previousLineNumber = entry.getLineNumber();
        }

        jvmti.getFunctions().Deallocate().invoke(jvmti, lineEntryTable);
        return previousLineNumber;
    }

    private static JNIObjectHandle getSourceFileName(JvmtiEnv jvmti, JNIEnvironment jni, JNIObjectHandle clazz) {
        CCharPointerPointer sourceFileNamePointer = StackValue.get(CCharPointerPointer.class);
        JvmtiError errorCode = jvmti.getFunctions().GetSourceFileName().invoke(jvmti, clazz, sourceFileNamePointer);
        if (errorCode == JvmtiError.JVMTI_ERROR_NONE) {
            String sourceFileName = Support.fromCString(sourceFileNamePointer.read());
            JNIObjectHandle sourceFileNameHandle = Support.toJniString(jni, sourceFileName);
            jvmti.getFunctions().Deallocate().invoke(jvmti, sourceFileNamePointer.read());
            return sourceFileNameHandle;
        } else {
            return nullHandle();
        }
    }

    private JNIObjectHandle getFrameStackTraceInfo(JvmtiEnv jvmti, JNIEnvironment jni, JvmtiFrameInfo frameInfo) {
        JNIObjectHandle declaringClass = Support.getMethodDeclaringClass(frameInfo.getMethod());

        String methodName = Support.getMethodNameOr(frameInfo.getMethod(), "");
        String className = Support.getClassNameOrNull(jni, declaringClass);

        JNIObjectHandle methodNameHandle = Support.toJniString(jni, methodName);
        JNIObjectHandle classNameHandle = Support.toJniString(jni, className);

        CCharPointer isNativePtr = StackValue.get(CCharPointer.class);

        JNIObjectHandle fileName = nullHandle();
        long lineNumber = LINE_NUMBER_UNAVAILABLE;
        JvmtiError errorCode = jvmti.getFunctions().IsMethodNative().invoke(jvmti, frameInfo.getMethod(), isNativePtr);
        if (errorCode == JvmtiError.JVMTI_ERROR_NONE && isNativePtr.read() == 0) {
            fileName = getSourceFileName(jvmti, jni, declaringClass);
            lineNumber = getFrameSourceLineNumber(jvmti, frameInfo);
        }
        return Support.newObjectLLLJ(jni, handles().javaLangStackTraceElement, handles().javaLangStackTraceElementCtor4, classNameHandle, methodNameHandle, fileName, lineNumber);
    }

    private JNIObjectHandle getCurrentThreadStackTrace(JvmtiEnv jvmti, JNIEnvironment jni) {
        int threadStackFrameCount = getCurrentThreadStackFrameCount(jvmti);
        int frameInfoSize = SizeOf.get(JvmtiFrameInfo.class);
        Pointer stackFramesPtr = UnmanagedMemory.malloc(frameInfoSize * threadStackFrameCount);
        CIntPointer readStackFramesPtr = StackValue.get(CIntPointer.class);
        check(jvmti.getFunctions().GetStackTrace().invoke(jvmti, nullHandle(), 0, threadStackFrameCount, (WordPointer) stackFramesPtr, readStackFramesPtr));
        VMError.guarantee(readStackFramesPtr.read() == threadStackFrameCount);

        NativeImageDiagnosticsAgent agent = singleton();
        JNIObjectHandle stackTrace = jni.getFunctions().getNewObjectArray().invoke(jni, threadStackFrameCount, agent.handles().javaLangStackTraceElement, nullHandle());

        for (int i = 0; i < threadStackFrameCount; ++i) {
            JvmtiFrameInfo frameInfo = (JvmtiFrameInfo) stackFramesPtr.add(i * frameInfoSize);
            JNIObjectHandle stackTraceElementHandle = getFrameStackTraceInfo(jvmti, jni, frameInfo);
            jni.getFunctions().getSetObjectArrayElement().invoke(jni, stackTrace, i, stackTraceElementHandle);
        }

        UnmanagedMemory.free(stackFramesPtr);
        return stackTrace;
    }

    private void reportClassInitialized(JNIEnvironment jni, JNIObjectHandle clazz, JNIObjectHandle stackTrace) {
        Support.callStaticVoidMethodLL(jni, handles().getClassInitializationTrackingClassHandle(), handles().getReportClassInitializedMethodId(), clazz, stackTrace);
    }

    private void reportObjectInstantiated(JNIEnvironment jni, JNIObjectHandle object, JNIObjectHandle stackTrace) {
        Support.callStaticVoidMethodLL(jni, handles().getClassInitializationTrackingClassHandle(), handles().getReportObjectInstantiatedMethodId(), object, stackTrace);
    }

    @CEntryPoint
    @CEntryPointOptions(prologue = AgentIsolate.Prologue.class)
    @SuppressWarnings("unused")
    private static void onClassPrepare(JvmtiEnv jvmti, JNIEnvironment jni,
                    JNIObjectHandle thread, JNIObjectHandle clazz) {
        NativeImageDiagnosticsAgent agent = singleton();
        agent.onClassPrepareCallback(jvmti, jni, clazz);
    }

    @CEntryPoint
    @CEntryPointOptions(prologue = AgentIsolate.Prologue.class)
    @SuppressWarnings("unused")
    private static void onBreakpoint(JvmtiEnv jvmti, JNIEnvironment jni, JNIObjectHandle thread, JNIMethodId method, long location) {
        NativeImageDiagnosticsAgent agent = singleton();
        agent.onBreakpointCallback(jvmti, jni, thread, method);
    }

    @Override
    protected int onUnloadCallback(JNIJavaVM vm) {
        return 0;
    }

    @Override
    protected void onVMStartCallback(JvmtiEnv jvmti, JNIEnvironment jni) {
    }

    @Override
    protected void onVMDeathCallback(JvmtiEnv jvmti, JNIEnvironment jni) {

    }

    @Override
    protected int getRequiredJvmtiVersion() {
        if (JavaVersionUtil.JAVA_SPEC > 8) {
            return JvmtiInterface.JVMTI_VERSION_9;
        }
        return JvmtiInterface.JVMTI_VERSION_1_2;
    }

    public static class RegistrationFeature implements Feature {

        @Override
        public void afterRegistration(AfterRegistrationAccess access) {
            JvmtiAgentBase.registerAgent(new NativeImageDiagnosticsAgent());
        }
    }
}
